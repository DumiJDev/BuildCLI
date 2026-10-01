package dev.buildcli.application;

import dev.buildcli.domain.Agent;
import dev.buildcli.domain.Attachment;
import dev.buildcli.domain.Chat;
import dev.buildcli.domain.Event;
import dev.buildcli.domain.Limits;
import dev.buildcli.domain.Task;
import dev.buildcli.domain.TaskStatus;
import dev.buildcli.domain.Roster;
import dev.buildcli.ports.ApprovalRequest;
import dev.buildcli.ports.ChatStore;
import dev.buildcli.ports.EscalationChoice;
import dev.buildcli.ports.UserInterface;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chats with agents that behave like people. Each agent has an inbox and works through it on its own
 * (virtual) thread, one conversation at a time: while it answers in one chat it does not answer in another, and what
 * arrives meanwhile waits. Different agents work in parallel. A handoff is a message to a teammate's inbox; the
 * delegating agent waits for the answer. Handoffs that would make two agents wait for each other are refused.
 *
 * <p>Everything the agents do shows up as messages in the chat ("thread") it belongs to: a group, or a direct
 * chat with one agent. A front end only draws {@link #messages()}, {@link #live(String)} and {@link #pending()}.
 * This class has no UI dependency; the TUI and the tests drive it through the same methods.
 */
public final class ChatSession implements UserInterface {

    /** CHANGES: the files a run wrote, shown as a card that can be reviewed and undone. */
    public enum Kind { USER, AGENT, ACTIVITY, SYSTEM, ERROR, CHANGES }

    /** User messages: queued (delivered, not read yet), running (read, being worked on), done, failed. Activity: running, done, failed. */
    public enum State { NONE, QUEUED, RUNNING, DONE, FAILED, UNDONE }

    /** The thread of the main group; direct chats are named after the agent they talk to. */
    public static final String MAIN = "";
    /** Your own private chat: notes to yourself, which no agent reads. */
    public static final String NOTES = "~notes";
    /** Local notes (command output, help) show in every thread. */
    public static final String EVERYWHERE = "*";

    /**
     * One entry of the conversation. {@code thread} says which chat it belongs to: {@link #MAIN}, an agent's name for
     * a direct chat, or {@link #EVERYWHERE}.
     */
    public record Message(long id, Kind kind, String author, String text, Instant at, State state, List<Attachment> attachments,
                          String thread) {
        Message withState(State s) {
            return new Message(id, kind, author, text, at, s, attachments, thread);
        }
    }

    /** Text an agent is still writing, and the chat it is writing in. */
    public record Live(String agent, String text, String thread) {}

    /** A question for the user. Several agents can ask at once; they are answered in order. */
    public sealed interface Pending {
        String agent();

        String thread();

        record Approval(ApprovalRequest request, CompletableFuture<Boolean> answer, String thread) implements Pending {
            @Override
            public String agent() {
                return request.agent();
            }
        }

        record Escalation(int taskId, String agent, String objective, String reason, CompletableFuture<EscalationChoice> answer,
                          String thread) implements Pending {}
    }

    /** Runs one request to completion on the addressed agent's thread. May block on the UI or on teammates. */
    public interface Executor {
        /** @param roster who is in the conversation, led by the agent that answers */
        Task execute(Roster roster, Orchestrator.Request request, UserInterface ui, BooleanSupplier cancelled, Orchestrator.Dispatcher dispatcher)
                throws Exception;
    }

    /**
     * One agent answering one message, with everything that happens for it (on any agent's thread). {@code hops}
     * counts messages agents sent each other since the user last wrote, so their conversation cannot run away.
     */
    private static final class Run {
        final long messageId;
        final String thread;
        final String me;
        final int hops;
        final AtomicBoolean stop = new AtomicBoolean();
        final Map<Integer, Task> tasks = new LinkedHashMap<>();
        final java.util.Set<String> handedOffTo = ConcurrentHashMap.newKeySet();
        /** Files written during this run, by its agent or by teammates it handed work to. */
        final List<dev.buildcli.domain.FileChange> changes = new CopyOnWriteArrayList<>();

        Run(long messageId, String thread, String me, int hops) {
            this.messageId = messageId;
            this.thread = thread;
            this.me = me;
            this.hops = hops;
        }
    }

    private record Job(Run run, Runnable body) {}

    /** An agent as a person: an inbox and one thread that handles one job at a time. */
    private final class Actor {
        final String name;
        final LinkedBlockingDeque<Job> inbox = new LinkedBlockingDeque<>();
        /** Jobs received and not finished yet; unlike the inbox it has no gap while a job is being picked up. */
        final AtomicInteger pending = new AtomicInteger();
        volatile Job current;
        volatile Thread thread;

        Actor(String name) {
            this.name = name;
        }

        synchronized void start() {
            if (thread == null && !closed) {
                thread = Thread.ofVirtual().name("agent-" + name).start(this::loop);
            }
        }

        void loop() {
            while (!closed) {
                Job job;
                try {
                    job = inbox.take();
                } catch (InterruptedException e) {
                    return;
                }
                current = job;
                touch();
                RUN.set(job.run());
                try {
                    job.body().run();
                } finally {
                    RUN.remove();
                    current = null;
                    state.put(name, "idle");
                    pending.decrementAndGet();
                    touch();
                }
            }
        }

        void send(Job job) {
            pending.incrementAndGet();
            inbox.add(job);
        }

        boolean busy() {
            return pending.get() > 0;
        }
    }

    private static final ThreadLocal<Run> RUN = new ThreadLocal<>();
        private static final int MAX_EVENTS = 300;
    private static final int MAX_RUNS = 50;

    private final Limits limits;
    private final Executor executor;
    private final ChatStore store;
    private final MessageStore transcript;
    /** "Always allow" answers: thread, agent and what, to what it means. In memory only: a grant never outlives the session. */
    private final Map<String, String> grants = new ConcurrentHashMap<>();
    private volatile java.nio.file.Path workspace;
    private volatile dev.buildcli.application.tools.WorkspaceLock workspaceLock;
    private final java.util.function.IntSupplier agentHops;
    private final ChatDirectory directory;
    /** For a user message sent to several agents: how many have not finished, and whether one failed. */
    private final Map<Long, AtomicInteger> openRuns = new ConcurrentHashMap<>();
    private final java.util.Set<Long> failedMessages = ConcurrentHashMap.newKeySet();
    private final Object lock = new Object();
    private final List<Event> events = new ArrayList<>();
    private final Map<String, Actor> actors = new java.util.concurrent.ConcurrentSkipListMap<>();
    private final Map<String, String> state = new ConcurrentHashMap<>();
    private final Map<String, StringBuilder> live = new LinkedHashMap<>();
    private final Map<String, String> liveThread = new HashMap<>();
    /** Who waits for whom because of a handoff: the graph in which a deadlock would be a cycle. */
    private final Map<String, String> waitsFor = new HashMap<>();
    private final List<Run> runs = new CopyOnWriteArrayList<>();
    private final List<Pending> pending = new CopyOnWriteArrayList<>();
    /** Goes up on every change a screen could show, so a front end redraws only when something changed. */
    private final AtomicLong version = new AtomicLong();
    private final AtomicInteger inputTokens = new AtomicInteger();
    private final AtomicInteger outputTokens = new AtomicInteger();
    private volatile boolean closed;

    /** A chat with one group made from a roster (its id is {@link #MAIN}), for tests. */
    public ChatSession(Roster roster, Executor executor) {
        this(roster, roster.agents(), executor, ChatStore.NONE, () -> 6);
    }

    /** A roster becomes the group {@link #MAIN}; the groups in {@code store} are added or override it. */
    public ChatSession(Roster roster, List<Agent> contacts, Executor executor, ChatStore store, java.util.function.IntSupplier agentHops) {
        this(roster, contacts, executor, store, agentHops, dev.buildcli.ports.ChatLog.NONE);
    }

    public ChatSession(Roster roster, List<Agent> contacts, Executor executor, ChatStore store, java.util.function.IntSupplier agentHops,
            dev.buildcli.ports.ChatLog log) {
        this(merge(roster.agents(), contacts), withStored(new Chat(MAIN, roster.name(), true, roster.agents().stream().map(Agent::name).toList(),
                List.of(roster.lead())), store.load()), roster.limits(), executor, store, agentHops, log);
    }

    /**
     * @param contacts  every agent the user can talk to; each gets an inbox, and a thread only once a message arrives
     * @param groups    the groups to start with
     * @param limits    limits for every run (steps, retries, tokens)
     * @param store     where groups are saved when they change
     * @param agentHops how many messages agents may send each other before they wait for the user
     * @param log       where the conversation is kept, so it is there again after a restart
     */
    public ChatSession(List<Agent> contacts, List<Chat> groups, Limits limits, Executor executor, ChatStore store,
            java.util.function.IntSupplier agentHops, dev.buildcli.ports.ChatLog log) {
        this.limits = limits;
        this.transcript = new MessageStore(log, this::touch);
        this.executor = executor;
        this.store = store;
        this.agentHops = agentHops;
        this.directory = new ChatDirectory(contacts, groups, store, this::touch, this::error, this::note);
        for (Agent a : directory.contacts()) {
            actors.put(a.name(), new Actor(a.name()));
            state.put(a.name(), "idle");
        }
    }

    private static List<Agent> merge(List<Agent> first, List<Agent> more) {
        Map<String, Agent> all = new LinkedHashMap<>();
        first.forEach(a -> all.put(a.name(), a));
        more.forEach(a -> all.putIfAbsent(a.name(), a));
        return List.copyOf(all.values());
    }

    private static List<Chat> withStored(Chat base, List<Chat> stored) {
        Map<String, Chat> all = new LinkedHashMap<>();
        all.put(base.id(), base);
        for (Chat g : stored) {
            all.put(g.id(), g.id().equals(base.id()) ? new Chat(g.id(), base.name(), true, g.members(), g.admins()) : g);
        }
        return List.copyOf(all.values());
    }

    // ---- contacts that come and go while the chat is open ----

    /** A new agent (created on the settings screen) becomes a contact at once. */
    public void addContact(Agent agent) {
        directory.addContact(agent);
        actors.computeIfAbsent(agent.name(), Actor::new);
        state.putIfAbsent(agent.name(), "idle");
    }

    /** A deleted agent leaves every group; what it already said stays in the chats. */
    public void removeContact(String name) {
        directory.removeContact(name);
        Actor a = actors.get(name);
        if (a != null && a.thread != null && a.current == null) {
            a.thread.interrupt();
        }
    }

    /** A number that changes whenever anything visible changes: messages, typing, presence, questions, groups. */
    public long version() {
        return version.get();
    }

    private void touch() {
        version.incrementAndGet();
    }

    // ---- what the user does ----

    public long submit(String text) {
        return submit(text, List.of());
    }

    public long submit(String text, List<Attachment> attachments) {
        return submit(text, attachments, null);
    }

    /**
     * Sends a message. In a group it goes to the lead, or to the agent it @mentions; in a direct chat
     * ({@code agent} not null) it goes to that agent unless it @mentions someone else. If the agent is busy, the
     * message waits in its inbox.
     *
     * @return the message id, or -1 if there was nothing to send
     */
    public long submit(String text, List<Attachment> attachments, String chat) {
        String clean = text == null ? "" : text.strip();
        if (clean.isEmpty() && attachments.isEmpty()) {
            return -1;
        }
        if (NOTES.equals(chat)) {
            long id = transcript.nextId();
            add(new Message(id, Kind.USER, "you", clean, Instant.now(), State.DONE, List.copyOf(attachments), NOTES));
            return id;
        }
        if (isAgentChat(chat)) {
            system("This chat is between " + String.join(" and ", agentChatMembers(chat)) + ". You can read it, not write in it: ask one of "
                    + "them in their own chat to write to the other.");
            return -1;
        }
        String thread = chat == null || (group(chat) == null && !directory.hasContact(chat)) ? defaultChat() : chat;
        if (thread == null) {
            error("There is nobody to talk to yet. Create an agent in Settings (F2) > Agents, or run 'buildcli init'.");
            return -1;
        }
        long id = transcript.nextId();
        add(new Message(id, Kind.USER, "you", clean, Instant.now(), State.QUEUED, List.copyOf(attachments), thread));
        route(id, clean, List.copyOf(attachments), thread);
        return id;
    }

    /**
     * Delivers a user message. In a direct chat it goes to that agent. In a group, every member it @mentions gets it
     * (they work in parallel); with no mention it goes to an admin, preferring one that is free.
     */
    private void route(long id, String text, List<Attachment> attachments, String thread) {
        Chat g = group(thread);
        List<String> targets;
        if (g == null) {
            directory.openDirect(thread);
            targets = List.of(thread);
        } else {
            List<String> mentioned = mentioned(text);
            List<String> outside = mentioned.stream().filter(m -> !g.has(m)).toList();
            if (!outside.isEmpty()) {
                note(thread, String.join(", ", outside) + (outside.size() == 1 ? " is" : " are") + " not in this group. Add "
                        + (outside.size() == 1 ? "them" : "them") + " from the group info to talk to them here.");
            }
            targets = mentioned.stream().filter(g::has).toList();
            if (targets.isEmpty()) {
                String admin = pickAdmin(g);
                if (admin == null) {
                    note(thread, "This group has no members. Add someone from the group info.");
                    replace(id, State.FAILED);
                    return;
                }
                targets = List.of(admin);
            }
        }
        openRuns.put(id, new AtomicInteger(targets.size()));
        failedMessages.remove(id);
        for (String t : targets) {
            enqueue(new Run(id, thread, t, 0), text, attachments, null);
        }
    }

    private String pickAdmin(Chat g) {
        List<String> candidates = g.admins().isEmpty() ? g.members() : g.admins();
        for (String a : candidates) {
            Actor actor = actors.get(a);
            if (actor != null && !actor.busy()) {
                return a;
            }
        }
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private void enqueue(Run run, String text, List<Attachment> attachments, String from) {
        Actor actor = actors.get(run.me);
        actor.send(new Job(run, () -> process(run, text, attachments, from)));
        actor.start();
    }

    /** Every contact @mentioned in the text, in order, once each. */
    public List<String> mentioned(String text) {
        return directory.mentioned(text);
    }

    // ---- chats: groups, members, admins, direct chats ----

    public Chat group(String id) {
        return directory.group(id);
    }

    public List<Chat> groups() {
        return directory.groups();
    }

    /** The chat to open first: the first group, else a direct chat with the first agent; null when there are no agents. */
    public String defaultChat() {
        return directory.defaultChat();
    }

    /** Every agent the user can talk to. */
    public List<Agent> contacts() {
        return directory.contacts();
    }

    public Agent contact(String name) {
        return directory.contact(name);
    }

    /** Direct chats that were opened or have messages. */
    public List<String> directChats() {
        return directory.directChats(threadsWithMessages());
    }

    private java.util.Set<String> threadsWithMessages() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (Message m : transcript.snapshot()) {
            out.add(m.thread());
        }
        return out;
    }

    /** A private chat between two agents, started by one of them when the user asked it to write to the other. */
    public static boolean isAgentChat(String thread) {
        return thread != null && thread.contains("~") && !thread.equals(NOTES);
    }

    public static String agentChatId(String a, String b) {
        return a.compareTo(b) < 0 ? a + "~" + b : b + "~" + a;
    }

    /** The two agents of a private chat between agents. */
    public static List<String> agentChatMembers(String thread) {
        int i = thread.indexOf('~');
        return List.of(thread.substring(0, i), thread.substring(i + 1));
    }

    /** The private chats between agents that have messages, in the order they started. */
    public List<String> agentChats() {
        return threadsWithMessages().stream().filter(ChatSession::isAgentChat).toList();
    }

    /** Whether {@code from} may write to {@code to}. Everyone may, until the user says otherwise. */
    public boolean canReach(String from, String to) {
        return directory.canReach(from, to);
    }

    public void setReach(String from, String to, boolean allowed) {
        directory.setReach(from, to, allowed);
    }

    /** Who cannot contact whom, as "bruno -> ana" lines. */
    public List<String> blockedPairs() {
        return directory.blockedPairs();
    }

    public void openDirect(String agent) {
        directory.openDirect(agent);
    }

    /** Agents in no group and with no direct chat: loaded, but nobody can reach them. */
    public List<String> idleContacts() {
        return directory.idleContacts(directChats());
    }

    /** @return the new group's id */
    public String createGroup(String name, List<String> members) {
        return directory.createGroup(name, members);
    }

    public void renameGroup(String id, String name) {
        directory.renameGroup(id, name);
    }

    /** The main group cannot be deleted. @return false if it was not deleted */
    public boolean deleteGroup(String id) {
        if (MAIN.equals(id)) {
            return false;
        }
        boolean removed = directory.removeGroup(id);
        if (removed) {
            clearChat(id);
        }
        return removed;
    }

    public void addMember(String id, String agent) {
        directory.addMember(id, agent);
    }

    /** Removing the last admin makes the next member admin, so a group always has someone to answer it. */
    public void removeMember(String id, String agent) {
        directory.removeMember(id, agent);
    }

    /** Makes a member an admin, or dismisses one. A group keeps at least one admin while it has members. */
    public void setAdmin(String id, String agent, boolean admin) {
        directory.setAdmin(id, agent, admin);
    }

    /** A note in one chat (who joined, who is not in the group). */
    private void note(String thread, String text) {
        add(new Message(transcript.nextId(), Kind.SYSTEM, "", text, Instant.now(), State.NONE, List.of(), thread));
    }

    /** Stops every run in {@code thread} (all runs if null) at its next step and answers their questions with "no". */
    public void stop(String thread) {
        for (Run r : runs) {
            if (thread == null || r.thread.equals(thread)) {
                r.stop.set(true);
            }
        }
        for (Pending p : pending) {
            if (thread == null || p.thread().equals(thread)) {
                answerNo(p);
            }
        }
    }

    public void stop() {
        stop(null);
    }

    private static void answerNo(Pending p) {
        if (p instanceof Pending.Approval a) {
            a.answer().complete(false);
        } else if (p instanceof Pending.Escalation e) {
            e.answer().complete(EscalationChoice.ABORT);
        }
    }

    /** Drops every user message that has not been read yet. @return how many were dropped */
    public int clearQueue() {
        int dropped = 0;
        for (Actor a : actors.values()) {
            List<Job> all = new ArrayList<>();
            a.inbox.drainTo(all);
            for (Job j : all) {
                Message m = j.run().messageId > 0 ? find(j.run().messageId) : null;
                if (m != null && m.state() == State.QUEUED) {
                    replace(m.id(), State.FAILED);
                    a.pending.decrementAndGet();
                    dropped++;
                } else {
                    a.inbox.add(j);
                }
            }
        }
        if (dropped > 0) {
            system("Dropped " + dropped + " waiting message(s).");
        }
        return dropped;
    }

    /** Sends a failed message again: the same message moves to the end of its chat. */
    public boolean retry(long messageId) {
        Message m = find(messageId);
        if (m == null || m.kind() != Kind.USER || m.state() != State.FAILED) {
            return false;
        }
        transcript.requeue(m);
        route(messageId, m.text(), m.attachments(), m.thread());
        return true;
    }

    /** The newest user message that failed, or -1. */
    public long lastFailedMessage() {
        return transcript.lastFailed();
    }

    /** Local notes (help, errors about a command) that are not part of the conversation with the agents. */
    public void system(String text) {
        add(new Message(transcript.nextId(), Kind.SYSTEM, "", text, Instant.now(), State.NONE, List.of(), threadNow()));
    }

    public void error(String text) {
        add(new Message(transcript.nextId(), Kind.ERROR, "", text, Instant.now(), State.NONE, List.of(), threadNow()));
    }

    /** Deletes one chat's messages, on screen and on disk, like "clear chat" in a messaging app. */
    public void clearChat(String thread) {
        String why = transcript.clearThread(thread);
        if (why != null) {
            error("Could not delete the saved messages: " + why);
        }
    }

    public void close() {
        closed = true;
        stop();
        transcript.close();
        for (Actor a : actors.values()) {
            Thread t = a.thread;
            if (t != null) {
                t.interrupt();
            }
        }
    }

    // ---- what the front end reads ----

    public List<Message> messages() {
        return transcript.snapshot();
    }

    /** What an agent is writing in {@code thread} right now, or null. */
    public Live live(String thread) {
        synchronized (live) {
            for (var e : live.entrySet()) {
                if (!e.getValue().isEmpty() && thread.equals(liveThread.get(e.getKey()))) {
                    return new Live(e.getKey(), e.getValue().toString(), thread);
                }
            }
        }
        return null;
    }

    /** The oldest open question, or null. */
    public Pending pending() {
        // one read of the list: checking isEmpty() and then get(0) fails when an answer removes the request in between
        for (Pending p : pending) {
            return p;
        }
        return null;
    }

    public int pendingCount() {
        return pending.size();
    }

    /** True while any agent has work. */
    public boolean busy() {
        for (Actor a : actors.values()) {
            if (a.busy()) {
                return true;
            }
        }
        return false;
    }

    /** True while an agent is working on something for {@code thread}. */
    public boolean isActive(String thread) {
        for (Actor a : actors.values()) {
            Job j = a.current;
            if (j != null && j.run().thread.equals(thread)) {
                return true;
            }
        }
        return false;
    }

    /** User messages that are delivered but not read yet. */
    public int queued() {
        int n = 0;
        for (Message m : messages()) {
            if (m.kind() == Kind.USER && m.state() == State.QUEUED) {
                n++;
            }
        }
        return n;
    }

    /**
     * What an agent is doing, the way a person's status reads: idle, reading, thinking, typing, working,
     * waiting for you, waiting for a teammate, waiting for the workspace.
     */
    public String agentState(String agent) {
        return state.getOrDefault(agent, "idle");
    }

    /** The chat an agent is busy in, or null when it is free. */
    public String agentThread(String agent) {
        Actor a = actors.get(agent);
        Job j = a == null ? null : a.current;
        return j == null ? null : j.run().thread;
    }

    /** Tasks of the runs in {@code thread} that are active, or else of the latest one. */
    public List<Task> tasks(String thread) {
        List<Task> out = new ArrayList<>();
        Run latest = null;
        for (Run r : runs) {
            if (!r.thread.equals(thread)) {
                continue;
            }
            latest = r;
            if (isRunning(r)) {
                synchronized (lock) {
                    out.addAll(r.tasks.values());
                }
            }
        }
        if (out.isEmpty() && latest != null) {
            synchronized (lock) {
                out.addAll(latest.tasks.values());
            }
        }
        return out;
    }

    private boolean isRunning(Run r) {
        for (Actor a : actors.values()) {
            Job j = a.current;
            if (j != null && j.run() == r) {
                return true;
            }
        }
        return false;
    }

    public List<Event> events() {
        synchronized (lock) {
            return List.copyOf(events);
        }
    }

    public int inputTokens() {
        return inputTokens.get();
    }

    public int outputTokens() {
        return outputTokens.get();
    }

    // ---- answering a message, on the addressed agent's thread ----

    private void process(Run run, String text, List<Attachment> attachments, String from) {
        runs.add(run);
        if (runs.size() > MAX_RUNS) {
            runs.remove(0);
        }
        String me = run.me;
        state.put(me, "reading");
        if (run.messageId > 0) {
            transcript.markRead(run.messageId);
        }
        String history = transcript.history(run.messageId, run.thread);
        int before = messages().size();
        state.put(me, "thinking");
        String request = from == null ? text : "Message from " + from + " in this chat:\n" + text;
        boolean ok = false;
        try {
            Task root = executor.execute(rosterFor(run.thread, me), new Orchestrator.Request(request, me, history, attachments, chatContext(run.thread, me)),
                    this, run.stop::get, dispatcher);
            flushLive(me);
            if (root.status == TaskStatus.DONE) {
                String said = addFinalIfMissing(me, root.result, before);
                ok = true;
                deliverMentions(run, said);
            } else {
                error("This request could not be finished: " + (root.result == null ? "no result" : root.result));
            }
        } catch (RunAborted e) {
            flushAll();
            system("Stopped: " + e.getMessage());
        } catch (Throwable t) {
            flushAll();
            error(Orchestrator.describe(t) + "\nCheck the provider with 'buildcli provider test <provider:model>' or 'buildcli doctor'. "
                    + (run.messageId > 0 ? "Your message is kept: press Retry or type /retry to send it again." : ""));
        }
        // a stopped or failed run may still have written files: they are shown and can be undone all the same
        postChanges(run);
        finishRun(run, ok);
    }

    private void postChanges(Run run) {
        if (run.changes.isEmpty()) {
            return;
        }
        List<dev.buildcli.domain.FileChange> files = List.copyOf(run.changes);
        List<String> paths = dev.buildcli.application.tools.FileChanges.net(files).stream().map(dev.buildcli.application.tools.FileChanges.Net::path).toList();
        long id = transcript.nextId();
        transcript.keepChanges(id, files);
        add(new Message(id, Kind.CHANGES, run.me, "Changed " + paths.size() + (paths.size() == 1 ? " file: " : " files: ") + String.join(", ", paths),
                Instant.now(), State.DONE, List.of(), run.thread));
    }

    /** Where undo writes, and the lock the agents share; without it undo is not offered. */
    public void workspace(java.nio.file.Path root, dev.buildcli.application.tools.WorkspaceLock lock) {
        this.workspace = root;
        this.workspaceLock = lock;
    }

    public boolean canUndo() {
        return workspace != null;
    }

    /** The files behind a changes card, oldest write first; empty if they were not kept. */
    public List<dev.buildcli.domain.FileChange> changes(long id) {
        return transcript.changes(id);
    }

    /**
     * Puts back the files of a changes card, except those changed since by you or another agent. The agents see in the
     * conversation that it was undone. @return what happened, in one line (also written in the chat)
     */
    public String undo(long id) {
        Message m = find(id);
        if (m == null || m.kind() != Kind.CHANGES) {
            return "Nothing to undo.";
        }
        if (m.state() == State.UNDONE) {
            return "Already undone.";
        }
        if (workspace == null) {
            return "Undo is not available here.";
        }
        List<dev.buildcli.domain.FileChange> files = changes(id);
        if (files.isEmpty()) {
            return "These changes were not kept, so they cannot be undone.";
        }
        String text;
        try {
            var r = dev.buildcli.application.tools.FileChanges.undo(workspace, workspaceLock, files);
            if (!r.restored().isEmpty() || !r.deleted().isEmpty()) {
                replace(id, State.UNDONE);
            }
            text = "Undid " + m.author() + "'s changes: " + r.summary() + ".";
        } catch (Exception e) {
            text = "Undo failed: " + e.getMessage();
        }
        note(m.thread(), text);
        return text;
    }

    /** The newest changes card of a chat that can still be undone, or -1. */
    public long lastChanges(String thread) {
        List<Message> all = messages();
        for (int i = all.size() - 1; i >= 0; i--) {
            Message m = all.get(i);
            if (m.kind() == Kind.CHANGES && m.thread().equals(thread) && m.state() == State.DONE) {
                return m.id();
            }
        }
        return -1;
    }

    /** A user message sent to several agents is done when all have answered, and failed if any failed. */
    private void finishRun(Run run, boolean ok) {
        if (run.messageId <= 0) {
            return;
        }
        if (!ok) {
            failedMessages.add(run.messageId);
        }
        AtomicInteger open = openRuns.get(run.messageId);
        if (open == null || open.decrementAndGet() <= 0) {
            openRuns.remove(run.messageId);
            replace(run.messageId, failedMessages.remove(run.messageId) ? State.FAILED : State.DONE);
        }
    }

    /** When an agent @mentions a teammate in the group, the teammate reads it and answers, like a person would. */
    private void deliverMentions(Run run, String said) {
        deliverMentions(run, run.thread, said);
    }

    private void deliverMentions(Run run, String thread, String said) {
        if (said == null || said.isBlank()) {
            return;
        }
        List<String> to;
        Chat g = group(thread);
        if (g != null) {
            to = mentioned(said).stream().filter(m -> !m.equals(run.me) && g.has(m) && !run.handedOffTo.contains(m)).toList();
        } else if (isAgentChat(thread)) {
            // in a private chat the other one reads what was said, until the agents have said enough to each other
            to = agentChatMembers(thread).stream().filter(m -> !m.equals(run.me) && directory.hasContact(m)).toList();
        } else {
            return;
        }
        for (String m : to) {
            if (!canReach(run.me, m)) {
                continue;
            }
            int limit = Math.max(0, agentHops.getAsInt());
            if (run.hops + 1 > limit) {
                note(thread, "The agents paused after " + limit + " messages among themselves. Write to them to keep going.");
                return;
            }
            enqueue(new Run(-1, thread, m, run.hops + 1), said, List.of(), run.me);
        }
    }

    /** Who is in the conversation, led by the agent that answers: group members, or everyone for a direct chat. */
    private Roster rosterFor(String thread, String me) {
        Chat g = group(thread);
        List<Agent> members = new ArrayList<>();
        members.add(directory.contact(me));
        for (String name : g != null ? g.members() : List.copyOf(directory.contactNames())) {
            Agent a = directory.contact(name);
            if (a != null && !a.name().equals(me)) {
                members.add(a);
            }
        }
        return new Roster(g != null ? g.name() : me, me, members, limits, dev.buildcli.domain.ModelRouting.unspecified());
    }

    private String chatContext(String thread, String me) {
        Chat g = group(thread);
        if (isAgentChat(thread)) {
            String other = agentChatMembers(thread).stream().filter(n -> !n.equals(me)).findFirst().orElse("a teammate");
            return "You are in a private chat with " + other + ", started because the user asked one of you to write to the other. The user can "
                    + "read it but cannot write in it. Answer " + other + " directly, briefly, and stop when there is nothing more to settle.";
        }
        if (g == null) {
            List<String> mine = groups().stream().filter(c -> c.has(me)).map(c -> "'" + c.name() + "'").toList();
            return "You are in a direct chat with the user."
                    + (mine.isEmpty() ? "" : " You are a member of the group" + (mine.size() == 1 ? " " : "s ") + String.join(", ", mine) + ".");
        }
        List<String> others = new ArrayList<>();
        for (String m : g.members()) {
            if (!m.equals(me)) {
                others.add(m + (g.isAdmin(m) ? " (admin)" : ""));
            }
        }
        return "You are " + me + (g.isAdmin(me) ? ", an admin," : "") + " in the group chat '" + g.name() + "' with "
                + (others.isEmpty() ? "" : String.join(", ", others) + " and ") + "the user.";
    }

    /** If the final report was not already shown as streamed text, show it. */
    /** @return what the agent finally said */
    private String addFinalIfMissing(String author, String result, int fromIndex) {
        if (result == null || result.isBlank()) {
            return result;
        }
        List<Message> all = messages();
        for (int i = all.size() - 1; i >= fromIndex && i >= 0; i--) {
            Message m = all.get(i);
            if (m.kind() == Kind.AGENT && m.author().equals(author) && m.text().strip().equals(result.strip())) {
                return result;
            }
        }
        add(new Message(transcript.nextId(), Kind.AGENT, author, result.strip(), Instant.now(), State.NONE, List.of(), threadNow()));
        return result;
    }

    // ---- handoffs: a message to a teammate's inbox ----

    private final Orchestrator.Dispatcher dispatcher = new Orchestrator.Dispatcher() {
        @Override
        public boolean sees(String from, String to) {
            return canReach(from, to);
        }

        @Override
        public String post(String from, String to, String text) {
            return postAs(from, to, text);
        }

        @Override
        public String refusal(String from, String to) {
            if (!canReach(from, to)) {
                return "you cannot contact " + to + ": the user has not given you contact with them. Do not try another way; tell the user you cannot";
            }
            synchronized (waitsFor) {
                for (String at = to; at != null; at = waitsFor.get(at)) {
                    if (at.equals(from)) {
                        return to + " is waiting for you to finish something, so they cannot take new work from you now. "
                                + "Do it yourself or report back.";
                    }
                }
            }
            return null;
        }

        @Override
        public String run(String from, String to, Supplier<String> work) {
            Actor target = actors.get(to);
            if (target == null) {
                return work.get();
            }
            Run run = RUN.get();
            CompletableFuture<String> answer = new CompletableFuture<>();
            synchronized (waitsFor) {
                waitsFor.put(from, to);
            }
            state.put(from, "waiting for " + to);
            try {
                target.send(new Job(run, () -> {
                    state.put(to, "thinking");
                    try {
                        answer.complete(work.get());
                    } catch (Throwable t) {
                        answer.completeExceptionally(t);
                    }
                }));
                target.start();
                return answer.join();
            } catch (CompletionException e) {
                if (e.getCause() instanceof RuntimeException r) {
                    throw r;
                }
                throw e;
            } finally {
                synchronized (waitsFor) {
                    waitsFor.remove(from);
                }
                state.put(from, "working");
            }
        }
    };

    /** An agent writes, as itself, in a group it belongs to or in a private chat with a teammate. Runs on that agent's thread. */
    private String postAs(String from, String to, String text) {
        Run run = RUN.get();
        Chat g = groups().stream().filter(c -> c.id().equalsIgnoreCase(to) || c.name().equalsIgnoreCase(to)).findFirst().orElse(null);
        if (g != null) {
            if (!g.has(from)) {
                return "ERROR: you are not a member of the group '" + g.name() + "', so you cannot write there";
            }
            add(new Message(transcript.nextId(), Kind.AGENT, from, text, Instant.now(), State.NONE, List.of(), g.id()));
            if (run != null) {
                deliverMentions(run, g.id(), text);
            }
            return "Posted in the group '" + g.name() + "'. Tell the user it is done; do not post it again.";
        }
        String other = directory.contactNames().stream().filter(n -> n.equalsIgnoreCase(to)).findFirst().orElse(null);
        if (other == null) {
            return "ERROR: there is no group or teammate called '" + to + "'";
        }
        if (other.equals(from)) {
            return "ERROR: you cannot write to yourself";
        }
        if (!canReach(from, other)) {
            return "ERROR: you cannot contact " + other + ": the user has not given you contact with them. Tell the user you cannot";
        }
        String thread = agentChatId(from, other);
        add(new Message(transcript.nextId(), Kind.AGENT, from, text, Instant.now(), State.NONE, List.of(), thread));
        int limit = Math.max(0, agentHops.getAsInt());
        int hops = run == null ? 0 : run.hops;
        if (hops + 1 > limit) {
            return "Sent to " + other + ", but the agents have already said as much to each other as allowed, so " + other + " will not answer now.";
        }
        enqueue(new Run(-1, thread, other, hops + 1), text, List.of(), from);
        return "Sent to " + other + " in your private chat, which the user can read. " + other + " answers there; tell the user it was sent.";
    }

    // ---- UserInterface: called by the orchestrator on the agents' threads ----

    @Override
    public void fileChanged(dev.buildcli.domain.FileChange change) {
        Run r = RUN.get();
        if (r != null) {
            r.changes.add(change);
        }
    }

    /**
     * The chat an agent is working for right now. Streaming callbacks arrive on the HTTP client's threads, where
     * {@link #RUN} is not set, so they ask by agent: an agent handles one job at a time, which makes this exact.
     */
    private String threadOf(String agent) {
        Actor a = actors.get(agent);
        Job j = a == null ? null : a.current;
        return j != null ? j.run().thread : threadNow();
    }

    private String threadNow() {
        Run r = RUN.get();
        return r == null ? EVERYWHERE : r.thread;
    }

    private static String grantId(String thread, ApprovalRequest r) {
        return thread + "\u0001" + r.agent() + "\u0001" + r.grantKey();
    }

    /** Answers yes to this request and to the same kind of request from this agent in this chat from now on. */
    public void approveAlways(Pending.Approval a) {
        ApprovalRequest r = a.request();
        if (r.grantKey() != null) {
            grants.put(grantId(a.thread(), r), r.grantLabel());
        }
        a.answer().complete(true);
    }

    /** What is being approved automatically in this chat. */
    public List<String> grants(String thread) {
        List<String> out = new ArrayList<>();
        grants.forEach((id, label) -> {
            if (id.startsWith(thread + "\u0001")) {
                out.add(label);
            }
        });
        java.util.Collections.sort(out);
        return out;
    }

    /** Asks again from now on. @return how many permissions were taken back */
    public int revokeGrants(String thread) {
        int before = grants.size();
        grants.keySet().removeIf(id -> id.startsWith(thread + "\u0001"));
        int n = before - grants.size();
        if (n > 0) {
            touch();
        }
        return n;
    }

    @Override
    public boolean approve(ApprovalRequest request) {
        Run run = RUN.get();
        if (run != null && run.stop.get()) {
            return false;
        }
        if (request.grantKey() != null && grants.containsKey(grantId(threadNow(), request))) {
            return true;
        }
        var answer = new CompletableFuture<Boolean>();
        Pending p = new Pending.Approval(request, answer, threadNow());
        pending.add(p);
        touch();
        state.put(request.agent(), "waiting for you");
        try {
            return answer.get();
        } catch (Exception e) {
            return false;
        } finally {
            pending.remove(p);
            touch();
            state.put(request.agent(), "working");
        }
    }

    @Override
    public EscalationChoice escalate(int taskId, String agent, String objective, String reason) {
        Run run = RUN.get();
        if (run != null && run.stop.get()) {
            return EscalationChoice.ABORT;
        }
        var answer = new CompletableFuture<EscalationChoice>();
        Pending p = new Pending.Escalation(taskId, agent, objective, reason, answer, threadNow());
        pending.add(p);
        touch();
        try {
            return answer.get();
        } catch (Exception e) {
            return EscalationChoice.ABORT;
        } finally {
            pending.remove(p);
        }
    }

    @Override
    public void onTaskChanged(Task t) {
        Run run = RUN.get();
        if (run == null) {
            return;
        }
        Task copy = new Task(t.id, t.parentId, t.from, t.to, t.objective, t.brief);
        copy.status = t.status;
        copy.tokens = t.tokens;
        copy.attempts = t.attempts;
        synchronized (lock) {
            run.tasks.put(t.id, copy);
        }
    }

    @Override
    public void onText(int taskId, String agent, String delta) {
        touch();
        synchronized (live) {
            live.computeIfAbsent(agent, k -> new StringBuilder()).append(delta);
            liveThread.put(agent, threadOf(agent));
        }
        state.put(agent, "typing");
    }

    @Override
    public void onEvent(Event e) {
        touch();
        synchronized (lock) {
            events.add(e);
            if (events.size() > MAX_EVENTS) {
                events.remove(0);
            }
        }
        switch (e.type()) {
            case "AgentInvoked" -> {
                inputTokens.addAndGet(e.inputTokens());
                outputTokens.addAndGet(e.outputTokens());
                state.put(e.agent(), "thinking");
            }
            case "AgentReplied", "TaskFailed", "TaskCompleted", "LimitReached", "TaskEscalated" -> flushLive(e.agent());
            default -> { }
        }
        switch (e.type()) {
            case "ToolCalled" -> {
                flushLive(e.agent());
                state.put(e.agent(), "working");
                if (!e.payload().startsWith("handoff")) {
                    activity(e.agent(), describeTool(e.payload()));
                }
            }
            case "ToolCompleted" -> completeActivity(e.agent(), e.payload());
            case "TaskCreated" -> {
                Task t = lookup(e.taskId());
                Run r = RUN.get();
                if (t != null && r != null && !t.from.equals("user")) {
                    r.handedOffTo.add(t.to);
                }
                if (t != null && !t.from.equals("user")) {
                    // a handoff reads like one teammate writing to another in the chat
                    add(new Message(transcript.nextId(), Kind.AGENT, t.from, "@" + t.to + " " + t.objective
                            + (t.brief == null || t.brief.isBlank() ? "" : "\n" + t.brief), Instant.now(), State.NONE, List.of(), threadNow()));
                }
            }
            case "WaitingForWorkspace" -> state.put(e.agent(), "waiting for the workspace");
            case "TaskFailed" -> system(e.agent() + " had a problem: " + e.payload());
            case "TaskRetried" -> system(e.agent() + " is trying again (" + e.payload() + ").");
            case "TaskEscalated" -> state.put(e.agent(), "needs you");
            case "TaskSkipped" -> system(e.agent() + "'s task was closed by you.");
            default -> { }
        }
    }

    private Task lookup(int id) {
        Run run = RUN.get();
        if (run == null) {
            return null;
        }
        synchronized (lock) {
            return run.tasks.get(id);
        }
    }

    // ---- building messages ----

    private void flushLive(String agent) {
        String text;
        String thread;
        synchronized (live) {
            StringBuilder sb = live.get(agent);
            if (sb == null || sb.isEmpty()) {
                return;
            }
            text = sb.toString().strip();
            thread = liveThread.getOrDefault(agent, threadNow());
            sb.setLength(0);
        }
        if (!text.isEmpty()) {
            add(new Message(transcript.nextId(), Kind.AGENT, agent, text, Instant.now(), State.NONE, List.of(), thread));
        }
    }

    private void flushAll() {
        List<String> agents;
        synchronized (live) {
            agents = new ArrayList<>(live.keySet());
        }
        agents.forEach(this::flushLive);
    }

    private static final Pattern PATH_ARG = Pattern.compile("path=([^,}]+)");
    private static final Pattern ARGV_ARG = Pattern.compile("argv=\\[([^\\]]*)\\]");

    /** A short human line for "write_file {path=x, content=...}" without dumping the content. */
    static String describeTool(String payload) {
        int sp = payload.indexOf(' ');
        String name = sp < 0 ? payload : payload.substring(0, sp);
        String args = sp < 0 ? "" : payload.substring(sp + 1);
        Matcher argv = ARGV_ARG.matcher(args);
        if (argv.find()) {
            return "ran " + argv.group(1).replace(",", "");
        }
        Matcher to = Pattern.compile("to=([^,}]+)").matcher(args);
        if (name.equals("send_message") && to.find()) {
            return "wrote to " + to.group(1).strip();
        }
        Matcher path = PATH_ARG.matcher(args);
        if (path.find()) {
            String verb = switch (name) {
                case "write_file" -> "wrote";
                case "read_file" -> "read";
                case "list_files" -> "listed";
                default -> name;
            };
            return verb + " " + path.group(1).strip();
        }
        return name + (args.isBlank() ? "" : " " + ToolRuntime.abbreviate(args, 120));
    }

    private void activity(String agent, String text) {
        add(new Message(transcript.nextId(), Kind.ACTIVITY, agent, text, Instant.now(), State.RUNNING, List.of(), threadOf(agent)));
    }

    private void completeActivity(String agent, String payload) {
        transcript.completeActivity(agent, payload);
    }

    private void add(Message m) {
        transcript.add(m);
    }

    private Message find(long id) {
        return transcript.find(id);
    }

    private void replace(long id, State state) {
        transcript.replace(id, state);
    }
}

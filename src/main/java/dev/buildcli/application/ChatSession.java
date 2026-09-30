package dev.buildcli.application;

import dev.buildcli.domain.Agent;
import dev.buildcli.domain.Attachment;
import dev.buildcli.domain.Chat;
import dev.buildcli.domain.Event;
import dev.buildcli.domain.Limits;
import dev.buildcli.domain.Task;
import dev.buildcli.domain.TaskStatus;
import dev.buildcli.domain.Team;
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
 * Chats with a team whose agents behave like people. Each agent has an inbox and works through it on its own
 * (virtual) thread, one conversation at a time: while it answers in one chat it does not answer in another, and what
 * arrives meanwhile waits. Different agents work in parallel. A handoff is a message to a teammate's inbox; the
 * delegating agent waits for the answer. Handoffs that would make two agents wait for each other are refused.
 *
 * <p>Everything the agents do shows up as messages in the chat ("thread") it belongs to: the team chat, or a direct
 * chat with one agent. A front end only draws {@link #messages()}, {@link #live(String)} and {@link #pending()}.
 * This class has no UI dependency; the TUI and the tests drive it through the same methods.
 */
public final class ChatSession implements UserInterface {

    public enum Kind { USER, AGENT, ACTIVITY, SYSTEM, ERROR }

    /** User messages: queued (delivered, not read yet), running (read, being worked on), done, failed. Activity: running, done, failed. */
    public enum State { NONE, QUEUED, RUNNING, DONE, FAILED }

    /** The thread of the team conversation; other threads are named after the agent they talk to. */
    public static final String TEAM = "";
    /** Local notes (command output, help) show in every thread. */
    public static final String EVERYWHERE = "*";

    /**
     * One entry of the conversation. {@code thread} says which chat it belongs to: {@link #TEAM}, an agent's name for
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
        /** @param team who is in the conversation, led by the agent that answers */
        Task execute(Team team, Orchestrator.Request request, UserInterface ui, BooleanSupplier cancelled, Orchestrator.Dispatcher dispatcher)
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
    private static final Pattern MENTION = Pattern.compile("(?<![\\w@])@([A-Za-z][A-Za-z0-9_-]*)");
    private static final int MAX_MESSAGES = 2000;
    private static final int MAX_EVENTS = 300;
    private static final int MAX_RUNS = 50;
    private static final int HISTORY_MESSAGES = 10;
    private static final int HISTORY_CHARS = 6000;

    private final Limits limits;
    private final Executor executor;
    private final ChatStore store;
    private final dev.buildcli.ports.ChatLog log;
    /** Messages waiting to be written: the UI never waits for the disk. */
    private final LinkedBlockingDeque<Message> writes = new LinkedBlockingDeque<>();
    private final Thread writer;
    /** Where each message sits in the conversation, for the history: it changes when a message moves down on being read. */
    private final Map<Long, Long> positions = new ConcurrentHashMap<>();
    private final AtomicLong nextPosition = new AtomicLong();
    private final java.util.function.IntSupplier agentHops;
    private final Map<String, Agent> contacts = new java.util.concurrent.ConcurrentSkipListMap<>();
    /** Groups by id; the team's own group has the id {@link #TEAM}. Guarded by {@code lock}. */
    private final Map<String, Chat> groups = new LinkedHashMap<>();
    private final java.util.Set<String> directs = new java.util.LinkedHashSet<>();
    /** For a user message sent to several agents: how many have not finished, and whether one failed. */
    private final Map<Long, AtomicInteger> openRuns = new ConcurrentHashMap<>();
    private final java.util.Set<Long> failedMessages = ConcurrentHashMap.newKeySet();
    private final Object lock = new Object();
    private final List<Message> messages = new ArrayList<>();
    private final List<Event> events = new ArrayList<>();
    private final Map<String, Actor> actors = new java.util.concurrent.ConcurrentSkipListMap<>();
    private final Map<String, String> state = new ConcurrentHashMap<>();
    private final Map<String, StringBuilder> live = new LinkedHashMap<>();
    private final Map<String, String> liveThread = new HashMap<>();
    /** Who waits for whom because of a handoff: the graph in which a deadlock would be a cycle. */
    private final Map<String, String> waitsFor = new HashMap<>();
    private final List<Run> runs = new CopyOnWriteArrayList<>();
    private final List<Pending> pending = new CopyOnWriteArrayList<>();
    private final AtomicLong ids = new AtomicLong();
    /** Goes up on every change a screen could show, so a front end redraws only when something changed. */
    private final AtomicLong version = new AtomicLong();
    private final AtomicInteger inputTokens = new AtomicInteger();
    private final AtomicInteger outputTokens = new AtomicInteger();
    private volatile boolean closed;

    /** A chat with one group made from a team (its id is {@link #TEAM}), for tests and the demo. */
    public ChatSession(Team team, Executor executor) {
        this(team, team.agents(), executor, ChatStore.NONE, () -> 6);
    }

    /** A team becomes the group {@link #TEAM}; the groups in {@code store} are added or override it. */
    public ChatSession(Team team, List<Agent> contacts, Executor executor, ChatStore store, java.util.function.IntSupplier agentHops) {
        this(team, contacts, executor, store, agentHops, dev.buildcli.ports.ChatLog.NONE);
    }

    public ChatSession(Team team, List<Agent> contacts, Executor executor, ChatStore store, java.util.function.IntSupplier agentHops,
            dev.buildcli.ports.ChatLog log) {
        this(merge(team.agents(), contacts), withStored(new Chat(TEAM, team.name(), true, team.agents().stream().map(Agent::name).toList(),
                List.of(team.lead())), store.load()), team.limits(), executor, store, agentHops, log);
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
        this.log = log;
        this.executor = executor;
        this.store = store;
        this.agentHops = agentHops;
        contacts.forEach(a -> this.contacts.putIfAbsent(a.name(), a));
        for (Agent a : this.contacts.values()) {
            actors.put(a.name(), new Actor(a.name()));
            state.put(a.name(), "idle");
        }
        for (Chat g : groups) {
            List<String> members = g.members().stream().filter(this.contacts::containsKey).distinct().toList();
            List<String> admins = g.admins().stream().filter(members::contains).toList();
            this.groups.put(g.id(), new Chat(g.id(), g.name(), true, members, admins.isEmpty() && !members.isEmpty() ? List.of(members.get(0)) : admins));
        }
        restore();
        this.writer = log == dev.buildcli.ports.ChatLog.NONE ? null : Thread.ofVirtual().name("chat-history").start(this::writeLoop);
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
        touch();
        contacts.put(agent.name(), agent);
        actors.computeIfAbsent(agent.name(), Actor::new);
        state.putIfAbsent(agent.name(), "idle");
    }

    /** A deleted agent leaves every group; what it already said stays in the chats. */
    public void removeContact(String name) {
        for (Chat g : groups()) {
            if (g.has(name)) {
                removeMember(g.id(), name);
            }
        }
        contacts.remove(name);
        Actor a = actors.get(name);
        if (a != null && a.thread != null && a.current == null) {
            a.thread.interrupt();
        }
    }

    /**
     * Loads the conversation kept from earlier. Work that was in progress when BuildCLI closed did not finish: those
     * messages come back as not sent, so they can be retried, and unfinished activity as failed.
     */
    private void restore() {
        List<dev.buildcli.domain.ChatEntry> saved;
        try {
            saved = log.recent(MAX_MESSAGES);
        } catch (RuntimeException e) {
            messages.add(new Message(ids.incrementAndGet(), Kind.ERROR, "", "Could not load the earlier messages: " + e.getMessage(),
                    Instant.now(), State.NONE, List.of(), EVERYWHERE));
            return;
        }
        long max = 0;
        for (dev.buildcli.domain.ChatEntry e : saved) {
            Kind kind;
            State st;
            try {
                kind = Kind.valueOf(e.kind());
                st = State.valueOf(e.state());
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            boolean interrupted = st == State.QUEUED || st == State.RUNNING;
            Message m = new Message(e.id(), kind, e.author(), e.text(), e.at(), interrupted ? State.FAILED : st, e.attachments(), e.thread());
            messages.add(m);
            positions.put(m.id(), e.position());
            if (interrupted) {
                writes.add(m);
            }
            max = Math.max(max, e.id());
            nextPosition.set(Math.max(nextPosition.get(), e.position()));
        }
        ids.set(max);
    }

    private void writeLoop() {
        while (true) {
            Message m;
            try {
                m = writes.take();
            } catch (InterruptedException e) {
                return;
            }
            write(m);
            if (closed && writes.isEmpty()) {
                return;
            }
        }
    }

    private void write(Message m) {
        try {
            // what is kept on disk is scrubbed of secrets, like the event log; the screen shows the original
            log.save(new dev.buildcli.domain.ChatEntry(m.id(), m.thread(), m.kind().name(), m.author(), Redactor.redact(m.text()), m.at(),
                    m.state().name(), m.attachments(), positions.getOrDefault(m.id(), 0L)));
        } catch (RuntimeException e) {
            // a full disk or a locked database must not break the chat; the message stays on screen
        }
    }

    /** A number that changes whenever anything visible changes: messages, typing, presence, questions, groups. */
    public long version() {
        return version.get();
    }

    private void touch() {
        version.incrementAndGet();
    }

    /** Keeps a new or changed message, except local notes (help, command errors) that belong to no chat. */
    private void persist(Message m) {
        touch();
        if (writer != null && !m.thread().equals(EVERYWHERE)) {
            writes.add(m);
        }
    }

    // ---- what the user does ----

    public long submit(String text) {
        return submit(text, List.of());
    }

    public long submit(String text, List<Attachment> attachments) {
        return submit(text, attachments, null);
    }

    /**
     * Sends a message. In the team chat it goes to the lead, or to the agent it @mentions; in a direct chat
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
        String thread = chat == null || (group(chat) == null && !contacts.containsKey(chat)) ? defaultChat() : chat;
        if (thread == null) {
            error("There is nobody to talk to yet. Create an agent in Settings (F2) > Agents, or run 'buildcli init'.");
            return -1;
        }
        long id = ids.incrementAndGet();
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
            synchronized (lock) {
                directs.add(thread);
            }
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

    /** The first @mention that names a contact, or null. */
    public String mentionedAgent(String text) {
        List<String> all = mentioned(text);
        return all.isEmpty() ? null : all.get(0);
    }

    /** Every contact @mentioned in the text, in order, once each. */
    public List<String> mentioned(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = MENTION.matcher(text);
        while (m.find()) {
            for (String name : contacts.keySet()) {
                if (name.equalsIgnoreCase(m.group(1)) && !out.contains(name)) {
                    out.add(name);
                }
            }
        }
        return out;
    }

    // ---- chats: groups, members, admins, direct chats ----

    public Chat group(String id) {
        synchronized (lock) {
            return groups.get(id);
        }
    }

    public List<Chat> groups() {
        synchronized (lock) {
            return List.copyOf(groups.values());
        }
    }

    /** The chat to open first: the first group, else a direct chat with the first agent; null when there are no agents. */
    public String defaultChat() {
        List<Chat> gs = groups();
        if (!gs.isEmpty()) {
            return gs.get(0).id();
        }
        return contacts.isEmpty() ? null : contacts.keySet().iterator().next();
    }

    /** Every agent the user can talk to. */
    public List<Agent> contacts() {
        return List.copyOf(contacts.values());
    }

    public Agent contact(String name) {
        return contacts.get(name);
    }

    /** Direct chats that were opened or have messages. */
    public List<String> directChats() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        synchronized (lock) {
            out.addAll(directs);
            for (Message m : messages) {
                if (contacts.containsKey(m.thread())) {
                    out.add(m.thread());
                }
            }
        }
        return List.copyOf(out);
    }

    public void openDirect(String agent) {
        touch();
        if (contacts.containsKey(agent)) {
            synchronized (lock) {
                directs.add(agent);
            }
        }
    }

    /** Agents in no group and with no direct chat: loaded, but nobody can reach them. */
    public List<String> idleContacts() {
        List<String> direct = directChats();
        List<String> out = new ArrayList<>();
        for (String name : contacts.keySet()) {
            boolean inGroup = groups().stream().anyMatch(g -> g.has(name));
            if (!inGroup && !direct.contains(name)) {
                out.add(name);
            }
        }
        return out;
    }

    /** @return the new group's id */
    public String createGroup(String name, List<String> members) {
        String clean = name == null || name.isBlank() ? "group" : name.strip();
        String base = "#" + clean.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        List<String> known = members.stream().filter(contacts::containsKey).distinct().toList();
        String id;
        synchronized (lock) {
            id = base;
            for (int i = 2; groups.containsKey(id); i++) {
                id = base + "-" + i;
            }
            groups.put(id, new Chat(id, clean, true, known, known.isEmpty() ? List.of() : List.of(known.get(0))));
        }
        saveGroups();
        return id;
    }

    public void renameGroup(String id, String name) {
        changeGroup(id, g -> new Chat(g.id(), name.strip(), true, g.members(), g.admins()));
    }

    /** The team's own group cannot be deleted. @return false if it was not deleted */
    public boolean deleteGroup(String id) {
        if (TEAM.equals(id)) {
            return false;
        }
        boolean removed;
        synchronized (lock) {
            removed = groups.remove(id) != null;
        }
        saveGroups();
        if (removed) {
            clearChat(id);
        }
        return removed;
    }

    public void addMember(String id, String agent) {
        if (!contacts.containsKey(agent)) {
            throw new IllegalArgumentException("no agent named " + agent);
        }
        changeGroup(id, g -> {
            if (g.has(agent)) {
                return g;
            }
            List<String> m = new ArrayList<>(g.members());
            m.add(agent);
            return g.withMembers(m, g.admins().isEmpty() ? List.of(agent) : g.admins());
        });
        note(id, agent + " was added");
    }

    /** Removing the last admin makes the next member admin, so a group always has someone to answer it. */
    public void removeMember(String id, String agent) {
        changeGroup(id, g -> {
            List<String> m = new ArrayList<>(g.members());
            m.remove(agent);
            List<String> a = new ArrayList<>(g.admins());
            a.remove(agent);
            if (a.isEmpty() && !m.isEmpty()) {
                a.add(m.get(0));
            }
            return g.withMembers(m, a);
        });
        note(id, agent + " was removed");
    }

    /** Makes a member an admin, or dismisses one. A group keeps at least one admin while it has members. */
    public void setAdmin(String id, String agent, boolean admin) {
        Chat before = group(id);
        if (before == null || !before.has(agent)) {
            throw new IllegalArgumentException(agent + " is not in this group");
        }
        if (!admin && before.admins().size() == 1 && before.isAdmin(agent)) {
            throw new IllegalArgumentException(agent + " is the only admin; make someone else admin first");
        }
        changeGroup(id, g -> {
            List<String> a = new ArrayList<>(g.admins());
            a.remove(agent);
            if (admin) {
                a.add(agent);
            }
            return g.withMembers(g.members(), a);
        });
        note(id, agent + (admin ? " is now an admin" : " is no longer an admin"));
    }

    private void changeGroup(String id, java.util.function.UnaryOperator<Chat> change) {
        synchronized (lock) {
            Chat g = groups.get(id);
            if (g == null) {
                throw new IllegalArgumentException("no such group");
            }
            groups.put(id, change.apply(g));
        }
        touch();
        saveGroups();
    }

    private void saveGroups() {
        try {
            store.save(groups());
        } catch (RuntimeException e) {
            error("Could not save the groups: " + e.getMessage());
        }
    }

    /** A note in one chat (who joined, who is not in the group). */
    private void note(String thread, String text) {
        add(new Message(ids.incrementAndGet(), Kind.SYSTEM, "", text, Instant.now(), State.NONE, List.of(), thread));
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
        synchronized (lock) {
            messages.removeIf(x -> x.id() == messageId);
            Message again = new Message(m.id(), m.kind(), m.author(), m.text(), Instant.now(), State.QUEUED, m.attachments(), m.thread());
            messages.add(again);
            positions.put(again.id(), nextPosition.incrementAndGet());
            persist(again);
        }
        route(messageId, m.text(), m.attachments(), m.thread());
        return true;
    }

    /** The newest user message that failed, or -1. */
    public long lastFailedMessage() {
        synchronized (lock) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                Message m = messages.get(i);
                if (m.kind() == Kind.USER && m.state() == State.FAILED) {
                    return m.id();
                }
            }
        }
        return -1;
    }

    /** Local notes (help, errors about a command) that are not part of the conversation with the team. */
    public void system(String text) {
        add(new Message(ids.incrementAndGet(), Kind.SYSTEM, "", text, Instant.now(), State.NONE, List.of(), threadNow()));
    }

    public void error(String text) {
        add(new Message(ids.incrementAndGet(), Kind.ERROR, "", text, Instant.now(), State.NONE, List.of(), threadNow()));
    }

    /** Clears every chat from the screen. The history on disk is kept; use {@link #clearChat} to delete a chat. */
    public void clearMessages() {
        synchronized (lock) {
            messages.clear();
        }
    }

    /** Deletes one chat's messages, on screen and on disk, like "clear chat" in a messaging app. */
    public void clearChat(String thread) {
        synchronized (lock) {
            messages.removeIf(m -> m.thread().equals(thread) || m.thread().equals(EVERYWHERE));
        }
        try {
            log.clear(thread);
        } catch (RuntimeException e) {
            error("Could not delete the saved messages: " + e.getMessage());
        }
    }

    public void close() {
        closed = true;
        stop();
        if (writer != null) {
            // write what is still queued, so the last messages are there next time
            List<Message> rest = new ArrayList<>();
            writes.drainTo(rest);
            writer.interrupt();
            rest.forEach(this::write);
        }
        for (Actor a : actors.values()) {
            Thread t = a.thread;
            if (t != null) {
                t.interrupt();
            }
        }
    }

    // ---- what the front end reads ----

    public List<Message> messages() {
        synchronized (lock) {
            return List.copyOf(messages);
        }
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
        return pending.isEmpty() ? null : pending.get(0);
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
            synchronized (lock) {
                // read now: the message moves to where the conversation is, as in a chat app
                for (int i = 0; i < messages.size(); i++) {
                    Message m = messages.get(i);
                    if (m.id() == run.messageId && m.state() == State.QUEUED) {
                        messages.remove(i);
                        Message read = m.withState(State.RUNNING);
                        messages.add(read);
                        positions.put(read.id(), nextPosition.incrementAndGet());
                        persist(read);
                        break;
                    }
                }
            }
        }
        String history = history(run.messageId, run.thread);
        int before = messages().size();
        state.put(me, "thinking");
        String request = from == null ? text : "Message from " + from + " in this chat:\n" + text;
        boolean ok = false;
        try {
            Task root = executor.execute(teamFor(run.thread, me), new Orchestrator.Request(request, me, history, attachments, chatContext(run.thread, me)),
                    this, run.stop::get, dispatcher);
            flushLive(me);
            if (root.status == TaskStatus.DONE) {
                String said = addFinalIfMissing(me, root.result, before);
                ok = true;
                deliverMentions(run, said);
            } else {
                error("The team could not finish this request: " + (root.result == null ? "no result" : root.result));
            }
        } catch (RunAborted e) {
            flushAll();
            system("Stopped: " + e.getMessage());
        } catch (Throwable t) {
            flushAll();
            error(Orchestrator.describe(t) + "\nCheck the provider with 'buildcli provider test <provider:model>' or 'buildcli doctor'. "
                    + (run.messageId > 0 ? "Your message is kept: press Retry or type /retry to send it again." : ""));
        }
        finishRun(run, ok);
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
        Chat g = group(run.thread);
        if (g == null || said == null || said.isBlank()) {
            return;
        }
        for (String m : mentioned(said)) {
            if (m.equals(run.me) || !g.has(m) || run.handedOffTo.contains(m)) {
                continue;
            }
            int limit = Math.max(0, agentHops.getAsInt());
            if (run.hops + 1 > limit) {
                note(run.thread, "The agents paused after " + limit + " messages among themselves. Write to them to keep going.");
                return;
            }
            enqueue(new Run(-1, run.thread, m, run.hops + 1), said, List.of(), run.me);
        }
    }

    /** Who is in the conversation, led by the agent that answers: group members, or everyone for a direct chat. */
    private Team teamFor(String thread, String me) {
        Chat g = group(thread);
        List<Agent> members = new ArrayList<>();
        members.add(contacts.get(me));
        for (String name : g != null ? g.members() : List.copyOf(contacts.keySet())) {
            Agent a = contacts.get(name);
            if (a != null && !a.name().equals(me)) {
                members.add(a);
            }
        }
        return new Team(g != null ? g.name() : me, me, members, limits, dev.buildcli.domain.ModelRouting.unspecified());
    }

    private String chatContext(String thread, String me) {
        Chat g = group(thread);
        if (g == null) {
            return "You are in a direct chat with the user.";
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
        add(new Message(ids.incrementAndGet(), Kind.AGENT, author, result.strip(), Instant.now(), State.NONE, List.of(), threadNow()));
        return result;
    }

    private String history(long exceptMessageId, String thread) {
        List<Message> all = messages();
        List<String> lines = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && lines.size() < HISTORY_MESSAGES; i--) {
            Message m = all.get(i);
            if (m.id() == exceptMessageId || (m.kind() != Kind.USER && m.kind() != Kind.AGENT) || !m.thread().equals(thread)) {
                continue;
            }
            if (m.kind() == Kind.USER && m.state() != State.DONE) {
                continue;
            }
            lines.add(0, (m.kind() == Kind.USER ? "user" : m.author()) + ": " + ToolRuntime.abbreviate(m.text(), 1200));
        }
        int skip = 0;
        while (skip < lines.size() && lines.stream().skip(skip).mapToInt(String::length).sum() > HISTORY_CHARS) {
            skip++;
        }
        StringBuilder sb = new StringBuilder();
        lines.stream().skip(skip).forEach(l -> sb.append(l).append('\n'));
        return sb.toString().strip();
    }

    // ---- handoffs: a message to a teammate's inbox ----

    private final Orchestrator.Dispatcher dispatcher = new Orchestrator.Dispatcher() {
        @Override
        public String refusal(String from, String to) {
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

    // ---- UserInterface: called by the orchestrator on the agents' threads ----

    private String threadNow() {
        Run r = RUN.get();
        return r == null ? EVERYWHERE : r.thread;
    }

    @Override
    public boolean approve(ApprovalRequest request) {
        Run run = RUN.get();
        if (run != null && run.stop.get()) {
            return false;
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
            liveThread.put(agent, threadNow());
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
                    add(new Message(ids.incrementAndGet(), Kind.AGENT, t.from, "@" + t.to + " " + t.objective
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
            add(new Message(ids.incrementAndGet(), Kind.AGENT, agent, text, Instant.now(), State.NONE, List.of(), thread));
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
        add(new Message(ids.incrementAndGet(), Kind.ACTIVITY, agent, text, Instant.now(), State.RUNNING, List.of(), threadNow()));
    }

    private void completeActivity(String agent, String payload) {
        boolean ok = payload.startsWith("ok");
        synchronized (lock) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                Message m = messages.get(i);
                if (m.kind() == Kind.ACTIVITY && m.author().equals(agent) && m.state() == State.RUNNING) {
                    Message done = new Message(m.id(), m.kind(), m.author(),
                            ok ? m.text() : m.text() + " (" + ToolRuntime.abbreviate(payload, 140) + ")", m.at(), ok ? State.DONE : State.FAILED,
                            m.attachments(), m.thread());
                    messages.set(i, done);
                    persist(done);
                    return;
                }
            }
        }
    }

    private void add(Message m) {
        synchronized (lock) {
            messages.add(m);
            positions.put(m.id(), nextPosition.incrementAndGet());
            if (messages.size() > MAX_MESSAGES) {
                messages.remove(0); // only from the screen: the history on disk keeps it
            }
            persist(m);
        }
    }

    private Message find(long id) {
        synchronized (lock) {
            return messages.stream().filter(m -> m.id() == id).findFirst().orElse(null);
        }
    }

    private void replace(long id, State s) {
        synchronized (lock) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i).id() == id) {
                    Message changed = messages.get(i).withState(s);
                    messages.set(i, changed);
                    persist(changed);
                    return;
                }
            }
        }
    }
}

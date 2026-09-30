package dev.buildcli.application;

import dev.buildcli.domain.Agent;
import dev.buildcli.domain.Attachment;
import dev.buildcli.domain.Event;
import dev.buildcli.domain.Task;
import dev.buildcli.domain.TaskStatus;
import dev.buildcli.domain.Team;
import dev.buildcli.ports.ApprovalRequest;
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
        Task execute(Orchestrator.Request request, UserInterface ui, BooleanSupplier cancelled, Orchestrator.Dispatcher dispatcher)
                throws Exception;
    }

    /** One run: a user message being answered, with everything that happens for it (on any agent's thread). */
    private static final class Run {
        final long messageId;
        final String thread;
        final AtomicBoolean stop = new AtomicBoolean();
        final Map<Integer, Task> tasks = new LinkedHashMap<>();

        Run(long messageId, String thread) {
            this.messageId = messageId;
            this.thread = thread;
        }
    }

    private record Job(Run run, Runnable body) {}

    /** An agent as a person: an inbox and one thread that handles one job at a time. */
    private final class Actor {
        final String name;
        final LinkedBlockingDeque<Job> inbox = new LinkedBlockingDeque<>();
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
                RUN.set(job.run());
                try {
                    job.body().run();
                } finally {
                    RUN.remove();
                    current = null;
                    state.put(name, "idle");
                }
            }
        }

        boolean busy() {
            return current != null || !inbox.isEmpty();
        }
    }

    private static final ThreadLocal<Run> RUN = new ThreadLocal<>();
    private static final Pattern MENTION = Pattern.compile("(?<![\\w@])@([A-Za-z][A-Za-z0-9_-]*)");
    private static final int MAX_MESSAGES = 2000;
    private static final int MAX_EVENTS = 300;
    private static final int MAX_RUNS = 50;
    private static final int HISTORY_MESSAGES = 10;
    private static final int HISTORY_CHARS = 6000;

    private final Team team;
    private final Executor executor;
    private final Object lock = new Object();
    private final List<Message> messages = new ArrayList<>();
    private final List<Event> events = new ArrayList<>();
    private final Map<String, Actor> actors = new LinkedHashMap<>();
    private final Map<String, String> state = new ConcurrentHashMap<>();
    private final Map<String, StringBuilder> live = new LinkedHashMap<>();
    private final Map<String, String> liveThread = new HashMap<>();
    /** Who waits for whom because of a handoff: the graph in which a deadlock would be a cycle. */
    private final Map<String, String> waitsFor = new HashMap<>();
    private final List<Run> runs = new CopyOnWriteArrayList<>();
    private final List<Pending> pending = new CopyOnWriteArrayList<>();
    private final AtomicLong ids = new AtomicLong();
    private final AtomicInteger inputTokens = new AtomicInteger();
    private final AtomicInteger outputTokens = new AtomicInteger();
    private volatile boolean closed;

    public ChatSession(Team team, Executor executor) {
        this.team = team;
        this.executor = executor;
        for (Agent a : team.agents()) {
            actors.put(a.name(), new Actor(a.name()));
            state.put(a.name(), "idle");
        }
    }

    public Team team() {
        return team;
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
    public long submit(String text, List<Attachment> attachments, String agent) {
        String clean = text == null ? "" : text.strip();
        if (clean.isEmpty() && attachments.isEmpty()) {
            return -1;
        }
        boolean direct = agent != null && team.agent(agent).isPresent();
        String mentioned = mentionedAgent(clean);
        String target = mentioned != null ? mentioned : direct ? agent : null;
        String thread = direct ? agent : TEAM;
        long id = ids.incrementAndGet();
        add(new Message(id, Kind.USER, "you", clean, Instant.now(), State.QUEUED, List.copyOf(attachments), thread));
        enqueue(id, clean, target, List.copyOf(attachments), thread);
        return id;
    }

    private void enqueue(long id, String text, String target, List<Attachment> attachments, String thread) {
        Actor actor = actors.get(target == null ? team.lead() : target);
        Run run = new Run(id, thread);
        actor.inbox.add(new Job(run, () -> process(run, text, target, attachments)));
        actor.start();
    }

    /** The first @mention that names an agent of this team, or null. */
    public String mentionedAgent(String text) {
        Matcher m = MENTION.matcher(text);
        while (m.find()) {
            for (Agent a : team.agents()) {
                if (a.name().equalsIgnoreCase(m.group(1))) {
                    return a.name();
                }
            }
        }
        return null;
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
        String mentioned = mentionedAgent(m.text());
        String target = mentioned != null ? mentioned : m.thread().equals(TEAM) ? null : m.thread();
        synchronized (lock) {
            messages.removeIf(x -> x.id() == messageId);
            messages.add(new Message(m.id(), m.kind(), m.author(), m.text(), Instant.now(), State.QUEUED, m.attachments(), m.thread()));
        }
        enqueue(messageId, m.text(), target, m.attachments(), m.thread());
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

    public void clearMessages() {
        synchronized (lock) {
            messages.clear();
        }
    }

    public void close() {
        closed = true;
        stop();
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

    private void process(Run run, String text, String target, List<Attachment> attachments) {
        runs.add(run);
        if (runs.size() > MAX_RUNS) {
            runs.remove(0);
        }
        String me = target == null ? team.lead() : target;
        state.put(me, "reading");
        synchronized (lock) {
            // read now: the message moves to where the conversation is, as in a chat app
            for (int i = 0; i < messages.size(); i++) {
                Message m = messages.get(i);
                if (m.id() == run.messageId) {
                    messages.remove(i);
                    messages.add(m.withState(State.RUNNING));
                    break;
                }
            }
        }
        String history = history(run.messageId, run.thread);
        int before = messages().size();
        try {
            Task root = executor.execute(new Orchestrator.Request(text, target, history, attachments), this, run.stop::get, dispatcher);
            flushLive(me);
            if (root.status == TaskStatus.DONE) {
                addFinalIfMissing(me, root.result, before);
                replace(run.messageId, State.DONE);
            } else {
                error("The team could not finish this request: " + (root.result == null ? "no result" : root.result));
                replace(run.messageId, State.FAILED);
            }
        } catch (RunAborted e) {
            flushAll();
            system("Stopped: " + e.getMessage());
            replace(run.messageId, State.FAILED);
        } catch (Throwable t) {
            flushAll();
            error(Orchestrator.describe(t) + "\nCheck the provider with 'buildcli provider test <provider:model>' or 'buildcli doctor'. "
                    + "Your message is kept: press Retry or type /retry to send it again.");
            replace(run.messageId, State.FAILED);
        }
    }

    /** If the final report was not already shown as streamed text, show it. */
    private void addFinalIfMissing(String author, String result, int fromIndex) {
        if (result == null || result.isBlank()) {
            return;
        }
        List<Message> all = messages();
        for (int i = all.size() - 1; i >= fromIndex && i >= 0; i--) {
            Message m = all.get(i);
            if (m.kind() == Kind.AGENT && m.author().equals(author) && m.text().strip().equals(result.strip())) {
                return;
            }
        }
        add(new Message(ids.incrementAndGet(), Kind.AGENT, author, result.strip(), Instant.now(), State.NONE, List.of(), threadNow()));
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
                target.inbox.add(new Job(run, () -> {
                    state.put(to, "reading");
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
        state.put(request.agent(), "waiting for you");
        try {
            return answer.get();
        } catch (Exception e) {
            return false;
        } finally {
            pending.remove(p);
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
        synchronized (live) {
            live.computeIfAbsent(agent, k -> new StringBuilder()).append(delta);
            liveThread.put(agent, threadNow());
        }
        state.put(agent, "typing");
    }

    @Override
    public void onEvent(Event e) {
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
                    messages.set(i, new Message(m.id(), m.kind(), m.author(),
                            ok ? m.text() : m.text() + " (" + ToolRuntime.abbreviate(payload, 140) + ")", m.at(), ok ? State.DONE : State.FAILED,
                            m.attachments(), m.thread()));
                    return;
                }
            }
        }
    }

    private void add(Message m) {
        synchronized (lock) {
            messages.add(m);
            if (messages.size() > MAX_MESSAGES) {
                messages.remove(0);
            }
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
                    messages.set(i, messages.get(i).withState(s));
                    return;
                }
            }
        }
    }
}

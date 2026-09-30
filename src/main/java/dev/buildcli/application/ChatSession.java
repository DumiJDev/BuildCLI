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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A conversation with a team. The user can send a message at any time: messages wait in a queue and the team works
 * through them one at a time (the runtime runs one agent at a time, so nothing overlaps). Everything the agents do
 * shows up as messages, so a front end only has to draw {@link #messages()}, {@link #live()} and {@link #pending()}.
 *
 * <p>Each message is its own run; the conversation so far is handed to the team as context. This class has no UI
 * dependency: the TUI, and the tests, both drive it through the same methods.
 */
public final class ChatSession implements UserInterface {

    public enum Kind { USER, AGENT, ACTIVITY, SYSTEM, ERROR }

    /** For user messages: queued, running, done, failed. For activity lines: running, done, failed. */
    public enum State { NONE, QUEUED, RUNNING, DONE, FAILED }

    /** The thread of the team conversation; other threads are named after the agent they talk to. */
    public static final String TEAM = "";
    /** Local notes (command output, help) show in every thread. */
    public static final String EVERYWHERE = "*";

    /**
     * One entry of the conversation. {@code thread} says which chat it belongs to: {@link #TEAM}, an agent's name for
     * a direct chat (a message addressed to that agent and everything its run produced), or {@link #EVERYWHERE}.
     */
    public record Message(long id, Kind kind, String author, String text, Instant at, State state, List<Attachment> attachments,
                          String thread) {
        Message withState(State s) {
            return new Message(id, kind, author, text, at, s, attachments, thread);
        }
    }

    /** Text an agent is still writing. */
    public record Live(String agent, String text, String thread) {}

    public sealed interface Pending {
        record Approval(ApprovalRequest request, CompletableFuture<Boolean> answer) implements Pending {}

        record Escalation(int taskId, String agent, String objective, String reason, CompletableFuture<EscalationChoice> answer)
                implements Pending {}
    }

    /** Runs one request to completion. Runs on the session's worker thread and may block on the UI. */
    public interface Executor {
        Task execute(Orchestrator.Request request, UserInterface ui, BooleanSupplier cancelled) throws Exception;
    }

    private record Submission(long messageId, String text, String target, List<Attachment> attachments) {}

    private static final Pattern MENTION = Pattern.compile("(?<![\\w@])@([A-Za-z][A-Za-z0-9_-]*)");
    private static final int MAX_MESSAGES = 2000;
    private static final int MAX_EVENTS = 300;
    private static final int HISTORY_MESSAGES = 10;
    private static final int HISTORY_CHARS = 6000;

    private final Team team;
    private final Executor executor;
    private final Object lock = new Object();
    private final List<Message> messages = new ArrayList<>();
    private final List<Event> events = new ArrayList<>();
    private final Map<Integer, Task> tasks = new LinkedHashMap<>();
    private final Map<String, String> agentState = new ConcurrentHashMap<>();
    private final LinkedBlockingDeque<Submission> queue = new LinkedBlockingDeque<>();
    private final AtomicLong ids = new AtomicLong();
    private final StringBuilder live = new StringBuilder();
    private String liveAgent = "";
    private volatile String runThread = TEAM;
    private Thread worker;
    private volatile boolean closed;
    private volatile boolean stopRequested;
    private volatile boolean busy;
    private volatile Pending pending;
    private volatile int inputTokens;
    private volatile int outputTokens;

    public ChatSession(Team team, Executor executor) {
        this.team = team;
        this.executor = executor;
        team.agents().forEach(a -> agentState.put(a.name(), "idle"));
    }

    public Team team() {
        return team;
    }

    // ---- what the user does ----

    /**
     * Sends a message. It is queued if the team is busy. A leading or inline {@code @name} addresses that agent directly.
     *
     * @return the message id, or -1 if there was nothing to send
     */
    public long submit(String text, List<Attachment> attachments) {
        return submit(text, attachments, null);
    }

    /** Sends a message in a direct chat: it goes to {@code agent} unless the text @mentions someone else. */
    public long submit(String text, List<Attachment> attachments, String agent) {
        String clean = text == null ? "" : text.strip();
        if (clean.isEmpty() && attachments.isEmpty()) {
            return -1;
        }
        String mentioned = mentionedAgent(clean);
        String target = mentioned != null ? mentioned : agent != null && team.agent(agent).isPresent() ? agent : null;
        long id = ids.incrementAndGet();
        boolean wait = busy || !queue.isEmpty();
        add(new Message(id, Kind.USER, "you", clean, Instant.now(), wait ? State.QUEUED : State.RUNNING, List.copyOf(attachments),
                target == null ? TEAM : target));
        queue.add(new Submission(id, clean, target, List.copyOf(attachments)));
        startWorker();
        return id;
    }

    public long submit(String text) {
        return submit(text, List.of());
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

    /** Stops the current run at its next step and answers any open question with "no". Queued messages stay queued. */
    public void stop() {
        stopRequested = true;
        Pending p = pending;
        if (p instanceof Pending.Approval a) {
            a.answer().complete(false);
        } else if (p instanceof Pending.Escalation e) {
            e.answer().complete(EscalationChoice.ABORT);
        }
    }

    /** Drops every message that has not started yet. @return how many were dropped */
    public int clearQueue() {
        List<Submission> dropped = new ArrayList<>();
        queue.drainTo(dropped);
        synchronized (lock) {
            for (Submission s : dropped) {
                replace(s.messageId(), State.FAILED);
            }
        }
        if (!dropped.isEmpty()) {
            system("Dropped " + dropped.size() + " queued message(s).");
        }
        return dropped.size();
    }

    /** Sends a failed message again, at the end of the queue. @return false if there is no such failed message */
    public boolean retry(long messageId) {
        Message m = find(messageId);
        if (m == null || m.kind() != Kind.USER || m.state() != State.FAILED) {
            return false;
        }
        synchronized (lock) {
            replace(messageId, busy || !queue.isEmpty() ? State.QUEUED : State.RUNNING);
        }
        queue.add(new Submission(messageId, m.text(), m.thread().equals(TEAM) ? mentionedAgent(m.text()) : m.thread(), m.attachments()));
        startWorker();
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
        if (worker != null) {
            worker.interrupt();
        }
    }

    // ---- what the front end reads ----

    public List<Message> messages() {
        synchronized (lock) {
            return List.copyOf(messages);
        }
    }

    /** The thread new messages from the team go to, or {@link #EVERYWHERE} for notes made outside a run. */
    private String threadNow() {
        return Thread.currentThread() == worker ? runThread : EVERYWHERE;
    }

    /** The thread the team is working in right now, or null when idle. */
    public String activeThread() {
        return busy ? runThread : null;
    }

    public Live live() {
        synchronized (live) {
            return live.isEmpty() ? null : new Live(liveAgent, live.toString(), runThread);
        }
    }

    public Pending pending() {
        return pending;
    }

    public boolean busy() {
        return busy;
    }

    public int queued() {
        return queue.size();
    }

    public String agentState(String agent) {
        return agentState.getOrDefault(agent, "idle");
    }

    public List<Task> tasks() {
        synchronized (lock) {
            return new ArrayList<>(tasks.values());
        }
    }

    public List<Event> events() {
        synchronized (lock) {
            return List.copyOf(events);
        }
    }

    public int inputTokens() {
        return inputTokens;
    }

    public int outputTokens() {
        return outputTokens;
    }

    // ---- the worker ----

    private synchronized void startWorker() {
        if (worker == null && !closed) {
            worker = Thread.ofPlatform().daemon().name("chat-worker").start(this::work);
        }
    }

    private void work() {
        while (!closed) {
            Submission s;
            try {
                s = queue.take();
            } catch (InterruptedException e) {
                return;
            }
            process(s);
        }
    }

    private void process(Submission s) {
        runThread = s.target() == null ? TEAM : s.target();
        busy = true;
        stopRequested = false;
        synchronized (lock) {
            tasks.clear();
            // a message that waited its turn moves to the end, where the conversation is now, like a chat app does
            for (int i = 0; i < messages.size(); i++) {
                Message m = messages.get(i);
                if (m.id() == s.messageId()) {
                    messages.remove(i);
                    messages.add(new Message(m.id(), m.kind(), m.author(), m.text(), m.state() == State.QUEUED ? Instant.now() : m.at(),
                            State.RUNNING, m.attachments(), m.thread()));
                    break;
                }
            }
        }
        String history = history(s.messageId(), runThread);
        String lead = s.target() == null ? team.lead() : s.target();
        int before = messages().size();
        try {
            Task root = executor.execute(new Orchestrator.Request(s.text(), s.target(), history, s.attachments()), this, () -> stopRequested);
            flushLive();
            if (root.status == TaskStatus.DONE) {
                addFinalIfMissing(lead, root.result, before);
                finish(s, State.DONE);
            } else {
                error("The team could not finish this request: " + (root.result == null ? "no result" : root.result));
                finish(s, State.FAILED);
            }
        } catch (RunAborted e) {
            flushLive();
            system("Stopped: " + e.getMessage());
            finish(s, State.FAILED);
        } catch (Throwable t) {
            flushLive();
            error(Orchestrator.describe(t) + "\nCheck the provider with 'buildcli provider test <provider:model>' or 'buildcli doctor'. "
                    + "Your message is kept: type /retry to send it again.");
            finish(s, State.FAILED);
        }
        agentState.replaceAll((k, v) -> "idle");
        pending = null;
        busy = false;
    }

    private void finish(Submission s, State state) {
        synchronized (lock) {
            replace(s.messageId(), state);
        }
    }

    /** If the lead's final report was not already shown as streamed text, show it. */
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
            if (m.kind() == Kind.USER && (m.state() == State.QUEUED || m.state() == State.RUNNING || m.state() == State.FAILED)) {
                continue;
            }
            lines.add(0, (m.kind() == Kind.USER ? "user" : m.author()) + ": " + ToolRuntime.abbreviate(m.text(), 1200));
        }
        StringBuilder sb = new StringBuilder();
        int skip = 0;
        while (skip < lines.size() && lines.stream().skip(skip).mapToInt(String::length).sum() > HISTORY_CHARS) {
            skip++;
        }
        lines.stream().skip(skip).forEach(l -> sb.append(l).append('\n'));
        return sb.toString().strip();
    }

    // ---- UserInterface: called by the orchestrator on the worker thread ----

    @Override
    public boolean approve(ApprovalRequest request) {
        var answer = new CompletableFuture<Boolean>();
        if (stopRequested) {
            return false;
        }
        pending = new Pending.Approval(request, answer);
        try {
            return answer.get();
        } catch (Exception e) {
            return false;
        } finally {
            pending = null;
        }
    }

    @Override
    public EscalationChoice escalate(int taskId, String agent, String objective, String reason) {
        var answer = new CompletableFuture<EscalationChoice>();
        if (stopRequested) {
            return EscalationChoice.ABORT;
        }
        pending = new Pending.Escalation(taskId, agent, objective, reason, answer);
        try {
            return answer.get();
        } catch (Exception e) {
            return EscalationChoice.ABORT;
        } finally {
            pending = null;
        }
    }

    @Override
    public void onTaskChanged(Task t) {
        Task copy = new Task(t.id, t.parentId, t.from, t.to, t.objective, t.brief);
        copy.status = t.status;
        copy.tokens = t.tokens;
        copy.attempts = t.attempts;
        synchronized (lock) {
            tasks.put(t.id, copy);
        }
    }

    @Override
    public void onText(int taskId, String agent, String delta) {
        synchronized (live) {
            if (!agent.equals(liveAgent)) {
                flushLiveLocked();
                liveAgent = agent;
            }
            live.append(delta);
        }
        agentState.put(agent, "typing");
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
                inputTokens += e.inputTokens();
                outputTokens += e.outputTokens();
                agentState.put(e.agent(), "thinking");
            }
            case "AgentReplied", "TaskFailed", "TaskCompleted", "LimitReached", "TaskEscalated" -> flushLive();
            default -> { }
        }
        switch (e.type()) {
            case "ToolCalled" -> {
                flushLive();
                agentState.put(e.agent(), "working");
                if (!e.payload().startsWith("handoff")) {
                    activity(e.agent(), describeTool(e.payload()));
                }
            }
            case "ToolCompleted" -> completeActivity(e.agent(), e.payload());
            case "TaskCreated" -> {
                Task t = lookup(e.taskId());
                if (t != null && !t.from.equals("user")) {
                    activity(t.from, "handed off to " + t.to + ": " + ToolRuntime.abbreviate(t.objective, 160));
                }
            }
            case "TaskFailed" -> system(e.agent() + " had a problem: " + e.payload());
            case "TaskRetried" -> system(e.agent() + " is trying again (" + e.payload() + ").");
            case "TaskEscalated" -> agentState.put(e.agent(), "needs you");
            case "ApprovalRequested" -> agentState.put(e.agent(), "waiting for you");
            case "ApprovalGranted", "ApprovalDenied" -> agentState.put(e.agent(), "working");
            case "TaskSkipped" -> system(e.agent() + "'s task was closed by you.");
            default -> { }
        }
    }

    private Task lookup(int id) {
        synchronized (lock) {
            return tasks.get(id);
        }
    }

    // ---- building messages ----

    private void flushLive() {
        synchronized (live) {
            flushLiveLocked();
        }
    }

    private void flushLiveLocked() {
        String text = live.toString().strip();
        if (!text.isEmpty() && !liveAgent.isEmpty()) {
            add(new Message(ids.incrementAndGet(), Kind.AGENT, liveAgent, text, Instant.now(), State.NONE, List.of(), threadNow()));
        }
        live.setLength(0);
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

    private void replace(long id, State state) {
        synchronized (lock) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i).id() == id) {
                    messages.set(i, messages.get(i).withState(state));
                    return;
                }
            }
        }
    }
}

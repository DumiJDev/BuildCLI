package dev.buildcli.infrastructure;

import static dev.tamboui.toolkit.Toolkit.column;
import static dev.tamboui.toolkit.Toolkit.dialog;
import static dev.tamboui.toolkit.Toolkit.panel;
import static dev.tamboui.toolkit.Toolkit.row;
import static dev.tamboui.toolkit.Toolkit.stack;
import static dev.tamboui.toolkit.Toolkit.text;
import static dev.tamboui.toolkit.Toolkit.textInput;

import dev.buildcli.domain.Agent;
import dev.buildcli.domain.Event;
import dev.buildcli.domain.Task;
import dev.buildcli.domain.Team;
import dev.buildcli.ports.ApprovalRequest;
import dev.buildcli.ports.EscalationChoice;
import dev.buildcli.ports.UserInterface;
import dev.tamboui.layout.ContentAlignment;
import dev.tamboui.toolkit.app.ToolkitApp;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.event.KeyEvent;
import dev.tamboui.widgets.input.TextInputState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The BuildCLI terminal UI (TamboUI): a team panel, the task/handoff tree, the event log, live agent output, approvals
 * with diffs, escalation and a usage status bar. The orchestrator runs on a worker thread; blocking calls
 * (approve/escalate) park it on a future that a key press completes on the render thread.
 */
public final class TamboUiApp extends ToolkitApp implements UserInterface {

    /** The work to run once there is a request. Runs on a worker thread; may block on the UI. */
    public interface Job {
        void run(String request, UserInterface ui) throws Exception;
    }

    private sealed interface Pending {
        record Approval(ApprovalRequest request, CompletableFuture<Boolean> answer) implements Pending {}

        record Escalation(int taskId, String agent, String objective, String reason,
                          CompletableFuture<EscalationChoice> answer) implements Pending {}
    }

    private enum Phase { INPUT, RUNNING, FINISHED }

    private static final int LIVE_CHARS = 400;
    private static final int DIFF_LINES = 18;
    private static final int LOG_LINES = 200;

    private final Team team;
    private final Map<String, String> models;
    private final Job job;
    private final TextInputState input = new TextInputState();
    private final List<String> log = new CopyOnWriteArrayList<>();
    private final Map<Integer, Task> tasks = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, String> agentState = new java.util.concurrent.ConcurrentHashMap<>();
    private final StringBuilder live = new StringBuilder();
    private volatile String liveAgent = "";
    private volatile Pending pending;
    private volatile Phase phase;
    private volatile String outcome = "";
    private volatile int inputTokens;
    private volatile int outputTokens;

    /**
     * @param models         agent name to a short "provider/model" label, for the team panel
     * @param initialRequest the request to run immediately, or null to ask for it first
     */
    public TamboUiApp(Team team, Map<String, String> models, String initialRequest, Job job) {
        this.team = team;
        this.models = models;
        this.job = job;
        this.phase = initialRequest == null ? Phase.INPUT : Phase.RUNNING;
        this.initialRequest = initialRequest;
        team.agents().forEach(a -> agentState.put(a.name(), "idle"));
    }

    private final String initialRequest;

    @Override
    protected TuiConfig configure() {
        return TuiConfig.builder().tickRate(Duration.ofMillis(100)).build();
    }

    @Override
    protected void onStart() {
        if (initialRequest != null) {
            start(initialRequest);
        } else {
            runner().focusManager().setFocus("request");
        }
    }

    private void start(String request) {
        phase = Phase.RUNNING;
        Thread.ofPlatform().daemon().name("orchestrator").start(() -> {
            try {
                job.run(request, this);
                outcome = outcome.isEmpty() ? "finished" : outcome;
            } catch (Throwable t) {
                outcome = "stopped: " + t.getMessage();
            } finally {
                phase = Phase.FINISHED;
            }
        });
    }

    /** Lets the caller show the result line in the status bar (for example the final report). */
    public void setOutcome(String text) {
        outcome = text;
    }

    // ---- UserInterface (called from the orchestrator thread) ----

    @Override
    public boolean approve(ApprovalRequest request) {
        var answer = new CompletableFuture<Boolean>();
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
        // a snapshot: the orchestrator mutates the live object
        Task copy = new Task(t.id, t.parentId, t.from, t.to, t.objective, t.brief);
        copy.status = t.status;
        copy.tokens = t.tokens;
        copy.attempts = t.attempts;
        tasks.put(t.id, copy);
    }

    @Override
    public void onText(int taskId, String agent, String delta) {
        synchronized (live) {
            if (!agent.equals(liveAgent)) {
                live.setLength(0);
                liveAgent = agent;
            }
            live.append(delta);
            if (live.length() > LIVE_CHARS) {
                live.delete(0, live.length() - LIVE_CHARS);
            }
        }
    }

    private void clearLive() {
        synchronized (live) {
            live.setLength(0);
            liveAgent = "";
        }
    }

    @Override
    public void onEvent(Event e) {
        if (e.type().equals("AgentReplied") || e.type().equals("ToolCalled")) {
            clearLive();
        }
        log.add(String.format("%-17s #%d %-6s %s", e.type(), e.taskId(), e.agent(), e.payload().replace('\n', ' ')));
        if (log.size() > LOG_LINES) {
            log.remove(0);
        }
        switch (e.type()) {
            case "AgentInvoked" -> {
                inputTokens += e.inputTokens();
                outputTokens += e.outputTokens();
                agentState.put(e.agent(), "working");
            }
            case "ApprovalRequested" -> agentState.put(e.agent(), "waiting for you");
            case "ApprovalGranted", "ApprovalDenied" -> agentState.put(e.agent(), "working");
            case "TaskCompleted", "TaskSkipped" -> agentState.put(e.agent(), "idle");
            case "TaskEscalated" -> agentState.put(e.agent(), "needs you");
            default -> { }
        }
    }

    // ---- keys (render thread) ----

    private EventResult onKey(KeyEvent key) {
        if (key.isCtrlC()) {
            quit();
            return EventResult.HANDLED;
        }
        Pending p = pending;
        if (p instanceof Pending.Approval a) {
            if (key.isCharIgnoreCase('y') || key.isCharIgnoreCase('n')) {
                a.answer().complete(key.isCharIgnoreCase('y'));
                return EventResult.HANDLED;
            }
        } else if (p instanceof Pending.Escalation esc) {
            EscalationChoice choice = null;
            if (key.isCharIgnoreCase('r')) {
                choice = EscalationChoice.RETRY;
            } else if (key.isCharIgnoreCase('s')) {
                choice = EscalationChoice.SKIP;
            } else if (key.isCharIgnoreCase('a')) {
                choice = EscalationChoice.ABORT;
            }
            if (choice != null) {
                esc.answer().complete(choice);
                return EventResult.HANDLED;
            }
        } else if (phase != Phase.INPUT && key.isCharIgnoreCase('q')) {
            quit();
            return EventResult.HANDLED;
        }
        return EventResult.UNHANDLED;
    }

    private void submitRequest() {
        String request = input.text().strip();
        if (!request.isEmpty() && phase == Phase.INPUT) {
            start(request);
        }
    }

    // ---- rendering (render thread) ----

    @Override
    protected Element render() {
        Element body = column(
                row(
                        column(teamPanel(), tasksPanel()).percent(42),
                        panel("Events", tail(log, 40).stream().map(l -> (Element) text(l)).toArray(Element[]::new)).rounded().fill()
                ).fill(),
                panel(liveTitle(), text(liveText()).dim()).rounded().length(4),
                bottomBar());

        Element view = body;
        Pending p = pending;
        if (p instanceof Pending.Approval a) {
            view = stack(body, approvalDialog(a.request())).alignment(ContentAlignment.CENTER);
        } else if (p instanceof Pending.Escalation esc) {
            view = stack(body, dialog("Task #" + esc.taskId() + " needs you (" + esc.agent() + ")",
                    text(esc.objective()), text("Failed after the automatic retries: " + esc.reason()).red(),
                    text("[r] retry   [s] skip this task   [a] abort the run").dim()).width(76).rounded())
                    .alignment(ContentAlignment.CENTER);
        }
        return column(view).id("root").focusable().onKeyEvent(this::onKey);
    }

    private Element teamPanel() {
        List<Element> lines = new ArrayList<>();
        for (Agent a : team.agents()) {
            String state = agentState.getOrDefault(a.name(), "idle");
            var line = text((state.equals("idle") ? "○ " : "● ") + a.name() + (a.name().equals(team.lead()) ? " (lead)" : "")
                    + "  " + a.role() + "  " + models.getOrDefault(a.name(), "") + "  " + state);
            lines.add(switch (state) {
                case "working" -> line.cyan();
                case "waiting for you", "needs you" -> line.yellow();
                default -> line.dim();
            });
        }
        return panel("Team: " + team.name(), lines.toArray(new Element[0])).rounded().length(team.agents().size() + 2);
    }

    private Element tasksPanel() {
        List<Element> lines = new ArrayList<>();
        List<Task> snapshot;
        synchronized (tasks) {
            snapshot = new ArrayList<>(tasks.values());
        }
        for (Task t : snapshot) {
            String indent = "  ".repeat(depth(t, snapshot));
            var line = text(indent + "#" + t.id + " " + t.status + "  " + t.from + " -> " + t.to + "  " + t.objective);
            lines.add(switch (t.status) {
                case DONE -> line.green();
                case ESCALATED, FAILED -> line.red();
                case WAITING_APPROVAL -> line.yellow();
                default -> line;
            });
        }
        return panel("Tasks", lines.toArray(new Element[0])).rounded().fill();
    }

    private static int depth(Task t, List<Task> all) {
        int d = 0;
        Integer parent = t.parentId;
        while (parent != null && d < 10) {
            int id = parent;
            Task up = all.stream().filter(x -> x.id == id).findFirst().orElse(null); // not map(): the root's parentId is null
            parent = up == null ? null : up.parentId;
            d++;
        }
        return d;
    }

    private Element approvalDialog(ApprovalRequest r) {
        List<Element> body = new ArrayList<>();
        body.add(text(r.summary()).bold());
        List<String> lines = r.detail().lines().toList();
        lines.stream().limit(DIFF_LINES).forEach(l -> body.add(diffLine(l)));
        if (lines.size() > DIFF_LINES) {
            body.add(text("... " + (lines.size() - DIFF_LINES) + " more lines").dim());
        }
        body.add(text("[y] approve   [n] deny").dim());
        return dialog("Approval requested by " + r.agent(), body.toArray(new Element[0])).width(100).rounded();
    }

    private static Element diffLine(String l) {
        var t = text(l);
        if (l.startsWith("+++") || l.startsWith("---")) {
            return t.bold();
        }
        if (l.startsWith("@@")) {
            return t.cyan();
        }
        if (l.startsWith("+")) {
            return t.green();
        }
        if (l.startsWith("-")) {
            return t.red();
        }
        return t;
    }

    private Element bottomBar() {
        if (phase == Phase.INPUT) {
            return textInput(input).id("request").focusable().rounded().title("What should the '" + team.name() + "' team do?  (Enter to run, Ctrl+C to quit)")
                    .onSubmit(this::submitRequest).length(3);
        }
        String state = phase == Phase.FINISHED ? "done" : "running";
        return panel(text(String.format("%s   tokens in/out: %d/%d   %s%s", state, inputTokens, outputTokens,
                outcome.isEmpty() ? "" : outcome + "   ", phase == Phase.FINISHED ? "[q] quit" : "")).dim()).rounded().length(3);
    }

    private String liveTitle() {
        return liveAgent.isEmpty() ? "Live" : "Live: " + liveAgent;
    }

    private String liveText() {
        synchronized (live) {
            return live.toString().replace('\n', ' ');
        }
    }

    private static <T> List<T> tail(List<T> list, int n) {
        return new ArrayList<>(list.subList(Math.max(0, list.size() - n), list.size()));
    }
}

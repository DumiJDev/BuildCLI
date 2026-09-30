package dev.buildcli.infrastructure;

import static dev.tamboui.toolkit.Toolkit.*;

import dev.buildcli.domain.Event;
import dev.buildcli.ports.ApprovalRequest;
import dev.buildcli.ports.EscalationChoice;
import dev.buildcli.ports.UserInterface;
import dev.tamboui.layout.ContentAlignment;
import dev.tamboui.toolkit.app.ToolkitApp;
import dev.tamboui.toolkit.element.Element;
import dev.tamboui.toolkit.event.EventResult;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.event.KeyEvent;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * TamboUI implementation of the UserInterface port. The orchestrator runs on a worker thread; blocking
 * calls (approve/escalate) park it on a future that a key press completes on the render thread.
 */
public final class TamboUiApp extends ToolkitApp implements UserInterface {
    private sealed interface Pending {
        record Approval(ApprovalRequest request, CompletableFuture<Boolean> answer) implements Pending {}
        record Escalation(int taskId, String agent, String objective, String reason,
                          CompletableFuture<EscalationChoice> answer) implements Pending {}
    }

    private static final int LIVE_CHARS = 400;

    private final Consumer<UserInterface> job;
    private final List<String> log = new CopyOnWriteArrayList<>();
    private final Map<Integer, String> tasks = java.util.Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<Integer, String> taskStatus = java.util.Collections.synchronizedMap(new LinkedHashMap<>());
    private volatile Pending pending;
    private volatile boolean finished;
    private volatile String outcome = "running";
    private volatile int inputTokens;
    private volatile int outputTokens;
    private final StringBuilder live = new StringBuilder();
    private volatile String liveAgent = "";

    /** @param job the run to execute on the worker thread, using this object as its UserInterface */
    public TamboUiApp(Consumer<UserInterface> job) {
        this.job = job;
    }

    @Override
    protected TuiConfig configure() {
        return TuiConfig.builder().tickRate(Duration.ofMillis(100)).build();
    }

    @Override
    protected void onStart() {
        Thread.ofPlatform().daemon().name("orchestrator").start(() -> {
            try {
                job.accept(this);
                outcome = "finished";
            } catch (Throwable t) {
                outcome = "stopped: " + t.getMessage();
            } finally {
                finished = true;
            }
        });
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
        log.add(String.format("%-17s #%d %-6s %s", e.type(), e.taskId(), e.agent(), one(e.payload())));
        switch (e.type()) {
            case "TaskCreated" -> {
                tasks.put(e.taskId(), e.payload());
                taskStatus.put(e.taskId(), "RUNNING");
            }
            case "TaskCompleted" -> taskStatus.put(e.taskId(), "DONE");
            case "TaskSkipped" -> taskStatus.put(e.taskId(), "SKIPPED");
            case "TaskRetried" -> taskStatus.put(e.taskId(), "RETRY");
            case "TaskEscalated" -> taskStatus.put(e.taskId(), "ESCALATED");
            case "ApprovalRequested" -> taskStatus.put(e.taskId(), "WAITING_APPROVAL");
            case "ApprovalGranted", "ApprovalDenied" -> taskStatus.put(e.taskId(), "RUNNING");
            case "AgentInvoked" -> {
                inputTokens += e.inputTokens();
                outputTokens += e.outputTokens();
            }
            default -> {}
        }
    }

    private static String one(String s) {
        return s.replace('\n', ' ');
    }

    // ---- rendering and keys (render thread) ----

    private EventResult onKey(KeyEvent key) {
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
        } else if (key.isCharIgnoreCase('q') || key.isCtrlC()) {
            quit();
            return EventResult.HANDLED;
        }
        return EventResult.UNHANDLED;
    }

    private String liveTitle() {
        return liveAgent.isEmpty() ? "Live" : "Live: " + liveAgent;
    }

    private String liveText() {
        synchronized (live) {
            return live.toString().replace('\n', ' ');
        }
    }

    @Override
    protected Element render() {
        List<Element> taskLines = new ArrayList<>();
        synchronized (tasks) {
            tasks.forEach((id, desc) -> {
                String status = taskStatus.getOrDefault(id, "");
                var t = text("#" + id + " " + status + "  " + desc);
                taskLines.add(switch (status) {
                    case "DONE" -> t.green();
                    case "ESCALATED" -> t.red();
                    case "WAITING_APPROVAL", "RETRY" -> t.yellow();
                    default -> t;
                });
            });
        }
        List<String> snapshot = new ArrayList<>(log);
        int from = Math.max(0, snapshot.size() - 30);
        List<Element> logLines = snapshot.subList(from, snapshot.size()).stream().map(l -> (Element) text(l)).toList();

        Element main = column(
                row(
                        panel("Tasks", taskLines.toArray(new Element[0])).rounded().percent(40),
                        panel("Events", logLines.toArray(new Element[0])).rounded().fill()
                ).fill(),
                panel(liveTitle(), text(liveText()).dim()).rounded().length(4),
                panel(text(String.format("run: %s   tokens in/out: %d/%d   %s", outcome, inputTokens, outputTokens,
                        finished ? "[q] quit" : "")).dim()).rounded().length(3)
        );

        Element view = main;
        Pending p = pending;
        if (p instanceof Pending.Approval a) {
            List<Element> body = new ArrayList<>();
            body.add(text(a.request().summary()).bold());
            a.request().detail().lines().limit(12).forEach(l -> body.add(text(l)));
            body.add(text("[y] approve   [n] deny").dim());
            view = stack(main, dialog("Approval requested by " + a.request().agent(), body.toArray(new Element[0]))
                    .width(70).rounded()).alignment(ContentAlignment.CENTER);
        } else if (p instanceof Pending.Escalation esc) {
            view = stack(main, dialog("Task #" + esc.taskId() + " needs you (" + esc.agent() + ")",
                    text(esc.objective()), text("Failed after 3 retries: " + esc.reason()).red(),
                    text("[r] retry   [s] skip task   [a] abort run").dim())
                    .width(70).rounded()).alignment(ContentAlignment.CENTER);
        }
        return column(view).id("root").focusable().onKeyEvent(this::onKey);
    }
}

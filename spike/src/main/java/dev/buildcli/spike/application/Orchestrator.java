package dev.buildcli.spike.application;

import dev.buildcli.spike.domain.Agent;
import dev.buildcli.spike.domain.Limits;
import dev.buildcli.spike.domain.Task;
import dev.buildcli.spike.domain.TaskStatus;
import dev.buildcli.spike.domain.Team;
import dev.buildcli.spike.ports.EscalationChoice;
import dev.buildcli.spike.ports.LlmGateway;
import dev.buildcli.spike.ports.LlmMessage;
import dev.buildcli.spike.ports.LlmReply;
import dev.buildcli.spike.ports.ToolCall;
import dev.buildcli.spike.ports.ToolSpec;
import dev.buildcli.spike.ports.UserInterface;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Deterministic control loop. The LLM proposes; this class decides whether a handoff is valid, how deep
 * it may go, when a task has failed, when to retry (3x) and when to escalate to the user.
 * Execution is sequential: exactly one agent runs at a time.
 */
public final class Orchestrator {
    private final Team team;
    private final Limits limits;
    private final LlmGateway llm;
    private final ToolRuntime tools;
    private final UserInterface ui;
    private final Events events;
    private final List<Task> tasks = new CopyOnWriteArrayList<>();
    private int seq;

    public Orchestrator(Team team, LlmGateway llm, ToolRuntime tools, UserInterface ui, Events events) {
        this.team = team;
        this.limits = team.limits();
        this.llm = llm;
        this.tools = tools;
        this.ui = ui;
        this.events = events;
    }

    public List<Task> tasks() {
        return tasks;
    }

    /** Runs a request through the team's lead. Throws RunAborted if the user aborts an escalation. */
    public Task run(String request) {
        events.emit("RunStarted", 0, "user", request);
        Task root = newTask(null, "user", team.lead(), request, "");
        runTask(root, 0);
        return root;
    }

    private Task newTask(Integer parent, String from, String to, String objective, String brief) {
        Task t = new Task(++seq, parent, from, to, objective, brief);
        tasks.add(t);
        events.emit("TaskCreated", t.id, to, from + " -> " + to + ": " + objective);
        return t;
    }

    private String runTask(Task t, int depth) {
        Agent agent = team.agent(t.to).orElseThrow();
        String feedback = "";
        int failures = 0;
        while (true) {
            t.status = TaskStatus.RUNNING;
            t.attempts++;
            String reason;
            try {
                String result = agentLoop(agent, t, depth, feedback);
                t.status = TaskStatus.DONE;
                t.result = result;
                events.emit("TaskCompleted", t.id, agent.name(), ToolRuntime.abbreviate(result, 200));
                return result;
            } catch (TokenBudgetExceeded e) {
                reason = e.getMessage();
                events.emit("LimitReached", t.id, agent.name(), reason);
            } catch (TaskFailure e) {
                reason = e.getMessage();
                failures++;
                events.emit("TaskFailed", t.id, agent.name(), reason);
                if (failures <= limits.maxRetries()) {
                    feedback = "Your previous attempt failed: " + reason + ". Correct it and try again.";
                    events.emit("TaskRetried", t.id, agent.name(), "retry " + failures + "/" + limits.maxRetries());
                    continue;
                }
            }
            t.status = TaskStatus.ESCALATED;
            events.emit("TaskEscalated", t.id, agent.name(), reason);
            EscalationChoice choice = ui.escalate(t.id, agent.name(), t.objective, reason);
            switch (choice) {
                case RETRY -> {
                    failures = 0;
                    t.tokens = 0;
                    feedback = "The user asked you to try again. Previous problem: " + reason;
                }
                case SKIP -> {
                    t.status = TaskStatus.FAILED;
                    t.result = "FAILED: " + reason;
                    events.emit("TaskSkipped", t.id, agent.name(), "closed by the user after: " + reason);
                    return t.result;
                }
                case ABORT -> throw new RunAborted("aborted by the user at task #" + t.id + ": " + reason);
            }
        }
    }

    private String agentLoop(Agent agent, Task t, int depth, String feedback) {
        List<LlmMessage> messages = new ArrayList<>();
        messages.add(new LlmMessage.System(systemPrompt(agent)));
        messages.add(new LlmMessage.User(taskPrompt(t, feedback)));
        List<ToolSpec> specs = tools.specsFor(agent);
        boolean nudged = false;
        int[] handoffs = {0};

        for (int step = 1; step <= limits.maxSteps(); step++) {
            LlmReply reply;
            try {
                reply = llm.chat(agent, messages, specs);
            } catch (RuntimeException e) {
                throw new TaskFailure("LLM error: " + e.getMessage());
            }
            t.tokens += reply.inputTokens() + reply.outputTokens();
            events.emit("AgentInvoked", t.id, agent.name(),
                    "step=" + step + " in=" + reply.inputTokens() + " out=" + reply.outputTokens());
            events.emit("AgentReplied", t.id, agent.name(), ToolRuntime.abbreviate(
                    "text=" + reply.text() + " calls=" + reply.toolCalls().stream().map(c -> c.name() + c.args()).toList(), 400));
            if (t.tokens > limits.maxTokensPerTask()) {
                throw new TokenBudgetExceeded("token budget exceeded (" + t.tokens + " > " + limits.maxTokensPerTask() + ")");
            }
            if (reply.toolCalls().isEmpty() && (reply.text() == null || reply.text().isBlank())) {
                // Small models sometimes go silent right after a tool result. Nudge once per attempt
                // (cheaper than failing the task and repeating its side effects), then fail.
                if (nudged) {
                    throw new TaskFailure("empty response (no text and no tool call)");
                }
                nudged = true;
                events.emit("AgentNudged", t.id, agent.name(), "empty reply");
                messages.add(new LlmMessage.User("Your last reply was empty. If the objective is complete, reply with a short "
                        + "final report. Otherwise call the next tool."));
                continue;
            }
            messages.add(new LlmMessage.Assistant(reply.text(), reply.toolCalls()));
            if (reply.toolCalls().isEmpty()) {
                return reply.text();
            }
            for (ToolCall call : reply.toolCalls()) {
                String out = call.name().equals("handoff") ? handoff(agent, t, call, depth, handoffs) : tools.execute(agent, t, call);
                messages.add(new LlmMessage.ToolResult(call.id(), call.name(), out));
            }
        }
        throw new TaskFailure("max steps (" + limits.maxSteps() + ") reached without a final answer");
    }

    /** The runtime validates the form and permissions of a handoff; it does not judge its quality. */
    private String handoff(Agent from, Task parent, ToolCall call, int depth, int[] handoffs) {
        events.emit("ToolCalled", parent.id, from.name(), "handoff " + call.args());
        String error = handoffs[0] >= limits.maxHandoffsPerAttempt()
                ? "handoff limit reached (" + limits.maxHandoffsPerAttempt() + "). Do not delegate again: write your final report now"
                : validateHandoff(from, call, depth);
        if (error != null) {
            events.emit("ToolCompleted", parent.id, from.name(), "error: " + error);
            return "ERROR: " + error;
        }
        String to = String.valueOf(call.args().get("to"));
        Agent target = team.agent(to).orElseThrow();
        Object brief = call.args().get("brief");
        handoffs[0]++;
        Task child = newTask(parent.id, from.name(), target.name(), String.valueOf(call.args().get("objective")),
                brief == null ? "" : brief.toString());
        events.emit("HandoffCreated", child.id, from.name(), "-> " + target.name() + " (task #" + child.id + ")");
        String result = runTask(child, depth + 1);
        events.emit("ToolCompleted", parent.id, from.name(), "ok: handoff #" + child.id + " " + child.status);
        return "Task #" + child.id + " " + child.status + ": " + result
                + "\n[If the original request is now satisfied, reply with your final report and make no further tool calls;"
                + " do not hand off just to summarise.]";
    }

    private String validateHandoff(Agent from, ToolCall call, int depth) {
        Map<String, Object> args = call.args();
        if (!from.can("agent.handoff")) {
            return from.name() + " does not have capability agent.handoff";
        }
        Object to = args.get("to");
        if (to == null || team.agent(to.toString()).isEmpty()) {
            return "unknown teammate '" + to + "'. Team members: " + roster(from);
        }
        if (to.toString().equalsIgnoreCase(from.name())) {
            return "you cannot hand off to yourself";
        }
        Object objective = args.get("objective");
        if (objective == null || objective.toString().isBlank()) {
            return "handoff requires a non-empty objective";
        }
        if (depth + 1 > limits.maxDepth()) {
            return "maximum handoff depth (" + limits.maxDepth() + ") reached; do the work yourself or report back";
        }
        return null;
    }

    private String roster(Agent self) {
        return team.agents().stream().filter(a -> !a.name().equals(self.name()))
                .map(a -> a.name() + " (" + a.role() + ")").collect(Collectors.joining(", "));
    }

    private String systemPrompt(Agent agent) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are ").append(agent.name()).append(", the ").append(agent.role())
                .append(" of the team '").append(team.name()).append("'.\n").append(agent.instructions()).append('\n');
        String mates = roster(agent);
        if (!mates.isEmpty()) {
            sb.append("Teammates: ").append(mates).append(".\n");
        }
        sb.append("Use the provided tools; you can only do what your tools allow. ")
                .append("When the task is complete, reply with a short final report and no tool call.");
        return sb.toString();
    }

    private String taskPrompt(Task t, String feedback) {
        StringBuilder sb = new StringBuilder("Objective: ").append(t.objective);
        if (!t.brief.isBlank()) {
            sb.append("\nBrief: ").append(t.brief);
        }
        if (!feedback.isBlank()) {
            sb.append("\n").append(feedback);
        }
        return sb.toString();
    }
}

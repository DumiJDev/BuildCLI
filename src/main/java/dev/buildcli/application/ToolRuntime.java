package dev.buildcli.application;

import dev.buildcli.application.tools.GitCommitTool;
import dev.buildcli.application.tools.GitReadTool;
import dev.buildcli.application.tools.ListFilesTool;
import dev.buildcli.application.tools.ReadFileTool;
import dev.buildcli.application.tools.RunCommandTool;
import dev.buildcli.application.tools.SearchTool;
import dev.buildcli.application.tools.Tool;
import dev.buildcli.application.tools.ToolContext;
import dev.buildcli.application.tools.WriteFileTool;
import dev.buildcli.domain.Agent;
import dev.buildcli.domain.Capability;
import dev.buildcli.domain.Task;
import dev.buildcli.domain.TaskStatus;
import dev.buildcli.ports.ApprovalRequest;
import dev.buildcli.ports.ToolCall;
import dev.buildcli.ports.ToolSpec;
import dev.buildcli.ports.ToolSpec.Param;
import dev.buildcli.ports.UserInterface;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Tool registry and executor. Every call goes through the same gate: the tool must exist, the agent must hold its
 * capability, the tool applies its own policy checks, output is scrubbed of secrets, and output that carries outside
 * content is delimited as untrusted data. Expected refusals come back to the model as text, never as exceptions.
 */
public final class ToolRuntime {
    static final String OUTPUT_TAG = "tool-output";

    private final Path workspace;
    private final UserInterface ui;
    private final Events events;
    private final List<Tool> tools;

    public ToolRuntime(Path workspace, UserInterface ui, Events events) {
        this(workspace, ui, events, defaultTools());
    }

    public ToolRuntime(Path workspace, UserInterface ui, Events events, List<Tool> tools) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.ui = ui;
        this.events = events;
        this.tools = List.copyOf(tools);
    }

    /** The built-in tools of 1.0. */
    public static List<Tool> defaultTools() {
        return List.of(new ReadFileTool(), new ListFilesTool(), new WriteFileTool(), new SearchTool(), new RunCommandTool(),
                new GitReadTool(), new GitCommitTool());
    }

    /** Only the tools an agent's capabilities resolve to: less to describe to the model, less it can misuse. */
    public List<ToolSpec> specsFor(Agent agent) {
        List<ToolSpec> specs = new ArrayList<>();
        for (Tool t : tools) {
            if (agent.can(t.capability())) {
                specs.add(t.spec());
            }
        }
        if (agent.can(Capability.AGENT_HANDOFF)) {
            specs.add(new ToolSpec("handoff",
                    "Delegate a well-defined piece of work to a teammate. Returns the teammate's result.",
                    List.of(new Param("to", "Name of the teammate", false, true),
                            new Param("objective", "What must be achieved", false, true),
                            new Param("brief", "Short context: decisions, constraints, relevant paths", false, false))));
        }
        return specs;
    }

    /** Never throws for expected failures. The event log gets the scrubbed raw result; the model gets it delimited. */
    public String execute(Agent agent, Task task, ToolCall call) {
        events.emit("ToolCalled", task.id, agent.name(), call.name() + " " + call.args());
        String result;
        Tool tool = tools.stream().filter(t -> t.name().equals(call.name())).findFirst().orElse(null);
        try {
            if (tool == null) {
                result = "ERROR: unknown tool '" + call.name() + "'";
            } else if (!agent.can(tool.capability())) {
                result = "DENIED: " + agent.name() + " does not have capability " + tool.capability();
            } else {
                ToolContext ctx = new ToolContext(workspace, request -> approve(agent, task, request));
                result = tool.execute(ctx, agent, call);
            }
        } catch (IllegalArgumentException e) {
            result = "ERROR: " + e.getMessage();
        } catch (Exception e) {
            result = "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        result = Redactor.redact(result);
        String status = result.startsWith("DENIED") ? "denied" : result.startsWith("ERROR") ? "error" : "ok";
        events.emit("ToolCompleted", task.id, agent.name(), status + ": " + abbreviate(result, 200));
        boolean external = tool != null && tool.returnsExternalContent() && status.equals("ok");
        return external ? wrapExternal(call.name(), result) : result;
    }

    /**
     * Delimits content that came from outside the runtime so the model can tell data from instructions. The closing
     * tag is neutralised inside the content so it cannot break out of the block.
     */
    static String wrapExternal(String toolName, String content) {
        String safe = content.replace("</" + OUTPUT_TAG, "</ " + OUTPUT_TAG);
        return "<" + OUTPUT_TAG + " tool=\"" + toolName + "\">\n" + safe + "\n</" + OUTPUT_TAG + ">";
    }

    private boolean approve(Agent agent, Task task, ApprovalRequest request) {
        events.emit("ApprovalRequested", task.id, agent.name(), request.summary());
        TaskStatus previous = task.status;
        task.status = TaskStatus.WAITING_APPROVAL;
        events.taskChanged(task);
        boolean granted = ui.approve(request);
        task.status = previous;
        events.taskChanged(task);
        events.emit(granted ? "ApprovalGranted" : "ApprovalDenied", task.id, agent.name(), request.summary());
        return granted;
    }

    static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "... [truncated " + (s.length() - max) + " chars]";
    }
}

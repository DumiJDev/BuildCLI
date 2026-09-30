package dev.buildcli.application;

import dev.buildcli.domain.Agent;
import dev.buildcli.domain.Task;
import dev.buildcli.ports.ApprovalRequest;
import dev.buildcli.ports.ToolCall;
import dev.buildcli.ports.ToolSpec;
import dev.buildcli.ports.ToolSpec.Param;
import dev.buildcli.ports.UserInterface;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.LinkOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Executes tools on behalf of agents. Every call goes through capability and policy checks first;
 * expected denials are returned to the LLM as text so it can adapt, never thrown.
 */
public final class ToolRuntime {
    static final int MAX_OUTPUT = 4000;

    private final Path workspace;
    private final UserInterface ui;
    private final Events events;

    public ToolRuntime(Path workspace, UserInterface ui, Events events) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.ui = ui;
        this.events = events;
    }

    public List<ToolSpec> specsFor(Agent agent) {
        List<ToolSpec> specs = new ArrayList<>();
        if (agent.can("filesystem.read")) {
            specs.add(new ToolSpec("read_file", "Read a text file from the workspace.",
                    List.of(new Param("path", "Path relative to the workspace root", false, true))));
        }
        if (agent.can("filesystem.write")) {
            specs.add(new ToolSpec("write_file", "Create or overwrite a text file in the workspace. Requires user approval.",
                    List.of(new Param("path", "Path relative to the workspace root", false, true),
                            new Param("content", "Full new file content", false, true))));
        }
        if (agent.can("command.execute")) {
            specs.add(new ToolSpec("run_command",
                    "Run a command in the workspace. Pass argv as an array of separate strings, never a shell string.",
                    List.of(new Param("argv", "Command and arguments, e.g. [\"ls\", \"-la\"]", true, true))));
        }
        if (agent.can("agent.handoff")) {
            specs.add(new ToolSpec("handoff",
                    "Delegate a well-defined piece of work to a teammate. Returns the teammate's result.",
                    List.of(new Param("to", "Name of the teammate", false, true),
                            new Param("objective", "What must be achieved", false, true),
                            new Param("brief", "Short context: decisions, constraints, relevant paths", false, false))));
        }
        return specs;
    }

    /** Never throws for expected failures. */
    public String execute(Agent agent, Task task, ToolCall call) {
        events.emit("ToolCalled", task.id, agent.name(), call.name() + " " + call.args());
        String result;
        String status = "ok";
        try {
            result = dispatch(agent, task, call);
            if (result.startsWith("DENIED") || result.startsWith("ERROR")) {
                status = result.startsWith("DENIED") ? "denied" : "error";
            }
        } catch (Exception e) {
            result = "ERROR: " + e.getMessage();
            status = "error";
        }
        events.emit("ToolCompleted", task.id, agent.name(), status + ": " + abbreviate(result, 200));
        return result;
    }

    private String dispatch(Agent agent, Task task, ToolCall call) throws Exception {
        String capability = switch (call.name()) {
            case "read_file" -> "filesystem.read";
            case "write_file" -> "filesystem.write";
            case "run_command" -> "command.execute";
            default -> null;
        };
        if (capability == null) {
            return "ERROR: unknown tool '" + call.name() + "'";
        }
        if (!agent.can(capability)) {
            return "DENIED: " + agent.name() + " does not have capability " + capability;
        }
        return switch (call.name()) {
            case "read_file" -> readFile(call);
            case "write_file" -> writeFile(agent, task, call);
            default -> runCommand(agent, task, call);
        };
    }

    private String readFile(ToolCall call) throws IOException {
        Path file = resolve(str(call, "path"));
        if (!Files.isRegularFile(file)) {
            return "ERROR: not a file: " + str(call, "path");
        }
        return abbreviate(Files.readString(file, StandardCharsets.UTF_8), MAX_OUTPUT);
    }

    private String writeFile(Agent agent, Task task, ToolCall call) throws IOException {
        String rel = str(call, "path");
        String content = str(call, "content");
        Path file = resolve(rel);
        String relNorm = workspace.relativize(file).toString().replace('\\', '/');
        boolean allowed = agent.permissions().writeGlobs().stream()
                .anyMatch(g -> FileSystems.getDefault().getPathMatcher("glob:" + g).matches(Path.of(relNorm)));
        if (!allowed) {
            return "DENIED: " + agent.name() + " may not write '" + relNorm + "' (allowed: "
                    + agent.permissions().writeGlobs() + ")";
        }
        String old = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        if (!approve(agent, task, new ApprovalRequest(agent.name(), "write", "Write " + relNorm, diff(old, content)))) {
            return "DENIED: the user rejected the write to " + relNorm;
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return "OK: wrote " + content.length() + " chars to " + relNorm;
    }

    private String runCommand(Agent agent, Task task, ToolCall call) throws Exception {
        Object raw = call.args().get("argv");
        if (!(raw instanceof List<?> list) || list.isEmpty() || !list.stream().allMatch(String.class::isInstance)) {
            return "ERROR: argv must be a non-empty array of strings (no shell strings, pipes or expansion)";
        }
        List<String> argv = list.stream().map(String.class::cast).toList();
        boolean allowed = agent.permissions().commandAllow().stream()
                .anyMatch(prefix -> argv.size() >= prefix.size() && argv.subList(0, prefix.size()).equals(prefix));
        if (!allowed && !approve(agent, task,
                new ApprovalRequest(agent.name(), "command", "Run outside policy: " + String.join(" ", argv),
                        "Not in " + agent.name() + "'s command allow list."))) {
            return "DENIED: command not allowed by policy and the user rejected it: " + String.join(" ", argv);
        }
        Process p = new ProcessBuilder(argv).directory(workspace.toFile()).redirectErrorStream(true).start();
        boolean finished = p.waitFor(agent.permissions().commandTimeout().toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            p.destroyForcibly();
            return "ERROR: command timed out after " + agent.permissions().commandTimeout();
        }
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return "exit=" + p.exitValue() + "\n" + abbreviate(out, MAX_OUTPUT);
    }

    private boolean approve(Agent agent, Task task, ApprovalRequest request) {
        events.emit("ApprovalRequested", task.id, agent.name(), request.summary());
        var previous = task.status;
        task.status = dev.buildcli.domain.TaskStatus.WAITING_APPROVAL;
        boolean granted = ui.approve(request);
        task.status = previous;
        events.emit(granted ? "ApprovalGranted" : "ApprovalDenied", task.id, agent.name(), request.summary());
        return granted;
    }

    /**
     * Confines every path to the workspace root, including through symlinks: the deepest existing ancestor is
     * resolved to its real path and must still be inside the (real) workspace.
     */
    private Path resolve(String rel) throws IOException {
        Path p = workspace.resolve(rel).normalize();
        if (!p.startsWith(workspace)) {
            throw new IllegalArgumentException("path escapes the workspace: " + rel);
        }
        Path existing = p;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing != null && !existing.toRealPath().startsWith(workspace.toRealPath())) {
            throw new IllegalArgumentException("path escapes the workspace through a symlink: " + rel);
        }
        return p;
    }

    private static String str(ToolCall call, String key) {
        Object v = call.args().get(key);
        if (v == null) {
            throw new IllegalArgumentException("missing argument '" + key + "'");
        }
        return v.toString();
    }

    static String diff(String old, String now) {
        StringBuilder sb = new StringBuilder();
        if (old == null) {
            sb.append("(new file)\n");
        } else {
            old.lines().forEach(l -> sb.append("- ").append(l).append('\n'));
        }
        now.lines().forEach(l -> sb.append("+ ").append(l).append('\n'));
        return sb.toString().stripTrailing();
    }

    static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "... [truncated " + (s.length() - max) + " chars]";
    }
}

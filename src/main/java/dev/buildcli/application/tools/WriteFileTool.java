package dev.buildcli.application.tools;

import dev.buildcli.domain.Agent;
import dev.buildcli.domain.Capability;
import dev.buildcli.ports.ApprovalRequest;
import dev.buildcli.ports.ToolCall;
import dev.buildcli.ports.ToolSpec;
import dev.buildcli.ports.ToolSpec.Param;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Writes are checked against the agent's write globs first, then shown to the user as a unified diff. */
public final class WriteFileTool implements Tool {
    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String capability() {
        return Capability.FILESYSTEM_WRITE;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(name(), "Create or overwrite a text file in the workspace. Requires user approval.",
                List.of(new Param("path", "Path relative to the workspace root", false, true),
                        new Param("content", "Full new file content", false, true)));
    }

    @Override
    public boolean returnsExternalContent() {
        return false;
    }

    @Override
    public String execute(ToolContext ctx, Agent agent, ToolCall call) throws Exception {
        Object raw = call.args().get("content");
        if (raw == null) {
            throw new IllegalArgumentException("missing argument 'content'");
        }
        String content = raw.toString(); // an empty file is a legitimate write
        Path file = ctx.resolve(ToolContext.required(call, "path"));
        String rel = ctx.relative(file);
        if (!ToolContext.matches(agent.permissions().writeGlobs(), rel)) {
            return "DENIED: " + agent.name() + " may not write '" + rel + "' (allowed: " + agent.permissions().writeGlobs() + ")";
        }
        String old = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
        if (!ctx.approve(new ApprovalRequest(agent.name(), "write", "Write " + rel, Diffs.unified(rel, old, content)))) {
            return "DENIED: the user rejected the write to " + rel;
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return "OK: wrote " + content.length() + " chars to " + rel;
    }
}

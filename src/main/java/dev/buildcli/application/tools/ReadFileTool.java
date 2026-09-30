package dev.buildcli.application.tools;

import dev.buildcli.domain.Agent;
import dev.buildcli.domain.Capability;
import dev.buildcli.ports.ToolCall;
import dev.buildcli.ports.ToolSpec;
import dev.buildcli.ports.ToolSpec.Param;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ReadFileTool implements Tool {
    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String capability() {
        return Capability.FILESYSTEM_READ;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(name(), "Read a text file from the workspace.",
                List.of(new Param("path", "Path relative to the workspace root", false, true)));
    }

    @Override
    public boolean returnsExternalContent() {
        return true;
    }

    @Override
    public String execute(ToolContext ctx, Agent agent, ToolCall call) throws Exception {
        String rel = ToolContext.required(call, "path");
        Path file = ctx.resolve(rel);
        String relNorm = ctx.relative(file);
        if (!ToolContext.matches(agent.permissions().readGlobs(), relNorm)) {
            return "DENIED: " + agent.name() + " may not read '" + relNorm + "' (allowed: " + agent.permissions().readGlobs() + ")";
        }
        if (!Files.isRegularFile(file)) {
            return "ERROR: not a file: " + rel;
        }
        return ToolContext.cap(Files.readString(file, StandardCharsets.UTF_8), ToolContext.MAX_OUTPUT);
    }
}

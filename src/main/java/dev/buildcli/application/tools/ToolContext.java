package dev.buildcli.application.tools;

import dev.buildcli.ports.ApprovalRequest;
import dev.buildcli.ports.ToolCall;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/** What a tool may use while running one call: the confined workspace, path checks and the approval channel. */
public final class ToolContext {
    /** Longest tool output handed back to the model; small local models have small context windows. */
    public static final int MAX_OUTPUT = 4000;

    /** Asks the user to approve an action. Blocks until they decide. */
    public interface Approval {
        boolean request(ApprovalRequest request);
    }

    private final Path workspace;
    private final Approval approval;

    public ToolContext(Path workspace, Approval approval) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.approval = approval;
    }

    public Path workspace() {
        return workspace;
    }

    public boolean approve(ApprovalRequest request) {
        return approval.request(request);
    }

    /**
     * Confines every path to the workspace root, including through symlinks: the deepest existing ancestor is
     * resolved to its real path and must still be inside the (real) workspace.
     */
    public Path resolve(String rel) throws IOException {
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

    /** The workspace-relative path with forward slashes, the form permission globs are matched against. */
    public String relative(Path absolute) {
        String rel = workspace.relativize(absolute).toString().replace('\\', '/');
        return rel.isEmpty() ? "." : rel;
    }

    public static boolean matches(List<String> globs, String relativePath) {
        Path p = Path.of(relativePath);
        return globs.stream().anyMatch(g -> FileSystems.getDefault().getPathMatcher("glob:" + g).matches(p));
    }

    public static String required(ToolCall call, String key) {
        Object v = call.args().get(key);
        if (v == null || v.toString().isBlank()) {
            throw new IllegalArgumentException("missing argument '" + key + "'");
        }
        return v.toString();
    }

    public static String optional(ToolCall call, String key, String fallback) {
        Object v = call.args().get(key);
        return v == null || v.toString().isBlank() ? fallback : v.toString();
    }

    public static String cap(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "... [truncated " + (s.length() - max) + " chars]";
    }
}

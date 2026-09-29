package dev.buildcli.spike;

import dev.buildcli.spike.domain.*;
import dev.buildcli.spike.infrastructure.HeadlessUi;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/** The M0 demo scenario: Ana (lead) hands off to Bruno, who writes a file and verifies it under policy. */
public final class Scenario {
    public static final String REQUEST = "Ask bruno to create the file out/greeting.txt containing exactly: hello from bruno. "
            + "Then bruno must verify it by running the command: cat out/greeting.txt. Report the result.";

    private Scenario() {}

    public static Team team(Limits limits) {
        Agent ana = new Agent("ana", "architect",
                "You coordinate. You never write files or run commands yourself. Delegate the whole request to a teammate in ONE handoff; when the teammate reports back, reply with a short final report.",
                Set.of("filesystem.read", "agent.handoff"), Permissions.none());
        Agent bruno = new Agent("bruno", "developer",
                "You implement tasks with your tools, then report what you did.",
                Set.of("filesystem.read", "filesystem.write", "command.execute"),
                new Permissions(List.of("out/**"), List.of(List.of("ls"), List.of("cat", "out/greeting.txt")), Duration.ofSeconds(30)));
        return new Team("backend", "ana", List.of(ana, bruno), limits);
    }

    /** Returns null when the scenario succeeded, otherwise why not. */
    public static String verify(Path workspace, HeadlessUi ui, Task root) {
        if (root.status != TaskStatus.DONE) {
            return "root task " + root.status;
        }
        if (ui.events.stream().noneMatch(e -> e.type().equals("HandoffCreated"))) {
            return "no handoff";
        }
        Path file = workspace.resolve("out/greeting.txt");
        try {
            if (!Files.isRegularFile(file) || !Files.readString(file).toLowerCase().contains("hello from bruno")) {
                return "file missing or wrong content";
            }
        } catch (Exception e) {
            return "cannot read file: " + e.getMessage();
        }
        boolean verified = ui.events.stream().anyMatch(e -> e.type().equals("ToolCompleted") && e.agent().equals("bruno")
                && e.payload().startsWith("ok:") && e.payload().contains("exit=0"));
        return verified ? null : "bruno never ran cat successfully";
    }
}

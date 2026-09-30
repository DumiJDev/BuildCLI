package dev.buildcli.spike;

import dev.buildcli.spike.application.Events;
import dev.buildcli.spike.application.Orchestrator;
import dev.buildcli.spike.application.RunAborted;
import dev.buildcli.spike.application.ToolRuntime;
import dev.buildcli.spike.domain.*;
import dev.buildcli.spike.infrastructure.HeadlessUi;
import dev.buildcli.spike.infrastructure.JdbcEventStore;
import dev.buildcli.spike.ports.EscalationChoice;
import dev.buildcli.spike.ports.LlmGateway;
import java.nio.file.Files;
import java.nio.file.Path;

/** Runs the scenario N times headless and reports how often a real model completes it. */
public final class Bench {
    private Bench() {}

    public static int run(LlmGateway llm, int runs, String label, boolean verbose) throws Exception {
        int ok = 0;
        long totalMs = 0;
        long totalTokens = 0;
        System.out.printf("== %s: %d runs%n", label, runs);
        for (int i = 1; i <= runs; i++) {
            Path ws = Files.createTempDirectory("buildcli-spike-");
            HeadlessUi ui = new HeadlessUi(r -> r.kind().equals("write"), EscalationChoice.ABORT, verbose);
            long t0 = System.currentTimeMillis();
            String failure;
            int tokens = 0;
            try (JdbcEventStore store = new JdbcEventStore(JdbcEventStore.IN_MEMORY)) {
                Events events = new Events(store, "run" + i, ui);
                Orchestrator o = new Orchestrator(Scenario.team(Limits.defaults()), llm,
                        new ToolRuntime(ws, ui, events), ui, events);
                try {
                    Task root = o.run(Scenario.REQUEST);
                    failure = Scenario.verify(ws, ui, root);
                } catch (RunAborted e) {
                    failure = "aborted: " + e.getMessage();
                }
                tokens = o.tasks().stream().mapToInt(t -> t.tokens).sum();
            }
            long ms = System.currentTimeMillis() - t0;
            totalMs += ms;
            totalTokens += tokens;
            if (failure == null) {
                ok++;
            }
            long retries = ui.events.stream().filter(e -> e.type().equals("TaskRetried")).count();
            System.out.printf("run %2d: %-4s %6.1fs %6d tok  retries=%d %s%n", i, failure == null ? "OK" : "FAIL",
                    ms / 1000.0, tokens, retries, failure == null ? "" : "- " + failure);
        }
        System.out.printf("== %s: %d/%d succeeded, avg %.1fs, avg %d tokens%n", label, ok, runs, totalMs / 1000.0 / runs, totalTokens / runs);
        return ok;
    }
}

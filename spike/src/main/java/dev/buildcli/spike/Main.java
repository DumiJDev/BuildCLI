package dev.buildcli.spike;

import dev.buildcli.spike.infrastructure.OllamaGateway;
import dev.buildcli.spike.application.Events;
import dev.buildcli.spike.application.Orchestrator;
import dev.buildcli.spike.application.ToolRuntime;
import dev.buildcli.spike.domain.Limits;
import dev.buildcli.spike.infrastructure.JdbcEventStore;
import dev.buildcli.spike.infrastructure.ScriptedGateway;
import dev.buildcli.spike.infrastructure.TamboUiApp;
import dev.buildcli.spike.ports.LlmGateway;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "spike", mixinStandardHelpOptions = true, subcommands = {Main.BenchCmd.class, Main.DemoCmd.class})
public final class Main implements Runnable {
    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }

    @Command(name = "bench", description = "Run the scenario headless against Ollama N times")
    static final class BenchCmd implements Callable<Integer> {
        @Option(names = "--model", defaultValue = "qwen2.5:3b") String model;
        @Option(names = "--url", defaultValue = "http://localhost:11434") String url;
        @Option(names = "--threads", defaultValue = "4", description = "Ollama num_thread (the default 16 was ~50x slower on this WSL2 box)") int threads;
        @Option(names = "--runs", defaultValue = "10") int runs;
        @Option(names = "-v") boolean verbose;

        @Override
        public Integer call() throws Exception {
            Bench.run(new OllamaGateway(url, model, threads), runs, model, verbose);
            return 0;
        }
    }

    @Command(name = "demo", description = "Run the scenario in the TamboUI TUI (real model, or --fake for a scripted one)")
    static final class DemoCmd implements Callable<Integer> {
        @Option(names = "--model", defaultValue = "qwen2.5:3b") String model;
        @Option(names = "--url", defaultValue = "http://localhost:11434") String url;
        @Option(names = "--threads", defaultValue = "4", description = "Ollama num_thread (the default 16 was ~50x slower on this WSL2 box)") int threads;
        @Option(names = "--fake", description = "Use a scripted LLM (no Ollama needed)") boolean fake;
        @Option(names = "--escalate", description = "With --fake: make Bruno fail so the escalation dialog shows") boolean escalate;

        @Override
        public Integer call() throws Exception {
            Path workspace = Files.createTempDirectory("buildcli-spike-");
            LlmGateway llm = fake ? script(escalate) : new OllamaGateway(url, model, threads);
            new TamboUiApp(ui -> {
                try (JdbcEventStore store = new JdbcEventStore(JdbcEventStore.IN_MEMORY)) {
                    Events events = new Events(store, "demo", ui);
                    new Orchestrator(Scenario.team(Limits.defaults()), llm, new ToolRuntime(workspace, ui, events), ui, events)
                            .run(Scenario.REQUEST);
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
            }).run();
            return 0;
        }

        private static ScriptedGateway script(boolean escalate) {
            ScriptedGateway g = new ScriptedGateway()
                    .call("ana", "handoff", Map.of("to", "bruno", "objective", "Create out/greeting.txt with 'hello from bruno' and verify it",
                            "brief", "Only out/** is writable"));
            if (escalate) {
                for (int i = 0; i < 4; i++) {
                    g.then("bruno", new RuntimeException("model returned malformed tool arguments"));
                }
                return g.say("ana", "Bruno's task was closed by the user.");
            }
            return g.call("bruno", "write_file", Map.of("path", "out/greeting.txt", "content", "hello from bruno"))
                    .call("bruno", "run_command", Map.of("argv", List.of("rm", "-rf", "out")))
                    .call("bruno", "run_command", Map.of("argv", List.of("cat", "out/greeting.txt")))
                    .say("bruno", "Created out/greeting.txt and verified it with cat.")
                    .say("ana", "Done: Bruno created and verified out/greeting.txt.");
        }
    }

}

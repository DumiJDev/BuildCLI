package dev.buildcli.cli;

import dev.buildcli.application.Events;
import dev.buildcli.application.Orchestrator;
import dev.buildcli.application.ToolRuntime;
import dev.buildcli.domain.Limits;
import dev.buildcli.domain.ModelRef;
import dev.buildcli.eval.Bench;
import dev.buildcli.eval.Scenario;
import dev.buildcli.infrastructure.ProviderSettings;
import dev.buildcli.infrastructure.ScriptedGateway;
import dev.buildcli.infrastructure.StateStore;
import dev.buildcli.ports.LlmGateway;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/** Tools for developing and qualifying BuildCLI itself. Hidden from the normal help. */
final class DevCommands {
    private DevCommands() {}

    /** Provider options shared by the commands below. The API key is only ever read from the environment. */
    static final class ModelOptions {
        @Option(names = "--provider", defaultValue = "ollama", description = "any provider from 'buildcli provider list'") String provider;
        @Option(names = "--model", defaultValue = "qwen2.5:3b") String model;
        @Option(names = "--url", description = "Base URL of the provider") String url;
        @Option(names = "--threads", defaultValue = "4", description = "Ollama num_thread") int threads;
        @Option(names = "--no-stream", description = "Disable streaming") boolean noStream;
        @Option(names = "--temperature", defaultValue = "0", description = "0 makes runs identical; use > 0 to measure real variance") double temperature;

        LlmGateway gateway(Map<String, String> env) {
            ProviderSettings s = ProviderSettings.fromEnvironment(env).withBaseUrl(provider, url).with(threads, temperature, !noStream);
            return s.gatewayFor(new ModelRef(provider, model));
        }
    }

    @Command(name = "bench", hidden = true, description = "Run the demo scenario headless against a real model N times")
    static final class BenchCmd implements Callable<Integer> {
        private final CliContext ctx;

        @picocli.CommandLine.Mixin ModelOptions m;
        @Option(names = "--runs", defaultValue = "10") int runs;
        @Option(names = "-v") boolean verbose;
        @Option(names = "--fixed-objective", description = "Script the lead: it hands this exact objective to bruno, so the run measures bruno alone")
        String fixedObjective;

        BenchCmd(CliContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public Integer call() throws Exception {
            LlmGateway real = m.gateway(ctx.env);
            String label = m.provider + ":" + m.model + " t=" + m.temperature + (fixedObjective == null ? "" : " fixed-objective");
            Bench.run(() -> fixedObjective == null ? real : withScriptedLead(real, fixedObjective), runs, label, verbose);
            return 0;
        }
    }

    /** The lead (ana) is scripted to hand off {@code objective}; every other agent uses the real model. */
    static LlmGateway withScriptedLead(LlmGateway real, String objective) {
        ScriptedGateway lead = new ScriptedGateway()
                .call("ana", "handoff", Map.of("to", "bruno", "objective", objective))
                .say("ana", "Done.");
        return (agent, messages, tools) -> agent.name().equals("ana") ? lead.chat(agent, messages, tools) : real.chat(agent, messages, tools);
    }

    @Command(name = "demo", hidden = true, description = "Run the demo scenario in the TUI, with a real model or --fake")
    static final class DemoCmd implements Callable<Integer> {
        private final CliContext ctx;

        @picocli.CommandLine.Mixin ModelOptions m;
        @Option(names = "--fake", description = "Use a scripted LLM (no Ollama needed)") boolean fake;
        @Option(names = "--escalate", description = "With --fake: make Bruno fail so the escalation dialog shows") boolean escalate;
        @Option(names = "--ask", description = "Start by asking for the request, like 'buildcli run' without one") boolean ask;

        DemoCmd(CliContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public Integer call() throws Exception {
            Path workspace = Files.createTempDirectory("buildcli-");
            LlmGateway llm = fake ? script(escalate) : m.gateway(ctx.env);
            var team = Scenario.team(Limits.defaults());
            Map<String, String> models = new java.util.LinkedHashMap<>();
            team.agents().forEach(a -> models.put(a.name(), fake ? "scripted" : m.provider + "/" + m.model));
            var workspaceLock = new dev.buildcli.application.tools.WorkspaceLock();
            var session = new dev.buildcli.application.ChatSession(team, (chatTeam, request, ui, cancelled, dispatcher) -> {
                // a message to the group goes to its admin, so "for the lead" means no target or the lead itself
                boolean forLead = request.target() == null || request.target().equals(chatTeam.lead());
                LlmGateway model = fake ? demoModel(script(escalate && forLead), request, forLead) : llm;
                try (StateStore store = new StateStore(StateStore.IN_MEMORY)) {
                    Events events = new Events(store, "demo", ui);
                    Orchestrator o = new Orchestrator(chatTeam, model, new ToolRuntime(workspace, ui, events, workspaceLock), ui, events);
                    o.cancelWhen(cancelled);
                    o.dispatchWith(dispatcher);
                    return o.run(request);
                }
            });
            if (!ask) {
                session.submit(Scenario.REQUEST);
            }
            new dev.buildcli.infrastructure.tui.ChatApp(session, models, workspace, !"0".equals(ctx.env.get("BUILDCLI_MOUSE"))).run();
            return 0;
        }

        /**
         * The scripted scenario for team requests about the greeting; a friendly canned answer otherwise, so the chat can be
         * tried without a model. Replies are streamed a few characters at a time, like a real model, so typing shows.
         */
        static LlmGateway demoModel(ScriptedGateway scenario, Orchestrator.Request request, boolean forLead) {
            boolean scripted = forLead && request.text().toLowerCase(java.util.Locale.ROOT).contains("greeting");
            return new LlmGateway() {
                @Override
                public dev.buildcli.ports.LlmReply chat(dev.buildcli.domain.Agent agent, List<dev.buildcli.ports.LlmMessage> messages,
                        List<dev.buildcli.ports.ToolSpec> tools) {
                    if (scripted) {
                        return scenario.chat(agent, messages, tools);
                    }
                    return new dev.buildcli.ports.LlmReply("Hi, I am **" + agent.name() + "** (" + agent.role() + "). This is the demo, "
                            + "so I am not really thinking. You said:\n\n> " + request.text().replace("\n", " ")
                            + "\n\nWith a real model I would work on it with my tools, for example:\n```\nread_file src/Main.java\n```",
                            List.of(), 120, 60);
                }

                @Override
                public dev.buildcli.ports.LlmReply chatStreaming(dev.buildcli.domain.Agent agent, List<dev.buildcli.ports.LlmMessage> messages,
                        List<dev.buildcli.ports.ToolSpec> tools, java.util.function.Consumer<String> onText) {
                    var reply = chat(agent, messages, tools);
                    String text = reply.text() == null ? "" : reply.text();
                    try {
                        Thread.sleep(600);
                        for (int i = 0; i < text.length(); i += 3) {
                            onText.accept(text.substring(i, Math.min(text.length(), i + 3)));
                            Thread.sleep(18);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return reply;
                }
            };
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
                    .call("bruno", "read_file", Map.of("path", "out/greeting.txt"))
                    .say("bruno", "Created `out/greeting.txt` and read it back: it says *hello from bruno*.")
                    .say("ana", "Done: Bruno created and verified out/greeting.txt.");
        }
    }
}

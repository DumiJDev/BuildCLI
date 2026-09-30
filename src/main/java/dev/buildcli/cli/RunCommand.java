package dev.buildcli.cli;

import dev.buildcli.application.Events;
import dev.buildcli.application.Orchestrator;
import dev.buildcli.application.RunAborted;
import dev.buildcli.application.ToolRuntime;
import dev.buildcli.application.TrustGate;
import dev.buildcli.domain.Agent;
import dev.buildcli.domain.AgentUsage;
import dev.buildcli.domain.Limits;
import dev.buildcli.domain.ModelRef;
import dev.buildcli.domain.Task;
import dev.buildcli.domain.TaskStatus;
import dev.buildcli.domain.Team;
import dev.buildcli.infrastructure.ConsoleUi;
import dev.buildcli.infrastructure.FileTrustStore;
import dev.buildcli.infrastructure.ProviderSettings;
import dev.buildcli.infrastructure.RoutingGateway;
import dev.buildcli.infrastructure.SqliteRunStore;
import dev.buildcli.ports.ConfigRepository;
import dev.buildcli.ports.UserInterface;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "run", description = "Run a request through a team or a single agent. On a terminal it opens the TUI.")
final class RunCommand implements Callable<Integer> {
    private final CliContext ctx;

    @Option(names = "--team", description = "The team to run (default: the only team, if there is one)")
    String teamName;

    @Option(names = "--agent", description = "Talk to a single agent directly instead of a team")
    String agentName;

    @Option(names = "--model", description = "Model for agents the team did not configure, as provider:model (e.g. ollama:qwen2.5:7b)")
    String model;

    @Option(names = "--headless", description = "Plain-text output, no TUI (also used when there is no terminal)")
    boolean headless;

    @Option(names = "--approve", description = "How approvals are decided without the TUI: ask (prompt), none, writes or all. Default: ask on a terminal, none otherwise.")
    ConsoleUi.Policy approve;

    @Option(names = "--no-stream", description = "Wait for each complete reply instead of streaming")
    boolean noStream;

    @Option(names = "--threads", description = "Ollama num_thread (default 4; Ollama's own default of 16 was ~50x slower on a WSL2 box)")
    Integer threads;

    @Option(names = "--temperature", description = "Sampling temperature (default 0)")
    Double temperature;

    @Parameters(arity = "0..*", paramLabel = "REQUEST", description = "What the team should do (asked for in the TUI if omitted)")
    List<String> request;

    private final AtomicInteger exit = new AtomicInteger();

    RunCommand(CliContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public Integer call() throws Exception {
        ConfigRepository config = ctx.loadConfig();
        if (config == null) {
            return 2;
        }
        Team team = resolveTeam(config);
        if (team == null) {
            return 2;
        }
        ModelRef fallback = null;
        if (model != null) {
            try {
                fallback = BuildCli.parseModel(model);
            } catch (IllegalArgumentException e) {
                ctx.err.println("error: " + e.getMessage());
                return 2;
            }
        }
        ProviderSettings base = ProviderSettings.fromEnvironment(ctx.env);
        ProviderSettings settings = new ProviderSettings(base.ollamaUrl(), base.openAiUrl(), base.openAiApiKey(),
                threads == null ? base.ollamaThreads() : threads, temperature == null ? base.temperature() : temperature, !noStream);
        RoutingGateway gateway = new RoutingGateway(team.routing(), fallback, ref -> ctx.gateways.create(ref, settings));
        Map<String, String> models = new LinkedHashMap<>();
        for (Agent a : team.agents()) {
            try {
                ModelRef ref = gateway.modelFor(a);
                models.put(a.name(), ref.provider() + "/" + ref.model());
            } catch (IllegalStateException e) {
                ctx.err.println("error: " + e.getMessage());
                return 2;
            }
        }

        String text = request == null ? "" : String.join(" ", request).strip();
        boolean tui = !headless && ctx.terminal;
        if (tui) {
            ctx.tui.launch(team, models, text.isEmpty() ? null : text, (req, ui) -> execute(team, config, gateway, req, ui));
            return exit.get();
        }
        if (text.isEmpty()) {
            ctx.err.println("error: a request is required without the TUI, for example: buildcli run --headless \"add a health endpoint\"");
            return 2;
        }
        ConsoleUi.Policy policy = approve != null ? approve : ctx.terminal ? ConsoleUi.Policy.ASK : ConsoleUi.Policy.NONE;
        execute(team, config, gateway, text, new ConsoleUi(ctx.out, ctx.in, policy));
        return exit.get();
    }

    private Team resolveTeam(ConfigRepository config) {
        if (agentName != null && teamName != null) {
            ctx.err.println("error: use either --team or --agent, not both");
            return null;
        }
        if (agentName != null) {
            Agent a = config.agent(agentName).orElse(null);
            if (a == null) {
                ctx.err.println("error: no agent named '" + agentName + "'. Available: " + config.agents().stream().map(Agent::name).toList());
                return null;
            }
            // Direct mode: the agent is its own team, so it cannot hand off. It keeps the model (and limits) of the first team
            // that includes it, since the model belongs to team configuration.
            Team home = config.teams().stream().filter(t -> t.agent(a.name()).isPresent()).findFirst().orElse(null);
            return new Team("direct-" + a.name(), a.name(), List.of(a), home == null ? Limits.defaults() : home.limits(),
                    home == null ? dev.buildcli.domain.ModelRouting.unspecified() : home.routing());
        }
        if (teamName != null) {
            Team t = config.team(teamName).orElse(null);
            if (t == null) {
                ctx.err.println("error: no team named '" + teamName + "'. Available: " + config.teams().stream().map(Team::name).toList());
            }
            return t;
        }
        if (config.teams().size() == 1) {
            return config.teams().get(0);
        }
        if (config.teams().isEmpty()) {
            ctx.err.println("error: no teams are defined. Run 'buildcli init' for a sample team.");
        } else {
            ctx.err.println("error: several teams are defined; choose one with --team " + config.teams().stream().map(Team::name).toList());
        }
        return null;
    }

    /** Runs on the calling thread (headless) or on the TUI's worker thread. Records its exit code in {@link #exit}. */
    private void execute(Team team, ConfigRepository config, RoutingGateway gateway, String requestText, UserInterface ui) {
        String runId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-"
                + HexFormat.of().formatHex(randomBytes());
        try (SqliteRunStore store = SqliteRunStore.open(ctx.stateDb())) {
            if (!TrustGate.ensureTrusted(team, config, new FileTrustStore(ctx.trustFile()), ctx.projectKey(), ui)) {
                ctx.err.println("The project's agent definitions were not trusted, so nothing was run.");
                exit.set(1);
                return;
            }
            Events events = new Events(store, runId, ui);
            Orchestrator orchestrator = new Orchestrator(team, gateway, new ToolRuntime(ctx.cwd, ui, events), ui, events,
                    config.projectContext());
            Task root = orchestrator.run(requestText);
            exit.set(root.status == TaskStatus.DONE ? 0 : 1);
            summarize(root, runId, store.usage(runId));
        } catch (RunAborted e) {
            exit.set(1);
            ctx.out.println();
            ctx.out.println("Run " + runId + " aborted: " + e.getMessage());
        } catch (Exception e) {
            exit.set(1);
            ctx.err.println("error: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
        if (ui instanceof dev.buildcli.infrastructure.TamboUiApp tuiApp) {
            tuiApp.setOutcome("run " + runId + (exit.get() == 0 ? " done" : " failed") + " (buildcli task list)");
        }
    }

    private void summarize(Task root, String runId, List<AgentUsage> usage) {
        ctx.out.println();
        ctx.out.println("Run " + runId + ": " + (root.status == TaskStatus.DONE ? "done" : "failed"));
        ctx.out.println();
        ctx.out.println(root.result == null ? "(no result)" : root.result);
        long in = usage.stream().mapToLong(AgentUsage::inputTokens).sum();
        long out = usage.stream().mapToLong(AgentUsage::outputTokens).sum();
        ctx.out.println();
        ctx.out.println("Tokens: " + in + " in / " + out + " out.   See: buildcli task list, buildcli usage");
    }

    private static byte[] randomBytes() {
        byte[] b = new byte[2];
        new SecureRandom().nextBytes(b);
        return b;
    }
}

package dev.buildcli.cli;

import dev.buildcli.domain.Agent;
import dev.buildcli.domain.ModelRef;
import dev.buildcli.domain.Team;
import dev.buildcli.ports.ConfigRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "team", description = "List, show and create teams", subcommands = {TeamCommand.ListCmd.class,
        TeamCommand.ShowCmd.class, TeamCommand.CreateCmd.class})
final class TeamCommand implements Callable<Integer> {
    private final CliContext ctx;

    TeamCommand(CliContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public Integer call() {
        ctx.out.println("Use one of: team list | team show <name> | team create <name> --agents a,b");
        return 2;
    }

    static String modelLabel(Team team, Agent a) {
        ModelRef ref = team.routing().forAgent(a.name());
        return ref == null ? "(no model)" : ref.provider() + "/" + ref.model();
    }

    @Command(name = "list", description = "List the available teams")
    static final class ListCmd implements Callable<Integer> {
        private final CliContext ctx;

        ListCmd(CliContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public Integer call() {
            ConfigRepository config = ctx.loadConfig();
            if (config == null) {
                return 2;
            }
            if (config.teams().isEmpty()) {
                ctx.out.println("No teams yet. Run 'buildcli init' for a sample team, or 'buildcli team create <name> --agents a,b'.");
                return 0;
            }
            Tables.print(ctx.out, List.of("NAME", "LEAD", "AGENTS"), config.teams().stream()
                    .map(t -> List.of(t.name(), t.lead(), String.join(",", t.agents().stream().map(Agent::name).toList()))).toList());
            return 0;
        }
    }

    @Command(name = "show", description = "Show one team in full")
    static final class ShowCmd implements Callable<Integer> {
        private final CliContext ctx;

        @Parameters(index = "0", description = "Team name")
        String name;

        ShowCmd(CliContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public Integer call() {
            ConfigRepository config = ctx.loadConfig();
            if (config == null) {
                return 2;
            }
            Team t = config.team(name).orElse(null);
            if (t == null) {
                ctx.err.println("error: no team named '" + name + "'. Available: " + config.teams().stream().map(Team::name).toList());
                return 2;
            }
            ctx.out.println(t.name() + "  (lead: " + t.lead() + ")");
            for (Agent a : t.agents()) {
                ctx.out.printf("  %-10s %-12s %s%n", a.name(), a.role(), modelLabel(t, a));
            }
            var l = t.limits();
            ctx.out.println("  limits: retries " + l.maxRetries() + ", steps " + l.maxSteps() + ", handoff depth " + l.maxDepth()
                    + ", tokens/task " + l.maxTokensPerTask() + ", handoffs/attempt " + l.maxHandoffsPerAttempt());
            return 0;
        }
    }

    @Command(name = "create", description = "Create a team file")
    static final class CreateCmd implements Callable<Integer> {
        private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_-]*");
        private final CliContext ctx;

        @Parameters(index = "0", description = "Team name")
        String name;

        @Option(names = "--agents", required = true, split = ",", description = "Comma-separated agent names")
        List<String> agents;

        @Option(names = "--lead", description = "The lead (default: the first agent)")
        String lead;

        @Option(names = "--model", description = "Default model as provider:model")
        String model;

        @Option(names = "--global", description = "Create it in ~/.buildcli instead of the project")
        boolean global;

        CreateCmd(CliContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public Integer call() throws IOException {
            if (!NAME.matcher(name).matches()) {
                ctx.err.println("error: the name must match [a-z][a-z0-9_-]*");
                return 2;
            }
            ConfigRepository config = ctx.loadConfig();
            if (config == null) {
                return 2;
            }
            List<String> missing = agents.stream().filter(a -> config.agent(a).isEmpty()).toList();
            if (!missing.isEmpty()) {
                ctx.err.println("error: unknown agents " + missing + "; available: " + config.agents().stream().map(Agent::name).toList());
                return 2;
            }
            String leadName = lead == null ? agents.get(0) : lead;
            if (!agents.contains(leadName)) {
                ctx.err.println("error: the lead '" + leadName + "' must be one of the agents");
                return 2;
            }
            StringBuilder yaml = new StringBuilder("schema: 1\nname: " + name + "\nlead: " + leadName + "\nagents: [" + String.join(", ", agents) + "]\n");
            if (model != null) {
                try {
                    ModelRef ref = BuildCli.parseModel(model);
                    yaml.append("runtime:\n  default: { provider: ").append(ref.provider()).append(", model: ").append(ref.model()).append(" }\n");
                } catch (IllegalArgumentException e) {
                    ctx.err.println("error: " + e.getMessage());
                    return 2;
                }
            }
            Path file = (global ? ctx.globalDir() : ctx.cwd.resolve(".buildcli")).resolve("teams").resolve(name + ".yaml");
            if (Files.exists(file)) {
                ctx.err.println("error: " + file + " already exists");
                return 2;
            }
            Files.createDirectories(file.getParent());
            Files.writeString(file, yaml.toString(), StandardCharsets.UTF_8);
            ctx.out.println("created  " + file);
            if (model == null) {
                ctx.out.println("No model set: add 'runtime: default: { provider: ollama, model: <name> }' or pass --model when running.");
            }
            return 0;
        }
    }
}

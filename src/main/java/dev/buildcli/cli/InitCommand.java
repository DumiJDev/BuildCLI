package dev.buildcli.cli;

import dev.buildcli.domain.ModelRef;
import dev.buildcli.infrastructure.FileTrustStore;
import dev.buildcli.ports.ConfigRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "init", description = "Create three sample agents (architect, developer, reviewer), a group with them, and an AGENTS.md")
final class InitCommand implements Callable<Integer> {
    private final CliContext ctx;

    @Option(names = "--model", description = "Default model for the agents as provider:model (default: ${DEFAULT-VALUE})",
            defaultValue = "ollama:qwen2.5:7b")
    String model;

    InitCommand(CliContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public Integer call() throws IOException {
        ModelRef ref;
        try {
            ref = BuildCli.parseModel(model);
        } catch (IllegalArgumentException e) {
            ctx.err.println("error: " + e.getMessage());
            return 2;
        }
        Path base = ctx.cwd.resolve(".buildcli");
        int created = 0;
        created += write(base.resolve("agents/ana.md"), ANA);
        created += write(base.resolve("agents/bruno.md"), BRUNO);
        created += write(base.resolve("agents/carla.md"), CARLA);
        created += write(ctx.cwd.resolve("AGENTS.md"), AGENTS_MD);

        // the group and the model are this machine's choices for this project: kept in its state directory, not the repo
        var groups = new dev.buildcli.infrastructure.FileChatStore(ctx.projectStateDir());
        if (groups.load().isEmpty()) {
            groups.save(java.util.List.of(new dev.buildcli.domain.Chat("#backend", "backend", true, java.util.List.of("ana", "bruno", "carla"),
                    java.util.List.of("ana"))));
            ctx.out.println("created  group 'backend' with ana (admin), bruno and carla");
        }
        var settings = new dev.buildcli.application.Settings(new dev.buildcli.infrastructure.FileSettingsStore(ctx.globalDir(), ctx.projectStateDir()));
        if (settings.stored(dev.buildcli.ports.SettingsStore.Scope.PROJECT, dev.buildcli.application.Settings.DEFAULT_MODEL) == null) {
            settings.set(dev.buildcli.ports.SettingsStore.Scope.PROJECT, dev.buildcli.application.Settings.DEFAULT_MODEL, ref.provider() + ":" + ref.model());
            ctx.out.println("set      default model " + ref.provider() + ":" + ref.model() + " for this project");
        }

        ConfigRepository config = ctx.loadConfig();
        if (config == null) {
            return 2;
        }
        // The user just asked for these files, so approving them is implied; anything added later asks again.
        if (created > 0 && !config.projectDigest().isEmpty()) {
            new FileTrustStore(ctx.trustFile()).trust(ctx.projectKey(), config.projectDigest());
        }
        ctx.out.println();
        ctx.out.println("Next steps:");
        ctx.out.println("  1. buildcli doctor                            check Java, git, the model provider and the config");
        ctx.out.println("  2. edit AGENTS.md and .buildcli/agents/*.md    describe your project and tune each agent's permissions");
        ctx.out.println("  3. buildcli                                    open the chat (F2 for settings: models, providers, agents)");
        ctx.out.println();
        ctx.out.println("The agents use " + ref.provider() + ":" + ref.model() + " unless you choose another model per agent in the settings"
                + " (F2 > Models); small models (3B) are unreliable for tool use.");
        return 0;
    }

    private int write(Path file, String content) throws IOException {
        Path shown = ctx.cwd.relativize(file);
        if (Files.exists(file)) {
            ctx.out.println("skipped  " + shown + " (already exists)");
            return 0;
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        ctx.out.println("created  " + shown);
        return 1;
    }

    private static final String ANA = """
            ---
            schema: 1
            name: ana
            role: architect
            description: Owns architecture, boundaries and technical trade-offs. Leads the team.
            capabilities: [filesystem.read, search, git.read, agent.handoff]
            permissions:
              filesystem:
                read: ["**"]
            ---
            You are the software architect and the admin of the backend group.
            You do not implement production code yourself: read the code, decide how the work should be done, and delegate
            the implementation to a teammate with a handoff. Give a short brief (decisions, constraints, relevant paths), not
            a transcript. When teammates report back, check that the result answers the request, then give the user a short
            final report. Challenge unnecessary complexity.
            """;

    private static final String BRUNO = """
            ---
            schema: 1
            name: bruno
            role: developer
            description: Implements tasks and runs the build.
            capabilities: [filesystem.read, filesystem.write, search, git.read, command.execute]
            permissions:
              filesystem:
                read: ["**"]
                write: ["src/**"]       # every write is still shown to you as a diff and needs your approval
              command:
                allow:                   # argv arrays; anything else asks you first
                  - ["mvn", "-q", "test"]
                  - ["mvn", "-q", "verify"]
                timeout: 10m
            ---
            You implement the tasks you are given with small, focused changes that follow the project's conventions in
            AGENTS.md. Run the tests after you change code. When you are done, report what you changed and the test result.
            """;

    private static final String CARLA = """
            ---
            schema: 1
            name: carla
            role: reviewer
            description: Reviews changes for correctness, tests and style.
            capabilities: [filesystem.read, search, git.read]
            permissions:
              filesystem:
                read: ["**"]
            ---
            You review changes. Use git to see what changed, read the affected code and its tests, and report concrete
            problems (bugs, missing tests, unclear naming) with file and line. Say plainly when the change looks good.
            """;

    private static final String AGENTS_MD = """
            # Project context

            <!-- BuildCLI gives this file to its agents as background information (it can never change what they are
                 allowed to do). Keep it short and factual. -->

            ## Build and test
            - Build: `mvn -q verify`
            - Run the tests: `mvn -q test`

            ## Architecture
            Describe the main modules and how they fit together.

            ## Conventions
            List the code style, naming and testing rules the team follows.
            """;
}

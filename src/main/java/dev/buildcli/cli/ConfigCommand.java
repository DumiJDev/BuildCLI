package dev.buildcli.cli;

import dev.buildcli.infrastructure.ProviderSettings;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;

@Command(name = "config", description = "Show where BuildCLI reads its configuration and keeps its state")
final class ConfigCommand implements Callable<Integer> {
    private final CliContext ctx;

    ConfigCommand(CliContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public Integer call() {
        ProviderSettings s = ProviderSettings.fromEnvironment(ctx.env);
        String v = BuildCli.class.getPackage().getImplementationVersion();
        ctx.out.println("buildcli " + (v == null ? "dev" : v));
        ctx.out.println();
        ctx.out.println("project directory : " + ctx.cwd);
        ctx.out.println("project config    : " + ctx.cwd.resolve(".buildcli") + "  (agents/, teams/), and AGENTS.md");
        ctx.out.println("global directory  : " + ctx.globalDir() + "  (override with BUILDCLI_HOME)");
        ctx.out.println("state database    : " + ctx.stateDb());
        ctx.out.println("trust file        : " + ctx.trustFile());
        ctx.out.println();
        ctx.out.println("ollama            : " + s.ollamaUrl() + "  (OLLAMA_HOST)");
        ctx.out.println("openai-compatible : " + s.openAiUrl() + "  (OPENAI_BASE_URL)");
        String key = ctx.env.get("OPENAI_API_KEY");
        ctx.out.println("OPENAI_API_KEY    : " + (key == null || key.isBlank() ? "not set" : "set (not shown)"));
        return 0;
    }
}

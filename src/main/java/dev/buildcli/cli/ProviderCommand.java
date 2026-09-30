package dev.buildcli.cli;

import dev.buildcli.domain.Agent;
import dev.buildcli.domain.ModelRef;
import dev.buildcli.infrastructure.ProviderRegistry;
import dev.buildcli.infrastructure.ProviderSettings;
import dev.buildcli.infrastructure.ProviderSpec;
import dev.buildcli.ports.LlmMessage;
import dev.buildcli.ports.LlmReply;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "provider", description = "List, add and test the places models come from (Ollama, OpenRouter, DeepSeek, Kimi, ...)",
        subcommands = {ProviderCommand.ListCmd.class, ProviderCommand.AddCmd.class, ProviderCommand.TestCmd.class})
final class ProviderCommand implements Callable<Integer> {
    private final CliContext ctx;

    ProviderCommand(CliContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public Integer call() {
        ctx.out.println("Use one of: provider list | provider add <name> --url <url> | provider test <provider:model>");
        return 2;
    }

    @Command(name = "list", description = "List the known providers and whether their API key is set")
    static final class ListCmd implements Callable<Integer> {
        private final CliContext ctx;

        ListCmd(CliContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public Integer call() {
            ProviderSettings settings;
            try {
                settings = ctx.providerSettings();
            } catch (IllegalArgumentException e) {
                ctx.err.println("error: " + e.getMessage());
                return 2;
            }
            List<List<String>> rows = settings.registry().all().stream().map(s -> List.of(s.name(), s.baseUrl(),
                    !s.needsKey() ? "no key needed" : ctx.env.getOrDefault(s.apiKeyEnv(), "").isBlank() ? s.apiKeyEnv() + " (not set)"
                            : s.apiKeyEnv() + " (set)")).toList();
            Tables.print(ctx.out, List.of("PROVIDER", "URL", "API KEY"), rows);
            ctx.out.println();
            ctx.out.println("Use a model as provider:model, e.g. openrouter:openrouter/free or deepseek:deepseek-chat.");
            ctx.out.println("Your own providers live in " + ctx.globalDir().resolve(ProviderRegistry.FILE_NAME) + ". Keys are read from the environment, never stored.");
            return 0;
        }
    }

    @Command(name = "add", description = "Add an OpenAI-compatible (or Ollama) provider to your providers.yaml")
    static final class AddCmd implements Callable<Integer> {
        private final CliContext ctx;

        @Parameters(index = "0", paramLabel = "NAME", description = "Lowercase name, used in provider:model")
        String name;

        @Option(names = "--url", required = true, description = "Base URL, e.g. https://api.example.com/v1")
        String url;

        @Option(names = "--key-env", description = "Environment variable that holds the API key (omit for servers that need none)")
        String keyEnv;

        @Option(names = "--kind", defaultValue = "openai-compatible", description = "openai-compatible (default) or ollama")
        String kind;

        AddCmd(CliContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public Integer call() throws Exception {
            ProviderSpec.Kind k = kind.equals("ollama") ? ProviderSpec.Kind.OLLAMA : ProviderSpec.Kind.OPENAI_COMPATIBLE;
            if (!kind.equals("ollama") && !kind.equals("openai-compatible")) {
                ctx.err.println("error: --kind must be openai-compatible or ollama");
                return 2;
            }
            try {
                ProviderRegistry.save(ctx.globalDir(), new ProviderSpec(name, k, url, keyEnv, ""));
            } catch (IllegalArgumentException e) {
                ctx.err.println("error: " + e.getMessage());
                return 2;
            }
            ctx.out.println("Added provider '" + name + "' to " + ctx.globalDir().resolve(ProviderRegistry.FILE_NAME));
            if (keyEnv != null) {
                ctx.out.println("Set " + keyEnv + " in your environment, then try: buildcli provider test " + name + ":<model>");
            }
            return 0;
        }
    }

    @Command(name = "test", description = "Send one tiny request to provider:model to check the key, the URL and the model name")
    static final class TestCmd implements Callable<Integer> {
        private final CliContext ctx;

        @Parameters(index = "0", paramLabel = "PROVIDER:MODEL", description = "For example openrouter:openrouter/free")
        String model;

        TestCmd(CliContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public Integer call() {
            ModelRef ref;
            try {
                ref = BuildCli.parseModel(model);
            } catch (IllegalArgumentException e) {
                ctx.err.println("error: " + e.getMessage());
                return 2;
            }
            long start = System.nanoTime();
            try {
                LlmReply reply = ctx.gateways.create(ref, ctx.providerSettings().with(null, null, false)).chat(
                        new Agent("probe", "probe", "", java.util.Set.of(), dev.buildcli.domain.Permissions.none()),
                        List.of(new LlmMessage.User("Reply with the single word: pong")), List.of());
                long ms = (System.nanoTime() - start) / 1_000_000;
                ctx.out.println("ok  " + ref.provider() + ":" + ref.model() + "  " + ms + " ms  " + reply.inputTokens() + " in / "
                        + reply.outputTokens() + " out");
                ctx.out.println("reply: " + (reply.text() == null ? "(empty)" : reply.text().strip()));
                return 0;
            } catch (RuntimeException e) {
                Throwable root = e;
                while (root.getCause() != null && root.getCause() != root) {
                    root = root.getCause();
                }
                ctx.err.println("failed  " + ref.provider() + ":" + ref.model() + ": " + e.getMessage()
                        + (root != e && root.getMessage() != null ? " (" + root.getMessage() + ")" : ""));
                return 1;
            }
        }
    }
}

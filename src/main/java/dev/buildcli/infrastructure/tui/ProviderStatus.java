package dev.buildcli.infrastructure.tui;

import dev.buildcli.infrastructure.ModelCatalog;
import dev.buildcli.infrastructure.ProviderRegistry;
import dev.tamboui.style.Color;
import java.util.ArrayList;
import java.util.List;

/** What a provider still needs before it can be used, in one short line and a few lines of help. */
record ProviderStatus(String text, Color color, boolean ready, List<String> help) {
    /** @param r what asking the provider for its models returned, or null while that is still going on */
    static ProviderStatus of(SettingsServices.Provider p, ModelCatalog.Result r) {
        if (p.keyEnv() != null && !p.keySet()) {
            return new ProviderStatus("needs a key", Theme.AMBER, false, keyHelp(p));
        }
        if (r == null) {
            return new ProviderStatus("checking…", Theme.DIM, false, List.of("Checking " + p.url() + " …"));
        }
        if (r.problem() != null) {
            if (p.local()) {
                return new ProviderStatus("not running", Theme.DIM, false, localHelp(p));
            }
            boolean rejected = r.problem().startsWith("HTTP 401") || r.problem().startsWith("HTTP 403");
            if (rejected && p.keyEnv() != null) {
                return new ProviderStatus("key rejected", Theme.RED, false, List.of(r.problem(), "",
                        p.keyFrom().equals("environment") ? "The key comes from the environment variable " + p.keyEnv() + ". Fix it there, or press K to save "
                                + "a different key here (a variable that is set wins, so unset it first)."
                                : "Press K to enter a new key."));
            }
            return new ProviderStatus(p.keyEnv() == null ? "not reachable" : "key or endpoint failed", Theme.RED, false,
                    List.of(r.problem(), "", "Check the URL " + p.url() + (p.keyEnv() == null ? "" : " and the key (press K to enter a new one)")
                            + ", then press R to check again."));
        }
        if (r.models().isEmpty()) {
            return new ProviderStatus("no models yet", Theme.AMBER, false, p.name().equals("ollama")
                    ? List.of("Ollama is running but has no models. Download one in a terminal:", "",
                            "    ollama pull qwen2.5-coder:7b", "", "then press R. Models of 7B or more work much better with tools.")
                    : List.of(p.name() + " answered but lists no models. Press Enter to type a model name yourself."));
        }
        long free = r.models().stream().filter(ModelCatalog.Model::free).count();
        String count = r.models().size() + (r.models().size() == 1 ? " model" : " models") + (p.local() || free == 0 ? "" : ", " + free + " free");
        return new ProviderStatus("ready · " + count, Theme.GREEN, true, List.of((p.description().isBlank() ? p.name() : p.description())
                + ". Press Enter to choose a model."));
    }

    private static List<String> keyHelp(SettingsServices.Provider p) {
        List<String> help = new ArrayList<>();
        String page = ProviderRegistry.keyPage(p.name());
        if (!p.description().isBlank()) {
            help.add(p.description() + ".");
            help.add("");
        }
        help.add("1. " + (page != null ? "Create a key at " + page : "Get a key from the provider"));
        help.add("2. Press Enter here and paste it. It is saved only on this computer, readable by your account only.");
        help.add("");
        help.add("Prefer an environment variable? Set " + p.keyEnv() + " before starting BuildCLI; it always wins over a saved key.");
        return help;
    }

    private static List<String> localHelp(SettingsServices.Provider p) {
        return switch (p.name()) {
            case "ollama" -> List.of("Ollama runs models on this machine, free and private.", "",
                    "1. Install it from https://ollama.com", "2. Start it:  ollama serve", "3. Download a model:  ollama pull qwen2.5-coder:7b",
                    "4. Press R to check again.");
            case "lmstudio" -> List.of("LM Studio runs models on this machine, free and private.", "",
                    "1. Install it from https://lmstudio.ai and download a model", "2. Start its server: Developer tab › Start server",
                    "3. Press R to check again.");
            default -> List.of("Nothing answers at " + p.url() + ". Start the server, then press R to check again.");
        };
    }
}

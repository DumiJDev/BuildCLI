package dev.buildcli.infrastructure;

import dev.buildcli.domain.ModelRef;
import dev.buildcli.ports.LlmGateway;
import java.util.Map;

/**
 * Where each provider lives and how to talk to it. Model choice ("ollama / qwen3-coder") is team configuration; this
 * is machine configuration: URLs, the API key (read from the environment, never stored) and generation settings.
 */
public record ProviderSettings(String ollamaUrl, String openAiUrl, String openAiApiKey, int ollamaThreads, double temperature,
                               boolean streaming) {

    public static final String DEFAULT_OLLAMA_URL = "http://localhost:11434";
    public static final String DEFAULT_OPENAI_URL = "https://api.openai.com/v1";

    /** Reads OLLAMA_HOST, OPENAI_BASE_URL and OPENAI_API_KEY; everything else takes its default. */
    public static ProviderSettings fromEnvironment(Map<String, String> env) {
        String ollama = env.getOrDefault("OLLAMA_HOST", DEFAULT_OLLAMA_URL);
        if (!ollama.startsWith("http")) {
            ollama = "http://" + ollama;
        }
        String key = env.get("OPENAI_API_KEY");
        return new ProviderSettings(ollama, env.getOrDefault("OPENAI_BASE_URL", DEFAULT_OPENAI_URL),
                key == null || key.isBlank() ? "not-needed" : key, 4, 0.0, true);
    }

    /** Builds the gateway for one provider/model pair. */
    public LlmGateway gatewayFor(ModelRef ref) {
        return switch (ref.provider()) {
            case "ollama" -> LangChain4jGateway.ollama(ollamaUrl, ref.model(), ollamaThreads, temperature, streaming);
            case "openai" -> LangChain4jGateway.openAiCompatible(openAiUrl, openAiApiKey, ref.model(), temperature, streaming);
            default -> throw new IllegalArgumentException("unknown provider '" + ref.provider() + "' (expected ollama or openai)");
        };
    }
}

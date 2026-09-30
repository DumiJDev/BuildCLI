package dev.buildcli.infrastructure.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.buildcli.application.ChatSession;
import dev.buildcli.application.Settings;
import dev.buildcli.domain.Task;
import dev.buildcli.domain.TaskStatus;
import dev.buildcli.infrastructure.ModelCatalog;
import dev.buildcli.ports.SettingsStore.Scope;
import dev.tamboui.tui.event.KeyCode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The connect screen: what each provider needs, choosing a model, and saving it only after it answered. */
class ConnectViewTest {
    @TempDir Path dir;

    final Settings settings = Settings.defaults();
    final List<String> tested = new ArrayList<>();

    /** Ollama running with one model, DeepSeek with a key that fails, OpenRouter without a key. */
    SettingsServices services() {
        SettingsServices basic = ChatScreen.basicServices(session(), Map.of());
        return new SettingsServices() {
            @Override
            public Settings settings() {
                return settings;
            }

            @Override
            public List<Provider> providers() {
                return List.of(new Provider("openrouter", "https://openrouter.ai/api/v1", "OPENROUTER_API_KEY", false, false, "hundreds of models"),
                        new Provider("deepseek", "https://api.deepseek.com/v1", "DEEPSEEK_API_KEY", true, false, "DeepSeek"),
                        new Provider("ollama", "http://localhost:11434", null, false, false, "local models through Ollama"));
            }

            @Override
            public void addProvider(String name, String url, String keyEnv) { }

            @Override
            public void removeProvider(String name) { }

            @Override
            public CompletableFuture<String> test(String model) {
                tested.add(model);
                return CompletableFuture.completedFuture(model.endsWith("qwen2.5-coder:7b") ? "ok 42 ms: pong" : "failed after 5 ms: model not found");
            }

            @Override
            public CompletableFuture<ModelCatalog.Result> models(String provider) {
                return CompletableFuture.completedFuture(switch (provider) {
                    case "ollama" -> new ModelCatalog.Result(List.of(new ModelCatalog.Model("ollama:qwen2.5-coder:7b", "local", true, true)), null);
                    case "deepseek" -> new ModelCatalog.Result(List.of(), "HTTP 401: invalid key");
                    default -> new ModelCatalog.Result(List.of(), "not expected");
                });
            }

            @Override
            public List<AgentInfo> agents() {
                return basic.agents();
            }

            @Override
            public String createAgent(String name, String role, String instructions, List<String> capabilities, boolean global) {
                return "";
            }

            @Override
            public void deleteAgent(String name) { }

            @Override
            public String teamModel(String agent) {
                return null;
            }
        };
    }

    ChatSession session() {
        return new ChatSession(ChatScreenTest.TEAM, (team, request, ui, cancelled, dispatcher) -> {
            Task t = new Task(1, null, "user", "ana", request.text(), "");
            t.status = TaskStatus.DONE;
            t.result = "ok";
            return t;
        });
    }

    ChatScreen screen(Map<String, String> models) {
        return new ChatScreen(session(), models, dir, () -> { }, services());
    }

    @Test
    void opensByItselfWhenNoAgentHasAModelAndSaysWhatEachProviderNeeds() {
        var screen = screen(Map.of());
        assertTrue(screen.connectOpenForTest());
        String out = ChatScreenTest.render(screen, 130, 40);
        assertTrue(out.contains("Connect a model"), out);
        assertTrue(out.contains("None of your agents has a model yet"), out);
        assertTrue(out.contains("ready · 1 model"), "ollama is listed first, running with one model:\n" + out);
        assertTrue(out.contains("key or endpoint failed"), out);
        assertTrue(out.contains("needs OPENROUTER_API_KEY"), out);

        ChatScreenTest.key(screen, KeyCode.DOWN);
        ChatScreenTest.key(screen, KeyCode.DOWN);
        out = ChatScreenTest.render(screen, 130, 40);
        assertTrue(out.contains("https://openrouter.ai/keys"), "where to get the key:\n" + out);
        assertTrue(out.contains("OPENROUTER_API_KEY"), out);
        assertTrue(out.contains("never writes them to disk"), out);

        ChatScreenTest.key(screen, KeyCode.ENTER);
        assertTrue(ChatScreenTest.render(screen, 130, 40).contains("1 Provider"), "a provider without its key cannot be chosen");
    }

    @Test
    void aModelThatAnswersBecomesTheDefault() {
        var screen = screen(Map.of());
        ChatScreenTest.render(screen, 130, 40);
        ChatScreenTest.key(screen, KeyCode.ENTER);
        String out = ChatScreenTest.render(screen, 130, 40);
        assertTrue(out.contains("Which ollama model?"), out);
        assertTrue(out.contains("qwen2.5-coder:7b"), out);
        ChatScreenTest.key(screen, KeyCode.ENTER);
        out = ChatScreenTest.render(screen, 130, 40);
        assertTrue(out.contains("It answered \"pong\" in 42 ms"), out);
        assertNull(settings.defaultModel(), "nothing is saved before the user chooses where");

        ChatScreenTest.type(screen, "p");
        assertFalse(screen.connectOpenForTest());
        assertEquals("ollama:qwen2.5-coder:7b", settings.defaultModel());
        assertEquals("ollama:qwen2.5-coder:7b", settings.stored(Scope.PROJECT, Settings.DEFAULT_MODEL));
        assertTrue(ChatScreenTest.render(screen, 130, 40).contains("Default model: ollama:qwen2.5-coder:7b"));
    }

    @Test
    void aModelThatFailsTheTestIsNeverSaved() {
        var screen = screen(Map.of());
        ChatScreenTest.render(screen, 130, 40);
        ChatScreenTest.key(screen, KeyCode.ENTER);
        ChatScreenTest.type(screen, "llama-typo");
        String out = ChatScreenTest.render(screen, 130, 40);
        assertTrue(out.contains("No match: Enter tests ollama:llama-typo"), out);
        ChatScreenTest.key(screen, KeyCode.ENTER);
        out = ChatScreenTest.render(screen, 130, 40);
        assertTrue(out.contains("model not found"), out);
        ChatScreenTest.type(screen, "p");
        ChatScreenTest.type(screen, "g");
        assertNull(settings.defaultModel());
        assertTrue(screen.connectOpenForTest());
        assertEquals(List.of("ollama:llama-typo"), tested);
    }

    @Test
    void withAModelTheChatOpensAsUsualAndSlashConnectStillReachesIt() {
        var screen = screen(Map.of("ana", "ollama:qwen2.5-coder:7b", "bruno", "ollama:qwen2.5-coder:7b"));
        assertFalse(screen.connectOpenForTest());
        ChatScreenTest.render(screen, 130, 40);
        ChatScreenTest.type(screen, "/connect");
        ChatScreenTest.key(screen, KeyCode.ENTER);
        assertTrue(screen.connectOpenForTest());
        ChatScreenTest.key(screen, KeyCode.ESCAPE);
        assertFalse(screen.connectOpenForTest(), "Esc on the first step closes it");
    }
}

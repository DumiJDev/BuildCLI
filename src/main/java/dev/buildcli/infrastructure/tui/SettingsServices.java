package dev.buildcli.infrastructure.tui;

import dev.buildcli.application.Settings;
import dev.buildcli.infrastructure.ModelCatalog;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** What the settings screen needs from the rest of the app: storage, providers, model lists and agent files. */
public interface SettingsServices {
    Settings settings();

    record Provider(String name, String url, String keyEnv, boolean keySet, boolean removable) {}

    List<Provider> providers();

    void addProvider(String name, String url, String keyEnv) throws Exception;

    void removeProvider(String name) throws Exception;

    /** One line: "ok 1200 ms: pong" or the reason it failed. */
    CompletableFuture<String> test(String model);

    CompletableFuture<ModelCatalog.Result> models(String provider);

    record AgentInfo(String name, String role, String origin, String file, List<String> capabilities) {}

    List<AgentInfo> agents();

    /** @return where the agent file was written */
    String createAgent(String name, String role, String instructions, List<String> capabilities, boolean global) throws Exception;

    void deleteAgent(String name) throws Exception;

    /** The model the team configuration gives an agent, or null. */
    String teamModel(String agent);
}

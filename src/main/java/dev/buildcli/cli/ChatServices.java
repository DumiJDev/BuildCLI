package dev.buildcli.cli;

import dev.buildcli.application.Settings;
import dev.buildcli.domain.Agent;
import dev.buildcli.domain.Capability;
import dev.buildcli.domain.ModelRef;
import dev.buildcli.domain.Origin;
import dev.buildcli.domain.Team;
import dev.buildcli.infrastructure.FileSettingsStore;
import dev.buildcli.infrastructure.ModelCatalog;
import dev.buildcli.infrastructure.ProviderProbe;
import dev.buildcli.infrastructure.ProviderRegistry;
import dev.buildcli.infrastructure.ProviderSettings;
import dev.buildcli.infrastructure.ProviderSpec;
import dev.buildcli.infrastructure.tui.SettingsServices;
import dev.buildcli.ports.ConfigRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/** The settings screen's view of the machine: settings files, providers, the model catalog and agent files. */
final class ChatServices implements SettingsServices {
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_-]{0,39}");

    private final CliContext ctx;
    private final ConfigRepository config;
    private final Team team;
    private final Settings settings;
    private volatile ModelCatalog catalog;

    ChatServices(CliContext ctx, ConfigRepository config, Team team) {
        this.ctx = ctx;
        this.config = config;
        this.team = team;
        this.settings = new Settings(new FileSettingsStore(ctx.globalDir(), ctx.projectStateDir()));
    }

    @Override
    public Settings settings() {
        return settings;
    }

    private ProviderSettings providerSettings() {
        return ctx.providerSettings();
    }

    @Override
    public List<Provider> providers() {
        List<String> mine = ProviderRegistry.userDefined(ctx.globalDir());
        List<Provider> out = new ArrayList<>();
        for (ProviderSpec s : providerSettings().registry().all()) {
            boolean keySet = s.needsKey() && !ctx.env.getOrDefault(s.apiKeyEnv(), "").isBlank();
            out.add(new Provider(s.name(), s.baseUrl(), s.apiKeyEnv(), keySet, mine.contains(s.name()) && !ProviderRegistry.isBuiltIn(s.name())));
        }
        return out;
    }

    @Override
    public void addProvider(String name, String url, String keyEnv) throws Exception {
        ProviderRegistry.save(ctx.globalDir(), new ProviderSpec(name, ProviderSpec.Kind.OPENAI_COMPATIBLE, url, keyEnv, ""));
        catalog = null;
    }

    @Override
    public void removeProvider(String name) throws Exception {
        if (!ProviderRegistry.remove(ctx.globalDir(), name)) {
            throw new IllegalArgumentException(name + " is built in or not in your providers file");
        }
        catalog = null;
    }

    @Override
    public CompletableFuture<String> test(String model) {
        return CompletableFuture.supplyAsync(() -> {
            ModelRef ref;
            try {
                ref = BuildCli.parseModel(model);
            } catch (IllegalArgumentException e) {
                return e.getMessage();
            }
            var o = ProviderProbe.test(ref, r -> ctx.gateways.create(r, providerSettings().with(null, null, false)));
            return o.ok() ? "ok " + o.millis() + " ms: " + o.text() : "failed after " + o.millis() + " ms: " + o.text();
        });
    }

    @Override
    public CompletableFuture<ModelCatalog.Result> models(String provider) {
        ModelCatalog c = catalog;
        if (c == null) {
            c = new ModelCatalog(providerSettings());
            catalog = c;
        }
        return c.models(provider);
    }

    @Override
    public List<AgentInfo> agents() {
        return config.agents().stream().map(a -> new AgentInfo(a.name(), a.role(), switch (a.origin()) {
            case PROJECT -> "this project";
            case GLOBAL -> "all projects";
            default -> "built in";
        }, a.source(), a.capabilities().stream().sorted().toList())).toList();
    }

    @Override
    public String createAgent(String name, String role, String instructions, List<String> capabilities, boolean global) throws Exception {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("the name must be lowercase letters, digits, - and _");
        }
        List<String> unknown = capabilities.stream().filter(c -> !Capability.KNOWN.contains(c)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("unknown capabilities " + unknown);
        }
        if (config.agent(name).isPresent()) {
            throw new IllegalArgumentException("there is already an agent named " + name);
        }
        Path file = (global ? ctx.globalDir() : ctx.cwd.resolve(".buildcli")).resolve("agents").resolve(name + ".md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, AgentCommand.CreateCmd.template(name, role.isBlank() ? "developer" : role,
                capabilities.isEmpty() ? List.of(Capability.FILESYSTEM_READ, Capability.SEARCH) : capabilities, instructions), StandardCharsets.UTF_8);
        return file.toString();
    }

    @Override
    public void deleteAgent(String name) throws Exception {
        Agent a = config.agent(name).orElseThrow(() -> new IllegalArgumentException("no agent named " + name));
        if (a.origin() == Origin.BUILTIN || a.source() == null || a.source().isBlank()) {
            throw new IllegalArgumentException(name + " is built in and cannot be deleted");
        }
        Path file = Path.of(a.source()).toAbsolutePath().normalize();
        Path projectAgents = ctx.cwd.resolve(".buildcli").resolve("agents").toAbsolutePath().normalize();
        Path globalAgents = ctx.globalDir().resolve("agents").toAbsolutePath().normalize();
        if (!file.startsWith(projectAgents) && !file.startsWith(globalAgents)) {
            throw new IllegalArgumentException("refusing to delete a file outside the agents folders: " + file);
        }
        Files.deleteIfExists(file);
    }

    @Override
    public String teamModel(String agent) {
        ModelRef ref = team.routing().forAgent(agent);
        if (ref == null) {
            for (Team t : config.teams()) {
                if (t.agent(agent).isPresent() && t.routing().forAgent(agent) != null) {
                    ref = t.routing().forAgent(agent);
                    break;
                }
            }
        }
        return ref == null ? null : ref.provider() + ":" + ref.model();
    }
}

package dev.buildcli.cli;

import dev.buildcli.application.Settings;
import dev.buildcli.domain.Agent;
import dev.buildcli.domain.Chat;
import dev.buildcli.domain.Limits;
import dev.buildcli.domain.ModelRef;
import dev.buildcli.domain.ModelRouting;
import dev.buildcli.domain.Team;
import dev.buildcli.infrastructure.FileChatStore;
import dev.buildcli.infrastructure.FileSettingsStore;
import dev.buildcli.ports.ConfigRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * BuildCLI is about agents, not teams: agents are the people you talk to, and groups are chats you put them in. This
 * gathers what a chat needs from the project: the agents, the groups (saved ones, plus team files from before groups
 * existed, turned into groups), the model for each agent and the limits.
 */
final class ChatSetup {
    final CliContext ctx;
    final ConfigRepository config;
    final Settings settings;
    final FileChatStore store;

    ChatSetup(CliContext ctx, ConfigRepository config) {
        this.ctx = ctx;
        this.config = config;
        this.settings = new Settings(new FileSettingsStore(ctx.globalDir(), ctx.projectStateDir()));
        this.store = new FileChatStore(ctx.projectStateDir());
    }

    static String groupId(String name) {
        return "#" + name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    /** Saved groups first, then a group for each team file that is not saved yet (its lead becomes admin). */
    List<Chat> groups() {
        Map<String, Chat> all = new LinkedHashMap<>();
        for (Chat g : store.load()) {
            if (!g.id().isEmpty()) {
                all.put(g.id(), g);
            }
        }
        for (Team t : config.teams()) {
            all.putIfAbsent(groupId(t.name()), new Chat(groupId(t.name()), t.name(), true, t.agents().stream().map(Agent::name).toList(),
                    List.of(t.lead())));
        }
        return new ArrayList<>(all.values());
    }

    Chat group(String nameOrId) {
        for (Chat g : groups()) {
            if (g.id().equals(nameOrId) || g.name().equalsIgnoreCase(nameOrId) || g.id().equals(groupId(nameOrId))) {
                return g;
            }
        }
        return null;
    }

    /** Limits of the first team file, if any; otherwise the defaults. */
    Limits limits() {
        return config.teams().isEmpty() ? Limits.defaults() : config.teams().get(0).limits();
    }

    /**
     * The model of each agent: the settings screen's choice, else what a team file gave it, else the default model from
     * the settings (the command line's --model fills any remaining gap).
     */
    ModelRouting routing() {
        Map<String, ModelRef> overrides = new HashMap<>();
        for (Team t : config.teams()) {
            for (Agent a : t.agents()) {
                ModelRef ref = t.routing().forAgent(a.name());
                if (ref != null) {
                    overrides.putIfAbsent(a.name(), ref);
                }
            }
        }
        for (Agent a : config.agents()) {
            String m = settings.modelFor(a.name());
            if (m != null) {
                try {
                    overrides.put(a.name(), BuildCli.parseModel(m));
                } catch (IllegalArgumentException ignored) {
                    // an unparsable value is shown on the settings screen; the other sources still apply
                }
            }
        }
        ModelRef def = null;
        if (settings.defaultModel() != null) {
            try {
                def = BuildCli.parseModel(settings.defaultModel());
            } catch (IllegalArgumentException ignored) {
                // as above
            }
        }
        return new ModelRouting(def, overrides);
    }

    /** The group as a team for one run: its members, led by its first admin. */
    Team teamOf(Chat g) {
        List<Agent> members = new ArrayList<>();
        for (String m : g.members()) {
            config.agent(m).ifPresent(members::add);
        }
        String lead = g.admins().isEmpty() ? g.members().get(0) : g.admins().get(0);
        return new Team(g.name(), lead, members, limits(), routing());
    }
}

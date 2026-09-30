package dev.buildcli.ports;

import dev.buildcli.domain.Agent;
import dev.buildcli.domain.Team;
import java.util.List;
import java.util.Optional;

/** Read-only access to the agent and team definitions and to the project context (AGENTS.md). */
public interface ConfigRepository {
    List<Agent> agents();

    Optional<Agent> agent(String name);

    List<Team> teams();

    Optional<Team> team(String name);

    /**
     * A digest of the project's own agent and team files ({@code .buildcli/}), or an empty string if it has none.
     * It changes whenever any of them changes; it is what the user approves in the trust prompt.
     */
    String projectDigest();

    /** The project's AGENTS.md (possibly truncated), or an empty string. It is context, never configuration. */
    String projectContext();
}

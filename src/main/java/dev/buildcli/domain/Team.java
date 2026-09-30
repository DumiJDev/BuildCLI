package dev.buildcli.domain;

import java.util.List;
import java.util.Optional;

public record Team(String name, String lead, List<Agent> agents, Limits limits, ModelRouting routing) {

    public Team(String name, String lead, List<Agent> agents, Limits limits) {
        this(name, lead, agents, limits, ModelRouting.unspecified());
    }

    public Optional<Agent> agent(String name) {
        return agents.stream().filter(a -> a.name().equalsIgnoreCase(name)).findFirst();
    }
}

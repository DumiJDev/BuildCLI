package dev.buildcli.spike.domain;

import java.util.List;
import java.util.Optional;

public record Team(String name, String lead, List<Agent> agents, Limits limits) {

    public Optional<Agent> agent(String name) {
        return agents.stream().filter(a -> a.name().equalsIgnoreCase(name)).findFirst();
    }
}

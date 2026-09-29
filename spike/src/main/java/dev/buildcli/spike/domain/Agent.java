package dev.buildcli.spike.domain;

import java.util.Set;

public record Agent(String name, String role, String instructions, Set<String> capabilities, Permissions permissions) {

    public boolean can(String capability) {
        return capabilities.contains(capability);
    }
}

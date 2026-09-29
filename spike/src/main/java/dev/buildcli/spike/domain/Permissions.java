package dev.buildcli.spike.domain;

import java.time.Duration;
import java.util.List;

/** Per-agent permissions, enforced by the runtime (never by the prompt). */
public record Permissions(List<String> writeGlobs, List<List<String>> commandAllow, Duration commandTimeout) {

    public static Permissions none() {
        return new Permissions(List.of(), List.of(), Duration.ofSeconds(30));
    }
}

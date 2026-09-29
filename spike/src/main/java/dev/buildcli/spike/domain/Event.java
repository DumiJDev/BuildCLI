package dev.buildcli.spike.domain;

import java.time.Instant;

public record Event(Instant ts, String type, int taskId, String agent, String payload) {}

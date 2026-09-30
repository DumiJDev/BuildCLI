package dev.buildcli.domain;

import java.time.Instant;

public record Event(Instant ts, String type, int taskId, String agent, String payload) {}

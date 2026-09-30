package dev.buildcli.domain;

import java.time.Instant;

/** One execution of a request through a team. {@code finishedAt} and {@code summary} are null while it runs. */
public record RunInfo(String id, String team, String request, Instant startedAt, Instant finishedAt, String status,
                      String summary) {}

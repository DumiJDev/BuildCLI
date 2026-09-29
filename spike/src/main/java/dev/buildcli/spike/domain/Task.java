package dev.buildcli.spike.domain;

/** A unit of execution. A handoff is simply a child task owned by another agent. */
public final class Task {
    public final int id;
    public final Integer parentId;
    public final String from;
    public final String to;
    public final String objective;
    public final String brief;
    public volatile TaskStatus status = TaskStatus.PENDING;
    public volatile String result;
    public volatile int attempts;
    public volatile int tokens;

    public Task(int id, Integer parentId, String from, String to, String objective, String brief) {
        this.id = id;
        this.parentId = parentId;
        this.from = from;
        this.to = to;
        this.objective = objective;
        this.brief = brief;
    }
}

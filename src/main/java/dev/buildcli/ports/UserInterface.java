package dev.buildcli.ports;

import dev.buildcli.domain.Event;

public interface UserInterface {
    /** Blocks until the user decides. */
    boolean approve(ApprovalRequest request);

    /** Called after the automatic retries are exhausted. Blocks until the user decides. */
    EscalationChoice escalate(int taskId, String agent, String objective, String reason);

    default void onEvent(Event event) {}
}

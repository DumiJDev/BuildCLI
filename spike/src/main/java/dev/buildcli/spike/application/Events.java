package dev.buildcli.spike.application;

import dev.buildcli.spike.domain.Event;
import dev.buildcli.spike.ports.EventStore;
import dev.buildcli.spike.ports.UserInterface;
import java.time.Instant;

/** Appends to the event store and notifies the UI: one log feeds persistence, audit and display. */
public final class Events {
    private final EventStore store;
    private final String runId;
    private final UserInterface ui;

    public Events(EventStore store, String runId, UserInterface ui) {
        this.store = store;
        this.runId = runId;
        this.ui = ui;
    }

    public void emit(String type, int taskId, String agent, String payload) {
        Event e = new Event(Instant.now(), type, taskId, agent, payload == null ? "" : payload);
        store.append(runId, e);
        ui.onEvent(e);
    }

    public String runId() {
        return runId;
    }
}

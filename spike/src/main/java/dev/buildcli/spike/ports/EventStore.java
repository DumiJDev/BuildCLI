package dev.buildcli.spike.ports;

import dev.buildcli.spike.domain.Event;
import java.util.List;

public interface EventStore {
    void append(String runId, Event event);

    List<Event> list(String runId);
}

package dev.buildcli.application;

import dev.buildcli.domain.FileChange;
import dev.buildcli.domain.Task;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One agent answering one message, with everything that happens for it (on any agent's thread). {@code hops}
 * counts messages agents sent each other since the user last wrote, so their conversation cannot run away.
 */
final class Run {
    /** The run the current thread is working for: set on an agent's own thread while it handles a job. */
    static final ThreadLocal<Run> CURRENT = new ThreadLocal<>();

    final long messageId;
    final String thread;
    final String me;
    final int hops;
    final AtomicBoolean stop = new AtomicBoolean();
    /** The tasks of this run (guarded by the map itself). */
    final Map<Integer, Task> tasks = new LinkedHashMap<>();
    final Set<String> handedOffTo = ConcurrentHashMap.newKeySet();
    /** Files written during this run, by its agent or by teammates it handed work to. */
    final List<FileChange> changes = new CopyOnWriteArrayList<>();

    Run(long messageId, String thread, String me, int hops) {
        this.messageId = messageId;
        this.thread = thread;
        this.me = me;
        this.hops = hops;
    }
}

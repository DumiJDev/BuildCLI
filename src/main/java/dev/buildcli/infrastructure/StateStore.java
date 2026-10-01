package dev.buildcli.infrastructure;

import dev.buildcli.domain.AgentUsage;
import dev.buildcli.domain.Event;
import dev.buildcli.domain.RunInfo;
import dev.buildcli.domain.Task;
import dev.buildcli.domain.TaskStatus;
import dev.buildcli.ports.RunStore;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The state database: runs, tasks, the append-only event log and the chat history. SQLite by default (decision from the M0
 * spike; WAL and synchronous=NORMAL, because its default sync mode was ~40x slower per append), or H2 (a file, or in memory
 * for something fast that is gone when BuildCLI closes); the engine is read from the URL. The schema version lives in
 * {@code PRAGMA user_version} (SQLite) or a one-row table (H2); each entry of {@link #MIGRATIONS} upgrades it by one.
 *
 * <p>With {@code batching}, writes return at once and one writer thread applies them in groups, one transaction each:
 * what piles up while a group is being committed becomes the next group, so a quiet store commits every write within a
 * few milliseconds and a busy one amortises the commit. Reads wait for what was written before them, so they never see
 * an old state; a failed write is reported by the next write and by {@link #close()}. After a crash, only what was still
 * waiting in the queue (milliseconds of work) is lost.
 */
public final class StateStore implements RunStore, dev.buildcli.ports.ChatLog, AutoCloseable {
    public static final String IN_MEMORY = "jdbc:sqlite::memory:";
    public static final String H2_MEMORY = "jdbc:h2:mem:buildcli;DB_CLOSE_DELAY=-1";

    /** Which engine keeps the state: a choice in the settings. */
    public enum Backend {
        SQLITE, H2, MEMORY;

        public static Backend parse(String text) {
            if (text == null || text.isBlank()) {
                return SQLITE;
            }
            return switch (text.strip().toLowerCase(java.util.Locale.ROOT)) {
                case "sqlite" -> SQLITE;
                case "h2" -> H2;
                case "memory", "h2-memory" -> MEMORY;
                default -> throw new IllegalArgumentException("unknown state database '" + text + "'; use sqlite, h2 or memory");
            };
        }
    }

    private static final int QUEUE_CAPACITY = 100_000;
    private static final int MAX_BATCH = 2_000;

    /** Index i migrates the schema from version i to version i+1. Never edit a released entry; append a new one. */
    static final List<List<String>> MIGRATIONS = List.of(
            List.of(
                    "CREATE TABLE runs (id TEXT PRIMARY KEY, team TEXT NOT NULL, request TEXT NOT NULL,"
                            + " started_at TEXT NOT NULL, finished_at TEXT, status TEXT NOT NULL, summary TEXT)",
                    "CREATE TABLE tasks (run_id TEXT NOT NULL, id INTEGER NOT NULL, parent_id INTEGER, from_agent TEXT NOT NULL,"
                            + " to_agent TEXT NOT NULL, objective TEXT NOT NULL, brief TEXT NOT NULL, status TEXT NOT NULL,"
                            + " result TEXT, attempts INTEGER NOT NULL, tokens INTEGER NOT NULL, updated_at TEXT NOT NULL,"
                            + " PRIMARY KEY (run_id, id))",
                    "CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT NOT NULL, ts TEXT NOT NULL,"
                            + " type TEXT NOT NULL, task_id INTEGER NOT NULL, agent TEXT, payload TEXT,"
                            + " input_tokens INTEGER NOT NULL DEFAULT 0, output_tokens INTEGER NOT NULL DEFAULT 0)",
                    "CREATE INDEX events_by_run ON events (run_id, id)"),
            List.of(
                    "CREATE TABLE chat_messages (id INTEGER PRIMARY KEY, thread TEXT NOT NULL, kind TEXT NOT NULL,"
                            + " author TEXT NOT NULL, text TEXT NOT NULL, at TEXT NOT NULL, state TEXT NOT NULL,"
                            + " attachments TEXT NOT NULL DEFAULT '', position INTEGER NOT NULL DEFAULT 0)",
                    "CREATE INDEX chat_messages_by_position ON chat_messages (position, id)"),
            List.of(
                    "CREATE TABLE file_changes (message_id INTEGER NOT NULL, seq INTEGER NOT NULL, agent TEXT NOT NULL,"
                            + " path TEXT NOT NULL, existed INTEGER NOT NULL, before_text TEXT, after_text TEXT,"
                            + " PRIMARY KEY (message_id, seq))"));

    /** The schema version this build writes. */
    public static final int SCHEMA_VERSION = MIGRATIONS.size();

    private static final int MAX_TEXT = 8000;

    private final Connection connection;
    private final boolean h2;
    /** Guards the connection: the writer thread and the readers take turns. */
    private final Object db = new Object();
    private final java.util.concurrent.BlockingQueue<Object> queue;
    private final Thread writer;
    private volatile Throwable failure;

    /** A store whose writes are applied before the call returns (what tests and one-off commands want). */
    public StateStore(String jdbcUrl) {
        this(jdbcUrl, false);
    }

    public StateStore(String jdbcUrl, boolean batching) {
        this.h2 = jdbcUrl.startsWith("jdbc:h2:");
        Connection opened;
        try {
            opened = DriverManager.getConnection(jdbcUrl);
        } catch (SQLException e) {
            throw new IllegalStateException(openFailure(jdbcUrl, e), e);
        }
        try {
            if (!h2) {
                try (Statement st = opened.createStatement()) {
                    st.execute("PRAGMA journal_mode=WAL");
                    st.execute("PRAGMA synchronous=NORMAL");
                    st.execute("PRAGMA busy_timeout=5000");
                }
            }
            migrate(opened, h2);
        } catch (SQLException | RuntimeException e) {
            // A refused or broken database must not leave its file locked: on Windows an open handle blocks deleting it.
            try {
                opened.close();
            } catch (SQLException ignored) {
                // nothing more can be done; the original failure is what matters
            }
            if (e instanceof IllegalStateException ise) {
                throw ise;
            }
            throw new IllegalStateException(openFailure(jdbcUrl, e), e);
        }
        this.connection = opened;
        if (batching) {
            this.queue = new java.util.concurrent.LinkedBlockingQueue<>(QUEUE_CAPACITY);
            // a platform thread: JDBC blocks inside native code and monitors, which a virtual thread would pin
            this.writer = new Thread(this::writeLoop, "state-writer");
            this.writer.setDaemon(true);
            this.writer.start();
        } else {
            this.queue = null;
            this.writer = null;
        }
    }

    private static String openFailure(String jdbcUrl, Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (jdbcUrl.startsWith("jdbc:h2:file") && (m.contains("already in use") || m.contains("Locked by another process"))) {
            return "the H2 state database is open in another BuildCLI, and H2 lets one process use it at a time"
                    + " (SQLite, the default, can be read from several): " + m;
        }
        return "cannot open the state database " + jdbcUrl + ": " + m;
    }

    /** Opens (creating it if needed) the SQLite state database file; parent directories are created. */
    public static StateStore open(java.nio.file.Path file) {
        return open(file, Backend.SQLITE, false);
    }

    /**
     * Opens the state database of a project: {@code file} is the SQLite file ({@code state.db}); H2 keeps its own next to
     * it ({@code state.mv.db}); {@link Backend#MEMORY} keeps nothing on disk.
     */
    public static StateStore open(java.nio.file.Path file, Backend backend, boolean batching) {
        if (backend == Backend.MEMORY) {
            return new StateStore(H2_MEMORY, batching);
        }
        try {
            java.nio.file.Files.createDirectories(file.toAbsolutePath().getParent());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot create " + file.getParent() + ": " + e.getMessage(), e);
        }
        String abs = file.toAbsolutePath().toString();
        if (backend == Backend.H2) {
            return new StateStore("jdbc:h2:file:" + (abs.endsWith(".db") ? abs.substring(0, abs.length() - 3) : abs), batching);
        }
        return new StateStore("jdbc:sqlite:" + abs, batching);
    }

    /** The SQL of a migration for this engine: H2 has no AUTOINCREMENT and keeps long text in VARCHAR. */
    private static String dialect(String sql, boolean h2) {
        return h2 ? sql.replace("INTEGER PRIMARY KEY AUTOINCREMENT", "BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY")
                .replaceAll("\\bTEXT\\b", "VARCHAR") : sql;
    }

    private static int readVersion(Connection connection, boolean h2) throws SQLException {
        try (Statement st = connection.createStatement()) {
            if (h2) {
                st.execute("CREATE TABLE IF NOT EXISTS buildcli_schema (version INT NOT NULL)");
                try (ResultSet rs = st.executeQuery("SELECT version FROM buildcli_schema")) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
            try (ResultSet rs = st.executeQuery("PRAGMA user_version")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private static void writeVersion(Statement st, boolean h2, int version) throws SQLException {
        if (h2) {
            st.execute("DELETE FROM buildcli_schema");
            st.execute("INSERT INTO buildcli_schema (version) VALUES (" + version + ")");
        } else {
            st.execute("PRAGMA user_version=" + version);
        }
    }

    private static void migrate(Connection connection, boolean h2) throws SQLException {
        int current = readVersion(connection, h2);
        if (current > SCHEMA_VERSION) {
            throw new IllegalStateException("the state database is schema version " + current + " but this BuildCLI only"
                    + " understands up to " + SCHEMA_VERSION + "; upgrade BuildCLI (the database was not modified)");
        }
        for (int v = current; v < SCHEMA_VERSION; v++) {
            connection.setAutoCommit(false);
            try (Statement st = connection.createStatement()) {
                for (String sql : MIGRATIONS.get(v)) {
                    st.execute(dialect(sql, h2));
                }
                writeVersion(st, h2, v + 1);
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    // ---- chat history ----

    /** Longest message kept: long replies and pasted logs, but not unbounded. */
    private static final int MAX_MESSAGE = 200_000;

    @Override
    public List<dev.buildcli.domain.ChatEntry> recent(int limit) {
        List<dev.buildcli.domain.ChatEntry> out = new ArrayList<>();
        String sql = "SELECT id, thread, kind, author, text, at, state, attachments, position FROM"
                + " (SELECT * FROM chat_messages ORDER BY position DESC, id DESC LIMIT ?) recent ORDER BY position, id";
        flush();
        synchronized (db) {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new dev.buildcli.domain.ChatEntry(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                                rs.getString(5), Instant.parse(rs.getString(6)), rs.getString(7), attachmentsFrom(rs.getString(8)), rs.getLong(9)));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("cannot read the chat history: " + e.getMessage(), e);
            }
        }
        return out;
    }

    @Override
    public void save(dev.buildcli.domain.ChatEntry e) {
        write((h2 ? "MERGE INTO chat_messages (id, thread, kind, author, text, at, state, attachments, position) KEY (id)"
                : "INSERT OR REPLACE INTO chat_messages (id, thread, kind, author, text, at, state, attachments, position)")
                + " VALUES (?,?,?,?,?,?,?,?,?)", ps -> {
            ps.setLong(1, e.id());
            ps.setString(2, e.thread());
            ps.setString(3, e.kind());
            ps.setString(4, e.author());
            ps.setString(5, e.text().length() > MAX_MESSAGE ? e.text().substring(0, MAX_MESSAGE) + "\n[truncated]" : e.text());
            ps.setString(6, e.at().toString());
            ps.setString(7, e.state());
            ps.setString(8, attachmentsTo(e.attachments()));
            ps.setLong(9, e.position());
        });
    }

    @Override
    public void clear(String thread) {
        write("DELETE FROM file_changes WHERE message_id IN (SELECT id FROM chat_messages WHERE thread = ?)", ps -> ps.setString(1, thread));
        write("DELETE FROM chat_messages WHERE thread = ?", ps -> ps.setString(1, thread));
    }

    /** Newest change sets kept; older ones are dropped so the database does not grow with every edit. */
    private static final int KEEP_CHANGE_SETS = 50;

    /** Largest file whose content is kept for undo after a restart. */
    private static final int MAX_KEPT_FILE = 200_000;

    /**
     * Content of files that may hold secrets is never written to disk: unlike messages it cannot be redacted, because
     * undo must restore the exact text. Those files can still be undone while BuildCLI stays open.
     */
    static boolean mayHoldSecrets(String path) {
        String p = path.toLowerCase(java.util.Locale.ROOT);
        String name = p.substring(p.lastIndexOf('/') + 1);
        return name.startsWith(".env") || name.endsWith(".pem") || name.endsWith(".key") || name.endsWith(".p12") || name.endsWith(".pfx")
                || name.endsWith(".jks") || name.startsWith("id_rsa") || name.startsWith("id_ed25519") || name.contains("secret")
                || name.contains("credential") || name.contains("password") || name.equals(".netrc") || name.equals(".npmrc")
                || name.equals(".pgpass") || name.equals("settings-security.xml") || p.contains(".aws/") || p.contains(".ssh/");
    }

    @Override
    public void saveChanges(long messageId, List<dev.buildcli.domain.FileChange> changes) {
        write("DELETE FROM file_changes WHERE message_id = ?", ps -> ps.setLong(1, messageId));
        int seq = 0;
        for (dev.buildcli.domain.FileChange c : changes) {
            boolean keep = !mayHoldSecrets(c.path()) && c.after().length() <= MAX_KEPT_FILE
                    && (c.before() == null || c.before().length() <= MAX_KEPT_FILE);
            int n = seq++;
            write("INSERT INTO file_changes (message_id, seq, agent, path, existed, before_text, after_text) VALUES (?,?,?,?,?,?,?)", ps -> {
                ps.setLong(1, messageId);
                ps.setInt(2, n);
                ps.setString(3, c.agent());
                ps.setString(4, c.path());
                ps.setInt(5, c.existed() ? 1 : 0);
                ps.setString(6, keep ? c.before() : null);
                ps.setString(7, keep ? c.after() : null);
            });
        }
        write("DELETE FROM file_changes WHERE message_id NOT IN (SELECT DISTINCT message_id FROM file_changes ORDER BY message_id DESC LIMIT "
                + KEEP_CHANGE_SETS + ")", ps -> { });
    }

    @Override
    public List<dev.buildcli.domain.FileChange> changes(long messageId) {
        List<dev.buildcli.domain.FileChange> out = new ArrayList<>();
        flush();
        synchronized (db) {
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT agent, path, existed, before_text, after_text FROM file_changes WHERE message_id = ? ORDER BY seq")) {
                ps.setLong(1, messageId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        // a null "after" means the content was not kept (a sensitive or very large file)
                        out.add(new dev.buildcli.domain.FileChange(rs.getString(1), rs.getString(2), rs.getInt(3) == 1, rs.getString(4), rs.getString(5)));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("cannot read the saved changes: " + e.getMessage(), e);
            }
        }
        return out;
    }

    /** One line per attachment: kind, mime type, size and path, separated by tabs. */
    private static String attachmentsTo(List<dev.buildcli.domain.Attachment> list) {
        StringBuilder sb = new StringBuilder();
        for (var a : list) {
            sb.append(a.kind()).append('\t').append(a.mime()).append('\t').append(a.size()).append('\t').append(a.path()).append('\n');
        }
        return sb.toString();
    }

    private static List<dev.buildcli.domain.Attachment> attachmentsFrom(String text) {
        List<dev.buildcli.domain.Attachment> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String line : text.split("\n")) {
            String[] f = line.split("\t", 4);
            if (f.length == 4) {
                try {
                    java.nio.file.Path p = java.nio.file.Path.of(f[3]);
                    out.add(new dev.buildcli.domain.Attachment(dev.buildcli.domain.Attachment.Kind.valueOf(f[0]), p,
                            p.getFileName() == null ? f[3] : p.getFileName().toString(), f[1], Long.parseLong(f[2])));
                } catch (RuntimeException ignored) {
                    // a damaged line loses its attachment, not the message
                }
            }
        }
        return out;
    }

    public int schemaVersion() {
        flush();
        synchronized (db) {
            try {
                return readVersion(connection, h2);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Override
    public void append(String runId, Event e) {
        write("INSERT INTO events (run_id, ts, type, task_id, agent, payload, input_tokens, output_tokens) VALUES (?,?,?,?,?,?,?,?)",
                ps -> {
                    ps.setString(1, runId);
                    ps.setString(2, e.ts().toString());
                    ps.setString(3, e.type());
                    ps.setInt(4, e.taskId());
                    ps.setString(5, e.agent());
                    ps.setString(6, cut(e.payload()));
                    ps.setInt(7, e.inputTokens());
                    ps.setInt(8, e.outputTokens());
                });
    }

    @Override
    public List<Event> list(String runId) {
        return query("SELECT ts, type, task_id, agent, payload, input_tokens, output_tokens FROM events WHERE run_id = ? ORDER BY id",
                ps -> ps.setString(1, runId),
                rs -> new Event(Instant.parse(rs.getString(1)), rs.getString(2), rs.getInt(3), rs.getString(4),
                        rs.getString(5), rs.getInt(6), rs.getInt(7)));
    }

    @Override
    public void startRun(RunInfo r) {
        write("INSERT INTO runs (id, team, request, started_at, status) VALUES (?,?,?,?,?)", ps -> {
            ps.setString(1, r.id());
            ps.setString(2, r.team());
            ps.setString(3, cut(r.request()));
            ps.setString(4, r.startedAt().toString());
            ps.setString(5, r.status());
        });
    }

    @Override
    public void finishRun(String runId, String status, String summary) {
        write("UPDATE runs SET finished_at = ?, status = ?, summary = ? WHERE id = ?", ps -> {
            ps.setString(1, Instant.now().toString());
            ps.setString(2, status);
            ps.setString(3, cut(summary));
            ps.setString(4, runId);
        });
    }

    @Override
    public void saveTask(String runId, Task t) {
        write(h2 ? "MERGE INTO tasks (run_id, id, parent_id, from_agent, to_agent, objective, brief, status, result, attempts, tokens, updated_at)"
                + " KEY (run_id, id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"
                : "INSERT INTO tasks (run_id, id, parent_id, from_agent, to_agent, objective, brief, status, result, attempts, tokens, updated_at)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"
                + " ON CONFLICT (run_id, id) DO UPDATE SET status = excluded.status, result = excluded.result,"
                + " attempts = excluded.attempts, tokens = excluded.tokens, updated_at = excluded.updated_at", ps -> {
                    ps.setString(1, runId);
                    ps.setInt(2, t.id);
                    if (t.parentId == null) {
                        ps.setNull(3, java.sql.Types.INTEGER);
                    } else {
                        ps.setInt(3, t.parentId);
                    }
                    ps.setString(4, t.from);
                    ps.setString(5, t.to);
                    ps.setString(6, cut(t.objective));
                    ps.setString(7, cut(t.brief));
                    ps.setString(8, t.status.name());
                    ps.setString(9, cut(t.result));
                    ps.setInt(10, t.attempts);
                    ps.setInt(11, t.tokens);
                    ps.setString(12, Instant.now().toString());
                });
    }

    @Override
    public List<RunInfo> listRuns(int limit) {
        return query("SELECT id, team, request, started_at, finished_at, status, summary FROM runs ORDER BY started_at DESC, id DESC LIMIT ?",
                ps -> ps.setInt(1, limit),
                rs -> new RunInfo(rs.getString(1), rs.getString(2), rs.getString(3), Instant.parse(rs.getString(4)),
                        rs.getString(5) == null ? null : Instant.parse(rs.getString(5)), rs.getString(6), rs.getString(7)));
    }

    @Override
    public List<Task> listTasks(String runId) {
        return query("SELECT id, parent_id, from_agent, to_agent, objective, brief, status, result, attempts, tokens"
                + " FROM tasks WHERE run_id = ? ORDER BY id", ps -> ps.setString(1, runId), rs -> {
                    int parent = rs.getInt(2);
                    Integer parentId = rs.wasNull() ? null : parent; // must be read right after the column it refers to
                    Task t = new Task(rs.getInt(1), parentId, rs.getString(3), rs.getString(4),
                            rs.getString(5), rs.getString(6));
                    t.status = TaskStatus.valueOf(rs.getString(7));
                    t.result = rs.getString(8);
                    t.attempts = rs.getInt(9);
                    t.tokens = rs.getInt(10);
                    return t;
                });
    }

    @Override
    public List<AgentUsage> usage(String runId) {
        return query("SELECT agent, COUNT(*), SUM(input_tokens), SUM(output_tokens) FROM events"
                + " WHERE run_id = ? AND type = 'AgentInvoked' GROUP BY agent ORDER BY agent",
                ps -> ps.setString(1, runId),
                rs -> new AgentUsage(rs.getString(1), rs.getInt(2), rs.getLong(3), rs.getLong(4)));
    }

    @Override
    public void close() throws SQLException {
        if (writer != null) {
            flush();
            writer.interrupt();
            try {
                writer.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (db) {
            for (PreparedStatement ps : statements.values()) {
                try {
                    ps.close();
                } catch (SQLException ignored) {
                    // the connection is closed right after
                }
            }
            statements.clear();
            connection.close();
        }
        Throwable lost = failure;
        if (lost != null) {
            failure = null;
            throw new SQLException("some writes to the state database failed: " + lost.getMessage(), lost);
        }
    }

    // ---- small JDBC helpers ----

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private record Op(String sql, Binder binder) {}

    private record Fence(java.util.concurrent.CountDownLatch done) {}

    /** Applies a write now, or queues it for the writer thread. */
    private void write(String sql, Binder binder) {
        if (queue == null) {
            synchronized (db) {
                execute(sql, binder);
            }
            return;
        }
        Throwable earlier = failure;
        if (earlier != null) {
            failure = null;
            throw new IllegalStateException("an earlier write to the state database failed: " + earlier.getMessage(), earlier);
        }
        try {
            queue.put(new Op(sql, binder)); // blocks when 100000 writes are waiting, instead of growing without bound
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while writing to the state database", e);
        }
    }

    /** Writes are few kinds of statement repeated many times: prepare each once. Only used while holding {@link #db}. */
    private final java.util.Map<String, PreparedStatement> statements = new java.util.HashMap<>();

    private void execute(String sql, Binder binder) {
        try {
            PreparedStatement ps = statements.get(sql);
            if (ps == null) {
                ps = connection.prepareStatement(sql);
                statements.put(sql, ps);
            }
            ps.clearParameters();
            binder.bind(ps);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /** Waits until everything written before this call has been applied. */
    private void flush() {
        if (queue == null || !writer.isAlive()) {
            return;
        }
        var fence = new Fence(new java.util.concurrent.CountDownLatch(1));
        try {
            queue.put(fence);
            fence.done().await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void writeLoop() {
        List<Object> batch = new ArrayList<>();
        while (true) {
            try {
                batch.add(queue.take());
            } catch (InterruptedException e) {
                // closing: apply what is still waiting, then stop
                queue.drainTo(batch);
                apply(batch);
                return;
            }
            queue.drainTo(batch, MAX_BATCH - 1);
            apply(batch);
            batch.clear();
        }
    }

    /** One transaction for the whole group; a write that fails is remembered and does not stop the others. */
    private void apply(List<Object> batch) {
        synchronized (db) {
            try {
                connection.setAutoCommit(false);
                for (Object o : batch) {
                    if (o instanceof Op op) {
                        try {
                            execute(op.sql(), op.binder());
                        } catch (RuntimeException e) {
                            failure = e;
                        }
                    }
                }
                connection.commit();
            } catch (SQLException e) {
                failure = e;
            } finally {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException ignored) {
                    // the connection is closing; nothing more to do
                }
            }
        }
        for (Object o : batch) {
            if (o instanceof Fence f) {
                f.done().countDown();
            }
        }
        batch.clear();
    }

    private <T> List<T> query(String sql, Binder binder, RowMapper<T> mapper) {
        flush();
        List<T> out = new ArrayList<>();
        synchronized (db) {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                binder.bind(ps);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(mapper.map(rs));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
        return out;
    }

    private static String cut(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s;
    }
}

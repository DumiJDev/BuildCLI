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
 * SQLite store for runs, tasks and the append-only event log (decision from the M0 spike). File databases use WAL and
 * synchronous=NORMAL: SQLite's default sync mode was ~40x slower per append. The schema version lives in
 * {@code PRAGMA user_version}; each entry of {@link #MIGRATIONS} upgrades it by one.
 */
public final class SqliteRunStore implements RunStore, AutoCloseable {
    public static final String IN_MEMORY = "jdbc:sqlite::memory:";

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
                    "CREATE INDEX events_by_run ON events (run_id, id)"));

    /** The schema version this build writes. */
    public static final int SCHEMA_VERSION = MIGRATIONS.size();

    private static final int MAX_TEXT = 8000;

    private final Connection connection;

    public SqliteRunStore(String jdbcUrl) {
        try {
            connection = DriverManager.getConnection(jdbcUrl);
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("PRAGMA busy_timeout=5000");
            }
            migrate();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open the state database " + jdbcUrl + ": " + e.getMessage(), e);
        }
    }

    /** Opens (creating it if needed) a state database file; parent directories are created. */
    public static SqliteRunStore open(java.nio.file.Path file) {
        try {
            java.nio.file.Files.createDirectories(file.toAbsolutePath().getParent());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot create " + file.getParent() + ": " + e.getMessage(), e);
        }
        return new SqliteRunStore("jdbc:sqlite:" + file.toAbsolutePath());
    }

    private void migrate() throws SQLException {
        int current;
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            current = rs.next() ? rs.getInt(1) : 0;
        }
        if (current > SCHEMA_VERSION) {
            throw new IllegalStateException("the state database is schema version " + current + " but this BuildCLI only"
                    + " understands up to " + SCHEMA_VERSION + "; upgrade BuildCLI (the database was not modified)");
        }
        for (int v = current; v < SCHEMA_VERSION; v++) {
            connection.setAutoCommit(false);
            try (Statement st = connection.createStatement()) {
                for (String sql : MIGRATIONS.get(v)) {
                    st.execute(sql);
                }
                st.execute("PRAGMA user_version=" + (v + 1));
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    public synchronized int schemaVersion() {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public synchronized void append(String runId, Event e) {
        run("INSERT INTO events (run_id, ts, type, task_id, agent, payload, input_tokens, output_tokens) VALUES (?,?,?,?,?,?,?,?)",
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
    public synchronized List<Event> list(String runId) {
        return query("SELECT ts, type, task_id, agent, payload, input_tokens, output_tokens FROM events WHERE run_id = ? ORDER BY id",
                ps -> ps.setString(1, runId),
                rs -> new Event(Instant.parse(rs.getString(1)), rs.getString(2), rs.getInt(3), rs.getString(4),
                        rs.getString(5), rs.getInt(6), rs.getInt(7)));
    }

    @Override
    public synchronized void startRun(RunInfo r) {
        run("INSERT INTO runs (id, team, request, started_at, status) VALUES (?,?,?,?,?)", ps -> {
            ps.setString(1, r.id());
            ps.setString(2, r.team());
            ps.setString(3, cut(r.request()));
            ps.setString(4, r.startedAt().toString());
            ps.setString(5, r.status());
        });
    }

    @Override
    public synchronized void finishRun(String runId, String status, String summary) {
        run("UPDATE runs SET finished_at = ?, status = ?, summary = ? WHERE id = ?", ps -> {
            ps.setString(1, Instant.now().toString());
            ps.setString(2, status);
            ps.setString(3, cut(summary));
            ps.setString(4, runId);
        });
    }

    @Override
    public synchronized void saveTask(String runId, Task t) {
        run("INSERT INTO tasks (run_id, id, parent_id, from_agent, to_agent, objective, brief, status, result, attempts, tokens, updated_at)"
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
    public synchronized List<RunInfo> listRuns(int limit) {
        return query("SELECT id, team, request, started_at, finished_at, status, summary FROM runs ORDER BY started_at DESC, id DESC LIMIT ?",
                ps -> ps.setInt(1, limit),
                rs -> new RunInfo(rs.getString(1), rs.getString(2), rs.getString(3), Instant.parse(rs.getString(4)),
                        rs.getString(5) == null ? null : Instant.parse(rs.getString(5)), rs.getString(6), rs.getString(7)));
    }

    @Override
    public synchronized List<Task> listTasks(String runId) {
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
    public synchronized List<AgentUsage> usage(String runId) {
        return query("SELECT agent, COUNT(*), SUM(input_tokens), SUM(output_tokens) FROM events"
                + " WHERE run_id = ? AND type = 'AgentInvoked' GROUP BY agent ORDER BY agent",
                ps -> ps.setString(1, runId),
                rs -> new AgentUsage(rs.getString(1), rs.getInt(2), rs.getLong(3), rs.getLong(4)));
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }

    // ---- small JDBC helpers ----

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private void run(String sql, Binder binder) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private <T> List<T> query(String sql, Binder binder, RowMapper<T> mapper) {
        List<T> out = new ArrayList<>();
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
        return out;
    }

    private static String cut(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > MAX_TEXT ? s.substring(0, MAX_TEXT) : s;
    }
}

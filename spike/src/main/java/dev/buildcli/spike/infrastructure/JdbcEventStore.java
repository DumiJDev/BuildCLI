package dev.buildcli.spike.infrastructure;

import dev.buildcli.spike.domain.Event;
import dev.buildcli.spike.ports.EventStore;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite event store (decision from the M0 spike). File databases use WAL + synchronous=NORMAL: SQLite's default
 * sync mode was ~40x slower per append. Plain JDBC keeps the engine swappable by URL.
 */
public final class JdbcEventStore implements EventStore, AutoCloseable {
    public static final String IN_MEMORY = "jdbc:sqlite::memory:";

    private final Connection connection;

    /** @param jdbcUrl e.g. {@code jdbc:sqlite:/path/events.db} or {@link #IN_MEMORY} */
    public JdbcEventStore(String jdbcUrl) {
        try {
            connection = DriverManager.getConnection(jdbcUrl);
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA synchronous=NORMAL");
                st.execute("CREATE TABLE IF NOT EXISTS events ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "run_id VARCHAR(64) NOT NULL, ts TIMESTAMP NOT NULL, type VARCHAR(64) NOT NULL,"
                        + "task_id INT NOT NULL, agent VARCHAR(64), payload VARCHAR(8000))");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open event store " + jdbcUrl, e);
        }
    }

    @Override
    public synchronized void append(String runId, Event e) {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO events (run_id, ts, type, task_id, agent, payload) VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, runId);
            ps.setTimestamp(2, Timestamp.from(e.ts()));
            ps.setString(3, e.type());
            ps.setInt(4, e.taskId());
            ps.setString(5, e.agent());
            ps.setString(6, e.payload().length() > 8000 ? e.payload().substring(0, 8000) : e.payload());
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Override
    public synchronized List<Event> list(String runId) {
        List<Event> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT ts, type, task_id, agent, payload FROM events WHERE run_id = ? ORDER BY id")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Event(rs.getTimestamp(1).toInstant(), rs.getString(2), rs.getInt(3), rs.getString(4), rs.getString(5)));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
        return out;
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}

package com.prosperitysmp.npc.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** One worker-owned, server-enforced read-only MVCC snapshot shared by evidence readers. */
public final class ReadSnapshot implements AutoCloseable {
    private final Database database;
    private final Connection connection;
    private final Instant capturedAt;
    private final Thread ownerThread = Thread.currentThread();
    private boolean closed;

    private ReadSnapshot(Database database, Connection connection, Instant capturedAt) {
        this.database = database;
        this.connection = connection;
        this.capturedAt = capturedAt;
    }

    public static ReadSnapshot open(Database database, Clock clock) throws SQLException {
        Objects.requireNonNull(database); Objects.requireNonNull(clock);
        Connection c = database.getConnection();
        try {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setAutoCommit(false);
            Instant at = clock.instant().truncatedTo(ChronoUnit.MILLIS);
            try (PreparedStatement begin = c.prepareStatement("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY")) {
                begin.setQueryTimeout(5);
                begin.execute();
                if (c.getWarnings() != null || begin.getWarnings() != null)
                    throw new SQLException("Read-only snapshot could not be established without warnings");
            }
            return new ReadSnapshot(database, c, at);
        } catch (SQLException | RuntimeException | Error failure) {
            try (c) { c.rollback(); }
            catch (SQLException | RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public Instant capturedAt() { return capturedAt; }

    /** Trusted write-transaction readers borrow only; they never own commit/rollback. */
    public static void requireTransaction(Connection connection) throws SQLException {
        Objects.requireNonNull(connection);
        if (connection.isClosed() || connection.getAutoCommit()
                || connection.getTransactionIsolation() != Connection.TRANSACTION_REPEATABLE_READ
                || connection.getWarnings() != null)
            throw new SQLException("Evidence requires an active repeatable-read transaction");
    }

    /** Internal JDBC access; never alter this connection's transaction or share it across workers. */
    public Connection connection(Database expectedDatabase) throws SQLException {
        if (Thread.currentThread() != ownerThread || closed || database != expectedDatabase || connection.isClosed())
            throw new SQLException("Wrong or closed evidence snapshot");
        if (connection.getAutoCommit() || connection.getTransactionIsolation() != Connection.TRANSACTION_REPEATABLE_READ)
            throw new SQLException("Evidence snapshot transaction changed");
        return connection;
    }

    @Override public void close() throws SQLException {
        if (closed) return;
        closed = true;
        try (Connection owned = connection) { owned.rollback(); }
    }
}

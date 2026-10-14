package com.prosperitysmp.npc.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Owns the HikariCP connection pool to the dedicated {@code prosperity_npc}
 * database. Nothing here moves money; it only provides connections and a
 * lightweight health/status probe for {@code /npcadmin health}.
 */
public final class Database implements AutoCloseable {

    /** Snapshot for the health command. */
    public record Status(boolean connected, boolean healthy, int schemaVersion, int tableCount) {
        public static Status down() {
            return new Status(false, false, -1, -1);
        }
    }

    private HikariDataSource dataSource;

    public void connect(DatabaseSettings settings) {
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName("prosperity-npc");
        cfg.setJdbcUrl(settings.jdbcUrl());
        cfg.setUsername(settings.user());
        cfg.setPassword(settings.password());
        cfg.setDriverClassName("org.mariadb.jdbc.Driver");
        cfg.setMaximumPoolSize(settings.poolSize());
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(settings.connectionTimeoutMs());
        // SQL defaults/updates must store UTC in timezone-less DATETIME columns.
        // No legacy rows are converted: populated databases with unknown timestamp
        // provenance require an audit before deploying this session change.
        cfg.setConnectionInitSql("SET SESSION time_zone = '+00:00'");
        // Pool admission is not a JDBC/network deadline. A lost localhost peer must
        // not permanently occupy the single encounter worker (or the pool).
        cfg.addDataSourceProperty("connectTimeout", "5000");
        cfg.addDataSourceProperty("socketTimeout", "10000");
        cfg.addDataSourceProperty("cachePrepStmts", "true");
        cfg.addDataSourceProperty("prepStmtCacheSize", "128");
        this.dataSource = new HikariDataSource(cfg);
    }

    public Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("database not connected");
        }
        return dataSource.getConnection();
    }

    public boolean isConnected() {
        return dataSource != null && !dataSource.isClosed();
    }

    public Status status() {
        if (!isConnected()) {
            return Status.down();
        }
        try (Connection c = getConnection()) {
            boolean healthy = c.isValid(3);
            int version = queryInt(c, "SELECT COALESCE(MAX(version), 0) FROM npc_schema_migrations");
            int tables = queryInt(c,
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()");
            return new Status(true, healthy, version, tables);
        } catch (SQLException e) {
            return new Status(true, false, -1, -1);
        }
    }

    private static int queryInt(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : -1;
        }
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
        dataSource = null;
    }
}

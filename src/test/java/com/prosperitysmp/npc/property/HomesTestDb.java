package com.prosperitysmp.npc.property;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/** One isolated embedded MariaDB with every migration applied, for the property repository tests. */
public final class HomesTestDb implements AutoCloseable {
    private DB maria;
    private final Database database = new Database();

    private HomesTestDb() { }

    public static HomesTestDb start(String prefix) throws Exception {
        var fixture = new HomesTestDb();
        try {
            var config = DBConfigurationBuilder.newBuilder().setPort(0).addArg("--bind-address=127.0.0.1");
            fixture.maria = DB.newEmbeddedDB(config.build());
            fixture.maria.start();
            String schema = prefix + UUID.randomUUID().toString().replace("-", "");
            fixture.maria.createDB(schema);
            fixture.database.connect(new DatabaseSettings("localhost", config.getPort(), schema, "root", "", 6, 10_000));
            var field = MigrationRunner.class.getDeclaredField("MIGRATIONS");
            field.setAccessible(true);
            for (Object migration : (List<?>) field.get(null)) {
                var resource = migration.getClass().getDeclaredMethod("resource");
                resource.setAccessible(true);
                try (InputStream input = HomesTestDb.class.getResourceAsStream("/" + resource.invoke(migration))) {
                    if (input == null) throw new IllegalStateException("Missing migration " + resource.invoke(migration));
                    for (String sql : MigrationRunner.splitStatements(new String(input.readAllBytes(), StandardCharsets.UTF_8)))
                        fixture.exec(sql);
                }
            }
            return fixture;
        } catch (Exception | AssertionError failure) {
            try { fixture.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public Database database() { return database; }

    public void exec(String sql, Object... args) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(10);
            bind(ps, args);
            ps.execute();
        }
    }

    /** The first column of the single row the query returns; null for SQL NULL. */
    public Object scalar(String sql, Object... args) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("No row for " + sql);
                Object value = rs.getObject(1);
                if (rs.next()) throw new SQLException("More than one row for " + sql);
                return value;
            }
        }
    }

    public long count(String sql, Object... args) throws SQLException {
        return ((Number) scalar(sql, args)).longValue();
    }

    /** Empties the tables, children first, with foreign keys off. */
    public void clear(String... tables) throws SQLException {
        try (Connection c = database.getConnection()) {
            try (var s = c.createStatement()) {
                s.execute("SET FOREIGN_KEY_CHECKS=0");
                for (String table : tables) s.execute("DELETE FROM " + table);
                s.execute("SET FOREIGN_KEY_CHECKS=1");
            }
        }
    }

    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof UUID id) ps.setBytes(i + 1, Uuids.toBytes(id));
            else ps.setObject(i + 1, args[i]);
        }
    }

    @Override
    public void close() throws Exception {
        try { database.close(); }
        finally {
            if (maria != null) {
                DB stopping = maria;
                maria = null;
                stopping.stop();
            }
        }
    }
}

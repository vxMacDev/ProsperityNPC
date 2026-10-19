package com.prosperitysmp.npc.economy;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyRepository;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

/** A real embedded MariaDB with every migration applied, for the economy integration tests. */
final class EconomyTestDatabase implements AutoCloseable {
    private static final UUID WORLD = new UUID(0, 6801);
    private final DB mariaDb;
    final Database database;

    private EconomyTestDatabase(DB mariaDb, Database database) {
        this.mariaDb = mariaDb;
        this.database = database;
    }

    static EconomyTestDatabase start(String prefix) throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        DB mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        mariaDb.start();
        Database database = new Database();
        try {
            String schema = prefix + "_" + UUID.randomUUID().toString().replace("-", "");
            mariaDb.createDB(schema);
            database.connect(new DatabaseSettings("localhost", cfg.getPort(), schema, "root", "", 6, 10_000L));
            var field = MigrationRunner.class.getDeclaredField("MIGRATIONS");
            field.setAccessible(true);
            try (Connection c = database.getConnection()) {
                for (Object migration : (List<?>) field.get(null)) {
                    var resource = migration.getClass().getDeclaredMethod("resource");
                    resource.setAccessible(true);
                    try (InputStream in = EconomyTestDatabase.class.getResourceAsStream("/" + resource.invoke(migration))) {
                        if (in == null) throw new IllegalStateException("missing migration " + resource.invoke(migration));
                        for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8)))
                            try (Statement st = c.createStatement()) { st.execute(sql); }
                    }
                }
            }
        } catch (Exception | Error failure) {
            try { database.close(); } finally { mariaDb.stop(); }
            throw failure;
        }
        return new EconomyTestDatabase(mariaDb, database);
    }

    /** Enrolls an enabled home for owner with a lifetime funding seed. */
    UUID enroll(UUID owner, long seedCents) throws SQLException {
        UUID property = UUID.randomUUID();
        new PropertyRepository(database).insertEnrollment(new Property(property, WORLD, "economy-" + property, "downtown",
                "room", 2, 1_000, 1_000, owner, 0, 80, true), seedCents);
        return property;
    }

    long scalarLong(String sql, Object... args) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c, sql, args); ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) throw new SQLException("no row for " + sql);
            return rs.getLong(1);
        }
    }

    String scalarString(String sql, Object... args) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c, sql, args); ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) throw new SQLException("no row for " + sql);
            return rs.getString(1);
        }
    }

    int execute(String sql, Object... args) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c, sql, args)) {
            return ps.executeUpdate();
        }
    }

    private static PreparedStatement prepare(Connection c, String sql, Object... args) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof UUID id) ps.setBytes(i + 1, Uuids.toBytes(id));
            else ps.setObject(i + 1, args[i]);
        }
        return ps;
    }

    @Override
    public void close() throws Exception {
        try {
            database.close();
        } finally {
            mariaDb.stop();
        }
    }
}

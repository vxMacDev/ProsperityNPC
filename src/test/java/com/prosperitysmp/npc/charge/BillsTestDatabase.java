package com.prosperitysmp.npc.charge;

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
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/** An embedded MariaDB with every migration applied, for the bill repository tests. */
public final class BillsTestDatabase implements AutoCloseable {
    private final DB maria;
    private final Database database;

    private BillsTestDatabase(DB maria, Database database) {
        this.maria = maria;
        this.database = database;
    }

    public static BillsTestDatabase boot(String prefix) throws Exception {
        var config = DBConfigurationBuilder.newBuilder().setPort(0).addArg("--bind-address=127.0.0.1");
        DB maria = DB.newEmbeddedDB(config.build());
        maria.start();
        Database database = null;
        try {
            String schema = prefix + "_" + UUID.randomUUID().toString().replace("-", "");
            maria.createDB(schema);
            database = new Database();
            database.connect(new DatabaseSettings("localhost", config.getPort(), schema, "root", "", 6, 10_000));
            var fixture = new BillsTestDatabase(maria, database);
            var field = MigrationRunner.class.getDeclaredField("MIGRATIONS");
            field.setAccessible(true);
            for (Object migration : (List<?>) field.get(null)) {
                var resource = migration.getClass().getDeclaredMethod("resource");
                resource.setAccessible(true);
                try (InputStream input = BillsTestDatabase.class.getResourceAsStream("/" + resource.invoke(migration))) {
                    if (input == null) throw new IllegalStateException("missing migration " + resource.invoke(migration));
                    for (String sql : MigrationRunner.splitStatements(new String(input.readAllBytes(), StandardCharsets.UTF_8)))
                        fixture.execute(sql);
                }
            }
            return fixture;
        } catch (Exception | Error failure) {
            if (database != null) database.close();
            maria.stop();
            throw failure;
        }
    }

    public Database database() { return database; }

    public void execute(String sql, Object... values) throws SQLException {
        if (values.length == 0) {
            try (var c = database.getConnection(); var statement = c.createStatement()) { statement.execute(sql); }
            return;
        }
        try (var c = database.getConnection(); var statement = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                if (values[i] instanceof UUID uuid) statement.setBytes(i + 1, Uuids.toBytes(uuid));
                else statement.setObject(i + 1, values[i]);
            }
            statement.execute();
        }
    }

    public long count(String table) throws SQLException {
        try (var c = database.getConnection(); var statement = c.createStatement();
             var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    /** Enrols an enabled two-bed room in the town market and returns its id. */
    public UUID home(UUID owner) throws SQLException {
        UUID id = UUID.randomUUID();
        new PropertyRepository(database).insertEnrollment(new Property(id, UUID.randomUUID(), "bill-" + id,
                "town", "room", 2, 1000, 1000, owner, 0, 80, true), 50_000);
        return id;
    }

    @Override public void close() throws Exception {
        try { database.close(); }
        finally { maria.stop(); }
    }
}

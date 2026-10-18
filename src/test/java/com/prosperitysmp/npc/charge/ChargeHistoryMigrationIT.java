package com.prosperitysmp.npc.charge;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ChargeHistoryMigrationIT {
    private static final String INDEX = "idx_evt_source_state_finalized";
    private static DB maria;
    private static int port;
    private Database database;

    @BeforeAll static void boot() throws Exception {
        var config = DBConfigurationBuilder.newBuilder().setPort(0).addArg("--bind-address=127.0.0.1");
        maria = DB.newEmbeddedDB(config.build());
        maria.start();
        port = config.getPort();
    }

    @AfterAll static void stop() throws Exception { if (maria != null) maria.stop(); }
    @AfterEach void close() { if (database != null) database.close(); }

    @BeforeEach void oldSchema() throws Exception {
        String schema = "npc_charge_history_" + UUID.randomUUID().toString().replace("-", "");
        maria.createDB(schema);
        database = new Database();
        database.connect(new DatabaseSettings("localhost", port, schema, "root", "", 4, 10_000));
        var field = MigrationRunner.class.getDeclaredField("MIGRATIONS");
        field.setAccessible(true);
        for (Object migration : (List<?>) field.get(null)) {
            var version = migration.getClass().getDeclaredMethod("version");
            var resource = migration.getClass().getDeclaredMethod("resource");
            version.setAccessible(true);
            resource.setAccessible(true);
            if ((Integer) version.invoke(migration) > 45) continue;
            try (InputStream input = getClass().getResourceAsStream("/" + resource.invoke(migration))) {
                assertNotNull(input);
                for (String sql : MigrationRunner.splitStatements(new String(input.readAllBytes(), StandardCharsets.UTF_8))) execute(sql);
            }
        }
    }

    private record Plan(String type, String index, long rows) { }

    private Plan plan(String sql) throws SQLException {
        try (var c = database.getConnection(); var ps = c.prepareStatement("EXPLAIN " + sql)) {
            ps.setQueryTimeout(5);
            try (var rows = ps.executeQuery()) {
                while (rows.next()) if ("npc_economy_events".equals(rows.getString("table")))
                    return new Plan(rows.getString("type"), rows.getString("key"), rows.getLong("rows"));
            }
        }
        throw new SQLException("No event query plan");
    }

    @Test void aSourceIndexAvoidsScanningHistoricalEventsForAnAbsentBill() throws Exception {
        try (var c = database.getConnection(); var ps = c.prepareStatement("""
                INSERT INTO npc_economy_events(idempotency_key,requested_amount_cents,paid_amount_cents,
                  source,direction,economy_day,state,created_at,claimed_at,finalized_at)
                VALUES (?,100,100,'npc-property-rent','TRANSFER_TO_PUBLIC','2020-01-01','PAID',
                  '2020-01-01','2020-01-01','2020-01-01')
                """)) {
            for (int i = 0; i < 10_000; i++) {
                ps.setString(1, "index-history-" + i);
                ps.addBatch();
                if (i % 1000 == 999) ps.executeBatch();
            }
        }
        execute("ANALYZE TABLE npc_economy_events");
        String lookup = "SELECT EXISTS(SELECT 1 FROM npc_economy_events WHERE source='npc-property-bills')";
        var before = plan(lookup);
        migrate();
        execute("ANALYZE TABLE npc_economy_events");
        var after = plan(lookup);
        System.out.println("Bills lookup: before=" + before + "; after=" + after);
        assertNotEquals(INDEX, before.index());
        assertEquals(INDEX, after.index());
        assertTrue(after.rows() < before.rows(), before + " -> " + after);
        try (var c = database.getConnection(); var ps = c.prepareStatement(lookup); var rows = ps.executeQuery()) {
            assertTrue(rows.next());
            assertFalse(rows.getBoolean(1));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void replayPreservesOldHashesAndLeavesMissingTermsUnknown(int interruptedAfter) throws Exception {
        String hash = "b".repeat(64);
        execute("INSERT INTO npc_charge_policies(policy_id,version,definition_sha256,effective_day)"
                + " VALUES ('old-policy',1,'" + hash + "','2026-01-01')");
        for (String sql : statements().subList(0, interruptedAfter)) execute(sql);
        migrate();
        migrate();
        try (var c = database.getConnection(); var statement = c.createStatement(); var rows = statement.executeQuery(
                "SELECT version,definition_sha256,effective_day,definition_json FROM npc_charge_policies WHERE policy_id='old-policy'")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
            assertEquals(hash, rows.getString(2));
            assertEquals("2026-01-01", rows.getString(3));
            assertNull(rows.getString(4));
            assertFalse(rows.next());
        }
    }

    private void migrate() throws Exception {
        for (String sql : statements()) execute(sql);
    }

    private List<String> statements() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/db/migration/V46__charge_policy_history.sql")) {
            assertNotNull(input);
            return MigrationRunner.splitStatements(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private void execute(String sql) throws SQLException {
        try (var c = database.getConnection(); var statement = c.createStatement()) {
            statement.setQueryTimeout(10);
            statement.execute(sql);
        }
    }
}

package com.prosperitysmp.npc.db;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.CoreTestSupport;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the whole registered migration list once against a real MariaDB before any test, then checks the wrapper and
 * snapshot on top of it. Each test leaves the migrated schema as it found it, so any one of them can run alone.
 */
class MigrationRunnerFullRunIT {
    private static DB mariaDb;
    private static DBConfigurationBuilder config;
    private static Database database;
    private static final List<String> LOG = new ArrayList<>();
    private static MigrationRunner runner;
    private static int firstRunVersion;
    private static List<String> firstRunLog;

    @BeforeAll
    static void boot(@TempDir Path data) throws Exception {
        config = DBConfigurationBuilder.newBuilder();
        config.setPort(0);
        mariaDb = DB.newEmbeddedDB(config.addArg("--bind-address=127.0.0.1").build());
        mariaDb.start();
        mariaDb.createDB("prosperity_npc");
        database = new Database();
        database.connect(new DatabaseSettings("localhost", config.getPort(), "prosperity_npc", "root", "", 4, 10_000L));
        runner = new MigrationRunner(CoreTestSupport.plugin(data, new YamlConfiguration(), LOG), database);
        firstRunVersion = runner.run();
        firstRunLog = List.copyOf(LOG);
    }

    @AfterAll
    static void stop() throws Exception {
        if (database != null) database.close();
        if (mariaDb != null) mariaDb.stop();
    }

    private static long count(String sql) throws SQLException {
        try (Connection c = database.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long applied() {
        return LOG.stream().filter(line -> line.startsWith("INFO: Applied migration V")).count();
    }

    @Test
    void aFreshDatabaseReceivesEveryRegisteredMigrationInOrder() throws SQLException {
        assertEquals(56, firstRunVersion);
        assertEquals(56L, count("SELECT COUNT(*) FROM npc_schema_migrations"));
        assertEquals(1L, count("SELECT MIN(version) FROM npc_schema_migrations"));
        assertEquals(56L, count("SELECT MAX(version) FROM npc_schema_migrations"));
        assertEquals(56L, firstRunLog.stream().filter(line -> line.startsWith("INFO: Applied migration V")).count());
        for (int v = 1; v <= 56; v++) {
            String prefix = "INFO: Applied migration V" + v + " (";
            assertTrue(firstRunLog.get(v - 1).startsWith(prefix), firstRunLog.get(v - 1));
        }
        assertTrue(firstRunLog.get(0).startsWith("INFO: Applied migration V1 (initial_schema): "), firstRunLog.get(0));
        assertTrue(firstRunLog.get(55).startsWith("INFO: Applied migration V56 (auto_care_repair_prices): "), firstRunLog.get(55));
    }

    @Test
    void aSecondRunAppliesNothingAndReportsTheSameVersion() throws SQLException {
        long before = applied();
        assertEquals(56, runner.run());
        assertEquals(before, applied());
        assertEquals(56L, count("SELECT COUNT(*) FROM npc_schema_migrations"));
    }

    @Test
    void statusReportsTheSchemaVersionAndTheTableCount() throws SQLException {
        Database.Status status = database.status();
        assertTrue(status.connected());
        assertTrue(status.healthy());
        assertEquals(56, status.schemaVersion());
        assertEquals(count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'prosperity_npc'"),
                status.tableCount());
        assertTrue(status.tableCount() > 56, "tables: " + status.tableCount());
    }

    @Test
    void theTwoNewestMigrationsRunAgainWhenTheirRecordsAreLost() throws SQLException {
        try (Connection c = database.getConnection(); Statement st = c.createStatement()) {
            assertEquals(2, st.executeUpdate("DELETE FROM npc_schema_migrations WHERE version >= 55"));
        }
        long before = applied();
        assertEquals(56, runner.run());
        assertEquals(before + 2, applied());
        assertTrue(LOG.get(LOG.size() - 2).startsWith("INFO: Applied migration V55 (item_repair_prices): "), LOG.get(LOG.size() - 2));
        assertTrue(LOG.get(LOG.size() - 1).startsWith("INFO: Applied migration V56 (auto_care_repair_prices): "), LOG.get(LOG.size() - 1));
        assertEquals(56L, count("SELECT COUNT(*) FROM npc_schema_migrations"));
    }

    @Test
    void everyPooledConnectionKeepsItsSessionInUtc() throws SQLException {
        try (Connection c = database.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT @@session.time_zone")) {
            assertTrue(rs.next());
            assertEquals("+00:00", rs.getString(1));
        }
    }

    @Test
    void aSnapshotIsReadOnlyStampedWithTheTruncatedClockAndOwnedByItsThread() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-10-18T12:34:56.789123Z"), ZoneOffset.UTC);
        try (ReadSnapshot snapshot = ReadSnapshot.open(database, clock)) {
            assertEquals(Instant.parse("2026-10-18T12:34:56.789Z"), snapshot.capturedAt());
            Connection c = snapshot.connection(database);
            ReadSnapshot.requireTransaction(c);
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM npc_schema_migrations")) {
                assertTrue(rs.next());
                assertEquals(56L, rs.getLong(1));
            }
            assertThrows(SQLException.class, () -> c.createStatement().executeUpdate("DELETE FROM npc_schema_migrations"));
            assertThrows(SQLException.class, () -> snapshot.connection(new Database()));
            AtomicReference<Throwable> other = new AtomicReference<>();
            Thread thread = new Thread(() -> {
                try {
                    snapshot.connection(database);
                } catch (Throwable failure) {
                    other.set(failure);
                }
            });
            thread.start();
            thread.join();
            assertTrue(other.get() instanceof SQLException);
            assertEquals("Wrong or closed evidence snapshot", other.get().getMessage());
        }
        assertEquals(56L, count("SELECT COUNT(*) FROM npc_schema_migrations"));
    }

    @Test
    void aClosedSnapshotRefusesItsConnectionAndCloseIsRepeatable() throws Exception {
        ReadSnapshot snapshot = ReadSnapshot.open(database, Clock.systemUTC());
        snapshot.close();
        snapshot.close();
        var failure = assertThrows(SQLException.class, () -> snapshot.connection(database));
        assertEquals("Wrong or closed evidence snapshot", failure.getMessage());
    }

    @Test
    void aSnapshotDoesNotSeeRowsCommittedAfterItWasOpened() throws Exception {
        try (ReadSnapshot snapshot = ReadSnapshot.open(database, Clock.systemUTC())) {
            try (Connection c = database.getConnection(); Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO npc_schema_migrations (version, description) VALUES (9999, 'snapshot-probe')");
            }
            try (Statement st = snapshot.connection(database).createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM npc_schema_migrations WHERE version = 9999")) {
                rs.next();
                assertEquals(0L, rs.getLong(1));
            }
        } finally {
            try (Connection c = database.getConnection(); Statement st = c.createStatement()) {
                st.executeUpdate("DELETE FROM npc_schema_migrations WHERE version = 9999");
            }
        }
        assertEquals(0L, count("SELECT COUNT(*) FROM npc_schema_migrations WHERE version = 9999"));
    }

    @Test
    void connectingToAClosedPortFailsAndLeavesTheWrapperDisconnected() {
        Database broken = new Database();
        assertThrows(RuntimeException.class,
                () -> broken.connect(new DatabaseSettings("127.0.0.1", 1, "prosperity_npc", "root", "", 1, 1000L)));
        assertFalse(broken.isConnected());
        assertEquals(Database.Status.down(), broken.status());
    }

    @Test
    void closingTheDatabaseDisconnectsItAndRefusesFurtherConnections() throws SQLException {
        Database temporary = new Database();
        temporary.connect(new DatabaseSettings("localhost", config.getPort(), "prosperity_npc", "root", "", 2, 5000L));
        assertTrue(temporary.isConnected());
        assertNotEquals(Database.Status.down(), temporary.status());
        temporary.close();
        assertFalse(temporary.isConnected());
        assertEquals("database not connected", assertThrows(SQLException.class, temporary::getConnection).getMessage());
        assertEquals(Database.Status.down(), temporary.status());
    }
}

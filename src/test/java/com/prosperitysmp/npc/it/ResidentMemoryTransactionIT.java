package com.prosperitysmp.npc.it;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.resident.RelationshipMemory;
import com.prosperitysmp.npc.resident.RelationshipMemory.Event;
import com.prosperitysmp.npc.resident.RelationshipMemory.Kind;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentProfile;
import com.prosperitysmp.npc.resident.ResidentRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real, isolated MariaDB transactions; the marker is caller work, never a financial runtime adapter. */
@Timeout(45)
class ResidentMemoryTransactionIT {
    private static final String SCHEMA = "prosperity_npc_memory_tx";
    private static final Instant AT = Instant.parse("2026-09-06T12:00:00.123456789Z");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 6);
    private static DB mariaDb;
    private static Database database;
    private ResidentRepository residents;
    private ResidentProfile profile;
    private UUID player;

    @BeforeAll
    static void bootDatabase() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        try {
            mariaDb.start();
            mariaDb.createDB(SCHEMA);
            database = new Database();
            database.connect(new DatabaseSettings("localhost", cfg.getPort(), SCHEMA, "root", "", 6, 10_000L));
            try (InputStream in = ResidentMemoryTransactionIT.class.getResourceAsStream(
                    "/db/migration/V2__resident_identity.sql")) {
                assertNotNull(in);
                for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8))) {
                    execute(sql);
                }
            }
            execute("CREATE TABLE caller_memory_marker (marker_id INT PRIMARY KEY) ENGINE=InnoDB");
        } catch (Exception | AssertionError failure) {
            try { stopDatabase(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        try {
            if (database != null) database.close();
        } finally {
            if (mariaDb != null) {
                DB stopping = mariaDb;
                mariaDb = null;
                stopping.stop();
            }
        }
    }

    @BeforeEach
    void freshTables() throws SQLException {
        dropFaultTrigger();
        execute("DELETE FROM caller_memory_marker");
        execute("DELETE FROM npc_resident_memory");
        execute("DELETE FROM npc_resident_profiles");
        residents = new ResidentRepository(database);
        profile = ResidentCatalogue.draft(UUID.randomUUID(), "Mara Vale");
        player = UUID.randomUUID();
        assertTrue(residents.register(profile));
    }

    @AfterEach
    void dropFaultTrigger() throws SQLException {
        if (database != null && database.isConnected()) execute("DROP TRIGGER IF EXISTS memory_tx_failure");
    }

    @Test
    void callerRollbackUndoesMemoryAndMarkerWithoutRepositoryEndingTransaction() throws SQLException {
        try (Connection c = transaction()) {
            try {
                marker(c, 1);
                assertTrue(ResidentRepository.appendVerified(c, repair("repair/rollback")));
                assertCallerPending(c, 1, 1);
                assertStored(0, 0);
            } finally {
                c.rollback();
            }
            assertFalse(c.isClosed());
            assertFalse(c.getAutoCommit());
        }
        assertStored(0, 0);
        assertEquals(RelationshipMemory.Stage.STRANGER, relationship().stage());
    }

    @Test
    void callerCommitPersistsBothAndNormalizedDuplicateDoesNotAwardAgain() throws SQLException {
        Event evidence = repair("repair/commit");
        try (Connection c = transaction()) {
            try {
                assertTrue(ResidentRepository.appendVerified(c, evidence));
                marker(c, 1);
                assertFalse(ResidentRepository.appendVerified(c, normalized(evidence)));
                assertCallerPending(c, 1, 1);
                assertStored(0, 0);
                c.commit();
            } finally {
                c.rollback();
            }
        }
        assertStored(1, 1);
        assertEquals(List.of(normalized(evidence)), relationship().recent());
        assertEquals(4, relationship().reliability());
        assertFalse(residents.appendVerified(evidence), "the connection-owning API shares idempotency");
    }

    @ParameterizedTest
    @ValueSource(strings = {"kind", "time", "day"})
    void fullPayloadConflictRequiresCallerRollbackAndRetainsOriginal(String field) throws SQLException {
        Event evidence = repair("repair/conflict");
        assertTrue(residents.appendVerified(evidence));
        Event conflict = event(evidence.sourceRef(), field.equals("kind") ? Kind.OBLIGATION_BROKEN : evidence.kind(),
                field.equals("time") ? AT.plusMillis(1) : AT, field.equals("day") ? DAY.plusDays(1) : DAY);
        try (Connection c = transaction()) {
            try {
                marker(c, 1);
                SQLException failure = assertThrows(SQLException.class, () -> ResidentRepository.appendVerified(c, conflict));
                assertTrue(failure.getMessage().contains("payload conflict"));
                assertCallerPending(c, 1, 1);
                assertStored(1, 0);
            } finally {
                c.rollback();
            }
        }
        assertStored(1, 0);
        assertEquals(List.of(normalized(evidence)), relationship().recent());
    }
    @ParameterizedTest
    @ValueSource(ints = {RelationshipMemory.MAX_EVENTS, RelationshipMemory.MAX_EVENTS + 1})
    void fullOrOverflowingMemoryRejectsAppendWithoutDiscardingCallerWork(int count) throws SQLException {
        seedHistory(count);
        try (Connection c = transaction()) {
            try {
                marker(c, 1);
                if (count == RelationshipMemory.MAX_EVENTS) {
                    assertFalse(ResidentRepository.appendVerified(c, event("fixture/0", Kind.REPAIR_COMPLETED, AT, DAY)));
                }
                SQLException failure = assertThrows(SQLException.class,
                        () -> ResidentRepository.appendVerified(c, repair("repair/full")));
                assertTrue(failure.getMessage().contains(count == RelationshipMemory.MAX_EVENTS
                        ? "memory is full" : "exceeds replay bound"));
                assertCallerPending(c, count, 1);
            } finally {
                c.rollback();
            }
        }
        assertStored(count, 0);
    }

    @Test
    void insertFailureLeavesPriorMemoryAndMarkerForCallerToAbort() throws SQLException {
        execute("""
                CREATE TRIGGER memory_tx_failure AFTER INSERT ON npc_resident_memory FOR EACH ROW
                BEGIN
                  IF NEW.source_ref='repair/fail' THEN
                    UPDATE npc_resident_profiles SET generation=8 WHERE resident_id=NEW.resident_id;
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='caller memory failure fixture';
                  END IF;
                END
                """);
        try (Connection c = transaction()) {
            try {
                marker(c, 1);
                assertTrue(ResidentRepository.appendVerified(c, repair("repair/prior")));
                SQLException failure = assertThrows(SQLException.class,
                        () -> ResidentRepository.appendVerified(c, repair("repair/fail")));
                assertEquals("45000", failure.getSQLState());
                assertCallerPending(c, 1, 1);
                assertEquals(profile.generation(), scalar(c, "SELECT generation FROM npc_resident_profiles LIMIT 1"));
                assertStored(0, 0);
            } finally {
                c.rollback();
            }
        }
        assertStored(0, 0);
        assertEquals(profile, residents.find(profile.id()).orElseThrow());
    }

    @Test
    void autoCommitIsRejectedWithoutChangingConnectionOrStoredData() throws SQLException {
        try (Connection c = database.getConnection()) {
            assertTrue(c.getAutoCommit());
            marker(c, 1);
            SQLException failure = assertThrows(SQLException.class,
                    () -> ResidentRepository.appendVerified(c, repair("repair/autocommit")));
            assertTrue(failure.getMessage().contains("caller-owned transaction"));
            assertTrue(c.getAutoCommit());
            assertFalse(c.isClosed());
            assertEquals(1, scalar(c, "SELECT COUNT(*) FROM caller_memory_marker"));
        }
        assertStored(0, 1);
        assertEquals(profile, residents.find(profile.id()).orElseThrow());
    }

    @Test
    void reservedIntroductionPairAndEpochNormalizationAreEnforced() throws SQLException {
        Event introduction = event("introduction", Kind.INTRODUCTION, AT, DAY);
        try (Connection c = transaction()) {
            try {
                assertTrue(ResidentRepository.appendVerified(c, introduction));
                assertFalse(ResidentRepository.appendVerified(c, normalized(introduction)));
                assertThrows(IllegalArgumentException.class,
                        () -> ResidentRepository.appendVerified(c, repair("introduction")));
                assertThrows(IllegalArgumentException.class, () -> ResidentRepository.appendVerified(c,
                        event("introduction/again", Kind.INTRODUCTION, AT, DAY)));
                assertCallerPending(c, 1, 0);
            } finally {
                c.rollback();
            }
        }
        try (Connection c = transaction()) {
            try {
                Event beforeEpoch = event("repair/epoch", Kind.REPAIR_COMPLETED,
                        Instant.parse("1969-12-31T23:59:59.999999999Z"), LocalDate.of(1969, 12, 31));
                assertTrue(ResidentRepository.appendVerified(c, beforeEpoch));
                assertFalse(ResidentRepository.appendVerified(c, normalized(beforeEpoch)));
                assertEquals(-1, scalar(c, "SELECT occurred_at_millis FROM npc_resident_memory LIMIT 1"));
                assertThrows(IllegalArgumentException.class, () -> ResidentRepository.appendVerified(c,
                        event("repair/range", Kind.REPAIR_COMPLETED, Instant.MAX, DAY)));
            } finally {
                c.rollback();
            }
        }
        assertStored(0, 0);
    }

    @Test
    void missingResidentFailsWithoutInventingIdentityOrEndingCallerTransaction() throws SQLException {
        Event missing = new Event(UUID.randomUUID(), player, "repair/missing", Kind.REPAIR_COMPLETED, AT, DAY);
        try (Connection c = transaction()) {
            try {
                marker(c, 1);
                SQLException failure = assertThrows(SQLException.class, () -> ResidentRepository.appendVerified(c, missing));
                assertTrue(failure.getMessage().contains("profile does not exist"));
                assertCallerPending(c, 0, 1);
            } finally {
                c.rollback();
            }
        }
        assertStored(0, 0);
        assertTrue(residents.find(missing.residentId()).isEmpty());
    }

    @Test
    void oldRepeatableReadSnapshotSeesCommittedDuplicateAndPreservesBothDistinctEvents() throws SQLException {
        Event first = repair("repair/first");
        Event second = event("repair/second", Kind.REPAIR_COMPLETED, AT.plusSeconds(1), DAY);
        try (Connection c = transaction()) {
            try {
                assertEquals(0, memoryCount(c), "establish the outer consistent-read snapshot before the other writer");
                commitOther(first, 1);
                assertEquals(0, memoryCount(c), "outer nonlocking snapshot must still be stale");
                assertFalse(ResidentRepository.appendVerified(c, first), "retry must not award a second caller marker");
                assertTrue(ResidentRepository.appendVerified(c, second));
                marker(c, 2);
                assertFalse(ResidentRepository.appendVerified(c, second));
                assertFalse(c.getAutoCommit());
                assertEquals(Connection.TRANSACTION_REPEATABLE_READ, c.getTransactionIsolation());
                assertStored(1, 1);
                c.commit();
            } finally {
                c.rollback();
            }
        }
        assertStored(2, 2);
        assertEquals(List.of(normalized(second), normalized(first)), relationship().recent());
        assertEquals(RelationshipMemory.MAX_POSITIVE_PER_DAY, relationship().reliability());
        assertEquals(1, relationship().distinctPositiveDays());
    }

    @Test
    void oldSnapshotCannotBypassCapacityAfterCompetingWriterFillsLastSlot() throws SQLException {
        seedHistory(RelationshipMemory.MAX_EVENTS - 1);
        try (Connection c = transaction()) {
            try {
                assertEquals(RelationshipMemory.MAX_EVENTS - 1, memoryCount(c));
                commitOther(event("repair/last", Kind.REPAIR_COMPLETED, AT.plusSeconds(60), DAY), 1);
                assertEquals(RelationshipMemory.MAX_EVENTS - 1, memoryCount(c));
                marker(c, 2);
                SQLException failure = assertThrows(SQLException.class, () -> ResidentRepository.appendVerified(c,
                        event("repair/overflow", Kind.REPAIR_COMPLETED, AT.plusSeconds(61), DAY)));
                assertTrue(failure.getMessage().contains("memory is full"));
                assertFalse(c.getAutoCommit());
                assertEquals(1, scalar(c, "SELECT COUNT(*) FROM caller_memory_marker WHERE marker_id=2"));
            } finally {
                c.rollback();
            }
        }
        assertStored(RelationshipMemory.MAX_EVENTS, 1);
    }

    @Test
    void oldSnapshotCannotHideCommittedHistoryFromChronologyValidation() throws SQLException {
        Event first = repair("repair/current");
        try (Connection c = transaction()) {
            try {
                assertEquals(0, memoryCount(c));
                commitOther(first, 1);
                assertEquals(0, memoryCount(c));
                marker(c, 2);
                SQLException failure = assertThrows(SQLException.class, () -> ResidentRepository.appendVerified(c,
                        event("repair/regressed", Kind.REPAIR_COMPLETED, AT.plusSeconds(1), DAY.minusDays(1))));
                assertTrue(failure.getMessage().contains("chronology"));
                assertFalse(c.isClosed());
                assertEquals(1, scalar(c, "SELECT COUNT(*) FROM caller_memory_marker WHERE marker_id=2"));
            } finally {
                c.rollback();
            }
        }
        assertStored(1, 1);
        assertEquals(List.of(normalized(first)), relationship().recent());
    }

    @Test
    void residentLockIsHeldUntilCallerFinishesButRelationshipReadDoesNotLock() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        UUID otherPlayer = UUID.randomUUID();
        Event competing = new Event(profile.id(), otherPlayer, "repair/waiter", Kind.REPAIR_COMPLETED, AT, DAY);
        try {
            Future<Boolean> waiting;
            try (Connection c = transaction()) {
                try {
                    assertTrue(ResidentRepository.appendVerified(c, repair("repair/held")));
                    waiting = worker.submit(() -> {
                        try (Connection other = transaction()) {
                            try {
                                boolean added = ResidentRepository.appendVerified(other, competing);
                                other.commit();
                                return added;
                            } finally {
                                other.rollback();
                            }
                        }
                    });
                    String query = awaitResidentWaiter().toUpperCase(Locale.ROOT);
                    assertTrue(query.contains("SELECT RESIDENT_ID FROM NPC_RESIDENT_PROFILES")
                            && query.contains("FOR UPDATE"), "must wait on profile read before touching memory: " + query);
                    assertFalse(waiting.isDone());
                    assertEquals(RelationshipMemory.Stage.STRANGER, relationship().stage(),
                            "readRelationship must not wait for the uncommitted resident or memory locks");
                    assertFalse(waiting.isDone());
                } finally {
                    c.rollback();
                }
            }
            assertTrue(waiting.get(10, TimeUnit.SECONDS));
            assertEquals(RelationshipMemory.Stage.STRANGER, relationship().stage());
            assertEquals(List.of(normalized(competing)), residents.readRelationship(profile.id(), otherPlayer).recent());
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(15, TimeUnit.SECONDS), "resident transaction worker terminated");
        }
    }
    private Event repair(String source) {
        return event(source, Kind.REPAIR_COMPLETED, AT, DAY);
    }

    private Event event(String source, Kind kind, Instant at, LocalDate day) {
        return new Event(profile.id(), player, source, kind, at, day);
    }

    private static Event normalized(Event event) {
        return new Event(event.residentId(), event.playerId(), event.sourceRef(), event.kind(),
                Instant.ofEpochMilli(event.occurredAt().toEpochMilli()), event.economyDay());
    }

    private RelationshipMemory.State relationship() throws SQLException {
        return residents.readRelationship(profile.id(), player);
    }

    private static Connection transaction() throws SQLException {
        Connection c = database.getConnection();
        try {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try (PreparedStatement ps = prepare(c, "SET SESSION innodb_lock_wait_timeout=5")) {
                ps.execute();
            }
            c.setAutoCommit(false);
            return c;
        } catch (SQLException failure) {
            try { c.close(); } catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static void marker(Connection c, int id) throws SQLException {
        try (PreparedStatement ps = prepare(c, "INSERT INTO caller_memory_marker (marker_id) VALUES (?)")) {
            ps.setInt(1, id);
            assertEquals(1, ps.executeUpdate());
        }
    }

    private static void commitOther(Event event, int markerId) throws SQLException {
        // Overlap two real transactions with deterministic ordering: the outer snapshot already exists.
        try (Connection other = transaction()) {
            try {
                assertTrue(ResidentRepository.appendVerified(other, event));
                marker(other, markerId);
                other.commit();
            } finally {
                other.rollback();
            }
        }
    }

    private long memoryCount(Connection c) throws SQLException {
        try (PreparedStatement ps = prepare(c,
                "SELECT COUNT(*) FROM npc_resident_memory WHERE resident_id=? AND player_id=?")) {
            ps.setBytes(1, Uuids.toBytes(profile.id()));
            ps.setBytes(2, Uuids.toBytes(player));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    private void assertStored(long memories, long markers) throws SQLException {
        try (Connection observer = database.getConnection()) {
            assertEquals(memories, memoryCount(observer));
            assertEquals(markers, scalar(observer, "SELECT COUNT(*) FROM caller_memory_marker"));
        }
    }

    private void assertCallerPending(Connection c, long memories, long markers) throws SQLException {
        assertFalse(c.isClosed(), "caller connection must remain open");
        assertFalse(c.getAutoCommit(), "caller must retain transaction ownership");
        assertEquals(Connection.TRANSACTION_REPEATABLE_READ, c.getTransactionIsolation());
        assertEquals(memories, memoryCount(c));
        assertEquals(markers, scalar(c, "SELECT COUNT(*) FROM caller_memory_marker"));
    }

    private void seedHistory(int count) throws SQLException {
        assertTrue(count >= 0 && count <= RelationshipMemory.MAX_EVENTS + 1);
        try (Connection c = transaction(); PreparedStatement ps = prepare(c, """
                INSERT INTO npc_resident_memory (resident_id, player_id, source_ref, kind, occurred_at_millis, economy_day)
                VALUES (?, ?, ?, 'REPAIR_COMPLETED', ?, ?)
                """)) {
            try {
                int pending = 0;
                for (int i = 0; i < count; i++) {
                    ps.setBytes(1, Uuids.toBytes(profile.id()));
                    ps.setBytes(2, Uuids.toBytes(player));
                    ps.setString(3, "fixture/" + i);
                    ps.setLong(4, AT.toEpochMilli() + i);
                    ps.setString(5, DAY.toString());
                    ps.addBatch();
                    pending++;
                    if (pending == 128 || i == count - 1) {
                        assertEquals(pending, ps.executeBatch().length);
                        ps.clearBatch();
                        pending = 0;
                    }
                }
                c.commit();
            } finally {
                c.rollback();
            }
        }
    }

    private static String awaitResidentWaiter() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            try (Connection observer = database.getConnection(); PreparedStatement ps = prepare(observer, """
                    SELECT INFO FROM information_schema.PROCESSLIST
                    WHERE ID<>CONNECTION_ID() AND DB=? AND INFO LIKE '%npc_resident_%' LIMIT 1
                    """)) {
                ps.setQueryTimeout(1);
                ps.setString(1, SCHEMA);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return rs.getString(1);
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("resident writer did not reach the initial profile lock within three seconds");
    }

    private static long scalar(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = prepare(c, sql); ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c, sql)) {
            ps.execute();
        }
    }

    private static PreparedStatement prepare(Connection c, String sql) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        try {
            ps.setQueryTimeout(5);
            return ps;
        } catch (SQLException failure) {
            try { ps.close(); } catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
}

package com.prosperitysmp.npc.it;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;

import java.io.InputStream;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Real V1 ownership/funding schema; no runtime, server, or synthetic SQL results. */
@Timeout(60)
class OwnershipReconciliationIT {
    private static final UUID ID = new UUID(0, 7100), WORLD = new UUID(0, 7101);
    private static final UUID A = new UUID(0, 7110), B = new UUID(0, 7111), C = new UUID(0, 7112);
    private static DB maria;
    private static Database database;
    private PropertyRepository repository;
    private Property expected;

    @BeforeAll static void boot() throws Exception {
        var cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        maria = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        try {
            maria.start();
            String schema = "npc_owner_" + UUID.randomUUID().toString().replace("-", "");
            maria.createDB(schema);
            database = new Database();
            database.connect(new DatabaseSettings("localhost", cfg.getPort(), schema, "root", "", 6, 10_000L));
            try (InputStream in = OwnershipReconciliationIT.class.getResourceAsStream("/db/migration/V1__initial_schema.sql")) {
                assertNotNull(in);
                for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8)))
                    execute(sql);
            }
        } catch (Exception | AssertionError failure) {
            try { shutdown(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @AfterAll static void shutdown() throws Exception {
        try { if (database != null) database.close(); }
        finally { if (maria != null) { DB stopping = maria; maria = null; stopping.stop(); } }
    }

    @BeforeEach void seed() throws SQLException {
        execute("DROP TRIGGER IF EXISTS reject_owner_history");
        execute("DELETE FROM npc_property_funding");
        execute("DELETE FROM npc_property_ownership");
        execute("DELETE FROM npc_properties");
        repository = new PropertyRepository(database);
        expected = new Property(ID, WORLD, "c001", "downtown", "room", 3, 1200, 1300, A, 3, 87, true);
        repository.insertEnrollment(expected, 5700);
        execute("UPDATE npc_properties SET row_version=11,last_arm_reconcile_at='2026-09-07 12:00:00.123'");
    }

    @Test void findByIdReturnsCompletePersistedTermsAndNoWrites() throws Exception {
        var before = data();
        assertEquals(Optional.of(expected), repository.findById(ID));
        assertEquals(before, data());
    }

    @Test void missingRowReadIsEmptyAndBothWritersReject() throws Exception {
        execute("DELETE FROM npc_property_funding");
        execute("DELETE FROM npc_properties");
        assertTrue(repository.findById(ID).isEmpty());
        rejectUnchanged(() -> repository.reconcileOwner(expected, B));
        rejectUnchanged(() -> repository.applyOwnerChange(ID, A, B, 4, "test"));
    }

    @Test void strictChangeRejectsWrongOldOwner() throws Exception {
        rejectUnchanged(() -> repository.applyOwnerChange(ID, C, B, 4, "test"));
    }

    @Test void strictChangeRejectsSkippedEpoch() throws Exception {
        rejectUnchanged(() -> repository.applyOwnerChange(ID, A, B, 5, "test"));
    }

    @Test void strictChangeRejectsZeroNegativeAndRegressingEpochs() throws Exception {
        for (int epoch : new int[]{0, -1, Integer.MIN_VALUE, 1, 2, 3})
            rejectUnchanged(() -> repository.applyOwnerChange(ID, A, B, epoch, "test"));
    }

    @Test void strictChangeRequiresNonblankBoundedSourceBeforeWriting() throws Exception {
        for (String source : new String[]{null, "", "  ", "x".repeat(256)})
            rejectUnchanged(() -> repository.applyOwnerChange(ID, A, B, 4, source));
    }

    @Test void strictChangeWritesExactlyOneNextEpochAndHistory() throws Exception {
        repository.applyOwnerChange(ID, A, B, 4, "verified-observation");
        assertEquals(expected.withOwner(B, 4), repository.findById(ID).orElseThrow());
        assertEquals(12, revision());
        assertHistory(A, B, 4, "verified-observation");
        rejectUnchanged(() -> repository.applyOwnerChange(ID, A, B, 4, "verified-observation"));
    }

    @Test void reconciliationRejectsOwnerMismatchEvenWithSameEpoch() throws Exception {
        execute("UPDATE npc_properties SET owner_uuid=? WHERE property_id=?", C, ID);
        rejectUnchanged(() -> repository.reconcileOwner(expected, B));
    }

    @Test void reconciliationRejectsEpochMismatchEvenWithSameOwner() throws Exception {
        execute("UPDATE npc_properties SET ownership_epoch=4 WHERE property_id=?", ID);
        rejectUnchanged(() -> repository.reconcileOwner(expected, B));
    }

    @Test void ownerAtoBtoALateObservationCannotOverwriteSuccessor() throws Exception {
        repository.applyOwnerChange(ID, A, B, 4, "first");
        repository.applyOwnerChange(ID, B, A, 5, "second");
        rejectUnchanged(() -> repository.reconcileOwner(expected, C));
        assertEquals(expected.withOwner(A, 5), repository.findById(ID).orElseThrow());
        assertEquals(3, historyCount());
    }

    @Test void changedWorldRejectsOriginalObservation() throws Exception {
        execute("UPDATE npc_properties SET world_uuid=? WHERE property_id=?", new UUID(0, 7190), ID);
        rejectUnchanged(() -> repository.reconcileOwner(expected, B));
    }

    @Test void changedRegionRejectsOriginalObservation() throws Exception {
        execute("UPDATE npc_properties SET region_id='c006' WHERE property_id=?", ID);
        rejectUnchanged(() -> repository.reconcileOwner(expected, B));
    }

    @Test void disabledActualOrDisabledExpectedRejectsEvenSameOwner() throws Exception {
        repository.setEnabled(ID, false);
        Property disabled = repository.findById(ID).orElseThrow();
        rejectUnchanged(() -> repository.reconcileOwner(expected, A));
        repository.setEnabled(ID, true);
        rejectUnchanged(() -> repository.reconcileOwner(disabled, B));
    }

    @Test void epochExhaustionRejectsChangeButAllowsReadOnlySameOwner() throws Exception {
        execute("UPDATE npc_properties SET ownership_epoch=?", Integer.MAX_VALUE);
        Property exhausted = repository.findById(ID).orElseThrow();
        rejectUnchanged(() -> repository.reconcileOwner(exhausted, B));
        rejectUnchanged(() -> repository.applyOwnerChange(ID, A, B, Integer.MAX_VALUE, "test"));
        var before = data();
        assertEquals(exhausted, repository.reconcileOwner(exhausted, A));
        assertEquals(before, data());
    }

    @Test void revisionExhaustionRejectsBothWritersButAllowsReadOnlySameOwner() throws Exception {
        execute("UPDATE npc_properties SET row_version=?", Long.MAX_VALUE);
        rejectUnchanged(() -> repository.reconcileOwner(expected, B));
        rejectUnchanged(() -> repository.applyOwnerChange(ID, A, B, 4, "test"));
        var before = data();
        assertEquals(expected, repository.reconcileOwner(expected, A));
        assertEquals(before, data());
    }

    @Test void corruptEpochRevisionAndEnabledAreRejectedByReadAndReconcile() throws Exception {
        for (String corruption : List.of("ownership_epoch=-1", "row_version=-1", "enabled=2")) {
            execute("UPDATE npc_properties SET ownership_epoch=3,row_version=11,enabled=1");
            execute("UPDATE npc_properties SET " + corruption);
            rejectUnchanged(() -> repository.findById(ID));
            rejectUnchanged(() -> repository.reconcileOwner(expected, B));
        }
    }

    @Test void nullableOwnerTransitionsAreRecordedAndNoOwnerIsReadBackAsNull() throws Exception {
        Property unowned = repository.reconcileOwner(expected, null);
        assertEquals(expected.withOwner(null, 4), unowned);
        assertNull(repository.findById(ID).orElseThrow().ownerUuid());
        assertHistory(A, null, 4, "arm-reconcile");
        Property restored = repository.reconcileOwner(unowned, B);
        assertEquals(expected.withOwner(B, 5), restored);
        assertHistory(null, B, 5, "arm-reconcile");
        assertEquals(13, revision());
    }

    @Test void strictChangeSupportsNullOldAndNewOwnersWithoutWeakeningCas() throws Exception {
        repository.applyOwnerChange(ID, A, null, 4, "unowned");
        rejectUnchanged(() -> repository.applyOwnerChange(ID, A, B, 5, "stale"));
        repository.applyOwnerChange(ID, null, B, 5, "owned");
        assertEquals(expected.withOwner(B, 5), repository.findById(ID).orElseThrow());
        assertHistory(null, B, 5, "owned");
    }

    @Test void sameOwnerReturnsFreshTermsWithoutHistoryOrRevisionWrite() throws Exception {
        changeTerms();
        Property fresh = repository.findById(ID).orElseThrow();
        var before = data();
        assertEquals(fresh, repository.reconcileOwner(expected, A));
        assertEquals(before, data());
        assertEquals(17, revision());
        assertEquals(1, historyCount());
    }

    @Test void sameNullOwnerAlsoRemainsReadOnly() throws Exception {
        Property unowned = repository.reconcileOwner(expected, null);
        var before = data();
        assertEquals(unowned, repository.reconcileOwner(unowned, null));
        assertEquals(before, data());
    }

    @Test void changedOwnerPreservesFreshTermsFundingAndUnrelatedColumns() throws Exception {
        changeTerms();
        Property fresh = repository.findById(ID).orElseThrow();
        var terms = rows("SELECT world_uuid,region_id,market_id,class_key,capacity,reference_rent_cents,"
                + "asking_rent_cents,condition_score,enabled,last_arm_reconcile_at,created_at FROM npc_properties");
        var funding = rows("SELECT * FROM npc_property_funding");
        assertEquals(fresh.withOwner(B, 4), repository.reconcileOwner(expected, B));
        assertEquals(fresh.withOwner(B, 4), repository.findById(ID).orElseThrow());
        assertEquals(terms, rows("SELECT world_uuid,region_id,market_id,class_key,capacity,reference_rent_cents,"
                + "asking_rent_cents,condition_score,enabled,last_arm_reconcile_at,created_at FROM npc_properties"));
        assertEquals(funding, rows("SELECT * FROM npc_property_funding"));
        assertEquals(18, revision());
        assertHistory(A, B, 4, "arm-reconcile");
    }

    @Test void historyInsertFailureRollsBackOwnerEpochAndRevisionForBothWriters() throws Exception {
        execute("CREATE TRIGGER reject_owner_history BEFORE INSERT ON npc_property_ownership FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected history insert failure'");
        try {
            SQLException first = rejectUnchanged(() -> repository.reconcileOwner(expected, B));
            assertTrue(first.getMessage().contains("injected history insert failure"), first.getMessage());
            SQLException second = rejectUnchanged(() -> repository.applyOwnerChange(ID, A, B, 4, "test"));
            assertTrue(second.getMessage().contains("injected history insert failure"), second.getMessage());
        } finally { execute("DROP TRIGGER reject_owner_history"); }
        assertEquals(expected.withOwner(B, 4), repository.reconcileOwner(expected, B));
        assertHistory(A, B, 4, "arm-reconcile");
    }

    @Test void lostCommitAcknowledgementIsNotRetriedAsANewOwnershipChange() throws Exception {
        try (Probe probe = new Probe(true, false)) {
            SQLException failure = assertThrows(SQLException.class,
                    () -> new PropertyRepository(probe.routed).reconcileOwner(expected, B));
            assertEquals("injected lost commit acknowledgement", failure.getMessage());
            assertTrue(probe.committed.get(), "fault must occur after the real commit");
        }
        assertEquals(expected.withOwner(B, 4), repository.findById(ID).orElseThrow());
        assertEquals(12, revision());
        assertHistory(A, B, 4, "arm-reconcile");
        rejectUnchanged(() -> repository.reconcileOwner(expected, B));
        var before = data();
        Property current = repository.findById(ID).orElseThrow();
        assertEquals(current, repository.reconcileOwner(current, B));
        assertEquals(before, data());
    }

    @Test void twoConcurrentObserversOnlyOneCommitsAndTheLoserCannotAddHistory() throws Exception {
        CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Callable<Optional<Property>> first = observer(B, ready, go);
            Callable<Optional<Property>> second = observer(C, ready, go);
            Future<Optional<Property>> one = pool.submit(first), two = pool.submit(second);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            List<Optional<Property>> outcomes = List.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter(Optional::isPresent).count());
            Property winner = outcomes.stream().flatMap(Optional::stream).findFirst().orElseThrow();
            assertTrue(Set.of(B, C).contains(winner.ownerUuid()));
            assertEquals(4, winner.ownershipEpoch());
            assertEquals(winner, repository.findById(ID).orElseThrow());
            assertEquals(12, revision());
            assertHistory(A, winner.ownerUuid(), 4, "arm-reconcile");
        } finally { go.countDown(); }
    }

    @Test void rowLockTimeoutLeavesOwnerAndHistoryUntouchedAndReleasesWorkerConnection() throws Exception {
        var before = data();
        try (Connection blocker = database.getConnection(); Probe probe = new Probe(false, true)) {
            blocker.setAutoCommit(false);
            try {
                execute(blocker, "SELECT property_id FROM npc_properties WHERE property_id=? FOR UPDATE", ID);
                SQLException failure = assertThrows(SQLException.class,
                        () -> new PropertyRepository(probe.routed).reconcileOwner(expected, B));
                assertEquals(1205, failure.getErrorCode(), "must fail on the real InnoDB row lock, not a fixture error");
                assertFalse(probe.committed.get());
            } finally { blocker.rollback(); }
        }
        assertEquals(before, data());
        assertEquals(expected.withOwner(B, 4), repository.reconcileOwner(expected, B));
        assertHistory(A, B, 4, "arm-reconcile");
    }

    private Callable<Optional<Property>> observer(UUID owner, CountDownLatch ready, CountDownLatch go) {
        return () -> {
            ready.countDown();
            assertTrue(go.await(5, TimeUnit.SECONDS));
            try { return Optional.of(repository.reconcileOwner(expected, owner)); }
            catch (SQLException stale) {
                assertEquals("Stale property ownership observation", stale.getMessage());
                return Optional.empty();
            }
        };
    }

    private static void changeTerms() throws SQLException {
        execute("UPDATE npc_properties SET market_id='uptown',class_key='apartment',capacity=4,"
                + "reference_rent_cents=1600,asking_rent_cents=1900,condition_score=94,row_version=17");
    }
    @FunctionalInterface private interface SqlAction { void run() throws SQLException; }
    private static SQLException rejectUnchanged(SqlAction action) throws SQLException {
        var before = data();
        SQLException error = assertThrows(SQLException.class, action::run);
        assertEquals(before, data(), "rejected operation changed persistent state");
        return error;
    }
    private static long revision() throws SQLException {
        return Long.parseLong(rows("SELECT row_version FROM npc_properties").getFirst().getFirst());
    }
    private static int historyCount() throws SQLException {
        return Integer.parseInt(rows("SELECT COUNT(*) FROM npc_property_ownership").getFirst().getFirst());
    }
    private static void assertHistory(UUID oldOwner, UUID newOwner, int epoch, String source) throws SQLException {
        assertEquals(epoch - 2, historyCount(), "one enrollment plus one row per observed change");
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT property_id,old_owner_uuid,new_owner_uuid,ownership_epoch,source_snapshot "
                        + "FROM npc_property_ownership ORDER BY id DESC LIMIT 1"); ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertArrayEquals(Uuids.toBytes(ID), rs.getBytes(1));
            assertArrayEquals(oldOwner == null ? null : Uuids.toBytes(oldOwner), rs.getBytes(2));
            assertArrayEquals(newOwner == null ? null : Uuids.toBytes(newOwner), rs.getBytes(3));
            assertEquals(epoch, rs.getInt(4));
            assertEquals(source, rs.getString(5));
            assertFalse(rs.next());
        }
    }
    private static Map<String, List<List<String>>> data() throws SQLException {
        var result = new LinkedHashMap<String, List<List<String>>>();
        for (String table : List.of("npc_properties", "npc_property_funding", "npc_property_ownership"))
            result.put(table, rows("SELECT * FROM " + table));
        return result;
    }
    private static List<List<String>> rows(String sql) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(5);
            try (ResultSet rs = ps.executeQuery()) {
                var out = new ArrayList<List<String>>();
                while (rs.next()) {
                    var row = new ArrayList<String>();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                        Object value = rs.getObject(i);
                        row.add(value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : rs.getString(i));
                    }
                    out.add(row);
                }
                out.sort(Comparator.comparing(List::toString));
                return out;
            }
        }
    }
    private static void execute(String sql, Object... args) throws SQLException {
        try (Connection c = database.getConnection()) { execute(c, sql, args); }
    }
    private static void execute(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(5);
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof UUID uuid) ps.setBytes(i + 1, Uuids.toBytes(uuid));
                else ps.setObject(i + 1, args[i]);
            }
            ps.execute();
        }
    }

    /** Forwards every SQL operation to MariaDB; only commit acknowledgement/session timeout differ. */
    private static final class Probe implements AutoCloseable {
        final Database routed = new Database();
        final AtomicBoolean committed = new AtomicBoolean();
        Probe(boolean loseAcknowledgement, boolean shortLockTimeout) throws ReflectiveOperationException {
            var forwarding = new HikariDataSource() {
                @Override public Connection getConnection() throws SQLException {
                    Connection actual = database.getConnection();
                    int previous;
                    try {
                        try (Statement st = actual.createStatement(); ResultSet rs = st.executeQuery("SELECT @@SESSION.innodb_lock_wait_timeout")) {
                            assertTrue(rs.next()); previous = rs.getInt(1);
                        }
                        if (shortLockTimeout) execute(actual, "SET SESSION innodb_lock_wait_timeout=1");
                    } catch (SQLException | RuntimeException | Error failure) {
                        try { actual.close(); } catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
                        throw failure;
                    }
                    return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                            (ignored, method, args) -> {
                                if (method.getName().equals("close") && shortLockTimeout) {
                                    try { execute(actual, "SET SESSION innodb_lock_wait_timeout=" + previous); }
                                    finally { actual.close(); }
                                    return null;
                                }
                                Object result;
                                try { result = method.invoke(actual, args); }
                                catch (InvocationTargetException error) { throw error.getCause(); }
                                if (method.getName().equals("commit")) {
                                    committed.set(true);
                                    if (loseAcknowledgement) throw new SQLException("injected lost commit acknowledgement");
                                }
                                return result;
                            });
                }
            };
            Field field = Database.class.getDeclaredField("dataSource");
            field.setAccessible(true); field.set(routed, forwarding);
        }
        @Override public void close() { routed.close(); }
    }
}

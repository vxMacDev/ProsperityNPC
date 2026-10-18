package com.prosperitysmp.npc.it;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.economy.EconomyEvent;
import com.prosperitysmp.npc.economy.EconomyEventRepository;
import com.prosperitysmp.npc.economy.EventSource;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real MariaDB ledger tests. Fault triggers exist only in this embedded test database. */
class EconomyLedgerIT {

    private static final UUID WORLD = UUID.fromString("00000000-0000-4000-8000-0000000000ab");
    private static final String DAY = "2026-09-06";
    private static final long SEED = 5_000;
    private static final List<String> FIXTURE_TRIGGERS = List.of(
            "ledger_insert_failure", "ledger_funding_failure", "ledger_event_failure");

    private static DB mariaDb;
    private static Database database;

    private EconomyEventRepository events;
    private PropertyRepository properties;
    private UUID property;
    private UUID owner;

    @BeforeAll
    static void bootDatabase() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        mariaDb.start();
        mariaDb.createDB("prosperity_npc_ledger");
        database = new Database();
        database.connect(new DatabaseSettings("localhost", cfg.getPort(), "prosperity_npc_ledger",
                "root", "", 6, 10_000L));

        for (String resource : List.of("V1__initial_schema.sql", "V41__charge_catalogue.sql", "V45__property_backend.sql")) {
            try (InputStream in = EconomyLedgerIT.class.getResourceAsStream("/db/migration/" + resource)) {
                assertNotNull(in, resource);
                String schema = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                try (Connection c = database.getConnection(); Statement st = c.createStatement()) {
                    for (String sql : MigrationRunner.splitStatements(schema)) {
                        st.execute(sql);
                    }
                }
            }
        }
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (database != null) {
            database.close();
        }
        if (mariaDb != null) {
            mariaDb.stop();
        }
    }

    @BeforeEach
    void freshLedger() throws SQLException {
        dropFixtureTriggers();
        for (String table : List.of("npc_economy_events", "npc_property_ownership",
                "npc_property_funding", "npc_properties")) {
            execute("DELETE FROM " + table);
        }
        events = new EconomyEventRepository(database);
        properties = new PropertyRepository(database);
        property = UUID.randomUUID();
        owner = UUID.randomUUID();
        properties.insertEnrollment(new Property(property, WORLD, "LEDGER", "downtown", "room",
                2, 1_000, 1_000, owner, 0, 80, true), SEED);
    }

    @AfterEach
    void dropFixtureTriggers() throws SQLException {
        if (database != null && database.isConnected()) {
            for (String trigger : FIXTURE_TRIGGERS) {
                execute("DROP TRIGGER IF EXISTS " + trigger);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(EventSource.class)
    void eachSourceBooksExactlyOnce(EventSource source) throws SQLException {
        long id = claimed(source);
        EventSnapshot before = snapshot(id);

        events.finalizePaid(id, 500);
        EventSnapshot paid = snapshot(id);
        assertEquals("PAID", paid.state());
        assertEquals(Long.valueOf(500), paid.paidCents());
        assertEquals(before.claimedAt(), paid.claimedAt());
        assertNotNull(paid.finalizedAt());
        assertFalse(events.claim(id));

        events.finalizePaid(id, 500);
        events.finalizeFailed(id);
        assertEquals(paid, snapshot(id), "terminal acknowledgement must not rewrite the event");
        assertArrayEquals(new long[]{SEED, source.fundsRent() ? 500 : 0, source.isPayout() ? 500 : 0},
                properties.fundingOf(property));
        try (Connection c = database.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT successful_cash_unfunded_cents FROM npc_property_funding")) {
            assertTrue(rs.next());
            assertEquals(source == EventSource.BILLS ? 500 : 0, rs.getLong(1));
        }
        assertEquals(source.isPayout() ? 500 : 0, events.sumPaidToPublicForDay(DAY));
        assertEquals(source.isPayout() ? 500 : 0, events.sumPaidToPublicForProperty(DAY, property));
        assertEquals(source.isPayout() ? 500 : 0, events.sumPaidToPublicForBenefitUnit(DAY, owner));
        assertEquals(source.isAppreciation() ? 500 : 0, events.sumPaidAppreciationForProperty(DAY, property));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "CLAIMED", "PAID", "FAILED", "AMBIGUOUS", "CANCELLED"})
    void identicalRetriesPreserveExistingStateAndNullablePayload(String state) throws SQLException {
        for (boolean nullable : new boolean[]{false, true}) {
            EconomyEvent event = nullable
                    ? EconomyEvent.pending("nullable", 500, EventSource.RENT, null, null, null, null, DAY)
                    : request("complete", EventSource.RENT);
            long id = events.insertPending(event);
            assertTrue(id > 0);
            putInState(id, state);
            EventSnapshot before = snapshot(id);
            long[] funding = properties.fundingOf(property);

            assertEquals(-1L, events.insertPending(event));
            assertEquals(before, snapshot(id));
            assertArrayEquals(funding, properties.fundingOf(property));
        }
        assertEquals(2, rowCount("npc_economy_events"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"amount", "source", "property", "counterparty", "epoch", "benefit", "day",
            "key-case", "key-space", "direction", "null-property", "null-counterparty", "null-epoch", "null-benefit"})
    void conflictingPayloadIsAnError(String field) throws SQLException {
        EconomyEvent original = request("conflict", EventSource.RENT);
        long id = events.insertPending(original);
        if (field.equals("direction")) {
            updateEvent(id, "direction", "TRANSFER_FROM_PUBLIC");
        }
        EconomyEvent conflicting = EconomyEvent.pending(
                field.equals("key-case") ? "CONFLICT" : field.equals("key-space") ? "conflict " : "conflict",
                field.equals("amount") ? 501 : 500,
                field.equals("source") ? EventSource.APPRECIATION : EventSource.RENT,
                field.equals("property") ? UUID.randomUUID() : field.equals("null-property") ? null : property,
                field.equals("counterparty") ? UUID.randomUUID() : field.equals("null-counterparty") ? null : owner,
                field.equals("epoch") ? Integer.valueOf(1) : field.equals("null-epoch") ? null : Integer.valueOf(0),
                field.equals("benefit") ? UUID.randomUUID() : field.equals("null-benefit") ? null : owner,
                field.equals("day") ? "2026-09-07" : DAY);
        EventSnapshot before = snapshot(id);

        assertThrows(SQLException.class, () -> events.insertPending(conflicting));
        assertEquals(before, snapshot(id));
        assertEquals(1, rowCount("npc_economy_events"));
        if (field.equals("direction")) {
            updateEvent(id, "direction", "TRANSFER_TO_PUBLIC");
        }
        assertEquals(-1L, events.insertPending(original), "conflict must not overwrite the original payload");
    }

    @Test
    void invalidRequestsNeverBecomeSafeRetries() throws SQLException {
        assertThrows(SQLException.class, () -> events.insertPending(null));
        for (String key : new String[]{null, "", " "}) {
            assertThrows(SQLException.class, () -> events.insertPending(
                    EconomyEvent.pending(key, 500, EventSource.RENT, property, owner, 0, owner, DAY)));
        }
        for (long amount : new long[]{0, -1}) {
            assertThrows(SQLException.class, () -> events.insertPending(
                    EconomyEvent.pending("bad-amount", amount, EventSource.RENT, property, owner, 0, owner, DAY)));
        }
        assertThrows(SQLException.class, () -> events.insertPending(
                EconomyEvent.pending("no-source", 500, null, property, owner, 0, owner, DAY)));
        assertEquals(0, rowCount("npc_economy_events"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreign-key", "other-unique", "not-null", "check"})
    void otherConstraintErrorsAreNotIdempotentRetries(String fixture) throws SQLException {
        EconomyEvent original = request("existing", EventSource.RENT);
        long id = events.insertPending(original);
        EventSnapshot before = snapshot(id);
        String body = switch (fixture) {
            case "foreign-key" -> "INSERT INTO npc_property_funding (property_id) "
                    + "VALUES (UNHEX('ffffffffffffffffffffffffffffffff'))";
            case "other-unique" -> "INSERT INTO npc_property_funding (property_id) VALUES (NEW.property_id)";
            case "not-null" -> "SET NEW.economy_day = NULL";
            case "check" -> "UPDATE npc_properties SET capacity=0 WHERE property_id=NEW.property_id";
            default -> throw new AssertionError(fixture);
        };
        int expectedCode = switch (fixture) {
            case "foreign-key" -> 1452;
            case "other-unique" -> 1062;
            case "not-null" -> 1048;
            case "check" -> 4025;
            default -> throw new AssertionError(fixture);
        };
        execute("CREATE TRIGGER ledger_insert_failure BEFORE INSERT ON npc_economy_events FOR EACH ROW " + body);

        // An identical row already exists: a payload lookup alone must not swallow these errors.
        SQLException retryFailure = assertThrows(SQLException.class, () -> events.insertPending(original));
        assertEquals(expectedCode, retryFailure.getErrorCode());
        SQLException newFailure = assertThrows(SQLException.class,
                () -> events.insertPending(request("new-key", EventSource.RENT)));
        assertEquals(expectedCode, newFailure.getErrorCode());
        assertEquals(before, snapshot(id));
        assertEquals(1, rowCount("npc_economy_events"));
        assertArrayEquals(new long[]{SEED, 0, 0}, properties.fundingOf(property));
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, -1, 0, 499, 501, Long.MAX_VALUE})
    void paidAmountMustExactlyMatchRequest(long paidCents) throws SQLException {
        long id = claimed(EventSource.RENT);
        assertFailureLeavesClaimedUnchanged(id, paidCents);
        events.finalizePaid(id, 500);
        assertArrayEquals(new long[]{SEED, 0, 500}, properties.fundingOf(property));
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, -1, 0})
    void nonpositiveStoredRequestFailsClosed(long requestedCents) throws SQLException {
        long id = claimed(EventSource.RENT);
        updateEvent(id, "requested_amount_cents", requestedCents);
        assertThrows(SQLException.class, () -> events.loadDetail(id));
        assertFailureLeavesClaimedUnchanged(id, requestedCents);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown-source", "source-case", "invalid-direction", "opposite-direction"})
    void malformedStoredSourceOrDirectionFailsClosed(String fixture) throws SQLException {
        long id = claimed(EventSource.RENT);
        switch (fixture) {
            case "unknown-source" -> updateEvent(id, "source", "npc-unknown");
            case "source-case" -> updateEvent(id, "source", "NPC-PROPERTY-RENT");
            case "invalid-direction" -> updateEvent(id, "direction", "INVALID");
            case "opposite-direction" -> updateEvent(id, "direction", "TRANSFER_FROM_PUBLIC");
            default -> throw new AssertionError(fixture);
        }
        assertThrows(SQLException.class, () -> events.loadDetail(id));
        assertFailureLeavesClaimedUnchanged(id, 500);
    }

    @Test
    void missingFundingRollsBackPaidEventAndCanBeRetriedAfterRepair() throws SQLException {
        long id = claimed(EventSource.RENT);
        execute("DELETE FROM npc_property_funding");
        assertFailureLeavesClaimedUnchanged(id, 500);
        assertEquals(0, rowCount("npc_property_funding"), "finalize must not invent a funding row");

        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO npc_property_funding (property_id, one_time_seed_cents) VALUES (?, ?)")) {
            ps.setBytes(1, Uuids.toBytes(property));
            ps.setLong(2, SEED);
            assertEquals(1, ps.executeUpdate());
        }
        events.finalizePaid(id, 500);
        assertArrayEquals(new long[]{SEED, 0, 500}, properties.fundingOf(property));
    }

    @ParameterizedTest
    @EnumSource(value = EventSource.class, names = {"RENT", "UTILITY"})
    void fundingOverflowRollsBackEventAndCounters(EventSource source) throws SQLException {
        long id = claimed(source);
        String column = source.isPayout() ? "successful_cash_payouts_cents" : "successful_cash_sinks_cents";
        execute("UPDATE npc_property_funding SET " + column + "=" + (Long.MAX_VALUE - 499));
        SQLException failure = assertFailureLeavesClaimedUnchanged(id, 500);
        assertEquals(1690, failure.getErrorCode(), "BIGINT arithmetic overflow must surface");

        execute("UPDATE npc_property_funding SET " + column + "=0");
        events.finalizePaid(id, 500);
        assertArrayEquals(new long[]{SEED, source.isPayout() ? 0 : 500, source.isPayout() ? 500 : 0},
                properties.fundingOf(property));
    }

    @Test
    void fundingSqlErrorAfterEventUpdateRollsBackEntireTransaction() throws SQLException {
        long id = claimed(EventSource.RENT);
        execute("""
                CREATE TRIGGER ledger_funding_failure BEFORE UPDATE ON npc_property_funding FOR EACH ROW
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ledger funding fixture'
                """);
        assertEquals("45000", assertFailureLeavesClaimedUnchanged(id, 500).getSQLState());

        execute("DROP TRIGGER ledger_funding_failure");
        events.finalizePaid(id, 500);
        assertArrayEquals(new long[]{SEED, 0, 500}, properties.fundingOf(property));
    }

    @Test
    void eventSqlErrorDoesNotBookFunding() throws SQLException {
        long id = claimed(EventSource.RENT);
        execute("""
                CREATE TRIGGER ledger_event_failure BEFORE UPDATE ON npc_economy_events FOR EACH ROW
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='ledger event fixture'
                """);
        assertEquals("45000", assertFailureLeavesClaimedUnchanged(id, 500).getSQLState());
    }

    @Test
    void nullablePropertyStillAllowsLedgerOnlyFinalization() throws SQLException {
        long id = events.insertPending(EconomyEvent.pending("no-property", 500, EventSource.RENT,
                null, owner, null, null, DAY));
        assertTrue(events.claim(id));
        assertEquals(new EconomyEventRepository.ClaimedDetail(id, EventSource.RENT, owner, 500),
                events.loadDetail(id).orElseThrow());
        events.finalizePaid(id, 500);
        events.finalizePaid(id, 500);
        assertEquals("PAID", snapshot(id).state());
        assertEquals(500, events.sumPaidToPublicForDay(DAY));
        assertArrayEquals(new long[]{SEED, 0, 0}, properties.fundingOf(property));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "PAID", "FAILED", "AMBIGUOUS", "CANCELLED"})
    void detailAndFinalizationIgnoreNonClaimedEvents(String state) throws SQLException {
        long id = events.insertPending(request("state", EventSource.RENT));
        putInState(id, state);
        EventSnapshot before = snapshot(id);
        long[] funding = properties.fundingOf(property);
        assertTrue(events.loadDetail(id).isEmpty());
        assertTrue(events.loadDetail(Long.MAX_VALUE).isEmpty());

        events.finalizePaid(id, 500);
        events.finalizePaid(Long.MAX_VALUE, 500);
        assertEquals(before, snapshot(id));
        assertArrayEquals(funding, properties.fundingOf(property));
    }

    @Test
    void pendingBatchIsOrderedFilteredAndHardBounded() throws SQLException {
        List<Long> pending = new ArrayList<>();
        for (int i = 0; i < 107; i++) {
            pending.add(events.insertPending(request("batch-" + i, EventSource.RENT)));
        }
        String[] excluded = {"CLAIMED", "PAID", "FAILED", "AMBIGUOUS", "CANCELLED"};
        // Set fixture states directly so startup recovery does not affect the other fixtures.
        for (int i = 0; i < excluded.length; i++) {
            updateEvent(pending.get(i), "state", excluded[i]);
        }
        List<Long> expected = pending.subList(excluded.length, pending.size());
        for (int max : new int[]{Integer.MIN_VALUE, -1, 0}) {
            assertTrue(events.selectPendingIds(max).isEmpty());
        }
        assertEquals(expected.subList(0, 1), events.selectPendingIds(1));
        assertEquals(expected.subList(0, 20), events.selectPendingIds(20));
        assertEquals(expected.subList(0, 100), events.selectPendingIds(100));
        assertEquals(expected.subList(0, 100), events.selectPendingIds(101));
        assertEquals(expected.subList(0, 100), events.selectPendingIds(Integer.MAX_VALUE));
        assertEquals(107, rowCount("npc_economy_events"));
        assertEquals("PENDING", snapshot(expected.getFirst()).state(), "selection does not claim");
    }

    @Test
    void concurrentFinalizersLockBeforeReadingAndBookOnlyOnce() throws Exception {
        long id = claimed(EventSource.RENT);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> first;
            Future<?> second;
            try (Connection blocker = database.getConnection()) {
                blocker.setAutoCommit(false);
                lockEvent(blocker, id);
                try {
                    first = workers.submit(() -> {
                        events.finalizePaid(id, 500);
                        return null;
                    });
                    second = workers.submit(() -> {
                        events.finalizePaid(id, 500);
                        return null;
                    });
                    List<String> waiting = awaitLedgerLockWaiters(2);
                    for (String query : waiting) {
                        String sql = query.stripLeading().toLowerCase(Locale.ROOT);
                        assertTrue(sql.startsWith("select") && sql.contains("for update"),
                                "finalizers must block on their initial locking read: " + query);
                    }
                    assertFalse(first.isDone());
                    assertFalse(second.isDone());
                } finally {
                    blocker.rollback();
                }
            }
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
            assertEquals("PAID", snapshot(id).state());
            assertEquals(Long.valueOf(500), snapshot(id).paidCents());
            assertArrayEquals(new long[]{SEED, 0, 500}, properties.fundingOf(property));
            assertEquals(500, events.sumPaidToPublicForDay(DAY));
            events.finalizePaid(id, 500);
            assertArrayEquals(new long[]{SEED, 0, 500}, properties.fundingOf(property));
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS), "finalizer workers terminated");
        }
    }

    @Test
    void waitingFinalizerRechecksClaimedStateAfterLockIsReleased() throws Exception {
        long id = claimed(EventSource.RENT);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> result;
            try (Connection blocker = database.getConnection()) {
                blocker.setAutoCommit(false);
                try (PreparedStatement ps = blocker.prepareStatement(
                        "UPDATE npc_economy_events SET state='FAILED', finalized_at=CURRENT_TIMESTAMP(3) WHERE event_id=?")) {
                    ps.setLong(1, id);
                    assertEquals(1, ps.executeUpdate());
                }
                try {
                    result = worker.submit(() -> {
                        events.finalizePaid(id, 500);
                        return null;
                    });
                    awaitLedgerLockWaiters(1);
                    blocker.commit();
                } finally {
                    blocker.rollback();
                }
            }
            result.get(15, TimeUnit.SECONDS);
            assertEquals("FAILED", snapshot(id).state());
            assertNull(snapshot(id).paidCents());
            assertArrayEquals(new long[]{SEED, 0, 0}, properties.fundingOf(property));
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(15, TimeUnit.SECONDS), "finalizer worker terminated");
        }
    }

    private EconomyEvent request(String key, EventSource source) {
        return EconomyEvent.pending(key, 500, source, property, owner, 0, owner, DAY);
    }

    private long claimed(EventSource source) throws SQLException {
        long id = events.insertPending(request("event", source));
        assertTrue(events.claim(id));
        return id;
    }

    private void putInState(long id, String state) throws SQLException {
        if (state.equals("PENDING")) {
            return;
        }
        assertTrue(events.claim(id));
        switch (state) {
            case "CLAIMED" -> { }
            case "PAID" -> events.finalizePaid(id, 500);
            case "FAILED" -> events.finalizeFailed(id);
            case "AMBIGUOUS" -> assertEquals(1, events.markClaimedAsAmbiguousOnStartup());
            case "CANCELLED" -> updateEvent(id, "state", "CANCELLED");
            default -> throw new AssertionError(state);
        }
    }

    private SQLException assertFailureLeavesClaimedUnchanged(long id, long amount) throws SQLException {
        EventSnapshot before = snapshot(id);
        assertEquals("CLAIMED", before.state());
        long[] funding = properties.fundingOf(property);
        SQLException failure = assertThrows(SQLException.class, () -> events.finalizePaid(id, amount));
        assertEquals(before, snapshot(id), "rollback must preserve all settlement fields");
        assertArrayEquals(funding, properties.fundingOf(property), "rollback must preserve both funding totals");
        try (Connection c = database.getConnection()) {
            assertTrue(c.getAutoCommit(), "pooled connection must be reusable after rollback");
        }
        return failure;
    }

    private record EventSnapshot(String state, Long paidCents, Timestamp claimedAt, Timestamp finalizedAt) { }

    private EventSnapshot snapshot(long id) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT state, paid_amount_cents, claimed_at, finalized_at FROM npc_economy_events WHERE event_id=?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "event exists");
                long paid = rs.getLong("paid_amount_cents");
                Long paidCents = rs.wasNull() ? null : Long.valueOf(paid);
                return new EventSnapshot(rs.getString("state"), paidCents,
                        rs.getTimestamp("claimed_at"), rs.getTimestamp("finalized_at"));
            }
        }
    }

    private void updateEvent(long id, String column, Object value) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE npc_economy_events SET " + column + "=? WHERE event_id=?")) {
            ps.setObject(1, value);
            ps.setLong(2, id);
            assertEquals(1, ps.executeUpdate());
        }
    }

    private static void lockEvent(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT event_id FROM npc_economy_events WHERE event_id=? FOR UPDATE")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
            }
        }
    }

    private static List<String> awaitLedgerLockWaiters(int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        List<String> queries = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            queries.clear();
            try (Connection c = database.getConnection(); Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("""
                         SELECT ID, INFO
                         FROM information_schema.PROCESSLIST
                         WHERE ID <> CONNECTION_ID() AND INFO LIKE '%npc_economy_events%'
                         """)) {
                while (rs.next()) {
                    queries.add(rs.getString("INFO"));
                }
            }
            if (queries.size() == expected) {
                return queries;
            }
            // Observe the blocked statement while our independent connection owns the row lock.
            // INNODB_TRX's cached snapshot can omit a waiter during repeated polling.
            Thread.sleep(50);
        }
        throw new AssertionError("expected " + expected + " blocked ledger transactions, saw " + queries);
    }

    private static long rowCount(String table) throws SQLException {
        try (Connection c = database.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection c = database.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }
}

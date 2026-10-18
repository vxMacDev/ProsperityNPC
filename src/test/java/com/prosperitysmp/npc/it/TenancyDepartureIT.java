package com.prosperitysmp.npc.it;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.economy.PurchaseRepository;
import com.prosperitysmp.npc.economy.PurchaseRepository.Receipt;
import com.prosperitysmp.npc.economy.PurchaseRepository.State;
import com.prosperitysmp.npc.economy.TransferJournal;
import com.prosperitysmp.npc.economy.TransferJournal.Handle;
import com.prosperitysmp.npc.economy.TransferProof.Observation;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyRepository;
import com.prosperitysmp.npc.property.Tenant;
import com.prosperitysmp.npc.property.TenantRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Isolated MariaDB lifecycle evidence; fixture journal observations never call Vault or authorize runtime money. */
@Timeout(45)
class TenancyDepartureIT {
    private static final String V15 = "/db/migration/V15__tenancy_departures.sql";
    private static final Instant AT = Instant.parse("2026-09-07T09:00:00.123Z");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 6);
    private static final UUID WORLD = new UUID(0, 2500);
    private static final UUID OWNER = new UUID(0, 2501);
    private static final UUID BUYER = new UUID(0, 2502);
    private static final UUID RESERVE = new UUID(0, 2503);
    private static DB mariaDb;
    private static Database database;
    private static String schema;
    private static List<String> tables;
    private TenantRepository tenants;
    private UUID source;
    private UUID target;
    private UUID tenant;

    @BeforeAll
    static void bootDatabase() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        try {
            mariaDb.start();
            schema = "npc_departure_" + UUID.randomUUID().toString().replace("-", "");
            mariaDb.createDB(schema);
            database = new Database();
            database.connect(new DatabaseSettings("localhost", cfg.getPort(), schema, "root", "", 6, 10_000L));
            Field migrations = MigrationRunner.class.getDeclaredField("MIGRATIONS");
            migrations.setAccessible(true);
            boolean registered = false;
            for (Object migration : (List<?>) migrations.get(null)) {
                Method version = migration.getClass().getDeclaredMethod("version");
                Method resource = migration.getClass().getDeclaredMethod("resource");
                version.setAccessible(true);
                resource.setAccessible(true);
                int number = (Integer) version.invoke(migration);
                if (number > 15 && number != 45) continue;
                String path = "/" + resource.invoke(migration);
                applyResource(path);
                if (number == 15) {
                    assertEquals(V15, path);
                    registered = true;
                }
            }
            assertTrue(registered, "V15 must be registered, not merely present on disk");
            // V40: the refusal columns the transfer and commerce readers expect, on the tables this schema has.
            try (var v40 = database.getConnection()) { com.prosperitysmp.npc.db.V40Fixture.apply(v40); }
            tables = rows("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() "
                    + "AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME").stream().map(row -> row.get("TABLE_NAME")).toList();
            assertTrue(tables.contains("npc_tenancy_departures"));
        } catch (Exception | AssertionError failure) {
            try { stopDatabase(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        try { if (database != null) database.close(); }
        finally {
            if (mariaDb != null) {
                DB stopping = mariaDb;
                mariaDb = null;
                stopping.stop();
            }
        }
    }

    @BeforeEach
    void freshTenancy() throws SQLException {
        dropFaultTrigger();
        // Only this disposable schema, on this connection; restore checks before returning it to the pool.
        try (Connection c = database.getConnection()) {
            execute(c, "SET SESSION foreign_key_checks=0");
            try { for (String table : tables) execute(c, "DELETE FROM " + table); }
            finally { execute(c, "SET SESSION foreign_key_checks=1"); }
        }
        tenants = repository(AT);
        source = enroll("departure-source");
        target = enroll("departure-target");
        tenant = insertTenant(source, 0);
    }

    @AfterEach
    void dropFaultTrigger() throws SQLException {
        if (database != null && database.isConnected()) execute("DROP TRIGGER IF EXISTS tenancy_departure_fault");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fullSnapshotIncludesNullableOwnerAndRetainsEveryOtherTenantField(boolean ownerless) throws SQLException {
        if (ownerless) execute("UPDATE npc_properties SET owner_uuid=NULL WHERE property_id=?", source);
        UUID neighbor = insertTenant(source, 1);
        Map<String, String> before = tenantRow(tenant);
        Map<String, String> neighborBefore = tenantRow(neighbor);
        var unrelated = dataExcept("npc_tenants", "npc_tenancy_departures");
        assertTrue(tenants.vacate(tenant));
        assertHousingChange(before, null, null, 8);
        assertEquals(neighborBefore, tenantRow(neighbor));
        assertDeparture(tenant, 7, source, ownerless ? null : OWNER, 0, 0, AT, "DEPARTURE", null);
        assertEquals(1, count("npc_tenancy_departures"));
        assertEquals(1, tenants.countByProperty(source));
        assertEquals(unrelated, dataExcept("npc_tenants", "npc_tenancy_departures"));
        assertNoFabricatedAdmission();
    }

    @Test
    void repeatedVacateWithLaterClockIsAnExactNoOpIncludingTimestampAndHash() throws SQLException {
        assertTrue(tenants.vacate(tenant));
        var before = data();
        assertFalse(repository(AT.plusSeconds(86_400)).vacate(tenant));
        assertFalse(new TenantRepository(database).vacate(tenant));
        assertEquals(before, data());
        assertDeparture(tenant, 7, source, OWNER, 0, 0, AT, "DEPARTURE", null);
    }

    @Test
    void missingOrAlreadyVacantLegacyTenantDoesNotFabricateDeparture() throws SQLException {
        execute("UPDATE npc_tenants SET property_id=NULL,slot=NULL WHERE tenant_id=?", tenant);
        var before = data();
        assertFalse(tenants.vacate(UUID.randomUUID()));
        assertFalse(tenants.vacate(tenant));
        assertTrue(tenants.departure(tenant, 7).isEmpty());
        assertEquals(before, data());
        assertNoFabricatedAdmission();
    }

    @Test
    @SuppressWarnings("deprecation")
    void legacyDeleteAlsoRecordsDepartureWithoutDeletingIdentity() throws SQLException {
        Map<String, String> before = tenantRow(tenant);
        tenants.delete(tenant);
        assertHousingChange(before, null, null, 8);
        assertDeparture(tenant, 7, source, OWNER, 0, 0, AT, "DEPARTURE", null);
        var after = data();
        repository(AT.plusSeconds(60)).delete(tenant);
        assertEquals(after, data());
    }

    @Test
    void ownershipChangesBeforeDepartureAreCapturedWithoutInventingAdmissionOwnership() throws SQLException {
        new PropertyRepository(database).applyOwnerChange(source, OWNER, BUYER, 1, "departure-fixture");
        assertTrue(tenants.vacate(tenant));
        assertDeparture(tenant, 7, source, BUYER, 1, 0, AT, "DEPARTURE", null);
        execute("UPDATE npc_properties SET owner_uuid=?,ownership_epoch=2 WHERE property_id=?", OWNER, source);
        assertDeparture(tenant, 7, source, BUYER, 1, 0, AT, "DEPARTURE", null);
        assertNoFabricatedAdmission();
    }

    @Test
    void returnAndLaterDepartureCreateDistinctRevisionsWithoutRestoringOldState() throws SQLException {
        assertTrue(tenants.vacate(tenant));
        var first = departureRows();
        tenants.rehouse(tenant, 8, target, 1);
        Map<String, String> rehoused = tenantRow(tenant);
        assertEquals(first, departureRows());
        assertTrue(tenants.departure(tenant, 8).isEmpty(), "rehousing is not a departure");
        assertTrue(repository(AT.plusSeconds(3_600)).vacate(tenant));
        assertHousingChange(rehoused, null, null, 10);
        assertDeparture(tenant, 7, source, OWNER, 0, 0, AT, "DEPARTURE", null);
        assertDeparture(tenant, 9, target, OWNER, 0, 1, AT.plusSeconds(3_600), "DEPARTURE", null);
        assertEquals(first.getFirst(), departureRows().getFirst());
        assertEquals(2, count("npc_tenancy_departures"));
        var after = data();
        assertFalse(repository(AT.plusSeconds(7_200)).vacate(tenant));
        assertEquals(after, data());
        assertNoFabricatedAdmission();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void callerOwnedVacateAndFactRollbackWithLaterSqlFault(boolean legacyOverload) throws SQLException {
        var before = data();
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            try {
                execute(c, "UPDATE npc_property_funding SET successful_cash_sinks_cents=321 WHERE property_id=?", source);
                assertTrue(legacyOverload ? TenantRepository.vacate(c, tenant, source)
                        : TenantRepository.vacate(c, tenant, source, fixed(AT), null));
                assertFalse(c.getAutoCommit(), "caller retains transaction ownership");
                var fact = rows(c, "SELECT * FROM npc_tenancy_departures WHERE resident_id=?", tenant);
                assertEquals(1, fact.size(), "fact must exist in the same uncommitted transaction");
                var vacancy = rows(c, "SELECT property_id,slot,row_version FROM npc_tenants WHERE tenant_id=?", tenant).getFirst();
                assertNull(vacancy.get("property_id"));
                assertNull(vacancy.get("slot"));
                assertEquals("8", vacancy.get("row_version"));
                SQLException fault = assertThrows(SQLException.class,
                        () -> execute(c, "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='departure later fault'"));
                assertEquals("45000", fault.getSQLState());
            } finally { c.rollback(); }
        }
        assertEquals(before, data(), "fact, vacancy, revision and earlier caller writes must all roll back");
    }

    @Test
    void wrongExpectedPropertyAndAutocommitCannotWriteDeparture() throws SQLException {
        var before = data();
        try (Connection c = database.getConnection()) {
            assertThrows(SQLException.class, () -> TenantRepository.vacate(c, tenant, source, fixed(AT), null));
            c.setAutoCommit(false);
            try {
                assertFalse(TenantRepository.vacate(c, tenant, target, fixed(AT), null));
                c.commit();
            } finally { c.rollback(); }
        }
        assertEquals(before, data());
    }

    @ParameterizedTest
    @ValueSource(strings = {"BEFORE INSERT ON npc_tenancy_departures", "AFTER INSERT ON npc_tenancy_departures",
            "BEFORE UPDATE ON npc_tenants", "AFTER UPDATE ON npc_tenants"})
    void factInsertOrTenantUpdateFailureCannotLeaveEitherPartialWrite(String phase) throws SQLException {
        var before = data();
        installFault(phase);
        SQLException fault = assertThrows(SQLException.class, () -> tenants.vacate(tenant));
        assertEquals("45000", fault.getSQLState(), "must reach the intended fault, not fail on another guard");
        assertEquals(before, data());
        dropFaultTrigger();
        assertTrue(tenants.vacate(tenant));
        assertDeparture(tenant, 7, source, OWNER, 0, 0, AT, "DEPARTURE", null);
        assertEquals(1, count("npc_tenancy_departures"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void existingFactAtCurrentOccupiedRevisionFailsClosedAndIsNeverOverwritten(boolean poisoned) throws SQLException {
        assertTrue(tenants.vacate(tenant));
        // Restore occupancy only to simulate a corrupt/replayed revision, never as an application operation.
        execute("UPDATE npc_tenants SET property_id=?,slot=0,row_version=7 WHERE tenant_id=?", source, tenant);
        if (poisoned) execute("UPDATE npc_tenancy_departures SET payload_sha256=REPEAT('0',64) WHERE resident_id=?", tenant);
        var before = data();
        assertThrows(SQLException.class, () -> repository(AT.plusSeconds(60)).vacate(tenant));
        assertEquals(before, data());
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, Long.MIN_VALUE, Long.MAX_VALUE})
    void corruptOrExhaustedOccupancyRevisionRejectsWithoutPartialWrites(long revision) throws SQLException {
        execute("UPDATE npc_tenants SET row_version=? WHERE tenant_id=?", revision, tenant);
        var before = data();
        assertThrows(SQLException.class, () -> tenants.vacate(tenant));
        assertEquals(before, data());
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, Long.MIN_VALUE, Long.MAX_VALUE})
    void invalidDepartureLookupRevisionRejectsBeforeOpeningDatabase(long revision) throws Exception {
        try (ReadProbe probe = new ReadProbe()) {
            assertThrows(IllegalArgumentException.class, () -> new TenantRepository(probe.routed, fixed(AT)).departure(tenant, revision));
            assertEquals(0, probe.connections);
        }
    }

    @Test
    void maximumUsableRevisionRecordsOneLastDepartureWithoutOverflow() throws SQLException {
        execute("UPDATE npc_tenants SET row_version=? WHERE tenant_id=?", Long.MAX_VALUE - 1, tenant);
        Map<String, String> before = tenantRow(tenant);
        assertTrue(tenants.vacate(tenant));
        assertHousingChange(before, null, null, Long.MAX_VALUE);
        assertDeparture(tenant, Long.MAX_VALUE - 1, source, OWNER, 0, 0, AT, "DEPARTURE", null);
        var after = data();
        assertFalse(tenants.vacate(tenant));
        assertEquals(after, data());
    }

    @ParameterizedTest
    @ValueSource(strings = {"property", "resident", "owner", "null-owner", "epoch", "slot", "revision-before",
            "revision-after", "time", "cause", "event", "hash", "invalid-hash", "negative-revision", "invalid-slot"})
    void departureReaderRejectsTamperedSnapshotOrHashWithoutRepairingIt(String field) throws SQLException {
        assertTrue(tenants.vacate(tenant));
        UUID readResident = tenant;
        long readRevision = 7;
        switch (field) {
            case "property" -> corrupt("property_id=?", target);
            case "resident" -> {
                readResident = insertTenant(target, 0);
                corrupt("resident_id=?", readResident);
            }
            case "owner" -> corrupt("owner_at_departure=?", BUYER);
            case "null-owner" -> corrupt("owner_at_departure=NULL");
            case "epoch" -> corrupt("ownership_epoch=1");
            case "slot" -> corrupt("slot=1");
            case "revision-before" -> {
                corrupt("occupancy_revision_before=8,occupancy_revision_after=9");
                readRevision = 8;
            }
            case "revision-after" -> corrupt("occupancy_revision_after=9");
            case "time" -> corrupt("departed_at=?", AT.plusMillis(1));
            case "cause" -> corrupt("cause='PAID_EVICTION'");
            case "event" -> corrupt("purchase_event_id=9223372036854775807");
            case "hash" -> corrupt("payload_sha256=REPEAT('0',64)");
            case "invalid-hash" -> corrupt("payload_sha256=REPEAT('g',64)");
            case "negative-revision" -> corrupt("occupancy_revision_after=-1");
            case "invalid-slot" -> corrupt("slot=4");
            default -> throw new AssertionError(field);
        }
        var before = data();
        UUID id = readResident;
        long revision = readRevision;
        assertThrows(SQLException.class, () -> tenants.departure(id, revision));
        assertEquals(before, data(), "reader may neither repair nor overwrite poisoned evidence");
    }

    @Test
    void boundedReadUsesExactCompositeKeyAndDoesNotScanUnrelatedPoisonedHistory() throws Exception {
        assertTrue(tenants.vacate(tenant));
        UUID other = insertTenant(target, 0);
        assertTrue(tenants.vacate(other));
        execute("UPDATE npc_tenancy_departures SET payload_sha256=REPEAT('0',64) WHERE resident_id=?", other);
        var before = data();
        try (ReadProbe probe = new ReadProbe()) {
            var found = new TenantRepository(probe.routed, fixed(AT)).departure(tenant, 7).orElseThrow();
            assertEquals(tenant, found.residentId());
            assertEquals(7, found.occupancyRevisionBefore());
            probe.assertPointRead(tenant, 7);
        }
        assertTrue(tenants.departure(tenant, 6).isEmpty());
        assertTrue(tenants.departure(tenant, 8).isEmpty());
        assertTrue(tenants.departure(UUID.randomUUID(), 7).isEmpty());
        assertEquals(before, data());
        execute("DELETE FROM npc_tenancy_departures WHERE resident_id=?", tenant);
        var missing = data();
        assertTrue(tenants.departure(tenant, 7).isEmpty(), "missing history is absent, never reconstructed from vacancy");
        assertFalse(tenants.vacate(tenant));
        assertEquals(missing, data());
    }

    @ParameterizedTest
    @ValueSource(strings = {"npc_properties", "npc_tenants"})
    void clockIsCapturedAfterPropertyAndTenantLocks(String lockedTable) throws Exception {
        AtomicReference<Instant> now = new AtomicReference<>(AT);
        AtomicInteger samples = new AtomicInteger();
        Clock clock = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now.get(), zone); }
            @Override public Instant instant() { samples.incrementAndGet(); return now.get(); }
        };
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> result;
            try (Connection blocker = database.getConnection()) {
                blocker.setAutoCommit(false);
                try {
                    lock(blocker, lockedTable, lockedTable.equals("npc_properties") ? source : tenant);
                    result = worker.submit(() -> new TenantRepository(database, clock).vacate(tenant));
                    awaitLockingReaders(lockedTable, 1);
                    assertFalse(result.isDone());
                    assertEquals(0, samples.get(), "waiting time must not become the departure timestamp");
                    now.set(AT.plusSeconds(3_600));
                    if (lockedTable.equals("npc_properties"))
                        execute(blocker, "UPDATE npc_properties SET owner_uuid=?,ownership_epoch=1 WHERE property_id=?", BUYER, source);
                    blocker.commit();
                } finally { blocker.rollback(); }
            }
            assertTrue(result.get(15, TimeUnit.SECONDS));
            assertTrue(samples.get() > 0, "departure must use the injected clock");
            boolean sale = lockedTable.equals("npc_properties");
            assertDeparture(tenant, 7, source, sale ? BUYER : OWNER, sale ? 1 : 0, 0,
                    AT.plusSeconds(3_600), "DEPARTURE", null);
        } finally { stopWorkers(worker); }
    }

    @Test
    void twoConcurrentVacatesSerializeToOneFactAndOneRevisionBump() throws Exception {
        Map<String, String> before = tenantRow(tenant);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> left;
            Future<Boolean> right;
            try (Connection blocker = database.getConnection()) {
                blocker.setAutoCommit(false);
                try {
                    lock(blocker, "npc_properties", source);
                    left = workers.submit(() -> repository(AT).vacate(tenant));
                    right = workers.submit(() -> repository(AT).vacate(tenant));
                    awaitLockingReaders("npc_properties", 2);
                    assertFalse(left.isDone());
                    assertFalse(right.isDone());
                } finally { blocker.rollback(); }
            }
            assertNotEquals(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS), "one change and one no-op");
            assertHousingChange(before, null, null, 8);
            assertEquals(1, count("npc_tenancy_departures"));
            assertDeparture(tenant, 7, source, OWNER, 0, 0, AT, "DEPARTURE", null);
        } finally { stopWorkers(workers); }
    }

    @Test
    void suppliedExpectedPropertyBypassesOldMvccDiscoveryAndUsesLockedCurrentTenancy() throws SQLException {
        try (Connection c = database.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setAutoCommit(false);
            try {
                assertEquals(hex(source), rows(c, "SELECT property_id FROM npc_tenants WHERE tenant_id=?", tenant)
                        .getFirst().get("property_id"));
                tenants.moveTenant(tenant, target, 1, "9999-12-31");
                assertEquals(hex(source), rows(c, "SELECT property_id FROM npc_tenants WHERE tenant_id=?", tenant)
                        .getFirst().get("property_id"), "fixture must really retain its old consistent read");
                assertTrue(TenantRepository.vacate(c, tenant, target, fixed(AT), null));
                c.commit();
            } finally { c.rollback(); }
        }
        assertDeparture(tenant, 8, target, OWNER, 0, 1, AT, "DEPARTURE", null);
        assertTrue(tenants.departure(tenant, 7).isEmpty(), "legacy move cannot fabricate departure evidence");
        assertNull(tenantRow(tenant).get("property_id"));
        assertEquals("9", tenantRow(tenant).get("row_version"));
        assertEquals(1, count("npc_tenancy_departures"));
    }

    @Test
    void concurrentMoveToDifferentPropertyRejectsOldDiscoveryWithoutVacatingNewHome() throws Exception {
        Map<String, String> before = tenantRow(tenant);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> result;
            try (Connection blocker = database.getConnection()) {
                blocker.setAutoCommit(false);
                try {
                    lock(blocker, "npc_properties", source);
                    result = worker.submit(() -> tenants.vacate(tenant));
                    awaitLockingReaders("npc_properties", 1);
                    assertFalse(result.isDone());
                    execute(blocker, "UPDATE npc_tenants SET property_id=?,slot=1,row_version=8 WHERE tenant_id=?", target, tenant);
                    blocker.commit();
                } finally { blocker.rollback(); }
            }
            var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(15, TimeUnit.SECONDS));
            SQLException rejected = assertInstanceOf(SQLException.class, failure.getCause());
            assertNotEquals(1205, rejected.getErrorCode(), "a lock timeout is not a tenancy-change rejection");
            assertNotEquals(1213, rejected.getErrorCode(), "a deadlock is not a tenancy-change rejection");
            assertHousingChange(before, target, 1, 8);
            assertEquals(0, count("npc_tenancy_departures"));
        } finally { stopWorkers(worker); }
    }

    @Test
    void v15RepeatPreservesLegacyRowsAndRecordedFactsWithoutBackfillingAdmissions() throws Exception {
        var legacy = data();
        applyResource(V15);
        applyResource(V15);
        assertEquals(legacy, data());
        assertEquals(0, count("npc_tenancy_departures"));
        assertTrue(tenants.vacate(tenant));
        var recorded = data();
        applyResource(V15);
        applyResource(V15);
        assertEquals(recorded, data());
        assertDeparture(tenant, 7, source, OWNER, 0, 0, AT, "DEPARTURE", null);
        assertNoFabricatedAdmission();
        assertEquals(List.of("resident_id", "occupancy_revision_before"), rows(
                "SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() "
                        + "AND TABLE_NAME='npc_tenancy_departures' AND INDEX_NAME='PRIMARY' ORDER BY SEQ_IN_INDEX")
                .stream().map(row -> row.get("COLUMN_NAME")).toList());
        assertEquals("3", scalar("SELECT DATETIME_PRECISION FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() "
                + "AND TABLE_NAME='npc_tenancy_departures' AND COLUMN_NAME='departed_at'"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"tenant", "property"})
    void departedTenantAndPropertyCannotBeDeletedThroughForeignKeyCascade(String parent) throws SQLException {
        assertTrue(tenants.vacate(tenant));
        var before = data();
        SQLException failure = assertThrows(SQLException.class, () -> {
            if (parent.equals("tenant")) execute("DELETE FROM npc_tenants WHERE tenant_id=?", tenant);
            else execute("DELETE FROM npc_properties WHERE property_id=?", source);
        });
        assertEquals("23000", failure.getSQLState());
        assertEquals(before, data());
        assertRestrictForeignKey(parent.equals("tenant") ? "resident_id" : "property_id",
                parent.equals("tenant") ? "npc_tenants" : "npc_properties");
    }

    @ParameterizedTest
    @ValueSource(strings = {"occupancy_revision_before=-1", "occupancy_revision_before=9223372036854775807",
            "occupancy_revision_after=7", "occupancy_revision_after=9", "ownership_epoch=-1", "slot=-1", "slot=4",
            "cause='UNKNOWN'", "cause='PAID_EVICTION'", "payload_sha256=REPEAT('g',64)"})
    void v15ConstraintsRejectInvalidFactShapes(String assignment) throws SQLException {
        assertTrue(tenants.vacate(tenant));
        var before = data();
        assertThrows(SQLException.class, () -> execute("UPDATE npc_tenancy_departures SET " + assignment + " WHERE resident_id=?", tenant));
        assertEquals(before, data());
    }

    @Test
    void realPaidEvictionRequiresBothJournalLegsAndReplayCannotTouchLaterTenancy() throws SQLException {
        PurchaseRepository purchases = purchases(AT);
        Receipt receipt = enqueueEviction(purchases);
        TransferJournal journal = new TransferJournal(database, RESERVE);
        Map<String, String> before = tenantRow(tenant);
        assertEquals(State.WAITING, purchases.complete(receipt.eventId()).orElseThrow().state());
        Handle handle = new Handle(UUID.randomUUID(), receipt.eventId(), UUID.randomUUID(), "a".repeat(64));
        assertTrue(journal.beginDebit(handle).newlyStarted());
        assertEquals(TransferJournal.State.CREDIT_READY, journal.recordDebit(handle, debit(receipt.quotedCents())).state());
        assertTrue(journal.beginCredit(handle).newlyStarted());
        assertEquals(State.WAITING, purchases.complete(receipt.eventId()).orElseThrow().state());
        assertEquals(before, tenantRow(tenant));
        assertEquals(0, count("npc_tenancy_departures"));
        assertEquals(TransferJournal.State.PAID, journal.recordCredit(handle, credit(receipt.quotedCents())).state());
        pinPaymentTime(receipt.eventId());
        var paidLedger = ledgerRows();
        Receipt applied = purchases.complete(receipt.eventId()).orElseThrow();
        assertEquals(State.APPLIED, applied.state());
        assertHousingChange(before, null, null, 8);
        assertDeparture(tenant, 7, source, OWNER, 0, 0, AT, "PAID_EVICTION", receipt.eventId());
        assertEquals(paidLedger, ledgerRows(), "completing benefits must not charge again");
        tenants.rehouse(tenant, 8, target, 1);
        var rehoused = data();
        PurchaseRepository later = purchases(AT.plusSeconds(60));
        assertEquals(applied, later.complete(receipt.eventId()).orElseThrow());
        assertEquals(applied, later.enqueue(receipt.request()));
        assertEquals(rehoused, data(), "old completed eviction must not evict a new tenancy or rewrite old history");
        assertTrue(repository(AT.plusSeconds(120)).vacate(tenant));
        assertDeparture(tenant, 9, target, OWNER, 0, 1, AT.plusSeconds(120), "DEPARTURE", null);
        assertDeparture(tenant, 7, source, OWNER, 0, 0, AT, "PAID_EVICTION", receipt.eventId());
        assertEquals(2, count("npc_tenancy_departures"));
        assertEquals(paidLedger, ledgerRows());
        assertNoFabricatedAdmission();
    }

    @Test
    void completionWithOldSnapshotUsesCurrentPaidDepartureBinding() throws Exception {
        Receipt receipt = enqueueEviction(purchases(AT));
        Map<String, String> before = tenantRow(tenant);
        try (ReadProbe probe = new ReadProbe(receipt.eventId())) {
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                PurchaseRepository gated = new PurchaseRepository(probe.routed, RESERVE, fixed(AT),
                        ZoneId.of("America/Chicago"), 4);
                Future<Receipt> completion = worker.submit(() -> gated.complete(receipt.eventId()).orElseThrow());
                probe.awaitInitialRead();
                assertFalse(completion.isDone(), "completion must pause before taking current property/event locks");
                pay(receipt);
                assertEquals("PAID", scalar("SELECT state FROM npc_economy_events WHERE event_id=?", receipt.eventId()));
                assertEquals(before, tenantRow(tenant));
                assertEquals(0, count("npc_tenancy_departures"));
                var paidLedger = ledgerRows();
                probe.release();
                assertEquals(State.APPLIED, completion.get(15, TimeUnit.SECONDS).state());
                assertHousingChange(before, null, null, 8);
                assertDeparture(tenant, 7, source, OWNER, 0, 0, AT, "PAID_EVICTION", receipt.eventId());
                assertEquals(1, count("npc_tenancy_departures"));
                assertEquals(paidLedger, ledgerRows(), "current binding read must not repeat or rewrite payment");
            } finally {
                probe.release();
                stopWorkers(worker);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"BEFORE INSERT ON npc_tenancy_departures", "AFTER UPDATE ON npc_tenants",
            "BEFORE UPDATE ON npc_purchase_intents"})
    void paidEvictionFaultRollsBackFactVacancyAndAppliedButRetainsConfirmedPayment(String phase) throws SQLException {
        PurchaseRepository purchases = purchases(AT);
        Receipt receipt = enqueueEviction(purchases);
        pay(receipt);
        var paid = data();
        installFault(phase);
        SQLException fault = assertThrows(SQLException.class, () -> purchases.complete(receipt.eventId()));
        assertEquals("45000", fault.getSQLState());
        assertEquals(paid, data(), "confirmed payment survives, but all benefit writes roll back together");
        dropFaultTrigger();
        assertEquals(State.APPLIED, purchases.complete(receipt.eventId()).orElseThrow().state());
        assertDeparture(tenant, 7, source, OWNER, 0, 0, AT, "PAID_EVICTION", receipt.eventId());
        assertEquals(1, count("npc_economy_events"));
        assertEquals(1, count("npc_tenancy_departures"));
    }

    @Test
    void paidEventReferenceIsUniqueAndRestrictsDeletion() throws SQLException {
        PurchaseRepository purchases = purchases(AT);
        Receipt receipt = enqueueEviction(purchases);
        pay(receipt);
        assertEquals(State.APPLIED, purchases.complete(receipt.eventId()).orElseThrow().state());
        UUID other = insertTenant(target, 0);
        assertTrue(tenants.vacate(other));
        var before = data();
        SQLException duplicate = assertThrows(SQLException.class, () -> execute("UPDATE npc_tenancy_departures "
                + "SET cause='PAID_EVICTION',purchase_event_id=? WHERE resident_id=?", receipt.eventId(), other));
        assertEquals(1062, duplicate.getErrorCode(), "one paid event must not back two departures");
        SQLException retained = assertThrows(SQLException.class,
                () -> execute("DELETE FROM npc_economy_events WHERE event_id=?", receipt.eventId()));
        assertEquals("23000", retained.getSQLState());
        assertEquals(before, data());
        assertRestrictForeignKey("purchase_event_id", "npc_economy_events");
    }

    private TenantRepository repository(Instant at) { return new TenantRepository(database, fixed(at)); }

    private PurchaseRepository purchases(Instant at) {
        return new PurchaseRepository(database, RESERVE, fixed(at), ZoneId.of("America/Chicago"), 4);
    }

    private Receipt enqueueEviction(PurchaseRepository purchases) throws SQLException {
        return purchases.enqueue(purchases.quoteEviction(UUID.randomUUID(), source, OWNER, 0, tenant, DAY));
    }

    private UUID enroll(String region) throws SQLException {
        UUID id = UUID.randomUUID();
        new PropertyRepository(database).insertEnrollment(new Property(id, WORLD, region, "downtown", "room",
                2, 1_000, 1_200, OWNER, 0, 80, true), 5_000);
        return id;
    }

    private UUID insertTenant(UUID property, int slot) throws SQLException {
        UUID id = UUID.randomUUID();
        tenants.insert(new Tenant(id, "Ava Departure", property, slot, "PREMIUM", "ARTISAN", 73));
        execute("UPDATE npc_tenants SET mood='HAPPY',schedule_template='NIGHT_SHIFT',state='WORKING',"
                + "move_cooldown_until_day='9999-12-31',row_version=7 WHERE tenant_id=?", id);
        return id;
    }

    private void assertDeparture(UUID resident, long revision, UUID property, UUID owner, int epoch, int slot,
                                 Instant at, String cause, Long event) throws SQLException {
        var departure = tenants.departure(resident, revision).orElseThrow();
        assertEquals(resident, departure.residentId());
        assertEquals(property, departure.propertyId());
        assertEquals(owner, departure.ownerAtDeparture());
        assertEquals(epoch, departure.ownershipEpoch());
        assertEquals(revision, departure.occupancyRevisionBefore());
        assertEquals(revision + 1, departure.occupancyRevisionAfter());
        assertEquals(slot, departure.slot());
        assertEquals(at, departure.departedAt());
        assertEquals(cause, departure.cause().toString());
        assertEquals(event, departure.purchaseEventId());
        assertTrue(departure.fingerprint().matches("[0-9a-f]{64}"));
        assertEquals(departure.fingerprint(), scalar("SELECT payload_sha256 FROM npc_tenancy_departures "
                + "WHERE resident_id=? AND occupancy_revision_before=?", resident, revision));
    }

    private static void assertHousingChange(Map<String, String> before, UUID property, Integer slot, long revision)
            throws SQLException {
        Map<String, String> expected = new LinkedHashMap<>(before);
        expected.put("property_id", property == null ? null : hex(property));
        expected.put("slot", slot == null ? null : slot.toString());
        expected.put("row_version", Long.toString(revision));
        UUID id = Uuids.fromBytes(HexFormat.of().parseHex(before.get("tenant_id")));
        assertEquals(expected, tenantRow(id), "only occupancy and its revision may change");
    }

    private static void assertNoFabricatedAdmission() throws SQLException {
        assertEquals(0, count("npc_housing_moves"));
        assertEquals(0, count("npc_housing_days"));
        assertEquals(0, count("npc_resident_profiles"));
        assertEquals(0, count("npc_resident_memory"));
    }

    private void corrupt(String assignment, Object... values) throws SQLException {
        try (Connection c = database.getConnection()) {
            execute(c, "SET SESSION check_constraint_checks=0");
            execute(c, "SET SESSION foreign_key_checks=0");
            try { execute(c, "UPDATE npc_tenancy_departures SET " + assignment, values); }
            finally {
                execute(c, "SET SESSION check_constraint_checks=1");
                execute(c, "SET SESSION foreign_key_checks=1");
            }
        }
    }

    private static void installFault(String phase) throws SQLException {
        execute("CREATE TRIGGER tenancy_departure_fault " + phase + " FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='departure injected fault'");
    }

    private static void lock(Connection c, String table, UUID id) throws SQLException {
        String key = table.equals("npc_properties") ? "property_id" : "tenant_id";
        assertEquals(1, rows(c, "SELECT " + key + " FROM " + table + " WHERE " + key + "=? FOR UPDATE", id).size());
    }

    private static void awaitLockingReaders(String table, int expected) throws SQLException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        List<Map<String, String>> waiting = List.of();
        while (System.nanoTime() < deadline) {
            waiting = rows("SELECT INFO FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID() AND DB=? "
                    + "AND INFO LIKE ? AND INFO LIKE '%FOR UPDATE%' LIMIT 6", schema, "%" + table + "%");
            if (waiting.size() == expected) {
                for (var row : waiting) {
                    String sql = row.get("INFO").stripLeading().toLowerCase(Locale.ROOT).replaceFirst(
                            "^set\\s+statement\\s+max_statement_time\\s*=\\s*\\d+(?:\\.\\d+)?\\s+for\\s+", "");
                    assertTrue(sql.matches("(?s)select\\s+.*\\s+for\\s+update\\s*"), "locking read: " + sql);
                }
                return;
            }
            Thread.yield();
        }
        fail("Expected " + expected + " locking readers on " + table + ", saw " + waiting);
    }

    private static void stopWorkers(ExecutorService workers) throws InterruptedException {
        workers.shutdownNow();
        assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS), "departure workers terminated");
    }

    private static void assertRestrictForeignKey(String column, String parent) throws SQLException {
        var keys = rows("SELECT k.REFERENCED_TABLE_NAME,r.DELETE_RULE FROM information_schema.KEY_COLUMN_USAGE k "
                + "JOIN information_schema.REFERENTIAL_CONSTRAINTS r ON r.CONSTRAINT_SCHEMA=k.CONSTRAINT_SCHEMA "
                + "AND r.TABLE_NAME=k.TABLE_NAME AND r.CONSTRAINT_NAME=k.CONSTRAINT_NAME "
                + "WHERE k.TABLE_SCHEMA=DATABASE() AND k.TABLE_NAME='npc_tenancy_departures' AND k.COLUMN_NAME=? "
                + "AND k.REFERENCED_TABLE_NAME IS NOT NULL", column);
        assertEquals(1, keys.size());
        assertEquals(parent, keys.getFirst().get("REFERENCED_TABLE_NAME"));
        assertEquals("RESTRICT", keys.getFirst().get("DELETE_RULE"));
    }

    private static void pay(Receipt receipt) throws SQLException {
        TransferJournal journal = new TransferJournal(database, RESERVE);
        Handle handle = new Handle(UUID.randomUUID(), receipt.eventId(), UUID.randomUUID(), "a".repeat(64));
        assertTrue(journal.beginDebit(handle).newlyStarted());
        assertEquals(TransferJournal.State.CREDIT_READY, journal.recordDebit(handle, debit(receipt.quotedCents())).state());
        assertTrue(journal.beginCredit(handle).newlyStarted());
        assertEquals(TransferJournal.State.PAID, journal.recordCredit(handle, credit(receipt.quotedCents())).state());
        pinPaymentTime(receipt.eventId());
    }

    private static Observation debit(long cents) {
        double dollars = cents / 100.0;
        return new Observation(true, true, dollars, 1_000.0, 1_000.0 - dollars, false);
    }

    private static Observation credit(long cents) {
        double dollars = cents / 100.0;
        return new Observation(true, true, dollars, 2_000.0, 2_000.0 + dollars, false);
    }

    private static void pinPaymentTime(long event) throws SQLException {
        // Preserve real journal proof; only align fixture SQL chronology with the injected clock.
        Instant paid = AT.minusSeconds(120);
        execute("UPDATE npc_economy_events SET created_at=?,claimed_at=?,finalized_at=? WHERE event_id=?",
                paid.minusSeconds(5), paid.minusSeconds(4), paid, event);
        execute("UPDATE npc_purchase_intents SET created_at=? WHERE event_id=?", paid.minusSeconds(5), event);
        execute("UPDATE npc_transfer_attempts SET created_at=?,credit_intent_at=?,updated_at=? WHERE event_id=?",
                paid.minusSeconds(4), paid.minusSeconds(2), paid, event);
        execute("UPDATE npc_transfer_observations o JOIN npc_transfer_attempts a ON a.attempt_id=o.attempt_id "
                + "SET o.observed_at=CASE WHEN o.leg='DEBIT' THEN ? ELSE ? END WHERE a.event_id=?",
                paid.minusSeconds(3), paid.minusSeconds(1), event);
    }

    /** Real JDBC forwarding, confined to an independent facade; never replace the shared fixture pool. */
    private static final class ReadProbe implements AutoCloseable {
        private final Database routed = new Database();
        private final List<String> queries = new ArrayList<>();
        private final List<String> parameters = new ArrayList<>();
        private final CountDownLatch initialRead = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);
        private int connections;

        private ReadProbe() throws ReflectiveOperationException { this(null); }

        private ReadProbe(Long completingEvent) throws ReflectiveOperationException {
            AtomicBoolean gate = new AtomicBoolean(completingEvent != null);
            HikariDataSource forwarding = new HikariDataSource() {
                @Override public Connection getConnection() throws SQLException {
                    connections++;
                    Connection actual = database.getConnection();
                    return proxy(Connection.class, (ignored, method, args) -> {
                        if (method.getName().equals("prepareStatement") && args != null && args[0] instanceof String sql) {
                            PreparedStatement statement = (PreparedStatement) invoke(actual, method, args);
                            String normalized = sql.strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
                            return proxy(PreparedStatement.class, (unused, operation, values) -> {
                                if (operation.getName().startsWith("set") && values != null && values.length >= 2)
                                    parameters.add(values[1] instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : String.valueOf(values[1]));
                                if (operation.getName().startsWith("execute")) {
                                    assertTrue(statement.getQueryTimeout() > 0 && statement.getQueryTimeout() <= 5, "bounded JDBC deadline");
                                    queries.add(normalized);
                                }
                                Object result = invoke(statement, operation, values);
                                if (operation.getName().equals("close")
                                        && normalized.equals("SELECT * FROM NPC_PURCHASE_INTENTS WHERE EVENT_ID=?")
                                        && gate.compareAndSet(true, false)) {
                                    // byEvent has consumed and closed its real result; no current locking reads yet.
                                    assertFalse(actual.getAutoCommit());
                                    assertEquals(Connection.TRANSACTION_REPEATABLE_READ, actual.getTransactionIsolation());
                                    assertPendingSnapshot(actual, completingEvent);
                                    initialRead.countDown();
                                    try {
                                        assertTrue(resume.await(15, TimeUnit.SECONDS), "payment phase gate timed out");
                                    } catch (InterruptedException interrupted) {
                                        Thread.currentThread().interrupt();
                                        throw new AssertionError("payment phase gate interrupted", interrupted);
                                    }
                                    assertPendingSnapshot(actual, completingEvent);
                                }
                                return result;
                            });
                        }
                        return invoke(actual, method, args);
                    });
                }
            };
            Field source = Database.class.getDeclaredField("dataSource");
            source.setAccessible(true);
            source.set(routed, forwarding);
        }

        private static void assertPendingSnapshot(Connection c, long event) throws SQLException {
            assertEquals("PENDING", rows(c, "SELECT state FROM npc_economy_events WHERE event_id=?", event)
                    .getFirst().get("state"), "plain reads must retain the initial pre-payment snapshot");
        }

        private void awaitInitialRead() throws InterruptedException {
            assertTrue(initialRead.await(10, TimeUnit.SECONDS), "initial byEvent read did not reach the phase gate");
        }

        private void release() { resume.countDown(); }

        private void assertPointRead(UUID resident, long revision) {
            assertEquals(1, connections);
            assertEquals(1, queries.size(), "one indexed lookup, no history scans or writes");
            String sql = queries.getFirst();
            assertTrue(sql.startsWith("SELECT "));
            assertTrue(sql.contains("NPC_TENANCY_DEPARTURES"));
            assertTrue(sql.matches("(?s).*WHERE .*RESIDENT_ID\\s*=\\s*\\?.*"));
            assertTrue(sql.matches("(?s).*WHERE .*OCCUPANCY_REVISION_BEFORE\\s*=\\s*\\?.*"));
            assertFalse(sql.contains("FOR UPDATE"), "reading historical evidence must not lock current tenancy");
            assertTrue(parameters.containsAll(List.of(hex(resident), Long.toString(revision))));
        }

        @Override public void close() { release(); routed.close(); }
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
    }

    private static Clock fixed(Instant at) { return Clock.fixed(at, ZoneOffset.UTC); }
    private static String hex(UUID id) { return HexFormat.of().formatHex(Uuids.toBytes(id)); }

    private static Map<String, String> tenantRow(UUID id) throws SQLException {
        var result = rows("SELECT * FROM npc_tenants WHERE tenant_id=?", id);
        assertEquals(1, result.size());
        return result.getFirst();
    }

    private static List<Map<String, String>> departureRows() throws SQLException {
        return rows("SELECT * FROM npc_tenancy_departures ORDER BY resident_id,occupancy_revision_before");
    }

    private static Map<String, List<Map<String, String>>> data() throws SQLException { return dataExcept(); }

    private static Map<String, List<Map<String, String>>> dataExcept(String... excluded) throws SQLException {
        Map<String, List<Map<String, String>>> result = new LinkedHashMap<>();
        for (String table : tables) {
            if (!List.of(excluded).contains(table)) {
                List<Map<String, String>> contents = rows("SELECT * FROM " + table);
                contents.sort(Comparator.comparing(Object::toString));
                result.put(table, contents);
            }
        }
        return result;
    }

    private static Map<String, List<Map<String, String>>> ledgerRows() throws SQLException {
        Map<String, List<Map<String, String>>> result = new LinkedHashMap<>();
        for (String table : List.of("npc_economy_events", "npc_transfer_attempts", "npc_transfer_observations", "npc_property_funding")) {
            List<Map<String, String>> contents = rows("SELECT * FROM " + table);
            contents.sort(Comparator.comparing(Object::toString));
            result.put(table, contents);
        }
        return result;
    }

    private static List<Map<String, String>> rows(String sql, Object... values) throws SQLException {
        try (Connection c = database.getConnection()) { return rows(c, sql, values); }
    }

    private static List<Map<String, String>> rows(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(5);
            bind(ps, values);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, String>> result = new ArrayList<>();
                while (rs.next()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                        Object value = rs.getObject(i);
                        row.put(rs.getMetaData().getColumnLabel(i), value instanceof byte[] bytes
                                ? HexFormat.of().formatHex(bytes) : rs.getString(i));
                    }
                    result.add(row);
                }
                return result;
            }
        }
    }

    private static long count(String table) throws SQLException {
        if (!tables.contains(table)) throw new IllegalArgumentException(table);
        return Long.parseLong(scalar("SELECT COUNT(*) FROM " + table));
    }

    private static String scalar(String sql, Object... values) throws SQLException {
        var result = rows(sql, values);
        assertEquals(1, result.size(), sql);
        return result.getFirst().values().iterator().next();
    }

    private static void execute(String sql, Object... values) throws SQLException {
        try (Connection c = database.getConnection()) { execute(c, sql, values); }
    }

    private static void execute(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(5);
            bind(ps, values);
            ps.execute();
        }
    }

    private static void bind(PreparedStatement ps, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) {
            if (values[i] instanceof UUID id) ps.setBytes(i + 1, Uuids.toBytes(id));
            else if (values[i] instanceof Instant at) ps.setObject(i + 1, LocalDateTime.ofInstant(at, ZoneOffset.UTC));
            else ps.setObject(i + 1, values[i]);
        }
    }

    private static void applyResource(String path) throws Exception {
        try (InputStream in = TenancyDepartureIT.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8))) execute(sql);
        }
    }
}

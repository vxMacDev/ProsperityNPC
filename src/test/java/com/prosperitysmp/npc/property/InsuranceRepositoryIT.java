package com.prosperitysmp.npc.property;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.economy.TransferJournal;
import com.prosperitysmp.npc.economy.TransferProof.Observation;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.prosperitysmp.npc.property.InsuranceRepository.*;

/** Real isolated MariaDB. Financial fixtures use the actual two-leg journal, never Vault. */
@Timeout(45)
class InsuranceRepositoryIT {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 29);
    private static final Instant AT = Instant.parse("2026-09-29T18:00:00.123Z");
    private static final UUID RESERVE = new UUID(0, 810);
    private static DB maria;
    private static Database db;
    private static DatabaseSettings settings;
    private Property property;
    private InsuranceRepository insurance;
    private TransferJournal transfers;

    @BeforeAll static void boot() throws Exception {
        var cfg = DBConfigurationBuilder.newBuilder(); cfg.setPort(0);
        cfg.setBaseDir(java.nio.file.Files.createTempDirectory("npc-insurance-maria-").toFile());
        maria = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        try {
            maria.start();
            String schema = "npc_insurance_" + UUID.randomUUID().toString().replace("-", "");
            maria.createDB(schema);
            settings = new DatabaseSettings("localhost", cfg.getPort(), schema, "root", "", 8, 10_000);
            db = new Database(); db.connect(settings);
            for (String file : List.of("V1__initial_schema.sql", "V3__purchase_receipts.sql", "V4__transfer_journal.sql",
                    "V6__incident_repairs.sql", "V7__incident_rolls.sql", "V8__eviction_tenancies.sql",
                    "V23__property_insurance.sql")) apply(file);
        } catch (Exception | AssertionError failure) {
            try { stop(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    @AfterAll static void stop() throws Exception {
        try { if (db != null) db.close(); }
        finally { if (maria != null) { DB old = maria; maria = null; old.stop(); } }
    }
    @BeforeEach void reset() throws SQLException {
        execute("DROP TRIGGER IF EXISTS insurance_failure");
        for (String table : List.of("npc_insurance_preventions", "npc_property_insurance", "npc_transfer_observations",
                "npc_transfer_attempts", "npc_purchase_intents", "npc_economy_events", "npc_incident_rolls",
                "npc_incidents", "npc_property_funding", "npc_properties")) execute("DELETE FROM " + table);
        property = new Property(UUID.randomUUID(), UUID.randomUUID(), "insurance-test", "test", "small", 2,
                100, 100, UUID.randomUUID(), 0, 80, true);
        execute("INSERT INTO npc_properties(property_id,world_uuid,region_id,market_id,class_key,capacity,"
                + "reference_rent_cents,asking_rent_cents,owner_uuid,ownership_epoch,enabled) VALUES(?,?,?,?,?,?,?,?,?,?,TRUE)",
                property.propertyId(), property.worldUuid(), property.regionId(), property.marketId(), property.classKey(),
                property.capacity(), 100, 100, property.ownerUuid(), property.ownershipEpoch());
        execute("INSERT INTO npc_property_funding(property_id) VALUES(?)", property.propertyId());
        insurance = new InsuranceRepository(); transfers = new TransferJournal(db, RESERVE);
    }
    @AfterEach void cleanup() throws SQLException { execute("DROP TRIGGER IF EXISTS insurance_failure"); }

    @Test void durableNextDaySevenDayTermsReplayNeverExtendsAndReadOnlySnapshotWorks() throws Exception {
        long event = paid();
        ApplyResult original = apply(event, AT, DAY);
        assertEquals(ApplyStatus.APPLIED, original.status());
        assertEquals(1600, original.receipt().priceCents());
        assertEquals(DAY.plusDays(1), original.receipt().startDay());
        assertEquals(DAY.plusDays(8), original.receipt().endDayExclusive());
        assertFalse(covered(AT, DAY));
        assertTrue(covered(AT.plusSeconds(86_400), DAY.plusDays(1)));
        assertTrue(covered(AT.plusSeconds(7 * 86_400), DAY.plusDays(7)));
        assertFalse(covered(AT.plusSeconds(8 * 86_400), DAY.plusDays(8)));
        execute("UPDATE npc_purchase_intents SET state='APPLIED' WHERE event_id=?", event);
        db.close(); db.connect(settings); insurance = new InsuranceRepository();
        ApplyResult replay = apply(event, AT.plusSeconds(20 * 86_400), DAY.plusDays(20));
        assertEquals(ApplyStatus.ALREADY_APPLIED, replay.status());
        assertEquals(original.receipt(), replay.receipt());
        try (Connection c = db.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); c.setAutoCommit(false);
            try (Statement ps = c.createStatement()) { ps.execute("SET TRANSACTION READ ONLY"); }
            assertEquals(original.receipt(), insurance.snapshot(c, property, AT, DAY).orElseThrow().receipt());
            assertFalse(insurance.snapshot(c, property, AT.plusSeconds(8 * 86_400), DAY.plusDays(8)).isPresent());
            c.rollback();
        }
        assertEquals(1, count("npc_property_insurance"));
        assertEquals(1600, scalar("SELECT successful_cash_sinks_cents FROM npc_property_funding"));
        assertEquals(80, scalar("SELECT condition_score FROM npc_properties"));
    }

    @Test void firstNewIncidentConsumesTermReplayIsExactAndUsedTermStillBlocks() throws Exception {
        long event = paid(); apply(event, AT, DAY);
        Instant generated = AT.plusSeconds(86_400); LocalDate next = DAY.plusDays(1);
        PreventionResult prevented = tx(c -> {
            long id = incident(c, next, generated, true);
            PreventionResult result = insurance.recordPrevention(c, property, id, generated, next);
            roll(c, id, next, generated, "PREVENTED");
            return result;
        });
        assertEquals(PreventionStatus.PREVENTED, prevented.status());
        assertFalse(covered(generated, next));
        assertFalse(tx(c -> insurance.canPurchase(c, property.propertyId(), next)).booleanValue());
        PreventionResult replay = tx(c -> insurance.recordPrevention(c, property,
                prevented.receipt().incidentId(), generated, next));
        assertEquals(PreventionStatus.ALREADY_PREVENTED, replay.status());
        assertEquals(prevented.receipt(), replay.receipt());
        assertThrows(SQLException.class, () -> tx(c -> {
            long id = incident(c, next.plusDays(1), generated.plusSeconds(86_400), true);
            return insurance.recordPrevention(c, property, id, generated.plusSeconds(86_400), next.plusDays(1));
        }));
        assertEquals(1, count("npc_insurance_preventions"));
        assertEquals(1, count("npc_incidents"));
        assertTrue(tx(c -> insurance.canPurchase(c, property.propertyId(), DAY.plusDays(8))).booleanValue());
        assertEquals(80, scalar("SELECT condition_score FROM npc_properties"));
        assertEquals(0, count("npc_upgrades"));
    }

    @Test void existingUnpreventedOrOldRollIncidentsCannotConsumeCoverage() throws Exception {
        apply(paid(), AT, DAY);
        Instant generated = AT.plusSeconds(86_400); LocalDate next = DAY.plusDays(1);
        long old = tx(c -> incident(c, next, generated.minusMillis(1), true));
        assertThrows(SQLException.class, () -> tx(c -> insurance.recordPrevention(c, property, old, generated, next)));
        execute("DELETE FROM npc_incidents");
        long unprevented = tx(c -> incident(c, next, generated, false));
        assertThrows(SQLException.class, () -> tx(c -> insurance.recordPrevention(c, property, unprevented, generated, next)));
        execute("DELETE FROM npc_incidents");
        long rolled = tx(c -> { long id = incident(c, next, generated, true); roll(c, id, next, generated, "PREVENTED"); return id; });
        assertThrows(SQLException.class, () -> tx(c -> insurance.recordPrevention(c, property, rolled, generated, next)));
        assertEquals(0, count("npc_insurance_preventions"));
        assertTrue(covered(generated, next));
    }

    @Test void saleInvalidatesCoveragePreservesHistoryAndAllowsNewIncarnationCoverage() throws Exception {
        long event = paid(); CoverageReceipt original = apply(event, AT, DAY).receipt();
        UUID buyer = UUID.randomUUID();
        execute("UPDATE npc_properties SET owner_uuid=?,ownership_epoch=1 WHERE property_id=?", buyer, property.propertyId());
        Property bought = property.withOwner(buyer, 1);
        assertFalse(tx(c -> insurance.isCovered(c, property, AT.plusSeconds(86_400), DAY.plusDays(1))).booleanValue());
        assertFalse(tx(c -> insurance.isCovered(c, bought, AT.plusSeconds(86_400), DAY.plusDays(1))).booleanValue());
        assertTrue(tx(c -> insurance.canPurchase(c, property.propertyId(), DAY.plusDays(1))).booleanValue());
        assertTrue(tx(c -> insurance.snapshot(c, bought, DAY.plusDays(1))).isEmpty());
        assertEquals(original, tx(c -> insurance.find(c, event)).orElseThrow());
        assertEquals(original, apply(event, AT.plusSeconds(86_400), DAY.plusDays(1)).receipt());
        property = bought;
        var buyerTerm = apply(paid(), AT.plusSeconds(86_400), DAY.plusDays(1)).receipt();
        assertEquals(buyer, buyerTerm.ownerUuid()); assertEquals(1, buyerTerm.ownershipEpoch());
        assertFalse(covered(AT.plusSeconds(86_400), DAY.plusDays(1)));
        assertTrue(covered(AT.plusSeconds(2 * 86_400), DAY.plusDays(2)));
        assertEquals(original, tx(c -> insurance.find(c, event)).orElseThrow());
        assertEquals(2, count("npc_property_insurance"));
        execute("UPDATE npc_properties SET owner_uuid=?,ownership_epoch=2 WHERE property_id=?", original.ownerUuid(), property.propertyId());
        property = property.withOwner(original.ownerUuid(), 2);
        assertTrue(tx(c -> insurance.canPurchase(c, property.propertyId(), DAY.plusDays(2))).booleanValue());
        assertTrue(tx(c -> insurance.snapshot(c, property, DAY.plusDays(2))).isEmpty());
    }

    @Test void lastCoveredDayRenewalIsAdjacentActiveFirstAndEachTermHasOnePrevention() throws Exception {
        CoverageReceipt original = apply(paid(), AT, DAY).receipt();
        LocalDate last = DAY.plusDays(7); Instant lastAt = AT.plusSeconds(7 * 86_400);
        assertFalse(tx(c -> insurance.canPurchase(c, property.propertyId(), last.minusDays(1))).booleanValue());
        assertTrue(tx(c -> insurance.canPurchase(c, property.propertyId(), last)).booleanValue());
        long renewedEvent = paid();
        assertThrows(SQLException.class, () -> apply(renewedEvent, lastAt.minusSeconds(86_400), last.minusDays(1)));
        CoverageReceipt renewal = apply(renewedEvent, lastAt, last).receipt();
        assertEquals(original.endDayExclusive(), renewal.startDay());
        assertEquals(renewal.startDay().plusDays(7), renewal.endDayExclusive());
        assertFalse(tx(c -> insurance.canPurchase(c, property.propertyId(), last)).booleanValue());
        var current = tx(c -> insurance.current(c, property, lastAt, last)).orElseThrow();
        assertEquals(original, current.receipt()); assertEquals(renewal, current.nextTerm().orElseThrow());
        try (Connection c = db.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); c.setAutoCommit(false);
            try (Statement ps = c.createStatement()) { ps.execute("SET TRANSACTION READ ONLY"); }
            var snapshot = insurance.snapshot(c, property, lastAt, last).orElseThrow();
            assertEquals(current, snapshot);
            var method = OwnerDeskRepository.class.getDeclaredMethod("insurance", Connection.class, Property.class, LocalDate.class, Instant.class);
            method.setAccessible(true);
            var display = (OwnerDeskData.Insurance) method.invoke(null, c, property, last, lastAt);
            assertEquals(original.startDay(), display.startDay()); assertTrue(display.nextTermReserved());
            assertFalse(display.canPurchase(last));
            c.rollback();
        }
        var first = tx(c -> {
            long id = incident(c, last, lastAt, true);
            return insurance.recordPrevention(c, property, id, lastAt, last);
        });
        assertEquals(original.eventId(), first.receipt().eventId()); assertFalse(covered(lastAt, last));
        LocalDate next = last.plusDays(1); Instant nextAt = lastAt.plusSeconds(86_400);
        var selected = tx(c -> insurance.current(c, property, nextAt, next)).orElseThrow();
        assertEquals(renewal, selected.receipt()); assertTrue(selected.nextTerm().isEmpty());
        assertTrue(covered(nextAt, next));
        var second = tx(c -> {
            long id = incident(c, next, nextAt, true);
            return insurance.recordPrevention(c, property, id, nextAt, next);
        });
        assertEquals(renewal.eventId(), second.receipt().eventId()); assertFalse(covered(nextAt, next));
        assertEquals(2, count("npc_insurance_preventions")); assertEquals(2, count("npc_property_insurance"));
        assertEquals(renewal, apply(renewedEvent, nextAt, next).receipt());
        assertEquals(original, tx(c -> insurance.find(c, original.eventId())).orElseThrow());
    }

    @Test void actualOverlappingTermsStillFailClosedInsteadOfSelectingOne() throws Exception {
        apply(paid(), AT, DAY);
        LocalDate last = DAY.plusDays(7); Instant lastAt = AT.plusSeconds(7 * 86_400);
        CoverageReceipt renewal = apply(paid(), lastAt, last).receipt();
        execute("UPDATE npc_property_insurance SET applied_day=?,start_day=?,end_day_exclusive=? WHERE event_id=?",
                last.minusDays(1), last, last.plusDays(7), renewal.eventId());
        assertThrows(SQLException.class, () -> tx(c -> insurance.current(c, property, lastAt, last)));
        assertThrows(SQLException.class, () -> tx(c -> insurance.snapshot(c, property, lastAt, last)));
        assertFalse(tx(c -> insurance.canPurchase(c, property.propertyId(), last)).booleanValue());
        assertEquals(2, count("npc_property_insurance")); assertEquals(0, count("npc_insurance_preventions"));
    }

    @Test void paymentAndIncarnationBindingRejectsStaleOrUnpaidAndNoAppliedBackfill() throws Exception {
        long event = pending();
        assertThrows(SQLException.class, () -> tx(c -> insurance.applyPaid(c, event, property, AT, DAY)));
        pay(event);
        execute("UPDATE npc_economy_events SET paid_amount_cents=1599 WHERE event_id=?", event);
        assertThrows(SQLException.class, () -> tx(c -> insurance.applyPaid(c, event, property, AT, DAY)));
        execute("UPDATE npc_economy_events SET paid_amount_cents=1600 WHERE event_id=?", event);
        execute("UPDATE npc_properties SET ownership_epoch=1 WHERE property_id=?", property.propertyId());
        assertThrows(SQLException.class, () -> apply(event, AT, DAY));
        execute("UPDATE npc_properties SET ownership_epoch=0 WHERE property_id=?", property.propertyId());
        execute("UPDATE npc_purchase_intents SET state='APPLIED' WHERE event_id=?", event);
        assertThrows(SQLException.class, () -> apply(event, AT, DAY));
        assertEquals(0, count("npc_property_insurance"));
        try (Connection c = db.getConnection()) {
            assertThrows(SQLException.class, () -> insurance.find(c, event));
        }
    }

    @Test void failedEffectTransactionRollsBackTermsAndConsumptionAndMigrationIsRepeatable() throws Exception {
        long event = paid();
        assertThrows(SQLException.class, () -> tx(c -> {
            verifyPaid(c, event); insurance.applyPaid(c, event, property, AT, DAY);
            throw new SQLException("caller benefit failure");
        }));
        assertEquals(0, count("npc_property_insurance"));
        CoverageReceipt term = apply(event, AT, DAY).receipt();
        execute("CREATE TRIGGER insurance_failure AFTER INSERT ON npc_insurance_preventions "
                + "FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='insurance test failure'");
        LocalDate next = DAY.plusDays(1); Instant generated = AT.plusSeconds(86_400);
        assertThrows(SQLException.class, () -> tx(c -> {
            long id = incident(c, next, generated, true);
            return insurance.recordPrevention(c, property, id, generated, next);
        }));
        assertEquals(0, count("npc_incidents")); assertEquals(0, count("npc_insurance_preventions"));
        assertTrue(covered(generated, next));
        execute("DROP TRIGGER insurance_failure");
        apply("V23__property_insurance.sql"); apply("V23__property_insurance.sql");
        assertEquals(term, tx(c -> insurance.find(c, event)).orElseThrow());
    }

    @Test void competingApplicationsActuallyWaitOnPropertyAndOnlyOneTermIsIssued() throws Exception {
        long event = paid();
        var workers = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<ApplyResult> first, second;
            try (Connection blocker = db.getConnection()) {
                blocker.setAutoCommit(false);
                update(blocker, "SELECT property_id FROM npc_properties WHERE property_id=? FOR UPDATE", property.propertyId());
                first = workers.submit(() -> apply(event, AT, DAY));
                second = workers.submit(() -> apply(event, AT, DAY));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                long waiters;
                do {
                    waiters = scalar("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE DB=DATABASE() "
                            + "AND ID<>CONNECTION_ID() AND INFO LIKE '%SELECT property_id FROM npc_properties%FOR UPDATE%'");
                    if (waiters < 2) Thread.sleep(10);
                } while (waiters < 2 && System.nanoTime() < deadline);
                assertEquals(2, waiters, "both real effect transactions must wait on the property guard");
                assertFalse(first.isDone()); assertFalse(second.isDone()); blocker.rollback();
            }
            List<ApplyResult> results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(r -> r.status() == ApplyStatus.APPLIED).count());
            assertEquals(results.get(0).receipt(), results.get(1).receipt());
            assertEquals(1, count("npc_property_insurance"));
        } finally { workers.shutdownNow(); assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS)); }
    }

    private long pending() throws SQLException {
        return tx(c -> {
            long event;
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO npc_economy_events(idempotency_key,"
                    + "requested_amount_cents,source,direction,property_id,counterparty_uuid,ownership_epoch,benefit_unit_id,economy_day) "
                    + "VALUES(?,1600,'npc-property-insurance','TRANSFER_FROM_PUBLIC',?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                bind(ps, "insurance-test:" + UUID.randomUUID(), property.propertyId(), property.ownerUuid(), property.ownershipEpoch(), property.ownerUuid(), DAY.toString());
                assertEquals(1, ps.executeUpdate());
                try (ResultSet rs = ps.getGeneratedKeys()) { assertTrue(rs.next()); event = rs.getLong(1); }
            }
            update(c, "INSERT INTO npc_purchase_intents(event_id,request_id,property_id,payer_uuid,ownership_epoch,kind,"
                    + "economy_day,quoted_amount_cents,action_key,insurance_capacity) VALUES(?,?,?,?,?,'INSURANCE',?,1600,'insurance',2)",
                    event, UUID.randomUUID(), property.propertyId(), property.ownerUuid(), property.ownershipEpoch(), DAY.toString());
            return event;
        });
    }
    private long paid() throws Exception { long event = pending(); pay(event); return event; }
    private void pay(long event) throws Exception {
        var handle = new TransferJournal.Handle(UUID.randomUUID(), event, UUID.randomUUID(), "a".repeat(64));
        assertTrue(transfers.beginDebit(handle).newlyStarted());
        assertEquals(TransferJournal.State.CREDIT_READY,
                transfers.recordDebit(handle, new Observation(true, true, 16.0, 1000.0, 984.0, false)).state());
        assertTrue(transfers.beginCredit(handle).newlyStarted());
        assertEquals(TransferJournal.State.PAID,
                transfers.recordCredit(handle, new Observation(true, true, 16.0, 2000.0, 2016.0, false)).state());
        execute("UPDATE npc_economy_events SET finalized_at=? WHERE event_id=?", utc(AT.minusSeconds(1)), event);
    }
    private ApplyResult apply(long event, Instant at, LocalDate day) throws SQLException {
        return tx(c -> {
            // Match parent's property -> event -> intent -> proof lock order.
            update(c, "SELECT property_id FROM npc_properties WHERE property_id=? FOR UPDATE", property.propertyId());
            update(c, "SELECT event_id FROM npc_economy_events WHERE event_id=? FOR UPDATE", event);
            update(c, "SELECT event_id FROM npc_purchase_intents WHERE event_id=? FOR UPDATE", event);
            verifyPaid(c, event);
            return insurance.applyPaid(c, event, property, at, day);
        });
    }
    private void verifyPaid(Connection c, long event) throws SQLException {
        try {
            var method = TransferJournal.class.getDeclaredMethod("confirmedPaid", Connection.class, long.class);
            method.setAccessible(true);
            assertEquals(true, method.invoke(transfers, c, event));
        } catch (ReflectiveOperationException e) { throw new SQLException("Real payment verifier failed", e); }
    }
    private boolean covered(Instant at, LocalDate day) throws SQLException {
        return tx(c -> insurance.isCovered(c, property, at, day));
    }
    private long incident(Connection c, LocalDate day, Instant at, boolean prevented) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO npc_incidents(property_id,incident_type,cash_repair_cents,"
                + "item_repair_spec,condition_damage,economy_day,created_at,due_at,prevented) "
                + "VALUES(?,'PESTS',2000,'test',5,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, property.propertyId(), day.toString(), utc(at), utc(at.plusSeconds(86_400)), prevented);
            assertEquals(1, ps.executeUpdate());
            try (ResultSet rs = ps.getGeneratedKeys()) { assertTrue(rs.next()); return rs.getLong(1); }
        }
    }
    private void roll(Connection c, long id, LocalDate day, Instant at, String outcome) throws SQLException {
        update(c, "INSERT INTO npc_incident_rolls(property_id,economy_day,ownership_epoch,owner_uuid,outcome,"
                + "incident_id,incident_type,condition_before,condition_after,recorded_at) VALUES(?,?,0,?,?,?,'PESTS',80,80,?)",
                property.propertyId(), day.toString(), property.ownerUuid(), outcome, id, utc(at));
    }
    @FunctionalInterface private interface Work<T> { T run(Connection c) throws SQLException; }
    private <T> T tx(Work<T> work) throws SQLException {
        try (Connection c = db.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED); c.setAutoCommit(false);
            try { T result = work.run(c); c.commit(); return result; }
            catch (SQLException | RuntimeException | AssertionError failure) { c.rollback(); throw failure; }
        }
    }
    private static LocalDateTime utc(Instant at) { return LocalDateTime.ofInstant(at, ZoneOffset.UTC); }
    private static long count(String table) throws SQLException { return scalar("SELECT COUNT(*) FROM " + table); }
    private static long scalar(String sql) throws SQLException {
        try (Connection c = db.getConnection(); Statement ps = c.createStatement(); ResultSet rs = ps.executeQuery(sql)) {
            assertTrue(rs.next()); return rs.getLong(1);
        }
    }
    private static void execute(String sql, Object... args) throws SQLException {
        try (Connection c = db.getConnection()) { update(c, sql, args); }
    }
    private static void update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(5); bind(ps, args); ps.execute();
        }
    }
    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof UUID id) ps.setBytes(i + 1, Uuids.toBytes(id));
            else ps.setObject(i + 1, args[i]);
        }
    }
    private static void apply(String file) throws Exception {
        try (var in = InsuranceRepositoryIT.class.getResourceAsStream("/db/migration/" + file)) {
            assertNotNull(in, file);
            for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8))) execute(sql);
        }
    }
}

package com.prosperitysmp.npc.property;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.economy.PurchaseRepository;
import com.prosperitysmp.npc.economy.PurchaseRequest;
import com.prosperitysmp.npc.economy.TransferJournal;
import com.prosperitysmp.npc.economy.TransferProof.Observation;
import com.prosperitysmp.npc.economy.UtilityRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static com.prosperitysmp.npc.property.AutoCareRepository.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual local SQL composition. No Vault calls, runtime dispatch, or live-client claims. */
@Timeout(45)
class AutoCareRepositoryIT {
    private static final UUID WORLD = new UUID(0, 2400), OWNER = new UUID(0, 2401), RESERVE = new UUID(0, 2402);
    private static final Set<Action> ALL = Set.copyOf(EnumSet.allOf(Action.class));
    private static DB maria;
    private static Database database;
    private Property property;
    private LocalDate day;
    private long incident;
    private AutoCareRepository care;
    private UtilityRepository utilities;
    private PurchaseRepository purchases;

    @BeforeAll static void start() throws Exception {
        var cfg = DBConfigurationBuilder.newBuilder().setPort(0)
                .setBaseDir(Files.createTempDirectory("npc-autocare-maria-").toFile());
        maria = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build()); maria.start();
        String schema = "npc_autocare_" + UUID.randomUUID().toString().replace("-", "");
        maria.createDB(schema);
        database = new Database();
        database.connect(new DatabaseSettings("localhost", cfg.getPort(), schema, "root", "", 8, 10000));
        for (String file : List.of("V1__initial_schema.sql", "V2__resident_identity.sql", "V3__purchase_receipts.sql",
                "V4__transfer_journal.sql", "V6__incident_repairs.sql", "V7__incident_rolls.sql",
                "V8__eviction_tenancies.sql", "V12__incident_service_receipts.sql", "V13__utility_obligations.sql", "V17__transfer_resolutions.sql",
                "V24__auto_care.sql")) {
            try (var in = AutoCareRepositoryIT.class.getResourceAsStream("/db/migration/" + file)) {
                assertNotNull(in, file);
                for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8))) execute(sql);
            }
        }
        // V40: the refusal columns the transfer and commerce readers expect, on the tables this schema has.
        try (var v40 = database.getConnection()) { com.prosperitysmp.npc.db.V40Fixture.apply(v40); }
    }

    @AfterAll static void stop() throws Exception {
        try { if (database != null) database.close(); } finally { if (maria != null) maria.stop(); }
    }

    @BeforeEach void fixture() throws SQLException {
        for (String table : List.of("npc_auto_care_claims", "npc_auto_care_consents", "npc_utility_payments",
                "npc_utility_bills", "npc_transfer_resolutions", "npc_transfer_observations", "npc_transfer_attempts",
                "npc_incident_service_receipts", "npc_purchase_intents", "npc_economy_events", "npc_upgrades", "npc_incidents",
                "npc_property_ownership", "npc_property_funding", "npc_properties")) execute("DELETE FROM " + table);
        day = LocalDate.now(ZoneOffset.UTC);
        property = new Property(new UUID(24, 1), WORLD, "auto-care-fixture", "fixture", "room",
                2, 1000, 1000, OWNER, 0, 80, true);
        new PropertyRepository(database).insertEnrollment(property, 5000);
        execute("INSERT INTO npc_incidents(property_id,incident_type,cash_repair_cents,item_repair_spec,condition_damage,"
                + "economy_day,created_at,due_at,prevented) VALUES(?,'PESTS',2000,'16 paper + 8 baked_potato',5,?,"
                + "UTC_TIMESTAMP(3),DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY),FALSE)", property.propertyId(), day.toString());
        incident = scalar("SELECT incident_id FROM npc_incidents WHERE property_id=?", property.propertyId());
        care = repository();
        utilities = new UtilityRepository(database, RESERVE, Clock.systemUTC(), ZoneOffset.UTC, 0);
        purchases = new PurchaseRepository(database, RESERVE, Clock.systemUTC(), ZoneOffset.UTC, 0);
    }

    @Test void defaultDisabledRevisionRevocationAndEpochHistoryStayBound() throws Exception {
        assertTrue(care.current(property.propertyId(), OWNER, 0).isEmpty());
        assertEquals(ReserveStatus.DISABLED, reserve(Action.UTILITY, 100).status());
        var consent = configure(3100, ALL);
        assertEquals(consent, configure(3100, ALL));
        assertThrows(UnsupportedOperationException.class, () -> consent.actions().clear());
        assertThrows(SQLException.class, () -> care.configure(property.propertyId(), UUID.randomUUID(), 0, true, 100, ALL));
        var claim = reserve(Action.UTILITY, 100).claim();
        var waiting = utilities.preparePayment(claim.claimId(), property.propertyId(), OWNER, 0, day).receipt();
        var disabled = care.configure(property.propertyId(), OWNER, 0, false, 3100, ALL);
        assertEquals(consent.revision() + 1, disabled.revision());
        assertEquals(disabled, care.configure(property.propertyId(), OWNER, 0, false, 3100, ALL));
        assertThrows(SQLException.class, () -> care.bind(claim.claimId(), waiting.eventId()));
        configure(3100, ALL);
        assertThrows(SQLException.class, () -> care.bind(claim.claimId(), waiting.eventId()));
        assertEquals(State.RESERVED, care.find(claim.claimId()).orElseThrow().state());
        UUID nextOwner = new UUID(0, 2499);
        new PropertyRepository(database).applyOwnerChange(property.propertyId(), OWNER, nextOwner, 1, "auto-care-test");
        assertTrue(care.current(property.propertyId(), OWNER, 0).isEmpty());
        assertTrue(care.enabled(100).isEmpty());
        var next = care.configure(property.propertyId(), nextOwner, 1, true, 100, Set.of(Action.UTILITY));
        assertEquals(1, next.ownershipEpoch());
        assertThrows(SQLException.class, () -> care.reserve(property.propertyId(), nextOwner, 1, day, Action.UTILITY, null, 100));
        assertEquals(2, scalar("SELECT COUNT(*) FROM npc_auto_care_consents"));
        assertEquals(OWNER, care.find(claim.claimId()).orElseThrow().ownerUuid());
        assertEquals(0, scalar("SELECT COUNT(*) FROM npc_transfer_attempts"));
    }

    @Test void concurrentCapAndReplayRetainOnePermanentActionSlot() throws Exception {
        configure(1000, ALL);
        var start = new CountDownLatch(1);
        var results = new ArrayList<Reservation>();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var jobs = new ArrayList<java.util.concurrent.Future<Reservation>>();
            for (int i = 0; i < 8; i++) {
                Action action = i % 2 == 0 ? Action.UTILITY : Action.MAINTAIN;
                jobs.add(pool.submit(() -> { assertTrue(start.await(10, TimeUnit.SECONDS));
                    return reserve(action, action == Action.UTILITY ? 100 : 1000); }));
            }
            start.countDown();
            for (var job : jobs) results.add(job.get(15, TimeUnit.SECONDS));
        }
        assertEquals(1, results.stream().filter(Reservation::newlyReserved).count());
        assertEquals(1, scalar("SELECT COUNT(*) FROM npc_auto_care_claims"));
        var claim = results.stream().filter(Reservation::newlyReserved).findFirst().orElseThrow().claim();
        var held = care.hold(claim.claimId()); assertEquals(State.HELD, held.state());
        Action other = claim.action() == Action.UTILITY ? Action.MAINTAIN : Action.UTILITY;
        assertEquals(ReserveStatus.LIMIT, reserve(other, other == Action.UTILITY ? 100 : 1000).status());
        var replay = reserve(claim.action(), claim.quotedCents());
        assertEquals(ReserveStatus.REPLAY, replay.status()); assertFalse(replay.newlyReserved());
        assertThrows(SQLException.class, () -> care.bind(claim.claimId(), 1));
        assertEquals(0, scalar("SELECT COUNT(*) FROM npc_economy_events"));
    }

    @Test void bindingRequiresExactExistingReceiptBeforeMoneyAndNeverReauthorizesReplay() throws Exception {
        configure(3100, ALL);
        var claim = reserve(Action.UTILITY, 100).claim();
        var unrelated = purchases.enqueue(new PurchaseRequest(UUID.randomUUID(), property.propertyId(), OWNER, 0,
                PurchaseRequest.Kind.MAINTAIN, null, null, null, day));
        assertThrows(SQLException.class, () -> care.bind(claim.claimId(), unrelated.eventId()));
        var waiting = utilities.preparePayment(claim.claimId(), property.propertyId(), OWNER, 0, day).receipt();
        assertTrue(care.bind(claim.claimId(), waiting.eventId()).newlyBound());
        assertFalse(care.bind(claim.claimId(), waiting.eventId()).newlyBound());
        var handle = handle(waiting.eventId());
        var journal = new TransferJournal(database, RESERVE);
        assertTrue(journal.beginDebit(handle).newlyStarted());
        assertFalse(care.bind(claim.claimId(), waiting.eventId()).newlyBound());
        assertFalse(journal.beginDebit(handle).newlyStarted());
        assertEquals(State.BOUND, care.reconcile(claim.claimId()).state());
        var maintenance = reserve(Action.MAINTAIN, 1000).claim();
        var queued = purchases.enqueue(new PurchaseRequest(maintenance.claimId(), property.propertyId(), OWNER, 0,
                PurchaseRequest.Kind.MAINTAIN, null, null, null, day));
        assertTrue(journal.beginDebit(handle(queued.eventId())).newlyStarted());
        assertThrows(SQLException.class, () -> care.bind(maintenance.claimId(), queued.eventId()));
        assertEquals(State.RESERVED, care.find(maintenance.claimId()).orElseThrow().state());
    }

    @Test void allSupportedActionsSpendOnlyAfterActualTwoLegProofAndDomainReceipt() throws Exception {
        configure(3100, ALL);
        for (Action action : Action.values()) {
            long cents = switch (action) { case UTILITY -> 100; case CASH_REPAIR -> 2000; case MAINTAIN -> 1000; };
            var claim = reserve(action, cents).claim();
            long event = event(claim);
            assertTrue(care.bind(claim.claimId(), event).newlyBound());
            var journal = new TransferJournal(database, RESERVE); var handle = handle(event);
            assertTrue(journal.beginDebit(handle).newlyStarted());
            double dollars = cents / 100.0;
            assertEquals(TransferJournal.State.CREDIT_READY, journal.recordDebit(handle,
                    new Observation(true, true, dollars, 1000.0, 1000.0 - dollars, false)).state());
            assertEquals(State.BOUND, care.reconcile(claim.claimId()).state());
            assertTrue(journal.beginCredit(handle).newlyStarted());
            assertEquals(TransferJournal.State.PAID, journal.recordCredit(handle,
                    new Observation(true, true, dollars, 2000.0, 2000.0 + dollars, false)).state());
            assertEquals(State.HELD, care.reconcile(claim.claimId()).state());
            if (action == Action.UTILITY) assertEquals(UtilityRepository.State.PAID, utilities.reconcile(event).orElseThrow().state());
            else assertEquals(PurchaseRepository.State.APPLIED, purchases.complete(event).orElseThrow().state());
            var spent = care.reconcile(claim.claimId()); assertEquals(State.SPENT, spent.state());
            assertEquals(spent, repository().reconcile(claim.claimId()));
            assertFalse(care.bind(claim.claimId(), event).newlyBound());
            assertFalse(reserve(action, cents).newlyReserved());
        }
        assertEquals(3, scalar("SELECT COUNT(*) FROM npc_economy_events"));
        assertEquals(3, scalar("SELECT COUNT(*) FROM npc_transfer_attempts"));
        assertEquals(3, scalar("SELECT COUNT(*) FROM npc_auto_care_claims WHERE state='SPENT'"));
        assertTrue(care.pending(100).isEmpty());
    }

    @Test void confirmedNoMovementReleasesBudgetButNotSlotAndEvidenceLossFailsClosed() throws Exception {
        configure(1000, ALL);
        var claim = reserve(Action.UTILITY, 100).claim(); long event = event(claim);
        assertTrue(care.bind(claim.claimId(), event).newlyBound());
        assertEquals(ReserveStatus.LIMIT, reserve(Action.MAINTAIN, 1000).status());
        var journal = new TransferJournal(database, RESERVE); var handle = handle(event);
        journal.beginDebit(handle);
        assertEquals(TransferJournal.State.DECLINED, journal.recordDebit(handle,
                new Observation(true, false, 1.0, 1000.0, 1000.0, false)).state());
        assertEquals(UtilityRepository.State.DECLINED, utilities.reconcile(event).orElseThrow().state());
        assertEquals(State.DECLINED, care.reconcile(claim.claimId()).state());
        assertEquals(ReserveStatus.REPLAY, reserve(Action.UTILITY, 100).status());
        execute("DELETE FROM npc_transfer_observations WHERE attempt_id=?", handle.attemptId());
        assertThrows(SQLException.class, () -> reserve(Action.MAINTAIN, 1000));
        assertEquals(1, scalar("SELECT COUNT(*) FROM npc_auto_care_claims"));
        execute("INSERT INTO npc_transfer_observations(attempt_id,leg,response_present,response_success,reported_bits,before_bits,after_bits,threw) "
                + "VALUES(?,'DEBIT',TRUE,FALSE,?,?,?,FALSE)", handle.attemptId(), Double.doubleToRawLongBits(1.0),
                Double.doubleToRawLongBits(1000.0), Double.doubleToRawLongBits(1000.0));
        assertTrue(reserve(Action.MAINTAIN, 1000).newlyReserved());
        assertEquals(2, scalar("SELECT COUNT(*) FROM npc_auto_care_claims"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM npc_transfer_attempts"));
    }

    @Test void unknownIntentAndForgedPaidLabelRetainCapAndBoundedFeedsAreImmutable() throws Exception {
        configure(1000, ALL);
        var claim = reserve(Action.UTILITY, 100).claim(); long event = event(claim);
        care.bind(claim.claimId(), event);
        var journal = new TransferJournal(database, RESERVE); var handle = handle(event);
        journal.beginDebit(handle);
        assertEquals(1, journal.recoverOtherRuns(UUID.randomUUID(), 8));
        assertEquals(State.HELD, care.reconcile(claim.claimId()).state());
        assertEquals(ReserveStatus.LIMIT, reserve(Action.MAINTAIN, 1000).status());
        execute("UPDATE npc_transfer_attempts SET state='PAID',credit_intent_at=UTC_TIMESTAMP(3) WHERE event_id=?", event);
        execute("UPDATE npc_economy_events SET state='PAID',paid_amount_cents=100,finalized_at=UTC_TIMESTAMP(3) WHERE event_id=?", event);
        execute("UPDATE npc_utility_payments SET state='PAID',outcome_code='TRANSFER_CONFIRMED',paid_at=UTC_TIMESTAMP(3) WHERE event_id=?", event);
        assertThrows(SQLException.class, () -> care.reconcile(claim.claimId()));
        assertEquals(State.HELD, care.find(claim.claimId()).orElseThrow().state());
        var feed = care.pending(1); assertEquals(1, feed.size());
        assertTrue(care.pending(feed.getFirst().claimId(), 1).isEmpty());
        assertThrows(UnsupportedOperationException.class, feed::clear);
        var consents = care.enabled(1); assertEquals(1, consents.size());
        assertTrue(care.enabled(consents.getFirst().propertyId(), 1).isEmpty());
        assertThrows(UnsupportedOperationException.class, consents::clear);
        assertThrows(IllegalArgumentException.class, () -> care.pending(101));
        assertThrows(IllegalArgumentException.class, () -> care.enabled(0));
        assertEquals(1, scalar("SELECT COUNT(*) FROM npc_auto_care_claims"));
    }

    @Test void refusedDebitIsADeclineThatReleasesTheBudget() throws Exception {
        configure(1000, ALL);
        var claim = reserve(Action.UTILITY, 100).claim(); long event = event(claim);
        assertTrue(care.bind(claim.claimId(), event).newlyBound());
        assertEquals(ReserveStatus.LIMIT, reserve(Action.MAINTAIN, 1000).status());
        var journal = new TransferJournal(database, RESERVE); var handle = handle(event);
        journal.beginDebit(handle);
        assertEquals(TransferJournal.State.DECLINED, journal.recordDebit(handle, Observation.refused(
                com.prosperitysmp.npc.economy.TransferProof.Refusal.INSUFFICIENT_FUNDS, null, 0.5, 0.5)).state());
        assertEquals(UtilityRepository.State.DECLINED, utilities.reconcile(event).orElseThrow().state());
        // The auto-care reader must see the refusal, or this decline reads as missing proof.
        assertEquals(State.DECLINED, care.reconcile(claim.claimId()).state());
        assertEquals(ReserveStatus.REPLAY, reserve(Action.UTILITY, 100).status(), "the day's slot stays used");
        assertTrue(reserve(Action.MAINTAIN, 1000).newlyReserved(), "the budget is released");
    }

    @Test void aLegacyRefusalResolvedByStaffIsADecline() throws Exception {
        configure(1000, ALL);
        var claim = reserve(Action.UTILITY, 100).claim(); long event = event(claim);
        care.bind(claim.claimId(), event);
        var journal = new TransferJournal(database, RESERVE); var handle = handle(event);
        journal.beginDebit(handle);
        assertEquals(TransferJournal.State.AMBIGUOUS, journal.recordDebit(handle,
                new Observation(false, false, null, 1000.001, 1000.001, true)).state());
        assertEquals(TransferJournal.State.DECLINED,
                journal.resolveLegacyRefusal(handle, UUID.randomUUID(), "staff:test", "e".repeat(64), true).state());
        assertEquals(UtilityRepository.State.DECLINED, utilities.reconcile(event).orElseThrow().state());
        assertEquals(State.DECLINED, care.reconcile(claim.claimId()).state());
        assertTrue(reserve(Action.MAINTAIN, 1000).newlyReserved(), "the budget is released");
    }

    private AutoCareRepository repository() { return new AutoCareRepository(database, RESERVE, Clock.systemUTC(), ZoneOffset.UTC, 0); }
    private Consent configure(long cap, Set<Action> actions) throws SQLException {
        return care.configure(property.propertyId(), OWNER, 0, true, cap, actions);
    }
    private Reservation reserve(Action action, long cents) throws SQLException {
        return care.reserve(property.propertyId(), OWNER, 0, day, action, action == Action.CASH_REPAIR ? incident : null, cents);
    }
    private long event(Claim claim) throws SQLException {
        if (claim.action() == Action.UTILITY)
            return utilities.preparePayment(claim.claimId(), property.propertyId(), OWNER, 0, day).receipt().eventId();
        PurchaseRequest request = claim.action() == Action.CASH_REPAIR
                ? purchases.quoteRepair(claim.claimId(), property.propertyId(), OWNER, 0, incident, day)
                : new PurchaseRequest(claim.claimId(), property.propertyId(), OWNER, 0, PurchaseRequest.Kind.MAINTAIN, null, null, null, day);
        return purchases.enqueue(request).eventId();
    }
    private static TransferJournal.Handle handle(long event) { return new TransferJournal.Handle(UUID.randomUUID(), event, UUID.randomUUID(), "a".repeat(64)); }
    private static long scalar(String sql, Object... values) throws SQLException {
        try (Connection c = database.getConnection(); var ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(5); bind(ps, values);
            try (var rs = ps.executeQuery()) { assertTrue(rs.next()); return rs.getLong(1); }
        }
    }
    private static void execute(String sql, Object... values) throws SQLException {
        try (Connection c = database.getConnection(); var ps = c.prepareStatement(sql)) { ps.setQueryTimeout(5); bind(ps, values); ps.execute(); }
    }
    private static void bind(PreparedStatement ps, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) {
            if (values[i] instanceof UUID id) ps.setBytes(i + 1, Uuids.toBytes(id)); else ps.setObject(i + 1, values[i]);
        }
    }
}

package com.prosperitysmp.npc.economy;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.config.EconomyLimits;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.economy.RentReservationRepository.AppreciationScore;
import com.prosperitysmp.npc.economy.RentReservationRepository.Reserved;
import com.prosperitysmp.npc.economy.RentReservationRepository.Retirement;
import com.prosperitysmp.npc.economy.RentReservationRepository.RetirementReason;
import com.prosperitysmp.npc.economy.TransferJournal.Handle;
import com.prosperitysmp.npc.economy.TransferProof.Observation;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyRepository;
import com.prosperitysmp.npc.property.PropertyService;
import com.prosperitysmp.npc.property.Tenant;
import com.prosperitysmp.npc.property.TenantRepository;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Payout retirement on a real MariaDB: the real rent and appreciation reservation SQL, the real transfer
 * journal and the real ownership repository. Vault is replaced by fixture observations; nothing here can
 * move money. A floor deferral is modelled the way the runtime leaves it: a reservation with no attempt.
 */
@Timeout(300)
class PayoutRetirementIT {
    private static final Instant NOW = Instant.parse("2026-09-07T12:00:00.001Z");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 7);
    private static final Instant NEXT_NOW = NOW.plusSeconds(86_400);
    private static final LocalDate NEXT = DAY.plusDays(1);
    private static final ZoneId ZONE = ZoneId.of("America/Chicago");
    private static final int RESET = 4;
    private static final UUID WORLD = new UUID(0, 3300);
    private static final UUID RESERVE = new UUID(0, 3302);
    private static final long FLOOR = 3_500_000L;
    private static final String RENT = "npc_economy_events WHERE source='npc-property-rent'";
    private static final String APPRECIATION = "npc_economy_events WHERE source='npc-property-appreciation'";
    private static final AppreciationScore TOP = (c, p, at) -> OptionalDouble.of(100.0);
    private static DB mariaDb;
    private static Database database;
    private static List<String> tables;
    private MutableClock clock;
    private List<UUID> properties;

    @BeforeAll
    static void bootDatabase() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        try {
            mariaDb.start();
            String schema = "npc_retirement_" + UUID.randomUUID().toString().replace("-", "");
            mariaDb.createDB(schema);
            database = new Database();
            database.connect(new DatabaseSettings("localhost", cfg.getPort(), schema, "root", "", 6, 10_000L));
            var migrations = MigrationRunner.class.getDeclaredField("MIGRATIONS");
            migrations.setAccessible(true);
            for (Object migration : (List<?>) migrations.get(null)) {
                var resource = migration.getClass().getDeclaredMethod("resource");
                resource.setAccessible(true);
                applyResource("/" + resource.invoke(migration));
            }
            tables = rows("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() "
                    + "AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME").stream().map(List::getFirst).toList();
            assertTrue(tables.containsAll(List.of("npc_payout_retirements", "npc_appreciation_reservations",
                    "npc_rent_reservations", "npc_transfer_attempts", "npc_economy_events")));
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
    void freshFixture() throws SQLException {
        try (Connection c = database.getConnection()) {
            execute(c, "SET SESSION foreign_key_checks=0");
            try { for (String table : tables) execute(c, "DELETE FROM " + table); }
            finally { execute(c, "SET SESSION foreign_key_checks=1"); }
        }
        execute("INSERT INTO npc_rent_mutex(singleton) VALUES (1)");
        execute("INSERT INTO npc_housing_registry_lock(singleton_id) VALUES (1)");
        execute("INSERT INTO npc_chronicle_recovery_lock(singleton_id) VALUES (1)");
        clock = new MutableClock(NOW);
        var ids = new ArrayList<UUID>();
        for (int i = 0; i < 4; i++) ids.add(operating(new UUID(0, 3310 + i)));
        properties = ids.stream().sorted(Comparator.comparing(UUID::toString)).toList();
        assertEquals(0, count("npc_payout_retirements"));
    }

    @Test
    void expiredUnattemptedRentIsRetiredAndReleasesLifetimeFunding() throws Exception {
        var limits = limits(15000, 4500, 1500);
        UUID id = properties.getFirst();
        // Exactly one $6 rent of lifetime funding: the floor-deferred rent from DAY holds all of it.
        var deferred = rents().reserve(DAY, List.of(property(id)), limits);
        assertEquals(List.of(600L), deferred.stream().map(Reserved::cents).toList());
        long dead = deferred.getFirst().eventId();
        assertTrue(rents().retireStale(DAY, 500).isEmpty(), "today's deferred rent is still payable today");
        closeDay();
        payUtilityFor(id, NEXT);
        execute("UPDATE npc_property_funding SET one_time_seed_cents=600,successful_cash_sinks_cents=0,"
                + "successful_cash_payouts_cents=0 WHERE property_id=?", id);
        assertEquals(Map.of(id, 600L), pendingLifetime(NEXT), "before: the dead row holds the home's funding");
        // Flag off: the dead row still consumes the funding and nothing is retired.
        var warnings = new ArrayList<String>();
        assertTrue(UtilityPaymentRuntime.reserveRentBatch(rents(), false, NEXT, List.of(property(id)), limits, warnings::add).isEmpty());
        assertEquals(0, count("npc_rent_reservations WHERE economy_day=?", NEXT));
        assertEquals(0, count("npc_payout_retirements"));
        assertTrue(warnings.isEmpty());
        // Flag on: the expired row is retired, its funding comes back, and today's rent is reserved in full.
        var queued = UtilityPaymentRuntime.reserveRentBatch(rents(), true, NEXT, List.of(property(id)), limits, warnings::add);
        assertEquals(List.of(600L), queued.stream().map(Reserved::cents).toList());
        assertEquals(1, count("npc_payout_retirements WHERE event_id=? AND kind='RENT' AND reason='EXPIRED' AND property_id=? "
                + "AND economy_day=? AND retired_on=? AND retired_owner=? AND retired_epoch=0 AND current_owner=? AND current_epoch=0 "
                + "AND retired_at IS NOT NULL", dead, id, DAY, NEXT, property(id).ownerUuid(), property(id).ownerUuid()));
        assertEquals(1, count("npc_economy_events WHERE event_id=? AND state='FAILED' AND paid_amount_cents=0 "
                + "AND claimed_at IS NOT NULL AND finalized_at IS NOT NULL", dead));
        assertEquals(1, count("npc_rent_reservations WHERE event_id=?", dead), "the reservation is kept, never erased");
        assertEquals(Map.of(id, 600L), pendingLifetime(NEXT), "only today's live rent is outstanding");
        assertEquals(List.of("Retired 1 never-attempted payout reservations (day passed or owner changed). No money moved."), warnings);
        // Never payable again: the journal refuses the retired event outright and writes nothing.
        var refused = assertThrows(SQLException.class, () -> journal().beginDebit(
                new Handle(UUID.randomUUID(), dead, UUID.randomUUID(), "e".repeat(64))));
        assertEquals("Transfer requires an untouched pending event", refused.getMessage());
        assertEquals(0, count("npc_transfer_attempts WHERE event_id=?", dead));
        // Replay: nothing new, nothing changed.
        var before = snapshot();
        assertTrue(rents().retireStale(NEXT, 500).isEmpty());
        assertEquals(0, UtilityPaymentRuntime.retireStale(rents(), NEXT, warnings::add));
        assertEquals(before, snapshot(), "a replayed retirement writes nothing");
        assertEquals(Map.of(id, 600L), pendingLifetime(NEXT));
    }

    @Test
    void deferredAppreciationIsLiveOnItsBatchDayAndRetiredOnlyAfterIt() throws Exception {
        var limits = limits(15000, 4500, 1500);
        payRent(limits);
        closeDay();
        var deferred = rents().reserveAppreciation(DAY, enabled(), limits, TOP);
        assertEquals(4, deferred.size(), "all four reserved, none dispatched (floor)");
        assertTrue(rents().retireStale(NEXT, 500).isEmpty(), "the closed day's appreciation is still payable today");
        assertEquals(4, rents().pendingAppreciation(DAY, enabled()).size());
        for (UUID id : properties) assertEquals(300L, pendingLifetime(NEXT).get(id));
        clock.set(NEXT_NOW.plusSeconds(86_400));
        LocalDate after = NEXT.plusDays(1);
        assertThrows(IllegalArgumentException.class, () -> rents().reserveAppreciation(DAY, enabled(), limits, TOP),
                "DAY's appreciation is unreachable from here on");
        var retired = rents().retireStale(after, 500);
        assertEquals(4, retired.size());
        for (Retirement r : retired) {
            assertEquals(EventSource.APPRECIATION, r.source());
            assertEquals(RetirementReason.EXPIRED, r.reason());
            assertEquals(DAY, r.day());
        }
        assertEquals(4, count("npc_payout_retirements WHERE kind='APPRECIATION' AND reason='EXPIRED' AND economy_day=? AND retired_on=?", DAY, after));
        assertEquals(4, count(APPRECIATION + " AND state='FAILED' AND paid_amount_cents=0"));
        assertEquals(Map.of(), pendingLifetime(after), "appreciation funding released");
        assertEquals(4, count(RENT + " AND state='PAID'"), "paid rent is never a candidate");
    }

    @Test
    void anyTransferAttemptLeavesTheRowUntouchedAndCounted() throws Exception {
        var limits = limits(15000, 4500, 1500);
        var rent = rents().reserve(DAY, enabled(), limits);
        assertEquals(4, rent.size());
        Reserved bare = rent.get(0), intent = rent.get(1), ambiguous = rent.get(2), relabelled = rent.get(3);
        // Ambiguous: debit confirmed, host died before credit, recovered by the next host.
        Handle crash = new Handle(UUID.randomUUID(), ambiguous.eventId(), UUID.randomUUID(), "c".repeat(64));
        assertTrue(journal().beginDebit(crash).newlyStarted());
        assertEquals(TransferJournal.State.CREDIT_READY, journal().recordDebit(crash,
                new Observation(true, true, 6.0, 100000.0, 99994.0, false)).state());
        assertEquals(1, journal().recoverOtherRuns(UUID.randomUUID(), 100));
        assertEquals(TransferJournal.State.AMBIGUOUS, journal().find(ambiguous.eventId()).orElseThrow().state());
        // A bare debit intent (event CLAIMED).
        assertTrue(journal().beginDebit(new Handle(UUID.randomUUID(), intent.eventId(), UUID.randomUUID(), "d".repeat(64))).newlyStarted());
        // Even a ledger row relabelled PENDING is never retired while any attempt row exists for it.
        assertTrue(journal().beginDebit(new Handle(UUID.randomUUID(), relabelled.eventId(), UUID.randomUUID(), "f".repeat(64))).newlyStarted());
        execute("UPDATE npc_economy_events SET state='PENDING',claimed_at=NULL WHERE event_id=?", relabelled.eventId());
        closeDay();
        var before = rows("SELECT event_id,state,paid_amount_cents,claimed_at,finalized_at FROM npc_economy_events WHERE event_id IN (?,?,?) ORDER BY event_id",
                intent.eventId(), ambiguous.eventId(), relabelled.eventId());
        var attempts = rows("SELECT * FROM npc_transfer_attempts ORDER BY attempt_id");
        var retired = rents().retireStale(NEXT, 500);
        assertEquals(List.of(new Retirement(bare.eventId(), EventSource.RENT, bare.property().propertyId(), DAY, RetirementReason.EXPIRED)),
                retired, "only the bare reservation is provably unattempted");
        assertEquals(before, rows("SELECT event_id,state,paid_amount_cents,claimed_at,finalized_at FROM npc_economy_events WHERE event_id IN (?,?,?) ORDER BY event_id",
                intent.eventId(), ambiguous.eventId(), relabelled.eventId()), "attempted rows are untouched");
        assertEquals(attempts, rows("SELECT * FROM npc_transfer_attempts ORDER BY attempt_id"));
        assertEquals(0, count("npc_payout_retirements WHERE event_id IN (?,?,?)", intent.eventId(), ambiguous.eventId(), relabelled.eventId()));
        var outstanding = pendingLifetime(NEXT);
        assertEquals(Map.of(intent.property().propertyId(), 600L, ambiguous.property().propertyId(), 600L,
                relabelled.property().propertyId(), 600L), outstanding, "attempted rows still hold their funding");
        assertTrue(rents().retireStale(NEXT, 500).isEmpty(), "replay");
    }

    @Test
    void saleAfterDeferredAppreciationNoLongerStopsTheSameDayRerun() throws Exception {
        var limits = limits(15000, 4500, 1500);
        payRent(limits);
        closeDay();
        assertEquals(4, rents().reserveAppreciation(DAY, enabled(), limits, TOP).size());
        // The batch defers appreciation at the floor; then property 0 is sold the same economy day.
        UUID sold = properties.getFirst();
        Property old = property(sold);
        UUID buyer = new UUID(0, 3399);
        new PropertyRepository(database).applyOwnerChange(sold, old.ownerUuid(), buyer, old.ownershipEpoch() + 1, "test-sale");
        // Flag off: the rerun's queued read refuses the batch, which the runtime holds for review.
        assertTrue(UtilityPaymentRuntime.reserveRentBatch(rents(), false, NEXT, enabled(), limits, ignored -> { }).isEmpty());
        var stale = assertThrows(SQLException.class, () -> rents().pendingAppreciation(DAY, enabled()));
        assertEquals("Queued appreciation ownership mismatch", stale.getMessage());
        // Flag on: the rerun retires the old owner's never-attempted appreciation and completes.
        var warnings = new ArrayList<String>();
        assertTrue(UtilityPaymentRuntime.reserveRentBatch(rents(), true, NEXT, enabled(), limits, warnings::add).isEmpty());
        assertEquals(1, warnings.size());
        assertTrue(rents().reserveAppreciation(DAY, enabled(), limits, TOP).isEmpty(), "replay reserves nothing");
        var queued = rents().pendingAppreciation(DAY, enabled());
        assertEquals(3, queued.size());
        assertTrue(queued.stream().noneMatch(r -> r.property().propertyId().equals(sold)));
        assertEquals(1, count("npc_payout_retirements WHERE kind='APPRECIATION' AND reason='OWNER_CHANGED' AND property_id=? "
                + "AND retired_owner=? AND retired_epoch=0 AND current_owner=? AND current_epoch=1", sold, old.ownerUuid(), buyer));
        assertEquals(0, count("npc_payout_retirements WHERE property_id<>?", sold), "nothing else is retired");
        assertFalse(pendingLifetime(NEXT).containsKey(sold), "the sold home's funding is released");
        for (var row : queued) assertTrue(pay(row), "the rest of the phase still pays");
    }

    @Test
    void saleAfterDeferredRentNoLongerStopsTheSameDayRerun() throws Exception {
        var limits = limits(15000, 4500, 1500);
        assertEquals(4, rents().reserve(DAY, enabled(), limits).size());
        UUID sold = properties.getFirst();
        Property old = property(sold);
        UUID buyer = new UUID(0, 3398);
        new PropertyRepository(database).applyOwnerChange(sold, old.ownerUuid(), buyer, old.ownershipEpoch() + 1, "test-sale");
        // Flag off: the same-day rerun refuses on the stale rent binding.
        var stale = assertThrows(SQLException.class,
                () -> UtilityPaymentRuntime.reserveRentBatch(rents(), false, DAY, enabled(), limits, ignored -> { }));
        assertEquals("Queued rent ownership mismatch", stale.getMessage());
        assertEquals(0, count("npc_payout_retirements"));
        var queued = UtilityPaymentRuntime.reserveRentBatch(rents(), true, DAY, enabled(), limits, ignored -> { });
        assertEquals(3, queued.size());
        assertTrue(queued.stream().noneMatch(r -> r.property().propertyId().equals(sold)));
        assertEquals(1, count("npc_payout_retirements WHERE kind='RENT' AND reason='OWNER_CHANGED' AND property_id=? AND economy_day=? "
                + "AND current_owner=? AND current_epoch=1", sold, DAY, buyer));
        // Its own day: still counted against today's caps (conservative), but no longer against lifetime funding.
        assertEquals(2400, rents().serverSpentCents(DAY));
        assertFalse(pendingLifetime(DAY).containsKey(sold));
        // Documented consequence: the buyer gets no rent for the sale day (the day's row and cohort already exist).
        assertTrue(rents().reserve(DAY, enabled(), limits).isEmpty());
        for (var row : queued) assertTrue(pay(row));
    }

    @Test
    void retiredRowsFromEarlierDaysLeaveTheBoundedLedgerScan() throws Exception {
        UUID id = properties.getFirst();
        UUID owner = property(id).ownerUuid();
        String digits = "(SELECT 0 d UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 "
                + "UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)";
        execute("INSERT INTO npc_economy_events(idempotency_key,requested_amount_cents,paid_amount_cents,source,direction,property_id,"
                + "counterparty_uuid,ownership_epoch,benefit_unit_id,economy_day,state,claimed_at,finalized_at) "
                + "SELECT CONCAT('synthetic-retired|',n),600,0,'npc-property-rent','TRANSFER_TO_PUBLIC',?,?,0,?,'2026-01-01','FAILED',"
                + "CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3) FROM (SELECT a.d+10*b.d+100*c.d+1000*e.d+10000*f.d+1 AS n FROM "
                + digits + " a," + digits + " b," + digits + " c," + digits + " e," + digits + " f) t WHERE n<=10001", id, owner, owner);
        execute("INSERT INTO npc_payout_retirements(event_id,kind,property_id,economy_day,reason,retired_owner,retired_epoch,"
                + "current_owner,current_epoch,retired_on) SELECT event_id,'RENT',property_id,economy_day,'EXPIRED',counterparty_uuid,0,"
                + "counterparty_uuid,0,'2026-01-02' FROM npc_economy_events WHERE idempotency_key LIKE 'synthetic-retired|%'");
        assertEquals(10001, count("npc_payout_retirements"));
        assertEquals(0, rents().serverSpentCents(DAY), "retired history is not scanned");
        assertEquals(Map.of(), pendingLifetime(DAY));
        // Without their retirement proof the same rows trip the bound.
        execute("DELETE FROM npc_payout_retirements");
        var thrown = assertThrows(SQLException.class, () -> rents().serverSpentCents(DAY));
        assertEquals("Rent outstanding ledger requires bounded maintenance", thrown.getMessage());
    }

    @Test
    void anUnprovedFailedLabelWithoutARetirementRowStillCounts() throws Exception {
        var limits = limits(15000, 4500, 1500);
        var rent = rents().reserve(DAY, enabled(), limits);
        execute("UPDATE npc_economy_events SET state='FAILED',paid_amount_cents=0,claimed_at=CURRENT_TIMESTAMP(3),"
                + "finalized_at=CURRENT_TIMESTAMP(3) WHERE event_id=?", rent.getFirst().eventId());
        closeDay();
        assertEquals(600L, pendingLifetime(NEXT).get(rent.getFirst().property().propertyId()),
                "a FAILED label is not proof; only a retirement row is");
        assertTrue(rents().retireStale(NEXT, 500).stream().noneMatch(r -> r.eventId() == rent.getFirst().eventId()),
                "a non-PENDING row is never a candidate");
    }

    @Test
    void retirementUnblocksResumeOfAPausedHome() throws Exception {
        var limits = limits(15000, 4500, 1500);
        var rent = rents().reserve(DAY, enabled(), limits);
        UUID paused = properties.getFirst();
        for (var row : rent) if (!row.property().propertyId().equals(paused)) assertTrue(pay(row));
        var repo = new PropertyRepository(database);
        assertTrue(repo.disableExpected(property(paused)));
        Property off = property(paused);
        assertThrows(PropertyRepository.ResumeRefused.class, () -> repo.resumeExpected(off, off.ownerUuid()));
        closeDay();
        assertEquals(1, rents().retireStale(NEXT, 500).size());
        assertTrue(repo.resumeExpected(off, off.ownerUuid()).enabled());
    }

    @Test
    void shippedConfigTurnsRetirementOnAndTheBatchRoutesThroughTheGatedStep() throws Exception {
        try (var input = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(input);
            var config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(input, StandardCharsets.UTF_8));
            assertEquals("economy.payout-retirement-enabled", UtilityPaymentRuntime.PAYOUT_RETIREMENT);
            assertEquals(Boolean.TRUE, config.get(UtilityPaymentRuntime.PAYOUT_RETIREMENT));
        }
        String code = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/prosperitysmp/npc/economy/UtilityPaymentRuntime.java"));
        assertTrue(code.contains("boolean retire = payoutRetirementActive();"));
        // Re-read on main after the fingerprint hop, right before the SQL step: off mid-batch means off.
        assertTrue(code.contains("boolean retireNow = retire && payoutRetirementActive();"));
        assertTrue(code.contains("return reserveRentBatchCounted(rents, retireNow, holdNow, day, properties, limits, plugin.getLogger()::warning);"));
        assertEquals(1, code.split(java.util.regex.Pattern.quote("rents.reserveCounting(day, properties, limits, holdDueRent)"), -1).length - 1,
                "the batch reserves only through reserveRentBatch");
        // Off writes nothing even with a dead row present. On, the same row is retired.
        var limits = limits(15000, 4500, 1500);
        rents().reserve(DAY, enabled(), limits);
        closeDay();
        assertTrue(UtilityPaymentRuntime.reserveRentBatch(rents(), false, NEXT, enabled(), limits, ignored -> { }).isEmpty());
        assertEquals(0, count("npc_payout_retirements"));
        assertEquals(4, count(RENT + " AND state='PENDING'"));
        assertEquals(4, UtilityPaymentRuntime.retireStale(rents(), NEXT, ignored -> { }));
        assertEquals(4, count("npc_payout_retirements WHERE reason='EXPIRED'"));
    }

    /**
     * Default on is safe for any server, including one with existing rows. With payouts never used there is
     * nothing it can touch: every pass writes nothing. With payouts in use it never touches a payout still live on
     * its day for the current owner, nor any payout with a transfer attempt; it only retires the never-attempted
     * ones that can no longer be paid, moving no money and only releasing funding that could never be paid out.
     */
    @Test
    void defaultOnIsInertWithoutPayoutsAndNeverTouchesALiveOrAttemptedPayout() throws Exception {
        // Parent feature off: no rent or appreciation payout was ever reserved (only utility bills exist).
        assertEquals(0, count(RENT));
        assertEquals(0, count(APPRECIATION));
        var untouched = snapshot();
        assertTrue(rents().retireStale(DAY, 500).isEmpty());
        assertTrue(rents().retireStale(NEXT.plusDays(30), 500).isEmpty());
        assertEquals("Payout retirement: nothing to retire. No money moved.",
                UtilityPaymentRuntime.retirementPass(rents(), NEXT.plusDays(30), warning -> fail(warning)));
        for (UUID id : properties) {
            try (Connection c = database.getConnection()) {
                c.setAutoCommit(false);
                try {
                    assertTrue(RentReservationRepository.retireStaleFor(c, id, NEXT.plusDays(30), 500).isEmpty());
                    c.commit();
                } finally { c.setAutoCommit(true); }
            }
        }
        assertEquals(untouched, snapshot(), "nothing written");

        // Payouts in use: today's reservations for the current owners are live and never retired on their day.
        var limits = limits(15000, 4500, 1500);
        rents().reserve(DAY, enabled(), limits);
        assertEquals(4, count(RENT + " AND state='PENDING'"));
        var live = snapshot();
        assertTrue(rents().retireStale(DAY, 500).isEmpty());
        assertEquals(live, snapshot());

        // One payout is attempted (beginDebit committed); after its day only the three unattempted ones retire.
        var attempted = rents().pending(DAY, enabled()).getFirst();
        assertTrue(journal().beginDebit(new Handle(UUID.randomUUID(), attempted.eventId(), UUID.randomUUID(), "b".repeat(64)))
                .newlyStarted());
        closeDay();
        String eventSql = "SELECT event_id,state,paid_amount_cents,claimed_at,finalized_at FROM npc_economy_events WHERE event_id=?";
        var attemptedBefore = rows(eventSql, attempted.eventId());
        var retired = rents().retireStale(NEXT, 500);
        assertEquals(3, retired.size());
        assertTrue(retired.stream().noneMatch(r -> r.eventId() == attempted.eventId()));
        assertTrue(retired.stream().allMatch(r -> r.reason() == RetirementReason.EXPIRED));
        assertEquals(attemptedBefore, rows(eventSql, attempted.eventId()), "the attempted payout is left for staff");
        assertEquals(0, count("npc_payout_retirements WHERE event_id=?", attempted.eventId()));
        assertEquals(1, count("npc_transfer_attempts WHERE event_id=?", attempted.eventId()));
        assertEquals(0, count("npc_economy_events WHERE source='npc-property-rent' AND state='FAILED' AND paid_amount_cents<>0"),
                "retired rows record no payment");
    }

    /** With no enabled home the rent batch returns before its SQL step, so it can never retire. */
    @Test
    void everyHomePausedStillRetiresThroughTheStandalonePassAndThenResumes() throws Exception {
        var limits = limits(15000, 4500, 1500);
        assertEquals(4, rents().reserve(DAY, enabled(), limits).size(), "floor-deferred: reserved, never attempted");
        var repo = new PropertyRepository(database);
        for (UUID id : properties) assertTrue(repo.disableExpected(property(id)));
        List<Property> paused = enabled();
        assertTrue(paused.stream().noneMatch(Property::enabled));
        // requestRentBatch's early exit: no enabled, non-away candidate, so its in-batch pass is unreachable.
        assertTrue(UtilityPaymentRuntime.rentCandidates(paused, java.util.Set.of()).isEmpty());
        closeDay();
        // Before the pass, each home's own dead reservation refuses its resume and nothing retires it.
        for (Property off : paused)
            assertThrows(PropertyRepository.ResumeRefused.class, () -> repo.resumeExpected(off, off.ownerUuid()));
        assertEquals(0, count("npc_payout_retirements"));
        // The daily cycle's standalone pass (phase 4, before the rent batch) does not depend on any payable home.
        var warnings = new ArrayList<String>();
        assertEquals("Payout retirement: 4 never-attempted payout reservations retired. No money moved.",
                UtilityPaymentRuntime.retirementPass(rents(), NEXT, warnings::add));
        assertEquals(4, count("npc_payout_retirements WHERE kind='RENT' AND reason='EXPIRED' AND economy_day=? AND retired_on=?", DAY, NEXT));
        assertEquals(4, count(RENT + " AND state='FAILED' AND paid_amount_cents=0"));
        assertEquals(0, count("npc_transfer_attempts a JOIN npc_rent_reservations r ON r.event_id=a.event_id"), "no money call was started");
        // After the pass, the plain (flag-off) resume succeeds for every home.
        for (Property off : paused) assertTrue(repo.resumeExpected(off, off.ownerUuid()).enabled());
        var before = snapshot();
        assertEquals("Payout retirement: nothing to retire. No money moved.",
                UtilityPaymentRuntime.retirementPass(rents(), NEXT, warnings::add));
        assertEquals(before, snapshot(), "a replayed pass writes nothing");
    }

    @Test
    void everyOwnerAwayStillRetiresThroughTheStandalonePass() throws Exception {
        var limits = limits(15000, 4500, 1500);
        assertEquals(4, rents().reserve(DAY, enabled(), limits).size());
        closeDay();
        var away = java.util.Set.copyOf(properties);
        // requestRentBatch's all-away exit (ALL_AWAY_REPLY) also returns before its in-batch pass.
        assertTrue(UtilityPaymentRuntime.rentCandidates(enabled(), away).isEmpty());
        assertFalse(away.isEmpty());
        assertEquals("Payout retirement: 4 never-attempted payout reservations retired. No money moved.",
                UtilityPaymentRuntime.retirementPass(rents(), NEXT, ignored -> { }));
        assertEquals(4, count("npc_payout_retirements WHERE reason='EXPIRED'"));
        assertEquals(Map.of(), pendingLifetime(NEXT), "their lifetime funding is released");
    }

    @Test
    void resumeRetiresThePausedHomesOwnDeadRowsOnlyWhenTheFlagIsOn() throws Exception {
        var limits = limits(15000, 4500, 1500);
        assertEquals(4, rents().reserve(DAY, enabled(), limits).size());
        UUID pausedId = properties.getFirst();
        var repo = new PropertyRepository(database);
        assertTrue(repo.disableExpected(property(pausedId)));
        Property off = property(pausedId);
        // Same day: the row is still payable to the current owner, so even flag-on resume is refused (documented).
        var sameDay = PropertyService.resumeRetirement(Boolean.TRUE, true, pausedId, DAY, ignored -> { });
        assertThrows(PropertyRepository.ResumeRefused.class, () -> repo.resumeExpected(off, off.ownerUuid(), sameDay));
        assertEquals(0, count("npc_payout_retirements"));
        closeDay();
        // Off: anything but a real Boolean true is the exact two-argument resume, still refused.
        assertNull(PropertyService.resumeRetirement(null, true, pausedId, NEXT, ignored -> { }));
        assertNull(PropertyService.resumeRetirement(Boolean.FALSE, true, pausedId, NEXT, ignored -> { }));
        assertNull(PropertyService.resumeRetirement("true", true, pausedId, NEXT, ignored -> { }));
        assertThrows(PropertyRepository.ResumeRefused.class, () -> repo.resumeExpected(off, off.ownerUuid(), null));
        assertEquals(0, count("npc_payout_retirements"));
        // On: only this home's dead row is retired, in the resume transaction, and the resume commits.
        var log = new ArrayList<String>();
        var prepared = PropertyService.resumeRetirement(Boolean.TRUE, true, pausedId, NEXT, log::add);
        assertTrue(repo.resumeExpected(off, off.ownerUuid(), prepared).enabled());
        assertEquals(1, count("npc_payout_retirements"));
        assertEquals(1, count("npc_payout_retirements WHERE property_id=? AND kind='RENT' AND reason='EXPIRED' AND economy_day=?", pausedId, DAY));
        assertEquals(3, count(RENT + " AND state='PENDING'"), "other homes' rows are left to the daily pass");
        assertEquals(List.of("Resume of property " + pausedId
                + " retires 1 never-attempted payout reservations if it commits. No money moved."), log);
    }

    /**
     * A paused home sold today. Paused rows are outside reconciliation, so SQL still holds the old owner. Resume
     * writes the fresh ARM owner (epoch + 1) first and retires against it, in one transaction. Comparing against
     * the stale owner would find the old owner's never-attempted current-day rent still live and refuse until the
     * row expired the next day.
     */
    @Test
    void pausedHomeSoldTodayResumesSameDayRetiringTheOldOwnersUnattemptedRent() throws Exception {
        var limits = limits(15000, 4500, 1500);
        var rent = rents().reserve(DAY, enabled(), limits);
        assertEquals(4, rent.size(), "floor-deferred: reserved, never attempted");
        UUID pausedId = properties.getFirst();
        long dead = rent.stream().filter(r -> r.property().propertyId().equals(pausedId)).findFirst().orElseThrow().eventId();
        var repo = new PropertyRepository(database);
        assertTrue(repo.disableExpected(property(pausedId)));
        Property off = property(pausedId);
        UUID oldOwner = off.ownerUuid(), buyer = new UUID(0, 3397);
        assertEquals(0, off.ownershipEpoch());
        var log = new ArrayList<String>();
        var prepared = PropertyService.resumeRetirement(Boolean.TRUE, true, pausedId, DAY, log::add);
        Property resumed = repo.resumeExpected(off, buyer, prepared);
        assertTrue(resumed.enabled());
        assertEquals(buyer, resumed.ownerUuid());
        assertEquals(1, resumed.ownershipEpoch());
        assertEquals(resumed, property(pausedId));
        assertEquals(1, count("npc_payout_retirements"));
        assertEquals(1, count("npc_payout_retirements WHERE event_id=? AND kind='RENT' AND reason='OWNER_CHANGED' AND property_id=? "
                + "AND economy_day=? AND retired_on=? AND retired_owner=? AND retired_epoch=0 AND current_owner=? AND current_epoch=1",
                dead, pausedId, DAY, DAY, oldOwner, buyer));
        assertEquals(1, count("npc_economy_events WHERE event_id=? AND state='FAILED' AND paid_amount_cents=0", dead));
        assertEquals(1, count("npc_property_ownership WHERE property_id=? AND old_owner_uuid=? AND new_owner_uuid=? "
                + "AND ownership_epoch=1 AND source_snapshot='resume-current-arm-owner'", pausedId, oldOwner, buyer));
        assertEquals(3, count(RENT + " AND state='PENDING'"), "other homes' rows are untouched");
        assertEquals(0, count("npc_transfer_attempts WHERE event_id=?", dead), "no money call was started");
        assertEquals(List.of("Resume of property " + pausedId
                + " retires 1 never-attempted payout reservations if it commits. No money moved."), log);
        // Never payable afterwards: the journal refuses the retired event and writes nothing.
        var refused = assertThrows(SQLException.class, () -> journal().beginDebit(
                new Handle(UUID.randomUUID(), dead, UUID.randomUUID(), "e".repeat(64))));
        assertEquals("Transfer requires an untouched pending event", refused.getMessage());
        assertEquals(0, count("npc_transfer_attempts WHERE event_id=?", dead));
    }

    /** With the flag off or the parent off, resume is refused and nothing is written. */
    @Test
    void pausedHomeSoldTodayStillRefusesWithRetirementOffAndWritesNothing() throws Exception {
        var limits = limits(15000, 4500, 1500);
        assertEquals(4, rents().reserve(DAY, enabled(), limits).size());
        UUID pausedId = properties.getFirst();
        var repo = new PropertyRepository(database);
        assertTrue(repo.disableExpected(property(pausedId)));
        Property off = property(pausedId);
        UUID buyer = new UUID(0, 3396);
        var before = snapshot();
        var ownership = rows("SELECT * FROM npc_property_ownership ORDER BY id");
        assertNull(PropertyService.resumeRetirement(null, true, pausedId, DAY, line -> fail(line)));
        assertNull(PropertyService.resumeRetirement(Boolean.FALSE, true, pausedId, DAY, line -> fail(line)));
        assertNull(PropertyService.resumeRetirement("true", true, pausedId, DAY, line -> fail(line)));
        assertNull(PropertyService.resumeRetirement(Boolean.TRUE, false, pausedId, DAY, line -> fail(line)), "parent off");
        assertThrows(PropertyRepository.ResumeRefused.class, () -> repo.resumeExpected(off, buyer));
        assertThrows(PropertyRepository.ResumeRefused.class, () -> repo.resumeExpected(off, buyer, null));
        assertEquals(off, property(pausedId), "owner, epoch and paused state unchanged");
        assertEquals(before, snapshot(), "nothing retired, nothing written");
        assertEquals(ownership, rows("SELECT * FROM npc_property_ownership ORDER BY id"), "no owner write");
        // With retirement active the same fixture resumes for the buyer.
        assertTrue(repo.resumeExpected(off, buyer, PropertyService.resumeRetirement(Boolean.TRUE, true, pausedId, DAY, ignored -> { }))
                .enabled());
        assertEquals(1, count("npc_payout_retirements WHERE property_id=? AND reason='OWNER_CHANGED'", pausedId));
    }

    /** An attempted row is never retired, so the resume refuses and its owner write rolls back with it. */
    @Test
    void pausedHomeSoldTodayWithAnAttemptedRentStillRefusesAndRollsTheOwnerWriteBack() throws Exception {
        var limits = limits(15000, 4500, 1500);
        var rent = rents().reserve(DAY, enabled(), limits);
        UUID pausedId = rent.getFirst().property().propertyId();
        assertTrue(journal().beginDebit(new Handle(UUID.randomUUID(), rent.getFirst().eventId(), UUID.randomUUID(), "d".repeat(64)))
                .newlyStarted());
        var repo = new PropertyRepository(database);
        assertTrue(repo.disableExpected(property(pausedId)));
        Property off = property(pausedId);
        var before = snapshot();
        var attempts = rows("SELECT * FROM npc_transfer_attempts ORDER BY attempt_id");
        var ownership = rows("SELECT * FROM npc_property_ownership ORDER BY id");
        var log = new ArrayList<String>();
        assertThrows(PropertyRepository.ResumeRefused.class, () -> repo.resumeExpected(off, new UUID(0, 3395),
                PropertyService.resumeRetirement(Boolean.TRUE, true, pausedId, DAY, log::add)));
        assertEquals(off, property(pausedId), "owner write rolled back: old owner, old epoch, still paused");
        assertEquals(before, snapshot());
        assertEquals(attempts, rows("SELECT * FROM npc_transfer_attempts ORDER BY attempt_id"));
        assertEquals(ownership, rows("SELECT * FROM npc_property_ownership ORDER BY id"));
        assertEquals(0, count("npc_payout_retirements"));
        assertEquals(List.of(), log);
    }

    /** The closed day's appreciation is live until tomorrow; bound to the old owner it retires on resume too. */
    @Test
    void pausedHomeSoldTodayRetiresTheOldOwnersClosedDayAppreciationOnResume() throws Exception {
        var limits = limits(15000, 4500, 1500);
        payRent(limits);
        closeDay();
        var appreciation = rents().reserveAppreciation(DAY, enabled(), limits, TOP);
        assertEquals(4, appreciation.size());
        UUID pausedId = properties.getFirst();
        long dead = appreciation.stream().filter(r -> r.property().propertyId().equals(pausedId)).findFirst().orElseThrow().eventId();
        var repo = new PropertyRepository(database);
        assertTrue(repo.disableExpected(property(pausedId)));
        Property off = property(pausedId);
        UUID buyer = new UUID(0, 3394);
        // Same owner: still payable today, so the documented refusal stands (nothing retired).
        assertThrows(PropertyRepository.ResumeRefused.class, () -> repo.resumeExpected(off, off.ownerUuid(),
                PropertyService.resumeRetirement(Boolean.TRUE, true, pausedId, NEXT, ignored -> { })));
        assertEquals(0, count("npc_payout_retirements"));
        Property resumed = repo.resumeExpected(off, buyer, PropertyService.resumeRetirement(Boolean.TRUE, true, pausedId, NEXT, ignored -> { }));
        assertTrue(resumed.enabled());
        assertEquals(buyer, resumed.ownerUuid());
        assertEquals(1, count("npc_payout_retirements WHERE event_id=? AND kind='APPRECIATION' AND reason='OWNER_CHANGED' "
                + "AND economy_day=? AND retired_owner=? AND current_owner=? AND current_epoch=1", dead, DAY, off.ownerUuid(), buyer));
        assertEquals(1, count("npc_payout_retirements"));
        assertEquals(3, count(APPRECIATION + " AND state='PENDING'"));
    }

    /** Another unsettled event (not a payout) still refuses; the retirement and the owner write roll back together. */
    @Test
    void pausedHomeSoldWithAnUnrelatedUnsettledBillRefusesAndRollsBothBack() throws Exception {
        var limits = limits(15000, 4500, 1500);
        var rent = rents().reserve(DAY, enabled(), limits);
        UUID pausedId = properties.getFirst();
        long retirable = rent.stream().filter(r -> r.property().propertyId().equals(pausedId)).findFirst().orElseThrow().eventId();
        closeDay();
        Property current = property(pausedId);
        utilities().preparePayment(UUID.randomUUID(), pausedId, current.ownerUuid(), current.ownershipEpoch(), NEXT);
        var repo = new PropertyRepository(database);
        assertTrue(repo.disableExpected(property(pausedId)));
        Property off = property(pausedId);
        var before = snapshot();
        var ownership = rows("SELECT * FROM npc_property_ownership ORDER BY id");
        assertThrows(PropertyRepository.ResumeRefused.class, () -> repo.resumeExpected(off, new UUID(0, 3393),
                PropertyService.resumeRetirement(Boolean.TRUE, true, pausedId, NEXT, ignored -> { })));
        assertEquals(off, property(pausedId));
        assertEquals(before, snapshot(), "the retirement rolled back");
        assertEquals(ownership, rows("SELECT * FROM npc_property_ownership ORDER BY id"), "the owner write rolled back");
        assertEquals(1, count("npc_economy_events WHERE event_id=? AND state='PENDING'", retirable));
    }

    /**
     * Default-on retirement must be inert with its parent off. With payments off (the shipped config) and an
     * expired never-attempted reservation, resume is refused and nothing is retired. With the parent on, the same
     * fixture retires the row and resumes.
     */
    @Test
    void paymentsOffResumeRefusesExactlyAsBeforeWithAnExpiredReservation() throws Exception {
        var limits = limits(15000, 4500, 1500);
        assertEquals(4, rents().reserve(DAY, enabled(), limits).size(), "never attempted");
        UUID pausedId = properties.getFirst();
        var repo = new PropertyRepository(database);
        assertTrue(repo.disableExpected(property(pausedId)));
        Property off = property(pausedId);
        closeDay(); // the reservation is from an earlier day: retirable if retirement were active
        String eventSql = "SELECT e.event_id,e.state,e.paid_amount_cents,e.claimed_at,e.finalized_at FROM npc_economy_events e "
                + "JOIN npc_rent_reservations r ON r.event_id=e.event_id WHERE r.property_id=?";
        var eventBefore = rows(eventSql, pausedId);
        assertEquals(1, eventBefore.size());
        assertEquals("PENDING", eventBefore.getFirst().get(1));
        var before = snapshot();

        // Parent off, every way: payments off, payments unset with the legacy smoke flag off, either switch off.
        boolean[][] switches = {{true, true}, {false, true}, {true, false}};
        Object[][] optIns = {{Boolean.FALSE, null}, {null, Boolean.FALSE}, {null, null}, {Boolean.FALSE, Boolean.TRUE}};
        for (Object[] optIn : optIns)
            assertFalse(UtilityPaymentRuntime.payoutRetirementActive(Boolean.TRUE, true, true, optIn[0], optIn[1]));
        for (boolean[] sw : switches) if (!(sw[0] && sw[1]))
            assertFalse(UtilityPaymentRuntime.payoutRetirementActive(Boolean.TRUE, sw[0], sw[1], Boolean.TRUE, null));
        boolean parent = UtilityPaymentRuntime.payoutRetirementActive(Boolean.TRUE, true, true, Boolean.FALSE, null);
        var log = new ArrayList<String>();
        var prepared = PropertyService.resumeRetirement(Boolean.TRUE, parent, pausedId, NEXT, log::add);
        assertNull(prepared, "parent off: no preparation, so resumeCoordinated takes the two-argument resume");
        // With a null preparation, resumeCoordinated takes the plain two-argument resume.
        assertThrows(PropertyRepository.ResumeRefused.class, () -> repo.resumeExpected(off, off.ownerUuid()));
        assertThrows(PropertyRepository.ResumeRefused.class, () -> repo.resumeExpected(off, off.ownerUuid(), prepared));
        assertFalse(property(pausedId).enabled(), "still paused");
        assertEquals(eventBefore, rows(eventSql, pausedId), "the expired reservation stays unsettled");
        assertEquals(0, count("npc_payout_retirements"));
        assertEquals(before, snapshot(), "nothing written");
        assertEquals(List.of(), log);

        // With the parent on, the same fixture retires this home's row and the resume commits.
        assertTrue(UtilityPaymentRuntime.payoutRetirementActive(Boolean.TRUE, true, true, Boolean.TRUE, null));
        assertTrue(UtilityPaymentRuntime.payoutRetirementActive(Boolean.TRUE, true, true, null, Boolean.TRUE), "legacy smoke opt-in");
        var active = PropertyService.resumeRetirement(Boolean.TRUE, true, pausedId, NEXT, log::add);
        assertNotNull(active);
        assertTrue(repo.resumeExpected(off, off.ownerUuid(), active).enabled());
        assertEquals(1, count("npc_payout_retirements WHERE property_id=? AND reason='EXPIRED'", pausedId));
        assertEquals("FAILED", rows(eventSql, pausedId).getFirst().get(1));
        assertEquals(1, log.size());
    }

    @Test
    void shippedConfigLeavesResumeRetirementInertAndResumeUsesTheSharedParentGate() throws Exception {
        try (var input = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(input);
            var config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(input, StandardCharsets.UTF_8));
            // Shipped: retirement on, payments off. Even with both switches on, retirement is not active.
            assertEquals(Boolean.TRUE, config.get(UtilityPaymentRuntime.PAYOUT_RETIREMENT));
            assertEquals(Boolean.FALSE, config.get(UtilityPaymentRuntime.PAYMENTS_OPT_IN));
            assertFalse(UtilityPaymentRuntime.payoutRetirementActive(config.get(UtilityPaymentRuntime.PAYOUT_RETIREMENT),
                    true, true, config.get(UtilityPaymentRuntime.PAYMENTS_OPT_IN), config.get(UtilityPaymentRuntime.SMOKE_OPT_IN)));
        }
        String service = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/prosperitysmp/npc/property/PropertyService.java"));
        String resume = service.substring(service.indexOf("public boolean resumeCoordinated("));
        resume = resume.substring(0, resume.indexOf("Property resumed;"));
        int parent = resume.indexOf("final boolean retirementParent = payments != null && payments.payoutRetirementActive();");
        int call = resume.indexOf("retirementParent, paused.propertyId(), economyToday(), plugin.getLogger()::info);");
        assertTrue(parent > 0 && call > parent, "resume computes the shared parent gate on main and passes it");
        // Every retirement entry point goes through payoutRetirementActive; there is no flag-only instance accessor.
        String code = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/prosperitysmp/npc/economy/UtilityPaymentRuntime.java"));
        assertFalse(code.contains("public boolean payoutRetirementEnabled()"));
        assertFalse(code.contains("Boolean.TRUE.equals(plugin.getConfig().get(PAYOUT_RETIREMENT))"));
        assertEquals(1, code.split(java.util.regex.Pattern.quote("plugin.getConfig().get(PAYOUT_RETIREMENT)"), -1).length - 1,
                "the raw flag is read only inside payoutRetirementActive()");
        assertFalse(code.contains("payoutRetirementEnabled(plugin.getConfig().get(PAYOUT_RETIREMENT))"),
                "the raw flag is never checked alone");
    }

    @Test
    void aRefusedResumeRollsItsRetirementBackAndAttemptedRowsStillNeedStaff() throws Exception {
        var limits = limits(15000, 4500, 1500);
        var rent = rents().reserve(DAY, enabled(), limits);
        UUID attempted = rent.get(0).property().propertyId(), unsettled = rent.get(1).property().propertyId();
        // Home A: its rent debit intent was written (an attempt exists), so it is never provably unattempted.
        assertTrue(journal().beginDebit(new Handle(UUID.randomUUID(), rent.get(0).eventId(), UUID.randomUUID(), "d".repeat(64))).newlyStarted());
        closeDay();
        // Home B: a retirable dead rent row plus an unrelated unsettled utility bill for today.
        Property b = property(unsettled);
        utilities().preparePayment(UUID.randomUUID(), unsettled, b.ownerUuid(), b.ownershipEpoch(), NEXT);
        var repo = new PropertyRepository(database);
        assertTrue(repo.disableExpected(property(attempted)));
        assertTrue(repo.disableExpected(property(unsettled)));
        var attempts = rows("SELECT * FROM npc_transfer_attempts ORDER BY attempt_id");
        Property offA = property(attempted), offB = property(unsettled);
        assertThrows(PropertyRepository.ResumeRefused.class, () -> repo.resumeExpected(offA, offA.ownerUuid(),
                PropertyService.resumeRetirement(Boolean.TRUE, true, attempted, NEXT, ignored -> { })));
        assertThrows(PropertyRepository.ResumeRefused.class, () -> repo.resumeExpected(offB, offB.ownerUuid(),
                PropertyService.resumeRetirement(Boolean.TRUE, true, unsettled, NEXT, ignored -> { })));
        assertEquals(0, count("npc_payout_retirements"), "B's retirement rolled back with its refused resume");
        assertEquals(1, count("npc_economy_events WHERE event_id=? AND state='PENDING'", rent.get(1).eventId()));
        assertEquals(attempts, rows("SELECT * FROM npc_transfer_attempts ORDER BY attempt_id"));
        assertFalse(property(attempted).enabled());
        assertFalse(property(unsettled).enabled());
        // The standalone pass still never touches the attempted row.
        UtilityPaymentRuntime.retirementPass(rents(), NEXT, ignored -> { });
        assertEquals(0, count("npc_payout_retirements WHERE event_id=?", rent.get(0).eventId()));
        assertThrows(PropertyRepository.ResumeRefused.class, () -> repo.resumeExpected(offA, offA.ownerUuid(),
                PropertyService.resumeRetirement(Boolean.TRUE, true, attempted, NEXT, ignored -> { })));
    }

    @Test
    void perPropertyRetirementNeedsTheCallersTransaction() throws Exception {
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(true);
            assertThrows(IllegalStateException.class, () -> RentReservationRepository.retireStaleFor(c, properties.getFirst(), NEXT, 10));
            c.setAutoCommit(false);
            try {
                assertThrows(IllegalArgumentException.class, () -> RentReservationRepository.retireStaleFor(c, properties.getFirst(), NEXT, 0));
                assertThrows(IllegalArgumentException.class, () -> RentReservationRepository.retireStaleFor(c, properties.getFirst(), NEXT, 501));
                assertTrue(RentReservationRepository.retireStaleFor(c, properties.getFirst(), NEXT, 10).isEmpty());
            } finally { c.rollback(); }
        }
    }

    @Test
    void theDailyCycleRetiresBeforeTheRentBatchUnderTheFenceAndOnlyWhenOn() throws Exception {
        assertTrue(com.prosperitysmp.npc.ProsperityNpcPlugin.retireBeforeRent(4, false, true));
        assertFalse(com.prosperitysmp.npc.ProsperityNpcPlugin.retireBeforeRent(4, false, false), "off: phase 4 is the rent batch alone");
        assertFalse(com.prosperitysmp.npc.ProsperityNpcPlugin.retireBeforeRent(4, true, true), "re-entry after the pass runs the batch");
        for (int phase = 0; phase <= 5; phase++) if (phase != 4)
            assertFalse(com.prosperitysmp.npc.ProsperityNpcPlugin.retireBeforeRent(phase, false, true));
        assertTrue(UtilityPaymentRuntime.payoutRetirementEnabled(Boolean.TRUE));
        assertFalse(UtilityPaymentRuntime.payoutRetirementEnabled(null));
        assertFalse(UtilityPaymentRuntime.payoutRetirementEnabled(Boolean.FALSE));
        assertFalse(UtilityPaymentRuntime.payoutRetirementEnabled("true"));
        // The fence the pass takes is the one every rent, appreciation and utility batch holds.
        var fence = new FinancialFence();
        var batch = fence.acquire("property-rent");
        assertThrows(IllegalStateException.class, () -> fence.acquire("payout-retirement"));
        batch.releaseVerified();
        fence.acquire("payout-retirement").releaseVerified();

        String plugin = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/prosperitysmp/npc/ProsperityNpcPlugin.java"));
        String phase = plugin.substring(plugin.indexOf("private void runDailyPhase(int phase,java.util.function.BooleanSupplier authority,"
                + "java.util.function.Consumer<String> reply,boolean retired)"));
        phase = phase.substring(0, phase.indexOf("/** Reloads and re-validates the economy limits"));
        int gate = phase.indexOf("(phase==4 && !killSwitches.isEconomyEnabled())");
        int pass = phase.indexOf("utilityPayments.requestPayoutRetirement(authority,");
        int batchCall = phase.indexOf("case 4->utilityPayments.requestRentBatch(authority,next);");
        assertTrue(gate > 0 && pass > gate && batchCall > pass, "kill switches first, then the pass, then the batch");
        assertTrue(phase.contains("if(retireBeforeRent(phase,retired,utilityPayments.payoutRetirementActive())) {"));
        assertTrue(phase.contains("runDailyPhase(4,authority,reply,true);"), "the batch re-enters every gate after the hop");

        String code = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/prosperitysmp/npc/economy/UtilityPaymentRuntime.java"));
        String request = code.substring(code.indexOf("public boolean requestPayoutRetirement("));
        request = request.substring(0, request.indexOf("public boolean payoutRetirementActive()"));
        int flag = request.indexOf("if (!payoutRetirementActive() || busy || recovery || !ready(authorized)) return false;");
        int fenced = request.indexOf("financialLease = plugin.financialFence().acquire(\"payout-retirement\");");
        int busy = request.indexOf("busy = true;");
        int submit = request.indexOf("retirementPass(rents, day, plugin.getLogger()::warning), worker)");
        assertTrue(flag > 0 && fenced > flag && busy > fenced && submit > busy, "flag, fence and busy are all held before any SQL");
        assertTrue(request.contains("|| busy || recovery || !ready(authorized)) return false;"));
        for (String eligibility : List.of("rentCandidates", "getEnrolled", "ownerAway", "accountingReady", "requireLimits"))
            assertFalse(request.contains(eligibility), "the pass must not depend on payout eligibility: " + eligibility);
        assertTrue(request.contains("busy = false; releaseFinancial();"));

        String service = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/prosperitysmp/npc/property/PropertyService.java"));
        assertTrue(service.contains("plugin.getConfig().get(com.prosperitysmp.npc.economy.UtilityPaymentRuntime.PAYOUT_RETIREMENT),"));
        assertTrue(service.contains("try { resumed = retire == null ? repo.resumeExpected(paused, owner.ownerId())"));
    }

    @Test
    void retirementScopeIsBounded() {
        assertThrows(IllegalArgumentException.class, () -> rents().retireStale(NEXT, 0));
        assertThrows(IllegalArgumentException.class, () -> rents().retireStale(NEXT, 501));
        assertThrows(NullPointerException.class, () -> rents().retireStale(null, 10));
    }

    @SuppressWarnings("unchecked")
    private Map<UUID, Long> pendingLifetime(LocalDate day) throws Exception {
        var spent = RentReservationRepository.class.getDeclaredMethod("spent", Connection.class, LocalDate.class);
        spent.setAccessible(true);
        try (Connection c = database.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setAutoCommit(false);
            try {
                Object result = spent.invoke(rents(), c, day);
                var accessor = result.getClass().getDeclaredMethod("pendingLifetime");
                accessor.setAccessible(true);
                return Map.copyOf((Map<UUID, Long>) accessor.invoke(result));
            } catch (InvocationTargetException failure) {
                if (failure.getCause() instanceof SQLException sql) throw sql;
                throw failure;
            } finally { c.rollback(); }
        }
    }

    private void closeDay() { clock.set(NEXT_NOW); }

    private static Property property(UUID id) throws SQLException {
        return new PropertyRepository(database).findById(id).orElseThrow();
    }

    private void payRent(EconomyLimits limits) throws SQLException {
        rents().reserve(DAY, enabled(), limits);
        int paid = 0;
        for (var row : rents().pending(DAY, enabled())) if (pay(row)) paid++;
        assertEquals(4, paid);
    }

    private static boolean pay(Reserved row) throws SQLException {
        Handle handle = new Handle(UUID.randomUUID(), row.eventId(), UUID.randomUUID(), "b".repeat(64));
        var start = journal().beginDebit(handle);
        if (!start.newlyStarted()) return false;
        double dollars = row.cents() / 100.0;
        assertEquals(TransferJournal.State.CREDIT_READY, journal().recordDebit(handle,
                new Observation(true, true, dollars, 100000.0, 100000.0 - dollars, false)).state());
        assertTrue(journal().beginCredit(handle).newlyStarted());
        assertEquals(TransferJournal.State.PAID, journal().recordCredit(handle,
                new Observation(true, true, dollars, 50.0, 50.0 + dollars, false)).state());
        return true;
    }

    private List<Property> enabled() {
        var repo = new PropertyRepository(database);
        return properties.stream().map(id -> {
            try { return repo.findById(id).orElseThrow(); }
            catch (SQLException failure) { throw new IllegalStateException(failure); }
        }).toList();
    }

    private static EconomyLimits limits(long server, long owner, long property) throws SQLException {
        try {
            var constructor = EconomyLimits.class.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            return (EconomyLimits) constructor.newInstance(server, owner, property, 600L, 300L, FLOOR, 5000L, 50000L, 0.9);
        } catch (ReflectiveOperationException failure) { throw new SQLException(failure); }
    }

    private RentReservationRepository rents() { return new RentReservationRepository(database, RESERVE, clock, ZONE, RESET, false); }
    private UtilityRepository utilities() { return new UtilityRepository(database, RESERVE, clock, ZONE, RESET); }
    private static TransferJournal journal() { return new TransferJournal(database, RESERVE); }

    /** Enrolled, one tenant, today's utility verified paid. */
    private UUID operating(UUID owner) throws SQLException {
        UUID id = UUID.randomUUID();
        new PropertyRepository(database).insertEnrollment(new Property(id, WORLD, "retire-" + id, "test", "room",
                4, 1000, 1000, owner, 0, 80, true), 5000);
        UUID tenant = UUID.randomUUID();
        assertTrue(new ResidentRepository(database).register(ResidentCatalogue.draft(tenant, "Mara Vale")));
        new TenantRepository(database).insert(new Tenant(tenant, "Mara Vale", id, 0, "STANDARD", "COOK", 50));
        payUtilityFor(id, owner, DAY);
        return id;
    }

    private void payUtilityFor(UUID id, LocalDate day) throws SQLException {
        payUtilityFor(id, property(id).ownerUuid(), day);
    }

    private void payUtilityFor(UUID id, UUID owner, LocalDate day) throws SQLException {
        UtilityRepository.Receipt receipt = utilities().preparePayment(UUID.randomUUID(), id, owner, 0, day).receipt();
        Instant at = clock.instant().plusSeconds(1);
        Handle handle = new Handle(UUID.randomUUID(), receipt.eventId(), UUID.randomUUID(), "a".repeat(64));
        double dollars = receipt.bill().cents() / 100.0;
        assertTrue(journal().beginDebit(handle).newlyStarted());
        assertEquals(TransferJournal.State.CREDIT_READY,
                journal().recordDebit(handle, new Observation(true, true, dollars, 1000.0, 1000.0 - dollars, false)).state());
        assertTrue(journal().beginCredit(handle).newlyStarted());
        assertEquals(TransferJournal.State.PAID,
                journal().recordCredit(handle, new Observation(true, true, dollars, 2000.0, 2000.0 + dollars, false)).state());
        execute("UPDATE npc_economy_events SET created_at=?,claimed_at=?,finalized_at=? WHERE event_id=?",
                receipt.bill().issuedAt(), at.minusMillis(4), at, receipt.eventId());
        execute("UPDATE npc_transfer_attempts SET created_at=?,credit_intent_at=?,updated_at=? WHERE event_id=?",
                at.minusMillis(4), at.minusMillis(2), at, receipt.eventId());
        execute("UPDATE npc_transfer_observations o JOIN npc_transfer_attempts a ON a.attempt_id=o.attempt_id "
                        + "SET o.observed_at=CASE WHEN o.leg='DEBIT' THEN ? ELSE ? END WHERE a.event_id=?",
                at.minusMillis(3), at.minusMillis(1), receipt.eventId());
        clock.set(at.plusMillis(1));
        assertEquals(UtilityRepository.State.PAID, utilities().reconcile(receipt.eventId()).orElseThrow().state());
    }

    private static List<List<String>> snapshot() throws SQLException {
        var result = new ArrayList<List<String>>();
        for (String table : List.of("npc_payout_retirements", "npc_appreciation_reservations", "npc_rent_reservations",
                "npc_rent_cohort", "npc_economy_events", "npc_transfer_attempts", "npc_transfer_observations", "npc_property_funding")) {
            result.add(List.of(table));
            var contents = rows("SELECT * FROM " + table);
            contents.sort(Comparator.comparing(Object::toString));
            result.addAll(contents);
        }
        return result;
    }

    private static long count(String tableAndWhere, Object... args) throws SQLException {
        return Long.parseLong(rows("SELECT COUNT(*) FROM " + tableAndWhere, args).getFirst().getFirst());
    }

    private static List<List<String>> rows(String sql, Object... args) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(30);
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<List<String>> result = new ArrayList<>();
                while (rs.next()) {
                    List<String> row = new ArrayList<>();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                        int type = rs.getMetaData().getColumnType(i);
                        Object value = type == Types.TIMESTAMP ? rs.getObject(i, LocalDateTime.class) : rs.getObject(i);
                        row.add(value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : value == null ? null : value.toString());
                    }
                    result.add(row);
                }
                return result;
            }
        }
    }

    private static void execute(String sql, Object... args) throws SQLException {
        try (Connection c = database.getConnection()) { execute(c, sql, args); }
    }

    private static void execute(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(60);
            bind(ps, args);
            ps.execute();
        }
    }

    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof UUID id) ps.setBytes(i + 1, Uuids.toBytes(id));
            else if (args[i] instanceof Instant at) ps.setObject(i + 1, LocalDateTime.ofInstant(at, ZoneOffset.UTC));
            else if (args[i] instanceof LocalDate day) ps.setString(i + 1, day.toString());
            else ps.setObject(i + 1, args[i]);
        }
    }

    private static void applyResource(String path) throws Exception {
        try (InputStream in = PayoutRetirementIT.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8))) execute(sql);
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;
        private MutableClock(Instant now) { this.now = now; }
        void set(Instant at) { now = at; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) {
            return new Clock() {
                @Override public ZoneId getZone() { return zone; }
                @Override public Clock withZone(ZoneId next) { return MutableClock.this.withZone(next); }
                @Override public Instant instant() { return now; }
            };
        }
        @Override public Instant instant() { return now; }
    }
}

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
import com.prosperitysmp.npc.economy.TransferJournal.Handle;
import com.prosperitysmp.npc.economy.TransferProof.Observation;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyRepository;
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
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RENT FIRST across batches on a real MariaDB (economy.appreciation-ledger-rent-hold-enabled). Batch 1 on day
 * NEXT reserves property A's rent and the floor defers it (left PENDING, unattempted). Batch 2 the same day
 * never lists A (its ARM owner read blipped, so isolation held it; or it was paused), so the per-batch
 * rentDeferred flag stays false. With the hold on, the appreciation step must still see A's outstanding rent in
 * the ledger and reserve or return nothing. Vault is replaced by fixture observations; nothing here moves money.
 */
@Timeout(300)
class AppreciationLedgerRentHoldIT {
    private static final Instant NOW = Instant.parse("2026-09-07T12:00:00.001Z");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 7);
    private static final Instant NEXT_NOW = NOW.plusSeconds(86_400);
    private static final LocalDate NEXT = DAY.plusDays(1);
    private static final ZoneId ZONE = ZoneId.of("America/Chicago");
    private static final int RESET = 4;
    private static final UUID WORLD = new UUID(0, 3400);
    private static final UUID RESERVE = new UUID(0, 3402);
    private static final long FLOOR = 3_500_000L;
    private static final AppreciationScore TOP = (c, p, at) -> OptionalDouble.of(100.0);
    private static DB mariaDb;
    private static Database database;
    private static List<String> tables;
    private MutableClock clock;
    /** a = the home whose current-day rent is outstanding; b = the only home batch 2 lists. */
    private UUID a, b;
    private EconomyLimits limits;

    @BeforeAll
    static void bootDatabase() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        try {
            mariaDb.start();
            String schema = "npc_ledger_hold_" + UUID.randomUUID().toString().replace("-", "");
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

    /** DAY: both homes' rent PAID (so both earn DAY's appreciation). NEXT: A's utility is paid so its rent can be reserved. */
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
        limits = limits(15000, 4500, 1500);
        var ids = new ArrayList<UUID>(List.of(operating(new UUID(0, 3410)), operating(new UUID(0, 3411))));
        ids.sort(Comparator.comparing(UUID::toString));
        a = ids.get(0); b = ids.get(1);
        var rent = rents().reserve(DAY, List.of(property(a), property(b)), limits);
        assertEquals(2, rent.size());
        for (var row : rent) assertTrue(pay(row));
        clock.set(NEXT_NOW);
        payUtilityFor(a, NEXT);
    }

    /** Batch 1 on NEXT: A's rent is reserved and the floor defers it, so it stays PENDING with no attempt. */
    private Reserved deferRentOfA() throws SQLException {
        var queued = UtilityPaymentRuntime.reserveRentBatch(rents(), false, NEXT, List.of(property(a)), limits, w -> { });
        assertEquals(1, queued.size());
        assertEquals(1, count("npc_economy_events WHERE event_id=? AND state='PENDING' AND claimed_at IS NULL", queued.getFirst().eventId()));
        assertEquals(0, count("npc_transfer_attempts WHERE event_id=?", queued.getFirst().eventId()));
        return queued.getFirst();
    }

    @Test
    void omittedDeferredRentHoldsTheWholeAppreciationPhaseWhenTheHoldIsOn() throws Exception {
        deferRentOfA();
        // Positive control: the ledger query does see the seeded row (a wrong source key would pass vacuously).
        assertTrue(rents().outstandingRent(NEXT));
        assertFalse(rents().outstandingRent(DAY), "DAY's rent is all PAID");
        var before = snapshot();
        // Batch 2: A was held by isolation, so only B is listed; the per-batch rentDeferred flag never saw A.
        var step = UtilityPaymentRuntime.appreciationStep(rents(), true, NEXT, List.of(property(b)), limits, TOP);
        assertEquals(Optional.empty(), step, "held: nothing returned for dispatch");
        assertEquals(0, count("npc_appreciation_reservations"), "held: nothing reserved");
        assertEquals(before, snapshot(), "a held step writes nothing");
        assertTrue(UtilityPaymentRuntime.appreciationLedgerHeld(true, true, step));
        assertTrue(UtilityPaymentRuntime.appreciationLedgerHeld(true, false, step), "turned off mid-hop: the mutex decision stands");
    }

    @Test
    void holdOffKeepsTheOldBehaviourAndPaysAppreciationPastTheOmittedRent() throws Exception {
        deferRentOfA();
        var step = UtilityPaymentRuntime.appreciationStep(rents(), false, NEXT, List.of(property(b)), limits, TOP);
        assertTrue(step.isPresent());
        var rows = step.get();
        assertEquals(1, rows.size());
        assertEquals(b, rows.getFirst().property().propertyId());
        assertEquals(300L, rows.getFirst().cents());
        assertEquals(1, count("npc_appreciation_reservations WHERE property_id=? AND economy_day=?", b, DAY));
        assertFalse(UtilityPaymentRuntime.appreciationLedgerHeld(false, false, step), "off before and after: old behaviour");
        assertTrue(UtilityPaymentRuntime.appreciationLedgerHeld(false, true, step), "switched on mid-hop: unverified, fail closed");
        // The old path is exactly reserveAppreciation then pendingAppreciation.
        assertTrue(rents().reserveAppreciation(DAY, List.of(property(b)), limits, TOP).isEmpty(), "replay reserves nothing");
        assertEquals(rows, rents().pendingAppreciation(DAY, List.of(property(b))));
    }

    @Test
    void appreciationQueuedByAnEarlierBatchIsNotReturnedWhileRentIsOutstanding() throws Exception {
        // An earlier batch reserved B's appreciation and left it PENDING (the live switch skipped it).
        var earlier = rents().reserveAppreciation(DAY, List.of(property(b)), limits, TOP);
        assertEquals(1, earlier.size());
        deferRentOfA();
        var step = UtilityPaymentRuntime.appreciationStep(rents(), true, NEXT, List.of(property(b)), limits, TOP);
        assertEquals(Optional.empty(), step, "queued appreciation is held too, not only new reservations");
        assertEquals(earlier, rents().pendingAppreciation(DAY, List.of(property(b))), "kept PENDING, never erased");
        assertEquals(1, count("npc_appreciation_reservations"));
    }

    @Test
    void claimedAndAmbiguousRentStillHoldAndOnlyAFinalRentReleasesTheHold() throws Exception {
        var rent = deferRentOfA();
        Handle handle = new Handle(UUID.randomUUID(), rent.eventId(), UUID.randomUUID(), "c".repeat(64));
        assertTrue(journal().beginDebit(handle).newlyStarted());
        assertEquals(1, count("npc_economy_events WHERE event_id=? AND state='CLAIMED'", rent.eventId()));
        assertEquals(Optional.empty(), UtilityPaymentRuntime.appreciationStep(rents(), true, NEXT, List.of(property(b)), limits, TOP),
                "in flight: held");
        double dollars = rent.cents() / 100.0;
        assertEquals(TransferJournal.State.CREDIT_READY, journal().recordDebit(handle,
                new Observation(true, true, dollars, 100000.0, 100000.0 - dollars, false)).state());
        assertEquals(Optional.empty(), UtilityPaymentRuntime.appreciationStep(rents(), true, NEXT, List.of(property(b)), limits, TOP),
                "debited, credit not yet confirmed: held");
        // An ambiguous label (as startup recovery leaves an orphaned claim) is not final either.
        execute("UPDATE npc_economy_events SET state='AMBIGUOUS' WHERE event_id=?", rent.eventId());
        assertTrue(rents().outstandingRent(NEXT));
        execute("UPDATE npc_economy_events SET state='CLAIMED' WHERE event_id=?", rent.eventId());
        assertTrue(journal().beginCredit(handle).newlyStarted());
        assertEquals(TransferJournal.State.PAID, journal().recordCredit(handle,
                new Observation(true, true, dollars, 50.0, 50.0 + dollars, false)).state());
        assertFalse(rents().outstandingRent(NEXT));
        var step = UtilityPaymentRuntime.appreciationStep(rents(), true, NEXT, List.of(property(b)), limits, TOP);
        assertEquals(List.of(b), step.orElseThrow().stream().map(r -> r.property().propertyId()).toList(), "rent paid: appreciation proceeds");
    }

    @Test
    void retiredOwnerChangedRentNoLongerHolds() throws Exception {
        deferRentOfA();
        Property old = property(a);
        new PropertyRepository(database).applyOwnerChange(a, old.ownerUuid(), new UUID(0, 3499), old.ownershipEpoch() + 1, "test-sale");
        assertEquals(Optional.empty(), UtilityPaymentRuntime.appreciationStep(rents(), true, NEXT, List.of(property(b)), limits, TOP),
                "unretired (retirement off): a same-day stale row still holds, fail closed");
        assertEquals(1, rents().retireStale(NEXT, 500).size());
        assertEquals(1, count("npc_payout_retirements WHERE kind='RENT' AND reason='OWNER_CHANGED' AND property_id=?", a));
        assertFalse(rents().outstandingRent(NEXT), "a V33 retirement is FAILED, so final");
        var step = UtilityPaymentRuntime.appreciationStep(rents(), true, NEXT, List.of(property(b)), limits, TOP);
        assertEquals(1, step.orElseThrow().size());
    }

    @Test
    void rentPausedAfterReservingStillHolds() throws Exception {
        deferRentOfA();
        // Paused after its rent was reserved: rentCandidates filters it out of every later batch's list.
        assertTrue(new PropertyRepository(database).disableExpected(property(a)));
        assertFalse(property(a).enabled());
        assertEquals(Optional.empty(), UtilityPaymentRuntime.appreciationStep(rents(), true, NEXT, List.of(property(b)), limits, TOP));
    }

    /** A delay-1 change requested on DAY, so it is due on NEXT; the rent-change step has not applied it. */
    private void scheduleChangeOfADueNext() throws SQLException {
        var change = new PropertyRepository(database).changeAskingRent(property(a), 500, DAY, 1);
        assertEquals(PropertyRepository.RentChange.Outcome.SCHEDULED, change.outcome());
        assertEquals(NEXT, change.effectiveDay());
        assertEquals(1000, property(a).askingRentCents(), "not applied yet");
    }

    /** Batch on NEXT before the rent-change step ran (its lease stayed busy): A is skipped and no event is written. */
    private void rentBatchSkipsAForItsDueChange() throws SQLException {
        assertTrue(UtilityPaymentRuntime.reserveRentBatch(rents(), false, true, NEXT, List.of(property(a)), limits,
                w -> fail("no warning expected: " + w)).isEmpty());
        assertEquals(0, count("npc_rent_reservations WHERE economy_day=?", NEXT));
        assertEquals(0, count("npc_economy_events WHERE source=? AND economy_day=?", EventSource.RENT.key(), NEXT.toString()));
    }

    @Test
    void rentHeldForADueUnappliedChangeHoldsAppreciationUntilThatRentIsFinal() throws Exception {
        scheduleChangeOfADueNext();
        rentBatchSkipsAForItsDueChange();
        // The defect: the event ledger alone sees nothing outstanding, so the old check would release appreciation.
        assertFalse(rents().outstandingRent(NEXT), "no ledger event for A's rent");
        assertTrue(rents().heldForDueRentChange(NEXT), "positive control: A's rent is held for its due change");
        var before = snapshot();
        assertEquals(Optional.empty(), UtilityPaymentRuntime.appreciationStep(rents(), true, NEXT, List.of(property(b)), limits, TOP),
                "held: appreciation cannot spend the headroom A's rent still needs today");
        assertEquals(0, count("npc_appreciation_reservations"), "held: nothing reserved");
        assertEquals(before, snapshot(), "a held step writes nothing");

        // The rent-change step applies A's change once its lease is free; the hold moves to the ledger row.
        var due = new PropertyRepository(database).applyDueRentChanges(NEXT, PropertyRepository.MAX_DUE_RENT_CHANGES);
        assertTrue(due.changes().getFirst().applied());
        assertFalse(rents().heldForDueRentChange(NEXT));
        var rent = UtilityPaymentRuntime.reserveRentBatch(rents(), false, true, NEXT, List.of(property(a)), limits, w -> { });
        assertEquals(1, rent.size(), "A's rent is reserved at the new rate later the same day");
        assertEquals(Optional.empty(), UtilityPaymentRuntime.appreciationStep(rents(), true, NEXT, List.of(property(b)), limits, TOP),
                "reserved but unpaid: still held");
        assertTrue(pay(rent.getFirst()));
        // Negative control: with A's rent final, the hold opens and B's appreciation for DAY proceeds.
        var step = UtilityPaymentRuntime.appreciationStep(rents(), true, NEXT, List.of(property(b)), limits, TOP);
        assertEquals(List.of(b), step.orElseThrow().stream().map(r -> r.property().propertyId()).toList());
    }

    /** The batch's real appreciation SQL step with the ledger hold on, as the count it would report (-1 held). */
    private CompletableFuture<Integer> appreciationPhase(List<Property> eligible) {
        try {
            return CompletableFuture.completedFuture(UtilityPaymentRuntime.appreciationStep(rents(), true, NEXT, eligible, limits, TOP)
                    .map(List::size).orElse(-1));
        } catch (SQLException failure) { return CompletableFuture.failedFuture(failure); }
    }

    @Test
    void aDueChangeAppliedMidBatchStillHoldsThatBatchsAppreciation() throws Exception {
        scheduleChangeOfADueNext();
        // This batch's SQL step skips A for its due, unapplied change and reports it.
        var batch = UtilityPaymentRuntime.reserveRentBatchCounted(rents(), false, true, NEXT, List.of(property(a), property(b)),
                limits, w -> fail("no warning expected: " + w));
        assertEquals(1, batch.heldForDueChange(), "A skipped for its due change");
        assertTrue(batch.pending().isEmpty(), "A held, B has no utility paid for NEXT");
        assertEquals(0, count("npc_rent_reservations WHERE economy_day=?", NEXT));
        // Mid-batch: the rent-change step applies A's change before this batch's appreciation phase runs.
        var due = new PropertyRepository(database).applyDueRentChanges(NEXT, PropertyRepository.MAX_DUE_RENT_CHANGES);
        assertTrue(due.changes().getFirst().applied());
        // The defect: neither SQL check can see that A's rent is still owed today.
        assertFalse(rents().outstandingRent(NEXT), "no ledger event for A's rent");
        assertFalse(rents().heldForDueRentChange(NEXT), "the change is applied, so no pending columns");
        var before = snapshot();
        var both = List.of(property(a), property(b));
        var started = new AtomicInteger();
        var counted = new AtomicInteger(99);
        var out = UtilityPaymentRuntime.thenAppreciation(CompletableFuture.completedFuture(0), true,
                () -> UtilityPaymentRuntime.appreciationHeld(false, batch.heldForDueChange()),
                () -> { started.incrementAndGet(); return appreciationPhase(both); },
                counted::set, Runnable::run);
        assertEquals(0, out.join(), "the batch still reports its rent");
        assertEquals(-1, counted.get(), "held this batch");
        assertEquals(0, started.get(), "the appreciation supplier is never invoked");
        assertEquals(0, count("npc_appreciation_reservations"), "held: nothing reserved");
        assertEquals(before, snapshot(), "a held phase writes nothing");
        // Positive control: without the count, the same phase would spend headroom A's rent still needs today.
        assertEquals(2, appreciationPhase(both).join());
        assertEquals(2, count("npc_appreciation_reservations WHERE economy_day=?", DAY));
    }

    @Test
    void aZeroSkipCountLetsTheBatchsAppreciationRun() throws Exception {
        var batch = UtilityPaymentRuntime.reserveRentBatchCounted(rents(), false, true, NEXT, List.of(property(a), property(b)),
                limits, w -> fail("no warning expected: " + w));
        assertEquals(0, batch.heldForDueChange(), "no pending change: nothing skipped");
        assertEquals(List.of(a), batch.pending().stream().map(r -> r.property().propertyId()).toList());
        assertTrue(pay(batch.pending().getFirst()));
        var both = List.of(property(a), property(b));
        var started = new AtomicInteger();
        var counted = new AtomicInteger(99);
        var out = UtilityPaymentRuntime.thenAppreciation(CompletableFuture.completedFuture(1), true,
                () -> UtilityPaymentRuntime.appreciationHeld(false, batch.heldForDueChange()),
                () -> { started.incrementAndGet(); return appreciationPhase(both); },
                counted::set, Runnable::run);
        assertEquals(1, out.join());
        assertEquals(1, started.get());
        assertEquals(2, counted.get(), "A's rent is final: both homes' appreciation for DAY proceeds");
    }

    @Test
    void theSkipCountIsInertWithoutTheDueRentHoldAndIgnoresHomesAlreadyReservedToday() throws Exception {
        scheduleChangeOfADueNext();
        // holdDueRent off (rent-change delay unused): the pending columns are never read, so the count is 0.
        var plain = UtilityPaymentRuntime.reserveRentBatchCounted(rents(), false, false, NEXT, List.of(property(a), property(b)),
                limits, w -> { });
        assertEquals(0, plain.heldForDueChange());
        assertEquals(List.of(a), plain.pending().stream().map(r -> r.property().propertyId()).toList(), "old behaviour");
        // A now has today's reservation, so a later batch with the hold on skips A but does not count it.
        var later = UtilityPaymentRuntime.reserveRentBatchCounted(rents(), false, true, NEXT, List.of(property(a), property(b)),
                limits, w -> { });
        assertEquals(0, later.heldForDueChange(), "already reserved today: not owed again");
        assertFalse(rents().heldForDueRentChange(NEXT), "same rule as the SQL check");
        assertEquals(plain.pending(), later.pending(), "the reserved row is still pending, unchanged");
    }

    @Test
    void holdOffIgnoresADueUnappliedChangeExactlyAsBefore() throws Exception {
        scheduleChangeOfADueNext();
        rentBatchSkipsAForItsDueChange();
        var step = UtilityPaymentRuntime.appreciationStep(rents(), false, NEXT, List.of(property(b)), limits, TOP);
        assertEquals(List.of(b), step.orElseThrow().stream().map(r -> r.property().propertyId()).toList(), "flag off: inert");
        assertEquals(1, count("npc_appreciation_reservations WHERE property_id=? AND economy_day=?", b, DAY));
    }

    @Test
    void aDueChangeOnAHomeWhoseRentIsAlreadyReservedAndPaidDoesNotHold() throws Exception {
        // A's rent for NEXT was reserved and paid before the change was scheduled, so nothing more is owed today.
        var rent = UtilityPaymentRuntime.reserveRentBatch(rents(), false, true, NEXT, List.of(property(a)), limits, w -> { });
        assertEquals(1, rent.size());
        assertTrue(pay(rent.getFirst()));
        scheduleChangeOfADueNext();
        assertFalse(rents().heldForDueRentChange(NEXT), "today's reservation exists: no double count");
        assertTrue(rents().heldForDueRentChange(NEXT.plusDays(1)), "positive control: still due and unreserved the day after");
        var step = UtilityPaymentRuntime.appreciationStep(rents(), true, NEXT, List.of(property(b)), limits, TOP);
        assertEquals(1, step.orElseThrow().size());
    }

    @Test
    void aDisabledHomeOrAChangeNotYetDueDoesNotHold() throws Exception {
        // Not due until the day after NEXT: today's rent is the current rate and is not held.
        var later = new PropertyRepository(database).changeAskingRent(property(a), 500, NEXT, 1);
        assertEquals(NEXT.plusDays(1), later.effectiveDay());
        assertFalse(rents().heldForDueRentChange(NEXT));
        assertTrue(rents().heldForDueRentChange(NEXT.plusDays(1)), "positive control: due on its own day");
        // Due on NEXT but the home is paused: it gets no rent today, and applyDueRentChanges skips it too.
        UUID c = operating(new UUID(0, 3413));
        assertEquals(PropertyRepository.RentChange.Outcome.SCHEDULED,
                new PropertyRepository(database).changeAskingRent(property(c), 500, DAY, 1).outcome());
        assertTrue(rents().heldForDueRentChange(NEXT), "positive control: enabled and due holds");
        assertTrue(new PropertyRepository(database).disableExpected(property(c)));
        assertEquals(1, count("npc_properties WHERE property_id=? AND pending_rent_change_id IS NOT NULL", c), "still pending");
        assertFalse(rents().heldForDueRentChange(NEXT));
        var step = UtilityPaymentRuntime.appreciationStep(rents(), true, NEXT, List.of(property(b)), limits, TOP);
        assertEquals(1, step.orElseThrow().size());
    }

    @Test
    void onlyTheOpenDaysRentHolds() throws Exception {
        // No rent at all today: nothing outstanding, appreciation proceeds with the hold on.
        assertFalse(rents().outstandingRent(NEXT));
        var step = UtilityPaymentRuntime.appreciationStep(rents(), true, NEXT, List.of(property(a), property(b)), limits, TOP);
        assertEquals(2, step.orElseThrow().size());
    }

    @Test
    void anExpiredRentFromAnEarlierDayDoesNotHold() throws Exception {
        // A rent row from DAY left PENDING is no longer payable on NEXT (deferred rent is not retried after its day).
        clock.set(NOW);
        UUID c = operating(new UUID(0, 3412));
        var stale = rents().reserve(DAY, List.of(property(c)), limits);
        assertEquals(1, stale.size());
        clock.set(NEXT_NOW.plusSeconds(10));
        assertTrue(rents().outstandingRent(DAY), "positive control: it is outstanding on its own day");
        assertFalse(rents().outstandingRent(NEXT));
        var step = UtilityPaymentRuntime.appreciationStep(rents(), true, NEXT, List.of(property(b)), limits, TOP);
        assertEquals(1, step.orElseThrow().size());
    }

    @Test
    void theHoldIsBoundToTheOpenDayInsideTheTransaction() throws Exception {
        var refused = assertThrows(IllegalArgumentException.class, () -> rents().reserveAppreciationUnlessRentOutstanding(
                NEXT.plusDays(1), DAY, List.of(property(b)), limits, TOP));
        assertEquals("Rent hold day is not the open economy day", refused.getMessage());
        // A rollover during the worker hop: the batch day is no longer today, so the step refuses, never pays.
        clock.set(NEXT_NOW.plusSeconds(86_400));
        assertThrows(IllegalArgumentException.class,
                () -> UtilityPaymentRuntime.appreciationStep(rents(), true, NEXT, List.of(property(b)), limits, TOP));
        assertEquals(0, count("npc_appreciation_reservations"));
    }

    @Test
    void flagParsingIsStrictAndShippedOn() throws Exception {
        assertEquals("economy.appreciation-ledger-rent-hold-enabled", UtilityPaymentRuntime.APPRECIATION_LEDGER_RENT_HOLD);
        assertTrue(UtilityPaymentRuntime.appreciationLedgerRentHold(Boolean.TRUE));
        for (Object off : new Object[] {null, Boolean.FALSE, "true", 1})
            assertFalse(UtilityPaymentRuntime.appreciationLedgerRentHold(off), String.valueOf(off));
        try (var input = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(input);
            var config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(input, StandardCharsets.UTF_8));
            assertEquals(Boolean.TRUE, config.get(UtilityPaymentRuntime.APPRECIATION_LEDGER_RENT_HOLD));
        }
        String code = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/prosperitysmp/npc/economy/UtilityPaymentRuntime.java"));
        // Read on main before the hop, decided under the mutex on the worker, reread on main after the hop.
        int read = code.indexOf("boolean ledgerHold = appreciationLedgerRentHold(plugin.getConfig().get(APPRECIATION_LEDGER_RENT_HOLD));");
        int worker = code.indexOf("return appreciationStep(rents, ledgerHold, day, eligible, limits, scorer);");
        int reread = code.indexOf("appreciationLedgerRentHold(plugin.getConfig().get(APPRECIATION_LEDGER_RENT_HOLD)), step)) {");
        int dispatch = code.indexOf("return gatedSequence(rows, stillOn,");
        assertTrue(read > 0 && worker > read && reread > worker && dispatch > reread, read + " " + worker + " " + reread + " " + dispatch);
        // The per-batch floor-deferral hold is kept, joined by this batch's due-change skip count.
        assertTrue(code.contains("return thenAppreciation(sequence, appreciate, () -> appreciationHeld(rentDeferred[0], dueChangeHeld[0]),"));
    }

    private static boolean pay(Reserved row) throws SQLException {
        Handle handle = new Handle(UUID.randomUUID(), row.eventId(), UUID.randomUUID(), "b".repeat(64));
        if (!journal().beginDebit(handle).newlyStarted()) return false;
        double dollars = row.cents() / 100.0;
        assertEquals(TransferJournal.State.CREDIT_READY, journal().recordDebit(handle,
                new Observation(true, true, dollars, 100000.0, 100000.0 - dollars, false)).state());
        assertTrue(journal().beginCredit(handle).newlyStarted());
        assertEquals(TransferJournal.State.PAID, journal().recordCredit(handle,
                new Observation(true, true, dollars, 50.0, 50.0 + dollars, false)).state());
        return true;
    }

    private static Property property(UUID id) throws SQLException {
        return new PropertyRepository(database).findById(id).orElseThrow();
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
        new PropertyRepository(database).insertEnrollment(new Property(id, WORLD, "hold-" + id, "test", "room",
                4, 1000, 1000, owner, 0, 80, true), 5000);
        UUID tenant = UUID.randomUUID();
        assertTrue(new ResidentRepository(database).register(ResidentCatalogue.draft(tenant, "Mara Vale")));
        new TenantRepository(database).insert(new Tenant(tenant, "Mara Vale", id, 0, "STANDARD", "COOK", 50));
        payUtilityFor(id, owner, days().today());
        return id;
    }

    private com.prosperitysmp.npc.clock.EconomyClock days() {
        return new com.prosperitysmp.npc.clock.EconomyClock(clock, ZONE, RESET);
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
        try (InputStream in = AppreciationLedgerRentHoldIT.class.getResourceAsStream(path)) {
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

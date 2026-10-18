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
import com.prosperitysmp.npc.economy.TransferJournal;
import com.prosperitysmp.npc.economy.TransferJournal.Handle;
import com.prosperitysmp.npc.economy.TransferJournal.Receipt;
import com.prosperitysmp.npc.economy.TransferJournal.Start;
import com.prosperitysmp.npc.economy.TransferJournal.State;
import com.prosperitysmp.npc.economy.HeldClass;
import com.prosperitysmp.npc.economy.TransferProof.Leg;
import com.prosperitysmp.npc.economy.TransferProof.Observation;
import com.prosperitysmp.npc.economy.TransferProof.Refusal;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real journal, ledger and funding SQL; no provider calls or repository mocks. */
class TransferJournalIT {
    private static final String DAY = "2026-09-06";
    private static final UUID WORLD = new UUID(0, 704);
    private static final long SEED = 50_000;
    private static final long CENTS = 1_000;
    private static final String PROVIDER = "a".repeat(64);
    private static final String V4 = "/db/migration/V4__transfer_journal.sql";
    private static final Observation DEBIT = new Observation(true, true, 10.0, 100.0, 90.0, false);
    private static final Observation CREDIT = new Observation(true, true, 10.0, 20.0, 30.0, false);
    private static final List<String> TRIGGERS = List.of("transfer_claim_failure", "transfer_paid_failure");
    private static DB mariaDb;
    private static Database database;
    private static String schemaName;
    private TransferJournal journal;
    private EconomyEventRepository events;
    private PropertyRepository properties;
    private UUID property;
    private UUID owner;
    private UUID reserve;
    private UUID run;

    @BeforeAll
    static void bootDatabase() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        mariaDb.start();
        schemaName = "npc_transfer_" + UUID.randomUUID().toString().replace("-", "");
        mariaDb.createDB(schemaName);
        database = new Database();
        database.connect(new DatabaseSettings("localhost", cfg.getPort(), schemaName, "root", "", 6, 10_000L));
        applyResource("/db/migration/V1__initial_schema.sql");
        applyResource("/db/migration/V45__property_backend.sql");
        applyResource("/db/migration/V2__resident_identity.sql");
        applyResource("/db/migration/V3__purchase_receipts.sql");
        applyResource(V4);
        applyResource("/db/migration/V17__transfer_resolutions.sql");
        applyResource("/db/migration/V41__charge_catalogue.sql");
        try (Connection c = database.getConnection()) { assertTrue(com.prosperitysmp.npc.db.V40Fixture.apply(c) >= 5); }
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        try {
            if (database != null) database.close();
        } finally {
            if (mariaDb != null) mariaDb.stop();
        }
    }

    @BeforeEach
    void freshJournal() throws SQLException {
        dropFixtureTriggers();
        for (String table : List.of("npc_transfer_resolutions", "npc_transfer_observations", "npc_transfer_attempts", "npc_purchase_intents",
                "npc_economy_events", "npc_property_ownership", "npc_property_funding", "npc_properties")) {
            execute("DELETE FROM " + table);
        }
        property = UUID.randomUUID();
        owner = UUID.randomUUID();
        reserve = UUID.randomUUID();
        run = UUID.randomUUID();
        journal = new TransferJournal(database, reserve);
        events = new EconomyEventRepository(database);
        properties = new PropertyRepository(database);
        enroll(property);
    }

    @AfterEach
    void dropFixtureTriggers() throws SQLException {
        if (database != null && database.isConnected()) {
            for (String trigger : TRIGGERS) execute("DROP TRIGGER IF EXISTS " + trigger);
        }
    }

    @ParameterizedTest
    @EnumSource(EventSource.class)
    void eachSourceClaimsOnceAndPaysOnlyAfterBothDurableLegProofs(EventSource source) throws SQLException {
        Handle handle = pending(source, run);
        Start debit = journal.beginDebit(handle);
        assertTrue(debit.newlyStarted());
        assertEquals(handle, debit.receipt().handle());
        assertEquals(source.isPayout() ? reserve : owner, debit.receipt().payer());
        assertEquals(source.isPayout() ? owner : reserve, debit.receipt().payee());
        assertEquals(CENTS, debit.receipt().cents());
        assertTrue(debit.receipt().requestSha256().matches("[a-f0-9]{64}"));
        assertReceipt(handle, State.DEBIT_INTENT, false, "CLAIMED", null);
        assertNoFunding();
        assertEquals(0, count("npc_transfer_observations"));
        assertFalse(journal.beginCredit(handle).newlyStarted());
        assertFalse(journal.beginDebit(handle).newlyStarted());

        Receipt ready = journal.recordDebit(handle, DEBIT);
        assertEquals(State.CREDIT_READY, ready.state());
        assertEquals(ready, journal.recordDebit(handle, DEBIT));
        assertReceipt(handle, State.CREDIT_READY, false, "CLAIMED", null);
        assertNoFunding();
        assertTrue(journal.observation(handle.attemptId(), Leg.CREDIT).isEmpty());

        assertTrue(journal.beginCredit(handle).newlyStarted());
        assertReceipt(handle, State.CREDIT_INTENT, true, "CLAIMED", null);
        assertNoFunding();
        assertFalse(journal.beginCredit(handle).newlyStarted());
        Receipt paid = journal.recordCredit(handle, CREDIT);
        assertEquals(State.PAID, paid.state());
        assertReceipt(handle, State.PAID, true, "PAID", CENTS);
        assertObservation(handle, Leg.DEBIT, DEBIT);
        assertObservation(handle, Leg.CREDIT, CREDIT);
        assertFunding(source, 1);

        journal = new TransferJournal(database, reserve);
        for (int replay = 0; replay < 3; replay++) {
            assertEquals(paid, journal.find(handle.eventId()).orElseThrow());
            assertEquals(new Start(false, paid), journal.beginDebit(handle));
            assertEquals(new Start(false, paid), journal.beginCredit(handle));
            assertEquals(paid, journal.recordDebit(handle, DEBIT));
            assertEquals(paid, journal.recordCredit(handle, CREDIT));
        }
        assertEquals(0, journal.recoverOtherRuns(UUID.randomUUID(), 100));
        assertEquals(1, count("npc_transfer_attempts"));
        assertEquals(2, count("npc_transfer_observations"));
        assertFunding(source, 1);
    }

    @Test
    void dispatcherRealSqlPaysOnceAndReplayOnlyReadsReceipt() throws SQLException {
        Handle handle = pending(EventSource.UPGRADE, run);
        List<String> calls = new ArrayList<>();
        var paid = com.prosperitysmp.npc.economy.TransferDispatchFixture.dispatch(journal, handle, calls)
                .toCompletableFuture().join();
        assertEquals(State.PAID, paid.state());
        assertEquals(List.of("authorize-DEBIT", "money-DEBIT", "authorize-CREDIT", "money-CREDIT", "release"), calls);
        assertReceipt(handle, State.PAID, true, "PAID", CENTS);
        assertFunding(EventSource.UPGRADE, 1);
        calls.clear();
        var replay = com.prosperitysmp.npc.economy.TransferDispatchFixture.dispatch(
                new TransferJournal(database, reserve), handle, calls).toCompletableFuture().join();
        assertEquals(paid, replay);
        assertEquals(List.of("release"), calls);
        assertEquals(2, count("npc_transfer_observations"));
        assertFunding(EventSource.UPGRADE, 1);
    }

    @Test
    void dispatcherRealSqlCannotReplayPreviouslyCommittedUnacknowledgedIntent() throws SQLException {
        Handle handle = pending(EventSource.UPGRADE, run);
        journal.beginDebit(handle); // Commit succeeded; original dispatcher never received authority.
        List<String> calls = new ArrayList<>();
        var held = com.prosperitysmp.npc.economy.TransferDispatchFixture.dispatch(
                new TransferJournal(database, reserve), handle, calls).toCompletableFuture().join();
        assertEquals(State.DEBIT_INTENT, held.state());
        assertTrue(calls.isEmpty());
        assertEquals(0, count("npc_transfer_observations"));
        assertNoFunding();
    }

    @ParameterizedTest
    @EnumSource(value = State.class, names = {"DEBIT_INTENT", "CREDIT_READY", "CREDIT_INTENT"})
    void recoveryIsBoundedReplayedAndPreservesEvidenceWhileLeavingCurrentRunUntouched(State crashPoint) throws SQLException {
        UUID oldRun = UUID.randomUUID();
        Handle first = started(EventSource.UPGRADE, oldRun, crashPoint);
        Handle current = started(EventSource.UPGRADE, run, crashPoint);
        Handle second = started(EventSource.UPGRADE, oldRun, crashPoint);
        List<List<String>> currentBefore = attemptRows(current);
        List<List<String>> currentLedger = eventRows(current);
        List<List<String>> evidenceBefore = observationRows();
        assertEquals(0, journal.recoverOtherRuns(run, -1));
        assertEquals(0, journal.recoverOtherRuns(run, 0));
        journal = new TransferJournal(database, reserve);
        assertEquals(1, journal.recoverOtherRuns(run, 1));
        assertReceipt(first, State.AMBIGUOUS, crashPoint == State.CREDIT_INTENT, "AMBIGUOUS", null);
        assertReceipt(second, crashPoint, crashPoint == State.CREDIT_INTENT, "CLAIMED", null);
        assertEquals("INTERRUPTED_RUN", journal.find(first.eventId()).orElseThrow().outcome());
        assertEquals(1, journal.recoverOtherRuns(run, 1));
        assertEquals(0, journal.recoverOtherRuns(run, 1));
        assertEquals(0, journal.recoverOtherRuns(run, 100));
        for (Handle recovered : List.of(first, second)) {
            assertReceipt(recovered, State.AMBIGUOUS, crashPoint == State.CREDIT_INTENT, "AMBIGUOUS", null);
            assertFalse(journal.beginDebit(recovered).newlyStarted());
            assertFalse(journal.beginCredit(recovered).newlyStarted());
        }
        assertEquals(currentBefore, attemptRows(current));
        assertEquals(currentLedger, eventRows(current));
        assertEquals(evidenceBefore, observationRows());
        assertNoFunding();
    }

    @Test
    void oversizedRecoveryBatchIsCappedAtOneHundred() throws SQLException {
        UUID oldRun = UUID.randomUUID();
        List<Handle> handles = new ArrayList<>();
        for (int i = 0; i < 102; i++) handles.add(started(EventSource.UPGRADE, oldRun, State.DEBIT_INTENT));
        assertEquals(100, journal.recoverOtherRuns(run, Integer.MAX_VALUE));
        for (int i = 0; i < handles.size(); i++) {
            assertReceipt(handles.get(i), i < 100 ? State.AMBIGUOUS : State.DEBIT_INTENT,
                    false, i < 100 ? "AMBIGUOUS" : "CLAIMED", null);
        }
        assertEquals(2, journal.recoverOtherRuns(run, Integer.MAX_VALUE));
        assertEquals(0, journal.recoverOtherRuns(run, Integer.MAX_VALUE));
        assertEquals(0, count("npc_transfer_observations"));
        assertNoFunding();
    }

    @ParameterizedTest
    @EnumSource(value = State.class, names = {"DEBIT_INTENT", "CREDIT_READY", "CREDIT_INTENT"})
    void lateProofsStayAmbiguousAndCreditRequiresAnActuallyPersistedIntent(State crashPoint) throws SQLException {
        Handle handle = started(EventSource.RENT, UUID.randomUUID(), crashPoint);
        String creditIntentAt = scalar("SELECT credit_intent_at FROM npc_transfer_attempts WHERE event_id=?", handle.eventId());
        assertEquals(1, journal.recoverOtherRuns(run, 100));
        Receipt recovered = journal.find(handle.eventId()).orElseThrow();
        assertEquals(recovered, journal.recordDebit(handle, DEBIT));
        assertObservation(handle, Leg.DEBIT, DEBIT);
        assertFalse(journal.beginDebit(handle).newlyStarted());
        assertFalse(journal.beginCredit(handle).newlyStarted());
        if (crashPoint == State.CREDIT_INTENT) {
            assertNotNull(creditIntentAt);
            assertEquals(recovered, journal.recordCredit(handle, CREDIT));
            assertEquals(recovered, journal.recordCredit(handle, CREDIT));
            assertObservation(handle, Leg.CREDIT, CREDIT);
        } else {
            assertNull(creditIntentAt);
            assertThrows(SQLException.class, () -> journal.recordCredit(handle, CREDIT),
                    "a late confirmed debit cannot invent a credit dispatch before recovery");
            assertTrue(journal.observation(handle.attemptId(), Leg.CREDIT).isEmpty());
        }
        assertEquals(creditIntentAt, scalar("SELECT credit_intent_at FROM npc_transfer_attempts WHERE event_id=?", handle.eventId()));
        assertReceipt(handle, State.AMBIGUOUS, crashPoint == State.CREDIT_INTENT, "AMBIGUOUS", null);
        assertEquals("INTERRUPTED_RUN", journal.find(handle.eventId()).orElseThrow().outcome());
        assertEquals(0, journal.recoverOtherRuns(run, 100));
        assertNoFunding();
    }

    @ParameterizedTest
    @EnumSource(value = EventSource.class, names = {"RENT", "UPGRADE"})
    void concurrentStartsAndAcksSerializeBeforeWritesAndDispatchEachLegOnlyOnce(EventSource source) throws Exception {
        Handle handle = pending(source, run);
        TransferJournal other = new TransferJournal(database, reserve);
        List<Start> debitStarts = behindPropertyLock(() -> journal.beginDebit(handle), () -> other.beginDebit(handle));
        assertEquals(1, debitStarts.stream().filter(Start::newlyStarted).count());
        assertEquals(debitStarts.getFirst().receipt(), debitStarts.getLast().receipt());
        assertReceipt(handle, State.DEBIT_INTENT, false, "CLAIMED", null);
        assertFalse(other.beginDebit(handle).newlyStarted());

        List<Receipt> debitAcks = behindPropertyLock(() -> journal.recordDebit(handle, DEBIT), () -> other.recordDebit(handle, DEBIT));
        assertEquals(debitAcks.getFirst(), debitAcks.getLast());
        assertReceipt(handle, State.CREDIT_READY, false, "CLAIMED", null);
        assertEquals(1, count("npc_transfer_observations"));
        assertNoFunding();

        List<Start> creditStarts = behindPropertyLock(() -> journal.beginCredit(handle), () -> other.beginCredit(handle));
        assertEquals(1, creditStarts.stream().filter(Start::newlyStarted).count());
        assertEquals(creditStarts.getFirst().receipt(), creditStarts.getLast().receipt());
        assertFalse(other.beginCredit(handle).newlyStarted());
        assertReceipt(handle, State.CREDIT_INTENT, true, "CLAIMED", null);

        List<Receipt> creditAcks = behindPropertyLock(() -> journal.recordCredit(handle, CREDIT), () -> other.recordCredit(handle, CREDIT));
        assertEquals(creditAcks.getFirst(), creditAcks.getLast());
        assertEquals(State.PAID, creditAcks.getFirst().state());
        assertReceipt(handle, State.PAID, true, "PAID", CENTS);
        assertObservation(handle, Leg.DEBIT, DEBIT);
        assertObservation(handle, Leg.CREDIT, CREDIT);
        assertEquals(1, count("npc_transfer_attempts"));
        assertEquals(2, count("npc_transfer_observations"));
        assertFunding(source, 1);
    }

    @Test
    void concurrentDifferentHandlesForOneEventCannotBothWin() throws Exception {
        Handle first = pending(EventSource.UPGRADE, run);
        Handle second = new Handle(UUID.randomUUID(), first.eventId(), UUID.randomUUID(), "b".repeat(64));
        List<Boolean> accepted = behindPropertyLock(() -> startOrIdentityConflict(first), () -> startOrIdentityConflict(second));
        assertEquals(1, accepted.stream().filter(Boolean::booleanValue).count());
        Handle winner = journal.find(first.eventId()).orElseThrow().handle();
        assertTrue(winner.equals(first) || winner.equals(second));
        assertReceipt(winner, State.DEBIT_INTENT, false, "CLAIMED", null);
        assertEquals(1, count("npc_transfer_attempts"));
        assertEquals(0, count("npc_transfer_observations"));
        assertNoFunding();
    }

    @ParameterizedTest
    @EnumSource(Leg.class)
    void concurrentConflictingAcksPersistExactlyOneUnmodifiedObservation(Leg leg) throws Exception {
        Handle handle = started(EventSource.UPGRADE, run, leg == Leg.DEBIT ? State.DEBIT_INTENT : State.CREDIT_INTENT);
        Observation first = leg == Leg.DEBIT ? DEBIT : CREDIT;
        Observation second = new Observation(true, true, 9.0, first.beforeDollars(), first.afterDollars(), false);
        List<Boolean> accepted = behindPropertyLock(() -> ackOrConflict(handle, leg, first), () -> ackOrConflict(handle, leg, second));
        assertEquals(1, accepted.stream().filter(Boolean::booleanValue).count());
        Observation winner = accepted.getFirst() ? first : second;
        assertObservation(handle, leg, winner);
        State expected = winner == second ? State.AMBIGUOUS : leg == Leg.DEBIT ? State.CREDIT_READY : State.PAID;
        assertEquals(expected, journal.find(handle.eventId()).orElseThrow().state());
        assertEquals(leg == Leg.DEBIT ? 1 : 2, count("npc_transfer_observations"));
        assertFunding(EventSource.UPGRADE, expected == State.PAID ? 1 : 0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"event", "attempt", "observation"})
    void ackUsesLockingReadsAfterItsInitialSnapshotWhenMetadataChanges(String fixture) throws Exception {
        Handle handle = started(EventSource.UPGRADE, run, State.CREDIT_INTENT);
        SQLException failure = whilePropertyLocked(
                () -> assertThrows(SQLException.class, () -> journal.recordCredit(handle, CREDIT)),
                () -> {
                    switch (fixture) {
                        case "event" -> eventChange(handle, "requested_amount_cents=1001");
                        case "attempt" -> execute("UPDATE npc_transfer_attempts SET request_sha256=? WHERE event_id=?", "d".repeat(64), handle.eventId());
                        case "observation" -> execute("UPDATE npc_transfer_observations SET after_bits=before_bits WHERE attempt_id=? AND leg='DEBIT'", handle.attemptId());
                        default -> throw new AssertionError(fixture);
                    }
                });
        assertNotNull(failure);
        assertEquals("CREDIT_INTENT", scalar("SELECT state FROM npc_transfer_attempts WHERE event_id=?", handle.eventId()));
        assertNotNull(scalar("SELECT credit_intent_at FROM npc_transfer_attempts WHERE event_id=?", handle.eventId()));
        assertLedger(handle, "CLAIMED", null);
        assertTrue(journal.observation(handle.attemptId(), Leg.CREDIT).isEmpty());
        assertNoFunding();
    }

    @Test
    void startRechecksEventPropertyAfterWaitingForTheOriginalPropertyLock() throws Exception {
        Handle handle = pending(EventSource.UPGRADE, run);
        UUID otherProperty = UUID.randomUUID();
        enroll(otherProperty);
        whilePropertyLocked(() -> assertThrows(SQLException.class, () -> journal.beginDebit(handle)),
                () -> eventChange(handle, "property_id=?", otherProperty));
        assertEquals(0, count("npc_transfer_attempts"));
        assertLedger(handle, "PENDING", null);
        assertNoFunding();
    }

    @ParameterizedTest
    @EnumSource(value = State.class, names = {"CREDIT_READY", "CREDIT_INTENT"})
    void recoveredStateCommittedWhileAckWaitsIsReadFreshAndNeverPromoted(State crashPoint) throws Exception {
        Handle handle = started(EventSource.UPGRADE, UUID.randomUUID(), crashPoint);
        // Recovery is committed on independent connections after the worker has taken its initial snapshot.
        Receipt result = whilePropertyLocked(
                () -> crashPoint == State.CREDIT_READY ? journal.beginCredit(handle).receipt() : journal.recordCredit(handle, CREDIT),
                () -> {
                    execute("UPDATE npc_transfer_attempts SET state='AMBIGUOUS',outcome_code='INTERRUPTED_RUN' WHERE event_id=?", handle.eventId());
                    eventChange(handle, "state='AMBIGUOUS',finalized_at=CURRENT_TIMESTAMP(3)");
                });
        assertEquals(State.AMBIGUOUS, result.state());
        assertReceipt(handle, State.AMBIGUOUS, crashPoint == State.CREDIT_INTENT, "AMBIGUOUS", null);
        if (crashPoint == State.CREDIT_INTENT) assertObservation(handle, Leg.CREDIT, CREDIT);
        else assertTrue(journal.observation(handle.attemptId(), Leg.CREDIT).isEmpty());
        assertFalse(journal.beginCredit(handle).newlyStarted());
        assertNoFunding();
    }

    @ParameterizedTest
    @EnumSource(value = State.class, names = {"DEBIT_INTENT", "CREDIT_READY", "CREDIT_INTENT"})
    void concurrentStartupRecoveryCountsEachOldAttemptOnlyOnce(State crashPoint) throws Exception {
        Handle handle = started(EventSource.UPGRADE, UUID.randomUUID(), crashPoint);
        List<List<String>> evidence = observationRows();
        List<Integer> counts = behindPropertyLock(() -> journal.recoverOtherRuns(run, 1),
                () -> new TransferJournal(database, reserve).recoverOtherRuns(run, 1));
        assertEquals(1, counts.stream().mapToInt(Integer::intValue).sum());
        assertReceipt(handle, State.AMBIGUOUS, crashPoint == State.CREDIT_INTENT, "AMBIGUOUS", null);
        assertEquals(evidence, observationRows());
        assertFalse(journal.beginDebit(handle).newlyStarted());
        assertFalse(journal.beginCredit(handle).newlyStarted());
        assertNoFunding();
    }

    @Test
    void repeatedV4ApplicationPreservesAllJournalStatesEvidenceAndOldTables() throws Exception {
        List<Handle> handles = new ArrayList<>();
        // These are the six V4 states; V17 compensation replay has its own SQL fixture.
        List<State> v4States = List.of(State.DEBIT_INTENT, State.CREDIT_READY, State.CREDIT_INTENT,
                State.PAID, State.DECLINED, State.AMBIGUOUS);
        for (State state : v4States) handles.add(started(EventSource.UPGRADE, run, state));
        Handle legacy = pending(EventSource.RENT, run);
        List<List<String>> before = journalRows();
        List<List<String>> propertyBefore = rows("SELECT * FROM npc_properties ORDER BY property_id");
        List<List<String>> ownershipBefore = rows("SELECT * FROM npc_property_ownership ORDER BY id");
        List<List<String>> oldTables = rows("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME");
        for (int replay = 0; replay < 2; replay++) applyResource(V4);
        assertEquals(before, journalRows());
        assertEquals(propertyBefore, rows("SELECT * FROM npc_properties ORDER BY property_id"));
        assertEquals(ownershipBefore, rows("SELECT * FROM npc_property_ownership ORDER BY id"));
        assertEquals(oldTables, rows("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME"));
        assertTrue(journal.find(legacy.eventId()).isEmpty(), "migration must not manufacture intent for legacy events");
        assertLedger(legacy, "PENDING", null);
        assertEquals(v4States.size(), count("npc_transfer_attempts"));
        for (int i = 0; i < handles.size(); i++) assertEquals(v4States.get(i), journal.find(handles.get(i).eventId()).orElseThrow().state());
        assertFunding(EventSource.UPGRADE, 1);
    }

    @Test
    void unknownEventsAndMissingIntentsCannotDispatchOrStoreEvidence() throws SQLException {
        for (long id : new long[]{Long.MIN_VALUE, -1, 0, Long.MAX_VALUE}) assertTrue(journal.find(id).isEmpty());
        Handle unknown = new Handle(UUID.randomUUID(), Long.MAX_VALUE, run, PROVIDER);
        assertThrows(SQLException.class, () -> journal.beginDebit(unknown));
        assertThrows(SQLException.class, () -> journal.beginCredit(unknown));
        assertThrows(SQLException.class, () -> journal.recordDebit(unknown, DEBIT));
        assertThrows(SQLException.class, () -> journal.recordCredit(unknown, CREDIT));
        Handle pending = pending(EventSource.UPGRADE, run);
        assertThrows(SQLException.class, () -> journal.beginCredit(pending));
        assertThrows(SQLException.class, () -> journal.recordDebit(pending, DEBIT));
        assertThrows(SQLException.class, () -> journal.recordCredit(pending, CREDIT));
        assertTrue(journal.observation(unknown.attemptId(), Leg.DEBIT).isEmpty());
        assertEquals(0, count("npc_transfer_attempts"));
        assertEquals(0, count("npc_transfer_observations"));
        assertLedger(pending, "PENDING", null);
        assertNoFunding();
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "direction", "property-null", "property-missing", "counterparty-null",
            "benefit-null", "epoch-null", "epoch-negative", "day-invalid", "day-shape", "day-zero",
            "key-blank", "amount-zero", "amount-negative", "paid-zero", "claimed", "paid", "failed",
            "ambiguous", "owner", "owner-null", "disabled", "epoch-stale", "self-transfer"})
    void invalidMetadataOrMirrorRejectsStartWithoutClaimOrAttempt(String fixture) throws SQLException {
        Handle handle = pending(EventSource.UPGRADE, run);
        switch (fixture) {
            case "source" -> eventChange(handle, "source=?", "invented-source");
            case "direction" -> eventChange(handle, "direction=?", "TRANSFER_TO_PUBLIC");
            case "property-null" -> eventChange(handle, "property_id=NULL");
            case "property-missing" -> eventChange(handle, "property_id=?", UUID.randomUUID());
            case "counterparty-null" -> eventChange(handle, "counterparty_uuid=NULL");
            case "benefit-null" -> eventChange(handle, "benefit_unit_id=NULL");
            case "epoch-null" -> eventChange(handle, "ownership_epoch=NULL");
            case "epoch-negative" -> eventChange(handle, "ownership_epoch=-1");
            case "day-invalid" -> eventChange(handle, "economy_day=?", "2026-02-30");
            case "day-shape" -> eventChange(handle, "economy_day=?", "2026-9-06");
            case "day-zero" -> eventChange(handle, "economy_day=?", "0000-09-06");
            case "key-blank" -> eventChange(handle, "idempotency_key=?", " ");
            case "amount-zero" -> eventChange(handle, "requested_amount_cents=0");
            case "amount-negative" -> eventChange(handle, "requested_amount_cents=-1");
            case "paid-zero" -> eventChange(handle, "paid_amount_cents=0");
            case "claimed", "paid", "failed", "ambiguous" -> eventChange(handle, "state=?", fixture.toUpperCase(Locale.ROOT));
            case "owner" -> execute("UPDATE npc_properties SET owner_uuid=? WHERE property_id=?", UUID.randomUUID(), property);
            case "owner-null" -> execute("UPDATE npc_properties SET owner_uuid=NULL WHERE property_id=?", property);
            case "disabled" -> execute("UPDATE npc_properties SET enabled=FALSE WHERE property_id=?", property);
            case "epoch-stale" -> execute("UPDATE npc_properties SET ownership_epoch=1 WHERE property_id=?", property);
            case "self-transfer" -> journal = new TransferJournal(database, owner);
            default -> throw new AssertionError(fixture);
        }
        List<List<String>> before = eventRows(handle);
        assertThrows(SQLException.class, () -> journal.beginDebit(handle));
        assertEquals(before, eventRows(handle));
        assertEquals(0, count("npc_transfer_attempts"));
        assertEquals(0, count("npc_transfer_observations"));
        assertNoFunding();
    }

    @ParameterizedTest
    @ValueSource(strings = {"attempt", "event", "run", "provider", "reserve"})
    void everyMutatorRequiresExactHandleProviderReserveAndRun(String fixture) throws SQLException {
        Handle handle = started(EventSource.UPGRADE, run, State.CREDIT_INTENT);
        Handle other = pending(EventSource.UPGRADE, run);
        journal.beginDebit(other);
        Handle forged = new Handle(fixture.equals("attempt") ? other.attemptId() : handle.attemptId(),
                fixture.equals("event") ? other.eventId() : handle.eventId(),
                fixture.equals("run") ? UUID.randomUUID() : handle.runId(),
                fixture.equals("provider") ? "b".repeat(64) : PROVIDER);
        TransferJournal caller = new TransferJournal(database, fixture.equals("reserve") ? UUID.randomUUID() : reserve);
        List<List<String>> before = journalRows();
        assertThrows(SQLException.class, () -> caller.beginDebit(forged));
        assertThrows(SQLException.class, () -> caller.beginCredit(forged));
        assertThrows(SQLException.class, () -> caller.recordDebit(forged, DEBIT));
        assertThrows(SQLException.class, () -> caller.recordCredit(forged, CREDIT));
        assertEquals(before, journalRows());
        assertNoFunding();
    }

    @Test
    void attemptUuidCannotBeReusedForAnotherEvent() throws SQLException {
        Handle first = started(EventSource.UPGRADE, run, State.DEBIT_INTENT);
        Handle second = pending(EventSource.RENT, run);
        Handle collision = new Handle(first.attemptId(), second.eventId(), run, PROVIDER);
        assertThrows(SQLException.class, () -> journal.beginDebit(collision));
        assertLedger(second, "PENDING", null);
        assertReceipt(first, State.DEBIT_INTENT, false, "CLAIMED", null);
        assertEquals(1, count("npc_transfer_attempts"));
        assertNoFunding();
    }

    @ParameterizedTest
    @ValueSource(strings = {"key", "amount", "source", "property", "counterparty", "epoch", "benefit", "day",
            "ledger-state", "ledger-paid", "request-hash", "payer", "payee", "receipt-amount"})
    void changedPersistedIdentityCannotBeAcknowledgedOrRecovered(String fixture) throws SQLException {
        Handle handle = started(EventSource.UPGRADE, run, State.CREDIT_INTENT);
        switch (fixture) {
            case "key" -> eventChange(handle, "idempotency_key=?", "changed|" + UUID.randomUUID());
            case "amount" -> eventChange(handle, "requested_amount_cents=1001");
            case "source" -> eventChange(handle, "source=?", EventSource.REPAIR.key());
            case "property" -> {
                UUID other = UUID.randomUUID();
                enroll(other);
                eventChange(handle, "property_id=?", other);
            }
            case "counterparty" -> eventChange(handle, "counterparty_uuid=?", UUID.randomUUID());
            case "epoch" -> eventChange(handle, "ownership_epoch=1");
            case "benefit" -> eventChange(handle, "benefit_unit_id=?", UUID.randomUUID());
            case "day" -> eventChange(handle, "economy_day=?", "2026-09-07");
            case "ledger-state" -> eventChange(handle, "state='PAID'");
            case "ledger-paid" -> eventChange(handle, "paid_amount_cents=0");
            case "request-hash" -> execute("UPDATE npc_transfer_attempts SET request_sha256=? WHERE event_id=?", "c".repeat(64), handle.eventId());
            case "payer", "payee" -> execute("UPDATE npc_transfer_attempts SET " + fixture + "_uuid=? WHERE event_id=?", UUID.randomUUID(), handle.eventId());
            case "receipt-amount" -> execute("UPDATE npc_transfer_attempts SET amount_cents=1001 WHERE event_id=?", handle.eventId());
            default -> throw new AssertionError(fixture);
        }
        List<List<String>> before = journalRows();
        assertThrows(SQLException.class, () -> journal.beginDebit(handle));
        assertThrows(SQLException.class, () -> journal.beginCredit(handle));
        assertThrows(SQLException.class, () -> journal.recordDebit(handle, DEBIT));
        assertThrows(SQLException.class, () -> journal.recordCredit(handle, CREDIT));
        assertThrows(SQLException.class, () -> journal.recoverOtherRuns(UUID.randomUUID(), 100));
        assertEquals(before, journalRows());
        assertNoFunding();
    }

    @ParameterizedTest
    @EnumSource(value = State.class, names = {"DEBIT_INTENT", "CREDIT_READY"})
    void creditProofBeforeDebitOrBeforeDurableCreditIntentIsRejected(State state) throws SQLException {
        Handle handle = started(EventSource.UPGRADE, run, state);
        List<List<String>> before = journalRows();
        assertThrows(SQLException.class, () -> journal.recordCredit(handle, CREDIT));
        assertEquals(before, journalRows());
        assertTrue(journal.observation(handle.attemptId(), Leg.CREDIT).isEmpty());
        assertReceipt(handle, state, false, "CLAIMED", null);
        assertNoFunding();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ready-missing", "ready-corrupt", "intent-missing", "intent-corrupt"})
    void missingOrCorruptDebitProofCannotAuthorizeCreditOrPaid(String fixture) throws SQLException {
        boolean ready = fixture.startsWith("ready");
        Handle handle = started(EventSource.UPGRADE, run, ready ? State.CREDIT_READY : State.CREDIT_INTENT);
        if (fixture.endsWith("missing")) {
            execute("DELETE FROM npc_transfer_observations WHERE attempt_id=? AND leg='DEBIT'", handle.attemptId());
        } else {
            execute("UPDATE npc_transfer_observations SET after_bits=before_bits WHERE attempt_id=? AND leg='DEBIT'", handle.attemptId());
        }
        List<List<String>> before = journalRows();
        if (ready) assertThrows(SQLException.class, () -> journal.beginCredit(handle));
        else assertThrows(SQLException.class, () -> journal.recordCredit(handle, CREDIT));
        assertEquals(before, journalRows());
        assertNoFunding();
    }

    @ParameterizedTest
    @ValueSource(strings = {"debit-missing", "debit-corrupt", "credit-missing", "credit-corrupt"})
    void paidReplayCannotCertifyPaymentWhenTheOtherLegProofIsMissingOrCorrupt(String fixture) throws SQLException {
        Handle handle = started(EventSource.UPGRADE, run, State.PAID);
        Leg damaged = fixture.startsWith("debit") ? Leg.DEBIT : Leg.CREDIT;
        if (fixture.endsWith("missing")) {
            execute("DELETE FROM npc_transfer_observations WHERE attempt_id=? AND leg=?", handle.attemptId(), damaged.name());
        } else {
            execute("UPDATE npc_transfer_observations SET after_bits=before_bits WHERE attempt_id=? AND leg=?", handle.attemptId(), damaged.name());
        }
        List<List<String>> before = journalRows();
        Leg replayed = damaged == Leg.DEBIT ? Leg.CREDIT : Leg.DEBIT;
        assertAll(
                () -> assertThrows(SQLException.class, () -> ack(journal, handle, replayed, replayed == Leg.DEBIT ? DEBIT : CREDIT),
                        "an identical ACK must not certify PAID based only on its own surviving proof"),
                () -> assertThrows(SQLException.class, () -> journal.find(handle.eventId()),
                        "a PAID read must validate both persisted leg proofs, not just the receipt state"),
                () -> assertEquals(before, journalRows(), "corruption rejection must not manufacture evidence or repeat funding"),
                () -> assertFunding(EventSource.UPGRADE, 1));
    }

    @Test
    void creditReadyWithUnsolicitedCreditObservationCannotStartCredit() throws SQLException {
        Handle handle = started(EventSource.UPGRADE, run, State.CREDIT_READY);
        execute("""
                INSERT INTO npc_transfer_observations(attempt_id,leg,response_present,response_success,
                  reported_bits,before_bits,after_bits,threw) VALUES(?,'CREDIT',TRUE,TRUE,?,?,?,FALSE)
                """, handle.attemptId(), bits(CREDIT.reportedDollars()), bits(CREDIT.beforeDollars()), bits(CREDIT.afterDollars()));
        assertObservation(handle, Leg.DEBIT, DEBIT);
        assertObservation(handle, Leg.CREDIT, CREDIT);
        List<List<String>> before = journalRows();
        assertThrows(SQLException.class, () -> journal.beginCredit(handle),
                "existing credit evidence without a persisted credit intent must never authorize another credit");
        assertEquals(before, journalRows());
        assertEquals("CREDIT_READY", scalar("SELECT state FROM npc_transfer_attempts WHERE event_id=?", handle.eventId()));
        assertNull(scalar("SELECT credit_intent_at FROM npc_transfer_attempts WHERE event_id=?", handle.eventId()));
        assertLedger(handle, "CLAIMED", null);
        assertNoFunding();
    }

    @ParameterizedTest
    @EnumSource(value = State.class, names = {"DEBIT_INTENT", "CREDIT_READY", "DECLINED", "CREDIT_INTENT", "PAID"})
    void v4RejectsCreditIntentTimestampInconsistentWithState(State state) throws SQLException {
        Handle handle = started(EventSource.UPGRADE, run, state);
        List<List<String>> before = journalRows();
        boolean started = state == State.CREDIT_INTENT || state == State.PAID;
        assertThrows(SQLException.class, () -> execute("UPDATE npc_transfer_attempts SET credit_intent_at="
                + (started ? "NULL" : "CURRENT_TIMESTAMP(3)") + " WHERE event_id=?", handle.eventId()));
        assertEquals(before, journalRows());
    }

    @ParameterizedTest
    @ValueSource(strings = {"response_present", "response_success", "threw"})
    void v4RejectsNonBooleanEvidenceFlags(String column) throws SQLException {
        Handle handle = started(EventSource.UPGRADE, run, State.CREDIT_READY);
        List<List<String>> before = journalRows();
        for (int invalid : new int[]{-1, 2}) {
            assertThrows(SQLException.class, () -> execute("UPDATE npc_transfer_observations SET " + column
                    + "=? WHERE attempt_id=? AND leg='DEBIT'", invalid, handle.attemptId()));
        }
        assertEquals(before, journalRows());
        assertObservation(handle, Leg.DEBIT, DEBIT);
    }

    @ParameterizedTest(name = "{0}: {1}={2}")
    @MethodSource("rawObservations")
    void rawObservationsRoundTripWithoutCanonicalizingAndRejectConflictingAck(
            Leg leg, String field, String fixture, Double value, Double conflict) throws SQLException {
        Handle handle = started(EventSource.UPGRADE, run, leg == Leg.DEBIT ? State.DEBIT_INTENT : State.CREDIT_INTENT);
        Observation original = replaceValue(leg == Leg.DEBIT ? DEBIT : CREDIT, field, value);
        Observation changed = replaceValue(original, field, conflict);
        Receipt ambiguous = ack(journal, handle, leg, original);
        assertEquals(State.AMBIGUOUS, ambiguous.state());
        assertObservation(handle, leg, original);
        journal = new TransferJournal(database, reserve);
        List<List<String>> before = journalRows();
        assertEquals(ambiguous, ack(journal, handle, leg, original));
        assertThrows(SQLException.class, () -> ack(journal, handle, leg, changed));
        assertEquals(before, journalRows());
        assertNoFunding();
    }

    static Stream<Arguments> rawObservations() {
        List<Arguments> cases = new ArrayList<>();
        String[] names = {"null", "nan-payload", "negative-nan", "positive-infinity", "negative-infinity", "positive-zero", "negative-zero"};
        Double[] values = {null, Double.longBitsToDouble(0x7ff8000000000042L), Double.longBitsToDouble(0xfff8000000000099L),
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, +0.0, -0.0};
        Double[] conflicts = {+0.0, Double.longBitsToDouble(0x7ff8000000000043L), Double.longBitsToDouble(0xfff8000000000098L),
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -0.0, +0.0};
        for (Leg leg : Leg.values()) {
            for (String field : List.of("reported", "before", "after")) {
                for (int i = 0; i < values.length; i++) cases.add(Arguments.of(leg, field, names[i], values[i], conflicts[i]));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest
    @ValueSource(strings = {"present", "success", "threw"})
    void conflictingBooleanAcknowledgementCannotOverwriteEvidence(String field) throws SQLException {
        Handle handle = started(EventSource.UPGRADE, run, State.DEBIT_INTENT);
        Observation original = new Observation(false, false, null, null, null, true);
        journal.recordDebit(handle, original);
        Observation changed = new Observation(field.equals("present"), field.equals("success"), null, null, null, !field.equals("threw"));
        List<List<String>> before = journalRows();
        assertThrows(SQLException.class, () -> journal.recordDebit(handle, changed));
        assertEquals(before, journalRows());
        assertObservation(handle, Leg.DEBIT, original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"success-alone", "no-delta", "short-reported", "short-delta", "throw-after-debit", "throw-no-delta", "refusal-with-movement"})
    void uncertainDebitNeverPermitsCreditOrFunding(String fixture) throws SQLException {
        Handle handle = started(EventSource.UPGRADE, run, State.DEBIT_INTENT);
        Observation evidence = switch (fixture) {
            case "success-alone" -> new Observation(true, true, 10.0, null, null, false);
            case "no-delta" -> new Observation(true, true, 10.0, 100.0, 100.0, false);
            case "short-reported" -> new Observation(true, true, 9.0, 100.0, 90.0, false);
            case "short-delta" -> new Observation(true, true, 10.0, 100.0, 91.0, false);
            case "throw-after-debit" -> new Observation(true, true, 10.0, 100.0, 90.0, true);
            case "throw-no-delta" -> new Observation(false, false, null, 100.0, 100.0, true);
            case "refusal-with-movement" -> new Observation(true, false, 10.0, 100.0, 90.0, false);
            default -> throw new AssertionError(fixture);
        };
        Receipt result = journal.recordDebit(handle, evidence);
        assertEquals(State.AMBIGUOUS, result.state());
        assertReceipt(handle, State.AMBIGUOUS, false, "AMBIGUOUS", null);
        assertEquals(result, journal.recordDebit(handle, evidence));
        assertFalse(journal.beginDebit(handle).newlyStarted());
        assertFalse(journal.beginCredit(handle).newlyStarted());
        assertThrows(SQLException.class, () -> journal.recordCredit(handle, CREDIT));
        assertObservation(handle, Leg.DEBIT, evidence);
        assertTrue(journal.observation(handle.attemptId(), Leg.CREDIT).isEmpty());
        assertNoFunding();
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, 10.0})
    void explicitDebitRefusalWithZeroMovementDeclinesAndNeverStartsCredit(double reported) throws SQLException {
        Handle handle = started(EventSource.UPGRADE, run, State.DEBIT_INTENT);
        Observation refusal = new Observation(true, false, reported, 100.0, 100.0, false);
        Receipt result = journal.recordDebit(handle, refusal);
        assertEquals(State.DECLINED, result.state());
        assertReceipt(handle, State.DECLINED, false, "FAILED", 0L);
        assertObservation(handle, Leg.DEBIT, refusal);
        assertEquals(result, journal.recordDebit(handle, refusal));
        assertFalse(journal.beginDebit(handle).newlyStarted());
        assertFalse(journal.beginCredit(handle).newlyStarted());
        assertThrows(SQLException.class, () -> journal.recordCredit(handle, CREDIT));
        assertEquals(0, journal.recoverOtherRuns(UUID.randomUUID(), 100));
        assertEquals(1, count("npc_transfer_observations"));
        assertNoFunding();
    }

    @ParameterizedTest
    @ValueSource(strings = {"success-alone", "no-delta", "short-reported", "short-delta", "refusal", "throw"})
    void uncertainCreditAfterConfirmedDebitIsAmbiguousNotDeclinedOrPaid(String fixture) throws SQLException {
        Handle handle = started(EventSource.RENT, run, State.CREDIT_INTENT);
        Observation evidence = switch (fixture) {
            case "success-alone" -> new Observation(true, true, 10.0, null, null, false);
            case "no-delta" -> new Observation(true, true, 10.0, 20.0, 20.0, false);
            case "short-reported" -> new Observation(true, true, 9.0, 20.0, 30.0, false);
            case "short-delta" -> new Observation(true, true, 10.0, 20.0, 29.0, false);
            case "refusal" -> new Observation(true, false, 0.0, 20.0, 20.0, false);
            case "throw" -> new Observation(true, true, 10.0, 20.0, 30.0, true);
            default -> throw new AssertionError(fixture);
        };
        Receipt result = journal.recordCredit(handle, evidence);
        assertEquals(State.AMBIGUOUS, result.state());
        assertReceipt(handle, State.AMBIGUOUS, true, "AMBIGUOUS", null);
        assertEquals(result, journal.recordCredit(handle, evidence));
        assertFalse(journal.beginDebit(handle).newlyStarted());
        assertFalse(journal.beginCredit(handle).newlyStarted());
        assertObservation(handle, Leg.DEBIT, DEBIT);
        assertObservation(handle, Leg.CREDIT, evidence);
        assertNoFunding();
    }

    @ParameterizedTest
    @EnumSource(value = EventSource.class, names = {"RENT", "UPGRADE"})
    void missingFundingRetainsBothProofsButNeverMarksPaidOrRetriesFunding(EventSource source) throws SQLException {
        Handle handle = started(source, run, State.CREDIT_INTENT);
        execute("DELETE FROM npc_property_funding WHERE property_id=?", property);
        Receipt result = journal.recordCredit(handle, CREDIT);
        assertEquals(State.AMBIGUOUS, result.state());
        assertEquals("FUNDING_MISSING", result.outcome());
        assertReceipt(handle, State.AMBIGUOUS, true, "AMBIGUOUS", null);
        assertObservation(handle, Leg.DEBIT, DEBIT);
        assertObservation(handle, Leg.CREDIT, CREDIT);
        assertEquals(0, count("npc_property_funding"));
        execute("INSERT INTO npc_property_funding(property_id,one_time_seed_cents) VALUES(?,?)", property, SEED);
        assertEquals(result, journal.recordCredit(handle, CREDIT));
        assertFalse(journal.beginCredit(handle).newlyStarted());
        assertNoFunding();
    }

    @Test
    void claimSqlFailureRollsBackNewAttemptAndLedgerTogether() throws SQLException {
        Handle handle = pending(EventSource.UPGRADE, run);
        List<List<String>> before = eventRows(handle);
        execute("""
                CREATE TRIGGER transfer_claim_failure AFTER UPDATE ON npc_economy_events FOR EACH ROW
                BEGIN
                  IF NEW.state='CLAIMED' THEN
                    IF (SELECT COUNT(*) FROM npc_transfer_attempts WHERE event_id=NEW.event_id)=1 THEN
                      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='claim failed after persisted intent';
                    ELSE
                      SIGNAL SQLSTATE '45001' SET MESSAGE_TEXT='fixture never saw transfer intent';
                    END IF;
                  END IF;
                END
                """);
        SQLException failure = assertThrows(SQLException.class, () -> journal.beginDebit(handle));
        assertEquals("45000", failure.getSQLState());
        assertEquals(before, eventRows(handle));
        assertEquals(0, count("npc_transfer_attempts"));
        assertEquals(0, count("npc_transfer_observations"));
        assertNoFunding();
        execute("DROP TRIGGER transfer_claim_failure");
        assertTrue(journal.beginDebit(handle).newlyStarted());
        assertFalse(journal.beginDebit(handle).newlyStarted());
    }

    @ParameterizedTest
    @EnumSource(value = EventSource.class, names = {"RENT", "UPGRADE"})
    void failureAfterFundingAndEvidenceRollsBackEverythingThenOnlyAckRetryPaysOnce(EventSource source) throws SQLException {
        Handle handle = started(source, run, State.CREDIT_INTENT);
        List<List<String>> before = journalRows();
        // A distinct SQLSTATE proves this trigger actually saw funding, evidence and PAID ledger.
        execute("""
                CREATE TRIGGER transfer_paid_failure AFTER UPDATE ON npc_transfer_attempts FOR EACH ROW
                BEGIN
                  IF NEW.state='PAID' THEN
                    IF (SELECT COUNT(*) FROM npc_transfer_observations WHERE attempt_id=NEW.attempt_id)=2
                       AND (SELECT paid_amount_cents FROM npc_economy_events WHERE event_id=NEW.event_id)=NEW.amount_cents
                       AND (SELECT state FROM npc_economy_events WHERE event_id=NEW.event_id)='PAID'
                       AND (SELECT successful_cash_sinks_cents+successful_cash_payouts_cents
                            FROM npc_property_funding WHERE property_id=(SELECT property_id
                            FROM npc_economy_events WHERE event_id=NEW.event_id))=NEW.amount_cents THEN
                      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='paid failed after funding and both proofs';
                    ELSE
                      SIGNAL SQLSTATE '45001' SET MESSAGE_TEXT='fixture did not reach all paid writes';
                    END IF;
                  END IF;
                END
                """);
        SQLException failure = assertThrows(SQLException.class, () -> journal.recordCredit(handle, CREDIT));
        assertEquals("45000", failure.getSQLState());
        assertEquals(before, journalRows(), "credit evidence, attempt, timestamps, ledger and funding all roll back");
        assertReceipt(handle, State.CREDIT_INTENT, true, "CLAIMED", null);
        assertObservation(handle, Leg.DEBIT, DEBIT);
        assertTrue(journal.observation(handle.attemptId(), Leg.CREDIT).isEmpty());
        assertNoFunding();
        execute("DROP TRIGGER transfer_paid_failure");

        journal = new TransferJournal(database, reserve);
        assertFalse(journal.beginDebit(handle).newlyStarted());
        assertFalse(journal.beginCredit(handle).newlyStarted(), "commit retry must never request a second external credit");
        Receipt paid = journal.recordCredit(handle, CREDIT);
        assertEquals(State.PAID, paid.state());
        assertEquals(paid, journal.recordCredit(handle, CREDIT));
        assertReceipt(handle, State.PAID, true, "PAID", CENTS);
        assertObservation(handle, Leg.DEBIT, DEBIT);
        assertObservation(handle, Leg.CREDIT, CREDIT);
        assertFunding(source, 1);
    }

    // ---- Refusals before the money call, fractional balances, and the recovery paths built on them.

    private static final String OPERATOR = "staff:test";
    private static final String EVIDENCE = "e".repeat(64);
    private static Observation refused(Refusal reason) {
        return Observation.refused(reason, new IllegalStateException("private detail"), null, null);
    }
    private static Observation legacyThrow(double balance) {
        return new Observation(false, false, null, balance, balance, true);
    }

    @Test
    void refusedDebitIsADeclineThatKeepsItsReasonAndReleasesTheDay() throws Exception {
        Handle handle = started(EventSource.UTILITY, run, State.DEBIT_INTENT);
        Observation evidence = refused(Refusal.LEASE_REFUSED);
        Receipt declined = journal.recordDebit(handle, evidence);
        assertEquals(State.DECLINED, declined.state());
        assertEquals("DEBIT_REFUSED_BEFORE_CALL", declined.outcome());
        assertReceipt(handle, State.DECLINED, false, "FAILED", 0L);
        assertTrue(confirmedDeclined(handle.eventId()), "a later same-day bill may be prepared");
        Observation stored = journal.observation(handle.attemptId(), Leg.DEBIT).orElseThrow();
        assertEquals(Refusal.LEASE_REFUSED, stored.refusedBeforeCall());
        assertEquals("java.lang.IllegalStateException", stored.refusalCause());
        assertEquals(List.of(List.of("LEASE_REFUSED", "java.lang.IllegalStateException")),
                rows("SELECT refused_before_call,refusal_cause FROM npc_transfer_observations WHERE leg='DEBIT'"));
        assertEquals(declined, journal.recordDebit(handle, evidence), "an exact replay is accepted");
        assertNoFunding();
    }

    @Test
    void refusedCreditStaysUncertainWithItsOwnOutcome() throws Exception {
        Handle handle = started(EventSource.UPGRADE, run, State.CREDIT_INTENT);
        Receipt held = journal.recordCredit(handle, refused(Refusal.BALANCE_UNVERIFIABLE));
        assertEquals(State.AMBIGUOUS, held.state());
        assertEquals("CREDIT_REFUSED_BEFORE_CALL", held.outcome());
        assertReceipt(handle, State.AMBIGUOUS, true, "AMBIGUOUS", null);
        assertFalse(confirmedDeclined(handle.eventId()));
        assertNoFunding();
    }

    @Test
    void anAcknowledgementThatDiffersOnlyInTheRefusalConflicts() throws Exception {
        Handle handle = started(EventSource.UPGRADE, run, State.DEBIT_INTENT);
        journal.recordDebit(handle, refused(Refusal.LEASE_REFUSED));
        List<List<String>> before = journalRows();
        for (Observation other : List.of(refused(Refusal.LEASE_FAILED),
                Observation.refused(Refusal.LEASE_REFUSED, new java.util.NoSuchElementException(), null, null),
                new Observation(false, false, null, null, null, true))) {
            var conflict = assertThrows(SQLException.class, () -> journal.recordDebit(handle, other));
            assertEquals("Conflicting transfer acknowledgement", conflict.getMessage());
        }
        assertEquals(before, journalRows());
    }

    @ParameterizedTest
    @ValueSource(strings = {"reason-without-cause", "cause-without-reason", "unknown-reason", "with-response", "with-success",
            "with-reported", "without-throw", "bad-cause"})
    void theTableRefusesAnImpossibleRefusal(String fixture) throws Exception {
        Handle handle = started(EventSource.UPGRADE, run, State.DEBIT_INTENT);
        String reason = fixture.equals("cause-without-reason") ? "NULL" : fixture.equals("unknown-reason") ? "'MAYBE'" : "'LEASE_REFUSED'";
        String cause = fixture.equals("reason-without-cause") ? "NULL" : fixture.equals("bad-cause") ? "'has space'" : "'none'";
        String present = fixture.equals("with-response") ? "1" : "0", success = fixture.equals("with-success") ? "1" : "0";
        String reported = fixture.equals("with-reported") ? "0" : "NULL";
        String threw = fixture.equals("without-throw") ? "0" : "1";
        String insert = "INSERT INTO npc_transfer_observations(attempt_id,leg,response_present,response_success,reported_bits,"
                + "before_bits,after_bits,threw,refused_before_call,refusal_cause) VALUES(?,'DEBIT',%s,%s,%s,NULL,NULL,%s,%s,%s)";
        assertThrows(SQLException.class, () -> execute(String.format(insert, present, success, reported, threw, reason, cause),
                handle.attemptId()));
        // The positive control: the same row with every field right is accepted.
        execute(String.format(insert, "0", "0", "NULL", "1", "'LEASE_REFUSED'", "'none'"), handle.attemptId());
    }

    @Test
    void fractionalReserveAndOwnerBalancesArePaidAndRereadCleanly() throws Exception {
        Handle handle = pending(EventSource.RENT, run);
        assertTrue(journal.beginDebit(handle).newlyStarted());
        Observation debit = new Observation(true, true, 10.0, 57492.488404927248686, 57482.488404927248686, false);
        assertEquals(State.CREDIT_READY, journal.recordDebit(handle, debit).state());
        assertTrue(journal.beginCredit(handle).newlyStarted());
        Observation credit = new Observation(true, true, 10.0, 1234.5678901, 1244.5678901, false);
        assertEquals(State.PAID, journal.recordCredit(handle, credit).state());
        assertReceipt(handle, State.PAID, true, "PAID", CENTS);
        assertObservation(handle, Leg.DEBIT, debit);
        assertObservation(handle, Leg.CREDIT, credit);
        assertFunding(EventSource.RENT, 1);
        assertEquals(State.PAID, new TransferJournal(database, reserve).find(handle.eventId()).orElseThrow().state());
    }

    @Test
    void legacyRefusalIsRecordedOnlyForADebitTheOldGateRefused() throws Exception {
        Handle accepted = started(EventSource.UTILITY, run, State.DEBIT_INTENT);
        assertEquals(State.AMBIGUOUS, journal.recordDebit(accepted, legacyThrow(100.001)).state());
        UUID resolution = UUID.randomUUID();
        // A dry run checks and writes everything, then rolls it back.
        List<List<String>> before = journalRows();
        assertEquals(State.DECLINED, journal.resolveLegacyRefusal(accepted, resolution, OPERATOR, EVIDENCE, false).state());
        assertEquals(before, journalRows());
        assertEquals(0, resolutionCount());
        Receipt declined = journal.resolveLegacyRefusal(accepted, resolution, OPERATOR, EVIDENCE, true);
        assertEquals(State.DECLINED, declined.state());
        assertEquals("LEGACY_REFUSAL_VERIFIED", declined.outcome());
        assertReceipt(accepted, State.DECLINED, false, "FAILED", 0L);
        assertTrue(confirmedDeclined(accepted.eventId()));
        assertEquals(List.of(java.util.Arrays.asList("LEGACY_REFUSAL", null, null, null)),
                rows("SELECT kind,credit_proof,refund_before_bits,refund_after_bits FROM npc_transfer_resolutions"));
        assertEquals(declined, journal.resolveLegacyRefusal(accepted, resolution, OPERATOR, EVIDENCE, true), "exact replay");
        assertThrows(SQLException.class, () -> journal.resolveLegacyRefusal(accepted, resolution, "staff:other", EVIDENCE, true));
        assertThrows(SQLException.class, () -> journal.resolveRefundedDebit(accepted, resolution, OPERATOR, EVIDENCE, observedFile(),
                1L, 2L));

        Handle moved = started(EventSource.UTILITY, run, State.DEBIT_INTENT);
        journal.recordDebit(moved, new Observation(false, false, null, 100.001, 100.002, true));
        assertThrows(SQLException.class, () -> journal.resolveLegacyRefusal(moved, UUID.randomUUID(), OPERATOR, EVIDENCE, true),
                "before != after");
        Handle passed = started(EventSource.UTILITY, run, State.DEBIT_INTENT);
        journal.recordDebit(passed, legacyThrow(100.0));
        assertThrows(SQLException.class, () -> journal.resolveLegacyRefusal(passed, UUID.randomUUID(), OPERATOR, EVIDENCE, true),
                "the old gate would have passed: the call may have run");
        Handle credited = started(EventSource.UPGRADE, run, State.CREDIT_INTENT);
        journal.recordCredit(credited, legacyThrow(20.005));
        assertThrows(SQLException.class, () -> journal.resolveLegacyRefusal(credited, UUID.randomUUID(), OPERATOR, EVIDENCE, true),
                "the credit has started");
        assertEquals(1, resolutionCount());
    }

    @Test
    void aRefusedCreditIsRefundedByStaffAndThenProvesCompensated() throws Exception {
        Handle handle = started(EventSource.UPGRADE, run, State.CREDIT_INTENT);
        journal.recordCredit(handle, refused(Refusal.LEASE_REFUSED));
        UUID resolution = UUID.randomUUID();
        String file = observedFile();
        long refundBefore = bits(DEBIT.afterDollars()), refundAfter = bits(DEBIT.beforeDollars());
        assertThrows(SQLException.class, () -> journal.resolveRefundedDebit(handle, resolution, OPERATOR, EVIDENCE, file,
                refundAfter, refundBefore), "swapped bits");
        Receipt done = journal.resolveRefundedDebit(handle, resolution, OPERATOR, EVIDENCE, file, refundBefore, refundAfter);
        assertEquals(State.COMPENSATED, done.state());
        assertTrue(done.creditStarted());
        assertReceipt(handle, State.COMPENSATED, true, "FAILED", 0L);
        assertTrue(confirmedDeclined(handle.eventId()), "a refunded payout releases its reservation");
        assertEquals(List.of(List.of("REFUND", "REFUSED")), rows("SELECT kind,credit_proof FROM npc_transfer_resolutions"));
        assertEquals(done, journal.resolveRefundedDebit(handle, resolution, OPERATOR, EVIDENCE, file, refundBefore, refundAfter));
        assertNoFunding();
    }

    @Test
    void aCreditThatMayHaveBeenCalledCanNeverBeRefundedThisWay() throws Exception {
        Handle handle = started(EventSource.UPGRADE, run, State.CREDIT_INTENT);
        journal.recordCredit(handle, new Observation(false, false, null, 20.0, 20.0, true));
        long refundBefore = bits(DEBIT.afterDollars()), refundAfter = bits(DEBIT.beforeDollars());
        assertThrows(SQLException.class, () -> journal.resolveRefundedDebit(handle, UUID.randomUUID(), OPERATOR, EVIDENCE,
                observedFile(), refundBefore, refundAfter));
        // Not even with provenance: the stored credit is whole cents, so the old gate would have let it through.
        assertThrows(SQLException.class, () -> journal.resolveRefundedDebit(handle, UUID.randomUUID(), OPERATOR, EVIDENCE,
                observedFile(), refundBefore, refundAfter, true, true));
        assertReceipt(handle, State.AMBIGUOUS, true, "AMBIGUOUS", null);
        assertEquals(0, resolutionCount());
    }

    @Test
    void aLegacyRefusedCreditNeedsVerifiedProvenance() throws Exception {
        Handle handle = started(EventSource.UPGRADE, run, State.CREDIT_INTENT);
        journal.recordCredit(handle, legacyThrow(20.005));
        long refundBefore = bits(DEBIT.afterDollars()), refundAfter = bits(DEBIT.beforeDollars());
        assertThrows(SQLException.class, () -> journal.resolveRefundedDebit(handle, UUID.randomUUID(), OPERATOR, EVIDENCE,
                observedFile(), refundBefore, refundAfter), "no provenance");
        Receipt done = journal.resolveRefundedDebit(handle, UUID.randomUUID(), OPERATOR, EVIDENCE, observedFile(), refundBefore,
                refundAfter, true, true);
        assertEquals(State.COMPENSATED, done.state());
        assertEquals(List.of(List.of("REFUND", "LEGACY")), rows("SELECT kind,credit_proof FROM npc_transfer_resolutions"));
        assertReceipt(handle, State.COMPENSATED, true, "FAILED", 0L);
    }

    @Test
    void tamperedResolutionsNeverProveCompensation() throws Exception {
        // A legacy refusal row relabelled COMPENSATED: no refund kind, so no proof.
        Handle legacy = started(EventSource.UTILITY, run, State.DEBIT_INTENT);
        journal.recordDebit(legacy, legacyThrow(100.001));
        journal.resolveLegacyRefusal(legacy, UUID.randomUUID(), OPERATOR, EVIDENCE, true);
        execute("UPDATE npc_transfer_attempts SET state='COMPENSATED' WHERE event_id=?", legacy.eventId());
        assertEquals("Transfer phase and durable evidence disagree",
                assertThrows(SQLException.class, () -> journal.find(legacy.eventId())).getMessage());
        // A refund whose credit may have been called, labelled as refused: the stored credit decides, not the label.
        Handle called = started(EventSource.UPGRADE, run, State.CREDIT_INTENT);
        journal.recordCredit(called, new Observation(false, false, null, 20.0, 20.0, true));
        execute("""
                INSERT INTO npc_transfer_resolutions(attempt_id,resolution_id,operator_ref,evidence_sha256,original_outcome,
                refund_before_bits,refund_after_bits,kind,credit_proof) VALUES(?,?,?,?,'CREDIT_UNCERTAIN',?,?,'REFUND','REFUSED')
                """, called.attemptId(), UUID.randomUUID(), OPERATOR, EVIDENCE, bits(DEBIT.afterDollars()), bits(DEBIT.beforeDollars()));
        execute("UPDATE npc_economy_events SET state='FAILED',paid_amount_cents=0 WHERE event_id=?", called.eventId());
        execute("UPDATE npc_transfer_attempts SET state='COMPENSATED' WHERE event_id=?", called.eventId());
        assertEquals("Transfer phase and durable evidence disagree",
                assertThrows(SQLException.class, () -> journal.find(called.eventId())).getMessage());
        // The table itself refuses a legacy refusal carrying refund bits.
        Handle other = started(EventSource.UTILITY, run, State.DEBIT_INTENT);
        assertThrows(SQLException.class, () -> execute("""
                INSERT INTO npc_transfer_resolutions(attempt_id,resolution_id,operator_ref,evidence_sha256,original_outcome,
                refund_before_bits,refund_after_bits,kind) VALUES(?,?,?,?,'X',1,2,'LEGACY_REFUSAL')
                """, other.attemptId(), UUID.randomUUID(), OPERATOR, EVIDENCE));
    }

    @Test
    void heldRowsAreListedAndSortedWithoutChangingThem() throws Exception {
        Handle legacy = started(EventSource.UTILITY, run, State.DEBIT_INTENT);
        journal.recordDebit(legacy, legacyThrow(100.001));
        Handle refusedCredit = started(EventSource.UPGRADE, run, State.CREDIT_INTENT);
        journal.recordCredit(refusedCredit, refused(Refusal.LEASE_REFUSED));
        Handle unknown = started(EventSource.UPGRADE, run, State.AMBIGUOUS);
        started(EventSource.UPGRADE, run, State.PAID);
        Handle interrupted = started(EventSource.UPGRADE, run, State.DEBIT_INTENT);
        List<List<String>> before = journalRows();
        var held = journal.held(100);
        assertEquals(before, journalRows(), "classify only reads");
        assertEquals(journal.heldCount(), held.size(), "the classify total equals what the startup hold sees");
        assertEquals(4, held.size());
        var byEvent = new java.util.HashMap<Long, HeldClass>();
        for (var row : held) byEvent.put(row.eventId(), row.heldClass());
        assertEquals(HeldClass.D_LEGACY_DEBIT_REFUSAL, byEvent.get(legacy.eventId()));
        assertEquals(HeldClass.R_REFUSED_CREDIT, byEvent.get(refusedCredit.eventId()));
        assertEquals(HeldClass.U_UNPROVABLE, byEvent.get(unknown.eventId()));
        assertEquals(HeldClass.U_UNPROVABLE, byEvent.get(interrupted.eventId()));
        assertTrue(held.stream().allMatch(row -> row.createdAt() != null));
    }

    private boolean confirmedDeclined(long eventId) throws Exception {
        var method = TransferJournal.class.getDeclaredMethod("confirmedDeclined", Connection.class, long.class);
        method.setAccessible(true);
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            try { return (boolean) method.invoke(journal, c, eventId); }
            finally { c.rollback(); }
        }
    }

    private static long resolutionCount() throws SQLException {
        return Long.parseLong(rows("SELECT COUNT(*) FROM npc_transfer_resolutions").getFirst().getFirst());
    }

    private Handle pending(EventSource source, UUID runId) throws SQLException {
        long eventId = events.insertPending(EconomyEvent.pending("transfer|" + UUID.randomUUID(), CENTS, source,
                property, owner, 0, owner, DAY));
        return new Handle(UUID.randomUUID(), eventId, runId, PROVIDER);
    }

    private Handle started(EventSource source, UUID runId, State state) throws SQLException {
        Handle handle = pending(source, runId);
        assertTrue(journal.beginDebit(handle).newlyStarted());
        if (state == State.DECLINED) {
            assertEquals(state, journal.recordDebit(handle, new Observation(true, false, 0.0, 100.0, 100.0, false)).state());
        } else if (state == State.AMBIGUOUS) {
            assertEquals(state, journal.recordDebit(handle, new Observation(false, false, null, null, null, true)).state());
        } else if (state != State.DEBIT_INTENT) {
            assertEquals(State.CREDIT_READY, journal.recordDebit(handle, DEBIT).state());
            if (state != State.CREDIT_READY) {
                assertTrue(journal.beginCredit(handle).newlyStarted());
                if (state == State.PAID) assertEquals(state, journal.recordCredit(handle, CREDIT).state());
            }
        }
        assertEquals(state, journal.find(handle.eventId()).orElseThrow().state());
        return handle;
    }

    private void enroll(UUID id) throws SQLException {
        properties.insertEnrollment(new Property(id, WORLD, "transfer-" + id, "downtown", "room",
                2, 1_000, 1_000, owner, 0, 80, true), SEED);
    }

    private static Observation replaceValue(Observation o, String field, Double value) {
        return new Observation(o.responsePresent(), o.responseSuccess(), field.equals("reported") ? value : o.reportedDollars(),
                field.equals("before") ? value : o.beforeDollars(), field.equals("after") ? value : o.afterDollars(), o.threw());
    }

    private static Receipt ack(TransferJournal caller, Handle handle, Leg leg, Observation evidence) throws SQLException {
        return leg == Leg.DEBIT ? caller.recordDebit(handle, evidence) : caller.recordCredit(handle, evidence);
    }

    private void assertReceipt(Handle handle, State state, boolean creditStarted, String ledgerState, Long paid) throws SQLException {
        assertReceiptStateOnly(handle, state, creditStarted);
        assertLedger(handle, ledgerState, paid);
    }

    private void assertReceiptStateOnly(Handle handle, State state, boolean creditStarted) throws SQLException {
        Receipt receipt = journal.find(handle.eventId()).orElseThrow();
        assertEquals(handle, receipt.handle());
        assertEquals(state, receipt.state());
        assertEquals(creditStarted, receipt.creditStarted());
        assertEquals(state.name(), scalar("SELECT state FROM npc_transfer_attempts WHERE event_id=?", handle.eventId()));
        assertEquals(creditStarted, scalar("SELECT credit_intent_at FROM npc_transfer_attempts WHERE event_id=?", handle.eventId()) != null);
    }

    private static void assertLedger(Handle handle, String state, Long paid) throws SQLException {
        assertEquals(state, scalar("SELECT state FROM npc_economy_events WHERE event_id=?", handle.eventId()));
        assertEquals(paid == null ? null : paid.toString(), scalar("SELECT paid_amount_cents FROM npc_economy_events WHERE event_id=?", handle.eventId()));
        assertEquals(!state.equals("PENDING"), scalar("SELECT claimed_at FROM npc_economy_events WHERE event_id=?", handle.eventId()) != null);
        assertEquals(!state.equals("PENDING") && !state.equals("CLAIMED"),
                scalar("SELECT finalized_at FROM npc_economy_events WHERE event_id=?", handle.eventId()) != null);
    }

    private void assertObservation(Handle handle, Leg leg, Observation expected) throws SQLException {
        Observation actual = journal.observation(handle.attemptId(), leg).orElseThrow();
        assertEquals(expected.responsePresent(), actual.responsePresent());
        assertEquals(expected.responseSuccess(), actual.responseSuccess());
        assertEquals(expected.threw(), actual.threw());
        assertEquals(bits(expected.reportedDollars()), bits(actual.reportedDollars()));
        assertEquals(bits(expected.beforeDollars()), bits(actual.beforeDollars()));
        assertEquals(bits(expected.afterDollars()), bits(actual.afterDollars()));
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM npc_transfer_observations WHERE attempt_id=? AND leg=?")) {
            ps.setQueryTimeout(3);
            bind(ps, handle.attemptId(), leg.name());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertArrayEquals(Uuids.toBytes(handle.attemptId()), rs.getBytes("attempt_id"));
                assertEquals(leg.name(), rs.getString("leg"));
                assertEquals(expected.responsePresent(), rs.getBoolean("response_present"));
                assertEquals(expected.responseSuccess(), rs.getBoolean("response_success"));
                assertEquals(expected.threw(), rs.getBoolean("threw"));
                assertEquals(bits(expected.reportedDollars()), nullableLong(rs, "reported_bits"));
                assertEquals(bits(expected.beforeDollars()), nullableLong(rs, "before_bits"));
                assertEquals(bits(expected.afterDollars()), nullableLong(rs, "after_bits"));
                assertNotNull(rs.getTimestamp("observed_at"));
                assertFalse(rs.next());
            }
        }
    }

    private void assertNoFunding() throws SQLException {
        assertFunding(EventSource.UPGRADE, 0);
    }

    private void assertFunding(EventSource source, int payments) throws SQLException {
        long sinks = source.fundsRent() ? CENTS * payments : 0;
        long payouts = source.isPayout() ? CENTS * payments : 0;
        assertArrayEquals(new long[]{SEED, sinks, payouts}, properties.fundingOf(property));
        assertEquals(Long.toString(SEED), scalar("SELECT one_time_seed_cents FROM npc_property_funding WHERE property_id=?", property));
        assertEquals(Long.toString(sinks), scalar("SELECT successful_cash_sinks_cents FROM npc_property_funding WHERE property_id=?", property));
        assertEquals(Long.toString(payouts), scalar("SELECT successful_cash_payouts_cents FROM npc_property_funding WHERE property_id=?", property));
        long unfunded = source == EventSource.BILLS ? CENTS * payments : 0;
        assertEquals(Long.toString(unfunded), scalar("SELECT successful_cash_unfunded_cents FROM npc_property_funding WHERE property_id=?", property));
    }

    private boolean startOrIdentityConflict(Handle handle) throws SQLException {
        try {
            assertTrue(new TransferJournal(database, reserve).beginDebit(handle).newlyStarted());
            return true;
        } catch (SQLException failure) {
            Handle winner = journal.find(handle.eventId()).orElseThrow(() -> failure).handle();
            assertFalse(handle.equals(winner), "only a persisted different handle justifies the rejection");
            return false;
        }
    }

    private boolean ackOrConflict(Handle handle, Leg leg, Observation evidence) throws SQLException {
        try {
            ack(new TransferJournal(database, reserve), handle, leg, evidence);
            return true;
        } catch (SQLException failure) {
            Observation winner = journal.observation(handle.attemptId(), leg).orElseThrow(() -> failure);
            assertFalse(bits(evidence.reportedDollars()).equals(bits(winner.reportedDollars())),
                    "a SQL timeout/deadlock must not masquerade as a conflicting ack");
            return false;
        }
    }

    private <T> List<T> behindPropertyLock(Callable<T> left, Callable<T> right) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<T> first;
            Future<T> second;
            try (Connection blocker = database.getConnection()) {
                blocker.setAutoCommit(false);
                try {
                    lockProperty(blocker);
                    first = workers.submit(left);
                    second = workers.submit(right);
                    assertLockingWaiters(2);
                    assertFalse(first.isDone());
                    assertFalse(second.isDone());
                } finally {
                    blocker.rollback();
                }
            }
            return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS), "journal workers terminated");
        }
    }

    @FunctionalInterface
    private interface SqlChange { void run() throws SQLException; }

    private <T> T whilePropertyLocked(Callable<T> work, SqlChange committedChange) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<T> result;
            try (Connection blocker = database.getConnection()) {
                blocker.setAutoCommit(false);
                try {
                    lockProperty(blocker);
                    result = worker.submit(work);
                    assertLockingWaiters(1);
                    assertFalse(result.isDone());
                    committedChange.run();
                    assertFalse(result.isDone());
                } finally {
                    blocker.rollback();
                }
            }
            return result.get(15, TimeUnit.SECONDS);
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(15, TimeUnit.SECONDS), "journal metadata waiter terminated");
        }
    }

    private void lockProperty(Connection blocker) throws SQLException {
        try (PreparedStatement ps = blocker.prepareStatement("SELECT property_id FROM npc_properties WHERE property_id=? FOR UPDATE")) {
            ps.setQueryTimeout(3);
            bind(ps, property);
            try (ResultSet rs = ps.executeQuery()) { assertTrue(rs.next()); }
        }
    }

    private static void assertLockingWaiters(int expected) throws SQLException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        List<String> queries = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            queries.clear();
            try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement("""
                    SELECT INFO FROM information_schema.PROCESSLIST
                    WHERE ID<>CONNECTION_ID() AND DB=? AND INFO LIKE '%npc_properties%' LIMIT 6
                    """)) {
                ps.setQueryTimeout(1);
                ps.setString(1, schemaName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) queries.add(rs.getString(1));
                }
            }
            if (queries.size() == expected) {
                for (String query : queries) {
                    String sql = query.stripLeading().toLowerCase(Locale.ROOT).replaceFirst(
                            "^set\\s+statement\\s+max_statement_time\\s*=\\s*\\d+(?:\\.\\d+)?\\s+for\\s+", "");
                    assertTrue(sql.matches("(?s)select\\s+.*\\s+for\\s+update\\s*"),
                            "worker must wait on a locking read, not a write after a stale read: " + query);
                }
                return;
            }
            // Observation of the held row's locking SELECT is the proof, never elapsed time.
            Thread.yield();
        }
        throw new AssertionError("expected " + expected + " initial property lock waiters, saw " + queries);
    }

    private static Long bits(Double value) {
        return value == null ? null : Double.doubleToRawLongBits(value);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static void eventChange(Handle handle, String set, Object... values) throws SQLException {
        List<Object> args = new ArrayList<>(java.util.Arrays.asList(values));
        args.add(handle.eventId());
        execute("UPDATE npc_economy_events SET " + set + " WHERE event_id=?", args.toArray());
    }

    private static List<List<String>> eventRows(Handle handle) throws SQLException {
        return rows("SELECT * FROM npc_economy_events WHERE event_id=?", handle.eventId());
    }

    private static List<List<String>> attemptRows(Handle handle) throws SQLException {
        return rows("SELECT * FROM npc_transfer_attempts WHERE event_id=?", handle.eventId());
    }

    private static List<List<String>> observationRows() throws SQLException {
        return rows("SELECT * FROM npc_transfer_observations ORDER BY attempt_id,leg");
    }

    private static List<List<String>> journalRows() throws SQLException {
        List<List<String>> result = new ArrayList<>();
        result.addAll(rows("SELECT * FROM npc_economy_events ORDER BY event_id"));
        result.addAll(rows("SELECT * FROM npc_transfer_attempts ORDER BY event_id"));
        result.addAll(observationRows());
        result.addAll(rows("SELECT * FROM npc_property_funding ORDER BY property_id"));
        return result;
    }

    private static List<List<String>> rows(String sql, Object... values) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(3);
            bind(ps, values);
            try (ResultSet rs = ps.executeQuery()) {
                List<List<String>> result = new ArrayList<>();
                while (rs.next()) {
                    List<String> row = new ArrayList<>();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                        Object value = rs.getObject(i);
                        row.add(value instanceof byte[] bytes ? java.util.HexFormat.of().formatHex(bytes) : rs.getString(i));
                    }
                    result.add(row);
                }
                return result;
            }
        }
    }

    private static long count(String table) throws SQLException {
        if (!List.of("npc_transfer_attempts", "npc_transfer_observations", "npc_property_funding").contains(table)) {
            throw new IllegalArgumentException(table);
        }
        return Long.parseLong(scalar("SELECT COUNT(*) FROM " + table));
    }

    private static String scalar(String sql, Object... values) throws SQLException {
        List<List<String>> result = rows(sql, values);
        assertEquals(1, result.size(), sql);
        return result.getFirst().getFirst();
    }

    private static void execute(String sql, Object... values) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(3);
            bind(ps, values);
            ps.execute();
        }
    }

    private static void bind(PreparedStatement ps, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) {
            if (values[i] instanceof UUID uuid) ps.setBytes(i + 1, Uuids.toBytes(uuid));
            else ps.setObject(i + 1, values[i]);
        }
    }

    /** The SHA-256 of a new refund-observed.json: each staff refund is recorded in its own file. */
    private static String observedFile() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
    }

    private static void applyResource(String path) throws Exception {
        try (InputStream in = TransferJournalIT.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String statement : MigrationRunner.splitStatements(sql)) execute(statement);
        }
    }
}

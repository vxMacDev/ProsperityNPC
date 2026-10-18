package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.economy.TransferJournal.Handle;
import com.prosperitysmp.npc.economy.TransferJournal.Held;
import com.prosperitysmp.npc.economy.TransferJournal.State;
import com.prosperitysmp.npc.economy.TransferProof.Observation;
import com.prosperitysmp.npc.economy.TransferProof.Refusal;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The read-only classify report the recovery tool prints: every row the startup hold counts, sorted by stored
 * evidence alone. One fresh database holds exactly the rows built here, so every count is exact.
 */
@Timeout(300)
class RefundRecoveryToolClassifyIT {
    private static final String PROVIDER = "a".repeat(64);
    private static final Observation MOVED_DEBIT = new Observation(true, true, 10.0, 100.0, 90.0, false);
    private static final Observation MOVED_CREDIT = new Observation(true, true, 10.0, 20.0, 30.0, false);
    private static EconomyTestDatabase db;
    private static TransferJournal journal;
    private static EconomyEventRepository events;
    private static UUID owner;
    private static UUID property;
    private static long debitIntent, creditReady, refusedCredit, legacyDebit, unprovableDebit;

    @BeforeAll
    static void boot() throws Exception {
        db = EconomyTestDatabase.start("npc_classify");
        UUID reserve = UUID.randomUUID();
        journal = new TransferJournal(db.database, reserve);
        events = new EconomyEventRepository(db.database);
        owner = UUID.randomUUID();
        property = db.enroll(owner, 50_000);
        assertEquals(empty(), RefundRecoveryTool.classifyReport(journal), "an empty journal holds nothing");

        debitIntent = begun().eventId();

        Handle ready = begun();
        assertEquals(State.CREDIT_READY, journal.recordDebit(ready, MOVED_DEBIT).state());
        creditReady = ready.eventId();

        Handle refused = begun();
        journal.recordDebit(refused, MOVED_DEBIT);
        assertTrue(journal.beginCredit(refused).newlyStarted());
        assertEquals(State.AMBIGUOUS, journal.recordCredit(refused,
                Observation.refused(Refusal.LEASE_REFUSED, null, null, null)).state());
        refusedCredit = refused.eventId();

        Handle legacy = begun();
        assertEquals(State.AMBIGUOUS, journal.recordDebit(legacy,
                new Observation(false, false, null, 100.001, 100.001, true)).state());
        legacyDebit = legacy.eventId();

        Handle threw = begun();
        assertEquals(State.AMBIGUOUS, journal.recordDebit(threw, new Observation(true, true, 10.0, 100.0, 90.0, true)).state());
        unprovableDebit = threw.eventId();

        Handle paid = begun();
        journal.recordDebit(paid, MOVED_DEBIT);
        journal.beginCredit(paid);
        assertEquals(State.PAID, journal.recordCredit(paid, MOVED_CREDIT).state());
    }

    @AfterAll
    static void stop() throws Exception {
        if (db != null) db.close();
    }

    private static String empty() {
        return "Transfer journal rows holding payments: 0\n  D_LEGACY_DEBIT_REFUSAL: 0\n  C_LEGACY_CREDIT_REFUSAL: 0"
                + "\n  R_REFUSED_CREDIT: 0\n  E_NOW_PROVABLE: 0\n  U_UNPROVABLE: 0";
    }

    private static Handle begun() throws SQLException {
        long event = events.insertPending(EconomyEvent.pending("classify|" + UUID.randomUUID(), 1_000, EventSource.UPGRADE,
                property, owner, 0, owner, "2026-10-14"));
        Handle handle = new Handle(UUID.randomUUID(), event, UUID.randomUUID(), PROVIDER);
        assertTrue(journal.beginDebit(handle).newlyStarted());
        return handle;
    }

    @Test
    void theReportCountsEveryHeldRowByItsClassAndLeavesThePaidOneOut() throws Exception {
        assertEquals("Transfer journal rows holding payments: 5\n  D_LEGACY_DEBIT_REFUSAL: 1\n  C_LEGACY_CREDIT_REFUSAL: 0"
                        + "\n  R_REFUSED_CREDIT: 1\n  E_NOW_PROVABLE: 0\n  U_UNPROVABLE: 3",
                RefundRecoveryTool.classifyReport(journal));
    }

    @Test
    void heldRowsComeOldestFirstWithTheirStateAndClass() throws SQLException {
        List<Held> held = journal.held(100);
        assertEquals(List.of(debitIntent, creditReady, refusedCredit, legacyDebit, unprovableDebit),
                held.stream().map(Held::eventId).toList());
        assertEquals(List.of(State.DEBIT_INTENT, State.CREDIT_READY, State.AMBIGUOUS, State.AMBIGUOUS, State.AMBIGUOUS),
                held.stream().map(Held::state).toList());
        assertEquals(List.of(HeldClass.U_UNPROVABLE, HeldClass.U_UNPROVABLE, HeldClass.R_REFUSED_CREDIT,
                HeldClass.D_LEGACY_DEBIT_REFUSAL, HeldClass.U_UNPROVABLE), held.stream().map(Held::heldClass).toList());
    }

    @Test
    void eachHeldRowCarriesTheEvidenceStoredForIt() throws SQLException {
        List<Held> held = journal.held(100);
        assertNull(held.get(0).debit(), "a debit intent has no observation yet");
        assertEquals(MOVED_DEBIT, held.get(1).debit());
        assertNull(held.get(1).credit());
        assertFalse(held.get(1).creditStarted());
        assertTrue(held.get(2).creditStarted());
        assertEquals(Refusal.LEASE_REFUSED, held.get(2).credit().refusedBeforeCall());
        for (Held row : held) {
            assertEquals(1_000, row.cents());
            assertNotNull(row.createdAt());
        }
    }

    @Test
    void theHeldListIsBoundedAndTheCountMatchesIt() throws SQLException {
        assertEquals(5, journal.heldCount());
        assertEquals(List.of(debitIntent, creditReady), journal.held(2).stream().map(Held::eventId).toList());
        assertEquals(List.of(), journal.held(0));
        assertEquals(List.of(), journal.held(-1));
        assertEquals(5, journal.held(Integer.MAX_VALUE).size());
    }
}

package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.economy.TransferJournal.Handle;
import com.prosperitysmp.npc.economy.TransferJournal.State;
import com.prosperitysmp.npc.economy.TransferProof.Observation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The journal-proved rent a bill formula reads as rent_paid_cents: only PAID rent events for the home and day whose
 * transfer proof holds, read inside the caller's transaction.
 */
@Timeout(300)
class BillPaymentsRentProofIT {
    private static final String PROVIDER = "a".repeat(64);
    private static final LocalDate DAY = LocalDate.of(2026, 10, 14);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), ZoneOffset.UTC);
    private static EconomyTestDatabase db;
    private static UUID reserve;
    private static TransferJournal journal;
    private static EconomyEventRepository events;
    private static BillPayments bills;
    private UUID owner;
    private UUID property;

    @BeforeAll
    static void boot() throws Exception {
        db = EconomyTestDatabase.start("npc_bill_rent");
        reserve = UUID.randomUUID();
        journal = new TransferJournal(db.database, reserve);
        events = new EconomyEventRepository(db.database);
        bills = new BillPayments(db.database, reserve, CLOCK);
    }

    @AfterAll
    static void stop() throws Exception {
        if (db != null) db.close();
    }

    @BeforeEach
    void freshHome() throws SQLException {
        owner = UUID.randomUUID();
        property = db.enroll(owner, 1_000_000);
    }

    private long proved(UUID home, LocalDate day) throws SQLException {
        try (Connection c = db.database.getConnection()) {
            c.setAutoCommit(false);
            try {
                return bills.provedRentPaidCents(c, home, day);
            } finally {
                c.rollback();
            }
        }
    }

    private static Handle started(EventSource source, long cents, UUID home, UUID payer, LocalDate day) throws SQLException {
        long event = events.insertPending(EconomyEvent.pending("rent-proof|" + UUID.randomUUID(), cents, source, home, payer, 0,
                payer, day.toString()));
        Handle handle = new Handle(UUID.randomUUID(), event, UUID.randomUUID(), PROVIDER);
        assertTrue(journal.beginDebit(handle).newlyStarted());
        double dollars = cents / 100.0;
        assertEquals(State.CREDIT_READY, journal.recordDebit(handle,
                new Observation(true, true, dollars, 50_000.0, 50_000.0 - dollars, false, null, null)).state());
        assertTrue(journal.beginCredit(handle).newlyStarted());
        return handle;
    }

    /** A rent payout whose debit and credit are both proved: the ledger row and the journal say PAID. */
    private static long journaledRent(long cents, UUID home, UUID payee, LocalDate day) throws SQLException {
        Handle handle = started(EventSource.RENT, cents, home, payee, day);
        double dollars = cents / 100.0;
        assertEquals(State.PAID, journal.recordCredit(handle,
                new Observation(true, true, dollars, 100.0, 100.0 + dollars, false, null, null)).state());
        return handle.eventId();
    }

    /** A rent row marked PAID in the ledger alone, with no transfer attempt behind it. */
    private static long ledgerOnlyRent(long cents, UUID home, UUID payee, LocalDate day) throws SQLException {
        long id = events.insertPending(EconomyEvent.pending("ledger-only|" + UUID.randomUUID(), cents, EventSource.RENT, home,
                payee, 0, payee, day.toString()));
        assertTrue(events.claim(id));
        events.finalizePaid(id, cents);
        return id;
    }

    @Test
    void aHomeWithNoRentThatDayHasZeroProvedRent() throws SQLException {
        assertEquals(0, proved(property, DAY));
    }

    @Test
    void provedRentSumsOnlyThatHomesPaidRentForThatDay() throws SQLException {
        journaledRent(1_000, property, owner, DAY);
        journaledRent(2_550, property, owner, DAY);
        journaledRent(750, property, owner, DAY.minusDays(1));
        UUID neighbourOwner = UUID.randomUUID();
        UUID neighbour = db.enroll(neighbourOwner, 1_000_000);
        journaledRent(500, neighbour, neighbourOwner, DAY);
        assertEquals(3_550, proved(property, DAY));
        assertEquals(750, proved(property, DAY.minusDays(1)));
        assertEquals(500, proved(neighbour, DAY));
        assertEquals(0, proved(property, DAY.plusDays(1)));
    }

    @Test
    void aRentRowPaidInTheLedgerWithoutTransferProofIsNotCounted() throws SQLException {
        journaledRent(1_200, property, owner, DAY);
        ledgerOnlyRent(9_999, property, owner, DAY);
        assertEquals(1_200, proved(property, DAY));
    }

    @Test
    void aRentPaymentWhoseCreditIsUnresolvedIsNotCounted() throws SQLException {
        journaledRent(400, property, owner, DAY);
        started(EventSource.RENT, 600, property, owner, DAY);
        assertEquals(400, proved(property, DAY));
    }

    @Test
    void paidSinksOnTheSameHomeAndDayAreNotRent() throws SQLException {
        Handle utility = started(EventSource.UTILITY, 300, property, owner, DAY);
        assertEquals(State.PAID, journal.recordCredit(utility,
                new Observation(true, true, 3.0, 100.0, 103.0, false, null, null)).state());
        journaledRent(800, property, owner, DAY);
        assertEquals(800, proved(property, DAY));
    }

    @Test
    void thirtyTwoRentRowsAreReadButAThirtyThirdIsRefused() throws SQLException {
        for (int i = 0; i < 32; i++) ledgerOnlyRent(1 + i, property, owner, DAY);
        assertEquals(0, proved(property, DAY), "ledger-only rows are read and skipped");
        ledgerOnlyRent(100, property, owner, DAY);
        SQLException refused = assertThrows(SQLException.class, () -> proved(property, DAY));
        assertEquals("Too many rent events for one home and day", refused.getMessage());
    }

    @Test
    void readingProofOutsideACallerTransactionIsRefused() throws SQLException {
        journaledRent(1_000, property, owner, DAY);
        try (Connection c = db.database.getConnection()) {
            assertTrue(c.getAutoCommit());
            SQLException refused = assertThrows(SQLException.class, () -> bills.provedRentPaidCents(c, property, DAY));
            assertEquals("Payment proof requires caller-owned transaction", refused.getMessage());
        }
    }
}

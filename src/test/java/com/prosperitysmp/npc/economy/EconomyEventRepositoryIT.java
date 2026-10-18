package com.prosperitysmp.npc.economy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The economy-event ledger against a real MariaDB: idempotency, the claim lifecycle, funding and daily sums. */
@Timeout(300)
class EconomyEventRepositoryIT {
    private static final String INVALID = "event requires a nonblank idempotency key, source and positive amount";
    private static final AtomicInteger DAYS = new AtomicInteger();
    private static EconomyTestDatabase db;
    private static EconomyEventRepository events;

    @BeforeAll
    static void boot() throws Exception {
        db = EconomyTestDatabase.start("npc_events");
        events = new EconomyEventRepository(db.database);
    }

    @AfterAll
    static void stop() throws Exception {
        if (db != null) db.close();
    }

    /** A day no other test writes, so day sums are exact. */
    private static String freshDay() {
        return java.time.LocalDate.of(2030, 1, 1).plusDays(DAYS.incrementAndGet()).toString();
    }

    private static String key() { return "it|" + UUID.randomUUID(); }

    private static long paid(EventSource source, long cents, UUID property, UUID unit, String day) throws SQLException {
        long id = events.insertPending(EconomyEvent.pending(key(), cents, source, property, new UUID(1, 1), 0, unit, day));
        assertTrue(events.claim(id));
        events.finalizePaid(id, cents);
        return id;
    }

    private static String state(long id) throws SQLException {
        return db.scalarString("SELECT state FROM npc_economy_events WHERE event_id=?", id);
    }

    private static long funding(String column, UUID property) throws SQLException {
        return db.scalarLong("SELECT " + column + " FROM npc_property_funding WHERE property_id=?", property);
    }

    @Test
    void aPendingInsertStoresEveryFieldOfTheRequest() throws SQLException {
        UUID property = db.enroll(UUID.randomUUID(), 0);
        UUID payer = UUID.randomUUID(), unit = UUID.randomUUID();
        String key = key(), day = freshDay();
        long id = events.insertPending(EconomyEvent.pending(key, 1_234, EventSource.UTILITY, property, payer, 5, unit, day));
        assertTrue(id > 0);
        assertEquals(key, db.scalarString("SELECT idempotency_key FROM npc_economy_events WHERE event_id=?", id));
        assertEquals(1_234, db.scalarLong("SELECT requested_amount_cents FROM npc_economy_events WHERE event_id=?", id));
        assertEquals(1, db.scalarLong("SELECT COUNT(*) FROM npc_economy_events WHERE event_id=? AND paid_amount_cents IS NULL"
                + " AND claimed_at IS NULL AND finalized_at IS NULL", id));
        assertEquals("npc-property-utility", db.scalarString("SELECT source FROM npc_economy_events WHERE event_id=?", id));
        assertEquals("TRANSFER_FROM_PUBLIC", db.scalarString("SELECT direction FROM npc_economy_events WHERE event_id=?", id));
        assertEquals(1, db.scalarLong("SELECT COUNT(*) FROM npc_economy_events WHERE event_id=? AND property_id=?"
                + " AND counterparty_uuid=? AND benefit_unit_id=?", id, property, payer, unit));
        assertEquals(5, db.scalarLong("SELECT ownership_epoch FROM npc_economy_events WHERE event_id=?", id));
        assertEquals(day, db.scalarString("SELECT economy_day FROM npc_economy_events WHERE event_id=?", id));
        assertEquals("PENDING", state(id));
    }

    @Test
    void nullOptionalFieldsAreStoredAsNull() throws SQLException {
        long id = events.insertPending(EconomyEvent.pending(key(), 1, EventSource.RENT, null, null, null, null, freshDay()));
        assertEquals(1, db.scalarLong("SELECT COUNT(*) FROM npc_economy_events WHERE event_id=? AND property_id IS NULL"
                + " AND counterparty_uuid IS NULL AND ownership_epoch IS NULL AND benefit_unit_id IS NULL", id));
    }

    @Test
    void theSameRequestAgainIsANoOpReturningMinusOne() throws SQLException {
        UUID property = db.enroll(UUID.randomUUID(), 0);
        EconomyEvent event = EconomyEvent.pending(key(), 900, EventSource.REPAIR, property, new UUID(2, 2), 1, new UUID(3, 3),
                freshDay());
        long id = events.insertPending(event);
        assertTrue(id > 0);
        assertEquals(-1L, events.insertPending(event));
        assertEquals(-1L, events.insertPending(EconomyEvent.pending(event.idempotencyKey(), 900, EventSource.REPAIR, property,
                new UUID(2, 2), 1, new UUID(3, 3), event.economyDay())));
        assertEquals(1, db.scalarLong("SELECT COUNT(*) FROM npc_economy_events WHERE idempotency_key=?", event.idempotencyKey()));
    }

    @Test
    void aConflictingPayloadUnderTheSameKeyIsRefusedNotTreatedAsARetry() throws SQLException {
        String key = key(), day = freshDay();
        UUID payer = new UUID(4, 4);
        events.insertPending(EconomyEvent.pending(key, 500, EventSource.UPGRADE, null, payer, 0, null, day));
        List<EconomyEvent> conflicts = List.of(
                EconomyEvent.pending(key, 501, EventSource.UPGRADE, null, payer, 0, null, day),
                EconomyEvent.pending(key, 500, EventSource.REPAIR, null, payer, 0, null, day),
                EconomyEvent.pending(key, 500, EventSource.UPGRADE, null, new UUID(4, 5), 0, null, day),
                EconomyEvent.pending(key, 500, EventSource.UPGRADE, null, payer, 1, null, day),
                EconomyEvent.pending(key, 500, EventSource.UPGRADE, null, payer, null, null, day),
                EconomyEvent.pending(key, 500, EventSource.UPGRADE, null, payer, 0, new UUID(4, 6), day),
                EconomyEvent.pending(key, 500, EventSource.UPGRADE, null, payer, 0, null, freshDay()));
        for (EconomyEvent conflict : conflicts)
            assertThrows(SQLException.class, () -> events.insertPending(conflict), conflict.toString());
        assertEquals(1, db.scalarLong("SELECT COUNT(*) FROM npc_economy_events WHERE idempotency_key=?", key));
        assertEquals(500, db.scalarLong("SELECT requested_amount_cents FROM npc_economy_events WHERE idempotency_key=?", key));
    }

    @Test
    void aKeyThatDiffersOnlyInCaseIsRefusedRatherThanReadAsTheSameRequest() throws SQLException {
        String suffix = UUID.randomUUID().toString();
        events.insertPending(EconomyEvent.pending("Case|" + suffix, 100, EventSource.UTILITY, null, null, null, null, "2030-01-01"));
        SQLException refused = assertThrows(SQLException.class, () -> events.insertPending(
                EconomyEvent.pending("case|" + suffix, 100, EventSource.UTILITY, null, null, null, null, "2030-01-01")));
        assertEquals(1062, refused.getErrorCode());
    }

    @Test
    void anInvalidEventIsRefusedBeforeAnyStatement() {
        List<EconomyEvent> invalid = new ArrayList<>();
        invalid.add(null);
        invalid.add(EconomyEvent.pending(null, 1, EventSource.RENT, null, null, null, null, "2030-01-01"));
        invalid.add(EconomyEvent.pending("", 1, EventSource.RENT, null, null, null, null, "2030-01-01"));
        invalid.add(EconomyEvent.pending("  ", 1, EventSource.RENT, null, null, null, null, "2030-01-01"));
        invalid.add(EconomyEvent.pending("k", 1, null, null, null, null, null, "2030-01-01"));
        invalid.add(EconomyEvent.pending("k", 0, EventSource.RENT, null, null, null, null, "2030-01-01"));
        invalid.add(EconomyEvent.pending("k", -1, EventSource.RENT, null, null, null, null, "2030-01-01"));
        invalid.add(EconomyEvent.pending("k", Long.MIN_VALUE, EventSource.RENT, null, null, null, null, "2030-01-01"));
        for (EconomyEvent event : invalid) {
            var c = new EconomyProxyConnection(false);
            SQLException refused = assertThrows(SQLException.class, () -> EconomyEventRepository.insertPending(c.connection, event));
            assertEquals(INVALID, refused.getMessage());
            assertEquals(List.of(), c.calls, String.valueOf(event));
        }
    }

    @Test
    void aClaimMovesPendingToClaimedExactlyOnce() throws SQLException {
        long id = events.insertPending(EconomyEvent.pending(key(), 10, EventSource.UTILITY, null, null, null, null, freshDay()));
        assertTrue(events.claim(id));
        assertFalse(events.claim(id));
        assertEquals("CLAIMED", state(id));
        assertEquals(1, db.scalarLong("SELECT COUNT(*) FROM npc_economy_events WHERE event_id=? AND claimed_at IS NOT NULL", id));
        assertFalse(events.claim(Long.MAX_VALUE));
        events.finalizeFailed(id);
    }

    @Test
    void aPaidAmountOtherThanTheRequestedAmountIsRefusedAndBooksNothing() throws SQLException {
        UUID property = db.enroll(UUID.randomUUID(), 0);
        long id = events.insertPending(EconomyEvent.pending(key(), 700, EventSource.UTILITY, property, null, 0, null, freshDay()));
        assertTrue(events.claim(id));
        for (long wrong : new long[]{699, 701, 0, -700}) {
            SQLException refused = assertThrows(SQLException.class, () -> events.finalizePaid(id, wrong));
            assertEquals("paid amount must equal the positive requested amount for event " + id, refused.getMessage());
        }
        assertEquals("CLAIMED", state(id));
        assertEquals(0, funding(FundingColumns.SINKS, property));
        events.finalizePaid(id, 700);
        assertEquals("PAID", state(id));
        assertEquals(700, funding(FundingColumns.SINKS, property));
    }

    @Test
    void finalizingAnUnclaimedOrMissingEventBooksNothing() throws SQLException {
        UUID property = db.enroll(UUID.randomUUID(), 0);
        long id = events.insertPending(EconomyEvent.pending(key(), 300, EventSource.UPGRADE, property, null, 0, null, freshDay()));
        events.finalizePaid(id, 300);
        events.finalizePaid(Long.MAX_VALUE, 300);
        assertEquals("PENDING", state(id));
        assertEquals(1, db.scalarLong("SELECT COUNT(*) FROM npc_economy_events WHERE event_id=? AND paid_amount_cents IS NULL", id));
        assertEquals(0, funding(FundingColumns.SINKS, property));
    }

    @ParameterizedTest
    @EnumSource(EventSource.class)
    void aPaidEventAddsItsAmountToExactlyOneFundingColumn(EventSource source) throws SQLException {
        UUID property = db.enroll(UUID.randomUUID(), 25_000);
        paid(source, 1_500, property, null, freshDay());
        String column = FundingColumns.fundingColumn(source);
        for (String each : List.of(FundingColumns.PAYOUTS, FundingColumns.SINKS, FundingColumns.UNFUNDED))
            assertEquals(each.equals(column) ? 1_500 : 0, funding(each, property), each);
        assertEquals(25_000, funding("one_time_seed_cents", property));
    }

    @Test
    void aSecondFinalizeOfAPaidEventAddsNothingMore() throws SQLException {
        UUID property = db.enroll(UUID.randomUUID(), 0);
        long id = paid(EventSource.RENT, 400, property, null, freshDay());
        events.finalizePaid(id, 400);
        assertEquals(400, funding(FundingColumns.PAYOUTS, property));
        assertEquals(400, db.scalarLong("SELECT paid_amount_cents FROM npc_economy_events WHERE event_id=?", id));
    }

    @Test
    void anEventWithoutAPropertyIsPaidWithoutAnyFundingRow() throws SQLException {
        long id = paid(EventSource.UTILITY, 250, null, null, freshDay());
        assertEquals("PAID", state(id));
        assertEquals(250, db.scalarLong("SELECT paid_amount_cents FROM npc_economy_events WHERE event_id=?", id));
        assertEquals(1, db.scalarLong("SELECT COUNT(*) FROM npc_economy_events WHERE event_id=? AND finalized_at IS NOT NULL", id));
    }

    @Test
    void aPaidEventForAHomeWithNoFundingRowIsRolledBack() throws SQLException {
        UUID orphan = UUID.randomUUID();
        long id = events.insertPending(EconomyEvent.pending(key(), 90, EventSource.UTILITY, orphan, null, 0, null, freshDay()));
        assertTrue(events.claim(id));
        SQLException refused = assertThrows(SQLException.class, () -> events.finalizePaid(id, 90));
        assertEquals("funding update did not affect exactly one row for event " + id, refused.getMessage());
        assertEquals("CLAIMED", state(id));
        events.finalizeFailed(id);
    }

    @Test
    void onlyAClaimedEventCanFail() throws SQLException {
        String day = freshDay();
        long pending = events.insertPending(EconomyEvent.pending(key(), 10, EventSource.UTILITY, null, null, null, null, day));
        long claimed = events.insertPending(EconomyEvent.pending(key(), 10, EventSource.UTILITY, null, null, null, null, day));
        long paidId = paid(EventSource.UTILITY, 10, null, null, day);
        assertTrue(events.claim(claimed));
        events.finalizeFailed(pending);
        events.finalizeFailed(claimed);
        events.finalizeFailed(paidId);
        assertEquals("PENDING", state(pending));
        assertEquals("FAILED", state(claimed));
        assertEquals("PAID", state(paidId));
        assertEquals(1, db.scalarLong("SELECT COUNT(*) FROM npc_economy_events WHERE event_id=? AND finalized_at IS NOT NULL"
                + " AND paid_amount_cents IS NULL", claimed));
    }

    @Test
    void pendingIdsComeOldestFirstAndAreCappedAtOneHundred() throws SQLException {
        String day = freshDay();
        for (int i = 0; i < 105; i++)
            events.insertPending(EconomyEvent.pending(key(), 1, EventSource.UTILITY, null, null, null, null, day));
        List<Long> all = events.selectPendingIds(1_000);
        assertEquals(100, all.size());
        for (int i = 1; i < all.size(); i++) assertTrue(all.get(i - 1) < all.get(i));
        assertEquals(all.subList(0, 3), events.selectPendingIds(3));
        assertEquals(List.of(), events.selectPendingIds(0));
        assertEquals(List.of(), events.selectPendingIds(-5));
        assertEquals(all, events.selectPendingIds(Integer.MAX_VALUE));
    }

    @Test
    void claimedDetailIsReadOnlyForAClaimedEvent() throws SQLException {
        UUID payer = UUID.randomUUID();
        long id = events.insertPending(EconomyEvent.pending(key(), 333, EventSource.INSURANCE, null, payer, null, null, freshDay()));
        assertEquals(Optional.empty(), events.loadDetail(id));
        assertTrue(events.claim(id));
        assertEquals(Optional.of(new EconomyEventRepository.ClaimedDetail(id, EventSource.INSURANCE, payer, 333)),
                events.loadDetail(id));
        events.finalizeFailed(id);
        assertEquals(Optional.empty(), events.loadDetail(id));
    }

    @Test
    void claimedDetailWithoutACounterpartyHasANullCounterparty() throws SQLException {
        long id = events.insertPending(EconomyEvent.pending(key(), 5, EventSource.RENT, null, null, null, null, freshDay()));
        assertTrue(events.claim(id));
        assertNull(events.loadDetail(id).orElseThrow().counterparty());
        events.finalizeFailed(id);
    }

    @Test
    void aStoredSourceWithTheWrongDirectionOrAnUnknownKeyIsRefused() throws SQLException {
        long wrong = events.insertPending(EconomyEvent.pending(key(), 5, EventSource.RENT, null, null, null, null, freshDay()));
        long unknown = events.insertPending(EconomyEvent.pending(key(), 5, EventSource.RENT, null, null, null, null, freshDay()));
        db.execute("UPDATE npc_economy_events SET direction='TRANSFER_FROM_PUBLIC', state='CLAIMED' WHERE event_id=?", wrong);
        db.execute("UPDATE npc_economy_events SET source='npc-property-bogus', state='CLAIMED' WHERE event_id=?", unknown);
        assertEquals("invalid direction for source: npc-property-rent",
                assertThrows(SQLException.class, () -> events.loadDetail(wrong)).getMessage());
        assertEquals("unknown source key: npc-property-bogus",
                assertThrows(SQLException.class, () -> events.loadDetail(unknown)).getMessage());
        assertThrows(SQLException.class, () -> events.finalizePaid(wrong, 5));
        assertEquals("CLAIMED", state(wrong));
        events.finalizeFailed(wrong);
        events.finalizeFailed(unknown);
    }

    @Test
    void startupRecoveryMarksEveryClaimedEventAmbiguousAndNeverRetriesIt() throws SQLException {
        String day = freshDay();
        long first = events.insertPending(EconomyEvent.pending(key(), 7, EventSource.UTILITY, null, null, null, null, day));
        long second = events.insertPending(EconomyEvent.pending(key(), 8, EventSource.UTILITY, null, null, null, null, day));
        long untouched = events.insertPending(EconomyEvent.pending(key(), 9, EventSource.UTILITY, null, null, null, null, day));
        long before = db.scalarLong("SELECT COUNT(*) FROM npc_economy_events WHERE state='CLAIMED'");
        assertTrue(events.claim(first));
        assertTrue(events.claim(second));
        assertEquals(before + 2, events.markClaimedAsAmbiguousOnStartup());
        assertEquals(0, db.scalarLong("SELECT COUNT(*) FROM npc_economy_events WHERE state='CLAIMED'"));
        assertEquals("AMBIGUOUS", state(first));
        assertEquals("AMBIGUOUS", state(second));
        assertEquals("PENDING", state(untouched));
        assertFalse(events.claim(first));
        events.finalizePaid(first, 7);
        events.finalizeFailed(second);
        assertEquals("AMBIGUOUS", state(first));
        assertEquals("AMBIGUOUS", state(second));
        assertEquals(0, events.markClaimedAsAmbiguousOnStartup());
    }

    @Test
    void existenceIsAnsweredByKey() throws SQLException {
        String key = key();
        assertFalse(events.existsByIdempotencyKey(key));
        events.insertPending(EconomyEvent.pending(key, 1, EventSource.UTILITY, null, null, null, null, freshDay()));
        assertTrue(events.existsByIdempotencyKey(key));
    }

    @Test
    void dailySumsCountOnlyPaidPayoutsInTheirScope() throws SQLException {
        String day = freshDay(), otherDay = freshDay();
        UUID a = db.enroll(UUID.randomUUID(), 0), b = db.enroll(UUID.randomUUID(), 0);
        UUID unit = UUID.randomUUID(), otherUnit = UUID.randomUUID();
        paid(EventSource.RENT, 300, a, unit, day);
        paid(EventSource.APPRECIATION, 200, a, unit, day);
        paid(EventSource.RENT, 70, b, unit, day);
        paid(EventSource.RENT, 11, b, otherUnit, day);
        paid(EventSource.UTILITY, 1_000, a, unit, day);
        paid(EventSource.RENT, 50, a, unit, otherDay);
        events.insertPending(EconomyEvent.pending(key(), 999, EventSource.RENT, a, null, 0, unit, day));
        long failed = events.insertPending(EconomyEvent.pending(key(), 888, EventSource.APPRECIATION, a, null, 0, unit, day));
        assertTrue(events.claim(failed));
        events.finalizeFailed(failed);

        assertEquals(581, events.sumPaidToPublicForDay(day));
        assertEquals(500, events.sumPaidToPublicForProperty(day, a));
        assertEquals(81, events.sumPaidToPublicForProperty(day, b));
        assertEquals(570, events.sumPaidToPublicForBenefitUnit(day, unit));
        assertEquals(11, events.sumPaidToPublicForBenefitUnit(day, otherUnit));
        assertEquals(200, events.sumPaidAppreciationForProperty(day, a));
        assertEquals(0, events.sumPaidAppreciationForProperty(day, b));
        assertEquals(50, events.sumPaidToPublicForDay(otherDay));
        assertEquals(0, events.sumPaidToPublicForDay(freshDay()));
    }
}

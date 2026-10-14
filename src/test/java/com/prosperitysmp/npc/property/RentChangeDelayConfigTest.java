package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.property.PropertyRepository.RentChange;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** housing.rent-change-delay-days parsing and the owner replies. Default and 0 mean immediate. */
class RentChangeDelayConfigTest {
    private static final Property P = new Property(UUID.randomUUID(), UUID.randomUUID(), "r", "m", "small", 2,
            1000, 1000, UUID.randomUUID(), 0, 80, true);
    private static final LocalDate DAY = LocalDate.of(2026, 10, 15);

    @Test void delayDefaultsToImmediateAndRejectsInvalidValues() {
        assertEquals(0, PropertyService.rentChangeDelayDays(null));
        assertEquals(0, PropertyService.rentChangeDelayDays(0));
        assertEquals(1, PropertyService.rentChangeDelayDays(1));
        assertEquals(30, PropertyService.rentChangeDelayDays(30));
        assertEquals(-1, PropertyService.rentChangeDelayDays(31));
        assertEquals(-1, PropertyService.rentChangeDelayDays(-1));
        assertEquals(-1, PropertyService.rentChangeDelayDays("2"));
        assertEquals(-1, PropertyService.rentChangeDelayDays(true));
        assertEquals(-1, PropertyService.rentChangeDelayDays(2.0));
    }

    @Test void repliesArePlainAndStateWhatSqlRecorded() {
        assertEquals("Asking rent set to $12.00/day.",
                PropertyService.rentChangeReply(new RentChange(RentChange.Outcome.APPLIED_NOW, P, 0, 1200, DAY, 0)));
        assertEquals("Rent changes to $12.00/day on 2026-10-15. 2 residents were told.",
                PropertyService.rentChangeReply(new RentChange(RentChange.Outcome.SCHEDULED, P, 5, 1200, DAY, 2)));
        assertEquals("Rent changes to $12.00/day on 2026-10-15. 1 resident was told.",
                PropertyService.rentChangeReply(new RentChange(RentChange.Outcome.SCHEDULED, P, 5, 1200, DAY, 1)));
        assertEquals("Rent already changes to $12.00/day on 2026-10-15. Nothing changed.",
                PropertyService.rentChangeReply(new RentChange(RentChange.Outcome.ALREADY_SCHEDULED, P, 5, 1200, DAY, 2)));
        assertEquals("Pending rent change cancelled. Rent stays $10.00/day.",
                PropertyService.rentChangeReply(new RentChange(RentChange.Outcome.CANCELLED, P, 5, 1000, DAY, 0)));
        assertEquals("Rent is already $10.00/day. Nothing changed.",
                PropertyService.rentChangeReply(new RentChange(RentChange.Outcome.UNCHANGED, P, 0, 1000, DAY, 0)));
        for (var outcome : RentChange.Outcome.values())
            assertFalse(PropertyService.rentChangeReply(new RentChange(outcome, P, 1, 1200, DAY, 0)).contains("—"));
    }

    @Test void unreadableStartupPendingStateFailsClosed() {
        var warnings = new java.util.ArrayList<String>();
        assertTrue(PropertyService.pendingRentChangesAtStartup(() -> { throw new java.sql.SQLException("db down"); }, warnings::add),
                "a failed count must not mean 'nothing pending'");
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("db down"));
        assertTrue(PropertyService.pendingRentChangesAtStartup(() -> { throw new IllegalStateException("pool closed"); }, warnings::add));
        assertTrue(PropertyService.pendingRentChangesAtStartup(() -> -1, warnings::add), "a nonsense count is unreadable too");
        assertEquals(3, warnings.size());
        // Positive controls: a readable count is used as is, and only 0 means none.
        assertFalse(PropertyService.pendingRentChangesAtStartup(() -> 0, warnings::add));
        assertTrue(PropertyService.pendingRentChangesAtStartup(() -> 2, warnings::add));
        assertEquals(3, warnings.size(), "readable counts log nothing");
    }

    @Test void dueRentSummaryKeepsOldWordingAndCountsReplacedSeparately() {
        assertEquals("No rent changes due.", PropertyService.dueRentSummary(0, 0, 0));
        assertEquals("Rent changes applied: 2.", PropertyService.dueRentSummary(2, 0, 0));
        assertEquals("Rent changes applied: 2; cancelled after an owner or terms change: 1.", PropertyService.dueRentSummary(2, 1, 0));
        assertEquals("Rent changes applied: 0; replaced by a newer asking rent: 1.", PropertyService.dueRentSummary(0, 0, 1));
        assertEquals("Rent changes applied: 1; cancelled after an owner or terms change: 1; replaced by a newer asking rent: 3.",
                PropertyService.dueRentSummary(1, 1, 3));
    }

    @Test void dueRentAppliedOnlyForTheAppliedResolution() {
        for (var r : PropertyRepository.DueRent.Resolution.values())
            assertEquals(r == PropertyRepository.DueRent.Resolution.APPLIED, new PropertyRepository.DueRent(P, P, 1, r).applied());
        assertThrows(NullPointerException.class, () -> new PropertyRepository.DueRent(P, P, 1, null));
    }

    @Test void deskLineMatchesTheOwnerDesign() {
        var pending = new OwnerDeskData.PendingRent(1250, DAY, 0);
        assertEquals("Rent changes to $12.50 on 2026-10-15. 0 residents were told.", pending.line());
        assertThrows(IllegalArgumentException.class, () -> new OwnerDeskData.PendingRent(0, DAY, 0));
        assertThrows(IllegalArgumentException.class, () -> new OwnerDeskData.PendingRent(100, DAY, 5));
    }
}

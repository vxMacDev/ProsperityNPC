package com.prosperitysmp.npc.economy;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** A fresh ledger request and the stored lifecycle names. */
class EconomyEventTest {
    private static final UUID PROPERTY = new UUID(0x11, 0x22);
    private static final UUID PAYER = new UUID(0x33, 0x44);
    private static final UUID UNIT = new UUID(0x55, 0x66);

    @Test
    void aPendingRequestHasNoIdNoPaidAmountAndEveryFieldItWasGiven() {
        EconomyEvent event = EconomyEvent.pending("bills|key", 1_250, EventSource.BILLS, PROPERTY, PAYER, 3, UNIT,
                "2026-10-14");
        assertEquals(0L, event.eventId());
        assertEquals("bills|key", event.idempotencyKey());
        assertEquals(1_250, event.requestedAmountCents());
        assertNull(event.paidAmountCents());
        assertEquals(EventSource.BILLS, event.source());
        assertEquals(PROPERTY, event.propertyId());
        assertEquals(PAYER, event.counterpartyUuid());
        assertEquals(3, event.ownershipEpoch());
        assertEquals(UNIT, event.benefitUnitId());
        assertEquals("2026-10-14", event.economyDay());
        assertEquals(EventState.PENDING, event.state());
    }

    @Test
    void aPendingRequestKeepsNullOptionalFieldsAsNull() {
        EconomyEvent event = EconomyEvent.pending("k", 1, EventSource.RENT, null, null, null, null, "2026-01-01");
        assertNull(event.propertyId());
        assertNull(event.counterpartyUuid());
        assertNull(event.ownershipEpoch());
        assertNull(event.benefitUnitId());
        assertEquals(EventState.PENDING, event.state());
    }

    @Test
    void twoPendingRequestsWithTheSameFieldsAreEqual() {
        EconomyEvent first = EconomyEvent.pending("k", 10, EventSource.UTILITY, PROPERTY, PAYER, 0, UNIT, "2026-10-14");
        EconomyEvent second = EconomyEvent.pending("k", 10, EventSource.UTILITY, PROPERTY, PAYER, 0, UNIT, "2026-10-14");
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, EconomyEvent.pending("k", 11, EventSource.UTILITY, PROPERTY, PAYER, 0, UNIT, "2026-10-14"));
    }

    @Test
    void lifecycleStatesKeepTheirStoredNamesAndOrder() {
        assertEquals(List.of("PENDING", "CLAIMED", "PAID", "FAILED", "AMBIGUOUS"),
                Arrays.stream(EventState.values()).map(Enum::name).toList());
        assertEquals(EventState.AMBIGUOUS, EventState.valueOf("AMBIGUOUS"));
        assertThrows(IllegalArgumentException.class, () -> EventState.valueOf("paid"));
    }
}

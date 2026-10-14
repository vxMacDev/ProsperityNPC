package com.prosperitysmp.npc.economy;

import java.util.UUID;

/**
 * An immutable requested/settled economy event. {@code eventId} is 0 until the
 * row is inserted. {@code idempotencyKey} makes a (source, property, day, slot)
 * settlement request safe to retry: a duplicate key never creates a second row.
 */
public record EconomyEvent(
        long eventId,
        String idempotencyKey,
        long requestedAmountCents,
        Long paidAmountCents,
        EventSource source,
        UUID propertyId,
        UUID counterpartyUuid,
        Integer ownershipEpoch,
        UUID benefitUnitId,
        String economyDay,
        EventState state) {

    /** A fresh PENDING request (not yet persisted). */
    public static EconomyEvent pending(String idempotencyKey, long requestedCents, EventSource source,
                                       UUID propertyId, UUID counterparty, Integer ownershipEpoch,
                                       UUID benefitUnitId, String economyDay) {
        return new EconomyEvent(0L, idempotencyKey, requestedCents, null, source, propertyId,
                counterparty, ownershipEpoch, benefitUnitId, economyDay, EventState.PENDING);
    }
}

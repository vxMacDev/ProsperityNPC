package com.prosperitysmp.npc.commerce;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** An issued one-shot offer, not a catalogue SKU. The trusted issuer binds the entire batch. */
public record ItemCustody(UUID requestNonce, UUID playerId, UUID offerId, Source source,
                          int quantity, CustodyBytes payload, Instant reservedAt, Instant expiresAt) {
    /** REPAIR_RETURN: items an item repair took and must give back. Written only by ItemCustodyRepository.reserveReturn. */
    public enum Source { WORKER_OUTPUT, MERCHANT_GOODS, REPAIR_RETURN }
    public static final int MAX_QUANTITY = 256;
    public static final int MAX_RESERVATION_SECONDS = 900;

    public ItemCustody {
        Objects.requireNonNull(requestNonce); Objects.requireNonNull(playerId); Objects.requireNonNull(offerId);
        Objects.requireNonNull(source); Objects.requireNonNull(payload);
        reservedAt = normalize(reservedAt); expiresAt = normalize(expiresAt);
        if (reservedAt.toEpochMilli() < 0 || quantity < 1 || quantity > MAX_QUANTITY
                || !expiresAt.isAfter(reservedAt)
                || expiresAt.isAfter(reservedAt.plusSeconds(MAX_RESERVATION_SECONDS)))
            throw new IllegalArgumentException("Item custody exceeds bounded policy");
    }

    static Instant normalize(Instant at) {
        return Instant.ofEpochMilli(Objects.requireNonNull(at).toEpochMilli());
    }
}

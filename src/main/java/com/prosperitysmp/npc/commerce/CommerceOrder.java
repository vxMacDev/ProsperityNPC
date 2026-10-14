package com.prosperitysmp.npc.commerce;

import com.prosperitysmp.npc.commerce.ItemCustody.Source;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Server-authored finite purchase/output batch; catalogue eligibility and full pricing belong to the issuer. */
public record CommerceOrder(UUID orderId, UUID issuanceNonce, UUID playerId, UUID residentId, Source source,
                            String catalogueId, String material, int quantity, long costCents,
                            LocalDate economyDay, Instant issuedAt, int outputDelaySeconds) {
    public static final int MAX_QUANTITY = 64;
    public static final long MAX_COST_CENTS = 100_000_000L;
    public static final int MAX_OUTPUT_DELAY_SECONDS = 86_400;

    public CommerceOrder {
        Objects.requireNonNull(orderId);
        Objects.requireNonNull(issuanceNonce);
        Objects.requireNonNull(playerId);
        Objects.requireNonNull(residentId);
        Objects.requireNonNull(source);
        Objects.requireNonNull(economyDay);
        issuedAt = ItemCustody.normalize(issuedAt);
        if (catalogueId == null || !catalogueId.matches("[a-z][a-z0-9_.:-]{0,63}")
                || material == null || !material.matches("[A-Z][A-Z0-9_]{0,63}")
                || quantity < 1 || quantity > MAX_QUANTITY || costCents < 1 || costCents > MAX_COST_CENTS
                || issuedAt.toEpochMilli() < 0 || economyDay.getYear() < 1970 || economyDay.getYear() > 9999
                || outputDelaySeconds < 0 || outputDelaySeconds > MAX_OUTPUT_DELAY_SECONDS
                || (source == Source.MERCHANT_GOODS && outputDelaySeconds != 0)
                || (source == Source.WORKER_OUTPUT && outputDelaySeconds == 0)
                || source == Source.REPAIR_RETURN
                || !orderId.equals(idFor(issuanceNonce, playerId, source, economyDay)))
            throw new IllegalArgumentException("Invalid bounded commerce order");
    }

    /** Stable server-issued nonce; never generate a replacement on replay or timeout. */
    public static UUID idFor(UUID issuanceNonce, UUID playerId, Source source, LocalDate day) {
        Objects.requireNonNull(issuanceNonce);
        Objects.requireNonNull(playerId);
        Objects.requireNonNull(source);
        Objects.requireNonNull(day);
        return UUID.nameUUIDFromBytes(("commerce-order-v1|" + issuanceNonce + "|" + playerId + "|" + source + "|" + day)
                .getBytes(StandardCharsets.UTF_8));
    }

    public String requestSha256() {
        return new CustodyBytes(("commerce-request-v1|" + orderId + "|" + issuanceNonce + "|" + playerId + "|"
                + residentId + "|" + source + "|" + catalogueId + "|" + material + "|" + quantity + "|" + costCents
                + "|" + economyDay + "|" + issuedAt.toEpochMilli() + "|" + outputDelaySeconds).getBytes(StandardCharsets.UTF_8)).sha256();
    }
}

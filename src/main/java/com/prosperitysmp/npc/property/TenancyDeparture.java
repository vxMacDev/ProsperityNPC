package com.prosperitysmp.npc.property;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable departure terms, not proof of ownership, payment or financial authorization.
 * Owner and epoch describe departure, not necessarily the original admission owner or epoch.
 * Admission timing, housing, retention scoring and wallet state are outside this model.
 */
public record TenancyDeparture(UUID residentId, UUID propertyId, UUID ownerAtDeparture,
                               int ownershipEpoch, long occupancyRevisionBefore, int slot,
                               Instant departedAt, Cause cause, Long purchaseEventId) {
    public static final String POLICY_VERSION = "tenancy-departure-v1";
    private static final Instant MIN_TIME = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant TIME_LIMIT = Instant.parse("+10000-01-01T00:00:00Z");

    /** VOLUNTARY: the resident chose to leave a neglected home; no purchase or money is involved. */
    public enum Cause { DEPARTURE, PAID_EVICTION, VOLUNTARY }

    public TenancyDeparture {
        Objects.requireNonNull(residentId, "residentId");
        Objects.requireNonNull(propertyId, "propertyId");
        Objects.requireNonNull(departedAt, "departedAt");
        Objects.requireNonNull(cause, "cause");
        if (ownershipEpoch < 0 || occupancyRevisionBefore < 0 || occupancyRevisionBefore == Long.MAX_VALUE)
            throw new IllegalArgumentException("Invalid departure ownership epoch or occupancy revision");
        if (slot < 0 || slot >= 4)
            throw new IllegalArgumentException("Departure slot must be in 0..3");
        if (departedAt.isBefore(MIN_TIME) || !departedAt.isBefore(TIME_LIMIT)
                || departedAt.getNano() % 1_000_000 != 0)
            throw new IllegalArgumentException("Departure must be exact milliseconds in UTC years 1..9999");
        if (cause != Cause.PAID_EVICTION && purchaseEventId != null)
            throw new IllegalArgumentException("Departure must not reference a purchase event");
        if (cause == Cause.PAID_EVICTION && (purchaseEventId == null || purchaseEventId <= 0))
            throw new IllegalArgumentException("Paid eviction requires a positive purchase event ID");
    }

    public long occupancyRevisionAfter() {
        return occupancyRevisionBefore + 1;
    }

    /**
     * SHA-256 of UTF-8: version, then all record components in declaration order, separated by '|'.
     * Null owner/event use the literal "null"; time is epoch milliseconds and cause is its enum name.
     */
    public String fingerprint() {
        String payload = POLICY_VERSION + "|" + residentId + "|" + propertyId
                + "|" + (ownerAtDeparture == null ? "null" : ownerAtDeparture.toString())
                + "|" + ownershipEpoch + "|" + occupancyRevisionBefore + "|" + slot
                + "|" + departedAt.toEpochMilli() + "|" + cause.name()
                + "|" + (purchaseEventId == null ? "null" : purchaseEventId.toString());
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Required SHA-256 unavailable", impossible);
        }
    }
}

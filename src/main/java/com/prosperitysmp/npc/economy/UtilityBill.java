package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.clock.EconomyClock;
import com.prosperitysmp.npc.formulas.PropertyFormulas;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable utility terms, not proof of ownership, payment or financial authorization.
 * The producer must verify provenance and journal these terms; no clock settings are defaulted.
 * Issuance is within [periodStart, dueAt), using exact milliseconds in UTC years 1..9999.
 * Both period boundaries must also fit that UTC storage range.
 */
public record UtilityBill(UUID propertyId, UUID ownerId, int ownershipEpoch, long propertyRevision,
                          LocalDate day, int capacity, int occupiedTenants, int installedUpgradeLevels,
                          Instant issuedAt, String zoneId, int resetHour) {
    /** Frozen tariff and fingerprint format. New prices require a versioned receipt migration. */
    public static final String POLICY_VERSION = "utility-bill-v1";
    private static final long V1_BASE_CENTS = 100L;
    private static final long V1_TENANT_CENTS = 75L;
    private static final long V1_UPGRADE_LEVEL_CENTS = 50L;
    private static final Instant MIN_TIME = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant TIME_LIMIT = Instant.parse("+10000-01-01T00:00:00Z");

    public UtilityBill {
        Objects.requireNonNull(propertyId, "propertyId");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(day, "day");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(zoneId, "zoneId");
        if (ownershipEpoch < 0 || propertyRevision < 0 || propertyRevision == Long.MAX_VALUE)
            throw new IllegalArgumentException("Invalid utility ownership epoch or property revision");
        if (day.getYear() < 1 || day.getYear() > 9998)
            throw new IllegalArgumentException("Utility day must be in years 1..9998");
        if (capacity < 1 || capacity > 4 || occupiedTenants < 0 || occupiedTenants > capacity
                || installedUpgradeLevels < 0 || installedUpgradeLevels > 6)
            throw new IllegalArgumentException("Invalid utility capacity, occupancy or upgrade levels");
        if (issuedAt.isBefore(MIN_TIME) || !issuedAt.isBefore(TIME_LIMIT)
                || issuedAt.getNano() % 1_000_000 != 0)
            throw new IllegalArgumentException("Utility issuance must be exact milliseconds in UTC years 1..9999");
        if (zoneId.length() > 64 || zoneId.indexOf('|') >= 0)
            throw new IllegalArgumentException("Invalid utility zone ID");
        ZoneId zone;
        try {
            zone = ZoneId.of(zoneId);
        } catch (DateTimeException invalid) {
            throw new IllegalArgumentException("Invalid utility zone ID", invalid);
        }
        EconomyClock clock = new EconomyClock(Clock.fixed(issuedAt, ZoneOffset.UTC), zone, resetHour);
        Instant start = clock.startOf(day);
        Instant due = clock.startOf(day.plusDays(1));
        if (!start.isBefore(due))
            throw new IllegalArgumentException("Utility period must have positive duration");
        if (start.isBefore(MIN_TIME) || !start.isBefore(TIME_LIMIT)
                || due.isBefore(MIN_TIME) || !due.isBefore(TIME_LIMIT))
            throw new IllegalArgumentException("Utility period boundaries must fit UTC years 1..9999");
        if (issuedAt.isBefore(start) || !issuedAt.isBefore(due))
            throw new IllegalArgumentException("Utility issuance must be within its economy day");
    }

    /** Delegates only validated counts, while refusing to silently reprice v1 receipts. */
    public long cents() {
        long cents = PropertyFormulas.dailyUtilitiesCents(occupiedTenants, installedUpgradeLevels);
        long v1Cents = V1_BASE_CENTS + V1_TENANT_CENTS * occupiedTenants
                + V1_UPGRADE_LEVEL_CENTS * installedUpgradeLevels;
        if (cents != v1Cents)
            throw new IllegalStateException("Utility tariff changed: explicit version migration required");
        return cents;
    }

    public Instant periodStart() {
        return economyClock().startOf(day);
    }

    public Instant dueAt() {
        return economyClock().startOf(day.plusDays(1));
    }

    /** SHA-256 of UTF-8: policy version, all record components in declaration order, then cents. */
    public String fingerprint() {
        String payload = POLICY_VERSION + "|" + propertyId + "|" + ownerId + "|" + ownershipEpoch
                + "|" + propertyRevision + "|" + day + "|" + capacity + "|" + occupiedTenants
                + "|" + installedUpgradeLevels + "|" + issuedAt.toEpochMilli() + "|" + zoneId
                + "|" + resetHour + "|" + cents();
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Required SHA-256 unavailable", impossible);
        }
    }

    private EconomyClock economyClock() {
        return new EconomyClock(Clock.fixed(issuedAt, ZoneOffset.UTC), ZoneId.of(zoneId), resetHour);
    }
}

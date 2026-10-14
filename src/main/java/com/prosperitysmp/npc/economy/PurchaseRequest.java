package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.formulas.UpgradeType;
import com.prosperitysmp.npc.formulas.PropertyFormulas;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Server-created immutable quote. No player-supplied prices, command text or free-form effect. */
public record PurchaseRequest(UUID requestId, UUID propertyId, UUID payerId, int ownershipEpoch,
                              Kind kind, UpgradeType upgrade, Integer targetLevel, UUID tenantId,
                              LocalDate day, RepairQuote repair, EvictionQuote eviction, InsuranceQuote insurance) {
    public enum Kind { UPGRADE, MAINTAIN, EVICT, REPAIR, INSURANCE }

    public record InsuranceQuote(int capacity) {
        public InsuranceQuote {
            if (capacity < 1 || capacity > 4) throw new IllegalArgumentException("Invalid insurance capacity");
        }
        public long cents() { return PropertyFormulas.insurancePriceCents(capacity); }
    }

    /** Preserves every pre-insurance constructor and fingerprint. */
    public PurchaseRequest(UUID requestId, UUID propertyId, UUID payerId, int ownershipEpoch,
                           Kind kind, UpgradeType upgrade, Integer targetLevel, UUID tenantId,
                           LocalDate day, RepairQuote repair, EvictionQuote eviction) {
        this(requestId, propertyId, payerId, ownershipEpoch, kind, upgrade, targetLevel, tenantId,
                day, repair, eviction, null);
    }

    /** Preserves the V3 request shape and event fingerprint for existing receipts. */
    public PurchaseRequest(UUID requestId, UUID propertyId, UUID payerId, int ownershipEpoch,
                           Kind kind, UpgradeType upgrade, Integer targetLevel, UUID tenantId, LocalDate day) {
        this(requestId, propertyId, payerId, ownershipEpoch, kind, upgrade, targetLevel, tenantId, day, null, null);
    }

    /** Preserves V6 repair and legacy V3 receipt fingerprints. */
    public PurchaseRequest(UUID requestId, UUID propertyId, UUID payerId, int ownershipEpoch,
                           Kind kind, UpgradeType upgrade, Integer targetLevel, UUID tenantId,
                           LocalDate day, RepairQuote repair) {
        this(requestId, propertyId, payerId, ownershipEpoch, kind, upgrade, targetLevel, tenantId, day, repair, null);
    }

    public PurchaseRequest {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(propertyId);
        Objects.requireNonNull(payerId);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(day);
        if (ownershipEpoch < 0 || day.getYear() < 1 || day.getYear() > 9999) throw new IllegalArgumentException("Invalid quote epoch/day");
        if ((kind == Kind.REPAIR) != (repair != null)) throw new IllegalArgumentException("Invalid repair target");
        if (eviction != null && kind != Kind.EVICT) throw new IllegalArgumentException("Invalid eviction target");
        if ((kind == Kind.INSURANCE) != (insurance != null)) throw new IllegalArgumentException("Invalid insurance target");
        switch (kind) {
            case UPGRADE -> {
                if (upgrade == null || targetLevel == null || targetLevel < 1 || targetLevel > UpgradeType.MAX_LEVEL || tenantId != null)
                    throw new IllegalArgumentException("Invalid upgrade quote");
            }
            case MAINTAIN, INSURANCE -> {
                if (upgrade != null || targetLevel != null || tenantId != null)
                    throw new IllegalArgumentException("Invalid maintenance quote");
            }
            case EVICT -> {
                if (upgrade != null || targetLevel != null || tenantId == null)
                    throw new IllegalArgumentException("Invalid eviction quote");
            }
            case REPAIR -> {
                if (upgrade != null || targetLevel != null || tenantId != null)
                    throw new IllegalArgumentException("Invalid incident repair quote");
            }
        }
    }

    public long cents() {
        return switch (kind) {
            case UPGRADE -> upgrade.costToLevelCents(targetLevel);
            case REPAIR -> repair.cents();
            case INSURANCE -> insurance.cents();
            case MAINTAIN, EVICT -> 1_000;
        };
    }
    public EventSource source() {
        return switch (kind) {
            case UPGRADE -> EventSource.UPGRADE;
            case MAINTAIN, REPAIR -> EventSource.REPAIR;
            case EVICT -> EventSource.EVICTION;
            case INSURANCE -> EventSource.INSURANCE;
        };
    }
    /** Binds the ledger event to the complete typed quote, including the exact tenant/upgrade. */
    public String eventKey() {
        String payload = "purchase-v1|" + requestId + "|" + propertyId + "|" + payerId + "|"
                + ownershipEpoch + "|" + kind + "|" + upgrade + "|" + targetLevel + "|" + tenantId
                + "|" + day + "|" + cents();
        if (repair != null) payload += "|repair-v1|" + repair.incidentId() + "|" + repair.type()
                + "|" + repair.snapshotSha256();
        if (eviction != null) payload += "|evict-v1|" + eviction.tenancyRevision() + "|" + eviction.slot();
        if (insurance != null) payload += "|insurance-v1|" + insurance.capacity();
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.US_ASCII));
            return "purchase|" + requestId + "|" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Required SHA-256 unavailable", impossible);
        }
    }
    /** Ownership/property are separate indexed fields; unrelated upgrade types cannot collide. */
    public String actionKey() {
        return switch (kind) {
            case UPGRADE -> "UPGRADE|" + upgrade.name() + "|" + targetLevel;
            case MAINTAIN -> "MAINTAIN|" + day;
            case EVICT -> "EVICT|" + tenantId + (eviction == null ? "" : "|r" + eviction.tenancyRevision());
            case REPAIR -> "REPAIR|" + repair.incidentId();
            case INSURANCE -> "INSURANCE|" + day;
        };
    }
}

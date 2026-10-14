package com.prosperitysmp.npc.market;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Trusted internal quote, not an authorization token or a client-supplied command payload. */
public record HousingMoveRequest(UUID requestId, UUID residentId, UUID propertyId, UUID ownerId,
                                 UUID worldId, String marketId, String classKey, int ownershipEpoch,
                                 long propertyRevision, long membershipRevision, long tenancyRevision,
                                 int slot, LocalDate day, HousingPolicy.Tier tier, Origin origin) {
    /** Exact source occupancy and authority; a relocation never infers these from its destination. */
    public record Origin(UUID propertyId, UUID ownerId, int ownershipEpoch, long propertyRevision,
                         int slot, UUID admissionId) {
        public Origin {
            Objects.requireNonNull(propertyId); Objects.requireNonNull(ownerId); Objects.requireNonNull(admissionId);
            if (ownershipEpoch < 0 || propertyRevision < 0 || propertyRevision == Long.MAX_VALUE || slot < 0 || slot > 3)
                throw new IllegalArgumentException("Invalid housing origin");
        }
    }
    public HousingMoveRequest(UUID requestId, UUID residentId, UUID propertyId, UUID ownerId,
                              UUID worldId, String marketId, String classKey, int ownershipEpoch,
                              long propertyRevision, long membershipRevision, long tenancyRevision, int slot,
                              LocalDate day, HousingPolicy.Tier tier) {
        this(requestId, residentId, propertyId, ownerId, worldId, marketId, classKey, ownershipEpoch,
                propertyRevision, membershipRevision, tenancyRevision, slot, day, tier, null);
    }
    public HousingMoveRequest(UUID requestId, UUID residentId, UUID propertyId, UUID ownerId,
                              UUID worldId, String marketId, String classKey, int ownershipEpoch,
                              long propertyRevision, long membershipRevision, long tenancyRevision, int slot, LocalDate day) {
        this(requestId, residentId, propertyId, ownerId, worldId, marketId, classKey, ownershipEpoch,
                propertyRevision, membershipRevision, tenancyRevision, slot, day, HousingPolicy.Tier.STANDARD, null);
    }
    public record Policy(int serverLimit, int marketLimit, int propertyLimit, int cooldownDays) {
        public Policy {
            if (serverLimit < 0 || serverLimit > 512 || marketLimit < 0 || marketLimit > 512
                    || propertyLimit < 0 || propertyLimit > 4 || cooldownDays < 1 || cooldownDays > 30)
                throw new IllegalArgumentException("Invalid housing move policy");
        }
    }
    public HousingMoveRequest {
        Objects.requireNonNull(requestId); Objects.requireNonNull(residentId); Objects.requireNonNull(propertyId);
        Objects.requireNonNull(ownerId); Objects.requireNonNull(worldId); Objects.requireNonNull(day); Objects.requireNonNull(tier);
        HousingPolicy.key(marketId); HousingPolicy.key(classKey);
        if (ownershipEpoch < 0 || propertyRevision < 0 || propertyRevision == Long.MAX_VALUE
                || membershipRevision < 0 || membershipRevision == Long.MAX_VALUE
                || tenancyRevision < -1 || tenancyRevision == Long.MAX_VALUE || slot < 0 || slot > 3
                || day.getYear() < 1 || day.getYear() > 9999)
            throw new IllegalArgumentException("Invalid housing move quote");
        if (origin != null && (tier != HousingPolicy.Tier.PREMIUM || tenancyRevision < 0
                || tenancyRevision > Long.MAX_VALUE - 2 || origin.propertyId().equals(propertyId)))
            throw new IllegalArgumentException("Invalid premium relocation quote");
    }
    public String fingerprint(Policy policy) {
        Objects.requireNonNull(policy);
        String payload = String.join("|", "housing-move-v1", requestId.toString(), residentId.toString(),
                propertyId.toString(), ownerId.toString(), worldId.toString(), marketId, classKey,
                Integer.toString(ownershipEpoch), Long.toString(propertyRevision), Long.toString(membershipRevision),
                Long.toString(tenancyRevision), Integer.toString(slot), day.toString(),
                Integer.toString(policy.serverLimit()), Integer.toString(policy.marketLimit()),
                Integer.toString(policy.propertyLimit()), Integer.toString(policy.cooldownDays()));
        // Existing STANDARD receipts retain their byte-exact v1 binding.
        if (tier == HousingPolicy.Tier.PREMIUM) payload += "|premium-v1";
        if (origin != null) payload += "|relocation-v1|" + origin.propertyId() + "|" + origin.ownerId()
                + "|" + origin.ownershipEpoch() + "|" + origin.propertyRevision() + "|" + origin.slot() + "|" + origin.admissionId();
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}

package com.prosperitysmp.npc.property;

import java.util.Optional;
import java.util.UUID;

/** An enrolled property row (a subset of {@code npc_properties} the runtime needs). */
public record Property(
        UUID propertyId,
        UUID worldUuid,
        String regionId,
        String marketId,
        String classKey,
        int capacity,
        int referenceRentCents,
        int askingRentCents,
        UUID ownerUuid,
        int ownershipEpoch,
        int conditionScore,
        boolean enabled) {

    public Optional<UUID> owner() {
        return Optional.ofNullable(ownerUuid);
    }

    /** Returns a copy reflecting a new owner and bumped epoch (used on ARM owner change). */
    public Property withOwner(UUID newOwner, int newEpoch) {
        return new Property(propertyId, worldUuid, regionId, marketId, classKey, capacity,
                referenceRentCents, askingRentCents, newOwner, newEpoch, conditionScore, enabled);
    }

    /** Returns a copy with a new asking rent (used when a delayed rent change takes effect). */
    public Property withAskingRent(int newAskingRentCents) {
        return new Property(propertyId, worldUuid, regionId, marketId, classKey, capacity,
                referenceRentCents, newAskingRentCents, ownerUuid, ownershipEpoch, conditionScore, enabled);
    }
}

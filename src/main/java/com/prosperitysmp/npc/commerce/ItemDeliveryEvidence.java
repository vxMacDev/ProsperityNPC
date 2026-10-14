package com.prosperitysmp.npc.commerce;

import java.util.Objects;
import java.util.UUID;

/**
 * Main-thread coordinator evidence, not authorization. Counts must use exact serialized-item
 * identity, with metadata/stack amounts verified against the stored batch, under exclusive
 * player-inventory custody. Snapshot hashes refer to the immediately adjacent full inventories.
 * The repository cannot decode Bukkit serialization or independently observe an inventory.
 */
public record ItemDeliveryEvidence(UUID playerId, String payloadSha256, String beforeInventorySha256,
                                   String afterInventorySha256, int beforeUnits, int afterUnits,
                                   int leftoverUnits, boolean returnedNormally) {
    public static final int MAX_OBSERVED_UNITS = 65_536;
    public ItemDeliveryEvidence {
        Objects.requireNonNull(playerId);
        requireHash(payloadSha256);
        requireHash(beforeInventorySha256);
        requireHash(afterInventorySha256);
        if (beforeUnits < 0 || afterUnits < 0 || leftoverUnits < 0
                || beforeUnits > MAX_OBSERVED_UNITS || afterUnits > MAX_OBSERVED_UNITS
                || leftoverUnits > ItemCustody.MAX_QUANTITY)
            throw new IllegalArgumentException("Inventory observation exceeds bounds");
    }

    public boolean confirms(ItemCustody request) {
        return playerId.equals(request.playerId()) && payloadSha256.equals(request.payload().sha256())
                && returnedNormally && leftoverUnits == 0 && afterUnits - beforeUnits == request.quantity()
                && !beforeInventorySha256.equals(afterInventorySha256);
    }

    static void requireHash(String value) {
        if (value == null || !value.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid SHA-256 evidence");
    }
}

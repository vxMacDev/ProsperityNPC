package com.prosperitysmp.npc.arm;

import java.util.Optional;
import java.util.UUID;

/**
 * An immutable, read-only snapshot of an ARM region as ProsperityNPC sees it.
 * The plugin never mutates ARM; it only reads these fields.
 */
public record ArmRegionView(
        String regionId,
        String worldName,
        UUID worldUuid,
        UUID ownerUuid,
        String sellType,
        boolean sold) {

    public boolean isSell() {
        return "SELL".equalsIgnoreCase(sellType);
    }

    public boolean matches(UUID expectedWorld, String requestedRegion) {
        return expectedWorld != null && expectedWorld.equals(worldUuid)
                && requestedRegion != null && regionId != null
                && regionId.equalsIgnoreCase(requestedRegion);
    }

    public Optional<UUID> owner() {
        return Optional.ofNullable(ownerUuid);
    }
}

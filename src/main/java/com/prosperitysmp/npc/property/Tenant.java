package com.prosperitysmp.npc.property;

import java.util.UUID;

/** An NPC tenant occupying a property slot. Has no economy balance and no Vault account. */
public record Tenant(
        UUID tenantId,
        String displayName,
        UUID propertyId,
        int slot,
        String tier,
        String archetype,
        int satisfaction) {

    public boolean isPremium() {
        return "PREMIUM".equalsIgnoreCase(tier);
    }

    public int tierMultiplierPct() {
        return isPremium() ? 125 : 100;
    }
}

package com.prosperitysmp.npc.market;

import java.util.Objects;
import java.util.UUID;

/** Explicit authoring data; means/occupation never silently generate a rent budget. */
public record HousingPolicy(UUID worldId, String marketId, String classKey, Tier tier,
                            long maxDailyRentCents, int minimumCondition) {
    public enum Tier { STANDARD, PREMIUM }
    public HousingPolicy {
        Objects.requireNonNull(worldId);
        Objects.requireNonNull(tier);
        key(marketId);
        key(classKey);
        if (maxDailyRentCents < 1 || maxDailyRentCents > Integer.MAX_VALUE || minimumCondition < 0 || minimumCondition > 100)
            throw new IllegalArgumentException("Invalid housing eligibility");
    }
    public static void key(String value) {
        if (value == null || !value.matches("[a-z][a-z0-9_-]{0,31}"))
            throw new IllegalArgumentException("Invalid housing market/class key");
    }
}

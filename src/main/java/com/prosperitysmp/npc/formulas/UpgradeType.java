package com.prosperitysmp.npc.formulas;

/**
 * Upgrade types with their fixed level costs. Costs are configured
 * integer cents, never {@code getWorth()}. Index 0 is the cost to reach level 1.
 */
public enum UpgradeType {
    ADVERTISING(new long[]{10_000L, 25_000L, 50_000L}),  // $100 / $250 / $500
    FACILITIES(new long[]{15_000L, 35_000L, 70_000L});   // $150 / $350 / $700

    public static final int MAX_LEVEL = 3;

    private final long[] costToLevelCents;

    UpgradeType(long[] costToLevelCents) {
        this.costToLevelCents = costToLevelCents;
    }

    /** Cost in cents to upgrade to {@code targetLevel} (1..3). */
    public long costToLevelCents(int targetLevel) {
        if (targetLevel < 1 || targetLevel > MAX_LEVEL) {
            throw new IllegalArgumentException("target level must be 1.." + MAX_LEVEL + ", got " + targetLevel);
        }
        return costToLevelCents[targetLevel - 1];
    }

    public static UpgradeType fromKey(String key) {
        for (UpgradeType u : values()) {
            if (u.name().equalsIgnoreCase(key)) {
                return u;
            }
        }
        return null;
    }
}

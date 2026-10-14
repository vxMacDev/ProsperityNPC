package com.prosperitysmp.npc.formulas;

/**
 * The incident table. Weights sum to 100. Each incident deals fixed
 * condition damage and offers a fixed cash OR fixed item repair. Repairs never
 * grant region access.
 */
public enum IncidentType {
    PESTS(40, 5, 2000L, "16 paper + 8 baked_potato"),
    PLUMBING(35, 8, 3500L, "16 copper_ingot + 4 iron_ingot"),
    BREAK_IN(25, 12, 5000L, "32 iron_ingot + 8 glass");

    private final int weight;
    private final int conditionDamage;
    private final long cashRepairCents;
    private final String itemRepairSpec;

    IncidentType(int weight, int conditionDamage, long cashRepairCents, String itemRepairSpec) {
        this.weight = weight;
        this.conditionDamage = conditionDamage;
        this.cashRepairCents = cashRepairCents;
        this.itemRepairSpec = itemRepairSpec;
    }

    public int weight() {
        return weight;
    }

    public int conditionDamage() {
        return conditionDamage;
    }

    public long cashRepairCents() {
        return cashRepairCents;
    }

    public String itemRepairSpec() {
        return itemRepairSpec;
    }

    /** Total of all weights; must be 100. */
    public static int totalWeight() {
        int t = 0;
        for (IncidentType it : values()) {
            t += it.weight;
        }
        return t;
    }

    /** Deterministically selects an incident from a roll in [0, totalWeight()). */
    public static IncidentType pickByRoll(int roll) {
        int acc = 0;
        for (IncidentType it : values()) {
            acc += it.weight;
            if (roll < acc) {
                return it;
            }
        }
        return BREAK_IN;
    }
}

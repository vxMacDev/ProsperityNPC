package com.prosperitysmp.npc.sim;

import com.prosperitysmp.npc.formulas.IncidentType;
import com.prosperitysmp.npc.formulas.PropertyFormulas;

import java.util.Optional;
import java.util.UUID;

/**
 * Pure daily state-machine decisions for a property, built on {@link PropertyFormulas}
 * and the deterministic {@link DailyRoll}. These decide <em>what</em> should happen;
 * persistence and any cash are applied elsewhere.
 */
public final class SimulationLogic {

    public enum OccupancyChange {
        FILL, LEAVE, NONE
    }

    private final DailyRoll rolls;

    public SimulationLogic(DailyRoll rolls) {
        this.rolls = rolls;
    }

    /**
     * Decides whether a property generates an incident on {@code economyDay} and,
     * if so, which type. Deterministic for the (property, day) pair.
     */
    public Optional<IncidentType> incidentForDay(UUID propertyId, String economyDay, int conditionScore) {
        int chanceBp = PropertyFormulas.incidentChanceBp(conditionScore);
        int occurRoll = rolls.roll(propertyId, economyDay, "incident-occurs", 10_000);
        if (occurRoll >= chanceBp) {
            return Optional.empty();
        }
        int typeRoll = rolls.roll(propertyId, economyDay, "incident-type", IncidentType.totalWeight());
        return Optional.of(IncidentType.pickByRoll(typeRoll));
    }

    /**
     * Decides at most one occupancy transition for the day. A vacate is considered
     * before a fill; the result respects capacity (never FILL when full, never
     * LEAVE when empty). Deterministic for the (property, day) pair.
     */
    public OccupancyChange occupancyForDay(UUID propertyId, String economyDay, double propertyScore,
                                           int advertisingLevel, int occupied, int capacity,
                                           boolean overdueIncident) {
        if (occupied > 0) {
            int leaveBp = PropertyFormulas.leaveChanceBp(propertyScore, overdueIncident);
            if (rolls.roll(propertyId, economyDay, "occupancy-leave", 10_000) < leaveBp) {
                return OccupancyChange.LEAVE;
            }
        }
        if (occupied < capacity) {
            int fillBp = PropertyFormulas.fillChanceBp(propertyScore, advertisingLevel);
            if (rolls.roll(propertyId, economyDay, "occupancy-fill", 10_000) < fillBp) {
                return OccupancyChange.FILL;
            }
        }
        return OccupancyChange.NONE;
    }

    /**
     * The leave half of {@link #occupancyForDay} for one occupied home, never a fill. Same roll, so a
     * replay or restart of the same (property, day) gives the same answer.
     */
    public boolean leavesToday(UUID propertyId, String economyDay, double score, boolean overdueIncident) {
        return occupancyForDay(propertyId, economyDay, score, 0, 1, 1, overdueIncident) == OccupancyChange.LEAVE;
    }

    /** Stable choice of which of {@code count} candidates leaves, for the (property, day) pair. */
    public int leavingIndex(UUID propertyId, String economyDay, int count) {
        return rolls.roll(propertyId, economyDay, "voluntary-leave-resident", count);
    }
}

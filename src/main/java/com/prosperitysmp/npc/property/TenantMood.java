package com.prosperitysmp.npc.property;

import java.util.List;
import java.util.Objects;

/**
 * Deterministic daily resident mood. Pure arithmetic over recorded facts: property condition, open
 * incidents and their age, whether the previous utility bill was paid, and asking rent against
 * reference rent. It never touches money. Satisfaction moves at most {@link #STEP} points a day
 * toward the target, so one bad day cannot flip a resident.
 */
public final class TenantMood {
    public enum Band {
        UNHAPPY, NEUTRAL, CONTENT, HAPPY;
        public static Band of(int satisfaction) {
            if (satisfaction < 0 || satisfaction > 100) throw new IllegalArgumentException("Satisfaction out of range");
            return satisfaction < 30 ? UNHAPPY : satisfaction < 55 ? NEUTRAL : satisfaction < 80 ? CONTENT : HAPPY;
        }
    }
    /** NONE: no bill was issued for the previous day, which neither helps nor hurts. */
    public enum Bill { PAID, UNPAID, NONE }

    public static final int BASE = 50;
    public static final int STEP = 5;
    public static final int MAX_INCIDENT_PENALTY = 40;
    private static final int INCIDENT_BASE = 5, INCIDENT_PER_DAY = 2, INCIDENT_AGE_CAP = 5;

    public record Inputs(int conditionScore, List<Integer> openIncidentAgeDays, Bill bill,
                         long askingRentCents, long referenceRentCents) {
        public Inputs {
            Objects.requireNonNull(bill);
            openIncidentAgeDays = List.copyOf(openIncidentAgeDays);
            if (conditionScore < 0 || conditionScore > 100 || askingRentCents < 0 || referenceRentCents < 0)
                throw new IllegalArgumentException("Invalid mood inputs");
        }
    }

    private TenantMood() { }

    /** 60 is neutral: 100 gives +20, 0 gives -30. */
    public static int conditionTerm(int conditionScore) {
        if (conditionScore < 0 || conditionScore > 100) throw new IllegalArgumentException("Invalid condition");
        return Math.floorDiv(conditionScore - 60, 2);
    }

    /** Each open incident costs 5 plus 2 per day open (days capped at 5); the total is capped at 40. */
    public static int incidentTerm(List<Integer> openAgeDays) {
        int total = 0;
        for (int age : openAgeDays) {
            total += INCIDENT_BASE + INCIDENT_PER_DAY * Math.min(Math.max(age, 0), INCIDENT_AGE_CAP);
            if (total >= MAX_INCIDENT_PENALTY) return -MAX_INCIDENT_PENALTY;
        }
        return -total;
    }

    public static int billTerm(Bill bill) {
        return switch (bill) {
            case PAID -> 5;
            case UNPAID -> -10;
            case NONE -> 0;
        };
    }

    /** Asking as a percent of reference. Cheaper than reference pleases; dearer annoys. No reference means no signal. */
    public static int rentTerm(long askingCents, long referenceCents) {
        if (askingCents < 0 || referenceCents < 0) throw new IllegalArgumentException("Invalid rent");
        if (referenceCents == 0) return 0;
        long pct = Math.multiplyExact(askingCents, 100L) / referenceCents;
        return pct <= 90 ? 10 : pct <= 100 ? 5 : pct <= 110 ? 0 : pct <= 125 ? -8 : -15;
    }

    public static int target(Inputs in) {
        int raw = BASE + conditionTerm(in.conditionScore()) + incidentTerm(in.openIncidentAgeDays())
                + billTerm(in.bill()) + rentTerm(in.askingRentCents(), in.referenceRentCents());
        return Math.max(0, Math.min(100, raw));
    }

    /** One bounded step from the current satisfaction toward the target. */
    public static int next(int current, int target) {
        if (current < 0 || current > 100 || target < 0 || target > 100) throw new IllegalArgumentException("Out of range");
        return current < target ? Math.min(target, current + STEP) : Math.max(target, current - STEP);
    }
}

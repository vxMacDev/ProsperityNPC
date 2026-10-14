package com.prosperitysmp.npc.formulas;

/**
 * Pure, side-effect-free property math. Money is integer cents; probabilities
 * are integer basis points (bp, out of 10000); scores are 0-100.
 */
public final class PropertyFormulas {

    public static final int TIER_STANDARD_PCT = 100;
    public static final int TIER_PREMIUM_PCT = 125;

    private PropertyFormulas() {
    }

    // ---- Rent & expenses --------------------------------------------

    /** Per-tenant daily rent: min(asking, reference*tierPct/100, tenant cap). All cents. */
    public static long tenantRentCents(long askingCents, long referenceCents, int tierMultPct, long tenantCapCents) {
        long byTier = referenceCents * tierMultPct / 100;
        return Math.min(askingCents, Math.min(byTier, tenantCapCents));
    }

    /** Daily utilities: $1.00 + $0.75/occupied tenant + $0.50/installed upgrade level. Cents. */
    public static long dailyUtilitiesCents(int occupiedTenants, int totalUpgradeLevels) {
        return 100L + 75L * Math.max(0, occupiedTenants) + 50L * Math.max(0, totalUpgradeLevels);
    }

    // ---- Condition & incidents --------------------------------

    /** Incident chance in basis points: clamp(2% + (80-condition)*0.1%, 1%, 8%). */
    public static int incidentChanceBp(int conditionScore) {
        int bp = 200 + (80 - conditionScore) * 10;
        return clampInt(bp, 100, 800);
    }

    // ---- Insurance --------------------------------------------------

    /** Insurance premium for a 7-day term: $12 + $2*capacity. Cents. */
    public static long insurancePriceCents(int capacity) {
        return 1200L + 200L * capacity;
    }

    // ---- Rating -----------------------------------------------------

    /** Rent fairness 0-100: 100 when asking<=reference, else falls to 0 at 150% of reference. */
    public static int rentFairness(long askingCents, long referenceCents) {
        if (referenceCents <= 0 || askingCents <= referenceCents) {
            return 100;
        }
        double ratio = (double) askingCents / (double) referenceCents;
        double fairness = 100.0 * (1.0 - (ratio - 1.0) / 0.50);
        return clampInt((int) Math.round(fairness), 0, 100);
    }

    /** Facilities rating contribution: level/3 * 100 (0, 33.3, 66.7, 100). */
    public static double facilitiesScore(int facilitiesLevel) {
        return clampInt(facilitiesLevel, 0, 3) / 3.0 * 100.0;
    }

    /** Public property score 0-100: 0.45 condition + 0.20 facilities + 0.20 fairness + 0.15 operator. */
    public static double propertyScore(int condition, double facilitiesScore, int rentFairness, int operatorScore) {
        double s = 0.45 * condition + 0.20 * facilitiesScore + 0.20 * rentFairness + 0.15 * operatorScore;
        return clampDouble(s, 0.0, 100.0);
    }

    // ---- Operator reputation ----------------------------------------

    /** Existing policy: attainable score 25-75 for on-time / SLA / retention rates in 0-1. */
    public static int operatorScore(double expenseOnTimeRate, double incidentSlaRate, double retentionRate) {
        double s = 50.0
                + 20.0 * (expenseOnTimeRate - 0.5)
                + 20.0 * (incidentSlaRate - 0.5)
                + 10.0 * (retentionRate - 0.5);
        return clampInt((int) Math.round(s), 0, 100);
    }

    // ---- Premium eligibility ----------------------------------------

    public static final int PREMIUM_MIN_SCORE = 80;
    public static final int PREMIUM_MIN_CONDITION = 80;
    public static final int PREMIUM_MIN_OP_SCORE = 70;
    public static final int PREMIUM_MIN_OBSERVATIONS = 7;
    public static final int PREMIUM_MAX_ASKING_PCT = 125;
    /** A premium tenant only moves to a rival scoring at least this much higher. */
    public static final int PREMIUM_MOVE_SCORE_GAP = 10;
    /** Days a moved premium tenant is pinned before it can move again. */
    public static final int PREMIUM_MOVE_COOLDOWN_DAYS = 7;

    /**
     * Whether a property may host premium tenants. All conditions must hold:
     * score ≥ 80, condition ≥ 80, operator score ≥ 70 with ≥ 7 observations,
     * asking ≤ 125% of reference, and no overdue incident or utility delinquency.
     */
    public static boolean premiumEligible(double propertyScore, int condition, int operatorScore,
                                          int operatorObservations, long askingCents, long referenceCents,
                                          boolean overdueIncident, boolean utilityDelinquent) {
        if (propertyScore < PREMIUM_MIN_SCORE) {
            return false;
        }
        if (condition < PREMIUM_MIN_CONDITION) {
            return false;
        }
        if (operatorScore < PREMIUM_MIN_OP_SCORE || operatorObservations < PREMIUM_MIN_OBSERVATIONS) {
            return false;
        }
        // asking must be within 125% of reference (a reference of 0 can never be "within 125%").
        if (referenceCents <= 0 || askingCents * 100L > referenceCents * (long) PREMIUM_MAX_ASKING_PCT) {
            return false;
        }
        return !overdueIncident && !utilityDelinquent;
    }

    // ---- Occupancy --------------------------------------------------

    /** Match-rank points per advertising level; orders compatible vacancies only, never a public score. */
    public static final double ADVERTISING_RANK_POINTS_PER_LEVEL = 4.0;
    public static double advertisingRankBonus(int effectiveLevel) {
        return ADVERTISING_RANK_POINTS_PER_LEVEL * Math.max(0, Math.min(3, effectiveLevel));
    }

    /** Daily vacancy-fill chance in bp: clamp(10% + 0.5%*(score-50) + 5%*advLevel, 2%, 60%). */
    public static int fillChanceBp(double propertyScore, int advertisingLevel) {
        double bp = 1000.0 + 50.0 * (propertyScore - 50.0) + 500.0 * advertisingLevel;
        return clampInt((int) Math.round(bp), 200, 6000);
    }

    /** Daily tenant-leave chance in bp: clamp(3% + 0.4%*(60-score) + (5% if overdue), 1%, 35%). */
    public static int leaveChanceBp(double propertyScore, boolean overdueIncident) {
        double bp = 300.0 + 40.0 * (60.0 - propertyScore) + (overdueIncident ? 500.0 : 0.0);
        return clampInt((int) Math.round(bp), 100, 3500);
    }

    // ---- Appreciation -----------------------------------------------

    /** Daily appreciation dividend: min($3.00, max(0, score-70) * $0.10). Cents. */
    public static long appreciationCents(double propertyScore, long appreciationCapCents) {
        long raw = (long) Math.floor(Math.max(0.0, propertyScore - 70.0) * 10.0);
        return Math.min(appreciationCapCents, Math.max(0L, raw));
    }

    // ---- Helpers -----------------------------------------------------------

    public static int clampInt(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static double clampDouble(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}

package com.prosperitysmp.npc.formulas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class PropertyFormulasBoundaryTest {
    @ParameterizedTest(name = "condition {0} -> {1} bp")
    @CsvSource({"0, 800", "20, 800", "21, 790", "60, 400", "79, 210", "80, 200", "89, 110", "90, 100", "91, 100", "100, 100",
            "-50, 800", "200, 100"})
    void incidentChanceClampsBetweenOneAndEightPercent(int condition, int bp) {
        assertEquals(bp, PropertyFormulas.incidentChanceBp(condition));
    }

    @ParameterizedTest(name = "{0} tenants, {1} levels -> {2}")
    @CsvSource({"0, 0, 100", "4, 6, 700", "4, 0, 400", "0, 6, 400", "-3, -2, 100", "1, 1, 225"})
    void dailyUtilitiesIgnoreNegativeCounts(int tenants, int levels, long cents) {
        assertEquals(cents, PropertyFormulas.dailyUtilitiesCents(tenants, levels));
    }

    @ParameterizedTest(name = "capacity {0} -> {1}")
    @CsvSource({"1, 1400", "2, 1600", "3, 1800", "4, 2000", "0, 1200"})
    void insuranceIsTwelveDollarsPlusTwoPerBed(int capacity, long cents) {
        assertEquals(cents, PropertyFormulas.insurancePriceCents(capacity));
    }

    @Test void tenantRentRoundsTheTierDown() {
        assertEquals(416, PropertyFormulas.tenantRentCents(10_000, 333, PropertyFormulas.TIER_PREMIUM_PCT, 600));
        assertEquals(333, PropertyFormulas.tenantRentCents(10_000, 333, PropertyFormulas.TIER_STANDARD_PCT, 600));
        assertEquals(0, PropertyFormulas.tenantRentCents(0, 333, 125, 600));
    }

    @Test void rentFairnessIsFullWithoutAReference() {
        assertEquals(100, PropertyFormulas.rentFairness(50_000, 0));
        assertEquals(100, PropertyFormulas.rentFairness(50_000, -1));
        assertEquals(100, PropertyFormulas.rentFairness(10_001, 10_000));
        assertEquals(40, PropertyFormulas.rentFairness(13_000, 10_000));
        assertEquals(1, PropertyFormulas.rentFairness(14_950, 10_000));
    }

    @ParameterizedTest(name = "level {0} -> {1}")
    @CsvSource({"-1, 0.0", "0, 0.0", "1, 4.0", "2, 8.0", "3, 12.0", "4, 12.0", "100, 12.0"})
    void advertisingRankBonusIsFourPointsPerLevelUpToThree(int level, double points) {
        assertEquals(points, PropertyFormulas.advertisingRankBonus(level));
    }

    @Test void facilitiesScoreClampsTheLevel() {
        assertEquals(0.0, PropertyFormulas.facilitiesScore(-2));
        assertEquals(100.0, PropertyFormulas.facilitiesScore(7));
        assertEquals(66.6667, PropertyFormulas.facilitiesScore(2), 0.0001);
    }

    @Test void operatorScoreClampsInputsOutsideZeroToOne() {
        assertEquals(100, PropertyFormulas.operatorScore(10, 10, 10));
        assertEquals(0, PropertyFormulas.operatorScore(-10, -10, -10));
        assertEquals(60, PropertyFormulas.operatorScore(1.0, 0.5, 0.5));
    }

    @Test void propertyScoreClampsToZeroAndOneHundred() {
        assertEquals(100.0, PropertyFormulas.propertyScore(200, 200.0, 200, 200));
        assertEquals(0.0, PropertyFormulas.propertyScore(-100, 0.0, 0, 0));
    }

    @Test void appreciationIsTenCentsPerWholePointAboveSeventyUpToTheCap() {
        assertEquals(10, PropertyFormulas.appreciationCents(71.0, 300));
        assertEquals(55, PropertyFormulas.appreciationCents(75.5, 300));
        assertEquals(100, PropertyFormulas.appreciationCents(100.0, 100));
        assertEquals(0, PropertyFormulas.appreciationCents(100.0, 0));
        assertEquals(0, PropertyFormulas.appreciationCents(0.0, 300));
    }

    @Test void premiumAskingLimitUsesExactCents() {
        assertTrue(PropertyFormulas.premiumEligible(90, 90, 80, 10, 1_250_000, 1_000_000, false, false));
        assertFalse(PropertyFormulas.premiumEligible(90, 90, 80, 10, 1_250_001, 1_000_000, false, false));
        assertFalse(PropertyFormulas.premiumEligible(90, 90, 80, 10, 0, -1, false, false));
    }

    @Test void theClampHelpersBoundBothEnds() {
        assertEquals(5, PropertyFormulas.clampInt(9, 0, 5));
        assertEquals(0, PropertyFormulas.clampInt(-9, 0, 5));
        assertEquals(3, PropertyFormulas.clampInt(3, 0, 5));
        assertEquals(1.5, PropertyFormulas.clampDouble(1.5, 0.0, 2.0));
        assertEquals(2.0, PropertyFormulas.clampDouble(9.0, 0.0, 2.0));
        assertEquals(0.0, PropertyFormulas.clampDouble(-9.0, 0.0, 2.0));
    }

    @Test void thePremiumThresholdsAreFixed() {
        assertEquals(100, PropertyFormulas.TIER_STANDARD_PCT);
        assertEquals(125, PropertyFormulas.TIER_PREMIUM_PCT);
        assertEquals(80, PropertyFormulas.PREMIUM_MIN_SCORE);
        assertEquals(80, PropertyFormulas.PREMIUM_MIN_CONDITION);
        assertEquals(70, PropertyFormulas.PREMIUM_MIN_OP_SCORE);
        assertEquals(7, PropertyFormulas.PREMIUM_MIN_OBSERVATIONS);
        assertEquals(125, PropertyFormulas.PREMIUM_MAX_ASKING_PCT);
        assertEquals(10, PropertyFormulas.PREMIUM_MOVE_SCORE_GAP);
        assertEquals(7, PropertyFormulas.PREMIUM_MOVE_COOLDOWN_DAYS);
        assertEquals(1_000L, PropertyFormulas.MAINTAIN_CENTS);
        assertEquals(1_000L, PropertyFormulas.EVICTION_CENTS);
    }
}

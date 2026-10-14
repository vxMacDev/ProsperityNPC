package com.prosperitysmp.npc.formulas;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertyFormulasTest {

    private static final long TENANT_CAP = 600; // $6.00

    // ---- Rent & utilities --------------------------------------------------

    @Test
    void tenantRent_capBinds() {
        // reference 1000 ($10) * standard would be $10, but the $6 cap binds.
        assertEquals(600, PropertyFormulas.tenantRentCents(1000, 1000, 100, TENANT_CAP));
    }

    @Test
    void tenantRent_premiumMultiplierThenCap() {
        // reference $4 * 1.25 = $5 (under cap), asking high -> $5.
        assertEquals(500, PropertyFormulas.tenantRentCents(9999, 400, 125, TENANT_CAP));
    }

    @Test
    void tenantRent_askingBinds() {
        // asking $2 is the lowest of the three.
        assertEquals(200, PropertyFormulas.tenantRentCents(200, 400, 125, TENANT_CAP));
    }

    @Test
    void utilities() {
        assertEquals(100, PropertyFormulas.dailyUtilitiesCents(0, 0));
        assertEquals(100 + 75 * 3 + 50 * 4, PropertyFormulas.dailyUtilitiesCents(3, 4));
    }

    // ---- Incident chance clamps --------------------------------------------

    @Test
    void incidentChance_clampsAndBaseline() {
        assertEquals(200, PropertyFormulas.incidentChanceBp(80));  // baseline 2%
        assertEquals(400, PropertyFormulas.incidentChanceBp(60));  // 2% + 20*0.1% = 4%
        assertEquals(100, PropertyFormulas.incidentChanceBp(100)); // would be 0% -> clamp 1%
        assertEquals(800, PropertyFormulas.incidentChanceBp(0));   // would be 10% -> clamp 8%
    }

    // ---- Insurance ---------------------------------------------------------

    @Test
    void insurancePrice() {
        assertEquals(1400, PropertyFormulas.insurancePriceCents(1));
        assertEquals(2000, PropertyFormulas.insurancePriceCents(4));
    }

    // ---- Rent fairness at 100 / 120 / 125 / 150% ---------------------------

    @Test
    void rentFairness_boundaries() {
        assertEquals(100, PropertyFormulas.rentFairness(5_000, 10_000));  // 50% -> full
        assertEquals(100, PropertyFormulas.rentFairness(10_000, 10_000)); // 100%
        assertEquals(60, PropertyFormulas.rentFairness(12_000, 10_000));  // 120%
        assertEquals(50, PropertyFormulas.rentFairness(12_500, 10_000));  // 125%
        assertEquals(0, PropertyFormulas.rentFairness(15_000, 10_000));   // 150%
        assertEquals(0, PropertyFormulas.rentFairness(20_000, 10_000));   // 200% -> clamp 0
    }

    // ---- Scores ------------------------------------------------------------

    @Test
    void facilitiesScore() {
        assertEquals(0.0, PropertyFormulas.facilitiesScore(0), 0.001);
        assertEquals(100.0, PropertyFormulas.facilitiesScore(3), 0.001);
        assertEquals(33.333, PropertyFormulas.facilitiesScore(1), 0.01);
    }

    @Test
    void propertyScore_weightedSum() {
        // 0.45*80 + 0.20*100 + 0.20*100 + 0.15*50 = 36 + 20 + 20 + 7.5 = 83.5
        assertEquals(83.5, PropertyFormulas.propertyScore(80, 100.0, 100, 50), 0.001);
    }

    @Test
    void operatorScore_rangeAndClamp() {
        assertEquals(50, PropertyFormulas.operatorScore(0.5, 0.5, 0.5));
        assertEquals(75, PropertyFormulas.operatorScore(1.0, 1.0, 1.0));
        assertEquals(25, PropertyFormulas.operatorScore(0.0, 0.0, 0.0));
    }

    // ---- Occupancy ---------------------------------------------------------

    @Test
    void fillChance_baseAndClamps() {
        assertEquals(1000, PropertyFormulas.fillChanceBp(50.0, 0));  // 10%
        // Max realistic input (score 100, adv 3) = 1000 + 2500 + 1500 = 5000 (50%),
        // which sits below the 6000 safety ceiling, so it is not clamped.
        assertEquals(5000, PropertyFormulas.fillChanceBp(100.0, 3));
        assertEquals(200, PropertyFormulas.fillChanceBp(10.0, 0));   // would be negative -> clamp 2%
    }

    @Test
    void leaveChance_baseIncidentAndClamps() {
        assertEquals(300, PropertyFormulas.leaveChanceBp(60.0, false)); // 3%
        assertEquals(800, PropertyFormulas.leaveChanceBp(60.0, true));  // + overdue 5%
        assertEquals(2700, PropertyFormulas.leaveChanceBp(0.0, false)); // 3% + 24%
        assertEquals(100, PropertyFormulas.leaveChanceBp(100.0, false)); // clamp 1%
    }

    // ---- Appreciation ------------------------------------------------------

    @Test
    void appreciation_capAndFloor() {
        assertEquals(0, PropertyFormulas.appreciationCents(70.0, 300));
        assertEquals(150, PropertyFormulas.appreciationCents(85.0, 300));
        assertEquals(300, PropertyFormulas.appreciationCents(100.0, 300)); // cap
        assertEquals(0, PropertyFormulas.appreciationCents(65.0, 300));    // below threshold
    }

    // ---- Funding invariant (§3.2) ------------------------------------------

    @Test
    void funding_neverExceedsSeedPlusRatioSinks() {
        // seed $50, sinks $100, ratio 0.9 -> funded $50 + $90 = $140.
        assertEquals(14_000, Funding.fundedCashCents(5_000, 10_000, 0.90));
        // available after $5 paid out.
        assertEquals(13_500, Funding.availableCashCents(14_000, 500));
        // combined max payout.
        assertEquals(13_500, Funding.maxPayoutCents(5_000, 10_000, 500, 0.90));
    }

    @Test
    void funding_floorsFractionalCent() {
        // sinks 1001, 0.9 -> floor(900.9) = 900 -> funded 5000 + 900 = 5900.
        assertEquals(5_900, Funding.fundedCashCents(5_000, 1_001, 0.90));
    }

    @Test
    void funding_payoutsCannotGoNegativeAvailable() {
        // overpaid state should clamp available to 0, never negative.
        assertEquals(0, Funding.availableCashCents(1_000, 5_000));
    }

    // ---- Incident + upgrade tables -----------------------------------------

    @Test
    void incidentWeightsSumTo100() {
        assertEquals(100, IncidentType.totalWeight());
    }

    @Test
    void incidentPickByRoll_boundaries() {
        assertEquals(IncidentType.PESTS, IncidentType.pickByRoll(0));
        assertEquals(IncidentType.PESTS, IncidentType.pickByRoll(39));
        assertEquals(IncidentType.PLUMBING, IncidentType.pickByRoll(40));
        assertEquals(IncidentType.PLUMBING, IncidentType.pickByRoll(74));
        assertEquals(IncidentType.BREAK_IN, IncidentType.pickByRoll(75));
        assertEquals(IncidentType.BREAK_IN, IncidentType.pickByRoll(99));
    }

    @Test
    void upgradeCosts() {
        assertEquals(10_000, UpgradeType.ADVERTISING.costToLevelCents(1));
        assertEquals(50_000, UpgradeType.ADVERTISING.costToLevelCents(3));
        assertEquals(35_000, UpgradeType.FACILITIES.costToLevelCents(2));
        assertThrows(IllegalArgumentException.class, () -> UpgradeType.FACILITIES.costToLevelCents(0));
        assertThrows(IllegalArgumentException.class, () -> UpgradeType.ADVERTISING.costToLevelCents(4));
    }

    // ---- Premium eligibility (§8.4) ----------------------------------------

    @Test
    void premiumEligible_allConditionsMet() {
        assertTrue(PropertyFormulas.premiumEligible(82.0, 85, 72, 8, 1000, 1000, false, false));
    }

    @Test
    void premiumEligible_boundaryValuesInclusive() {
        // exactly at every threshold: score 80, condition 80, op 70, obs 7, asking = 125% of reference.
        assertTrue(PropertyFormulas.premiumEligible(80.0, 80, 70, 7, 1250, 1000, false, false));
    }

    @Test
    void premiumEligible_eachFailureRejects() {
        assertFalse(PropertyFormulas.premiumEligible(79.9, 85, 72, 8, 1000, 1000, false, false)); // score
        assertFalse(PropertyFormulas.premiumEligible(82.0, 79, 72, 8, 1000, 1000, false, false)); // condition
        assertFalse(PropertyFormulas.premiumEligible(82.0, 85, 69, 8, 1000, 1000, false, false)); // op score
        assertFalse(PropertyFormulas.premiumEligible(82.0, 85, 72, 6, 1000, 1000, false, false)); // observations
        assertFalse(PropertyFormulas.premiumEligible(82.0, 85, 72, 8, 1251, 1000, false, false)); // asking > 125%
        assertFalse(PropertyFormulas.premiumEligible(82.0, 85, 72, 8, 1000, 1000, true, false));  // overdue incident
        assertFalse(PropertyFormulas.premiumEligible(82.0, 85, 72, 8, 1000, 1000, false, true));  // delinquency
        assertFalse(PropertyFormulas.premiumEligible(82.0, 85, 72, 8, 1000, 0, false, false));    // no reference
    }

    @Test
    void allMoneyIsIntegralCents() {
        // sanity: results are longs; no rounding surprises at a representative point.
        long rent = PropertyFormulas.tenantRentCents(550, 500, 125, TENANT_CAP);
        assertTrue(rent >= 0);
        assertEquals(550, rent); // min(asking 550, ref*1.25=625, cap 600) = 550
    }
}

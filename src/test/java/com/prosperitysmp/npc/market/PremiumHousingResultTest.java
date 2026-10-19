package com.prosperitysmp.npc.market;

import com.prosperitysmp.npc.market.PremiumHousingEligibility.Reason;
import com.prosperitysmp.npc.market.PremiumHousingEligibility.Result;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

/** A premium result can never claim eligibility the counts and scores inside it do not support. */
class PremiumHousingResultTest {
    private static Result result(boolean eligible, Reason reason, int utilities, int days, int incidents, int retention,
                                 Integer operator, Double property) {
        return new Result(eligible, reason, utilities, days, incidents, retention,
                operator == null ? OptionalInt.empty() : OptionalInt.of(operator),
                property == null ? OptionalDouble.empty() : OptionalDouble.of(property));
    }

    @Test void theWeakestEligibleResultIsAccepted() {
        var weakest = result(true, Reason.ELIGIBLE, 7, 7, 1, 1, 70, 80.0);
        assertTrue(weakest.eligible());
        assertEquals(Reason.ELIGIBLE, weakest.reason());
        assertEquals(70, weakest.operatorScore().getAsInt());
        assertEquals(80.0, weakest.propertyScore().getAsDouble());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(nullValues = "NULL", value = {
            "operator 69, 7, 7, 1, 1, 69, 80.0",
            "property under 80, 7, 7, 1, 1, 70, 79.999",
            "six bill days, 7, 6, 1, 1, 70, 80.0",
            "no incidents, 7, 7, 0, 1, 70, 80.0",
            "no retention, 7, 7, 1, 0, 70, 80.0",
            "no scores, 7, 7, 1, 1, NULL, NULL"})
    void eligibilityNeedsEveryThreshold(String name, int utilities, int days, int incidents, int retention,
                                        Integer operator, Double property) {
        var refusal = assertThrows(IllegalArgumentException.class,
                () -> result(true, Reason.ELIGIBLE, utilities, days, incidents, retention, operator, property), name);
        assertEquals("Invalid premium housing eligibility result", refusal.getMessage());
        assertDoesNotThrow(() -> result(false, Reason.PREMIUM_THRESHOLDS_NOT_MET, utilities, days, incidents, retention,
                operator, property), "the same numbers are a valid refusal");
    }

    @ParameterizedTest
    @EnumSource(value = Reason.class, mode = EnumSource.Mode.EXCLUDE, names = "ELIGIBLE")
    void eligibleMustMatchTheReason(Reason reason) {
        assertThrows(IllegalArgumentException.class, () -> result(true, reason, 7, 7, 1, 1, 70, 80.0));
        assertFalse(result(false, reason, 7, 7, 1, 1, 70, 80.0).eligible());
    }

    @Test void anIneligibleResultCannotCarryTheEligibleReason() {
        assertThrows(IllegalArgumentException.class, () -> result(false, Reason.ELIGIBLE, 7, 7, 1, 1, 70, 80.0));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(nullValues = "NULL", value = {
            "negative utilities, -1, 0, 0, 0, NULL, NULL",
            "more days than bills, 3, 4, 0, 0, NULL, NULL",
            "negative days, 0, -1, 0, 0, NULL, NULL",
            "negative incidents, 0, 0, -1, 0, NULL, NULL",
            "negative retention, 0, 0, 0, -1, NULL, NULL",
            "operator without property, 0, 0, 0, 0, 50, NULL",
            "property without operator, 0, 0, 0, 0, NULL, 50.0",
            "operator 24, 0, 0, 0, 0, 24, 50.0",
            "operator 76, 0, 0, 0, 0, 76, 50.0",
            "property below zero, 0, 0, 0, 0, 50, -0.001",
            "property over 100, 0, 0, 0, 0, 50, 100.001",
            "property NaN, 0, 0, 0, 0, 50, NaN",
            "property infinite, 0, 0, 0, 0, 50, Infinity"})
    void countsAndScoresStayInsideTheirRanges(String name, int utilities, int days, int incidents, int retention,
                                              Integer operator, Double property) {
        assertThrows(IllegalArgumentException.class,
                () -> result(false, Reason.UNKNOWN_RATES, utilities, days, incidents, retention, operator, property), name);
    }

    @ParameterizedTest
    @CsvSource({"25, 0.0", "75, 100.0", "50, 63.5", "25, 100.0", "75, 0.0"})
    void scoreLimitsAreInclusive(int operator, double property) {
        var ok = result(false, Reason.PREMIUM_THRESHOLDS_NOT_MET, 3, 3, 0, 0, operator, property);
        assertEquals(operator, ok.operatorScore().getAsInt());
        assertEquals(property, ok.propertyScore().getAsDouble());
    }

    @Test void equalBillAndDayCountsAreAllowed() {
        assertEquals(5, result(false, Reason.INSUFFICIENT_UTILITY_DAYS, 5, 5, 0, 0, null, null).distinctUtilityBillDays());
    }

    @Test void requiredPartsAreNotNull() {
        assertThrows(NullPointerException.class, () -> new Result(false, null, 0, 0, 0, 0, OptionalInt.empty(), OptionalDouble.empty()));
        assertThrows(NullPointerException.class, () -> new Result(false, Reason.UNKNOWN_RATES, 0, 0, 0, 0, null, OptionalDouble.empty()));
        assertThrows(NullPointerException.class, () -> new Result(false, Reason.UNKNOWN_RATES, 0, 0, 0, 0, OptionalInt.empty(), null));
    }

    @Test void thePolicyVersionIsTheDevelopmentSample() {
        assertEquals("premium-housing-dev-v1", PremiumHousingEligibility.POLICY_VERSION);
    }
}

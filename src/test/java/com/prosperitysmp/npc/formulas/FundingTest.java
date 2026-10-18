package com.prosperitysmp.npc.formulas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class FundingTest {
    @ParameterizedTest(name = "seed {0}, sinks {1}, ratio {2} -> {3}")
    @CsvSource({
            "10000, 50000, 0.90, 55000",
            "10000, 1, 0.90, 10000",
            "10000, 2, 0.90, 10001",
            "0, 0, 0.90, 0",
            "5000, 1001, 0.90, 5900",
            "5000, 10000, 0.0, 5000",
            "5000, 10000, 1.0, 15000",
            "-5, 100, 0.90, 90",
            "5000, -100, 0.90, 5000",
            "-5000, -100, 0.90, 0"})
    void fundingIsTheSeedPlusTheFlooredShareOfSinks(long seed, long sinks, double ratio, long funded) {
        assertEquals(funded, Funding.fundedCashCents(seed, sinks, ratio));
    }

    @Test void aNaNRatioAddsNothingFromSinks() {
        assertEquals(100, Funding.fundedCashCents(100, 1_000, Double.NaN));
    }

    @ParameterizedTest(name = "funded {0}, payouts {1} -> {2}")
    @CsvSource({
            "14000, 500, 13500",
            "5, 9, 0",
            "9, 5, 4",
            "100, 100, 0",
            "100, -50, 100",
            "-10, 0, 0",
            "0, 0, 0",
            "9223372036854775807, 0, 9223372036854775807"})
    void availableCashIsNeverNegative(long funded, long payouts, long available) {
        assertEquals(available, Funding.availableCashCents(funded, payouts));
    }

    @Test void theMaximumPayoutIsTheAvailableCashOfTheFundedAmount() {
        assertEquals(13_500, Funding.maxPayoutCents(5_000, 10_000, 500, 0.90));
        assertEquals(0, Funding.maxPayoutCents(0, 0, 1, 0.90));
        assertEquals(5_899, Funding.maxPayoutCents(5_000, 1_001, 1, 0.90));
        assertEquals(0, Funding.maxPayoutCents(5_000, 1_001, 60_000, 0.90));
        assertEquals(45_000, Funding.maxPayoutCents(-5, 50_000, -3, 0.90));
    }

    @Test void aHugeSeedNeverWrapsToANegativeFundedAmount() {
        long funded;
        try {
            funded = Funding.fundedCashCents(Long.MAX_VALUE, 10, 0.9);
        } catch (ArithmeticException refused) {
            return;
        }
        assertEquals(Long.MAX_VALUE, funded);
    }
}

package com.prosperitysmp.npc.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** The provider's answer for one leg keeps the raw double it reported, never a rounded one. */
class LegResponseTest {

    @Test void aSuccessfulResponseKeepsItsAmount() {
        var response = LegResponse.of(true, 12.34);
        assertTrue(response.success());
        assertEquals(12.34, response.amountDollars());
    }

    @Test void anUnsuccessfulResponseStillKeepsTheAmountTheProviderReported() {
        var response = LegResponse.of(false, 5.5);
        assertFalse(response.success());
        assertEquals(5.5, response.amountDollars());
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, -0.0, 0.2899995, 57492.488404927248686, 8388608.0, 1e9, -1.5, Double.MIN_VALUE,
            Double.MAX_VALUE, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.1 + 0.2})
    void theAmountKeepsEveryBitOfTheDouble(double amount) {
        assertEquals(Double.doubleToRawLongBits(amount),
                Double.doubleToRawLongBits(LegResponse.of(true, amount).amountDollars()));
    }

    @Test void notANumberIsKept() {
        assertTrue(Double.isNaN(LegResponse.of(true, Double.NaN).amountDollars()));
    }
}

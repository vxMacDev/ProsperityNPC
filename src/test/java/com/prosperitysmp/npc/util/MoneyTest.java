package com.prosperitysmp.npc.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

final class MoneyTest {
    @Test void parsesExactConsentAmounts() {
        assertEquals(1000, Money.parseDollarsToCents("10"));
        assertEquals(1005, Money.parseDollarsToCents("$10.05"));
        assertEquals(Long.MAX_VALUE, Money.parseDollarsToCents("92233720368547758.07"));
    }
    @Test void overflowingAmountsCannotBecomeSmallerPositiveConsents() {
        assertEquals(-1, Money.parseDollarsToCents("92233720368547758.08"));
        assertEquals(-1, Money.parseDollarsToCents("9223372036854775807"));
        assertEquals(-1, Money.parseDollarsToCents("184467440737095517"));
    }
}

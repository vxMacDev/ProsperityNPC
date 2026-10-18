package com.prosperitysmp.npc.charge;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The bill ceilings the server owner chose: $200 a bill (each line and the whole bill), $1,000 per owner a day and
 * $25,000 per server a day.
 */
class ChargeCeilingTest {
    private static Map<String, Object> block(String maxCents, String ownerMax, String serverMax) {
        return new LinkedHashMap<>(Map.of("enabled", true,
                "limits", Map.of("per-owner-daily-max-cents", ownerMax, "server-daily-max-cents", serverMax),
                "policies", Map.of("tax", Map.of("version", "1", "title", "Tax", "amount", "100", "max-cents", maxCents))));
    }

    /** Read from the loaded class, not a copy the compiler folded into this test. */
    private static long ceiling(String name) throws Exception {
        return ChargeLimits.class.getField(name).getLong(null);
    }

    @Test void theCeilingsAreTheChosenAmounts() throws Exception {
        assertEquals(20_000L, ceiling("PER_BILL_CEILING_CENTS"));
        assertEquals(20_000L, ceiling("BILL_TOTAL_CEILING_CENTS"));
        assertEquals(100_000L, ceiling("OWNER_DAILY_CEILING_CENTS"));
        assertEquals(2_500_000L, ceiling("SERVER_DAILY_CEILING_CENTS"));
    }

    @Test void aBillMaximumOneCentAboveTwoHundredDollarsIsRefused() throws Exception {
        var atCeiling = ChargeCatalogue.load(block("20000", "100000", "2500000"), Map.of());
        assertEquals(20_000L, atCeiling.policy("tax").orElseThrow().maxCents());
        var failure = assertThrows(ChargeCatalogue.Rejected.class,
                () -> ChargeCatalogue.load(block("20001", "100000", "2500000"), Map.of()));
        assertTrue(failure.getMessage().contains("bills.policies.tax.max-cents"), failure.getMessage());
    }

    @Test void anOwnerDailyLimitOneCentAboveOneThousandDollarsIsRefused() throws Exception {
        assertEquals(100_000L, ChargeCatalogue.load(block("1000", "100000", "2500000"), Map.of()).ownerDailyMaxCents());
        var failure = assertThrows(ChargeCatalogue.Rejected.class,
                () -> ChargeCatalogue.load(block("1000", "100001", "2500000"), Map.of()));
        assertTrue(failure.getMessage().contains("bills.limits.per-owner-daily-max-cents"), failure.getMessage());
    }

    @Test void aServerDailyLimitOneCentAboveTwentyFiveThousandDollarsIsRefused() throws Exception {
        assertEquals(2_500_000L, ChargeCatalogue.load(block("1000", "100000", "2500000"), Map.of()).serverDailyMaxCents());
        var failure = assertThrows(ChargeCatalogue.Rejected.class,
                () -> ChargeCatalogue.load(block("1000", "100000", "2500001"), Map.of()));
        assertTrue(failure.getMessage().contains("bills.limits.server-daily-max-cents"), failure.getMessage());
    }
}

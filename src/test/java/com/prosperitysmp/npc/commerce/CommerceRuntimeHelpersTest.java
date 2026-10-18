package com.prosperitysmp.npc.commerce;

import com.prosperitysmp.npc.resident.ResidentProfile.Occupation;
import java.time.Instant;
import java.time.LocalDate;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

/** The pure parts of the order desk: labels and the stale-quote check, which refuses rather than throws. */
class CommerceRuntimeHelpersTest {
    private static final CommerceCatalogue.Entry HIRE = CommerceCatalogue.entries(Occupation.SHOPKEEPER).get(1);

    private static CommerceOrder quote(long cost) {
        UUID nonce = new UUID(8, 1), player = new UUID(8, 2);
        LocalDate day = LocalDate.of(2026, 10, 18);
        return new CommerceOrder(CommerceOrder.idFor(nonce, player, HIRE.source(), day), nonce, player, new UUID(8, 3),
                HIRE.source(), HIRE.id(), HIRE.material(), HIRE.quantity(), cost, day, Instant.parse("2026-10-18T08:00:00Z"),
                HIRE.delaySeconds());
    }

    @ParameterizedTest
    @CsvSource({"STONE, stone", "OAK_LOG, oak log", "IRON_INGOT, iron ingot", "RAW_IRON, raw iron", "A_B_C, a b c", "COAL, coal"})
    void materialLabelsAreLowercaseWithSpaces(String material, String label) {
        assertEquals(label, CommerceRuntime.materialLabel(material));
    }

    @Test void aMatchingQuoteIsStillValid() {
        assertTrue(CommerceRuntime.quoteStillValid(HIRE, quote(540), 0, OptionalLong.of(100)));
        assertTrue(CommerceRuntime.quoteStillValid(HIRE, quote(500), 40, OptionalLong.of(100)));
    }

    @Test void aStaleQuoteOrAnInvalidRateIsRefusedWithoutThrowing() {
        assertFalse(CommerceRuntime.quoteStillValid(HIRE, quote(540), 0, OptionalLong.of(101)), "the price moved");
        assertFalse(CommerceRuntime.quoteStillValid(HIRE, quote(540), 0, OptionalLong.empty()), "no live price");
        assertFalse(CommerceRuntime.quoteStillValid(HIRE, quote(540), 61, OptionalLong.of(100)), "a rate over 60%");
        assertFalse(CommerceRuntime.quoteStillValid(HIRE, quote(540), -1, OptionalLong.of(100)), "a negative rate");
        assertFalse(CommerceRuntime.quoteStillValid(HIRE, quote(540), 0, OptionalLong.of(0)), "a zero price");
        assertFalse(CommerceRuntime.quoteStillValid(HIRE, quote(540), 0, OptionalLong.of(-100)), "a negative price");
        assertFalse(CommerceRuntime.quoteStillValid(HIRE, quote(540), 10, OptionalLong.of(Long.MAX_VALUE)), "an overflowing price");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "RESTORER|0|42|0|Buy 32 stone - $14.44",
            "RESTORER|1|42|0|Hire: 32 stone - $18.15",
            "CARPENTER|0|250|0|Buy 8 oak log - $21.00",
            "SHOPKEEPER|2|1000|60|Hire: 8 iron ingot - $91.20 (partner rate)",
            "SHOPKEEPER|2|1000|0|Hire: 8 iron ingot - $108.00",
            "COURIER|0|1|0|Buy 4 coal - $1.04",
            "RESTORER|0|1000000|0|Buy 32 stone - $336000.00"})
    void orderLabels(Occupation occupation, int index, long unit, int pct, String label) {
        assertEquals(label, CommerceRuntime.orderLabel(CommerceCatalogue.entries(occupation).get(index), unit, pct));
    }

    @Test void theLabelRefusesAPriceTheCatalogueRefuses() {
        assertThrows(IllegalArgumentException.class, () -> CommerceRuntime.orderLabel(HIRE, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> CommerceRuntime.orderLabel(HIRE, 100, 61));
        assertEquals(" (partner rate)", CommerceRuntime.PARTNER_RATE);
    }
}

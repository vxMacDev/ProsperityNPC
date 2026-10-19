package com.prosperitysmp.npc.commerce;

import com.prosperitysmp.npc.resident.RelationshipMemory.Stage;
import com.prosperitysmp.npc.resident.ResidentProfile.Occupation;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

/** The authored catalogue: three fixed offers per occupation and exact prices that always cover the full material value. */
class CommerceCatalogueTest {
    @ParameterizedTest
    @CsvSource({"RESTORER, STONE, 32, 64", "CARPENTER, OAK_LOG, 8, 16", "GARDENER, WHEAT, 4, 8", "COOK, BREAD, 4, 8",
            "SHOPKEEPER, IRON_INGOT, 4, 8", "COURIER, COAL, 4, 8"})
    void everyOccupationHasExactlySupplyWorkAndSpecialistOffers(Occupation occupation, String material, int quantity,
                                                                int specialistQuantity) {
        String lower = material.toLowerCase(java.util.Locale.ROOT);
        assertEquals(List.of(
                new CommerceCatalogue.Entry("supplies-" + lower, ItemCustody.Source.MERCHANT_GOODS, material, quantity,
                        Stage.ACQUAINTANCE, 0, 500),
                new CommerceCatalogue.Entry("work-" + lower, ItemCustody.Source.WORKER_OUTPUT, material, quantity,
                        Stage.ACQUAINTANCE, 300, 3500),
                new CommerceCatalogue.Entry("specialist-" + lower, ItemCustody.Source.WORKER_OUTPUT, material,
                        specialistQuantity, Stage.TRUSTED, 300, 3500)), CommerceCatalogue.entries(occupation));
    }

    @ParameterizedTest
    @EnumSource(Occupation.class)
    void everyMaterialIsOneThePriceBookPublishes(Occupation occupation) {
        for (var entry : CommerceCatalogue.entries(occupation))
            assertTrue(CommercePriceBook.MATERIALS.contains(entry.material()), entry.id());
    }

    @ParameterizedTest
    @EnumSource(Occupation.class)
    void everyEntryIssuesAValidBoundedOrder(Occupation occupation) {
        UUID nonce = new UUID(4, 4), player = new UUID(5, 5);
        LocalDate day = LocalDate.of(2026, 10, 18);
        for (var entry : CommerceCatalogue.entries(occupation)) {
            var order = new CommerceOrder(CommerceOrder.idFor(nonce, player, entry.source(), day), nonce, player,
                    new UUID(6, 6), entry.source(), entry.id(), entry.material(), entry.quantity(), entry.cost(42), day,
                    Instant.parse("2026-10-18T10:00:00Z"), entry.delaySeconds());
            assertEquals(entry.cost(42), order.costCents());
            assertTrue(entry.quantity() <= CommerceOrder.MAX_QUANTITY);
        }
    }

    @Test void findReturnsTheEntryOfThatOccupationOnly() {
        var entries = CommerceCatalogue.entries(Occupation.RESTORER);
        assertEquals(Optional.of(entries.get(0)), CommerceCatalogue.find(Occupation.RESTORER, "supplies-stone"));
        assertEquals(Optional.of(entries.get(1)), CommerceCatalogue.find(Occupation.RESTORER, "work-stone"));
        assertEquals(Optional.of(entries.get(2)), CommerceCatalogue.find(Occupation.RESTORER, "specialist-stone"));
        assertEquals(Optional.empty(), CommerceCatalogue.find(Occupation.RESTORER, "work-coal"));
        assertEquals(Optional.empty(), CommerceCatalogue.find(Occupation.RESTORER, "WORK-STONE"));
        assertEquals(Optional.empty(), CommerceCatalogue.find(Occupation.RESTORER, ""));
        assertEquals(Optional.empty(), CommerceCatalogue.find(Occupation.RESTORER, null));
        assertEquals("work-coal", CommerceCatalogue.find(Occupation.COURIER, "work-coal").orElseThrow().id());
    }

    @ParameterizedTest(name = "{0} offer {1} at {2} cents and {3}% = {4}")
    @CsvSource({
            "RESTORER, 0, 42, 0, 1444",
            "RESTORER, 1, 42, 0, 1815",
            "RESTORER, 1, 42, 25, 1697",
            "RESTORER, 2, 42, 0, 3629",
            "RESTORER, 0, 1, 0, 132",
            "SHOPKEEPER, 2, 1000, 0, 10800",
            "SHOPKEEPER, 2, 1000, 60, 9120",
            "SHOPKEEPER, 1, 100, 40, 500",
            "COOK, 0, 2500, 0, 10500",
            "COOK, 0, 5000, 0, 21000",
            "COOK, 0, 5001, 0, 21005",
            "CARPENTER, 1, 333, 10, 3504"})
    void exactPrices(Occupation occupation, int index, long unit, int pct, long expected) {
        assertEquals(expected, CommerceCatalogue.entries(occupation).get(index).cost(unit, pct));
    }

    @Test void theOneArgumentCostIsTheUndiscountedCost() {
        for (var occupation : Occupation.values())
            for (var entry : CommerceCatalogue.entries(occupation))
                assertEquals(entry.cost(777, 0), entry.cost(777), entry.id());
    }

    @Test void theServiceFeeNeverDropsBelowOneDollar() {
        var supplies = CommerceCatalogue.entries(Occupation.COOK).get(0);
        assertEquals(4 + 100, supplies.cost(1));
        assertEquals(1_600 + 100, supplies.cost(400), "5% of $16.00 is $0.80, raised to the $1 floor");
        assertEquals(1_996 + 100, supplies.cost(499), "5% of $19.96 is $0.998, rounded up to $1.00");
        assertEquals(2_004 + 101, supplies.cost(501), "5% of $20.04 is $1.002, rounded up to $1.01");
    }

    @Test void pricesAndDiscountsOutsideTheirRangesAreRefused() {
        var entry = CommerceCatalogue.entries(Occupation.GARDENER).get(1);
        assertEquals("A verified full price is required",
                assertThrows(IllegalArgumentException.class, () -> entry.cost(0)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> entry.cost(-1));
        assertThrows(IllegalArgumentException.class, () -> entry.cost(Long.MIN_VALUE, 10));
        assertEquals("Fee discount must be 0..60",
                assertThrows(IllegalArgumentException.class, () -> entry.cost(100, 61)).getMessage());
        assertThrows(IllegalArgumentException.class, () -> entry.cost(100, -1));
        assertThrows(IllegalArgumentException.class, () -> entry.cost(100, Integer.MIN_VALUE));
        assertEquals(500, entry.cost(100, 60), "the largest discount is accepted and the $1 fee floor applies");
        assertEquals(4_560, entry.cost(1_000, 60));
    }

    @Test void theDiscountedFormulaOverflowsBeforeThePlainOneAndSaysSo() {
        var supplies = CommerceCatalogue.entries(Occupation.RESTORER).get(0);
        long unit = 10_000_000_000_000L;
        assertEquals(336_000_000_000_000L, supplies.cost(unit, 0));
        assertThrows(ArithmeticException.class, () -> supplies.cost(unit, 1), "never a wrapped price");
        assertThrows(ArithmeticException.class, () -> supplies.cost(Long.MAX_VALUE / 32 + 1, 0));
    }

    @Test void entriesAreImmutable() {
        var entries = CommerceCatalogue.entries(Occupation.RESTORER);
        assertThrows(UnsupportedOperationException.class, entries::clear);
        assertEquals(entries, CommerceCatalogue.entries(Occupation.RESTORER), "a fresh call gives equal entries");
    }
}

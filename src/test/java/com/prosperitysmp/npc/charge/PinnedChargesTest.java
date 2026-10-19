package com.prosperitysmp.npc.charge;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.*;

class PinnedChargesTest {
    static Stream<Arguments> ranges() {
        return Stream.of(
                Arguments.of(PinnedCharges.UTILITY, 100, 700),
                Arguments.of(PinnedCharges.UPGRADE_ADVERTISING, 10_000, 50_000),
                Arguments.of(PinnedCharges.UPGRADE_FACILITIES, 15_000, 70_000),
                Arguments.of(PinnedCharges.MAINTAIN, 1_000, 1_000),
                Arguments.of(PinnedCharges.REPAIR_CASH, 2_000, 5_000),
                Arguments.of(PinnedCharges.INSURANCE, 1_400, 2_000),
                Arguments.of(PinnedCharges.EVICTION, 1_000, 1_000));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ranges")
    void eachPinnedChargeSpansExactlyTodaysPrices(PinnedCharges.Descriptor charge, long lo, long hi) {
        var result = RangeCheck.check(charge.parsed(), Map.of(), charge.variables());
        assertEquals(new RangeCheck.Interval(Rational.of(lo), Rational.of(hi), true), result);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("ranges")
    void eachPinnedChargeReadsExactlyItsDeclaredValues(PinnedCharges.Descriptor charge, long lo, long hi) {
        assertEquals(charge.variables().keySet(), charge.parsed().names());
        assertThrows(UnsupportedOperationException.class, () -> charge.variables().clear());
    }

    @Test void thereAreSevenPinnedChargesInAFixedOrder() {
        assertEquals(List.of("utility-bill-v1", "upgrade-advertising-v1", "upgrade-facilities-v1", "maintain-v1",
                "repair-cash-v1", "insurance-v1", "eviction-v1"), PinnedCharges.ALL.stream().map(PinnedCharges.Descriptor::id).toList());
    }

    @Test void theUtilityChargeIsExactAtItsCorners() {
        var f = PinnedCharges.UTILITY.parsed();
        assertEquals(Rational.of(100), f.evaluate(Map.of("tenants", Rational.ZERO, "upgrade_levels", Rational.ZERO)));
        assertEquals(Rational.of(700), f.evaluate(Map.of("tenants", Rational.of(4), "upgrade_levels", Rational.of(6))));
        assertEquals(Rational.of(400), f.evaluate(Map.of("tenants", Rational.of(4), "upgrade_levels", Rational.ZERO)));
        assertEquals(Rational.of(400), f.evaluate(Map.of("tenants", Rational.ZERO, "upgrade_levels", Rational.of(6))));
    }

    @Test void theUtilityRangesMatchTheHomeFacts() {
        assertEquals(ChargeVariables.HOME.get("tenants"), PinnedCharges.UTILITY.variables().get("tenants"));
        assertEquals(ChargeVariables.HOME.get("upgrade_levels"), PinnedCharges.UTILITY.variables().get("upgrade_levels"));
        assertEquals(ChargeVariables.HOME.get("capacity"), PinnedCharges.INSURANCE.variables().get("capacity"));
    }

    @Test void aDescriptorWhoseFormulaReadsAnUndeclaredValueDoesNotBind() {
        var bad = new PinnedCharges.Descriptor("x", "tenants + 1", Map.of());
        assertEquals("unknown name 'tenants'", assertThrows(FormulaException.class, bad::parsed).getMessage());
    }

    @Test void pinnedIdsCannotBeConfiguredAsBills() {
        for (var charge : PinnedCharges.ALL) {
            var block = Map.<String, Object>of("policies", Map.of(charge.id(),
                    Map.of("version", "1", "title", "Copy", "amount", "1", "max-cents", "100")));
            var failure = assertThrows(ChargeCatalogue.Rejected.class, () -> ChargeCatalogue.load(block, Map.of()), charge.id());
            assertEquals("bills.policies." + charge.id() + ": today's charges keep their own prices and cannot be configured here",
                    failure.getMessage());
        }
    }
}

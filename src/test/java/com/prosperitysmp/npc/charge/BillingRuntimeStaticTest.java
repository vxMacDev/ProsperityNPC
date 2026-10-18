package com.prosperitysmp.npc.charge;

import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.util.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class BillingRuntimeStaticTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 18);
    private static final UUID OWNER = new UUID(7, 1);
    private static final UUID WORLD = new UUID(9, 9);

    private static ChargeCatalogue.Policy policy(String id, int version) throws Exception {
        return ChargeCatalogue.load(Map.of("policies", Map.of(id, Map.of("version", Integer.toString(version), "title", "Bill",
                "amount", "100", "max-cents", "1000"))), Map.of()).policy(id).orElseThrow();
    }

    private static ChargeCatalogue.Catalogue holdRent() {
        return new ChargeCatalogue.Catalogue(true, false, ChargeCatalogue.OnUnpaid.HOLD_RENT, 7, 1000, 10000, List.of());
    }

    private static BillingRuntime.Open open(UUID home, LocalDate day, LocalDate until) {
        return new BillingRuntime.Open(home, OWNER, 0, day, until);
    }

    private static Property home(UUID id, UUID owner, int epoch, boolean enabled) {
        return new Property(id, WORLD, "home", "town", "room", 2, 1000, 1000, owner, epoch, 80, enabled);
    }

    @ParameterizedTest(name = "{0} cents")
    @CsvSource({"0, $0.00", "5, $0.05", "99, $0.99", "100, $1.00", "101, $1.01", "123456789, $1234567.89",
            "20000, $200.00", "9223372036854775807, $92233720368547758.07"})
    void dollarsWritesWholeDollarsAndTwoCentDigits(long cents, String text) {
        assertEquals(text, BillingRuntime.dollars(cents));
    }

    @Test void negativeCentsFormatLikeMoneyFormat() {
        assertEquals("-$1.05", BillingRuntime.dollars(-105));
        assertEquals(Money.format(-5), BillingRuntime.dollars(-5));
    }

    @Test void theBillsPhaseRunsOnlyWhenSwitchedOnAndLoaded() {
        assertTrue(BillingRuntime.billsPhaseRuns(true, true));
        assertFalse(BillingRuntime.billsPhaseRuns(true, false));
        assertFalse(BillingRuntime.billsPhaseRuns(false, true));
        assertFalse(BillingRuntime.billsPhaseRuns(false, false));
    }

    @Test void aPeriodStartsOnItsFirstDayAndEndsTheDayBeforeItsUntil() throws Exception {
        var period = new ChargeInvoiceRepository.Period(policy("tax", 1), DAY, DAY.plusDays(3));
        assertFalse(period.includes(DAY.minusDays(1)));
        assertTrue(period.includes(DAY));
        assertTrue(period.includes(DAY.plusDays(2)));
        assertFalse(period.includes(DAY.plusDays(3)));
        var open = new ChargeInvoiceRepository.Period(policy("tax", 1), DAY, null);
        assertTrue(open.includes(LocalDate.of(9999, 12, 31)));
        assertFalse(open.includes(DAY.minusDays(1)));
    }

    @Test void inForceChoosesTheVersionWhosePeriodIncludesTheDay() throws Exception {
        var v1 = policy("tax", 1);
        var v2 = policy("tax", 2);
        var periods = List.of(new ChargeInvoiceRepository.Period(v1, DAY.minusDays(5), DAY.plusDays(1)),
                new ChargeInvoiceRepository.Period(v2, DAY.plusDays(1), null));
        assertEquals(List.of(v1), BillingRuntime.inForce(periods, DAY));
        assertEquals(List.of(v2), BillingRuntime.inForce(periods, DAY.plusDays(1)));
        assertEquals(List.of(), BillingRuntime.inForce(periods, DAY.minusDays(6)));
    }

    @Test void inForceListsPoliciesById() throws Exception {
        var periods = List.of(new ChargeInvoiceRepository.Period(policy("zeta", 1), DAY, null),
                new ChargeInvoiceRepository.Period(policy("alpha", 1), DAY, null));
        assertEquals(List.of("alpha", "zeta"), BillingRuntime.inForce(periods, DAY).stream().map(ChargeCatalogue.Policy::id).toList());
    }

    @Test void overlappingVersionsOfOnePolicyAreAnError() throws Exception {
        var periods = List.of(new ChargeInvoiceRepository.Period(policy("tax", 1), DAY, null),
                new ChargeInvoiceRepository.Period(policy("tax", 2), DAY.minusDays(1), null));
        var failure = assertThrows(IllegalStateException.class, () -> BillingRuntime.inForce(periods, DAY));
        assertEquals("Overlapping bill policy versions", failure.getMessage());
        assertEquals(1, BillingRuntime.inForce(periods, DAY.minusDays(1)).size());
    }

    @Test void anOpenBillHoldsRentFromTheDayAfterItsDayThroughItsLastPayableDay() {
        UUID home = new UUID(0, 1);
        var bill = List.of(open(home, DAY.minusDays(1), DAY.plusDays(2)));
        assertEquals(Set.of(), BillingRuntime.rentHolds(holdRent(), DAY.minusDays(1), () -> bill).orElseThrow());
        assertEquals(Set.of(home), BillingRuntime.rentHolds(holdRent(), DAY, () -> bill).orElseThrow());
        assertEquals(Set.of(home), BillingRuntime.rentHolds(holdRent(), DAY.plusDays(2), () -> bill).orElseThrow());
        assertEquals(Set.of(), BillingRuntime.rentHolds(holdRent(), DAY.plusDays(3), () -> bill).orElseThrow());
    }

    @Test void severalBillsForOneHomeHoldItOnce() {
        UUID home = new UUID(0, 1), other = new UUID(0, 2);
        var bills = List.of(open(home, DAY.minusDays(2), DAY.plusDays(2)), open(home, DAY.minusDays(1), DAY.plusDays(3)),
                open(other, DAY, DAY.plusDays(6)));
        assertEquals(Set.of(home), BillingRuntime.rentHolds(holdRent(), DAY, () -> bills).orElseThrow());
    }

    @Test void withoutHoldRentOrWithBillsOffTheCacheIsNotRead() {
        var none = new ChargeCatalogue.Catalogue(true, false, ChargeCatalogue.OnUnpaid.NONE, 7, 1000, 10000, List.of());
        var off = new ChargeCatalogue.Catalogue(false, false, ChargeCatalogue.OnUnpaid.HOLD_RENT, 7, 1000, 10000, List.of());
        for (var catalogue : List.of(none, off))
            assertEquals(Set.of(), BillingRuntime.rentHolds(catalogue, DAY, () -> { throw new AssertionError("read"); }).orElseThrow());
        assertTrue(BillingRuntime.rentHolds(holdRent(), DAY, () -> null).isEmpty());
    }

    @Test void currentOwnershipKeepsOnlyBillsForTheCachedOwnerEpochAndEnabledHome() {
        UUID a = new UUID(0, 1), b = new UUID(0, 2), c = new UUID(0, 3), d = new UUID(0, 4);
        var rows = List.of(open(a, DAY, DAY), open(b, DAY, DAY), open(c, DAY, DAY), open(d, DAY, DAY),
                new BillingRuntime.Open(a, OWNER, 1, DAY, DAY));
        var homes = List.of(home(a, OWNER, 0, true), home(b, new UUID(7, 2), 0, true), home(c, OWNER, 0, false));
        assertEquals(List.of(open(a, DAY, DAY)), BillingRuntime.currentOwnership(rows, homes));
        assertEquals(List.of(), BillingRuntime.currentOwnership(rows, List.of()));
    }
}

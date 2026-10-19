package com.prosperitysmp.npc.adapter.realty;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OwnerRuleTest {
    static final UUID WORLD = new UUID(0, 1), HOLDER = new UUID(0, 2), OTHER = new UUID(0, 3);
    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 18, 12, 0);
    static final Duration MARGIN = Duration.ofSeconds(60);

    static RealtyView.Plot freehold(RealtyView.Holder holder, UUID id) {
        return new RealtyView.Plot(WORLD, "plot", 1, RealtyView.Kind.FREEHOLD, holder, id, null, null, null);
    }

    static RealtyView.Plot lease(UUID tenant, LocalDateTime end, LocalDateTime termination) {
        var holder = tenant == null ? RealtyView.Holder.NONE : RealtyView.Holder.PLAYER;
        return new RealtyView.Plot(WORLD, "plot", 1, RealtyView.Kind.LEASEHOLD, holder, tenant, NOW.minusDays(1), end, termination);
    }

    static RealtyView.Guard guard(UUID... owners) {
        return new RealtyView.Guard("plot", new Object(), List.of(owners), 0, 0);
    }

    static Optional<UUID> pays(RealtyView.Plot plot, RealtyView.Guard guard) {
        return OwnerRule.payable(plot, guard, NOW, MARGIN);
    }

    @Test void aSoldFreeholdPaysItsTitleHolder() {
        assertEquals(Optional.of(HOLDER), pays(freehold(RealtyView.Holder.PLAYER, HOLDER), guard(HOLDER)));
    }

    @Test void anUnsoldFreeholdPaysNobody() {
        assertTrue(pays(freehold(RealtyView.Holder.NONE, null), guard()).isEmpty());
    }

    @Test void aRentedLeasePaysItsTenant() {
        assertEquals(Optional.of(HOLDER), pays(lease(HOLDER, NOW.plusDays(5), null), guard(HOLDER)));
    }

    @Test void aVacantLeasePaysNobody() {
        assertTrue(pays(lease(null, null, null), guard()).isEmpty());
        assertTrue(pays(lease(null, null, null), guard(OTHER)).isEmpty());
    }

    @Test void aRegionWithBothContractsOrNoneIsNotAPlot() {
        for (var kind : List.of(RealtyView.Kind.BOTH, RealtyView.Kind.NONE)) {
            var plot = new RealtyView.Plot(WORLD, "plot", 1, kind, RealtyView.Holder.PLAYER, HOLDER, null, null, null);
            assertTrue(pays(plot, guard(HOLDER)).isEmpty(), kind.toString());
        }
    }

    @Test void aHolderThatIsNotAPlayerOrIsTheZeroUuidPaysNobody() {
        assertTrue(pays(freehold(RealtyView.Holder.OTHER, HOLDER), guard(HOLDER)).isEmpty());
        assertTrue(pays(freehold(RealtyView.Holder.PLAYER, new UUID(0, 0)), guard(new UUID(0, 0))).isEmpty());
        assertTrue(pays(freehold(RealtyView.Holder.PLAYER, null), guard()).isEmpty());
    }

    @Test void aLeaseNeedsAnEndDateThatIsPastTheMargin() {
        assertTrue(pays(lease(HOLDER, null, null), guard(HOLDER)).isEmpty(), "no end date");
        assertTrue(pays(lease(HOLDER, NOW, null), guard(HOLDER)).isEmpty(), "ends now");
        assertTrue(pays(lease(HOLDER, NOW.minusDays(1), null), guard(HOLDER)).isEmpty(), "ended");
        assertTrue(pays(lease(HOLDER, NOW.plusSeconds(60), null), guard(HOLDER)).isEmpty(), "ends at the margin");
        assertTrue(pays(lease(HOLDER, NOW.plusSeconds(30), null), guard(HOLDER)).isEmpty(), "ends inside the margin");
        assertTrue(pays(lease(HOLDER, NOW.plusSeconds(61), null), guard(HOLDER)).isPresent(), "ends past the margin");
    }

    @Test void aTerminationDateEndsTheLeaseEarly() {
        var end = NOW.plusDays(9);
        assertTrue(pays(lease(HOLDER, end, NOW.minusHours(1)), guard(HOLDER)).isEmpty(), "past");
        assertTrue(pays(lease(HOLDER, end, NOW.plusSeconds(10)), guard(HOLDER)).isEmpty(), "inside the margin");
        assertTrue(pays(lease(HOLDER, end, NOW.plusDays(2)), guard(HOLDER)).isPresent(), "still ahead");
    }

    @Test void aWiderMarginReadsALeaseAsEndedSooner() {
        var plot = lease(HOLDER, NOW.plusMinutes(4), null);
        assertTrue(OwnerRule.payable(plot, guard(HOLDER), NOW, Duration.ofMinutes(5)).isEmpty());
        assertTrue(OwnerRule.payable(plot, guard(HOLDER), NOW, Duration.ofMinutes(3)).isPresent());
    }

    @Test void worldGuardMustNameTheHolderAndNobodyElse() {
        var plot = freehold(RealtyView.Holder.PLAYER, HOLDER);
        assertTrue(pays(plot, guard()).isEmpty(), "no owner");
        assertTrue(pays(plot, guard(HOLDER, OTHER)).isEmpty(), "two owners");
        assertTrue(pays(plot, guard(OTHER)).isEmpty(), "another owner");
        assertTrue(pays(plot, new RealtyView.Guard("plot", new Object(), List.of(HOLDER), 1, 0)).isEmpty(), "plus a name");
        assertTrue(pays(plot, new RealtyView.Guard("plot", new Object(), List.of(HOLDER), 0, 1)).isEmpty(), "plus a group");
        assertTrue(pays(plot, new RealtyView.Guard("plot", null, List.of(HOLDER), 0, 0)).isEmpty(), "no identity");
        assertTrue(pays(plot, null).isEmpty(), "no region");
    }

    @Test void theRegionIdsMatchWhateverTheCase() {
        var plot = new RealtyView.Plot(WORLD, "MyPlot", 1, RealtyView.Kind.FREEHOLD, RealtyView.Holder.PLAYER, HOLDER, null, null, null);
        assertTrue(pays(plot, new RealtyView.Guard("myplot", new Object(), List.of(HOLDER), 0, 0)).isPresent());
        assertTrue(pays(plot, new RealtyView.Guard("other", new Object(), List.of(HOLDER), 0, 0)).isEmpty());
    }
}

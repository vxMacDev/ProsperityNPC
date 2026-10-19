package com.prosperitysmp.npc.adapter.realty;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Who pays for a plot. The title holder of a freehold or the tenant of a rented leasehold, and only while
 * WorldGuard names that one player as the plot's owner. No I/O, no state.
 */
final class OwnerRule {
    private static final UUID ZERO = new UUID(0, 0);

    private OwnerRule() { }

    /** The player Realty's database says holds the plot right now, or empty. */
    static Optional<UUID> holder(RealtyView.Plot plot, LocalDateTime now, Duration margin) {
        if (plot.kind() != RealtyView.Kind.FREEHOLD && plot.kind() != RealtyView.Kind.LEASEHOLD) return Optional.empty();
        if (plot.holder() != RealtyView.Holder.PLAYER) return Optional.empty();
        UUID holder = plot.holderId();
        if (holder == null || holder.equals(ZERO)) return Optional.empty();
        if (plot.kind() == RealtyView.Kind.LEASEHOLD && !leaseRunning(plot, now, margin)) return Optional.empty();
        return Optional.of(holder);
    }

    /** A lease counts only while its end date and any termination date are past the margin. */
    private static boolean leaseRunning(RealtyView.Plot plot, LocalDateTime now, Duration margin) {
        LocalDateTime limit = now.plus(margin);
        if (plot.leaseEnd() == null || !plot.leaseEnd().isAfter(limit)) return false;
        return plot.terminationAt() == null || plot.terminationAt().isAfter(limit);
    }

    /**
     * True while a leasehold has a tenant. This is the check for a unit under a freehold, so it leans the other way
     * from {@link #holder}: a missing end date counts as rented, and the margin keeps the unit rented a little past
     * its end instead of freeing it early.
     */
    static boolean tenanted(RealtyView.Plot unit, LocalDateTime now, Duration margin) {
        if (unit.kind() != RealtyView.Kind.LEASEHOLD || unit.holder() == RealtyView.Holder.NONE) return false;
        LocalDateTime limit = now.minus(margin);
        if (unit.leaseEnd() != null && !unit.leaseEnd().isAfter(limit)) return false;
        return unit.terminationAt() == null || unit.terminationAt().isAfter(limit);
    }

    /** True when WorldGuard names the plot's holder, and nobody else, as the owner. */
    static boolean agrees(RealtyView.Plot plot, RealtyView.Guard guard) {
        if (guard == null || guard.identity() == null) return false;
        if (!guard.id().equalsIgnoreCase(plot.regionId())) return false;
        if (guard.ownerNames() != 0 || guard.ownerGroups() != 0) return false;
        return guard.owners().size() == 1 && guard.owners().getFirst().equals(plot.holderId());
    }

    static Optional<UUID> payable(RealtyView.Plot plot, RealtyView.Guard guard, LocalDateTime now, Duration margin) {
        return holder(plot, now, margin).filter(holder -> agrees(plot, guard));
    }
}

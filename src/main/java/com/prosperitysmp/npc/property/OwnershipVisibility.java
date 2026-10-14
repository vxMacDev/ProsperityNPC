package com.prosperitysmp.npc.property;

import java.util.*;
import java.util.function.LongSupplier;
import java.util.concurrent.TimeUnit;

/** Main-thread-only display lease. Never an ownership, admission or payout authorization. */
public final class OwnershipVisibility {
    public static final long LEASE_NANOS = TimeUnit.MINUTES.toNanos(10);
    private record Lease(long verified, long lastRead) {}
    private final Map<UUID, Lease> leases = new HashMap<>();
    private final LongSupplier clock;
    public OwnershipVisibility(LongSupplier clock) { this.clock = Objects.requireNonNull(clock); }
    public void verified(UUID id) {
        Objects.requireNonNull(id);
        if (!leases.containsKey(id) && leases.size() >= 512) throw new IllegalStateException("Display lease limit");
        long now = clock.getAsLong(); leases.put(id, new Lease(now, now));
    }
    public boolean unavailable(UUID id) {
        Lease l = leases.get(id); if (l == null) return true;
        long now = clock.getAsLong();
        if (now - l.lastRead < 0 || now - l.verified < 0 || now - l.verified >= LEASE_NANOS) {
            leases.remove(id); return true;
        }
        leases.put(id, new Lease(l.verified, now)); return false;
    }
    public void invalidate(UUID id) { leases.remove(id); }
    public void clear() { leases.clear(); }
    /** Shared world-event filter; cancelled unloads and unrelated worlds have no effect. */
    public static Set<UUID> affected(Collection<Property> properties, UUID world, boolean cancelled) {
        if (cancelled) return Set.of();
        Set<UUID> ids = new HashSet<>();
        for (Property p : properties) if (p.worldUuid().equals(world)) ids.add(p.propertyId());
        return Set.copyOf(ids);
    }
}

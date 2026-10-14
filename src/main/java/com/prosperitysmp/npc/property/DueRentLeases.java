package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.arm.OwnershipFence;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * housing.rent-change-lease-guard-enabled. The daily asking-rent change step takes the same ownership-fence slot a
 * payment, rent payout, matching run or admission holds, for every cached property, on main before its SQL hop.
 * A property some other holder has right now (for example a payment between its DEBIT and its CREDIT) is excluded:
 * the step neither writes its row nor replaces its cached record, so the payment's CREDIT still sees the exact
 * property it was admitted against. Its change stays pending and a later attempt applies it. While the step holds
 * a property, nothing else can start on it. Main thread only, like the fence.
 */
public final class DueRentLeases {
    /** Attempts the step makes in one request while any due change is deferred, one every RETRY_TICKS. */
    public static final int MAX_ATTEMPTS = 30;
    public static final long RETRY_TICKS = 20L;

    private final Map<UUID, OwnershipFence.Lease> held = new LinkedHashMap<>();
    private final Set<UUID> excluded = new LinkedHashSet<>();
    private boolean released;

    private DueRentLeases() { }

    /** Only a real Boolean true enables the guard; anything else is the old unguarded step. */
    public static boolean enabled(Object configured) { return Boolean.TRUE.equals(configured); }

    /**
     * Takes an exclusion lease per cached property; a busy one (or a duplicate region) is excluded, never waited on.
     * Only OwnershipFence.Busy means busy. Any other failure (a stopped coordinator, the wrong thread, a bad region)
     * aborts the attempt: every lease taken here is released and the failure is thrown, so the caller stops instead
     * of marking every home excluded and trying again.
     */
    public static DueRentLeases acquire(OwnershipFence fence, Collection<Property> cached) {
        Objects.requireNonNull(fence); Objects.requireNonNull(cached);
        var leases = new DueRentLeases();
        try {
            for (Property p : cached) {
                if (leases.held.containsKey(p.propertyId()) || leases.excluded.contains(p.propertyId())) continue;
                try { leases.held.put(p.propertyId(), fence.acquireExclusive(p.worldUuid(), p.regionId())); }
                catch (OwnershipFence.Busy busy) { leases.excluded.add(p.propertyId()); }
            }
        } catch (RuntimeException failure) {
            // Nothing was submitted: every lease taken here may be released.
            try { leases.releaseAll(); } catch (RuntimeException release) { failure.addSuppressed(release); }
            throw failure;
        }
        return leases;
    }

    /** Properties the step must not write or rebase in this attempt. */
    public Set<UUID> excluded() { return Set.copyOf(excluded); }

    /** True while this step holds the property, so its cached record may be replaced. */
    public boolean holds(UUID propertyId) { return !released && held.containsKey(propertyId); }

    public int heldCount() { return released ? 0 : held.size(); }

    /** The properties this step holds right now, in acquisition order; empty once released. */
    public java.util.List<UUID> heldIds() { return released ? java.util.List.of() : java.util.List.copyOf(held.keySet()); }

    /** Call only after the SQL outcome is known and the cache reconciled. Releases every lease; idempotent. */
    public void releaseAll() {
        if (released) return;
        released = true;
        RuntimeException first = null;
        for (var lease : held.values()) {
            try { lease.releaseReconciled(); }
            catch (RuntimeException failure) { if (first == null) first = failure; else first.addSuppressed(failure); }
        }
        if (first != null) throw first;
    }

    /** After an attempt that deferred some due change: true means release, wait RETRY_TICKS and run the step again. */
    public static boolean retry(int deferred, int attempt) {
        return deferred > 0 && attempt < MAX_ATTEMPTS;
    }
}

package com.prosperitysmp.npc.arm;

import com.prosperitysmp.npc.api.OwnershipToken;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Main-thread cooperative exclusion; not protection against uninstrumented WG writers. */
public final class OwnershipFence {
    private record Key(UUID world, String region) {
        Key {
            Objects.requireNonNull(world);
            Objects.requireNonNull(region);
            if (region.isBlank()) throw new IllegalArgumentException("Missing region");
        }
    }

    private static final class State {
        long generation;
        int writers;
        Lease lease;
    }

    /**
     * The region is held right now by another lease or an ARM write. The only refusal that means "busy";
     * every other IllegalStateException (stopped coordinator, wrong thread) is a coordinator failure.
     */
    public static final class Busy extends IllegalStateException {
        private static final long serialVersionUID = 1L;
        private Busy() {
            super("Property is busy");
        }
    }

    private final Map<Key, State> states = new HashMap<>();
    // Thread-safe mirror of the keys that hold a lease right now, written on main. Read by isHeld from any thread.
    private final Set<Key> held = ConcurrentHashMap.newKeySet();
    // Keys an off-main event marked as changed; cleared by the main-thread hop that applies the change.
    private final Set<Key> dirty = ConcurrentHashMap.newKeySet();
    private final BooleanSupplier mainThread;
    private final Consumer<Runnable> mainHop;
    private boolean stopped;

    /** For a fence that never receives off-main events (ARM). markDirty marks the key and never clears it. */
    public OwnershipFence(BooleanSupplier mainThread) {
        this(mainThread, hop -> { throw new IllegalStateException("No main-thread scheduler for markDirty"); });
    }

    /** @param mainHop runs a task on the main thread later (the Bukkit scheduler); called from any thread */
    public OwnershipFence(BooleanSupplier mainThread, Consumer<Runnable> mainHop) {
        this.mainThread = Objects.requireNonNull(mainThread);
        this.mainHop = Objects.requireNonNull(mainHop);
    }

    /** ARM hook must wrap the entire synchronous operation with this callback in finally. */
    public Runnable beginWrite(UUID world, String region) {
        check();
        Key key = new Key(world, region);
        State state = states.computeIfAbsent(key, ignored -> new State());
        if (state.lease != null) throw new IllegalStateException("Property transaction or recovery in progress");
        state.generation = Math.incrementExact(state.generation);
        state.writers = Math.incrementExact(state.writers);
        return new Runnable() {
            private boolean ended;
            @Override public void run() {
                requireMain();
                if (ended) return;
                ended = true;
                if (--state.writers < 0) throw new IllegalStateException("Ownership writer imbalance");
                // No lease may exist while any nested writer is active.
                if (state.writers == 0 && state.lease == null) states.remove(key, state);
            }
        };
    }

    /**
     * Main thread. An owner change the backend could not block. Moves the region's generation on even while a
     * lease holds it, so that lease's next validate fails. Never throws because of a lease; leaves writers and the
     * lease alone. A region nobody holds has nothing to invalidate and is not tracked.
     */
    public void externalChange(UUID world, String region) {
        check();
        State state = states.get(new Key(world, region));
        if (state != null) state.generation = Math.incrementExact(state.generation);
    }

    /**
     * Any thread. Marks the region changed now (every validate on it fails while marked) and posts a main-thread
     * hop that calls externalChange and clears the mark. If the hop cannot be posted the mark stays, which holds the
     * region until a restart rather than letting a payment through.
     */
    public void markDirty(UUID world, String region) {
        Key key = new Key(world, region);
        dirty.add(key);
        mainHop.accept(() -> {
            try {
                if (!stopped) externalChange(world, region);
            } finally {
                dirty.remove(key);
            }
        });
    }

    /** Any thread. A snapshot: true while a lease holds the region. An off-main caller can race an acquire; markDirty closes that. */
    public boolean isHeld(UUID world, String region) {
        return held.contains(new Key(world, region));
    }

    /** Acquire only from a freshly captured strict OwnerRead after hook registration is verified. */
    public Lease acquire(OwnershipToken owner) {
        check();
        Objects.requireNonNull(owner);
        Key key = new Key(owner.worldId(), owner.regionId());
        State state = states.computeIfAbsent(key, ignored -> new State());
        if (state.writers != 0 || state.lease != null) throw new Busy();
        Lease lease = new Lease(key, state, owner);
        state.lease = lease;
        held.add(key);
        return lease;
    }

    /**
     * Exclusion-only lease by region identity, with no owner read. It holds the region exactly like acquire
     * (no transfer, admission, recovery or ARM write can start on it meanwhile, and it fails while any of them
     * holds it), but it authorizes nothing: validate always throws. For a step that changes no ownership and
     * re-checks ownership itself in SQL, such as the daily asking-rent change step.
     */
    public Lease acquireExclusive(UUID world, String region) {
        check();
        Key key = new Key(world, region);
        State state = states.computeIfAbsent(key, ignored -> new State());
        if (state.writers != 0 || state.lease != null) throw new Busy();
        Lease lease = new Lease(key, state, null);
        state.lease = lease;
        held.add(key);
        return lease;
    }

    /** Stop new work without releasing a lease whose worker may still commit. */
    public void stop() {
        requireMain();
        stopped = true;
    }

    public final class Lease {
        private final Key key;
        private final State state;
        private final OwnershipToken owner;
        private final long generation;
        private boolean released;
        private Lease(Key key, State state, OwnershipToken owner) {
            this.key = key;
            this.state = state;
            this.owner = owner;
            this.generation = state.generation;
        }

        public void validate(OwnershipToken current) {
            check();
            if (released || states.get(key) != state || state.lease != this || state.writers != 0
                    || generation != state.generation || owner == null || dirty.contains(key)
                    || !owner.sameIncarnation(current))
                throw new IllegalStateException("Property authority changed");
        }

        /** Call only after the worker has ended and its committed result is reconciled. */
        public void releaseReconciled() {
            requireMain();
            if (released) return;
            if (state.lease != this || state.writers != 0) throw new IllegalStateException("Property lease mismatch");
            released = true;
            state.lease = null;
            held.remove(key);
            states.remove(key, state);
        }
    }

    private void check() {
        requireMain();
        if (stopped) throw new IllegalStateException("Ownership coordinator stopped");
    }

    private void requireMain() {
        if (!mainThread.getAsBoolean()) throw new IllegalStateException("Ownership fence requires main thread");
    }
}

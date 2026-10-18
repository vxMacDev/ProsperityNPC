package com.prosperitysmp.npc.adapter.realty;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A snapshot of Realty's plots. Reads on the main thread never wait on the database: the reader thread fills the
 * snapshot and the main thread applies it. All state is touched on the main thread only.
 *
 * <p>A slot is FOUND only while its data is fresh. An event, a disagreement with WorldGuard or a new WorldGuard
 * object marks the slot stale at once and gives it a new identity, so tokens taken before it stop matching.
 */
final class RegionCache {
    /** Timings in milliseconds. None of these is measured on a real server yet. */
    static final class Tuning {
        static final long FULL_EVERY = 600_000;
        static final long HOT_EVERY = 60_000;
        static final long HOT_WINDOW = 900_000;
        static final long HOT_TTL = 300_000;
        static final long TTL = 1_500_000;
        static final long RETRY = 5_000;
        static final long DISAGREE_RETOUCH = 5_000;
        static final long CLOCK_MARGIN = 60_000;
        static final int CHUNK = 50;
        static final int MAX_ID = 128;

        private Tuning() { }
    }

    enum Status { FOUND, STALE, ABSENT, NOT_LOADED }

    record Lookup(Status status, RealtyView.Plot plot, Object identity) { }

    record Key(UUID world, String region) { }

    private record Seen(int realtyRegionId, UUID holder) { }

    private record Request(Key key, String ask, LocalDateTime rentedAt) { }

    private record Loaded(Request request, long started, boolean ok, Optional<RealtyView.Plot> plot) { }

    private static final class Slot {
        final String ask;
        RealtyView.Plot plot;
        Object identity = new Object();
        boolean stale = true;
        long confirmedAt;
        long lastRead = -1;
        long touchEpoch;
        long nextTry;
        Object guardSeen;

        Slot(String ask) { this.ask = ask; }
    }

    /** A full load in progress: the list first, then the contracts a chunk at a time. */
    private static final class FullLoad {
        final long started;
        final long startEpoch;
        final int generation;
        final Set<Key> seen = new HashSet<>();
        List<RealtyView.Ref> refs = List.of();
        int next;

        FullLoad(long started, long startEpoch, int generation) {
            this.started = started;
            this.startEpoch = startEpoch;
            this.generation = generation;
        }
    }

    private final RealtyView view;
    private final RealtyView.Source source;
    private final Consumer<String> log;
    private final Map<Key, Slot> slots = new HashMap<>();
    private final Map<Key, Seen> seen = new HashMap<>();
    private final Map<Key, Set<UUID>> suspects = new HashMap<>();
    private final Set<Key> held = new LinkedHashSet<>();
    private final Map<Key, Long> lastDisagree = new HashMap<>();
    private final Map<Key, Integer> inFlight = new HashMap<>();
    private final Map<UUID, Map<String, List<String>>> childIndexes = new HashMap<>();
    private long epoch;
    private int generation;
    private boolean loadedOnce;
    private boolean fullInFlight;
    private boolean lastFullGood;
    private boolean fullTried;
    private boolean hotQueued;
    private long lastFullStart;
    private long lastFullAttempt;
    private long lastHotStart = Long.MIN_VALUE / 2;
    private Duration skewBound;
    private int skippedLongIds;
    private int failures;
    private volatile boolean linkageFailed;

    RegionCache(RealtyView view, Consumer<String> log) {
        this.view = view;
        this.source = view.source();
        this.log = log;
    }

    static Key key(UUID world, String regionId) {
        return new Key(world, regionId.toLowerCase(Locale.ROOT));
    }

    Lookup find(UUID world, String regionId, boolean markHot) {
        if (!loadedOnce || lastFullAgeMillis() > Tuning.TTL) return new Lookup(Status.NOT_LOADED, null, null);
        Slot slot = slots.get(key(world, regionId));
        if (slot == null) return new Lookup(Status.ABSENT, null, null);
        long now = view.nowMillis();
        if (markHot) slot.lastRead = now;
        return new Lookup(statusOf(slot, now), slot.plot, slot.identity);
    }

    private Status statusOf(Slot slot, long now) {
        if (slot.plot == null || slot.stale) return Status.STALE;
        long ttl = isHot(slot, now) ? Tuning.HOT_TTL : Tuning.TTL;
        return now - slot.confirmedAt > ttl ? Status.STALE : Status.FOUND;
    }

    private static boolean isHot(Slot slot, long now) {
        return slot.lastRead >= 0 && now - slot.lastRead <= Tuning.HOT_WINDOW;
    }

    /** An event named this region. Marks it stale, gives it a new identity and reloads it. */
    void touched(UUID world, String regionId, LocalDateTime rentedAt) {
        if (regionId.length() > Tuning.MAX_ID) return;
        Key key = key(world, regionId);
        Slot slot = slots.computeIfAbsent(key, k -> new Slot(regionId));
        mark(slot);
        read(List.of(requestFor(key, slot, rentedAt)), null);
    }

    /**
     * Notes players who are on the owner list of a plot that has no holder. If the title later moves to one of them,
     * the plot is held. It does not reload the slot, so it is cheap to call on every read.
     */
    void suspect(UUID world, String regionId, Collection<UUID> listed) {
        if (listed.isEmpty()) return;
        suspects.computeIfAbsent(key(world, regionId), k -> new HashSet<>()).addAll(listed);
    }

    /** WorldGuard disagreed with a FOUND slot. The listed owners become suspects for a title taken from a stale list. */
    void disagree(UUID world, String regionId, Collection<UUID> listed) {
        Key key = key(world, regionId);
        Slot slot = slots.get(key);
        if (slot == null) return;
        suspect(world, regionId, listed);
        long now = view.nowMillis();
        Long last = lastDisagree.get(key);
        if (last != null && now - last < Tuning.DISAGREE_RETOUCH) return;
        lastDisagree.put(key, now);
        mark(slot);
        read(List.of(requestFor(key, slot, null)), null);
    }

    /** False when WorldGuard hands out another region object than the last read saw. The slot reloads first. */
    boolean noteGuard(UUID world, String regionId, Object guard) {
        Key key = key(world, regionId);
        Slot slot = slots.get(key);
        if (slot == null) return false;
        if (slot.guardSeen == null) {
            slot.guardSeen = guard;
            return true;
        }
        if (slot.guardSeen == guard) return true;
        mark(slot);
        slot.guardSeen = guard;
        read(List.of(requestFor(key, slot, null)), null);
        return false;
    }

    private void mark(Slot slot) {
        slot.touchEpoch = ++epoch;
        slot.stale = true;
        slot.identity = new Object();
        slot.guardSeen = null;
    }

    private Request requestFor(Key key, Slot slot, LocalDateTime rentedAt) {
        String ask = slot.plot != null ? slot.plot.regionId() : slot.ask;
        return new Request(key, ask, rentedAt);
    }

    // Loading

    /** Main thread, once: the result of the first full load. */
    void applyFirst(List<RealtyView.Plot> plots, long startedAt) {
        var load = new FullLoad(startedAt, epoch, generation);
        applyChunk(load, plots);
        finish(load);
        fullTried = true;
        lastFullAttempt = startedAt;
    }

    void tick() {
        if (!view.installed()) return;
        long now = view.nowMillis();
        startFullIfDue(now);
        startHotIfDue(now);
        retryStale(now);
    }

    private void startFullIfDue(long now) {
        if (fullInFlight) return;
        long every = lastFullGood ? Tuning.FULL_EVERY : Tuning.RETRY;
        if (fullTried && now - lastFullAttempt < every) return;
        fullTried = true;
        lastFullAttempt = now;
        fullInFlight = true;
        var load = new FullLoad(now, epoch, generation);
        view.runAsync(() -> {
            List<RealtyView.Ref> refs = null;
            try {
                refs = source.list();
            } catch (Exception | LinkageError failure) {
                noteLinkage(failure);
            }
            List<RealtyView.Ref> listed = refs;
            view.runOnMain(() -> listed(load, listed));
        });
    }

    private void listed(FullLoad load, List<RealtyView.Ref> refs) {
        if (load.generation != generation) return;
        if (refs == null) {
            failFull();
            return;
        }
        var usable = new ArrayList<RealtyView.Ref>();
        for (var ref : refs) {
            if (ref.regionId().length() <= Tuning.MAX_ID) usable.add(ref);
        }
        skippedLongIds = refs.size() - usable.size();
        load.refs = usable;
        nextChunk(load);
    }

    private void nextChunk(FullLoad load) {
        if (load.next >= load.refs.size()) {
            finish(load);
            return;
        }
        int end = Math.min(load.refs.size(), load.next + Tuning.CHUNK);
        List<RealtyView.Ref> chunk = load.refs.subList(load.next, end);
        load.next = end;
        view.runAsync(() -> {
            List<RealtyView.Plot> plots = null;
            try {
                plots = source.read(chunk);
            } catch (Exception | LinkageError failure) {
                noteLinkage(failure);
            }
            List<RealtyView.Plot> read = plots;
            view.runOnMain(() -> chunkRead(load, read));
        });
    }

    private void chunkRead(FullLoad load, List<RealtyView.Plot> plots) {
        if (load.generation != generation) return;
        if (plots == null) {
            failFull();
            return;
        }
        applyChunk(load, plots);
        nextChunk(load);
    }

    /** Runs on the reader thread. The backend reads the flag on the main thread. */
    private void noteLinkage(Throwable failure) {
        if (failure instanceof LinkageError) linkageFailed = true;
    }

    /** True once a read on the reader thread hit an incompatible Realty class. */
    boolean linkageFailed() {
        return linkageFailed;
    }

    private void failFull() {
        failures++;
        fullInFlight = false;
        lastFullGood = false;
    }

    private void applyChunk(FullLoad load, List<RealtyView.Plot> plots) {
        var worlds = new HashSet<UUID>();
        for (var plot : plots) {
            if (plot.regionId().length() > Tuning.MAX_ID) {
                skippedLongIds++;
                continue;
            }
            Key key = key(plot.worldId(), plot.regionId());
            load.seen.add(key);
            worlds.add(plot.worldId());
            Slot slot = slots.get(key);
            if (slot != null && slot.touchEpoch > load.startEpoch) continue;
            if (slot == null) {
                slot = new Slot(plot.regionId());
                slots.put(key, slot);
            }
            apply(key, slot, plot, load.started);
        }
        worlds.forEach(this::refreshIndex);
    }

    private void finish(FullLoad load) {
        slots.entrySet().removeIf(e -> !load.seen.contains(e.getKey()) && e.getValue().touchEpoch <= load.startEpoch);
        loadedOnce = true;
        lastFullStart = load.started;
        lastFullGood = true;
        fullInFlight = false;
    }

    private void startHotIfDue(long now) {
        if (hotQueued || now - lastHotStart < Tuning.HOT_EVERY) return;
        lastHotStart = now;
        var due = new ArrayList<Request>();
        for (var entry : slots.entrySet()) {
            Slot slot = entry.getValue();
            boolean hot = slot.plot != null && isHot(slot, now);
            if (hot && !loading(entry.getKey())) {
                due.add(requestFor(entry.getKey(), slot, null));
            }
        }
        if (due.isEmpty()) return;
        hotQueued = true;
        read(due, () -> hotQueued = false);
    }

    private void retryStale(long now) {
        var due = new ArrayList<Request>();
        for (var entry : slots.entrySet()) {
            Slot slot = entry.getValue();
            boolean retryDue = slot.stale && slot.nextTry <= now;
            if (retryDue && !loading(entry.getKey())) {
                due.add(requestFor(entry.getKey(), slot, null));
            }
        }
        if (!due.isEmpty()) read(due, null);
    }

    private boolean loading(Key key) {
        return inFlight.getOrDefault(key, 0) > 0;
    }

    /** Reads these regions on the reader thread, one after the other, and applies the answers on the main thread. */
    private void read(List<Request> requests, Runnable after) {
        int issuedIn = generation;
        long startEpoch = epoch;
        long now = view.nowMillis();
        for (var request : requests) {
            inFlight.merge(request.key(), 1, Integer::sum);
            Slot slot = slots.get(request.key());
            if (slot != null) slot.nextTry = now + Tuning.RETRY;
        }
        view.runAsync(() -> {
            var answers = new ArrayList<Loaded>();
            for (var request : requests) answers.add(loadOne(request));
            view.runOnMain(() -> {
                if (issuedIn != generation) return;
                var worlds = new HashSet<UUID>();
                for (var answer : answers) {
                    applyOne(startEpoch, answer, worlds);
                }
                worlds.forEach(this::refreshIndex);
                if (after != null) after.run();
            });
        });
    }

    private Loaded loadOne(Request request) {
        long started = view.nowMillis();
        try {
            return new Loaded(request, started, true, source.load(request.key().world(), request.ask()));
        } catch (Exception | LinkageError failure) {
            noteLinkage(failure);
            return new Loaded(request, started, false, Optional.empty());
        }
    }

    private void applyOne(long startEpoch, Loaded answer, Set<UUID> changedWorlds) {
        Key key = answer.request().key();
        inFlight.computeIfPresent(key, (k, count) -> count > 1 ? count - 1 : null);
        if (!answer.ok()) {
            failures++;
            return;
        }
        Slot slot = slots.get(key);
        if (slot == null || slot.touchEpoch > startEpoch) return;
        if (answer.plot().isEmpty()) {
            slots.remove(key);
            return;
        }
        RealtyView.Plot plot = answer.plot().get();
        apply(key, slot, plot, answer.started());
        sampleSkew(answer.request().rentedAt(), plot);
        changedWorlds.add(plot.worldId());
    }

    private void apply(Key key, Slot slot, RealtyView.Plot plot, long stamp) {
        checkHold(key, slot, plot);
        if (!plot.equals(slot.plot)) {
            slot.identity = new Object();
            slot.guardSeen = null;
        }
        slot.plot = plot;
        slot.stale = false;
        slot.confirmedAt = stamp;
        seen.put(key, new Seen(plot.realtyRegionId(), plot.holderId()));
    }

    // Held plots

    /**
     * Two ways a plot can pay the wrong player without a purchase: a region deleted and registered again under
     * another holder, and a title moved by a player who was only on a stale WorldGuard owner list.
     */
    private void checkHold(Key key, Slot slot, RealtyView.Plot plot) {
        Seen before = seen.get(key);
        if (registeredAgainUnderAnotherHolder(before, plot)) {
            hold(key, "the region was registered again under another holder");
        }
        Set<UUID> listed = suspects.get(key);
        UUID holder = plot.holderId();
        boolean moved = slot.plot != null && holder != null && !holder.equals(slot.plot.holderId());
        if (moved && listed != null && listed.contains(holder))
            hold(key, "the title moved to a player from a stale owner list");
    }

    private static boolean registeredAgainUnderAnotherHolder(Seen before, RealtyView.Plot plot) {
        if (before == null || before.realtyRegionId() == plot.realtyRegionId()) return false;
        return !Objects.equals(before.holder(), plot.holderId());
    }

    private void hold(Key key, String why) {
        if (held.add(key)) log.accept("Realty plot " + key.region() + " is held until staff release it: " + why + ".");
    }

    boolean isHeld(UUID world, String regionId) {
        return held.contains(key(world, regionId));
    }

    Set<Key> held() {
        return Set.copyOf(held);
    }

    /** Lets a held plot pay again. Returns false when it was not held. */
    boolean release(UUID world, String regionId) {
        Key key = key(world, regionId);
        if (!held.remove(key)) return false;
        suspects.remove(key);
        Slot slot = slots.get(key);
        if (slot != null && slot.plot != null) seen.put(key, new Seen(slot.plot.realtyRegionId(), slot.plot.holderId()));
        return true;
    }

    // Nested plots

    /** Main thread. A world that cannot be indexed is unknown again, so nothing under it is paid on old data. */
    void refreshIndex(UUID world) {
        try {
            childIndexes.put(world, view.childIndex(world));
        } catch (RuntimeException | LinkageError unreadable) {
            noteLinkage(unreadable);
            childIndexes.remove(world);
            failures++;
        }
    }

    /** Direct WorldGuard children of a region as of the last load. Empty when the world has not been indexed. */
    Optional<List<String>> childrenOf(UUID world, String regionId) {
        Map<String, List<String>> index = childIndexes.get(world);
        if (index == null) return Optional.empty();
        return Optional.of(index.getOrDefault(regionId.toLowerCase(Locale.ROOT), List.of()));
    }

    // Clock

    private void sampleSkew(LocalDateTime rentedAt, RealtyView.Plot plot) {
        if (rentedAt == null || plot.leaseStart() == null) return;
        Duration sample = Duration.between(rentedAt, plot.leaseStart()).abs();
        if (skewBound == null || sample.compareTo(skewBound) > 0) skewBound = sample;
    }

    /** The largest gap seen between the database clock and the server clock, or empty until a rent was seen. */
    Optional<Duration> skewBound() {
        return Optional.ofNullable(skewBound);
    }

    /** How close to a lease end counts as ended. Never below the fixed margin: one sample can only under-measure. */
    Duration margin() {
        Duration floor = Duration.ofMillis(Tuning.CLOCK_MARGIN);
        return skewBound != null && skewBound.compareTo(floor) > 0 ? skewBound : floor;
    }

    // State

    /** Realty or WorldGuard went away. Drops everything but the held list. */
    void clear() {
        generation++;
        slots.clear();
        seen.clear();
        suspects.clear();
        lastDisagree.clear();
        inFlight.clear();
        childIndexes.clear();
        loadedOnce = false;
        fullInFlight = false;
        fullTried = false;
        hotQueued = false;
    }

    boolean loadedOnce() { return loadedOnce; }

    /** Milliseconds since the start of the last good full load; the largest long when there was none. */
    long lastFullAgeMillis() {
        return loadedOnce ? view.nowMillis() - lastFullStart : Long.MAX_VALUE;
    }

    int size() { return slots.size(); }

    int hotSize() {
        long now = view.nowMillis();
        return (int) slots.values().stream().filter(slot -> isHot(slot, now)).count();
    }

    int skippedLongIds() { return skippedLongIds; }

    int failures() { return failures; }
}

package com.prosperitysmp.npc.adapter.realty;

import com.prosperitysmp.npc.api.Capabilities;
import com.prosperitysmp.npc.api.GuardBinding;
import com.prosperitysmp.npc.api.GuardMode;
import com.prosperitysmp.npc.api.GuardResult;
import com.prosperitysmp.npc.api.OwnershipHooks;
import com.prosperitysmp.npc.api.OwnershipToken;
import com.prosperitysmp.npc.api.Presence;
import com.prosperitysmp.npc.api.PropertyBackend;
import com.prosperitysmp.npc.api.ProsperityNpcApi;
import com.prosperitysmp.npc.api.RegionRef;
import com.prosperitysmp.npc.api.RegionView;
import org.bukkit.Location;
import org.bukkit.World;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The property backend for Realty plots (id {@code realty}). The home is a WorldGuard region that Realty manages;
 * the paying owner is the title holder of a freehold or the tenant of a rented leasehold, and only while the plot's
 * WorldGuard owner list holds that one player. A vacant unit, an unsold plot and a landlord or authority are never
 * paid. A rented subregion pays its own tenant.
 *
 * <p>Guard: none. Realty can change a holder or tenant through paths that fire no event, or one that cannot be
 * cancelled, so a veto would promise more than it keeps. The handlers still report every change they see, so the
 * fence moves on. Payouts follow {@code property.when-unguarded}.
 *
 * <p>Realty keeps its data in a database, so owners are read from a snapshot that a reader thread fills.
 */
public final class RealtyPropertyBackend implements PropertyBackend {
    public static final String ID = "realty";
    public static final String VERIFICATION = "compiled against Realty 2.0.0 and booted on a test server; not tested with real players";
    static final long FIRST_LOAD_WAIT_MILLIS = 20_000;
    private static final UUID ZERO = new UUID(0, 0);

    private final RealtyView view;
    private final Consumer<String> log;
    private final RegionCache cache;
    private OwnershipHooks hooks;
    private boolean guardInstalled;
    private boolean warnedTick;
    private boolean warnedSkew;
    private volatile boolean linkageFailed;

    /** @param log one line per held plot or failed load, for staff */
    public RealtyPropertyBackend(RealtyView view, Consumer<String> log) {
        this.view = Objects.requireNonNull(view);
        this.log = Objects.requireNonNull(log);
        this.cache = new RegionCache(view, log);
    }

    /** One owner read: the same incarnation only for the same cache slot, WorldGuard region object and owner. */
    record Token(UUID worldId, String regionId, UUID ownerId, Object slot, Object guard) implements OwnershipToken {
        @Override
        public boolean sameIncarnation(OwnershipToken other) {
            return other instanceof Token t && worldId.equals(t.worldId) && regionId.equals(t.regionId)
                    && ownerId.equals(t.ownerId) && slot != null && slot == t.slot && guard != null && guard == t.guard;
        }
    }

    private record At(UUID worldId, String regionId) implements RegionRef { }

    /** A plot that pays: who, and the two objects the token is made of. */
    private record Payable(UUID owner, RealtyView.Plot plot, Object slot, Object guard) { }

    /** What one look at a plot found: a payer, nobody to pay, or a state that only means "cannot read now". */
    private record Reading(Payable payable, boolean readable) {
        static final Reading NOBODY = new Reading(null, true);
        static final Reading UNREADABLE = new Reading(null, false);

        static Reading of(Payable payable) {
            return new Reading(payable, true);
        }

        Optional<Payable> payer() {
            return Optional.ofNullable(payable);
        }
    }

    /** What the units registered below a freehold say about paying it. */
    private enum Units { CLEAR, RENTED, UNKNOWN }

    @Override public String id() { return ID; }
    @Override public int apiMajor() { return ProsperityNpcApi.MAJOR; }
    @Override public int apiMinor() { return ProsperityNpcApi.MINOR; }
    @Override public String label() { return "unverified"; }
    @Override public String displayName() { return "Realty"; }

    @Override
    public Capabilities capabilities() {
        return Capabilities.builder()
                .value("guard", "none")
                .value("owner-model", "title holder or tenant; one player UUID")
                .flag("location-lookup", true)
                .flag("region-by-id", true)
                .flag("distinguishes-missing", true)
                .value("token-identity", "object")
                .flag("sees-owner-round-trip", false)
                .flag("needs-worldguard", true)
                .value("region-id-max-length", Integer.toString(RegionCache.Tuning.MAX_ID))
                .flag("main-thread-only", true)
                .build();
    }

    // Loading

    /** Main thread, in onEnable: the first full load, so the snapshot exists when the core binds the backend. */
    public void start() {
        long started = view.nowMillis();
        try {
            cache.applyFirst(view.loadFirst(FIRST_LOAD_WAIT_MILLIS), started);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception | LinkageError failure) {
            noteFailure(failure);
            log.accept("Realty: the first load did not finish (" + failure.getClass().getSimpleName()
                    + "). Payouts stay held until a load succeeds.");
        }
    }

    /** Main thread, once a second. */
    public void tick() {
        try {
            cache.tick();
            warnAboutClocks();
        } catch (RuntimeException | LinkageError failure) {
            noteFailure(failure);
            if (!warnedTick) {
                log.accept("Realty: a refresh failed (" + failure.getClass().getSimpleName() + ").");
            }
            warnedTick = true;
        }
    }

    private void warnAboutClocks() {
        Optional<Duration> skew = cache.skewBound();
        if (warnedSkew || skew.isEmpty() || skew.get().toMillis() <= RegionCache.Tuning.CLOCK_MARGIN) return;
        warnedSkew = true;
        log.accept("Realty: the database clock differs from the server clock by about " + skew.get().toSeconds()
                + "s. Keep both on one time source.");
    }

    /** Realty, WorldGuard or WorldEdit went away. */
    public void reset() {
        cache.clear();
        view.reset();
    }

    public void stop() {
        cache.clear();
        view.stop();
    }

    private void noteFailure(Throwable failure) {
        if (failure instanceof LinkageError) linkageFailed = true;
    }

    /** True once any read, on the main thread or the reader thread, hit an incompatible class. */
    private boolean linkageLatched() {
        return linkageFailed || cache.linkageFailed();
    }

    @Override
    public boolean available() {
        try {
            if (linkageLatched() || !view.installed()) return false;
            return cache.lastFullAgeMillis() <= RegionCache.Tuning.TTL;
        } catch (RuntimeException | LinkageError failure) {
            noteFailure(failure);
            return false;
        }
    }

    // Guard

    @Override
    public GuardResult installGuard(OwnershipHooks hooks) {
        this.hooks = Objects.requireNonNull(hooks);
        try {
            if (!view.installed()) return GuardResult.failed("Realty is not available: " + view.problem());
        } catch (RuntimeException | LinkageError failure) {
            noteFailure(failure);
            return GuardResult.failed("Realty is not available");
        }
        guardInstalled = true;
        return GuardResult.installed(GuardMode.NONE);
    }

    @Override
    public List<GuardBinding> guardBindings() {
        if (hooks == null || !guardInstalled) return List.of();
        return view.bindings(new Handlers(hooks));
    }

    /** The guard's reactions. Every body runs inside hooks.guarded, so a vendor error holds payments. */
    private final class Handlers implements RealtyView.Events {
        private final OwnershipHooks hooks;

        Handlers(OwnershipHooks hooks) { this.hooks = hooks; }

        @Override
        public void changed(UUID worldId, String regionId, boolean rented) {
            hooks.guarded("Realty event", () -> {
                if (view.onMainThread()) {
                    react(worldId, regionId, rented, true);
                    return;
                }
                hooks.markDirty(worldId, regionId);
                view.runOnMain(() -> hooks.guarded("Realty event", () -> react(worldId, regionId, rented, false)));
            });
        }

        private void react(UUID worldId, String regionId, boolean rented, boolean includeEventId) {
            cache.touched(worldId, regionId, rented ? view.now() : null);
            // A unit made without an event is new to the index, and its parent must see it before the next read.
            cache.refreshIndex(worldId);
            bump(worldId, regionId, includeEventId);
        }

        /**
         * The region, its canonical spelling, every region below it and every region above it, once each. A unit
         * changing hands moves the freehold around it, so a payment planned for that freehold has to fail.
         */
        private void bump(UUID worldId, String regionId, boolean includeEventId) {
            var ids = new LinkedHashSet<String>();
            ids.add(canonical(worldId, regionId));
            ids.add(regionId);
            for (String child : view.descendants(worldId, regionId)) {
                ids.add(child);
                ids.add(canonical(worldId, child));
            }
            for (String parent : view.ancestors(worldId, regionId)) {
                ids.add(parent);
                ids.add(canonical(worldId, parent));
            }
            if (!includeEventId) ids.remove(regionId);
            for (String id : ids) hooks.externalChange(worldId, id);
        }
    }

    /** The spelling Realty's database holds, or the given one when the plot is not known. */
    private String canonical(UUID world, String regionId) {
        RealtyView.Plot plot = cache.find(world, regionId, false).plot();
        return plot != null ? plot.regionId() : regionId;
    }

    // Reads

    @Override
    public Optional<RegionRef> regionAt(Location at) {
        try {
            if (at == null || at.getWorld() == null || !available()) return Optional.empty();
            World world = at.getWorld();
            UUID worldId = world.getUID();
            if (!worldId.equals(view.worldId(world.getName()))) return Optional.empty();
            var plots = new ArrayList<RealtyView.Candidate>();
            for (var candidate : view.regionsAt(at)) {
                if (isPlot(worldId, candidate.id())) plots.add(candidate);
            }
            return innermost(plots).map(candidate -> new At(worldId, canonical(worldId, candidate.id())));
        } catch (RuntimeException | LinkageError failure) {
            noteFailure(failure);
            return Optional.empty();
        }
    }

    private boolean isPlot(UUID world, String regionId) {
        if (!validId(regionId)) return false;
        RegionCache.Status status = cache.find(world, regionId, false).status();
        return status == RegionCache.Status.FOUND || status == RegionCache.Status.STALE;
    }

    /** The one region that has every other region in its parent chain. Two unrelated plots give nothing. */
    private static Optional<RealtyView.Candidate> innermost(List<RealtyView.Candidate> plots) {
        for (var candidate : plots) {
            boolean deepest = true;
            for (var other : plots) {
                if (other != candidate && !hasAncestor(candidate, other.id())) deepest = false;
            }
            if (deepest) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    private static boolean hasAncestor(RealtyView.Candidate candidate, String id) {
        return candidate.ancestors().stream().anyMatch(id::equalsIgnoreCase);
    }

    @Override
    public Optional<RegionView> findRegion(String worldName, String regionId) {
        try {
            UUID world = worldName == null ? null : view.worldId(worldName);
            if (world == null || !validId(regionId) || !available()) return Optional.empty();
            RegionCache.Lookup lookup = cache.find(world, regionId, true);
            if (lookup.status() != RegionCache.Status.FOUND || cache.isHeld(world, regionId)) return Optional.empty();
            Reading reading = payable(world, regionId);
            if (!reading.readable()) return Optional.empty();
            Optional<UUID> owner = reading.payer().map(Payable::owner);
            String canonical = lookup.plot().regionId();
            return Optional.of(new RegionView() {
                @Override public UUID worldId() { return world; }
                @Override public String regionId() { return canonical; }
                @Override public String market() { return ID; }
                @Override public String kind() { return "owned"; }
                @Override public Optional<UUID> ownerUuid() { return owner; }
                @Override public boolean matches(String name, String id) {
                    return world.equals(view.worldId(name)) && canonical.equalsIgnoreCase(id);
                }
            });
        } catch (RuntimeException | LinkageError failure) {
            noteFailure(failure);
            return Optional.empty();
        }
    }

    @Override
    public Optional<OwnershipToken> ownerReadAt(Location at, UUID owner) {
        return regionAt(at).flatMap(ref -> ownerReadRegion(ref.worldId(), ref.regionId(), owner));
    }

    @Override
    public Optional<OwnershipToken> ownerReadRegion(UUID world, String regionId, UUID owner) {
        try {
            if (world == null || owner == null || owner.equals(ZERO)) return Optional.empty();
            return payable(world, regionId).payer().filter(p -> p.owner().equals(owner))
                    .map(p -> new Token(world, p.plot().regionId(), owner, p.slot(), p.guard()));
        } catch (RuntimeException | LinkageError failure) {
            noteFailure(failure);
            return Optional.empty();
        }
    }

    /** The one place that decides who pays, so findRegion and the owner reads can never disagree. */
    private Reading payable(UUID world, String regionId) {
        if (world == null || !validId(regionId) || !available()) return Reading.UNREADABLE;
        RegionCache.Lookup lookup = cache.find(world, regionId, true);
        if (lookup.status() != RegionCache.Status.FOUND || cache.isHeld(world, regionId)) return Reading.UNREADABLE;
        Optional<RealtyView.Guard> guard = view.worldGuard(world, regionId);
        if (guard.isEmpty()) return Reading.NOBODY;
        if (guard.get().identity() == null) return Reading.UNREADABLE;
        // A new WorldGuard object sends the slot back to the database first, so this read cannot be trusted yet.
        if (!cache.noteGuard(world, regionId, guard.get().identity())) return Reading.UNREADABLE;
        RealtyView.Plot plot = lookup.plot();
        Optional<UUID> holder = OwnerRule.holder(plot, view.now(), cache.margin());
        if (holder.isEmpty()) {
            // An unsold freehold is the easiest plot to take with a transfer, so note who is on its list.
            if (plot.kind() == RealtyView.Kind.FREEHOLD) cache.suspect(world, regionId, guard.get().owners());
            return Reading.NOBODY;
        }
        if (!OwnerRule.agrees(plot, guard.get())) {
            cache.disagree(world, regionId, guard.get().owners());
            return Reading.NOBODY;
        }
        Units units = unitsBelow(plot);
        if (units == Units.UNKNOWN) return Reading.UNREADABLE;
        if (units == Units.RENTED) return Reading.NOBODY;
        return Reading.of(new Payable(holder.get(), plot, lookup.identity(), guard.get().identity()));
    }

    /**
     * A plot is not paid while a unit inside it is rented or sold. A unit that cannot be read counts against paying.
     */
    private Units unitsBelow(RealtyView.Plot plot) {
        if (plot.kind() != RealtyView.Kind.FREEHOLD && plot.kind() != RealtyView.Kind.LEASEHOLD) return Units.CLEAR;
        boolean unknown = false;
        Set<String> visited = new HashSet<>();
        var pending = new ArrayDeque<String>();
        pending.add(plot.regionId());
        while (!pending.isEmpty()) {
            Optional<List<String>> children = cache.childrenOf(plot.worldId(), pending.poll());
            if (children.isEmpty()) return Units.UNKNOWN;
            for (String child : children.get()) {
                if (!visited.add(child.toLowerCase(Locale.ROOT))) continue;
                Units unit = unitState(plot.worldId(), child);
                if (unit == Units.RENTED) return Units.RENTED;
                if (unit == Units.UNKNOWN) unknown = true;
                pending.add(child);
            }
        }
        return unknown ? Units.UNKNOWN : Units.CLEAR;
    }

    private Units unitState(UUID world, String regionId) {
        // The cache never holds an id this long, so the unit cannot be read.
        if (regionId.length() > RegionCache.Tuning.MAX_ID) return Units.UNKNOWN;
        // Reading the unit as hot keeps it fresh with the freehold around it.
        RegionCache.Lookup lookup = cache.find(world, regionId, true);
        // No slot means no contract at the last load. A unit made and rented with no Realty event at all, such as by
        // a database write, shows up at the next good load.
        if (lookup.status() == RegionCache.Status.ABSENT) return Units.CLEAR;
        // A unit that is stale or not loaded may have been rented a moment ago.
        if (lookup.status() != RegionCache.Status.FOUND) return Units.UNKNOWN;
        RealtyView.Plot unit = lookup.plot();
        if (unit.kind() == RealtyView.Kind.BOTH) return Units.RENTED;
        // A sold freehold inside the plot is paid to its own holder, so this plot waits.
        if (unit.kind() == RealtyView.Kind.FREEHOLD) {
            return unit.holder() != RealtyView.Holder.NONE ? Units.RENTED : Units.CLEAR;
        }
        return OwnerRule.tenanted(unit, view.now(), cache.margin()) ? Units.RENTED : Units.CLEAR;
    }

    @Override
    public Presence observe(UUID world, String regionId) {
        try {
            if (world == null || !available()) return Presence.UNREADABLE;
            if (!validId(regionId)) return Presence.MISSING;
            RegionCache.Status status = cache.find(world, regionId, false).status();
            if (status == RegionCache.Status.ABSENT) return Presence.MISSING;
            if (status != RegionCache.Status.FOUND) return Presence.UNREADABLE;
            return view.worldGuard(world, regionId).isPresent() ? Presence.PRESENT : Presence.MISSING;
        } catch (RuntimeException | LinkageError failure) {
            noteFailure(failure);
            return Presence.UNREADABLE;
        }
    }

    private static boolean validId(String regionId) {
        return regionId != null && !regionId.isBlank() && regionId.length() <= RegionCache.Tuning.MAX_ID
                && !regionId.equalsIgnoreCase("__global__");
    }

    // Staff

    /** The held plots, one line each. */
    public List<String> held() {
        var lines = new ArrayList<String>();
        for (var key : cache.held()) {
            String world = view.worldName(key.world());
            lines.add((world != null ? world : key.world().toString()) + " " + key.region());
        }
        return lines;
    }

    /** Lets a held plot pay again. Returns false when the world is not loaded or the plot was not held. */
    public boolean release(String worldName, String regionId) {
        UUID world = view.worldId(worldName);
        return world != null && regionId != null && cache.release(world, regionId);
    }

    @Override
    public List<String> describe() {
        try {
            return List.of("realty: the title holder of a freehold or the tenant of a rented lease, one player UUID; "
                            + "guard none (payouts held by default)", statusLine());
        } catch (RuntimeException | LinkageError failure) {
            noteFailure(failure);
            return List.of("realty: unreadable", "Realty UNAVAILABLE; " + VERIFICATION);
        }
    }

    private String statusLine() {
        long age = cache.lastFullAgeMillis();
        String last = age == Long.MAX_VALUE ? "never" : age / 1000 + "s ago";
        String skew = cache.skewBound().map(bound -> bound.toSeconds() + "s").orElse("unknown until a rent is seen");
        return "Realty " + (available() ? "available" : "UNAVAILABLE (" + why() + ")")
                + "; plots cached " + cache.size() + " (hot " + cache.hotSize() + ", skipped long ids "
                + cache.skippedLongIds() + "), last full load " + last
                + "; clock skew bound " + skew + ", margin " + cache.margin().toSeconds() + "s"
                + "; held " + cache.held().size() + "; " + VERIFICATION;
    }

    private String why() {
        if (linkageLatched()) return "Realty or WorldGuard changed; restart required";
        if (!view.installed()) return view.problem();
        return "no recent full load";
    }
}

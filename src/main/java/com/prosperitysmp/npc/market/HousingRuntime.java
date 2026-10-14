package com.prosperitysmp.npc.market;

import com.prosperitysmp.npc.ProsperityNpcPlugin;
import com.prosperitysmp.npc.alert.StaffAlerts;
import com.prosperitysmp.npc.arm.OwnershipFence;
import com.prosperitysmp.npc.clock.EconomyClock;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.property.IncidentRollRepository;
import com.prosperitysmp.npc.property.OwnerAway;
import com.prosperitysmp.npc.property.TenantMoodRepository;
import com.prosperitysmp.npc.property.VoluntaryDepartureRepository;
import com.prosperitysmp.npc.render.PremiumWalk;
import com.prosperitysmp.npc.resident.ResidentProfile;
import com.prosperitysmp.npc.resident.ResidentRepository;
import com.prosperitysmp.npc.sim.DailyRoll;
import com.prosperitysmp.npc.sim.SimulationLogic;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

/** Bounded live host for the finite, evidence-gated matching/receipt engine. */
public final class HousingRuntime implements AutoCloseable {
    private final ProsperityNpcPlugin plugin;
    private final Database database;
    private final ExecutorService worker;
    private final ConcurrentLinkedQueue<Runnable> replies = new ConcurrentLinkedQueue<>();
    private final HousingDecisionRepository decisions;
    private final HousingCoordinator coordinator;
    private final EconomyClock days;
    private final BukkitTask ticker;
    private boolean busy, closed;
    private final boolean premiumEnabled;
    private final PremiumMoveBroadcast broadcast = new PremiumMoveBroadcast();
    /** Safety check, on by default; only read while residents.cast-file-enabled is on. */
    public static final String REQUIRE_SKIN_FOR_ROSTER = "residents.require-skin-for-roster";

    public HousingRuntime(ProsperityNpcPlugin plugin) {
        this.plugin = plugin;
        this.database = Objects.requireNonNull(plugin.database());
        Clock clock = Clock.systemUTC();
        ZoneId zone = ZoneId.of(plugin.getConfig().getString("clock.timezone", "America/Chicago"));
        int reset = plugin.getConfig().getInt("clock.daily-reset-hour", 4);
        days = new EconomyClock(clock, zone, reset);
        int lockDays = Math.max(0, Math.min(30, plugin.getConfig().getInt("housing.evicted-slot-lock-days", 0)));
        decisions = new HousingDecisionRepository(plugin.database(), clock, zone, reset, lockDays);
        premiumEnabled = Boolean.TRUE.equals(plugin.getConfig().get("housing.premium-enabled"));
        coordinator = new HousingCoordinator(new HousingMoveRepository(plugin.database(), clock, zone, reset, premiumEnabled, lockDays));
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1), r -> {
            Thread t = new Thread(r, "npc-housing");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
        plugin.trackDatabaseWorker(worker);
        ticker = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            Runnable reply = replies.poll();
            if (reply != null) reply.run();
        }, 1, 1);
    }

    private boolean ready(BooleanSupplier authorized) {
        return !closed && plugin.isEnabled() && plugin.ownershipFence() != null
                && plugin.killSwitches().isSystemEnabled() && plugin.killSwitches().isMovesEnabled()
                && authorized.getAsBoolean();
    }

    /** Main thread only: staff alert for a coordinated daily phase that started and then failed. */
    private void phaseFailed(int phase, String text) {
        try { plugin.alerts().raise(StaffAlerts.Kind.DAILY_PHASE_FAILED, "phase-" + phase, text); }
        catch (RuntimeException ignored) { /* alerts never block recovery handling */ }
    }

    /**
     * Registers the active cast for housing. This is explicit finite cast authoring, not a repeating
     * population faucet. While residents.cast-file-enabled is on and
     * residents.require-skin-for-roster is on, a resident with no verified everyday skin is skipped and counted, so
     * the town never gets an invisible paying tenant. With the cast file off the check is never read.
     */
    public boolean registerCast(HousingPolicy policy, com.prosperitysmp.npc.resident.ResidentCast cast,
                                Predicate<ResidentProfile> hasEverydaySkin,
                                BooleanSupplier authorized, Consumer<String> reply) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Main thread required");
        if (busy || !ready(authorized)) return false;
        Objects.requireNonNull(cast);
        Objects.requireNonNull(hasEverydaySkin);
        LocalDate available = days.today();
        boolean requireSkin = skinRequired(cast,
                () -> plugin.getConfig().getBoolean(REQUIRE_SKIN_FOR_ROSTER, true));
        busy = true;
        try {
            worker.execute(() -> {
                List<String> messages;
                try {
                    var result = rosterCast(database, policy, available, cast, requireSkin ? hasEverydaySkin : person -> true);
                    messages = result.messages();
                } catch (Exception failure) {
                    messages = List.of("Roster import stopped; earlier entries may be saved. "
                            + "Exact same policy can be retried; no tenants were moved.");
                }
                List<String> result = messages;
                replies.add(() -> {
                    busy = false;
                    result.forEach(reply);
                });
            });
            return true;
        } catch (RejectedExecutionException rejected) {
            busy = false;
            return false;
        }
    }

    /** The safety check applies only to a residents.yml cast; for the built-in three the flag is never read. */
    static boolean skinRequired(com.prosperitysmp.npc.resident.ResidentCast cast, BooleanSupplier requireSkinForRoster) {
        return cast.source() != com.prosperitysmp.npc.resident.ResidentCast.Source.BUILT_IN
                && requireSkinForRoster.getAsBoolean();
    }

    /** What one roster pass did. {@code skipped} counts residents held back for want of a skin. */
    record RosterResult(int added, int skipped) {
        List<String> messages() {
            String ready = "Housing roster ready: " + added + " residents added. Existing records preserved.";
            return skipped == 0 ? List.of(ready)
                    : List.of(ready, "Roster: " + skipped + (skipped == 1 ? " resident" : " residents")
                    + " skipped until they have a skin.");
        }
    }

    /**
     * Worker thread. For the built-in cast, a saved profile that differs stops the import.
     * A residents.yml cast keeps a differing saved profile as saved and still enrolls that UUID.
     */
    static RosterResult rosterCast(Database database, HousingPolicy policy, LocalDate available,
                                   com.prosperitysmp.npc.resident.ResidentCast cast,
                                   Predicate<ResidentProfile> include) throws Exception {
        var residents = new ResidentRepository(database);
        var registry = new HousingRegistry(database);
        boolean fromFile = cast.source() != com.prosperitysmp.npc.resident.ResidentCast.Source.BUILT_IN;
        int added = 0;
        int skipped = 0;
        for (var person : cast.profiles()) {
            if (!include.test(person)) {
                skipped++;
                continue;
            }
            try {
                residents.register(person);
            } catch (ResidentRepository.ProfileMismatchException kept) {
                if (!fromFile) throw kept;
            }
            var prior = registry.find(person.id());
            if (prior.isPresent()) {
                if (!prior.get().policy().equals(policy)) throw new IllegalStateException("Existing roster policy differs");
                continue; // Preserve original priority, cooldown, enabled state and identity.
            }
            if (registry.admit(person.id(), policy, available)) added++;
        }
        return new RosterResult(added, skipped);
    }

    /**
     * roster-one's version of the roster skin check: true only for a residents.yml cast member with no everyday skin
     * while residents.require-skin-for-roster is on. For the built-in three the flag is never read.
     */
    public static boolean heldForSkin(UUID residentId, com.prosperitysmp.npc.resident.ResidentCast cast,
                                      Predicate<ResidentProfile> hasEverydaySkin,
                                      BooleanSupplier requireSkinForRoster) {
        if (!skinRequired(cast, requireSkinForRoster)) return false;
        var member = cast.byId(residentId);
        return member.isPresent() && !hasEverydaySkin.test(member.get());
    }

    enum RosterOne { ADMITTED, UPDATED, HELD_FOR_SKIN }

    /** Staff reply when roster-one holds a resident back for want of a skin. */
    public static final String HELD_FOR_SKIN_REPLY = "Not added. This resident has no everyday skin yet.";

    /**
     * Worker thread. A resident held for want of a skin is never newly admitted. One already on the roster can
     * still have its policy changed, since that adds no tenant.
     */
    static RosterOne rosterOne(Database database, UUID residentId, HousingPolicy policy,
                               LocalDate available, boolean heldForSkin) throws Exception {
        var registry = new HousingRegistry(database);
        var prior = registry.find(residentId);
        if (prior.isEmpty()) {
            if (heldForSkin) return RosterOne.HELD_FOR_SKIN;
            registry.admit(residentId, policy, available);
            return RosterOne.ADMITTED;
        }
        registry.setVacantPolicy(residentId, prior.get().revision(), policy);
        return RosterOne.UPDATED;
    }

    /** A finite authored resident only; changing tier never vacates a tenant or grants money. */
    public boolean registerResident(UUID residentId, HousingPolicy policy, BooleanSupplier authorized, Consumer<String> reply) {
        return registerResident(residentId, policy, false, authorized, reply);
    }

    /** As above. {@code heldForSkin} comes from {@link #heldForSkin} on the main thread. */
    public boolean registerResident(UUID residentId, HousingPolicy policy, boolean heldForSkin,
                                    BooleanSupplier authorized, Consumer<String> reply) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Main thread required");
        if (busy || !ready(authorized) || policy.tier() == HousingPolicy.Tier.PREMIUM && !premiumEnabled) return false;
        LocalDate available = days.today();
        busy = true;
        try {
            worker.execute(() -> {
                String message;
                try {
                    if (rosterOne(database, residentId, policy, available, heldForSkin) == RosterOne.HELD_FOR_SKIN) {
                        message = HELD_FOR_SKIN_REPLY;
                    } else {
                        message = "Resident housing policy saved. Existing occupancy, cooldown and history were not reset.";
                        plugin.getLogger().info("Housing authoring resident=" + residentId + " tier=" + policy.tier());
                    }
                } catch (Exception failure) {
                    message = "Policy not changed: resident missing, occupied or concurrently changed. No resident was moved.";
                }
                String result = message;
                replies.add(() -> {
                    busy = false;
                    reply.accept(result);
                });
            });
            return true;
        } catch (RejectedExecutionException rejected) {
            busy = false;
            return false;
        }
    }

    /** Each incident decision commits once per property/day under the captured ownership lease. */
    public boolean requestIncidents(BooleanSupplier authorized, Consumer<String> reply) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Main thread required");
        if (closed || busy || !plugin.isEnabled() || plugin.ownershipFence() == null
                || !plugin.killSwitches().isSystemEnabled() || !plugin.killSwitches().isIncidentsEnabled()
                || !authorized.getAsBoolean()) return false;
        long seed = plugin.getConfig().getLong("state.sim-seed", 0);
        if (seed == 0) return false;
        var enrolled = plugin.propertyService().getEnrolled();
        // Saved owner-away decisions only; none saved yet today means the step waits (no reclassifying).
        var held = plugin.ownerAway().held(enrolled);
        if (held.isEmpty()) return false;
        var properties = dailyTargets(enrolled, held.get());
        if (properties.size() > 512) return false;
        var rolls = new IncidentRollRepository(database,
                new SimulationLogic(new DailyRoll(seed)), Clock.systemUTC(),
                ZoneId.of(plugin.getConfig().getString("clock.timezone", "America/Chicago")),
                plugin.getConfig().getInt("clock.daily-reset-hour", 4));
        var leases = new ArrayList<OwnershipFence.Lease>();
        LocalDate day = days.today();
        var service = plugin.propertyService();
        try {
            for (var p : properties) {
                var owner = plugin.armAdapter().ownerReadRegion(p.worldUuid(), p.regionId(), p.ownerUuid()).orElseThrow();
                var lease = plugin.ownershipFence().acquire(owner);
                leases.add(lease);
                lease.validate(owner);
            }
            busy = true;
            worker.execute(() -> {
                try {
                    int count = 0;
                    for (var p : properties) {
                        var result = rolls.roll(p.propertyId(), p.ownerUuid(), p.ownershipEpoch(), day);
                        if (result.newlyRecorded() && result.outcome() == IncidentRollRepository.Outcome.INCIDENT) count++;
                    }
                    int created = count;
                    // Rolls can lower condition in SQL: re-read every rolled home so the cache matches before release.
                    List<com.prosperitysmp.npc.property.Property> rows = new ArrayList<>();
                    try { for (var p : properties) service.readRow(p.propertyId()).ifPresent(rows::add); }
                    catch (Exception unreadable) { rows = null; }
                    var reread = rows;
                    replies.add(()->{
                        try { service.installLeased(properties,reread,"Daily property checks"); }
                        catch (RuntimeException cacheFailure) {
                            plugin.getLogger().warning(
                                    "Daily property checks could not refresh cached homes; the next ownership check does.");
                        }
                        for(var lease:leases)lease.releaseReconciled();
                        busy=false;
                        reply.accept("Daily property checks complete: " + created
                                + " new service incidents. Existing daily decisions preserved.");
                    });
                } catch (Exception failure) {
                    replies.add(()->{
                        plugin.getLogger().severe("Incident batch requires recovery for " + day + "; leases retained.");
                        phaseFailed(0, "Daily property checks were interrupted; staff recovery required.");
                        reply.accept("Property checks interrupted. Earlier decisions may be committed; staff recovery required.");
                    });
                }
            });
            return true;
        } catch (RuntimeException beforeDispatch) {
            for (var lease : leases) lease.releaseReconciled();
            busy = false;
            return false;
        }
    }

    /**
     * Incident rolls and voluntary departures: enabled, owned homes in id order. Owner-away homes are left out,
     * so they get no incident, no condition loss and no departure roll that day.
     */
    public static List<com.prosperitysmp.npc.property.Property> dailyTargets(
            Collection<com.prosperitysmp.npc.property.Property> enrolled, Set<UUID> away) {
        return OwnerAway.present(enrolled.stream().filter(p -> p.enabled() && p.ownerUuid() != null)
                .sorted(Comparator.comparing(p -> p.propertyId().toString())).toList(), away);
    }

    public boolean moodsEnabled() { return Boolean.TRUE.equals(plugin.getConfig().get("housing.moods-enabled")); }

    /** Daily resident mood step. Display data only: no money, occupancy or ownership lease involved. */
    public boolean requestMoods(BooleanSupplier authorized, Consumer<String> reply) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Main thread required");
        if (closed || busy || !plugin.isEnabled() || !plugin.killSwitches().isSystemEnabled()
                || !moodsEnabled() || !authorized.getAsBoolean()) return false;
        LocalDate day = days.today();
        // Owner-away homes keep their residents' moods unchanged for the day (read on main, passed to the worker).
        var held = plugin.ownerAway().held(plugin.propertyService().getEnrolled());
        if (held.isEmpty()) return false; // Today's owner-away decisions are not saved yet.
        var paused = held.get();
        busy = true;
        try {
            worker.execute(() -> {
                String message;
                boolean failed = false;
                try {
                    int updated = new TenantMoodRepository(database)
                            .updateDay(day, TenantMoodRepository.MAX_BATCH, paused);
                    message = "Resident moods updated: " + updated + ". Earlier updates for today were kept.";
                } catch (Exception failure) {
                    message = "Resident moods interrupted. Nothing else was changed; the step can run again.";
                    failed = true;
                }
                String result = message;
                boolean moodsFailed = failed;
                replies.add(() -> {
                    busy = false;
                    if (moodsFailed) phaseFailed(1, "Resident moods step was interrupted; it can run again.");
                    reply.accept(result);
                });
            });
            return true;
        } catch (RejectedExecutionException rejected) {
            busy = false;
            return false;
        }
    }

    public boolean voluntaryDeparturesEnabled() {
        return Boolean.TRUE.equals(plugin.getConfig().get("housing.voluntary-departures-enabled"));
    }

    /**
     * Daily voluntary departures from neglected homes. No money moves. Each home is leased on main; a busy
     * or unreadable home is skipped for the day, never fatal. A replay of the same day changes nothing.
     */
    public boolean requestDepartures(BooleanSupplier authorized, Consumer<String> reply) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Main thread required");
        if (busy || !ready(authorized) || !voluntaryDeparturesEnabled()) return false;
        var config = plugin.getConfig();
        long seed = config.getLong("state.sim-seed", 0);
        if (seed == 0) return false;
        var policy = new VoluntaryDepartureRepository.Policy(moodsEnabled(),
                Math.max(0, Math.min(VoluntaryDepartureRepository.MAX_SERVER_DAILY,
                        config.getInt("housing.voluntary-departure-server-daily-limit", 3))),
                Math.max(1, Math.min(30, config.getInt("housing.cooldown-days", 7))),
                Math.max(0, Math.min(100, config.getInt("housing.voluntary-departure-satisfaction-below", 40))),
                Math.max(0, Math.min(100, config.getInt("housing.voluntary-departure-condition-below", 50))));
        var enrolled = plugin.propertyService().getEnrolled();
        var awayHeld = plugin.ownerAway().held(enrolled);
        if (awayHeld.isEmpty()) return false; // Today's owner-away decisions are not saved yet.
        var properties = dailyTargets(enrolled, awayHeld.get());
        if (properties.size() > VoluntaryDepartureRepository.MAX_TARGETS) return false;
        LocalDate day = days.today();
        var leases = new ArrayList<OwnershipFence.Lease>();
        var targets = new ArrayList<VoluntaryDepartureRepository.Target>();
        for (var p : properties) {
            OwnershipFence.Lease lease = null;
            try {
                var owner = plugin.armAdapter().ownerReadRegion(p.worldUuid(), p.regionId(), p.ownerUuid()).orElse(null);
                if (owner == null) continue;
                lease = plugin.ownershipFence().acquire(owner);
                lease.validate(owner);
                targets.add(new VoluntaryDepartureRepository.Target(
                        p.propertyId(), p.ownerUuid(), p.ownershipEpoch()));
                leases.add(lease);
            } catch (RuntimeException busyOrUnreadable) {
                if (lease != null && !leases.contains(lease)) lease.releaseReconciled();
            }
        }
        int skipped = properties.size() - targets.size();
        var departures = new VoluntaryDepartureRepository(database,
                new SimulationLogic(new DailyRoll(seed)), Clock.systemUTC());
        busy = true;
        try {
            worker.execute(() -> {
                VoluntaryDepartureRepository.Result result = null;
                try {
                    result = departures.runDay(day, targets, policy);
                } catch (Exception failure) {
                    result = null;
                }
                var done = result;
                replies.add(() -> {
                    for (var lease : leases) lease.releaseReconciled();
                    busy = false;
                    if (done == null) {
                        plugin.getLogger().warning("Voluntary departures interrupted for " + day + "; committed departures kept.");
                        phaseFailed(2, "Voluntary departures step was interrupted; it can run again.");
                        reply.accept("Voluntary departures interrupted. Committed departures were kept; the step can run again.");
                        return;
                    }
                    for (var d : done.departed()) plugin.getLogger().info("Voluntary departure day=" + day + " resident=" + d.residentId()
                            + " property=" + d.propertyId() + " score=" + d.score() + " chanceBp=" + d.chanceBp() + " cooldownUntil="
                            + d.cooldownUntil());
                    for (var failed : done.failed()) plugin.getLogger().warning("Voluntary departure skipped after an error: property="
                            + failed + " day=" + day);
                    int held = skipped + done.failed().size();
                    reply.accept("Voluntary departures: " + done.departed().size() + " residents left neglected homes."
                            + (held > 0 ? " " + held + " homes were busy or unreadable and were left unchanged." : ""));
                });
            });
            return true;
        } catch (RejectedExecutionException rejected) {
            for (var lease : leases) lease.releaseReconciled();
            busy = false;
            return false;
        }
    }

    /** Authorization is checked on main before dispatch AND after the planning read. */
    public boolean request(BooleanSupplier authorized, Consumer<String> reply) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Main thread required");
        if (busy || !ready(authorized)) return false;
        var policy = new HousingMoveRequest.Policy(plugin.getConfig().getInt("housing.server-daily-limit", 3),
                plugin.getConfig().getInt("housing.market-daily-limit", 1),
                plugin.getConfig().getInt("housing.property-daily-limit", 1),
                plugin.getConfig().getInt("housing.cooldown-days", 7));
        LocalDate day = days.today();
        var options = new HousingDecisionRepository.Options(Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(Duration.ofDays(29)),
                7, policy, HousingDecisionPlan.ColdStartPolicy.PROVISIONAL_50_V1, 512, 4096, Duration.ofSeconds(20),
                premiumEnabled && Boolean.TRUE.equals(plugin.getConfig().get("housing.premium-enabled")),
                Boolean.TRUE.equals(plugin.getConfig().get("housing.advertising-rank-enabled")),
                // town.partnerships rank perk, read live; empty unless partnerships and the rank bonus are on.
                plugin.partnerRankCaps());
        busy = true;
        try {
            worker.execute(() -> {
                try {
                    HousingDecisionPlan plan = decisions.read(day, options);
                    replies.add(() -> apply(plan, authorized, reply));
                } catch (Exception failure) {
                    replies.add(() -> {
                        busy = false;
                        phaseFailed(3, "Resident matching could not read its plan; no admissions were dispatched.");
                        reply.accept("Matching unavailable; no admissions dispatched.");
                    });
                }
            });
            return true;
        } catch (RejectedExecutionException rejected) {
            busy = false;
            return false;
        }
    }

    private void apply(HousingDecisionPlan plan, BooleanSupplier authorized, Consumer<String> reply) {
        if (!ready(authorized) || !plan.snapshot().day().equals(days.today())) {
            busy = false;
            reply.accept("Matching cancelled before admission; authority or day changed.");
            return;
        }
        if (plan.requests().stream().anyMatch(r -> r.tier() == HousingPolicy.Tier.PREMIUM)
                && !Boolean.TRUE.equals(plugin.getConfig().get("housing.premium-enabled"))) {
            busy = false;
            reply.accept("Premium matching cancelled before admission; policy changed.");
            return;
        }
        if (plan.requests().isEmpty()) {
            busy = false;
            reply.accept("No eligible resident moves today.");
            return;
        }
        var leases = new ArrayList<OwnershipFence.Lease>();
        try {
            Set<UUID> targets = new HashSet<>();
            for (var request : plan.requests()) {
                targets.add(request.propertyId());
                if (request.origin() != null) targets.add(request.origin().propertyId());
            }
            for (var home : plan.snapshot().homes()) {
                if (!targets.remove(home.propertyId())) continue;
                var owner = plugin.armAdapter().ownerReadRegion(home.worldId(), home.regionId(), home.ownerId())
                        .orElseThrow(() -> new IllegalStateException("Owner unavailable"));
                var lease = plugin.ownershipFence().acquire(owner);
                leases.add(lease);
                lease.validate(owner);
            }
            if (!targets.isEmpty()) throw new IllegalStateException("Admission target missing");
            boolean announce = Boolean.TRUE.equals(plugin.getConfig().get("housing.premium-broadcast-enabled"));
            worker.execute(() -> {
                try {
                    var results = coordinator.execute(plan);
                    List<String[]> lines;
                    try { lines = announce ? PremiumMoveBroadcast.relocations(database, results) : List.of(); }
                    catch (Exception | LinkageError cosmetic) { lines = List.of(); }
                    var toSend = lines;
                    var walks = PremiumWalk.moves(results);
                    replies.add(() -> {
                        try { broadcastRelocations(toSend); } catch (RuntimeException cosmetic) { /* never block lease release */ }
                        try { if (plugin.renderManager() != null) plugin.renderManager().premiumRelocated(walks); }
                        catch (RuntimeException cosmetic) { /* walking is cosmetic; respawn is the fallback */ }
                        for (var lease : leases) lease.releaseReconciled();
                        busy = false;
                        long count = results.stream().filter(HousingMoveRepository.Result::newlyApplied).count();
                        reply.accept("Resident matching complete: " + count + " moved in.");
                    });
                } catch (Exception failure) {
                    String id = failure instanceof HousingCoordinator.Interrupted interrupted
                            ? interrupted.unresolved().requestId().toString() : "unknown";
                    replies.add(() -> {
                        plugin.getLogger().severe("Housing recovery required; request " + id + ". Property leases retained.");
                        phaseFailed(3, "Resident matching needs staff recovery. Reference " + id + ".");
                        reply.accept("Matching requires recovery; do not retry. Staff reference: " + id);
                    });
                }
            });
        } catch (RuntimeException beforeDispatch) {
            for (var lease : leases) lease.releaseReconciled();
            busy = false;
            reply.accept("Matching held before admission; a property is busy or unavailable.");
        }
    }

    private void broadcastRelocations(List<String[]> moves) {
        for (var m : moves) {
            var line = broadcast.offer(m[0], m[1], System.currentTimeMillis());
            if (line.isPresent()) Bukkit.broadcastMessage(line.get());
            else plugin.getLogger().info("Premium relocation not broadcast (rate limited): " + m[0] + " -> " + m[1]);
        }
    }

    @Override public void close() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Main thread required");
        closed = true;
        ticker.cancel();
        worker.shutdown();
        // Shutdown orchestration drains registered workers; unresolved leases are not timed out.
    }
}

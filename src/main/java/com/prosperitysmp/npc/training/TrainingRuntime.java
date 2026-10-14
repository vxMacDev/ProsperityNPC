package com.prosperitysmp.npc.training;

import com.prosperitysmp.npc.clock.EconomyClock;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.ProsperityNpcPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Main-thread authorization/events/cache; one bounded tracked worker for SQL. No per-event SQL.
 * Parent owns construction, nearby trainer authorization, migration registration and closeAsync drainage
 * before Database.close(). Missing town.training-enabled is always disabled (DEV opt-in only).
 */
public final class TrainingRuntime implements AutoCloseable, Listener {
    public static final int MAX_CACHED_PLAYERS = 256;
    public enum Status { UNKNOWN, LOADING, NONE, ACTIVE, EXPIRED, INELIGIBLE, DISABLED, UNAVAILABLE, UNCERTAIN }
    public record View(Status status, Optional<TrainingGrant> grant) {
        public View { Objects.requireNonNull(status); Objects.requireNonNull(grant); }
    }
    private record Cached(Player login, Optional<TrainingGrant> grant, Status status) { }
    private record Pending(Player login, UUID token, UUID trainer, String job,
                           Consumer<TrainingRepository.Result> requestReply, Consumer<View> viewReply) { }
    private record Delivery(UUID player, Pending pending, TrainingRepository.Result result,
                            Optional<TrainingGrant> grant, boolean uncertain) { }

    private final Plugin plugin;
    private final EconomyClock economyClock;
    private final Clock clock;
    private final BooleanSupplier gates;
    private final BiPredicate<Player, UUID> nearbyTrainer;
    private final TrainingRepository repository;
    private final TrainingJobsAccess jobs;
    private final Map<UUID, Cached> cache = new HashMap<>();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final ArrayBlockingQueue<Delivery> deliveries = new ArrayBlockingQueue<>(32);
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private final ThreadPoolExecutor worker;
    private final BukkitTask ticker;
    private volatile boolean closed;
    private boolean failed;

    /** gates must check current SYSTEM/ECONOMY switches; nearbyTrainer verifies trusted real encounter/session. */
    public TrainingRuntime(Plugin plugin, Database database, EconomyClock economyClock,
                           BooleanSupplier gates, BiPredicate<Player, UUID> nearbyTrainer) {
        this(plugin, new TrainingRepository(database), economyClock, gates, nearbyTrainer, Clock.systemUTC(), link(plugin));
    }

    TrainingRuntime(Plugin plugin, TrainingRepository repository, EconomyClock economyClock,
                    BooleanSupplier gates, BiPredicate<Player, UUID> nearbyTrainer, Clock clock, TrainingJobsAccess jobs) {
        requireMain();
        this.plugin = Objects.requireNonNull(plugin);
        this.repository = Objects.requireNonNull(repository);
        this.economyClock = Objects.requireNonNull(economyClock);
        this.clock = Objects.requireNonNull(clock);
        this.gates = Objects.requireNonNull(gates);
        this.nearbyTrainer = Objects.requireNonNull(nearbyTrainer);
        this.jobs = jobs;
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), r -> {
            Thread thread = new Thread(r, "npc-training-sql"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy()) {
            @Override protected void terminated() { drained.complete(null); }
        };
        BukkitTask scheduled = null;
        try {
            if (plugin instanceof ProsperityNpcPlugin owner) owner.trackDatabaseWorker(worker);
            var manager = plugin.getServer().getPluginManager();
            manager.registerEvents(this, plugin);
            if (jobs != null) {
                // NORMAL precedes HIGH income tax. PrePayment changes money only; ExpGain changes XP only.
                manager.registerEvent(jobs.money().type(), this, EventPriority.NORMAL,
                        (listener, event) -> apply(jobs.money(), event), plugin, true);
                manager.registerEvent(jobs.xp().type(), this, EventPriority.NORMAL,
                        (listener, event) -> apply(jobs.xp(), event), plugin, true);
            }
            scheduled = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1, 1);
            for (Player online : plugin.getServer().getOnlinePlayers()) {
                if (cache.size() + pending.size() >= MAX_CACHED_PLAYERS) break;
                view(online, ignored -> { });
            }
            ticker = scheduled;
        } catch (RuntimeException | Error failure) {
            closed = true;
            if (scheduled != null) {
                try { scheduled.cancel(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            }
            try { HandlerList.unregisterAll(this); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            finally { worker.shutdown(); }
            throw failure;
        }
    }

    private static TrainingJobsAccess link(Plugin plugin) {
        requireMain();
        Plugin installed = plugin.getServer().getPluginManager().getPlugin("Jobs");
        if (installed == null || !installed.isEnabled()) return null;
        try { return new TrainingJobsAdapter(installed); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { return null; }
    }

    /** Main thread only; callback on main while the same login/encounter remains authorized. */
    public void request(Player player, UUID trainer, String jobName, Consumer<TrainingRepository.Result> callback) {
        requireMain(); Objects.requireNonNull(player); Objects.requireNonNull(trainer); Objects.requireNonNull(callback);
        String job;
        try { job = TrainingGrant.canonicalJob(jobName); }
        catch (RuntimeException invalid) { callback.accept(TrainingRepository.Result.denied(TrainingRepository.Code.NOT_EMPLOYED)); return; }
        TrainingRepository.Code denied = admission(player, trainer, job);
        if (denied != null) { callback.accept(TrainingRepository.Result.denied(denied)); return; }
        UUID id = player.getUniqueId();
        if (!room(id)) { callback.accept(TrainingRepository.Result.denied(TrainingRepository.Code.BUSY)); return; }
        Pending ticket = new Pending(player, UUID.randomUUID(), trainer, job, callback, null);
        Instant at = clock.instant();
        submit(id, ticket, () -> {
            var result = repository.request(id, trainer, job, ticket.token(), at, economyClock.dayAt(at));
            return new Delivery(id, ticket, result, result.grant(), false);
        });
    }

    /** Explicit off-thread refresh/recovery; never changes the original session's expiry. */
    public void view(Player player, Consumer<View> callback) {
        requireMain(); Objects.requireNonNull(player); Objects.requireNonNull(callback);
        if (!baseAuthorized(player)) {
            callback.accept(new View(!available() ? Status.UNAVAILABLE : !enabled() ? Status.DISABLED : Status.INELIGIBLE,
                    Optional.empty())); return;
        }
        UUID id = player.getUniqueId();
        if (!room(id)) { callback.accept(new View(Status.LOADING, Optional.empty())); return; }
        Pending ticket = new Pending(player, UUID.randomUUID(), null, null, null, callback);
        submit(id, ticket, () -> new Delivery(id, ticket, null, repository.currentForPlayer(id), false));
    }

    /** Main-thread, player-scoped, SQL-free snapshot. UNKNOWN is not proof of no durable grant. */
    public View current(UUID player) {
        requireMain(); Objects.requireNonNull(player);
        if (!available()) return new View(Status.UNAVAILABLE, Optional.empty());
        if (!enabled()) return new View(Status.DISABLED, Optional.empty());
        if (pending.containsKey(player)) return new View(Status.LOADING, Optional.empty());
        Cached entry = cache.get(player);
        if (entry == null) return new View(Status.UNKNOWN, Optional.empty());
        if (entry.status() == Status.UNCERTAIN) return new View(Status.UNCERTAIN, Optional.empty());
        if (!baseAuthorized(entry.login())) return new View(Status.INELIGIBLE, entry.grant());
        if (entry.grant().isEmpty()) return new View(Status.NONE, Optional.empty());
        TrainingGrant grant = entry.grant().orElseThrow();
        Instant now = clock.instant();
        if (now.isBefore(grant.acceptedAt())) return new View(Status.INELIGIBLE, entry.grant());
        if (!now.isBefore(grant.expiresAt())) return new View(Status.EXPIRED, entry.grant());
        return new View(employed(player, grant.jobName()) ? Status.ACTIVE : Status.INELIGIBLE, entry.grant());
    }

    public boolean available() {
        requireMain();
        return !closed && !failed && jobs != null && jobs.plugin().isEnabled()
                && plugin.getServer().getPluginManager().getPlugin("Jobs") == jobs.plugin();
    }

    /** Current feature/master gates, independent of any one player's eligibility. */
    public boolean enabled() {
        requireMain();
        try {
            return available() && plugin.isEnabled() && plugin.getConfig().getBoolean("town.training-enabled", false)
                    && plugin.getConfig().getBoolean("switches.system-enabled", false)
                    && plugin.getConfig().getBoolean("switches.economy-enabled", false) && gates.getAsBoolean();
        } catch (RuntimeException failure) { return false; }
    }

    private boolean baseAuthorized(Player player) {
        return available() && enabled() && player.isOnline()
                && plugin.getServer().getPlayer(player.getUniqueId()) == player
                && player.hasPermission("prosperitynpc.use");
    }

    private TrainingRepository.Code admission(Player player, UUID trainer, String job) {
        if (!available()) return TrainingRepository.Code.UNAVAILABLE;
        if (!enabled()) return TrainingRepository.Code.DISABLED;
        if (!baseAuthorized(player)) return TrainingRepository.Code.UNAUTHORIZED;
        try { if (!nearbyTrainer.test(player, trainer)) return TrainingRepository.Code.UNAUTHORIZED; }
        catch (RuntimeException failure) { return TrainingRepository.Code.UNAUTHORIZED; }
        return employed(player.getUniqueId(), job) ? null : TrainingRepository.Code.NOT_EMPLOYED;
    }

    /** Main-thread offer inspection; Jobs availability is required, training enablement is not. */
    public boolean employed(UUID id, String job) {
        requireMain(); Objects.requireNonNull(id);
        try { TrainingGrant.canonicalJob(job); }
        catch (RuntimeException invalid) { return false; }
        if (!available()) return false;
        try { return jobs.employed(id, job).isPresent(); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { failed = true; return false; }
    }

    public boolean isEmployed(UUID id, String job) { return employed(id, job); }

    private boolean room(UUID id) {
        return !pending.containsKey(id) && (cache.containsKey(id)
                || cache.size() + pending.size() < MAX_CACHED_PLAYERS);
    }

    @FunctionalInterface private interface Work { Delivery run() throws Exception; }

    private void submit(UUID id, Pending ticket, Work work) {
        pending.put(id, ticket);
        cache.remove(id); // No benefit while authorization/read outcome is unresolved.
        try {
            worker.execute(() -> {
                Delivery delivery;
                try { delivery = work.run(); }
                catch (Exception failure) {
                    delivery = new Delivery(id, ticket, TrainingRepository.Result.denied(TrainingRepository.Code.UNCERTAIN),
                            Optional.empty(), true);
                }
                if (!closed && !deliveries.offer(delivery)) {
                    // Impossible under queue/pending bounds; hold rather than publish an unacknowledged grant.
                    closed = true; worker.shutdown();
                }
            });
        } catch (RejectedExecutionException busy) {
            pending.remove(id, ticket);
            if (ticket.requestReply() != null) ticket.requestReply().accept(TrainingRepository.Result.denied(TrainingRepository.Code.BUSY));
            if (ticket.viewReply() != null) ticket.viewReply().accept(new View(Status.UNKNOWN, Optional.empty()));
        }
    }

    private void tick() {
        requireMain();
        if (closed) return;
        for (int i = 0; i < 32; i++) {
            Delivery delivery = deliveries.poll();
            if (delivery == null) break;
            Pending ticket = delivery.pending(); UUID id = delivery.player();
            if (!pending.remove(id, ticket) || !ticket.login().isOnline()
                    || plugin.getServer().getPlayer(id) != ticket.login()) continue;
            TrainingRepository.Code denied = ticket.trainer() == null ? null
                    : admission(ticket.login(), ticket.trainer(), ticket.job());
            if (baseAuthorized(ticket.login()) && denied == null) {
                cache.put(id, new Cached(ticket.login(), delivery.grant(), delivery.uncertain() ? Status.UNCERTAIN : Status.NONE));
            }
            try {
                if (ticket.requestReply() != null) ticket.requestReply().accept(denied == null
                        ? delivery.result() : TrainingRepository.Result.denied(denied));
                if (ticket.viewReply() != null) ticket.viewReply().accept(current(id));
            } catch (RuntimeException ignored) { /* UI callback cannot interrupt worker drainage. */ }
        }
        cache.entrySet().removeIf(entry -> !entry.getValue().login().isOnline()
                || plugin.getServer().getPlayer(entry.getKey()) != entry.getValue().login());
    }

    private void apply(TrainingJobsAdapter.EventBinding binding, org.bukkit.event.Event event) {
        if (!Bukkit.isPrimaryThread() || event.isAsynchronous() || !available() || !enabled()) return;
        try {
            binding.apply(event, clock.instant(), id -> {
                Cached entry = cache.get(id);
                return entry == null || entry.status() == Status.UNCERTAIN || pending.containsKey(id)
                        ? Optional.empty() : entry.grant();
            }, (id, job) -> {
                Cached entry = cache.get(id);
                return entry != null && baseAuthorized(entry.login()) && employed(id, job);
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { failed = true; cache.clear(); }
    }

    @EventHandler public void joined(PlayerJoinEvent event) { view(event.getPlayer(), ignored -> { }); }
    @EventHandler public void quit(PlayerQuitEvent event) {
        requireMain(); UUID id = event.getPlayer().getUniqueId(); cache.remove(id); pending.remove(id);
    }

    /** Revoke first; no main-thread wait. Parent MUST await this completion before closing its Database. */
    public CompletionStage<Void> closeAsync() {
        requireMain();
        closed = true; ticker.cancel(); HandlerList.unregisterAll(this);
        cache.clear(); pending.clear(); deliveries.clear(); worker.shutdown();
        return drained.minimalCompletionStage();
    }

    @Override public void close() { closeAsync(); }

    private static void requireMain() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("training runtime requires main thread");
    }
}

package com.prosperitysmp.npc.training;

import org.bukkit.OfflinePlayer;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Function;

/** Version-gated reflection of the installed Jobs APIs, never a global boost or provider payment. */
final class TrainingJobsAdapter implements TrainingJobsAccess {
    final Plugin jobs;
    final EventBinding money;
    final EventBinding xp;
    private final Method getJob;
    private final Method getManager;
    private final Method getPlayer;
    private final Method playerId;
    private final Method progression;
    private final Method progressionJob;
    private final Method jobName;

    TrainingJobsAdapter(Plugin jobs) throws ReflectiveOperationException {
        this.jobs = Objects.requireNonNull(jobs);
        ClassLoader loader = jobs.getClass().getClassLoader();
        Class<?> api = Class.forName("com.gamingmesh.jobs.Jobs", false, loader);
        Class<?> job = Class.forName("com.gamingmesh.jobs.container.Job", false, loader);
        Class<?> manager = Class.forName("com.gamingmesh.jobs.PlayerManager", false, loader);
        Class<?> player = Class.forName("com.gamingmesh.jobs.container.JobsPlayer", false, loader);
        Class<?> progress = Class.forName("com.gamingmesh.jobs.container.JobProgression", false, loader);
        if (!api.isInstance(jobs)) throw new NoSuchMethodException("Jobs plugin identity mismatch");
        getJob = signature(api, "getJob", job, String.class);
        getManager = signature(api, "getPlayerManager", manager);
        if (!Modifier.isStatic(getJob.getModifiers()) || !Modifier.isStatic(getManager.getModifiers())) {
            throw new NoSuchMethodException("Jobs lookup must be static");
        }
        getPlayer = signature(manager, "getJobsPlayer", player, UUID.class);
        playerId = signature(player, "getUniqueId", UUID.class);
        progression = signature(player, "getJobProgression", progress, job);
        progressionJob = signature(progress, "getJob", job);
        jobName = signature(job, "getName", String.class);
        money = EventBinding.link(Class.forName("com.gamingmesh.jobs.api.JobsPrePaymentEvent", false, loader), job, false);
        xp = EventBinding.link(Class.forName("com.gamingmesh.jobs.api.JobsExpGainEvent", false, loader), job, true);
    }

    @Override public Plugin plugin() { return jobs; }
    @Override public EventBinding money() { return money; }
    @Override public EventBinding xp() { return xp; }

    @Override public Optional<String> employed(UUID id, String requested) throws ReflectiveOperationException {
        String canonical = TrainingGrant.canonicalJob(requested);
        Object job = getJob.invoke(null, canonical);
        if (job == null || !canonical.equals(TrainingGrant.canonicalJob((String) jobName.invoke(job)))) {
            return Optional.empty();
        }
        Object manager = getManager.invoke(null);
        if (manager == null) return Optional.empty();
        Object player = getPlayer.invoke(manager, id);
        if (player == null || !id.equals(playerId.invoke(player))) return Optional.empty();
        Object progress = progression.invoke(player, job);
        if (progress == null) return Optional.empty();
        Object actualJob = progressionJob.invoke(progress);
        return actualJob != null && canonical.equals(TrainingGrant.canonicalJob((String) jobName.invoke(actualJob)))
                ? Optional.of(canonical) : Optional.empty();
    }

    private static Method signature(Class<?> type, String name, Class<?> result, Class<?>... args)
            throws NoSuchMethodException {
        Method method = type.getMethod(name, args);
        if (method.getReturnType() != result) throw new NoSuchMethodException("Jobs signature mismatch: " + name);
        return method;
    }

    /** Actual event method binding, also exercised with synthetic Bukkit events (not a live Jobs claim). */
    record EventBinding(Class<? extends Event> type, Method player, Method job, Method name,
                        Method cancelled, Method amount, Method setAmount, boolean xp) {
        static EventBinding link(Class<?> event, Class<?> jobType, boolean xp) throws NoSuchMethodException {
            if (!Event.class.isAssignableFrom(event) || !Cancellable.class.isAssignableFrom(event)) {
                throw new NoSuchMethodException("Jobs event contract mismatch");
            }
            return new EventBinding(event.asSubclass(Event.class), signature(event, "getPlayer", OfflinePlayer.class),
                    signature(event, "getJob", jobType), signature(jobType, "getName", String.class),
                    signature(event, "isCancelled", boolean.class),
                    signature(event, xp ? "getExp" : "getAmount", double.class),
                    signature(event, xp ? "setExp" : "setAmount", void.class, double.class), xp);
        }

        boolean apply(Event event, Instant now, Function<UUID, Optional<TrainingGrant>> cache,
                      BiPredicate<UUID, String> eligible) throws ReflectiveOperationException {
            if (!type.isInstance(event) || event.isAsynchronous() || (boolean) cancelled.invoke(event)) return false;
            Object account = player.invoke(event);
            Object eventJob = job.invoke(event);
            if (!(account instanceof OfflinePlayer offline) || eventJob == null) return false;
            UUID id = offline.getUniqueId();
            String actual;
            try { actual = TrainingGrant.canonicalJob((String) name.invoke(eventJob)); }
            catch (IllegalArgumentException unsupported) { return false; }
            Optional<TrainingGrant> found = cache.apply(id);
            if (found.isEmpty()) return false;
            TrainingGrant grant = found.orElseThrow();
            if (!grant.playerId().equals(id) || !grant.jobName().equals(actual) || !grant.activeAt(now)
                    || !eligible.test(id, actual)) return false;
            double before = (double) amount.invoke(event);
            int basisPoints = xp ? grant.xpBasisPoints() : grant.moneyBasisPoints();
            double after = before * (1.0 + basisPoints / 10_000.0);
            if (basisPoints == 0 || before <= 0 || !Double.isFinite(before) || !Double.isFinite(after)
                    || (boolean) cancelled.invoke(event)) return false;
            setAmount.invoke(event, after);
            return true;
        }
    }
}

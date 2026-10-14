package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.ProsperityNpcPlugin;
import com.prosperitysmp.npc.clock.EconomyClock;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.economy.ReserveAccount;
import com.prosperitysmp.npc.economy.UtilityRepository;
import com.prosperitysmp.npc.formulas.PropertyFormulas;
import com.prosperitysmp.npc.util.Money;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/** Explicit recurring consent, bounded discovery and the existing journaled payment path. */
public final class AutoCareRuntime {
    record Authority(AutoCareRepository.Consent consent, long generation) { }
    /** Main-thread consent cache. A newer intake invalidates both callbacks and captured debit authority. */
    static final class ConsentFence {
        private record Entry(long generation, AutoCareRepository.Consent consent) { }
        private final Map<UUID, Entry> entries = new HashMap<>();
        long generation(UUID property) {
            var entry = entries.get(property);
            return entry == null ? 0 : entry.generation();
        }
        long revoke(UUID property) {
            long next = Math.incrementExact(generation(property));
            entries.put(property, new Entry(next, null));
            return next;
        }
        boolean acknowledge(UUID property, long generation, AutoCareRepository.Consent consent) {
            if (generation(property) != generation || consent == null || !property.equals(consent.propertyId())) return false;
            entries.put(property, new Entry(generation, consent));
            return true;
        }
        Authority capture(AutoCareRepository.Consent consent) {
            if (!consent.enabled()) return null;
            var entry = entries.get(consent.propertyId());
            if (entry == null) {
                entry = new Entry(0, consent);
                entries.put(consent.propertyId(), entry);
            }
            return consent.equals(entry.consent()) ? new Authority(consent, entry.generation()) : null;
        }
        boolean permits(Authority authority) {
            var entry = entries.get(authority.consent().propertyId());
            return entry != null && entry.generation() == authority.generation()
                    && authority.consent().enabled() && authority.consent().equals(entry.consent());
        }
        void clear() { entries.clear(); }
    }
    private record Candidate(AutoCareRepository.Consent consent, AutoCareRepository.Action action,
                             Long incidentId, long cents) { }
    private final ProsperityNpcPlugin plugin;
    private final Database database;
    private final AutoCareRepository repository;
    private final UtilityRepository utilities;
    private final OwnerDeskRepository desks;
    private final EconomyClock days;
    private final ThreadPoolExecutor worker;
    private final ArrayBlockingQueue<Runnable> completions = new ArrayBlockingQueue<>(16);
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private final ConsentFence consentFence = new ConsentFence();
    private final BukkitTask task;
    private boolean busy;
    private int pendingConfigurations;
    private volatile boolean closed;
    private UUID consentCursor, recoveryCursor;
    private long nextCycle;

    public AutoCareRuntime(ProsperityNpcPlugin plugin) {
        main();
        this.plugin = plugin;
        database = plugin.database();
        var clock = Clock.systemUTC();
        var zone = ZoneId.of(plugin.getConfig().getString("clock.timezone", "America/Chicago"));
        int reset = plugin.getConfig().getInt("clock.daily-reset-hour", 4);
        var reserve = ReserveAccount.fromConfig(plugin.getConfig());
        repository = new AutoCareRepository(plugin.database(), reserve, clock, zone, reset);
        utilities = new UtilityRepository(plugin.database(), reserve, clock, zone, reset);
        desks = new OwnerDeskRepository(plugin.database());
        days = new EconomyClock(clock, zone, reset);
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(4), r -> {
            var t = new Thread(r, "npc-auto-care");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy()) {
            @Override protected void terminated() { drained.complete(null); }
        };
        BukkitTask scheduled = null;
        try {
            plugin.trackDatabaseWorker(worker);
            scheduled = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                for (int i = 0; i < 8; i++) {
                    var r = completions.poll();
                    if (r == null) break;
                    r.run();
                }
                long now = System.nanoTime();
                if (!closed && !busy && pendingConfigurations == 0 && now >= nextCycle) {
                    nextCycle = now + TimeUnit.MINUTES.toNanos(2);
                    cycle();
                }
            }, 20, 20);
            task = scheduled;
        } catch (RuntimeException | Error failure) {
            closed = true;
            if (scheduled != null) scheduled.cancel();
            worker.shutdown();
            throw failure;
        }
    }

    /** OFF revokes in-memory dispatch authority before waiting for its durable acknowledgement. */
    public void configure(Player player, Property property, Boolean enabled, long ceiling) {
        main();
        if (closed || !owner(player, property) || ((busy || pendingConfigurations != 0) && !Boolean.FALSE.equals(enabled))) {
            tell(player, "Auto-care is busy or ownership changed. Nothing started.");
            return;
        }
        UUID actor = player.getUniqueId();
        long generation = enabled == null ? consentFence.generation(property.propertyId()) : consentFence.revoke(property.propertyId());
        pendingConfigurations++;
        submit(() -> {
            var consent = enabled == null
                    ? repository.current(property.propertyId(), actor, property.ownershipEpoch()).orElse(null)
                    : repository.configure(property.propertyId(), actor, property.ownershipEpoch(), enabled,
                        enabled ? ceiling : 0, enabled ? EnumSet.allOf(AutoCareRepository.Action.class) : Set.of());
            post(() -> {
                pendingConfigurations--;
                if (!owner(player, property) || consentFence.generation(property.propertyId()) != generation) return;
                if (enabled != null && !consentFence.acknowledge(property.propertyId(), generation, consent)) return;
                tell(player, consent == null || !consent.enabled() ? "Auto-care is OFF. No new recurring charges."
                        : "Auto-care ON: at most " + Money.format(consent.dailyCeilingCents())
                        + "/service day for utilities, one cash repair and maintenance below 80 condition. "
                        + "No upgrades, insurance or eviction; no automatic payment retries. /property autocare off stops new charges."
                        + (!configured() || !plugin.killSwitches().isSystemEnabled() || !plugin.killSwitches().isEconomyEnabled()
                            ? " Consent is saved, but recurring charges are currently paused by server controls." : ""));
            });
        }, () -> {
            pendingConfigurations--;
            if (owner(player, property) && consentFence.generation(property.propertyId()) == generation)
                tell(player, "Auto-care confirmation failed. New dispatch for this property stays revoked; try status before changing it.");
        });
    }

    private void cycle() {
        busy = true;
        boolean enabled = configured();
        var enrolled = List.copyOf(plugin.propertyService().getEnrolled());
        // Owner-away homes are not charged for the day: no utility bill, repair or maintenance. Decided on main.
        // Saved decisions only. None saved yet today: no new charge for anyone, old claims still reconcile below.
        var held = plugin.ownerAway().held(enrolled).orElseGet(() -> enrolled.stream()
                .map(Property::propertyId).collect(Collectors.toUnmodifiableSet()));
        var properties = careTargets(enrolled, held);
        // Reconcile old obligations without ever replaying a provider call or freeing an uncertain budget.
        submit(() -> {
            var old = repository.pending(recoveryCursor, 16);
            recoveryCursor = old.size() < 16 ? null : old.getLast().claimId();
            for (var claim : old) {
                var result = repository.reconcile(claim.claimId());
                if (result.state() == AutoCareRepository.State.RESERVED || result.state() == AutoCareRepository.State.BOUND)
                    repository.hold(result.claimId());
            }
            if (!enabled) {
                post(() -> busy = false);
                return;
            }
            var page = repository.enabled(consentCursor, 16);
            Candidate chosen = null;
            LocalDate day = days.today();
            for (var consent : page) {
                var property = properties.stream()
                        .filter(p -> p.propertyId().equals(consent.propertyId())).findFirst().orElse(null);
                if (property == null) continue;
                var data = desks.read(property.worldUuid(), property.regionId(), consent.ownerUuid(), day).orElse(null);
                if (data == null || data.property().ownershipEpoch() != consent.ownershipEpoch()) continue;
                chosen = candidate(consent, data, day);
                if (chosen != null) break;
            }
            Candidate found = chosen;
            consentCursor = scanCursor(page, found == null ? null : found.consent());
            post(() -> {
                Authority captured = found == null ? null : consentFence.capture(found.consent());
                // The ownership check is updating this home right now: skip this cycle rather than reserve the day's action.
                if (captured == null || !authority(captured) || checking(found.consent().propertyId())) { busy = false; return; }
                submit(() -> {
                    var c = found.consent();
                    var reservation = repository.reserve(c.propertyId(), c.ownerUuid(), c.ownershipEpoch(), day,
                            found.action(), found.incidentId(), found.cents());
                    post(() -> {
                        if (!reservation.newlyReserved()) { busy = false; return; }
                        admitWhenChecked(reservation.claim(), c, captured, 0);
                    });
                }, () -> busy = false);
            });
        }, () -> busy = false);
    }

    /** Seconds a reserved action waits for the ownership check to finish updating its home before it is admitted. */
    static final int CHECK_WAIT_SECONDS = 15;

    private boolean checking(UUID property) {
        var service = plugin.propertyService();
        return service != null && service.reconcileWriting(property);
    }

    /**
     * Main thread. A reserved action is admitted once the ownership check is not updating its home, waiting up to
     * CHECK_WAIT_SECONDS, so a momentary check does not hold the day's action. If the check is still running after
     * that, or the action is refused for any other reason, the claim is held for the day.
     */
    private void admitWhenChecked(AutoCareRepository.Claim claim, AutoCareRepository.Consent c, Authority captured, int waited) {
        if (closed) {
            busy = false;
            return;
        }
        if (checking(claim.propertyId()) && waited < CHECK_WAIT_SECONDS) {
            try { Bukkit.getScheduler().runTaskLater(plugin, () -> admitWhenChecked(claim, c, captured, waited + 1), 20L); return; }
            catch (RuntimeException unschedulable) { /* falls through: refused and held */ }
        }
        boolean accepted = claim.consentRevision() == c.revision() && authority(captured) && plugin.utilityPayments() != null
                && plugin.utilityPayments().requestAutoCare(claim, () -> authority(captured), ignored -> busy = false);
        if (!accepted) submit(() -> {
            repository.hold(claim.claimId());
            post(() -> busy = false);
        }, () -> busy = false);
    }

    /** Worker-only selection; paid/waiting manual bills and consumed daily slots are not duplicated. */
    private Candidate candidate(AutoCareRepository.Consent c, OwnerDeskData data, LocalDate day) throws Exception {
        var attempted = EnumSet.noneOf(AutoCareRepository.Action.class);
        long retained = 0;
        try (var connection = database.getConnection(); var ps = connection.prepareStatement(
                "SELECT action,ownership_epoch,quoted_cents,state FROM npc_auto_care_claims "
                        + "WHERE property_id=? AND economy_day=? LIMIT 4")) {
            ps.setQueryTimeout(5);
            ps.setBytes(1, Uuids.toBytes(c.propertyId()));
            ps.setObject(2, day);
            try (var rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (!attempted.add(AutoCareRepository.Action.valueOf(rs.getString(1))))
                        throw new IllegalStateException("Duplicate recurring action");
                    if (rs.getInt("ownership_epoch") == c.ownershipEpoch()
                            && AutoCareRepository.State.valueOf(rs.getString("state")) != AutoCareRepository.State.DECLINED)
                        retained = Math.addExact(retained, rs.getLong("quoted_cents"));
                }
                if (connection.getWarnings() != null || ps.getWarnings() != null || rs.getWarnings() != null)
                    throw new IllegalStateException("Auto-care slot read warning");
            }
        }
        // Selection hint only. reserve()/bind() recheck amounts and every released no-movement proof.
        long remaining = remainingBudget(c.dailyCeilingCents(), retained);
        if (c.actions().contains(AutoCareRepository.Action.UTILITY) && !attempted.contains(AutoCareRepository.Action.UTILITY)) {
            var history = utilities.history(c.propertyId(), day);
            if (history.isEmpty() || history.stream().allMatch(r -> r.state() == UtilityRepository.State.DECLINED)) {
                long cost = history.isEmpty() ? PropertyFormulas.dailyUtilitiesCents(data.residents().size(),
                        data.upgrades().stream().mapToInt(OwnerDeskData.Upgrade::level).sum()) : history.getFirst().bill().cents();
                if (cost <= remaining) return new Candidate(c, AutoCareRepository.Action.UTILITY, null, cost);
            }
        }
        if (c.actions().contains(AutoCareRepository.Action.CASH_REPAIR) && !attempted.contains(AutoCareRepository.Action.CASH_REPAIR)) {
            for (var issue : data.issues()) if (issue.repairCents() <= remaining)
                return new Candidate(c, AutoCareRepository.Action.CASH_REPAIR, issue.id(), issue.repairCents());
        }
        if (c.actions().contains(AutoCareRepository.Action.MAINTAIN) && !attempted.contains(AutoCareRepository.Action.MAINTAIN)
                && data.property().conditionScore() < 80 && remaining >= 1000)
            return new Candidate(c, AutoCareRepository.Action.MAINTAIN, null, 1000);
        return null;
    }

    private boolean owner(Player p, Property property) {
        main();
        return !closed && p.isOnline() && Bukkit.getPlayer(p.getUniqueId()) == p && !p.isDead()
                && p.hasPermission("prosperitynpc.use") && p.getUniqueId().equals(property.ownerUuid())
                && plugin.armAdapter().ownerReadRegion(property.worldUuid(), property.regionId(), p.getUniqueId()).isPresent();
    }
    /** Recurring-charge candidates: owner-away homes are left out for the day. */
    public static List<Property> careTargets(List<Property> enrolled, Set<UUID> away) { return OwnerAway.present(enrolled, away); }
    static UUID scanCursor(List<AutoCareRepository.Consent> page, AutoCareRepository.Consent attempted) {
        if (attempted != null) return attempted.propertyId();
        return page.size() < 16 ? null : page.getLast().propertyId();
    }
    static long remainingBudget(long ceiling, long retained) {
        if (ceiling < 0 || retained < 0) throw new IllegalArgumentException("Invalid auto-care selection budget");
        return retained >= ceiling ? 0 : ceiling - retained;
    }
    private boolean authority(Authority captured) {
        main();
        if (closed || !configured() || !plugin.killSwitches().isSystemEnabled() || !plugin.killSwitches().isEconomyEnabled()
                || !consentFence.permits(captured)) return false;
        var c = captured.consent();
        var p = plugin.propertyService().getEnrolled().stream().filter(a -> a.propertyId().equals(c.propertyId())).findFirst().orElse(null);
        return p != null && p.enabled() && c.ownerUuid().equals(p.ownerUuid()) && c.ownershipEpoch() == p.ownershipEpoch()
                && plugin.armAdapter().ownerReadRegion(p.worldUuid(), p.regionId(), c.ownerUuid()).isPresent();
    }
    private boolean configured() { return Boolean.TRUE.equals(plugin.getConfig().get("auto-care.enabled")); }
    private void submit(Work work, Runnable failed) {
        main();
        try {
            worker.execute(() -> {
                try {
                    work.run();
                } catch (Exception failure) {
                    post(() -> {
                        plugin.getLogger().warning("Auto-care operation unconfirmed; retained claims require review.");
                        failed.run();
                    });
                }
            });
        } catch (RuntimeException rejected) {
            failed.run();
        }
    }
    private void post(Runnable callback) {
        if (!closed && !completions.offer(callback)) {
            closed = true;
            worker.shutdown();
        }
    }
    public CompletionStage<Void> closeAsync() {
        main();
        closed = true;
        task.cancel();
        completions.clear();
        consentFence.clear();
        worker.shutdown();
        return drained.minimalCompletionStage();
    }
    private static void tell(Player p, String message) {
        if (p.isOnline()) p.sendMessage(Component.text(message, NamedTextColor.GRAY));
    }
    private static void main() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Auto-care authority requires main thread");
    }
    @FunctionalInterface private interface Work { void run() throws Exception; }
}

package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.ProsperityNpcPlugin;
import com.prosperitysmp.npc.alert.StaffAlerts;
import com.prosperitysmp.npc.clock.EconomyClock;
import com.prosperitysmp.npc.commerce.CommerceCatalogue;
import com.prosperitysmp.npc.commerce.CommerceOrder;
import com.prosperitysmp.npc.commerce.CommerceOrderRepository;
import com.prosperitysmp.npc.commerce.CommercePriceBook;
import com.prosperitysmp.npc.db.Database;
import java.util.concurrent.RejectedExecutionException;
import com.prosperitysmp.npc.api.MoneySource;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Finite, two-leg purchases. Custody is separate; uncertainty never causes a retry or refund. */
public final class CommercePaymentRuntime {
    /** EXPIRED: refused at the debit intent because the quote's partner rate no longer holds. Nothing moved. */
    public enum Status { FUNDED, DECLINED, CANCELLED, HELD, BUSY, UNAVAILABLE, INSUFFICIENT_FUNDS, LIMIT, EXPIRED }
    public record Result(Status status, CommerceOrderRepository.Receipt receipt) { }
    private final ProsperityNpcPlugin plugin;
    private final Database database;
    private final CommerceOrderRepository orders;
    private final CommercePriceBook prices;
    private final EconomyClock days;
    private final UUID reserve, runId = UUID.randomUUID();
    /** Main thread only: payments refused before any money moved. */
    private final RefusalMonitor refusals;
    private final ThreadPoolExecutor worker;
    private final ArrayBlockingQueue<Runnable> completions = new ArrayBlockingQueue<>(16);
    private final Executor mainExecutor = this::post;
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private final BukkitTask ticker;
    private volatile boolean closed;
    private boolean ready, busy, held;

    public CommercePaymentRuntime(ProsperityNpcPlugin plugin, CommercePriceBook prices, EconomyClock days) {
        main();
        this.plugin = Objects.requireNonNull(plugin);
        this.prices = Objects.requireNonNull(prices);
        this.days = Objects.requireNonNull(days);
        reserve = ReserveAccount.fromConfig(plugin.getConfig());
        database = Objects.requireNonNull(plugin.database());
        orders = new CommerceOrderRepository(database, reserve);
        refusals = new RefusalMonitor((kind, subject, text) -> plugin.alerts().raise(kind, subject, text),
                plugin.getLogger()::warning, Bukkit::isPrimaryThread);
        String build = plugin.getPluginMeta().getVersion();
        Path ownJar;
        try { ownJar = Path.of(CommercePaymentRuntime.class.getProtectionDomain().getCodeSource().getLocation().toURI()); }
        catch (Exception unavailable) { ownJar = null; }
        final Path runJar = ownJar;
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), r -> {
            Thread t = new Thread(r, "npc-commerce-payment");
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
                    Runnable next = completions.poll();
                    if (next == null) break;
                    next.run();
                }
            }, 1, 1);
            ticker = scheduled;
            CompletableFuture.supplyAsync(() -> {
                // Ties every order this run writes (run_id) to the build and jar that wrote it.
                try { plugin.getLogger().info(UtilityPaymentRuntime.runLine("NPC commerce run", runId, build, runJar)); }
                catch (RuntimeException unlogged) { /* Startup continues; the hold check below still runs. */ }
                try {
                    if (orders.recoverOtherRuns(runId, 100) != 0) return false;
                    try (var c = database.getConnection(); var ps = c.prepareStatement("""
                            SELECT 0 FROM npc_commerce_orders
                            WHERE state IN ('DEBIT_INTENT','CREDIT_READY','CREDIT_INTENT','AMBIGUOUS')
                            UNION ALL SELECT 0 FROM npc_transfer_attempts
                            WHERE state IN ('DEBIT_INTENT','CREDIT_READY','CREDIT_INTENT','AMBIGUOUS') LIMIT 1
                            """)) {
                        ps.setQueryTimeout(5);
                        try (var rs = ps.executeQuery()) {
                            boolean unresolved = rs.next();
                            if (c.getWarnings() != null || ps.getWarnings() != null || rs.getWarnings() != null)
                                throw new IllegalStateException("Commerce startup read warning");
                            return !unresolved;
                        }
                    }
                } catch (Exception failure) { return false; }
            }, worker).thenAccept(clear -> post(() -> {
                ready = clear;
                held = !clear;
            }));
        } catch (RuntimeException | Error failure) {
            closed = true;
            if (scheduled != null) scheduled.cancel();
            worker.shutdown();
            throw failure;
        }
    }

    /** Accepts a server-held quote only. Both wallets stay leased through SQL acknowledgements. */
    public boolean request(Player player, CommerceOrder order, CommerceCatalogue.Entry entry,
                           BooleanSupplier authorized, Consumer<Result> reply) {
        return request(player, order, entry, 0, null, authorized, reply);
    }

    /**
     * feeDiscountPct is the partner rate the caller read on its worker (town.partnerships; 0 otherwise). It only lowers
     * the service fee in the quote check below; the material part and every cap are unchanged. With 0 and no check
     * this is the plain request above. A rate above 0 needs partnerCheck, which re-checks that rate against the
     * current partnership inside the debit-intent transaction; when it fails the order is cancelled there and the
     * reply is EXPIRED with nothing moved.
     */
    public boolean request(Player player, CommerceOrder order, CommerceCatalogue.Entry entry, int feeDiscountPct,
                           CommerceOrderRepository.DebitCheck partnerCheck, BooleanSupplier authorized, Consumer<Result> reply) {
        main();
        Objects.requireNonNull(player);
        Objects.requireNonNull(order);
        Objects.requireNonNull(entry);
        Objects.requireNonNull(authorized);
        Objects.requireNonNull(reply);
        if (feeDiscountPct > 0 && partnerCheck == null) {
            reply.accept(new Result(Status.UNAVAILABLE, null));
            return false;
        }
        if (!available() || busy || !actor(player, order, entry, feeDiscountPct, authorized) || reserve.equals(order.playerId())) {
            reply.accept(new Result(busy ? Status.BUSY : Status.UNAVAILABLE, null));
            return false;
        }
        var pinned = plugin.moneySource().pin().orElse(null);
        final Path jar;
        try {
            if (pinned == null || !pinned.stillCurrent()) {
                reply.accept(new Result(Status.UNAVAILABLE, null));
                return false;
            }
            long balance = TransferProof.availableCents(pinned.balanceDollars(player)).orElseThrow();
            if (balance < order.costCents()) {
                reply.accept(new Result(Status.INSUFFICIENT_FUNDS, null));
                return false;
            }
            jar = pinned.artifact();
        } catch (Exception unavailable) {
            reply.accept(new Result(Status.UNAVAILABLE, null));
            return false;
        }
        final FinancialFence.Lease lease;
        try { lease = plugin.financialFence().acquire("commerce:" + order.orderId()); }
        catch (RuntimeException occupied) {
            reply.accept(new Result(Status.BUSY, null));
            return false;
        }
        final CommerceOrderRepository.Budgets limits;
        try {
            limits = limits();
        } catch (RuntimeException invalid) {
            lease.releaseVerified();
            reply.accept(new Result(Status.UNAVAILABLE, null));
            return false;
        }
        busy = true;
        final CompletableFuture<String> fingerprint;
        try { fingerprint = CompletableFuture.supplyAsync(() -> UtilityPaymentRuntime.hash(jar), worker); }
        catch (RuntimeException rejected) {
            busy = false;
            lease.releaseVerified();
            reply.accept(new Result(Status.BUSY, null));
            return false;
        }
        // Nothing is written until the order is reserved below: a failure up to there started no payment.
        fingerprint.handle((sha, error) -> {
            if (error != null) throw NotStarted.of(error);
            return sha;
        }).thenComposeAsync(sha -> {
            if (!actor(player, order, entry, feeDiscountPct, authorized)) {
                return CompletableFuture.completedFuture(new Result(Status.CANCELLED, null));
            }
            try { provider(pinned, lease); }
            catch (RuntimeException | LinkageError refused) { throw new NotStarted(refused); }
            return CompletableFuture.supplyAsync(() -> {
                try { return orders.reserve(order, limits).receipt(); }
                catch (Exception failure) { throw new IllegalStateException("Commerce reservation unconfirmed", failure); }
            }, worker).thenComposeAsync(receipt -> {
                if (receipt.state() != CommerceOrderRepository.State.RESERVED) return completed(receipt);
                if (!actor(player, order, entry, feeDiscountPct, authorized)) return cancel(order);
                // Reserved but no debit intent yet: cancel the order exactly as a lost actor does.
                try { provider(pinned, lease); }
                catch (RuntimeException | LinkageError refused) { return cancel(order); }
                var handle = new CommerceOrderRepository.Handle(order.orderId(), runId, order.playerId(), reserve, sha);
                return CompletableFuture.supplyAsync(() -> {
                    try { return orders.beginDebit(order, handle, partnerCheck); }
                    catch (Exception failure) { throw new IllegalStateException("Commerce debit intent unconfirmed"); }
                }, worker).thenComposeAsync(start -> {
                    if (!start.newlyStarted()) return start.receipt().state() == CommerceOrderRepository.State.RESERVED
                            ? cancel(order) : completed(start.receipt());
                    var observed = observe(player, order, entry, feeDiscountPct, authorized, pinned, lease, TransferProof.Leg.DEBIT);
                    if (observed.refusedBeforeCall() != null)
                        refusals.refused("commerce", "order " + order.orderId(), TransferProof.Leg.DEBIT, observed);
                    return CompletableFuture.supplyAsync(() -> {
                        try { return orders.recordDebit(handle, observed); }
                        catch (Exception failure) { throw new IllegalStateException("Commerce debit acknowledgement unconfirmed"); }
                    }, worker).thenComposeAsync(debit -> {
                        // The credit leg checks the provider first; a refusal there is journaled for staff to refund.
                        if (debit.state() != CommerceOrderRepository.State.CREDIT_READY) return completed(debit);
                        return CompletableFuture.supplyAsync(() -> {
                            try { return orders.beginCredit(handle); }
                            catch (Exception failure) { throw new IllegalStateException("Commerce credit intent unconfirmed"); }
                        }, worker).thenComposeAsync(credit -> {
                            if (!credit.newlyStarted()) return completed(credit.receipt());
                            var credited = observe(player, order, entry, feeDiscountPct, authorized, pinned, lease,
                                    TransferProof.Leg.CREDIT);
                            if (credited.refusedBeforeCall() != null)
                                refusals.refused("commerce", "order " + order.orderId(), TransferProof.Leg.CREDIT, credited);
                            Instant funded = Instant.now();
                            return CompletableFuture.supplyAsync(() -> {
                                try {
                                    var paid = orders.recordCredit(handle, credited, funded);
                                    if (paid.state() == CommerceOrderRepository.State.FUNDED
                                            && orders.paidProof(order.playerId(), order.orderId()).isEmpty())
                                        throw new IllegalStateException("Commerce funded proof missing");
                                    return result(paid);
                                } catch (Exception failure) { throw new IllegalStateException("Commerce funding unconfirmed"); }
                            }, worker);
                        }, mainExecutor);
                    }, mainExecutor);
                }, mainExecutor);
            }, mainExecutor);
        }, mainExecutor).whenComplete((outcome, failure) -> {
            try { post(() -> {
                plugin.accountingProjection().invalidate();
                NotStarted stopped = NotStarted.in(failure);
                if (stopped != null) refusals.notStarted("commerce", "order " + order.orderId(), stopped.getCause());
                Result actual = failure == null ? outcome : stopped != null ? new Result(Status.UNAVAILABLE, null)
                        : new Result(knownLimit(failure) ? Status.LIMIT : Status.HELD, null);
                if (actual.status() == Status.FUNDED) refusals.paid("commerce");
                if (actual.status() == Status.HELD) {
                    held = true;
                    plugin.getLogger().warning("Commerce held for recovery; order " + order.orderId());
                    plugin.alerts().raise(StaffAlerts.Kind.PAYMENT_HELD_FOR_REVIEW, "order-" + order.orderId(),
                            "Commerce payment held for review. Order " + order.orderId() + ".");
                } else {
                    lease.releaseVerified();
                    busy = false;
                }
                if (!closed && player.isOnline() && Bukkit.getPlayer(player.getUniqueId()) == player) reply.accept(actual);
            });
            } catch (RuntimeException stopping) { /* Journal intent survives shutdown; no further provider call. */ }
        });
        return true;
    }

    private TransferProof.Observation observe(Player player, CommerceOrder order, CommerceCatalogue.Entry entry,
            int feeDiscountPct, BooleanSupplier authorized, MoneySource.Pin pinned, FinancialFence.Lease lease,
            TransferProof.Leg leg) {
        var observation = new LegObservation(pinned, () -> {
            // Completing a verified debit depends only on the provider: not on the player staying in the menu or
            // online, nor on a reload or kill switch since the debit.
            UtilityPaymentRuntime.requireLegAuthority(leg, pinned, () -> {
                provider(pinned, lease);
                if (!actor(player, order, entry, feeDiscountPct, authorized))
                    throw new IllegalStateException("Commerce debit authority changed");
            });
            return pinned;
        }, Bukkit::isPrimaryThread);
        UUID account = leg == TransferProof.Leg.DEBIT ? order.playerId() : reserve;
        UUID counterparty = leg == TransferProof.Leg.DEBIT ? reserve : order.playerId();
        return observation.execute(account, Bukkit.getOfflinePlayer(account), Bukkit.getOfflinePlayer(counterparty),
                order.costCents(), leg);
    }

    private void provider(MoneySource.Pin pinned, FinancialFence.Lease lease) {
        main();
        lease.validate();
        if (closed || !plugin.isEnabled() || !pinned.stillCurrent()
                || !reserve.toString().equals(ReserveAccount.liveText(plugin.getConfig())))
            throw new IllegalStateException("Commerce provider unavailable");
    }
    private boolean actor(Player player, CommerceOrder order, CommerceCatalogue.Entry entry, int feeDiscountPct,
                          BooleanSupplier authorized) {
        try {
            var unit = prices.unitCents(order.material());
            return available() && player.isOnline() && Bukkit.getPlayer(player.getUniqueId()) == player
                    && player.hasPermission("prosperitynpc.use") && player.getUniqueId().equals(order.playerId())
                    && days.today().equals(order.economyDay()) && authorized.getAsBoolean() && unit.isPresent()
                    && entry.id().equals(order.catalogueId()) && entry.source() == order.source()
                    && entry.material().equals(order.material()) && entry.quantity() == order.quantity()
                    && entry.delaySeconds() == order.outputDelaySeconds()
                    && entry.cost(unit.getAsLong(), feeDiscountPct) <= order.costCents();
        } catch (RuntimeException changed) { return false; }
    }
    public boolean available() {
        main(); return !closed && ready && !held && plugin.isEnabled()
                && plugin.killSwitches().isSystemEnabled() && plugin.killSwitches().isEconomyEnabled()
                && Boolean.TRUE.equals(plugin.getConfig().get("town.commerce-enabled"))
                && plugin.accountingProjection() != null && plugin.accountingProjection().ready();
    }
    public String status() { main(); return closed ? "CLOSED" : held ? "RECOVERY_HELD" : !ready ? "STARTUP_PENDING"
            : busy ? "SETTLING" : available() ? "READY" : "DISABLED_OR_ACCOUNTING_PENDING"; }
    private CommerceOrderRepository.Budgets limits() {
        return new CommerceOrderRepository.Budgets(plugin.getConfig().getInt("town.commerce.global-orders-per-day", 64),
                plugin.getConfig().getInt("town.commerce.global-units-per-day", 512),
                plugin.getConfig().getInt("town.commerce.material-units-per-day", 128),
                plugin.getConfig().getLong("town.commerce.global-cost-cents-per-day", 1_000_000));
    }
    private CompletableFuture<Result> cancel(CommerceOrder order) {
        return CompletableFuture.supplyAsync(() -> {
            try { return result(orders.cancel(order)); }
            catch (Exception failure) { throw new IllegalStateException("Commerce cancel unconfirmed"); }
        }, worker);
    }
    private static CompletableFuture<Result> completed(CommerceOrderRepository.Receipt receipt) {
        return CompletableFuture.completedFuture(result(receipt));
    }
    private static Result result(CommerceOrderRepository.Receipt receipt) {
        return new Result(switch (receipt.state()) {
            case FUNDED -> Status.FUNDED;
            case DECLINED -> Status.DECLINED;
            case CANCELLED -> CommerceOrderRepository.QUOTE_EXPIRED.equals(receipt.outcome()) ? Status.EXPIRED : Status.CANCELLED;
            default -> Status.HELD;
        }, receipt);
    }
    private static boolean knownLimit(Throwable failure) {
        for (Throwable next = failure; next != null; next = next.getCause()) {
            if (next.getSuppressed().length != 0) return false;
            if (next instanceof CommerceOrderRepository.IssuanceRefused) return true;
        }
        return false;
    }
    private synchronized void post(Runnable callback) {
        if (closed || !completions.offer(callback)) throw new RejectedExecutionException("Commerce main queue unavailable");
    }
    public CompletionStage<Void> closeAsync() {
        main();
        closed = true;
        ready = false;
        ticker.cancel();
        completions.clear();
        worker.shutdown();
        return drained.minimalCompletionStage();
    }
    private static void main() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Commerce settlement requires main thread");
    }
}

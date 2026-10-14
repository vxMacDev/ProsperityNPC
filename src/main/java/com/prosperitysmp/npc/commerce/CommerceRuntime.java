package com.prosperitysmp.npc.commerce;

import com.prosperitysmp.npc.ProsperityNpcPlugin;
import com.prosperitysmp.npc.clock.EconomyClock;
import com.prosperitysmp.npc.economy.CommercePaymentRuntime;
import com.prosperitysmp.npc.economy.ReserveAccount;
import com.prosperitysmp.npc.interaction.EncounterView;
import com.prosperitysmp.npc.interaction.ResidentAction;
import com.prosperitysmp.npc.resident.RelationshipMemory;
import com.prosperitysmp.npc.resident.ResidentProfile;
import com.prosperitysmp.npc.resident.ResidentRepository;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Player-facing merchant and hired-worker loop; no client-authored material, amount, cost or recipient. */
public final class CommerceRuntime {
    private record Pending(Player login, UUID token, Consumer<String> reply) { }
    @FunctionalInterface private interface Work { void run() throws Exception; }
    /**
     * town.partnerships partner rate: percent off one resident's service fee for one player today. Worker thread
     * only. enabled() false (partnerships off) means no read at all: full prices and the plain checks.
     */
    public interface PartnerRates {
        boolean enabled();
        int feeDiscountPct(UUID player, UUID resident, LocalDate day) throws Exception;
        /**
         * Inside the payment transaction c, just before the debit intent: true only while this player still gets at
         * least pct off at this resident on day. Must lock what it reads so an end waits for the decision.
         */
        boolean stillGrants(Connection c, UUID player, UUID resident, LocalDate day, int pct) throws SQLException;
    }
    /** Appended to a quote label when the partner rate applies. */
    public static final String PARTNER_RATE = " (partner rate)";
    private final ProsperityNpcPlugin plugin;
    private final CommerceOrderRepository orders;
    private final ItemCustodyRepository custody;
    private final ResidentRepository residents;
    private final CommercePaymentRuntime payments;
    private final ItemDeliveryRuntime delivery;
    private final EconomyClock days;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final ThreadPoolExecutor worker;
    private final ArrayBlockingQueue<Runnable> completions = new ArrayBlockingQueue<>(64);
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    private final BukkitTask ticker;
    private volatile boolean closed;
    private boolean maintenancePending;
    private long nextMaintenance;
    private volatile PartnerRates partnerRates;

    public CommerceRuntime(ProsperityNpcPlugin plugin, CommercePaymentRuntime payments,
                           ItemDeliveryRuntime delivery, EconomyClock days) {
        main();
        this.plugin = plugin;
        this.payments = payments;
        this.delivery = delivery;
        this.days = days;
        orders = new CommerceOrderRepository(plugin.database(), ReserveAccount.fromConfig(plugin.getConfig()));
        custody = new ItemCustodyRepository(plugin.database());
        residents = new ResidentRepository(plugin.database());
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), r -> {
            Thread t = new Thread(r, "npc-commerce-orders");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy()) {
            @Override protected void terminated() {
                drained.complete(null);
            }
        };
        BukkitTask scheduled = null;
        try {
            plugin.trackDatabaseWorker(worker);
            scheduled = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                pending.entrySet().removeIf(e -> !e.getValue().login().isOnline()
                        || Bukkit.getPlayer(e.getKey()) != e.getValue().login());
                for (int i = 0; i < 16; i++) {
                    Runnable r = completions.poll();
                    if (r == null) break;
                    r.run();
                }
                if (!maintenancePending && System.nanoTime() >= nextMaintenance) sweep();
            }, 1, 1);
            ticker = scheduled;
        } catch (RuntimeException | Error failure) {
            closed = true;
            if (scheduled != null) scheduled.cancel();
            worker.shutdown();
            throw failure;
        }
    }

    /** Set once at wiring. Null means no partner rates. */
    public void setPartnerRates(PartnerRates rates) { partnerRates = rates; }

    /** Worker: the partner rate for this player at this resident today, 0 while partnerships are off. */
    private int partnerPct(UUID player, UUID resident, LocalDate day) throws Exception {
        PartnerRates rates = partnerRates;
        if (rates == null || !rates.enabled()) return 0;
        int pct = rates.feeDiscountPct(player, resident, day);
        if (pct < 0 || pct > 60) throw new IllegalStateException("Invalid partner rate");
        return pct;
    }

    /** Stale untouched quotes may expire; funded/uncertain obligations and consumed budgets never do. */
    private void sweep() {
        maintenancePending = true;
        nextMaintenance = System.nanoTime() + TimeUnit.MINUTES.toNanos(2);
        try {
            worker.execute(() -> {
                try {
                    Instant now = Instant.now();
                    orders.cancelUnstartedBefore(now.minusSeconds(600), 32);
                    custody.expireReservations(now, 32);
                } catch (Exception unconfirmed) {
                    post(() -> plugin.getLogger().warning("Commerce reservation cleanup unconfirmed; obligations retained."));
                } finally { post(() -> maintenancePending = false); }
            });
        } catch (RuntimeException queueFull) { maintenancePending = false; }
    }

    /** Worker-thread view. All mutable Bukkit values are immutable snapshots supplied by the encounter. */
    public EncounterView view(EncounterView base, ResidentProfile resident, RelationshipMemory.State relationship,
                              UUID player, Instant now, LocalDate day, Map<String, Long> fullPrices, boolean enabled) throws Exception {
        return view(base, resident, relationship, player, now, day, fullPrices, enabled, false);
    }

    /** hireNotice: once the day's one hired job is used, the Hire button is hidden and the line says so. */
    public EncounterView view(EncounterView base, ResidentProfile resident, RelationshipMemory.State relationship,
                              UUID player, Instant now, LocalDate day, Map<String, Long> fullPrices, boolean enabled,
                              boolean hireNotice) throws Exception {
        if (!enabled) return new EncounterView(base.name(), base.occupation(), base.familiarity(),
                "My supplies and hired-work desk is closed right now. Existing paid orders stay recorded.", base.topics());
        var outstanding = orders.outstanding(player, 8);
        var actions = new ArrayList<ResidentAction>();
        String line = "Supplies are immediate. Hired work takes 5 minutes; one job per service day. Prices include materials and service.";
        var thisResident = outstanding.stream().filter(r -> r.order().residentId().equals(resident.id())).toList();
        for (var receipt : thisResident) {
            if (receipt.state() != CommerceOrderRepository.State.FUNDED) {
                line = "An order needs settlement review. Reference: " + receipt.order().orderId() + ". Nothing will be charged again.";
                continue;
            }
            if (now.isBefore(receipt.outputReadyAt())) {
                line = "Your " + materialLabel(receipt.order().material()) + " job is underway. Ready in "
                        + Math.max(1, Duration.between(now, receipt.outputReadyAt()).toSeconds()) + " seconds.";
            } else if (actions.isEmpty()) {
                var held = receipt.custodyNonce() == null ? null : custody.find(player, receipt.custodyNonce(), now).orElse(null);
                if (held == null || held.state() == ItemCustodyRepository.State.RESERVED) {
                    actions.add(new ResidentAction(ResidentAction.Kind.COLLECT, null, receipt.order().orderId(), resident.id(),
                            "Collect " + receipt.order().quantity() + " " + materialLabel(receipt.order().material())));
                    line = "Your paid order is ready. Collect into inventory or ender chest; it is never dropped on the ground.";
                } else line = "Your item receipt needs staff review. Reference: " + receipt.order().orderId() + ". Do not buy it again.";
            }
        }
        if (outstanding.size() >= 8) line += " Collect outstanding orders before starting more.";
        else {
            var eligible = CommerceCatalogue.entries(resident.occupation()).stream()
                    .filter(entry -> relationship.stage().ordinal() >= entry.minimum().ordinal()).toList();
            // One merchant option plus the highest eligible worker tier; ready collections take priority.
            var selected = new ArrayList<CommerceCatalogue.Entry>();
            boolean hireUsed = hireNotice && orders.workerDayUsed(player, day);
            int partnerPct = partnerPct(player, resident.id(), day);
            eligible.stream().filter(e -> e.source() == ItemCustody.Source.MERCHANT_GOODS).findFirst().ifPresent(selected::add);
            if (!hireUsed) eligible.stream().filter(e -> e.source() == ItemCustody.Source.WORKER_OUTPUT).reduce((a, b) -> b)
                    .ifPresent(selected::add);
            for (var entry : selected) {
                Long unit = fullPrices.get(entry.material());
                if (unit == null || actions.size() >= 3) continue;
                long cost = entry.cost(unit, partnerPct);
                UUID nonce = UUID.randomUUID();
                var order = new CommerceOrder(CommerceOrder.idFor(nonce, player, entry.source(), day), nonce, player,
                        resident.id(), entry.source(), entry.id(), entry.material(), entry.quantity(), cost, day, now,
                        entry.delaySeconds());
                String label = orderLabel(entry, unit, partnerPct);
                actions.add(new ResidentAction(ResidentAction.Kind.ORDER, entry.id(), order.orderId(), resident.id(), label, order));
            }
            if (eligible.isEmpty())
                line = "Let's get acquainted before trading. Errands build trust; supplies and paid jobs open at Acquaintance.";
            else if (fullPrices.isEmpty())
                line = "I cannot verify today's full material prices. New purchases are held; paid outputs remain collectable.";
            else if (hireUsed && thisResident.isEmpty() && eligible.stream().anyMatch(e -> e.source() == ItemCustody.Source.WORKER_OUTPUT))
                line += " You've used today's hired job; it opens again on the next service day.";
        }
        return new EncounterView(base.name(), base.occupation(), base.familiarity(), line, base.topics(), actions);
    }

    public void order(Player player, CommerceOrder quote, BooleanSupplier authority, Consumer<String> reply) {
        main();
        Objects.requireNonNull(quote);
        if (!delivery.ready()) {
            reply.accept("Item persistence checks are not ready. No payment started.");
            return;
        }
        Pending op = begin(player, reply);
        if (op == null) return;
        Instant now = Instant.now();
        if (!actor(op, quote.residentId(), authority) || !quote.playerId().equals(player.getUniqueId())
                || now.isBefore(quote.issuedAt()) || now.isAfter(quote.issuedAt().plusSeconds(120))
                || !days.today().equals(quote.economyDay())) {
            finish(op, "That quote expired. No payment started; ask for a fresh quote.");
            return;
        }
        submit(op, () -> {
            var resident = residents.find(quote.residentId()).orElseThrow();
            var entry = CommerceCatalogue.find(resident.occupation(), quote.catalogueId()).orElseThrow();
            var relationship = residents.readRelationship(resident.id(), quote.playerId());
            if (relationship.stage().ordinal() < entry.minimum().ordinal()) {
                post(() -> finish(op, "That service requires more trust. No payment started."));
                return;
            }
            // Partner rate re-validated against the CURRENT partnership, before any payment. Off: no read, no check.
            PartnerRates rates = partnerRates;
            boolean partnersOn = rates != null && rates.enabled();
            int pct = partnersOn ? partnerPct(quote.playerId(), resident.id(), quote.economyDay()) : 0;
            // pct is captured here, so a partnership ended (or a perk lowered) after this read is invisible to the
            // checks below. The payment re-checks it inside the debit-intent transaction, under a lock on the row.
            CommerceOrderRepository.DebitCheck stillPartner = pct > 0
                    ? c -> rates.stillGrants(c, quote.playerId(), resident.id(), quote.economyDay(), pct) : null;
            post(() -> {
                if (!actor(op, resident.id(), authority)) {
                    finish(op, "You moved away; no payment started.");
                    return;
                }
                if (partnersOn && !quoteStillValid(entry, quote, pct, plugin.commercePrices().unitCents(quote.material()))) {
                    finish(op, "That quote expired. No payment started; ask for a fresh quote."); return;
                }
                payments.request(player, quote, entry, pct, stillPartner, () -> actor(op, resident.id(), authority), result -> {
                    String message = switch (result.status()) {
                        case FUNDED -> quote.source() == ItemCustody.Source.MERCHANT_GOODS
                                ? "Payment verified: " + money(quote.costCents()) + ". Choose Supplies to collect your goods."
                                : "Work commissioned for " + money(quote.costCents())
                                + ". Your output is ready in 5 minutes; return here to collect.";
                        case DECLINED -> "Payment declined with no movement. No automatic retry; today's issuance slot stays used.";
                        case CANCELLED -> "Order cancelled before payment. No goods or money were moved.";
                        case INSUFFICIENT_FUNDS -> "You do not have enough available money. No payment started.";
                        case BUSY -> "The settlement desk is busy. No payment started.";
                        case LIMIT -> "The day's commodity/job limit or your open-order limit is reached. No payment started.";
                        case UNAVAILABLE -> "New purchases are held while price/accounting checks finish. No payment started.";
                        case HELD -> "Settlement needs staff review. Do not retry. Order: " + quote.orderId();
                        case EXPIRED -> "That quote expired. No payment started; ask for a fresh quote.";
                    };
                    finish(op, message);
                });
            });
        });
    }

    public void collect(Player player, UUID orderId, UUID resident, BooleanSupplier authority, Consumer<String> reply) {
        main();
        Pending op = begin(player, reply);
        if (op == null) return;
        UUID playerId = player.getUniqueId();
        if (!actor(op, resident, authority)) {
            finish(op, "Speak with the resident to collect your order.");
            return;
        }
        submit(op, () -> {
            var receipt = orders.find(playerId, orderId).orElseThrow();
            if (!receipt.order().residentId().equals(resident) || receipt.state() != CommerceOrderRepository.State.FUNDED) {
                post(() -> finish(op, "This is not a funded order with this resident."));
                return;
            }
            if (Instant.now().isBefore(receipt.outputReadyAt())) {
                post(() -> finish(op, "Your job is still underway. Nothing new was granted."));
                return;
            }
            post(() -> {
                if (!actor(op, resident, authority)) {
                    finish(op, "You moved away. Your paid order is retained.");
                    return;
                }
                var material = Material.matchMaterial(receipt.order().material());
                if (material == null || !material.isItem() || material.isAir()) {
                    finish(op, "The stored material needs staff review. Your receipt is retained.");
                    return;
                }
                var item = new ItemStack(material, receipt.order().quantity());
                if (!ItemInventoryCustody.canReceive(player, item)) {
                    finish(op, "Free space in your inventory or ender chest. Your paid order is retained.");
                    return;
                }
                CustodyBytes payload = new CustodyBytes(item.serializeAsBytes());
                submit(op, () -> {
                    var claim = orders.claimItems(receipt.order(), payload, Instant.now());
                    post(() -> delivery.deliver(player, claim.custody().request(), () -> actor(op, resident, authority), result -> {
                        finish(op, switch (result) {
                            case DELIVERED -> "Order collected: " + receipt.order().quantity() + " "
                                    + materialLabel(receipt.order().material()) + ". Receipt verified; it cannot be collected twice.";
                            case NO_SPACE -> "Free inventory or ender-chest space. Your paid output is retained.";
                            case BUSY, NOT_READY -> "Collection is busy. Your paid output stays recorded; ask again shortly.";
                            case UNAUTHORIZED -> "Collection stopped because your encounter changed. Your order stays recorded.";
                            case HELD -> "Item delivery needs staff review. Do not collect again. Order: " + orderId;
                        });
                    }));
                });
            });
        });
    }

    /** With partnerships on: the quote must equal today's price at the current partner rate, or it is refused. */
    public static boolean quoteStillValid(CommerceCatalogue.Entry entry, CommerceOrder quote, int pct, OptionalLong unit) {
        try { return unit.isPresent() && entry.cost(unit.getAsLong(), pct) == quote.costCents(); }
        catch (RuntimeException invalid) { return false; }
    }

    private Pending begin(Player player, Consumer<String> reply) {
        if (closed || pending.size() >= 32 || pending.containsKey(player.getUniqueId())) {
            reply.accept("One order operation at a time, please.");
            return null;
        }
        Pending op = new Pending(player, UUID.randomUUID(), reply);
        pending.put(player.getUniqueId(), op);
        return op;
    }
    private boolean actor(Pending op, UUID resident, BooleanSupplier authority) {
        try { return !closed && pending.get(op.login().getUniqueId()) == op && plugin.isEnabled()
                && op.login().isOnline() && Bukkit.getPlayer(op.login().getUniqueId()) == op.login()
                && op.login().hasPermission("prosperitynpc.use") && enabled() && authority.getAsBoolean(); }
        catch (RuntimeException unavailable) { return false; }
    }
    public boolean enabled() {
        main(); return !closed && plugin.killSwitches().isSystemEnabled() && plugin.killSwitches().isEconomyEnabled()
                && Boolean.TRUE.equals(plugin.getConfig().get("town.commerce-enabled"));
    }
    private void finish(Pending op, String line) {
        if (!pending.remove(op.login().getUniqueId(), op)) return;
        if (!closed && op.login().isOnline() && Bukkit.getPlayer(op.login().getUniqueId()) == op.login()) op.reply().accept(line);
    }
    private void submit(Pending op, Work task) {
        try {
            worker.execute(() -> {
                try { task.run(); }
                catch (Exception failure) {
                    post(() -> {
                        plugin.getLogger().warning("Commerce order operation unconfirmed; request " + op.token());
                        finish(op, "Order confirmation is unavailable. Any recorded payment/output is retained; "
                                + "check again before buying another.");
                    });
                }
            });
        } catch (RuntimeException full) { finish(op, "The order desk is busy. No new provider call was started here."); }
    }
    private void post(Runnable task) {
        if (!closed && !completions.offer(task)) {
            closed = true;
            worker.shutdown();
        }
    }
    public CompletionStage<Void> closeAsync() {
        main();
        closed = true;
        ticker.cancel();
        pending.clear();
        completions.clear();
        worker.shutdown();
        return drained.minimalCompletionStage();
    }
    /**
     * "Buy 32 stone - $14.44". The partner mark shows only when the partner price is lower than the full price: the
     * service fee never drops below $1, so a small order can cost the same either way. Without a partner rate the
     * label never carries the mark.
     */
    static String orderLabel(CommerceCatalogue.Entry entry, long fullUnitCents, int partnerPct) {
        long cost = entry.cost(fullUnitCents, partnerPct);
        return (entry.source() == ItemCustody.Source.MERCHANT_GOODS ? "Buy " : "Hire: ")
                + entry.quantity() + " " + materialLabel(entry.material()) + " - " + money(cost)
                + (partnerPct > 0 && cost < entry.cost(fullUnitCents) ? PARTNER_RATE : "");
    }

    public static String materialLabel(String material) { return material.toLowerCase(Locale.ROOT).replace('_', ' '); }
    private static String money(long cents) { return String.format(Locale.ROOT, "$%.2f", cents / 100.0); }
    private static void main() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Commerce interaction requires main thread");
    }
}

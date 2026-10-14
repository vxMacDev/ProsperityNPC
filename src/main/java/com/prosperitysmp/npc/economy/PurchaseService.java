package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.ProsperityNpcPlugin;
import com.prosperitysmp.npc.util.Money;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Read-only receipt access. The reader capability deliberately has no enqueue or
 * completion operation, so this service cannot attach a financial writer. Live
 * purchases are journaled through UtilityPaymentRuntime.
 */
public final class PurchaseService implements AutoCloseable {
    @FunctionalInterface
    public interface ReceiptReader {
        Optional<PurchaseRepository.Receipt> findByRequest(UUID request) throws SQLException;
    }
    /** Item repair receipts by hand-in id, only for the player who handed the items in. */
    @FunctionalInterface
    public interface ItemReceiptReader {
        Optional<ItemRepairRepository.Stored> find(UUID collection, UUID player) throws SQLException;
    }
    private final ProsperityNpcPlugin plugin;
    private final ReceiptReader purchases;
    private final ItemReceiptReader itemRepairs;
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> cooldowns = new HashMap<>(); // main-thread only, capped at 256
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16), task -> {
                Thread thread = new Thread(task, "prosperity-npc-purchases");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean closed;

    public PurchaseService(ProsperityNpcPlugin plugin, ReceiptReader purchases) {
        this(plugin, purchases, (collection, player) -> Optional.empty());
    }

    /** itemRepairs is asked only when no purchase receipt matches, so purchase receipts read exactly as before. */
    public PurchaseService(ProsperityNpcPlugin plugin, ReceiptReader purchases, ItemReceiptReader itemRepairs) {
        this.plugin = plugin;
        if(plugin!=null)plugin.trackDatabaseWorker(worker);
        this.purchases = purchases;
        this.itemRepairs = java.util.Objects.requireNonNull(itemRepairs);
    }

    /** Receipts remain readable during a hold and after sale, but only by their payer. */
    public void showReceipt(Player player, UUID request) {
        requireMain();
        if (closed || !player.hasPermission("prosperitynpc.use")) { unavailable(player); return; }
        UUID actor = player.getUniqueId();
        if (!admit(actor)) { busy(player); return; }
        try {
            worker.execute(() -> {
                try {
                    var receipt = purchases.findByRequest(request);
                    boolean paid = receipt.isPresent() && receipt.get().request().payerId().equals(actor);
                    var items = paid ? Optional.<ItemRepairRepository.Stored>empty() : itemRepairs.find(request, actor);
                    deliver(player, () -> {
                        if (paid) report(player, receipt.get());
                        else if (items.isPresent()) itemLines(items.get()).forEach(line -> send(player, NamedTextColor.GRAY, line));
                        else send(player, NamedTextColor.GRAY, "No matching receipt for your account.");
                    });
                } catch (SQLException | RuntimeException error) {
                    logFailure("receipt-read", request.toString(), error);
                    deliver(player, () -> send(player, NamedTextColor.RED, "Receipts are temporarily unavailable. Keep your reference."));
                } finally { pending.remove(actor); }
            });
        } catch (RejectedExecutionException full) { pending.remove(actor); busy(player); }
    }

    private boolean admit(UUID actor) {
        long now = System.nanoTime();
        cooldowns.entrySet().removeIf(entry -> now - entry.getValue() >= 0);
        if (cooldowns.containsKey(actor) || cooldowns.size() >= 256 || !pending.add(actor)) return false;
        cooldowns.put(actor, now + TimeUnit.SECONDS.toNanos(2));
        return true;
    }
    private void deliver(Player original, Runnable response) {
        if (closed) return;
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!closed && original.isOnline() && Bukkit.getPlayer(original.getUniqueId()) == original
                        && original.hasPermission("prosperitynpc.use")) response.run();
            });
        } catch (IllegalStateException shuttingDown) { /* Durable receipts remain queryable after restart. */ }
    }
    private void logFailure(String action, String receipt, Exception error) {
        plugin.getLogger().warning("Purchase " + action + " ref=" + receipt + " failed: " + error.getClass().getSimpleName());
    }
    private static void report(Player player, PurchaseRepository.Receipt receipt) {
        String summary = switch (receipt.state()) {
            case WAITING -> "Payment pending; no benefit has been applied.";
            case APPLIED -> "Payment confirmed and benefit applied.";
            case DECLINED -> "Payment declined; no benefit applied. A new request can be made.";
            case REVIEW_REQUIRED -> "This request needs staff review. Do not retry or assume a refund.";
        };
        send(player, receipt.state() == PurchaseRepository.State.APPLIED ? NamedTextColor.GREEN : NamedTextColor.GOLD,
                summary + " " + Money.format(receipt.quotedCents()));
        send(player, NamedTextColor.GRAY, "/property receipt " + receipt.request().requestId());
    }
    /** Item repair receipt: the incident, the items handed in, the time, no money moved, and the state. */
    static java.util.List<String> itemLines(ItemRepairRepository.Stored stored) {
        var q = stored.quote();
        String state = switch (stored.state()) {
            case APPLIED -> "Repair done.";
            case RETURN_ISSUED -> "The home changed, so the items were given back.";
            case CANCELLED -> "Cancelled. Nothing was taken.";
            case RECONCILED_NOT_COLLECTED -> "Checked by staff. Nothing was taken.";
            case AMBIGUOUS -> "Held for a staff check. Do not try again.";
            case INTENT -> "Still being checked.";
        };
        String when = stored.updatedAt() == null ? "" : " " + java.time.format.DateTimeFormatter.ofPattern("MMM d HH:mm 'UTC'", java.util.Locale.US)
                .withZone(java.time.ZoneOffset.UTC).format(stored.updatedAt()) + ".";
        return java.util.List.of("Item repair for problem #" + q.repair().incidentId() + "." + when,
                "Items: " + ItemRepairRecipes.describe(q.lines()) + ". No money moved.", state);
    }
    public static void unavailable(Player player) {
        send(player, NamedTextColor.RED, "Receipts are unavailable right now.");
    }
    private static void busy(Player player) { send(player, NamedTextColor.GRAY, "Please wait for the current request."); }
    private static void send(Player player, NamedTextColor color, String message) { player.sendMessage(Component.text(message, color)); }
    private static void requireMain() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Purchase admission requires the main thread"); }
    @Override public void close() {
        closed = true; worker.shutdownNow(); pending.clear(); cooldowns.clear();
    }
}

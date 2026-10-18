package com.prosperitysmp.npc.command;

import com.prosperitysmp.npc.ProsperityNpcPlugin;
import com.prosperitysmp.npc.charge.BillText;
import com.prosperitysmp.npc.charge.BillingRuntime;
import com.prosperitysmp.npc.charge.ChargeInvoiceRepository;
import com.prosperitysmp.npc.api.OwnershipToken;
import com.prosperitysmp.npc.economy.ItemRepairCopy;
import com.prosperitysmp.npc.economy.PurchaseRequest;
import com.prosperitysmp.npc.economy.PurchaseService;
import com.prosperitysmp.npc.formulas.UpgradeType;
import com.prosperitysmp.npc.property.OwnerAway;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.util.Money;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.DateTimeException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/** Player-facing {@code /property status|rent|upgrade|maintain}. Operates on the property you stand in. */
public final class PropertyCommand implements TabExecutor {

    private final ProsperityNpcPlugin plugin;
    private final Set<UUID> pendingBillReads = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> billCooldowns = new HashMap<>();

    public PropertyCommand(ProsperityNpcPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, NamedTextColor.RED, "Only players can use /property.");
            return true;
        }
        if (!player.hasPermission("prosperitynpc.use")) {
            send(player, NamedTextColor.RED, "You don't have permission to use this command.");
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("autocare")) {
            doAutoCare(player, args);
            return true;
        }
        if (sub.equals("rent")) {
            doCoordinatedRent(player, args);
            return true;
        }
        if (sub.equals("upgrade") || sub.equals("maintain")) {
            doConfirmedPurchase(player, args, sub.equals("upgrade"));
            return true;
        }
        if (sub.equals("pause") || sub.equals("resume")) {
            doLifecycle(player, args, sub.equals("resume"));
            return true;
        }
        if (sub.equals("utility")) {
            doUtility(player, args);
            return true;
        }
        if (sub.equals("bills") && billsEnabled()) {
            doBills(player, args);
            return true;
        }
        if (sub.equals("repair")) {
            doRepair(player, args);
            return true;
        }
        if (sub.equals("insure") && insureEnabled()) {
            doInsure(player, args);
            return true;
        }
        if (sub.equals("status") || sub.equals("close")) {
            if (plugin.ownerDesk() == null) {
                send(player, NamedTextColor.GRAY, "The owner desk is unavailable.");
            } else if (sub.equals("close") && args.length == 1) {
                plugin.ownerDesk().dismiss(player);
            } else if (sub.equals("status") && (args.length <= 1
                    || (args.length == 2 && args[1].equalsIgnoreCase("text")))) {
                plugin.ownerDesk().open(player, args.length == 2, false);
            } else send(player, NamedTextColor.RED, "Usage: /property [status [text]|close]");
            return true;
        }
        if (sub.equals("rating")) {
            if (args.length != 1 || plugin.publicRating() == null) {
                send(player, NamedTextColor.GRAY, args.length != 1 ? "Usage: /property rating" : "Property ratings are unavailable.");
            } else plugin.publicRating().show(player);
            return true;
        }
        if (sub.equals("receipt")) {
            if (plugin.purchaseService() == null) {
                PurchaseService.unavailable(player);
                return true;
            }
            if (args.length != 2 || !args[1].matches("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")) {
                send(player, NamedTextColor.RED, "Usage: /property receipt <request-uuid>");
                return true;
            }
            plugin.purchaseService().showReceipt(player, UUID.fromString(args[1]));
            return true;
        }
        send(player, NamedTextColor.RED, "Usage: /property <status [text]|close|pause|resume|autocare|utility|repair|"
                + (insureEnabled() ? "insure|" : "") + (billsEnabled() ? "bills|" : "") + "rent|upgrade|maintain|receipt|rating>");
        return true;
    }

    /** Configured bills are on (bills.enabled with a loaded catalogue). Off: the command and its hints are hidden. */
    private boolean billsEnabled() {
        return plugin.billing() != null && plugin.billing().enabledNow();
    }

    /** /property bills [pay <bill> <maximum-dollars>]: the bills of the home the owner stands in. */
    private void doBills(Player player, String[] args) {
        boolean pay = args.length == 4 && args[1].equalsIgnoreCase("pay");
        boolean older = args.length == 3 && args[1].equalsIgnoreCase("before");
        if (!(args.length == 1 || pay || older)) {
            send(player, NamedTextColor.GOLD, "Usage: /property bills [before <YYYY-MM-DD>|pay <bill> <maximum-dollars>]");
            send(player, NamedTextColor.GRAY, BillText.HOW_BILLS_WORK);
            return;
        }
        LocalDate before = null;
        if (older) {
            try {
                if (!args[2].matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException();
                before = LocalDate.parse(args[2]);
                if (before.getYear() < 1) throw new IllegalArgumentException();
            } catch (DateTimeException | IllegalArgumentException invalid) {
                send(player, NamedTextColor.RED, "Enter an economy day like 2026-10-17.");
                return;
            }
        }
        long maximum = 0;
        if (pay) {
            try {
                maximum = Money.parseDollarsToCents(args[3]);
            } catch (IllegalArgumentException | ArithmeticException invalid) {
                maximum = 0;
            }
            if (maximum <= 0) {
                send(player, NamedTextColor.RED, "Enter a positive maximum payment in dollars.");
                return;
            }
        }
        if (plugin.utilityPayments() == null || plugin.propertyBackend() == null || plugin.propertyService() == null) {
            send(player, NamedTextColor.GRAY, "Bills are unavailable.");
            return;
        }
        var authority = plugin.propertyBackend().ownerReadAt(player.getLocation(), player.getUniqueId());
        if (authority.isEmpty()) {
            send(player, NamedTextColor.RED, "Stand in one property you own outright.");
            return;
        }
        var property = plugin.propertyService().findByWorldRegion(authority.get().worldId(), authority.get().regionId());
        if (property.isEmpty() || !property.get().enabled() || !property.get().ownerUuid().equals(player.getUniqueId())) {
            send(player, NamedTextColor.GRAY, "This property is not available in the NPC economy.");
            return;
        }
        if (pay && plugin.ownerAway().isAway(property.get())) {
            send(player, NamedTextColor.GRAY, OwnerAway.PAY_NOTE);
            return;
        }
        var billing = plugin.billing();
        UUID home = property.get().propertyId();
        UUID actor = player.getUniqueId();
        long now = System.nanoTime();
        billCooldowns.entrySet().removeIf(entry -> now - entry.getValue() >= 0);
        if (billCooldowns.containsKey(actor) || billCooldowns.size() >= 256 || !pendingBillReads.add(actor)) {
            send(player, NamedTextColor.GRAY, "Please wait before checking bills again.");
            return;
        }
        billCooldowns.put(actor, now + TimeUnit.SECONDS.toNanos(2));
        final long limit = maximum;
        String billId = pay ? args[2] : null;
        UUID exactBill = billId != null && billId.matches("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
                ? UUID.fromString(billId) : null;
        var beforeDay = before;
        var day = billing.today();
        BooleanSupplier authorized = () -> currentBillOwner(player, property.get(), authority.get(), billing)
                && day.equals(billing.today());
        try {
            CompletableFuture.supplyAsync(() -> {
                try {
                    if (exactBill != null) return new ChargeInvoiceRepository.OwnerView(
                            billing.invoices().ownerInvoice(home, actor, property.get().ownershipEpoch(), exactBill).stream().toList(), Map.of());
                    return billing.invoices().ownerView(home, actor, property.get().ownershipEpoch(), beforeDay);
                }
                catch (SQLException failure) { throw new IllegalStateException("Bills unreadable", failure); }
            }, billing.worker()).whenComplete((view, failure) -> {
                try {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (!authorized.getAsBoolean()) return;
                        if (failure != null) {
                            send(player, NamedTextColor.GRAY, "Bills could not be read right now. Nothing changed.");
                            return;
                        }
                        if (!pay) {
                            // With on-unpaid: hold-rent, a bill that is pausing this home's rent says so.
                            for (String line : BillText.list(view.withHoldRent(billing.holdsRentNow()), day))
                                send(player, NamedTextColor.GRAY, line);
                            for (var bill : view.invoices()) if (BillText.payable(bill, view.payments().get(bill.invoiceId()), day))
                                player.sendMessage(Component.text("Pay bill from " + bill.day() + " up to " + Money.format(bill.totalCents()),
                                        NamedTextColor.GOLD).clickEvent(ClickEvent.suggestCommand(BillText.payCommand(bill))));
                            if (view.hasOlder()) {
                                String command = "/property bills before " + view.invoices().getLast().day();
                                player.sendMessage(Component.text("Older bills: " + command, NamedTextColor.GRAY)
                                        .clickEvent(ClickEvent.suggestCommand(command)));
                            }
                            return;
                        }
                        var invoice = BillText.match(view.invoices(), billId);
                        if (invoice.isEmpty()) {
                            send(player, NamedTextColor.RED, "No bill of this home starts with that id. Use /property bills to see them.");
                            return;
                        }
                        boolean accepted = plugin.utilityPayments().requestBill(home, actor, invoice.get().invoiceId(), limit,
                                authorized, message -> send(player, NamedTextColor.GRAY, message));
                        send(player, accepted ? NamedTextColor.GRAY : NamedTextColor.RED, accepted
                                ? "Checking the bill and payment authorization. This is not a payment receipt."
                                : "Payments are busy or unavailable. No payment was started by this command.");
                    });
                } catch (IllegalStateException shutdown) {
                    // Saved bills remain readable after restart.
                } finally { pendingBillReads.remove(actor); }
            });
        } catch (RejectedExecutionException full) {
            pendingBillReads.remove(actor);
            send(player, NamedTextColor.GRAY, "Bills are busy right now. Nothing changed.");
        }
    }

    private boolean currentBillOwner(Player player, Property property, OwnershipToken authority, BillingRuntime billing) {
        if (!plugin.isEnabled() || !billPlayerCurrent(player, plugin.getServer()::getPlayer)
                || plugin.billing() != billing || !billing.enabledNow() || plugin.utilityPayments() == null
                || plugin.propertyBackend() == null || plugin.propertyService() == null) return false;
        var live = plugin.propertyBackend().ownerReadAt(player.getLocation(), player.getUniqueId());
        var current = plugin.propertyService().findByWorldRegion(property.worldUuid(), property.regionId());
        return live.isPresent() && current.isPresent()
                && sameBillOwnership(property, current.get(), authority, live.get());
    }

    static boolean billPlayerCurrent(Player player, Function<UUID, Player> online) {
        return player.isOnline() && !player.isDead() && player.hasPermission("prosperitynpc.use")
                && online.apply(player.getUniqueId()) == player;
    }

    static boolean sameBillOwnership(Property expected, Property current, OwnershipToken admitted, OwnershipToken live) {
        return current.enabled() && expected.propertyId().equals(current.propertyId())
                && expected.worldUuid().equals(current.worldUuid()) && expected.regionId().equals(current.regionId())
                && expected.ownerUuid().equals(current.ownerUuid()) && expected.ownershipEpoch() == current.ownershipEpoch()
                && expected.worldUuid().equals(admitted.worldId()) && expected.regionId().equals(admitted.regionId())
                && expected.ownerUuid().equals(admitted.ownerId()) && admitted.sameIncarnation(live);
    }

    private void doUtility(Player player, String[] args) {
        if (args.length != 3 || !args[1].equalsIgnoreCase("pay")) {
            send(player, NamedTextColor.GOLD, "Usage: /property utility pay <maximum-dollars>");
            send(player, NamedTextColor.GRAY, "Pays today's utility bill only, up to your limit. No recurring authorization.");
            return;
        }
        long maximum;
        try {
            maximum = Money.parseDollarsToCents(args[2]);
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            maximum = 0;
        }
        if (maximum <= 0) {
            send(player, NamedTextColor.RED, "Enter a positive maximum payment in dollars.");
            return;
        }
        if (plugin.utilityPayments() == null || plugin.propertyBackend() == null || plugin.propertyService() == null) {
            send(player, NamedTextColor.GRAY, "Utility payments are unavailable.");
            return;
        }
        var authority = plugin.propertyBackend().ownerReadAt(player.getLocation(), player.getUniqueId());
        if (authority.isEmpty()) {
            send(player, NamedTextColor.RED, "Stand in one property you own outright.");
            return;
        }
        var property = plugin.propertyService().findByWorldRegion(authority.get().worldId(), authority.get().regionId());
        if (property.isEmpty() || !property.get().enabled() || !property.get().ownerUuid().equals(player.getUniqueId())) {
            send(player, NamedTextColor.GRAY, "This property is not available in the NPC economy.");
            return;
        }
        if (plugin.ownerAway().isAway(property.get())) {
            send(player, NamedTextColor.GRAY, OwnerAway.PAY_NOTE);
            return;
        }
        boolean accepted = plugin.utilityPayments().request(property.get().propertyId(), player.getUniqueId(), maximum,
                () -> player.isOnline() && !player.isDead() && plugin.getServer().getPlayer(player.getUniqueId()) == player
                        && player.hasPermission("prosperitynpc.use"),
                message -> send(player, NamedTextColor.GRAY, message));
        send(player, accepted ? NamedTextColor.GRAY : NamedTextColor.RED, accepted
                ? "Checking today's bill and payment authorization. This is not a payment receipt."
                : "Utility payments are busy or unavailable. No payment was started by this command.");
    }

    private void doAutoCare(Player player, String[] args) {
        boolean on = args.length == 3 && args[1].equalsIgnoreCase("on");
        boolean off = args.length == 2 && args[1].equalsIgnoreCase("off");
        boolean status = args.length <= 1 || (args.length == 2 && args[1].equalsIgnoreCase("status"));
        long ceiling = on ? Money.parseDollarsToCents(args[2]) : 0;
        if ((!on && !off && !status) || (on && ceiling <= 0)) {
            send(player, NamedTextColor.GOLD, "Usage: /property autocare <status|off|on <maximum-dollars-per-day>>");
            return;
        }
        if (plugin.autoCare() == null || plugin.propertyBackend() == null || plugin.propertyService() == null) {
            send(player, NamedTextColor.GRAY, "Auto-care is unavailable.");
            return;
        }
        var owner = plugin.propertyBackend().ownerReadAt(player.getLocation(), player.getUniqueId()).orElse(null);
        var property = owner == null ? Optional.<Property>empty()
                : plugin.propertyService().findByWorldRegion(owner.worldId(), owner.regionId());
        if (property.isEmpty() || !player.getUniqueId().equals(property.get().ownerUuid())) {
            send(player, NamedTextColor.RED, "Stand in your enrolled property.");
            return;
        }
        plugin.autoCare().configure(player, property.get(), status ? null : on, ceiling);
    }

    private void doConfirmedPurchase(Player player, String[] args, boolean upgrade) {
        if (args.length != (upgrade ? 4 : 2)) {
            send(player, NamedTextColor.GOLD, upgrade
                    ? "Usage: /property upgrade <advertising|facilities> <target-level> <maximum-dollars>"
                    : "Usage: /property maintain <maximum-dollars> (cost $10.00)");
            return;
        }
        UpgradeType type = null;
        Integer level = null;
        long maximum;
        try {
            maximum = Money.parseDollarsToCents(args[upgrade ? 3 : 1]);
            if (upgrade) {
                type = UpgradeType.fromKey(args[1]);
                level = Integer.valueOf(args[2]);
                if (type == null || level < 1 || level > UpgradeType.MAX_LEVEL) throw new IllegalArgumentException();
            }
            if (maximum <= 0) throw new IllegalArgumentException();
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            send(player, NamedTextColor.RED, "Enter a valid upgrade level and maximum payment.");
            return;
        }
        if (plugin.utilityPayments() == null || plugin.propertyBackend() == null || plugin.propertyService() == null) {
            send(player, NamedTextColor.RED, "Property payments are unavailable.");
            return;
        }
        var authority = plugin.propertyBackend().ownerReadAt(player.getLocation(), player.getUniqueId()).orElse(null);
        var property = authority == null ? Optional.<Property>empty()
                : plugin.propertyService().findByWorldRegion(authority.worldId(), authority.regionId());
        if (property.isEmpty() || !property.get().enabled() || !player.getUniqueId().equals(property.get().ownerUuid())) {
            send(player, NamedTextColor.RED, "Stand in your enrolled property.");
            return;
        }
        boolean accepted = plugin.utilityPayments().requestPurchase(property.get().propertyId(), player.getUniqueId(),
                upgrade ? PurchaseRequest.Kind.UPGRADE
                        : PurchaseRequest.Kind.MAINTAIN,
                type, level, maximum, () -> player.isOnline() && !player.isDead()
                        && plugin.getServer().getPlayer(player.getUniqueId()) == player
                        && player.hasPermission("prosperitynpc.use")
                        && plugin.propertyBackend().ownerReadAt(player.getLocation(), player.getUniqueId())
                            .filter(a -> a.worldId().equals(authority.worldId()) && a.regionId().equals(authority.regionId())).isPresent(),
                message -> send(player, NamedTextColor.GRAY, message));
        send(player, accepted ? NamedTextColor.GRAY : NamedTextColor.RED, accepted
                ? "Checking the exact benefit and your payment limit. Wait for the verified result."
                : "Property payments are busy or unavailable. No payment was started.");
    }

    private void doCoordinatedRent(Player player, String[] args) {
        if (args.length != 2 || plugin.propertyService() == null || plugin.propertyBackend() == null) {
            send(player, NamedTextColor.GOLD, "Usage: /property rent <dollars-per-day>");
            return;
        }
        long cents;
        try {
            cents = Money.parseDollarsToCents(args[1]);
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            cents = 0;
        }
        var owner = plugin.propertyBackend().ownerReadAt(player.getLocation(), player.getUniqueId()).orElse(null);
        if (cents <= 0 || owner == null) {
            send(player, NamedTextColor.RED, "Stand in your property and enter a positive rent.");
            return;
        }
        boolean accepted = plugin.propertyService().setRentCoordinated(owner.worldId(), owner.regionId(), player.getUniqueId(), cents,
                () -> player.isOnline() && !player.isDead() && plugin.getServer().getPlayer(player.getUniqueId()) == player
                        && player.hasPermission("prosperitynpc.use")
                        && plugin.propertyBackend().ownerReadAt(player.getLocation(), player.getUniqueId())
                                .filter(a -> a.worldId().equals(owner.worldId()) && a.regionId().equals(owner.regionId())).isPresent(),
                message -> send(player, NamedTextColor.GRAY, message));
        send(player, accepted ? NamedTextColor.GRAY : NamedTextColor.RED, accepted ? "Checking your property and rent range..."
                : "Rent change unavailable, or outside 50%-150% of reference rent. Nothing dispatched.");
    }

    private boolean insureEnabled() {
        return plugin.getConfig().getBoolean("ux.insure-command", false);
    }

    /** Typed twin of the desk's insurance button: the maximum is a ceiling, the premium is quoted by the server. */
    private void doInsure(Player player, String[] args) {
        if (args.length != 2) {
            send(player, NamedTextColor.GOLD, "Usage: /property insure <maximum-dollars>");
            send(player, NamedTextColor.GRAY,
                    "Seven economy days of cover for one new incident. Prepaid, nonrefundable. Your limit must cover the premium.");
            return;
        }
        long maximum;
        try {
            maximum = Money.parseDollarsToCents(args[1]);
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            maximum = 0;
        }
        if (maximum <= 0) {
            send(player, NamedTextColor.RED, "Enter a positive maximum payment in dollars.");
            return;
        }
        if (plugin.utilityPayments() == null || plugin.propertyBackend() == null || plugin.propertyService() == null) {
            send(player, NamedTextColor.RED, "Property payments are unavailable.");
            return;
        }
        var authority = plugin.propertyBackend().ownerReadAt(player.getLocation(), player.getUniqueId());
        var property = authority.flatMap(a -> plugin.propertyService().findByWorldRegion(a.worldId(), a.regionId()));
        if (property.isEmpty() || !property.get().enabled() || !player.getUniqueId().equals(property.get().ownerUuid())) {
            send(player, NamedTextColor.RED, "Stand in your enrolled property.");
            return;
        }
        var owner = authority.get();
        boolean accepted = plugin.utilityPayments().requestInsurance(property.get().propertyId(), player.getUniqueId(), maximum,
                () -> player.isOnline() && !player.isDead() && plugin.getServer().getPlayer(player.getUniqueId()) == player
                        && player.hasPermission("prosperitynpc.use")
                        && plugin.propertyBackend().ownerReadAt(player.getLocation(), player.getUniqueId())
                            .filter(a -> a.worldId().equals(owner.worldId()) && a.regionId().equals(owner.regionId())).isPresent(),
                message -> send(player, NamedTextColor.GRAY, message));
        send(player, accepted ? NamedTextColor.GRAY : NamedTextColor.RED, accepted
                ? "Checking the insurance premium and your limit. Wait for the verified result."
                : "Property payments are busy or unavailable. No payment was started.");
    }

    private void doRepair(Player player, String[] args) {
        // With item-repairs off and never used there is no runtime, so "returns" falls through to the usage reply.
        if (args.length == 2 && args[1].equalsIgnoreCase("returns")
                && (plugin.itemRepairSettings().enabled() || plugin.itemRepairsRunning())) {
            doRepairReturns(player);
            return;
        }
        if (args.length == 3 && args[2].equalsIgnoreCase("items")) {
            doItemRepair(player, args[1]);
            return;
        }
        if (args.length != 3) {
            send(player, NamedTextColor.GOLD, "Usage: /property repair <incident-id> <maximum-dollars>");
            return;
        }
        long incident, maximum;
        try {
            incident = Long.parseLong(args[1]);
            maximum = Money.parseDollarsToCents(args[2]);
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            send(player, NamedTextColor.RED, "Enter a valid incident number and maximum payment.");
            return;
        }
        if (incident <= 0 || maximum <= 0 || plugin.utilityPayments() == null || plugin.propertyBackend() == null
                || plugin.propertyService() == null) {
            send(player, NamedTextColor.RED, "Repair payments are unavailable, or the amount is invalid.");
            return;
        }
        var authority = plugin.propertyBackend().ownerReadAt(player.getLocation(), player.getUniqueId());
        var property = authority.flatMap(a -> plugin.propertyService().findByWorldRegion(a.worldId(), a.regionId()));
        if (property.isEmpty() || !property.get().enabled() || !player.getUniqueId().equals(property.get().ownerUuid())) {
            send(player, NamedTextColor.RED, "Stand in your enrolled property to repair it.");
            return;
        }
        boolean accepted = plugin.utilityPayments().requestRepair(property.get().propertyId(), player.getUniqueId(), incident, maximum,
                () -> player.isOnline() && !player.isDead() && plugin.getServer().getPlayer(player.getUniqueId()) == player
                        && player.hasPermission("prosperitynpc.use"),
                message -> send(player, NamedTextColor.GRAY, message));
        send(player, accepted ? NamedTextColor.GRAY : NamedTextColor.RED, accepted
                ? "Checking the repair and payment limit. Wait for the verified repair result."
                : "Repair payments are busy or unavailable. No payment was started by this command.");
    }

    /** item-repairs: hand in the listed plain items for one open problem, while standing in the home. */
    private void doItemRepair(Player player, String id) {
        if (!plugin.itemRepairSettings().enabled()) {
            send(player, NamedTextColor.GRAY, ItemRepairCopy.OFF);
            return;
        }
        long incident;
        try {
            incident = Long.parseLong(id);
        } catch (NumberFormatException invalid) {
            incident = 0;
        }
        if (incident <= 0) {
            send(player, NamedTextColor.GOLD, ItemRepairCopy.USAGE);
            return;
        }
        var runtime = plugin.itemRepairs();
        if (runtime == null) {
            send(player, NamedTextColor.GRAY, ItemRepairCopy.UNAVAILABLE);
            return;
        }
        var owner = plugin.propertyBackend() == null ? null
                : plugin.propertyBackend().ownerReadAt(player.getLocation(), player.getUniqueId()).orElse(null);
        runtime.requestTyped(player, incident, () -> player.isOnline() && !player.isDead()
                        && plugin.getServer().getPlayer(player.getUniqueId()) == player && player.hasPermission("prosperitynpc.use")
                        && owner != null && plugin.propertyBackend().ownerReadAt(player.getLocation(), player.getUniqueId())
                            .filter(owner::sameIncarnation).isPresent(),
                message -> send(player, NamedTextColor.GRAY, message));
    }

    /** item-repairs: collect items given back after a hand-in could not finish. Works with item-repairs off. */
    private void doRepairReturns(Player player) {
        var runtime = plugin.itemRepairReturns();
        if (runtime == null) {
            send(player, NamedTextColor.GRAY, ItemRepairCopy.UNAVAILABLE);
            return;
        }
        runtime.claimReturns(player, message -> send(player, NamedTextColor.GRAY, message));
    }

    private void doLifecycle(Player player, String[] args, boolean resume) {
        if (args.length != 1 || plugin.propertyBackend() == null || plugin.propertyService() == null) {
            send(player, NamedTextColor.RED, "Use /property pause or /property resume while inside your property.");
            return;
        }
        var owner = plugin.propertyBackend().ownerReadAt(player.getLocation(), player.getUniqueId()).orElse(null);
        if (owner == null) {
            send(player, NamedTextColor.RED, "Stand inside a property you solely own.");
            return;
        }
        BooleanSupplier authorized = () -> player.isOnline()
                && player.hasPermission("prosperitynpc.use")
                && plugin.propertyBackend().ownerReadAt(player.getLocation(), player.getUniqueId())
                    .filter(current -> current.worldId().equals(owner.worldId())
                            && current.regionId().equals(owner.regionId())).isPresent();
        Consumer<String> reply = message -> {
            if (player.isOnline()) send(player, NamedTextColor.YELLOW, message);
        };
        boolean accepted = resume
                ? plugin.propertyService().resumeCoordinated(owner.worldId(), owner.regionId(), authorized, reply)
                : plugin.propertyService().unenrollCoordinated(owner.worldId(), owner.regionId(), authorized, reply);
        send(player, accepted ? NamedTextColor.YELLOW : NamedTextColor.RED,
                accepted ? "Checking property and ownership..." : "Property unavailable or busy; nothing changed.");
    }

    private static void send(CommandSender s, NamedTextColor c, String m) {
        s.sendMessage(Component.text(m, c));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player) || !sender.hasPermission("prosperitynpc.use")) return List.of();
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("status", "close", "pause", "resume", "autocare", "utility", "repair", "insure", "bills",
                    "rent", "upgrade", "maintain", "receipt", "rating")) {
                if (s.equals("insure") && !insureEnabled()) continue;
                if (s.equals("bills") && !billsEnabled()) continue;
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("autocare")) {
            for (String value : List.of("status", "off", "on")) if (value.startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(value);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("status") && "text".startsWith(args[1].toLowerCase(Locale.ROOT))) {
            out.add("text");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("bills") && billsEnabled()) {
            for (String value : List.of("pay", "before")) if (value.startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(value);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("utility")
                && "pay".startsWith(args[1].toLowerCase(Locale.ROOT))) {
            out.add("pay");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("upgrade")) {
            out.add("advertising");
            out.add("facilities");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("repair")
                && (plugin.itemRepairSettings().enabled() || plugin.itemRepairsRunning())
                && "returns".startsWith(args[1].toLowerCase(Locale.ROOT))) {
            out.add("returns");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("repair") && plugin.itemRepairSettings().enabled()
                && "items".startsWith(args[2].toLowerCase(Locale.ROOT))) {
            out.add("items");
        }
        return out;
    }
}

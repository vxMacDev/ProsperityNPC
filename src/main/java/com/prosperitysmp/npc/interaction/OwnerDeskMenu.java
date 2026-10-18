package com.prosperitysmp.npc.interaction;

import com.prosperitysmp.npc.economy.PurchasePrices;
import com.prosperitysmp.npc.economy.UtilityPrices;

import com.prosperitysmp.npc.economy.ItemRepairCopy;
import com.prosperitysmp.npc.economy.ItemRepairRuntime;
import com.prosperitysmp.npc.charge.BillText;
import com.prosperitysmp.npc.charge.ChargeInvoiceRepository;
import com.prosperitysmp.npc.formulas.PropertyFormulas;
import com.prosperitysmp.npc.formulas.UpgradeType;
import com.prosperitysmp.npc.market.PremiumHousingEligibility;
import com.prosperitysmp.npc.property.OwnerDeskData;
import com.prosperitysmp.npc.util.Money;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Consumer;

/** Cosmetic inventory items are never transferable; every action returns to owner authorization. */
final class OwnerDeskMenu implements Listener {
    static final long EVICTION_CENTS = com.prosperitysmp.npc.formulas.PropertyFormulas.EVICTION_CENTS;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("MMM d HH:mm:ss 'UTC'", Locale.US).withZone(ZoneOffset.UTC);
    private static final class View implements InventoryHolder {
        final OwnerDeskSessions.Ticket ticket;
        Inventory inventory;
        InventoryOpenEvent openingEvent;
        boolean interrupted;
        View(OwnerDeskSessions.Ticket ticket) { this.ticket = ticket; }
        @Override public Inventory getInventory() { return inventory; }
    }
    private final OwnerDeskController controller;
    private final BooleanSupplier advertisingRank; // read live, so /npc reload changes it
    private final boolean pauseControls;
    private final PurchasePrices prices;
    private final UtilityPrices utilityPrices;
    private View opening;
    OwnerDeskMenu(OwnerDeskController controller) { this(controller, () -> false); }
    OwnerDeskMenu(OwnerDeskController controller, BooleanSupplier advertisingRank) { this(controller, advertisingRank, false); }
    OwnerDeskMenu(OwnerDeskController controller, BooleanSupplier advertisingRank, boolean pauseControls) {
        this(controller, advertisingRank, pauseControls, PurchasePrices.DEFAULT);
    }
    OwnerDeskMenu(OwnerDeskController controller, BooleanSupplier advertisingRank, boolean pauseControls,
                  PurchasePrices prices) {
        this(controller, advertisingRank, pauseControls, prices, UtilityPrices.DEFAULT);
    }
    OwnerDeskMenu(OwnerDeskController controller, BooleanSupplier advertisingRank, boolean pauseControls,
                  PurchasePrices prices, UtilityPrices utilityPrices) {
        this.controller = controller;
        this.advertisingRank = advertisingRank;
        this.pauseControls = pauseControls;
        this.prices = Objects.requireNonNull(prices);
        this.utilityPrices = Objects.requireNonNull(utilityPrices);
    }
    static final String PAUSE_LABEL = "Pause this property";
    static final String PAUSE_CONFIRM = "Pause: leave the NPC economy";
    static final String RESUME_LABEL = "Resume this property";
    static List<String> pauseLines() {
        return List.of("Takes it out of the NPC economy until you resume.", "Tenants, incidents and history are kept.",
                "Asks you to confirm first.");
    }
    static String pauseBody(String region) {
        return "Pause " + region + "? NPC rent and bills stop until you resume. Tenants, incidents and history are kept.";
    }
    static String pausedBody(String region) {
        return region + " is paused. Resume to put it back in the NPC economy. Tenants, incidents and history were kept. "
                + "You can also type /property resume.";
    }
    static List<String> advertisingLines(boolean enabled, OwnerDeskData data) {
        if (!enabled) return List.of("Click authorizes the displayed level and cost.");
        int level = data.upgrades().stream().filter(u -> u.type().equals("ADVERTISING")
                && !u.disabled()).mapToInt(OwnerDeskData.Upgrade::level).max().orElse(0);
        boolean off = data.upgrades().stream().anyMatch(u -> u.type().equals("ADVERTISING") && u.disabled());
        return List.of(off ? "Advertising: switched off until repaired." : level > 0 ? "Advertising: matched earlier"
                : "Advertising: not active yet",
                "Residents look at advertised homes first. Rent and score do not change.",
                "Click authorizes the displayed level and cost.");
    }

    record Room(int number, int inventorySlot, OwnerDeskData.Resident resident) { }
    static List<Room> rooms(OwnerDeskData data) {
        var rooms = new ArrayList<Room>();
        for (int slot = 0; slot < data.property().capacity(); slot++) {
            final int index = slot;
            var resident = data.residents().stream().filter(r -> r.slot() == index).findFirst().orElse(null);
            rooms.add(new Room(slot + 1, 10 + slot * 2, resident));
        }
        return List.copyOf(rooms);
    }

    boolean show(Player player, OwnerDeskSessions.Ticket ticket, OwnerDeskData data, BooleanSupplier authorized) {
        View view = new View(ticket);
        String id = data.property().regionId();
        view.inventory = Bukkit.createInventory(view, 45, Component.text("Property | " + shorten(id, 20)));
        return OwnerDeskPublication.publish(() -> openEmpty(player, view), authorized,
                () -> populate(view.inventory, data), () -> discard(player, view));
    }

    private void populate(Inventory inventory, OwnerDeskData data) {
        var p = data.property();
        inventory.setItem(4, item(Material.OAK_DOOR, p.regionId(), List.of(label(p.classKey()) + " | " + p.marketId(),
                "Residents: " + data.residents().size() + " / " + p.capacity(), "As of " + STAMP.format(data.capturedAt()))));
        for (var room : rooms(data)) {
            if (room.resident() != null) {
                var r = room.resident();
                inventory.setItem(room.inventorySlot(), item(Material.WRITABLE_BOOK, r.name(),
                        List.of("Room " + room.number() + " | " + label(r.tier()),
                        "Mood: " + label(r.mood()), "Satisfaction: " + r.satisfaction() + " / 100", "Manage tenancy")));
            } else inventory.setItem(room.inventorySlot(), item(Material.LIGHT_GRAY_BED, "Room " + room.number() + " | Vacant",
                    List.of("No resident assigned.")));
        }
        inventory.setItem(20, item(Material.BRICKS, "Condition: " + p.conditionScore() + " / 100",
                rentLines(data, List.of("Asking rent: " + Money.format(p.askingRentCents()) + " / resident / day",
                        "Asking rent is not settled income."))));
        List<String> issues = issueLines(data, controller == null ? List.of() : controller.itemRepairLines(data.property().ownerUuid()));
        inventory.setItem(22, item(data.issues().isEmpty() ? Material.LANTERN : Material.ANVIL,
                data.issues().isEmpty() ? "No open service issues" : repairLabel(data), issues));
        inventory.setItem(24, item(Material.CRAFTING_TABLE, purchaseLabel(data, 24, prices),
                List.of("Click authorizes the displayed level and cost.")));
        inventory.setItem(26, item(Material.BELL, purchaseLabel(data, 26, prices), advertisingLines(advertisingRank.getAsBoolean(), data)));
        inventory.setItem(28, item(Material.BRUSH, purchaseLabel(data, 28, prices), List.of("Click authorizes " + Money.format(prices.maintenanceCents()) + "; once per economy day.")));
        inventory.setItem(30, item(Material.SHIELD, insuranceLabel(data), insuranceLines(data)));
        inventory.setItem(31, item(Material.PAPER, "Your settled statement", statementAndLinkLines(data)));
        inventory.setItem(33, item(Material.BOOK, "Service record and qualification", housingLines(data)));
        if (pauseControls) inventory.setItem(32, item(Material.RED_DYE, PAUSE_LABEL, pauseLines()));
        inventory.setItem(34, item(Material.CLOCK, "Auto-care settings",
                List.of("Optional daily spending ceiling.", "Utilities, one cash repair and condition upkeep.")));
        if (controller != null && controller.billsOn()) inventory.setItem(35, item(Material.PAPER, "Configured bills",
                List.of("View bill lines and payment receipts.", "Choose a bill before confirming a payment.")));
        inventory.setItem(36, item(Material.CLOCK, "Refresh", List.of()));
        inventory.setItem(38, item(Material.WRITABLE_BOOK, "Read in chat", List.of()));
        inventory.setItem(40, item(Material.BARRIER, "Close", List.of()));
        inventory.setItem(42, item(Material.GOLD_NUGGET, utilityLabel(data, utilityPrices),
                List.of("Today's bill only; click authorizes this maximum.", "A higher bill is refused, never charged.",
                        "Unavailable while the payment gate is held.")));
        inventory.setItem(44, item(Material.PAPER, "Rent settings", rentLines(data,
                List.of("/property rent <dollars-per-day>", "50%-150% of the reference rent."))));
    }
    static String billEntryLabel(OwnerDeskController.BillPage page, ChargeInvoiceRepository.Invoice invoice) {
        return invoice.day() + " | " + Money.format(invoice.totalCents()) + " | "
                + BillText.state(invoice, page.view().payments().get(invoice.invoiceId()), page.day());
    }
    static String billPageBody(OwnerDeskController.BillPage page) {
        StringBuilder paused = new StringBuilder();
        // With on-unpaid: hold-rent, each bill on this page that is pausing the home's rent says so here.
        for (String line : BillText.rentPausedNotes(page.view(), page.day())) paused.append(line).append('\n');
        return (page.view().invoices().isEmpty() ? "This home has no bills on this page.\n" : "Choose a bill to see its lines and payment maximum.\n")
                + "Bill amounts are fixed when issued.\n"
                + paused
                + (page.utilityPayable() ? "Utility payment authorizes today's bill up to "
                    + Money.format(page.utilityMaximum()) + ". A higher utility bill is refused."
                    : "Today's utility bill belongs to earlier ownership and needs review.");
    }
    static String billPayLabel(ChargeInvoiceRepository.Invoice invoice) {
        return "Pay bill up to " + Money.format(invoice.totalCents());
    }
    static List<String> billLines(OwnerDeskController.BillPage page, ChargeInvoiceRepository.Invoice invoice) {
        return BillText.detail(invoice, page.view().titles(), page.view().payments().get(invoice.invoiceId()), page.day(),
                page.view().holdRent(), BillText.pauseShared(page.view(), page.day()));
    }
    boolean showBills(Player player, OwnerDeskSessions.Ticket ticket, OwnerDeskController.BillPage page, BooleanSupplier authorized) {
        return showBillView(player, ticket, "Property | Bills", authorized, inventory -> {
            inventory.setItem(4, item(Material.PAPER, "Bills", List.of(billPageBody(page).split("\n"))));
            for (int i = 0; i < page.view().invoices().size(); i++) {
                var invoice = page.view().invoices().get(i);
                inventory.setItem(9 + i, item(Material.PAPER, billEntryLabel(page, invoice), List.of("View lines and payment options.")));
            }
            if (page.newer()) inventory.setItem(30, item(Material.ARROW, "Newer bills", List.of()));
            if (page.view().hasOlder()) inventory.setItem(32, item(Material.ARROW, "Older bills", List.of()));
            inventory.setItem(34, item(Material.ARROW, "Back to property", List.of()));
            inventory.setItem(40, item(Material.BARRIER, "Close", List.of()));
            inventory.setItem(42, item(Material.GOLD_NUGGET, page.utilityPayable()
                    ? "Pay utility up to " + Money.format(page.utilityMaximum()) : "Utilities require ownership review", List.of()));
        });
    }
    boolean confirmBill(Player player, OwnerDeskSessions.Ticket ticket, OwnerDeskController.BillPage page,
                        ChargeInvoiceRepository.Invoice invoice, BooleanSupplier authorized) {
        return showBillView(player, ticket, "Property | Bill", authorized, inventory -> {
            inventory.setItem(13, item(Material.PAPER, "Bill for " + invoice.day(), billLines(page, invoice)));
            boolean payable = BillText.payable(invoice, page.view().payments().get(invoice.invoiceId()), page.day());
            inventory.setItem(32, item(payable ? Material.EMERALD : Material.GRAY_DYE,
                    payable ? billPayLabel(invoice) : "Payment unavailable", List.of("Only the displayed bill and maximum are authorized.")));
            if (page.view().payments().containsKey(invoice.invoiceId()))
                inventory.setItem(38, item(Material.WRITABLE_BOOK, "Read payment receipt", List.of()));
            inventory.setItem(34, item(Material.ARROW, "Back to bills", List.of("Return without paying.")));
            inventory.setItem(40, item(Material.BARRIER, "Close", List.of()));
        });
    }
    private boolean showBillView(Player player, OwnerDeskSessions.Ticket ticket, String title, BooleanSupplier authorized,
                                 Consumer<Inventory> populate) {
        View view = new View(ticket);
        view.inventory = Bukkit.createInventory(view, 45, Component.text(title));
        return OwnerDeskPublication.publish(() -> openEmpty(player, view), authorized,
                () -> populate.accept(view.inventory), () -> discard(player, view));
    }

    /** Appends the pending-rent line when SQL recorded one; otherwise the lines are unchanged. */
    static List<String> rentLines(OwnerDeskData data, List<String> lines) {
        if (data.pendingRent().isEmpty()) return lines;
        var out = new ArrayList<>(lines);
        out.add(data.pendingRent().get().line());
        return List.copyOf(out);
    }
    static int nextLevel(OwnerDeskData data, UpgradeType type) {
        return data.upgrades().stream().filter(u -> u.type().equalsIgnoreCase(type.name()))
                .mapToInt(OwnerDeskData.Upgrade::level).max().orElse(0) + 1;
    }
    static String evictionLabel(OwnerDeskData.Resident resident) {
        return evictionLabel(resident, PurchasePrices.DEFAULT);
    }
    static String evictionLabel(OwnerDeskData.Resident resident, PurchasePrices prices) {
        return "End tenancy: " + resident.name() + " | " + Money.format(prices.evictionCents());
    }
    static String evictionCommand(OwnerDeskData.Resident resident, PurchasePrices prices) {
        return "/tenants evict " + resident.id() + " " + java.math.BigDecimal.valueOf(prices.evictionCents(), 2).toPlainString();
    }
    static OwnerDeskData.Resident evictionTarget(OwnerDeskData data, int slot) {
        return rooms(data).stream().filter(r -> r.inventorySlot() == slot).map(Room::resident)
                .filter(Objects::nonNull).findFirst().orElse(null);
    }
    boolean confirmEviction(Player player, UUID token, OwnerDeskData.Resident resident) {
        var inventory = player.getOpenInventory().getTopInventory();
        if (!belongs(inventory, token)) return false;
        inventory.clear();
        inventory.setItem(13, item(Material.WRITABLE_BOOK, resident.name(),
                List.of("Room " + (resident.slot() + 1), "This ends the current tenancy.", "Their identity and history are retained.")));
        inventory.setItem(32, item(Material.RED_DYE, evictionLabel(resident, prices),
                List.of("Confirm the " + Money.format(prices.evictionCents()) + " payment and move-out.", "An unavailable tenancy is refused.")));
        inventory.setItem(34, item(Material.ARROW, "Keep tenancy", List.of("Return without paying.")));
        inventory.setItem(40, item(Material.BARRIER, "Close", List.of()));
        return true;
    }
    boolean confirmPause(Player player, UUID token) {
        var inventory = player.getOpenInventory().getTopInventory();
        if (!belongs(inventory, token)) return false;
        inventory.clear();
        inventory.setItem(13, item(Material.PAPER, PAUSE_LABEL, List.of("NPC rent and bills stop until you resume.",
                "Tenants, incidents and history are kept.")));
        inventory.setItem(32, item(Material.RED_DYE, PAUSE_CONFIRM, List.of("Confirm the pause.")));
        inventory.setItem(34, item(Material.ARROW, "Keep it running", List.of("Return without pausing.")));
        inventory.setItem(40, item(Material.BARRIER, "Close", List.of()));
        return true;
    }
    boolean showPaused(Player player, OwnerDeskSessions.Ticket ticket, BooleanSupplier authorized) {
        View view = new View(ticket);
        view.inventory = Bukkit.createInventory(view, 45, Component.text("Property | Paused"));
        return OwnerDeskPublication.publish(() -> openEmpty(player, view), authorized, () -> {
            view.inventory.setItem(13, item(Material.PAPER, shorten(ticket.region(), 30) + " is paused",
                    List.of("Tenants, incidents and history were kept.", "You can also type /property resume.")));
            view.inventory.setItem(22, item(Material.EMERALD, RESUME_LABEL, List.of("Puts it back in the NPC economy.")));
            view.inventory.setItem(40, item(Material.BARRIER, "Close", List.of()));
        }, () -> discard(player, view));
    }
    boolean showAutoCare(Player player, OwnerDeskSessions.Ticket ticket, BooleanSupplier authorized) {
        View view = new View(ticket);
        view.inventory = Bukkit.createInventory(view, 45, Component.text("Property | Auto-care"));
        return OwnerDeskPublication.publish(() -> openEmpty(player, view), authorized,
                () -> populateAutoCare(view.inventory), () -> discard(player, view));
    }

    private boolean openEmpty(Player player, View view) {
        View previous = opening;
        if (previous != null) {
            previous.interrupted = true;
            if (previous.openingEvent != null) previous.openingEvent.setCancelled(true);
        }
        opening = view;
        try {
            player.openInventory(view.inventory);
            return player.getOpenInventory().getTopInventory() == view.inventory;
        } finally {
            opening = previous;
            view.openingEvent = null;
        }
    }

    private static void discard(Player player, View view) {
        view.inventory.clear();
        if (player.getOpenInventory().getTopInventory() == view.inventory) player.closeInventory();
    }

    @EventHandler(priority = EventPriority.LOWEST) public void opening(InventoryOpenEvent event) {
        View pending = opening;
        if (pending == null || !pending.ticket.actor().equals(event.getPlayer().getUniqueId())) return;
        if (event.getInventory() == pending.inventory) {
            if (pending.openingEvent == null) pending.openingEvent = event;
            else if (pending.openingEvent != event) pending.interrupted = true;
            if (pending.interrupted) event.setCancelled(true);
        } else pending.interrupted = true;
        // Paper must not install this window over one opened by a nested handler.
        if (pending.interrupted && pending.openingEvent != null) pending.openingEvent.setCancelled(true);
    }
    private static void populateAutoCare(Inventory inventory) {
        inventory.setItem(13, item(Material.CLOCK, "Recurring property care", List.of(
                "One utility payment, one cash repair, upkeep below 80 condition.",
                "No upgrades, insurance or eviction; no automatic retries.",
                "Turn OFF to stop new charges; accepted debits still settle.")));
        for (int slot : List.of(22, 24, 26)) {
            long cents = slot == 22 ? 1000 : slot == 24 ? 3000 : 6000;
            inventory.setItem(slot, item(Material.EMERALD, "Authorize up to " + Money.format(cents) + " / day", List.of(
                    "Recurring consent for this property and ownership.", "Not a flat fee; only verified work is charged.")));
        }
        inventory.setItem(30, item(Material.RED_DYE, "Turn auto-care OFF", List.of()));
        inventory.setItem(38, item(Material.PAPER, "Check saved consent", List.of()));
        inventory.setItem(34, item(Material.ARROW, "Back to property", List.of()));
        inventory.setItem(40, item(Material.BARRIER, "Close", List.of()));
    }
    static String purchaseLabel(OwnerDeskData data, int slot) {
        return purchaseLabel(data, slot, PurchasePrices.DEFAULT);
    }
    static String purchaseLabel(OwnerDeskData data, int slot, PurchasePrices prices) {
        if (slot == 28) return "Maintain condition for " + Money.format(prices.maintenanceCents());
        var type = slot == 24 ? UpgradeType.FACILITIES : UpgradeType.ADVERTISING;
        int next = nextLevel(data, type);
        return next > 3 ? label(type.name()) + " at maximum level" : label(type.name()) + " level " + next + " for "
                + Money.format(prices.upgradeCents(type, next));
    }
    static long utilityLimit(OwnerDeskData data) {
        return utilityLimit(data, UtilityPrices.DEFAULT);
    }
    static long utilityLimit(OwnerDeskData data, UtilityPrices prices) {
        return data.utilityMaximum(prices);
    }
    static String utilityLabel(OwnerDeskData data, UtilityPrices prices) {
        return data.canPayUtility() ? "Pay utility up to " + Money.format(utilityLimit(data, prices))
                : "Utilities require ownership review";
    }
    static String insuranceLabel(OwnerDeskData data) {
        var cover = data.insurance();
        if (data.canPurchaseInsurance()) return (cover.startDay() != null && cover.currentIncarnation() ? "Renew" : "Buy")
                + " seven-day insurance for " + Money.format(cover.premiumCents());
        if (!cover.currentIncarnation()) return "Insurance | Previous owner's term";
        if (cover.used()) return "Insurance | Prevention used";
        return data.statement().throughDay().isBefore(cover.startDay()) ? "Insurance | Starts " + cover.startDay() : "Insurance | Covered";
    }
    static List<String> insuranceLines(OwnerDeskData data) {
        var cover = data.insurance();
        var lines = new ArrayList<String>();
        if (cover.startDay() != null) {
            lines.add("Term: " + cover.startDay() + " to " + cover.endDayExclusive().minusDays(1) + " | Economy days");
            if (!cover.currentIncarnation()) lines.add("Previous owner coverage does not transfer.");
            else lines.add(cover.used() ? "The one prevention has been used." : "One prevention remains for new incidents.");
            lines.add(cover.nextTermReserved() ? "The next seven-day term is already reserved."
                    : "Repurchase available from " + cover.endDayExclusive().minusDays(1) + ".");
        } else lines.add("Starts next economy day after verified payment.");
        lines.add("Seven economy days; one new incident prevented.");
        lines.add("Existing incidents are not covered.");
        lines.add("Prepaid and nonrefundable; no cash payout.");
        return List.copyOf(lines);
    }
    static String repairLabel(OwnerDeskData data) {
        if (data.issues().isEmpty()) return "No open repair";
        var issue = data.issues().getFirst();
        return "Repair #" + issue.id() + " up to " + Money.format(issue.repairCents());
    }
    static List<String> statementLines(OwnerDeskData data) {
        var s = data.statement();
        return List.of(s.fromDay() + " to " + s.throughDay() + " | Economy days",
                "Income received: " + Money.format(s.grossCents()), "Expenses paid: " + Money.format(s.expenseCents()),
                "Net settled: " + Money.format(s.netCents()), "Pending: " + s.pendingCount() + " | Needs review: " + s.reviewCount(),
                "Your current ownership only.");
    }
    /**
     * The statement lines plus the linked-accounts line when this owner shares the daily rent limit. Used by the
     * chest, /property status text and the Bedrock form, so all three show the same words.
     */
    static List<String> statementAndLinkLines(OwnerDeskData data) {
        if (data.linked().isEmpty() && data.partner().isEmpty() && data.bills().isEmpty()) return statementLines(data);
        var out = new ArrayList<>(statementLines(data));
        data.linked().ifPresent(linked -> out.add(linked.line()));
        // town.partnerships: one read-only line for the viewing owner's partnership.
        data.partner().ifPresent(partner -> out.add(partner.line()));
        data.bills().ifPresent(bills -> out.add(bills.line()));
        return List.copyOf(out);
    }
    static List<String> housingLines(OwnerDeskData data) {
        if (data.housing().isEmpty()) return List.of("Service record unavailable");
        var view = data.housing().orElseThrow();
        var rating = view.rating();
        var premium = view.premium();
        var lines = new ArrayList<String>();
        // Fixed labels, bounded numeric fields and timestamps keep these lines below 700 characters.
        lines.add("Owner-wide service record | " + Duration.between(view.windowFrom(), view.capturedAt()).toDays() + "-day window");
        lines.add(STAMP.format(view.windowFrom()) + " to " + STAMP.format(view.capturedAt()));
        lines.add(switch (rating.status()) {
            case UNRATED_HELD -> "Rating: Unrated";
            case SERVICE_REVIEW -> "Rating: Needs review";
            case RATED -> "Operator: " + rating.planningOperatorScore().orElseThrow() + " / 75 | Property: "
                    + String.format(Locale.US, "%.2f", rating.rankingScore().orElseThrow()) + " / 100";
            case PROVISIONAL -> throw new IllegalStateException("Owner service rating must not be provisional");
        });
        lines.add("Assessed utility days: " + premium.distinctUtilityBillDays() + " / " + PropertyFormulas.PREMIUM_MIN_OBSERVATIONS
                + " | Bills assessed: " + premium.utilityAssessedCount());
        lines.add("Incidents assessed: " + premium.incidentAssessedCount() + " / 1");
        lines.add("Mature tenancies assessed: " + premium.retentionAssessedCount() + " / 1");
        lines.add(switch (premium.reason()) {
            case ELIGIBLE -> "Premium evidence qualifies";
            case OWNER_MISMATCH -> "Premium: Service record owner does not match";
            case CLOCK_POLICY_MISMATCH -> "Premium: Utility bill clock policy does not match";
            case SERVICE_REVIEW -> "Premium: Service evidence needs review";
            case OVERDUE_UTILITIES -> "Premium: Overdue utilities recorded";
            case OVERDUE_INCIDENTS -> "Premium: Overdue incidents recorded";
            case INSUFFICIENT_UTILITY_DAYS -> "Premium: More assessed utility days needed";
            case INSUFFICIENT_INCIDENTS -> "Premium: An assessed incident is needed";
            case INSUFFICIENT_RETENTION -> "Premium: An assessed mature tenancy is needed";
            case UNKNOWN_RATES -> "Premium: Actual service rates unavailable";
            case PREMIUM_THRESHOLDS_NOT_MET -> "Premium: Score, condition or rent thresholds not met";
        });
        if (premium.reason() == PremiumHousingEligibility.Reason.PREMIUM_THRESHOLDS_NOT_MET)
            lines.add("Requires condition >= " + PropertyFormulas.PREMIUM_MIN_CONDITION + ", property >= "
            + PropertyFormulas.PREMIUM_MIN_SCORE
                    + ", operator >= " + PropertyFormulas.PREMIUM_MIN_OP_SCORE + ", rent <= " + PropertyFormulas.PREMIUM_MAX_ASKING_PCT
                    + "% of reference.");
        lines.add("Evidence only; matching depends on feature flags, availability and caps.");
        return List.copyOf(lines);
    }
    static List<String> issueLines(OwnerDeskData data) {
        if (data.issues().isEmpty()) return List.of("No unresolved service issue is recorded.");
        List<String> lines = new ArrayList<>();
        for (var issue : data.issues()) {
            lines.add("#" + issue.id() + " " + label(issue.type()) + " | Repair quote " + Money.format(issue.repairCents()));
            lines.add("Due " + STAMP.format(issue.dueAt()));
        }
        return lines;
    }
    /**
     * The item-repairs desk lines from the live state: a note while on, "Items to collect" while returns wait, nothing
     * when neither. "Items to collect" follows the running runtime, not the flag: with item-repairs off the runtime
     * still runs for earlier hand-ins that owe items back. waiting is asked only while running.
     */
    static List<String> itemRepairLines(boolean on, boolean running, Predicate<UUID> waiting,
                                        UUID owner) {
        return itemRepairLines(on, running && waiting.test(owner));
    }
    static List<String> itemRepairLines(boolean on, boolean returnsPending) {
        var lines = new ArrayList<String>();
        if (on) lines.add(ItemRepairCopy.DESK_NOTE);
        if (returnsPending) lines.add(ItemRepairCopy.ITEMS_TO_COLLECT);
        return List.copyOf(lines);
    }
    static List<String> issueLines(OwnerDeskData data, List<String> itemRepairs) {
        if (itemRepairs.isEmpty()) return issueLines(data);
        var lines = new ArrayList<>(issueLines(data));
        lines.addAll(itemRepairs);
        return List.copyOf(lines);
    }
    /** The repair choice lines shown at slot 13 and as the Bedrock form body. */
    static List<String> repairChoiceLines(OwnerDeskData.Issue issue, ItemRepairRuntime.Preview preview) {
        String type = preview == null ? label(issue.type()) : preview.typeLabel();
        boolean available = preview != null && preview.available();
        return ItemRepairCopy.choiceLines(type, issue.repairCents(), available ? preview.list() : "", available);
    }
    static String handInLabel(ItemRepairRuntime.Preview preview) {
        return preview != null && preview.available() ? ItemRepairCopy.HAND_IN
                : ItemRepairCopy.HAND_IN_UNAVAILABLE;
    }
    boolean showRepairChoice(Player player, UUID token, OwnerDeskData.Issue issue, ItemRepairRuntime.Preview preview) {
        var inventory = player.getOpenInventory().getTopInventory();
        if (!belongs(inventory, token)) return false;
        inventory.clear();
        inventory.setItem(13, item(Material.ANVIL, ItemRepairCopy.title(issue.id()), repairChoiceLines(issue, preview)));
        inventory.setItem(30, item(Material.GOLD_NUGGET, ItemRepairCopy.payCash(issue.repairCents()), List.of()));
        inventory.setItem(32, item(Material.CHEST, handInLabel(preview), List.of()));
        inventory.setItem(34, item(Material.ARROW, ItemRepairCopy.BACK, List.of()));
        inventory.setItem(40, item(Material.BARRIER, ItemRepairCopy.CLOSE, List.of()));
        return true;
    }
    private static List<String> upgradeLines(OwnerDeskData data) {
        var lines = new ArrayList<String>();
        for (var upgrade : data.upgrades()) lines.add(label(upgrade.type()) + " L" + upgrade.level()
                + (upgrade.disabled() ? " | Out of service" : ""));
        if (lines.isEmpty()) lines.add("No installed upgrades.");
        return lines;
    }
    static void text(Player player, OwnerDeskData data, boolean tenantsOnly) { text(player, data, tenantsOnly, List.of()); }
    static void text(Player player, OwnerDeskData data, boolean tenantsOnly, List<String> itemRepairs) {
        text(player, data, tenantsOnly, itemRepairs, PurchasePrices.DEFAULT);
    }
    static void text(Player player, OwnerDeskData data, boolean tenantsOnly, List<String> itemRepairs, PurchasePrices prices) {
        text(player, data, tenantsOnly, itemRepairs, prices, UtilityPrices.DEFAULT);
    }
    static void text(Player player, OwnerDeskData data, boolean tenantsOnly, List<String> itemRepairs,
                     PurchasePrices prices, UtilityPrices utilityPrices) {
        say(player, "Property " + data.property().regionId() + " | " + data.residents().size() + " / " + data.property().capacity()
                + " residents", NamedTextColor.AQUA);
        say(player, "As of " + STAMP.format(data.capturedAt()), NamedTextColor.GRAY);
        for (var resident : data.residents()) {
            say(player, "Room " + (resident.slot() + 1) + ": " + resident.name() + " | " + label(resident.mood()) +
                    " | Satisfaction " + resident.satisfaction() + " / 100", NamedTextColor.GRAY);
            String eviction = evictionCommand(resident, prices);
            player.sendMessage(Component.text("End this tenancy for " + Money.format(prices.evictionCents()) + ": " + eviction, NamedTextColor.GOLD)
                    .clickEvent(ClickEvent.suggestCommand(eviction)));
        }
        if (data.residents().isEmpty()) say(player, "No residents assigned.", NamedTextColor.GRAY);
        if (!tenantsOnly) {
            say(player, "Condition " + data.property().conditionScore() + " / 100 | Asking rent "
                    + Money.format(data.property().askingRentCents()) + " / resident / day", NamedTextColor.GRAY);
            data.pendingRent().ifPresent(pending -> say(player, pending.line(), NamedTextColor.GOLD));
            for (String line : issueLines(data, itemRepairs)) say(player, line, NamedTextColor.GRAY);
            for (String line : upgradeLines(data)) say(player, line, NamedTextColor.GRAY);
            say(player, insuranceLabel(data), NamedTextColor.GOLD);
            for (String line : insuranceLines(data)) say(player, line, NamedTextColor.GRAY);
            say(player, String.join("\n", housingLines(data)), NamedTextColor.GRAY);
            for (String line : statementAndLinkLines(data)) say(player, line, NamedTextColor.GRAY);
            say(player, "/property utility pay <maximum-dollars> | /property repair <incident-id> <maximum-dollars>", NamedTextColor.GOLD);
            say(player, data.canPayUtility() ? "Utility maximum: " + Money.format(utilityLimit(data, utilityPrices))
                    : "Utilities require review: today's bill belongs to earlier ownership.", NamedTextColor.GRAY);
        }
    }
    boolean belongs(Inventory inventory, UUID token) {
        return inventory.getHolder() instanceof View view && view.inventory == inventory && view.ticket.token().equals(token);
    }
    void closeOwned(Player player, UUID token) {
        if (player.isOnline() && belongs(player.getOpenInventory().getTopInventory(), token)) player.closeInventory();
    }
    private static String label(String value) {
        String s = value.replace('_', ' ').toLowerCase(Locale.ROOT);
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
    private static String shorten(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }
    private static ItemStack item(Material material, String title, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        var meta = stack.getItemMeta();
        meta.displayName(Component.text(title, NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore.stream().flatMap(line -> EncounterView.wrap(line, 42).stream()).map(line -> Component.text(line, NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)).toList());
        stack.setItemMeta(meta);
        return stack;
    }
    private static void say(Player player, String text, NamedTextColor color) { player.sendMessage(Component.text(text, color)); }
    static boolean clickAllowed(boolean vetoed, boolean sameActor, boolean topInventory, ClickType click) {
        return !vetoed && sameActor && topInventory && click == ClickType.LEFT;
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof View view)) return;
        boolean vetoed = event.isCancelled();
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !clickAllowed(vetoed,
                player.getUniqueId().equals(view.ticket.actor()), event.getClickedInventory() == view.inventory, event.getClick())) return;
        controller.scheduleAction(player, view.ticket.token(), event.getRawSlot());
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof View) event.setCancelled(true);
    }
    @EventHandler public void close(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof View view) controller.viewClosed(event.getPlayer().getUniqueId(),
                view.ticket.token());
    }
}

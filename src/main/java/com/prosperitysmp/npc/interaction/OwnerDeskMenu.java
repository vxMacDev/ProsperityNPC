package com.prosperitysmp.npc.interaction;

import com.prosperitysmp.npc.economy.ItemRepairCopy;
import com.prosperitysmp.npc.economy.ItemRepairRuntime;
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

/** Cosmetic inventory items are never transferable; every action returns to owner authorization. */
final class OwnerDeskMenu implements Listener {
    static final long EVICTION_CENTS = 1000;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("MMM d HH:mm:ss 'UTC'", Locale.US).withZone(ZoneOffset.UTC);
    private static final class View implements InventoryHolder {
        final OwnerDeskSessions.Ticket ticket;
        Inventory inventory;
        View(OwnerDeskSessions.Ticket ticket) { this.ticket = ticket; }
        @Override public Inventory getInventory() { return inventory; }
    }
    private final OwnerDeskController controller;
    private final BooleanSupplier advertisingRank; // read live, so /npc reload changes it
    private final boolean pauseControls;
    OwnerDeskMenu(OwnerDeskController controller) { this(controller, () -> false); }
    OwnerDeskMenu(OwnerDeskController controller, BooleanSupplier advertisingRank) { this(controller, advertisingRank, false); }
    OwnerDeskMenu(OwnerDeskController controller, BooleanSupplier advertisingRank, boolean pauseControls) {
        this.controller = controller;
        this.advertisingRank = advertisingRank;
        this.pauseControls = pauseControls;
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
        return OwnerDeskPublication.publish(() -> {
            player.openInventory(view.inventory);
            return player.getOpenInventory().getTopInventory() == view.inventory;
        }, authorized, () -> populate(view.inventory, data), () -> {
            view.inventory.clear();
            closeOwned(player, ticket.token());
        });
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
        inventory.setItem(24, item(Material.CRAFTING_TABLE, purchaseLabel(data, 24),
                List.of("Click authorizes the displayed level and cost.")));
        inventory.setItem(26, item(Material.BELL, purchaseLabel(data, 26), advertisingLines(advertisingRank.getAsBoolean(), data)));
        inventory.setItem(28, item(Material.BRUSH, purchaseLabel(data, 28), List.of("Click authorizes $10.00; once per economy day.")));
        inventory.setItem(30, item(Material.SHIELD, insuranceLabel(data), insuranceLines(data)));
        inventory.setItem(31, item(Material.PAPER, "Your settled statement", statementAndLinkLines(data)));
        inventory.setItem(33, item(Material.BOOK, "Service record and qualification", housingLines(data)));
        if (pauseControls) inventory.setItem(32, item(Material.RED_DYE, PAUSE_LABEL, pauseLines()));
        inventory.setItem(34, item(Material.CLOCK, "Auto-care settings",
                List.of("Optional daily spending ceiling.", "Utilities, one cash repair and condition upkeep.")));
        inventory.setItem(36, item(Material.CLOCK, "Refresh", List.of()));
        inventory.setItem(38, item(Material.WRITABLE_BOOK, "Read in chat", List.of()));
        inventory.setItem(40, item(Material.BARRIER, "Close", List.of()));
        inventory.setItem(42, item(Material.GOLD_NUGGET, "Pay utility up to " + Money.format(utilityLimit(data)),
                List.of("Today's bill only; click authorizes this maximum.", "A higher bill is refused, never charged.",
                        "Unavailable while the payment gate is held.")));
        inventory.setItem(44, item(Material.PAPER, "Rent settings", rentLines(data,
                List.of("/property rent <dollars-per-day>", "50%-150% of the reference rent."))));
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
        return "End tenancy: " + resident.name() + " | " + Money.format(EVICTION_CENTS);
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
        inventory.setItem(32, item(Material.RED_DYE, evictionLabel(resident),
                List.of("Confirm the $10.00 payment and move-out.", "An unavailable tenancy is refused.")));
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
        return OwnerDeskPublication.publish(() -> {
            player.openInventory(view.inventory);
            return player.getOpenInventory().getTopInventory() == view.inventory;
        }, authorized, () -> {
            view.inventory.setItem(13, item(Material.PAPER, shorten(ticket.region(), 30) + " is paused",
                    List.of("Tenants, incidents and history were kept.", "You can also type /property resume.")));
            view.inventory.setItem(22, item(Material.EMERALD, RESUME_LABEL, List.of("Puts it back in the NPC economy.")));
            view.inventory.setItem(40, item(Material.BARRIER, "Close", List.of()));
        }, () -> {
            view.inventory.clear();
            closeOwned(player, ticket.token());
        });
    }
    boolean showAutoCare(Player player, OwnerDeskSessions.Ticket ticket, BooleanSupplier authorized) {
        View view = new View(ticket);
        view.inventory = Bukkit.createInventory(view, 45, Component.text("Property | Auto-care"));
        return OwnerDeskPublication.publish(() -> {
            player.openInventory(view.inventory);
            return player.getOpenInventory().getTopInventory() == view.inventory;
        }, authorized, () -> populateAutoCare(view.inventory), () -> {
            view.inventory.clear();
            closeOwned(player, ticket.token());
        });
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
        if (slot == 28) return "Maintain condition for $10.00";
        var type = slot == 24 ? UpgradeType.FACILITIES : UpgradeType.ADVERTISING;
        int next = nextLevel(data, type);
        return next > 3 ? label(type.name()) + " at maximum level" : label(type.name()) + " level " + next + " for "
                + Money.format(type.costToLevelCents(next));
    }
    static long utilityLimit(OwnerDeskData data) {
        return PropertyFormulas.dailyUtilitiesCents(data.residents().size(),
                data.upgrades().stream().mapToInt(OwnerDeskData.Upgrade::level).sum());
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
        if (data.linked().isEmpty() && data.partner().isEmpty()) return statementLines(data);
        var out = new ArrayList<>(statementLines(data));
        data.linked().ifPresent(linked -> out.add(linked.line()));
        // town.partnerships: one read-only line for the viewing owner's partnership.
        data.partner().ifPresent(partner -> out.add(partner.line()));
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
        say(player, "Property " + data.property().regionId() + " | " + data.residents().size() + " / " + data.property().capacity()
                + " residents", NamedTextColor.AQUA);
        say(player, "As of " + STAMP.format(data.capturedAt()), NamedTextColor.GRAY);
        for (var resident : data.residents()) {
            say(player, "Room " + (resident.slot() + 1) + ": " + resident.name() + " | " + label(resident.mood()) +
                    " | Satisfaction " + resident.satisfaction() + " / 100", NamedTextColor.GRAY);
            String eviction = "/tenants evict " + resident.id() + " 10.00";
            player.sendMessage(Component.text("End this tenancy for $10.00: " + eviction, NamedTextColor.GOLD)
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

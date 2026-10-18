package com.prosperitysmp.npc.interaction;

import com.prosperitysmp.npc.ProsperityNpcPlugin;
import com.prosperitysmp.npc.api.OwnershipToken;
import com.prosperitysmp.npc.clock.EconomyClock;
import com.prosperitysmp.npc.charge.BillingRuntime;
import com.prosperitysmp.npc.charge.BillText;
import com.prosperitysmp.npc.charge.ChargeInvoiceRepository;
import com.prosperitysmp.npc.economy.ItemRepairCopy;
import com.prosperitysmp.npc.economy.ItemRepairRecipes;
import com.prosperitysmp.npc.economy.ItemRepairRuntime;
import com.prosperitysmp.npc.economy.PurchaseRequest;
import com.prosperitysmp.npc.formulas.IncidentType;
import com.prosperitysmp.npc.formulas.UpgradeType;
import com.prosperitysmp.npc.property.OwnerAway;
import com.prosperitysmp.npc.property.OwnerDeskData;
import com.prosperitysmp.npc.property.OwnerDeskRepository;
import com.prosperitysmp.npc.property.PropertyRepository;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Private owner desk. Reads use its worker; payments use the separately authorized receipt runtime. */
public final class OwnerDeskController implements Listener, AutoCloseable {
    public interface NativeUi {
        boolean handles(UUID player);
        boolean show(Player player, UUID token, OwnerDeskData data, BooleanSupplier authorized);
        boolean confirmEviction(Player player, UUID token, OwnerDeskData.Resident resident,
                                BooleanSupplier authorized);
        /** Optional desk controls (ux.desk-pause-controls); false falls back to the typed /property commands. */
        default boolean confirmPause(Player player, UUID token, BooleanSupplier authorized) { return false; }
        default boolean showPaused(Player player, UUID token, String region, BooleanSupplier authorized) { return false; }
        default boolean showAutoCare(Player player, UUID token, OwnerDeskData data,
                                     BooleanSupplier authorized) { return false; }
        /** item-repairs: pay cash or hand in items for one incident. False falls back to the typed commands. */
        default boolean showRepairChoice(Player player, UUID token, OwnerDeskData.Issue issue,
                                         ItemRepairRuntime.Preview preview,
                                         BooleanSupplier authorized) { return false; }
        default boolean showBills(Player player, UUID token, BillPage page, BooleanSupplier authorized) { return false; }
        default boolean confirmBill(Player player, UUID token, BillPage page, ChargeInvoiceRepository.Invoice invoice,
                                    BooleanSupplier authorized) { return false; }
    }
    public record BillPage(ChargeInvoiceRepository.OwnerView view, LocalDate day, boolean newer, long utilityMaximum,
                           boolean utilityPayable) {
        public BillPage(ChargeInvoiceRepository.OwnerView view, LocalDate day, boolean newer, long utilityMaximum) {
            this(view, day, newer, utilityMaximum, true);
        }
        public BillPage {
            java.util.Objects.requireNonNull(view);
            java.util.Objects.requireNonNull(day);
            if (view.invoices().size() > 8 || utilityMaximum <= 0) throw new IllegalArgumentException("Invalid bill page");
        }
    }

    private static final class Bills {
        final BillingRuntime runtime;
        final LocalDate day;
        final List<LocalDate> pages = new ArrayList<>();
        int page;
        long readAt = -2_000;
        boolean loading;
        ChargeInvoiceRepository.OwnerView view;
        ChargeInvoiceRepository.Invoice selected;
        Bills(BillingRuntime runtime) {
            this.runtime = runtime;
            day = runtime.today();
            pages.add(null);
        }
    }
    private NativeUi nativeUi;
    public void setNativeUi(NativeUi ui) {
        requireMain();
        nativeUi = ui;
    }
    private static final class Active {
        OwnerDeskSessions.Ticket ticket;
        final OwnershipToken authority;
        final Player session;
        final boolean text, tenantsOnly;
        OwnerDeskData data;
        OwnerDeskData.Resident eviction;
        boolean actionPending;
        boolean autoCare;
        boolean paused, pauseConfirm;
        // item-repairs: the open choice screen and the item list it shows (main thread).
        OwnerDeskData.Issue repairIssue;
        ItemRepairRuntime.Preview repairPreview;
        Bills bills;
        Active(OwnerDeskSessions.Ticket ticket, OwnershipToken authority, Player player, boolean text, boolean tenantsOnly) {
            this.ticket = ticket;
            this.authority = authority;
            this.session = player;
            this.text = text;
            this.tenantsOnly = tenantsOnly;
        }
    }
    private final ProsperityNpcPlugin plugin;
    private final OwnerDeskRepository repository;
    private final EconomyClock clock;
    private final OwnerDeskSessions sessions = new OwnerDeskSessions();
    private final Map<UUID, Active> active = new HashMap<>(); // main-thread only
    private final Map<UUID, UUID> operations = new ConcurrentHashMap<>();
    private final long started = System.nanoTime();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(16), task -> {
                Thread t = new Thread(task, "prosperity-npc-owner-desk");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());
    private final OwnerDeskMenu menu;
    private final boolean pauseControls;
    private final BukkitTask watcher;
    private volatile boolean closed;
    private long nextWarning;

    public OwnerDeskController(ProsperityNpcPlugin plugin, OwnerDeskRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
        plugin.trackDatabaseWorker(worker);
        clock = new EconomyClock(Clock.systemUTC(), ZoneId.of(plugin.getConfig().getString("clock.timezone", "America/Chicago")),
                plugin.getConfig().getInt("clock.daily-reset-hour", 4));
        pauseControls = plugin.getConfig().getBoolean("ux.desk-pause-controls", false);
        menu = new OwnerDeskMenu(this, () -> Boolean.TRUE.equals(plugin.getConfig().get("housing.advertising-rank-enabled")),
                pauseControls, plugin.purchasePrices(), plugin.utilityPrices());
        try {
            Bukkit.getPluginManager().registerEvents(this, plugin);
            Bukkit.getPluginManager().registerEvents(menu, plugin);
            watcher = Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, 20, 20);
        } catch (RuntimeException | LinkageError error) {
            HandlerList.unregisterAll(this);
            HandlerList.unregisterAll(menu);
            worker.shutdownNow();
            throw error;
        }
    }
    /** The desk flag is read once at construction; Bedrock asks here so both UIs always agree. */
    boolean pauseControls() { return pauseControls; }
    boolean billsOn() { return plugin.billing() != null && plugin.billing().enabledNow(); }
    private long now() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }
    private static void requireMain() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Owner desk requires main thread"); }
    private boolean allowed(Player player) {
        return !closed && plugin.isEnabled() && player != null && player.isOnline() && !player.isDead()
                && player.hasPermission("prosperitynpc.use") && plugin.propertyBackend() != null
                && (plugin.killSwitches().isSystemEnabled() || (player.hasPermission("prosperitynpc.admin")
                && plugin.getConfig().getBoolean("beta.resident-lab-enabled", false)
                && plugin.getConfig().getBoolean("beta.encounters-enabled", false)));
    }

    public void open(Player player, boolean text, boolean tenantsOnly) {
        request(player, text, tenantsOnly, null);
    }
    private void request(Player player, boolean text, boolean tenantsOnly, Active refreshing) {
        requireMain();
        if (!allowed(player)) {
            note(player, "The owner desk is unavailable.");
            return;
        }
        long admittedAt = now();
        if (!sessions.attempt(player.getUniqueId(), admittedAt)) {
            note(player, "Please wait a moment before refreshing the owner desk.");
            // Native forms close when answered; retain the authorized current view on throttling.
            if (refreshing != null && refreshing.data != null && valid(refreshing) && nativeUi != null
                    && nativeUi.handles(player.getUniqueId())) {
                try { nativeUi.show(player, refreshing.ticket.token(), refreshing.data, () -> valid(refreshing)); }
                catch (RuntimeException failure) { note(player, "Reopen your property with /property status."); }
            }
            return;
        }
        if (refreshing != null && !valid(refreshing)) {
            retire(refreshing.ticket);
            return;
        }
        var authority = plugin.propertyBackend().ownerReadAt(player.getLocation(), player.getUniqueId());
        if (authority.isEmpty()) {
            note(player, "Stand in one property you own outright. Shared, nested or overlapping ownership needs staff review.");
            return;
        }
        var admitted = sessions.admit(player.getUniqueId(), authority.get().worldId(), authority.get().regionId(), admittedAt);
        if (admitted.isEmpty()) {
            note(player, "Please wait a moment before refreshing the owner desk.");
            return;
        }
        var ticket = admitted.get();
        Active old = active.remove(player.getUniqueId());
        if (old != null) menu.closeOwned(player, old.ticket.token());
        active.put(ticket.actor(), new Active(ticket, authority.get(), player, text, tenantsOnly));
        operations.put(ticket.actor(), ticket.token());
        var day = clock.today();
        boolean includeBills = plugin.billing() != null && plugin.billing().enabledNow();
        note(player, "Checking your property records...");
        try {
            worker.execute(() -> {
                // Queued requests canceled before SQL admission do no database work.
                if (closed || !ticket.token().equals(operations.get(ticket.actor()))) return;
                OwnerDeskData data = null;
                String failure = null;
                boolean isPaused = false;
                try {
                    data = repository.read(ticket.world(), ticket.region(), ticket.actor(), day, includeBills, !tenantsOnly).orElse(null);
                    if (data == null && pauseControls) isPaused = pausedHere(ticket.world(), ticket.region());
                }
                catch (Exception error) { failure = error.getClass().getSimpleName(); }
                OwnerDeskData result = data;
                String errorType = failure;
                boolean pausedRow = isPaused;
                if (closed) return;
                try { Bukkit.getScheduler().runTask(plugin, () -> deliver(ticket, result, errorType, pausedRow)); }
                catch (IllegalStateException shutdown) { operations.remove(ticket.actor(), ticket.token()); }
            });
        } catch (RejectedExecutionException full) {
            retire(ticket);
            note(player, "The owner desk is busy. Please try again shortly.");
        }
    }

    private boolean valid(Active state) {
        if (state == null || !sessions.valid(state.ticket, now()) || !allowed(state.session)
                || Bukkit.getPlayer(state.ticket.actor()) != state.session) return false;
        var authority = plugin.propertyBackend().ownerReadAt(state.session.getLocation(), state.ticket.actor());
        return authority.isPresent() && state.authority.sameIncarnation(authority.get());
    }
    static boolean matches(OwnerDeskSessions.Ticket ticket, OwnerDeskData data) {
        return data != null && data.property().enabled() && ticket.actor().equals(data.property().ownerUuid())
                && ticket.world().equals(data.property().worldUuid()) && ticket.region().equals(data.property().regionId());
    }
    private void deliver(OwnerDeskSessions.Ticket ticket, OwnerDeskData data, String failure, boolean pausedRow) {
        requireMain();
        if (!ticket.token().equals(operations.get(ticket.actor()))) return;
        operations.remove(ticket.actor(), ticket.token());
        Active state = active.get(ticket.actor());
        if (state == null || state.ticket != ticket) return;
        if (!sessions.mayDeliver(ticket, now()) || !valid(state)) {
            retire(ticket);
            return;
        }
        if (failure == null && data == null && pausedRow && !state.text) {
            showPaused(state, ticket);
            return;
        }
        if (failure != null || !matches(ticket, data)) {
            retire(ticket);
            note(state.session, failure == null ? "This property has no available NPC records for your ownership."
                    : "Property records are temporarily unavailable.");
            if (failure != null && now() >= nextWarning) {
                nextWarning = now() + 30_000;
                plugin.getLogger().warning("Owner desk read unavailable ref=" + ticket.token() + " type=" + failure);
            }
            return;
        }
        state.data = data;
        if (plugin.ownerAway().isAway(data.property())) note(state.session, OwnerAway.DESK_NOTE);
        if (state.text) {
            OwnerDeskMenu.text(state.session, data, state.tenantsOnly, itemRepairLines(ticket.actor()), plugin.purchasePrices(), plugin.utilityPrices());
            retire(ticket);
        }
        else {
            try {
                BooleanSupplier authorized = () -> active.get(ticket.actor()) == state
                        && sessions.mayDeliver(ticket, now()) && valid(state);
                NativeUi selected = nativeUi;
                if (selected != null && selected.handles(ticket.actor())) {
                    if (!authorized.getAsBoolean() || nativeUi != selected) {
                        retire(ticket);
                        return;
                    }
                    boolean shown = selected.show(state.session, ticket.token(), data, () -> active.get(ticket.actor()) == state
                            && valid(state));
                    if (!authorized.getAsBoolean()) {
                        retire(ticket);
                        return;
                    }
                    if (!shown) {
                        OwnerDeskMenu.text(state.session, data, state.tenantsOnly, List.of(), plugin.purchasePrices(), plugin.utilityPrices());
                        retire(ticket);
                    }
                } else if (!menu.show(state.session, ticket, data, authorized)) retire(ticket);
            } catch (RuntimeException | LinkageError error) {
                retire(ticket);
                note(state.session, "The owner desk could not open. Try /property status text.");
                if (now() >= nextWarning) {
                    nextWarning = now() + 30_000;
                    plugin.getLogger().warning("Owner desk view unavailable ref=" + ticket.token() + " type="
                            + error.getClass().getSimpleName());
                }
            }
        }
    }
    private void showPaused(Active state, OwnerDeskSessions.Ticket ticket) {
        state.paused = true;
        try {
            BooleanSupplier authorized = () -> active.get(ticket.actor()) == state
                    && sessions.mayDeliver(ticket, now()) && valid(state);
            NativeUi selected = nativeUi;
            boolean shown = selected != null && selected.handles(ticket.actor())
                    ? selected.showPaused(state.session, ticket.token(), ticket.region(), () -> active.get(ticket.actor()) == state
                            && valid(state))
                    : menu.showPaused(state.session, ticket, authorized);
            if (!shown) {
                retire(ticket);
                note(state.session, "This property is paused. Stand in it and use /property resume.");
            }
        } catch (RuntimeException | LinkageError error) {
            retire(ticket);
            note(state.session, "This property is paused. Stand in it and use /property resume.");
        }
    }
    void scheduleAction(Player player, UUID token, int slot) {
        requireMain();
        if (closed || ((slot < 9 || slot > 17) && slot != 32 && slot != 34 && slot != 35
                && slot != 22 && slot != 24 && slot != 26 && slot != 28 && slot != 30 && slot != 36 && slot != 38 && slot != 40
                && slot != 42)) return;
        Active state = active.get(player.getUniqueId());
        if (state == null || state.session != player || !state.ticket.token().equals(token) || state.actionPending) return;
        state.actionPending = true;
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (active.get(player.getUniqueId()) != state) return;
                state.actionPending = false;
                action(player, token, slot);
            });
        } catch (IllegalStateException shutdown) {
            state.actionPending = false;
            retire(state.ticket);
        }
    }
    void action(Player player, UUID token, int slot) {
        requireMain();
        Active state = active.get(player.getUniqueId());
        if (state == null || !state.ticket.token().equals(token) || state.session != player) return;
        if (slot == 40) {
            retire(state.ticket);
            return;
        }
        if (slot == 36) {
            request(player, false, state.tenantsOnly, state);
            return;
        }
        if (state.paused) {
            if (!valid(state)) {
                retire(state.ticket);
                return;
            }
            if (slot == 22) {
                var service = plugin.propertyService();
                if (service == null) {
                    note(player, "Property service is unavailable.");
                    return;
                }
                boolean accepted = service.resumeCoordinated(state.authority.worldId(), state.authority.regionId(),
                        () -> player.isOnline() && player.hasPermission("prosperitynpc.use")
                            && plugin.propertyBackend().ownerReadAt(player.getLocation(), player.getUniqueId())
                                .filter(state.authority::sameIncarnation).isPresent(), message -> note(player, message));
                retire(state.ticket);
                note(player, accepted ? "Checking property and ownership..." : "Property unavailable or busy; nothing changed.");
            }
            return;
        }
        if (!valid(state) || state.data == null) {
            retire(state.ticket);
            return;
        }
        if (state.bills != null) {
            billAction(state, token, slot);
            return;
        }
        if (state.autoCare) {
            if (slot == 34) {
                if (!rotateView(state)) return;
                state.autoCare = false;
                showExisting(state);
                return;
            }
            if (slot != 22 && slot != 24 && slot != 26 && slot != 30 && slot != 38) return;
            var care = plugin.autoCare();
            if (care == null) {
                note(player, "Auto-care is unavailable.");
                return;
            }
            long ceiling = slot == 22 ? 1000 : slot == 24 ? 3000 : slot == 26 ? 6000 : 0;
            Boolean enabled = slot == 38 ? null : slot != 30;
            var property = state.data.property();
            retire(state.ticket);
            care.configure(player, property, enabled, ceiling);
            return;
        }
        if (state.pauseConfirm) {
            if (slot == 34) {
                state.pauseConfirm = false;
                showExisting(state);
            }
            else if (slot == 32) {
                var service = plugin.propertyService();
                if (service == null) {
                    retire(state.ticket);
                    note(player, "Property service is unavailable.");
                    return;
                }
                boolean accepted = service.unenrollCoordinated(state.authority.worldId(), state.authority.regionId(),
                        () -> player.isOnline() && player.hasPermission("prosperitynpc.use")
                            && plugin.propertyBackend().ownerReadAt(player.getLocation(), player.getUniqueId())
                                .filter(state.authority::sameIncarnation).isPresent(), message -> note(player, message));
                retire(state.ticket);
                note(player, accepted ? "Checking property and ownership..." : "Property unavailable or busy; nothing changed.");
            }
            return;
        }
        if (state.repairIssue != null) {
            repairChoice(player, token, state, slot);
            return;
        }
        if (pauseControls && slot == 32 && state.eviction == null) {
            state.pauseConfirm = true;
            try {
                boolean shown = nativeUi != null && nativeUi.handles(player.getUniqueId())
                        ? nativeUi.confirmPause(player, token, () -> active.get(player.getUniqueId()) == state && valid(state))
                        : menu.confirmPause(player, token);
                if (!shown) retire(state.ticket);
            } catch (RuntimeException | LinkageError failure) {
                retire(state.ticket);
                note(player, "Use /property pause. Nothing changed.");
            }
            return;
        }
        if (slot == 34 && state.eviction == null) {
            if (!rotateView(state)) return;
            state.autoCare = true;
            boolean shown = nativeUi != null && nativeUi.handles(player.getUniqueId())
                    ? nativeUi.showAutoCare(player, state.ticket.token(), state.data, () -> valid(state))
                    : menu.showAutoCare(player, state.ticket, () -> valid(state));
            if (!shown) {
                state.autoCare = false;
                note(player, "Use /property autocare status, off, or on <maximum-dollars-per-day>.");
            }
            return;
        }
        if ((slot == 22 || slot == 24 || slot == 26 || slot == 28 || slot == 30 || slot == 32 || slot == 42)
                && !plugin.killSwitches().isEconomyEnabled()) {
            note(player, "Property payments are currently disabled. Nothing was charged.");
            return;
        }
        if (state.eviction != null) {
            if (slot == 34) {
                state.eviction = null;
                showExisting(state);
            } else if (slot == 32) {
                var resident = state.eviction;
                var payments = plugin.utilityPayments();
                if (payments == null) {
                    retire(state.ticket);
                    note(player, "Property payments are unavailable.");
                    return;
                }
                boolean accepted = payments.requestEviction(state.data.property().propertyId(), player.getUniqueId(), resident.id(),
                        plugin.purchasePrices().evictionCents(), () -> player.isOnline() && !player.isDead()
                            && Bukkit.getPlayer(player.getUniqueId()) == player && player.hasPermission("prosperitynpc.use")
                            && plugin.propertyBackend().ownerReadAt(player.getLocation(), player.getUniqueId())
                                .filter(state.authority::sameIncarnation).isPresent(), message -> note(player, message));
                retire(state.ticket);
                note(player, accepted ? "Checking the selected tenancy and displayed cost. Wait for the verified result."
                        : "Property payments are busy or unavailable. No payment was started.");
            }
            return;
        }
        if (slot == 35) {
            if (!billsOn()) {
                note(player, "Configured bills are unavailable.");
                return;
            }
            state.bills = new Bills(plugin.billing());
            loadBills(state, 0, null);
            return;
        }
        var selectedResident = OwnerDeskMenu.evictionTarget(state.data, slot);
        if (selectedResident != null) {
            state.eviction = selectedResident;
            try {
                boolean shown = nativeUi != null && nativeUi.handles(player.getUniqueId())
                        ? nativeUi.confirmEviction(player, token, state.eviction, () -> active.get(player.getUniqueId()) == state
                                && valid(state))
                        : menu.confirmEviction(player, token, state.eviction);
                if (!shown) retire(state.ticket);
            } catch (RuntimeException | LinkageError failure) {
                retire(state.ticket);
                note(player, "The tenancy confirmation could not open. No payment started.");
            }
            return;
        }
        if (slot == 24 || slot == 26 || slot == 28) {
            var payments = plugin.utilityPayments();
            if (payments == null) {
                note(player, "Property payments are unavailable.");
                return;
            }
            var type = slot == 28 ? null : slot == 24 ? UpgradeType.FACILITIES
                    : UpgradeType.ADVERTISING;
            Integer level = type == null ? null : OwnerDeskMenu.nextLevel(state.data, type);
            if (level != null && level > 3) {
                note(player, "This upgrade is already at maximum level.");
                return;
            }
            long cost = type == null ? plugin.purchasePrices().maintenanceCents() : plugin.purchasePrices().upgradeCents(type, level);
            boolean accepted = payments.requestPurchase(state.data.property().propertyId(), player.getUniqueId(),
                    type == null ? PurchaseRequest.Kind.MAINTAIN : PurchaseRequest.Kind.UPGRADE,
                    type, level, cost, () -> player.isOnline() && !player.isDead() && Bukkit.getPlayer(player.getUniqueId()) == player
                        && player.hasPermission("prosperitynpc.use")
                        && plugin.propertyBackend().ownerReadAt(player.getLocation(), player.getUniqueId())
                            .filter(a -> state.authority.sameIncarnation(a)).isPresent(), message -> note(player, message));
            if (accepted) retire(state.ticket);
            note(player, accepted ? "Checking the displayed benefit and cost. Wait for the verified result."
                    : "Property payments are unavailable or busy. Nothing dispatched.");
            return;
        }
        if (slot == 22 && itemRepairsOn() && !state.data.issues().isEmpty()) {
            openRepairChoice(player, token, state);
            return;
        }
        if (slot == 22) {
            if (state.data.issues().isEmpty()) return;
            var issue = state.data.issues().getFirst();
            var payments = plugin.utilityPayments();
            if (payments == null) {
                note(player, "Repair payments are unavailable.");
                return;
            }
            boolean accepted = payments.requestRepair(state.data.property().propertyId(), player.getUniqueId(), issue.id(),
                    issue.repairCents(),
                    () -> player.isOnline() && !player.isDead() && Bukkit.getPlayer(player.getUniqueId()) == player
                            && player.hasPermission("prosperitynpc.use"), message -> note(player, message));
            if (accepted) retire(state.ticket);
            note(player, accepted ? "Checking the selected repair. Wait for the verified result."
                    : "Repair payments are busy or unavailable. No payment was started by this click.");
            return;
        }
        if (slot == 30) {
            if (!state.data.canPurchaseInsurance()) {
                note(player, "An unexpired insurance term prevents repurchase. Refresh your owner desk.");
                return;
            }
            var payments = plugin.utilityPayments();
            if (payments == null) {
                note(player, "Property payments are unavailable.");
                return;
            }
            boolean accepted = payments.requestInsurance(state.data.property().propertyId(), player.getUniqueId(),
                    state.data.insurance().premiumCents(), () -> player.isOnline() && !player.isDead()
                        && Bukkit.getPlayer(player.getUniqueId()) == player && player.hasPermission("prosperitynpc.use")
                        && plugin.propertyBackend().ownerReadAt(player.getLocation(), player.getUniqueId())
                            .filter(state.authority::sameIncarnation).isPresent(), message -> note(player, message));
            if (accepted) retire(state.ticket);
            note(player, accepted ? "Checking the displayed insurance premium. Wait for the verified result."
                    : "Property payments are unavailable or busy. Nothing dispatched.");
            return;
        }
        if (slot == 42) {
            if (!state.data.canPayUtility()) {
                note(player, "Today's utility bill belongs to earlier ownership and needs review.");
                return;
            }
            var payments = plugin.utilityPayments();
            if (payments == null) {
                note(player, "Utility payments are unavailable.");
                return;
            }
            long maximum = OwnerDeskMenu.utilityLimit(state.data, plugin.utilityPrices());
            boolean accepted = payments.request(state.data.property().propertyId(), player.getUniqueId(), maximum,
                    () -> player.isOnline() && !player.isDead() && Bukkit.getPlayer(player.getUniqueId()) == player
                            && player.hasPermission("prosperitynpc.use"), message -> note(player, message));
            if (accepted) retire(state.ticket);
            note(player, accepted ? "Checking today's utility bill. Wait for the verified result."
                    : "Utility payments are busy or unavailable. No payment was started by this click.");
            return;
        }
        if (slot == 38) {
            OwnerDeskMenu.text(player, state.data, state.tenantsOnly, List.of(), plugin.purchasePrices(), plugin.utilityPrices());
            retire(state.ticket);
        }
    }
    /** item-repairs.enabled and valid. With it off, slot 22 and the Bedrock repair button only pay cash. */
    boolean itemRepairsOn() { return plugin.itemRepairSettings().active(); }

    /**
     * Desk lines for item repairs: the note while on, and "Items to collect" while returns wait. Empty when neither.
     * Returns stay collectable with the feature off (the runtime then runs only for earlier hand-ins), so the collect
     * line follows the running runtime, not the flag. With the flag off and no runtime there are no lines.
     */
    List<String> itemRepairLines(UUID owner) {
        return OwnerDeskMenu.itemRepairLines(itemRepairsOn(), plugin.itemRepairsRunning(),
                id -> plugin.itemRepairs().hasReturnsPending(id), owner);
    }

    private void openRepairChoice(Player player, UUID token, Active state) {
        var issue = state.data.issues().getFirst();
        var runtime = plugin.itemRepairs();
        var preview = runtime == null || !runtime.ready() ? null : runtime.preview(issue.id(), issue.type(), issue.repairCents());
        if (preview == null) {
            IncidentType type;
            try { type = IncidentType.valueOf(issue.type()); }
            catch (IllegalArgumentException unknown) {
                note(player, ItemRepairCopy.UNAVAILABLE);
                return;
            }
            preview = new ItemRepairRuntime.Preview(issue.id(), type, issue.repairCents(), List.of(),
                    null, ItemRepairRecipes.Unavailable.NO_RECIPE);
        }
        state.repairIssue = issue;
        state.repairPreview = preview;
        try {
            var shownPreview = preview;
            boolean shown = nativeUi != null && nativeUi.handles(player.getUniqueId())
                    ? nativeUi.showRepairChoice(player, token, issue, shownPreview, () -> active.get(player.getUniqueId()) == state
                            && valid(state))
                    : menu.showRepairChoice(player, token, issue, shownPreview);
            if (!shown) {
                retire(state.ticket);
                note(player, ItemRepairCopy.USAGE);
            }
        } catch (RuntimeException | LinkageError failure) {
            retire(state.ticket);
            note(player, ItemRepairCopy.USAGE);
        }
    }

    /** Choice screen: 30 pays cash, 32 hands in the shown items, 34 goes back, 40 closes (handled earlier). */
    private void repairChoice(Player player, UUID token, Active state, int slot) {
        var issue = state.repairIssue;
        var preview = state.repairPreview;
        if (slot == 34) {
            state.repairIssue = null;
            state.repairPreview = null;
            showExisting(state);
            return;
        }
        if (slot != 30 && slot != 32) return;
        if (!plugin.killSwitches().isEconomyEnabled()) {
            note(player, "Property payments are currently disabled. Nothing was charged.");
            return;
        }
        if (slot == 30) {
            var payments = plugin.utilityPayments();
            if (payments == null) {
                note(player, "Repair payments are unavailable.");
                return;
            }
            boolean accepted = payments.requestRepair(state.data.property().propertyId(), player.getUniqueId(), issue.id(),
                    issue.repairCents(),
                    () -> player.isOnline() && !player.isDead() && Bukkit.getPlayer(player.getUniqueId()) == player
                            && player.hasPermission("prosperitynpc.use"), message -> note(player, message));
            if (accepted) retire(state.ticket);
            note(player, accepted ? "Checking the selected repair. Wait for the verified result."
                    : "Repair payments are busy or unavailable. No payment was started by this click.");
            return;
        }
        var runtime = plugin.itemRepairs();
        // Close the desk first: a hand-in needs no open container.
        retire(state.ticket);
        if (runtime == null) {
            note(player, ItemRepairCopy.UNAVAILABLE);
            return;
        }
        runtime.request(player, issue.id(), preview, () -> player.isOnline() && !player.isDead()
                && Bukkit.getPlayer(player.getUniqueId()) == player && player.hasPermission("prosperitynpc.use")
                && plugin.propertyBackend().ownerReadAt(player.getLocation(), player.getUniqueId())
                    .filter(state.authority::sameIncarnation).isPresent(), message -> note(player, message));
    }

    private void loadBills(Active state, int page, LocalDate before) {
        Bills bills = state.bills;
        if (!billAuthority(state) || !valid(state)) { retire(state.ticket); return; }
        if (bills.loading) return;
        if (now() - bills.readAt < 2_000) {
            note(state.session, "Please wait a moment before checking another bill page.");
            showBills(state);
            return;
        }
        var old = state.ticket;
        if (!rotateView(state)) return;
        var ticket = state.ticket;
        menu.closeOwned(state.session, old.token());
        if (!billVisible(state, ticket)) { retire(ticket); return; }
        var home = state.data.property();
        bills.page = page;
        if (page == bills.pages.size()) bills.pages.add(before);
        else bills.pages.set(page, before);
        bills.selected = null;
        bills.loading = true;
        bills.readAt = now();
        operations.put(ticket.actor(), ticket.token());
        note(state.session, "Checking your bills...");
        try {
            worker.execute(() -> {
                if (closed || !ticket.token().equals(operations.get(ticket.actor()))) return;
                ChargeInvoiceRepository.OwnerView result = null;
                boolean failed = false;
                try { result = bills.runtime.invoices().ownerView(home.propertyId(), home.ownerUuid(), home.ownershipEpoch(), before); }
                catch (Exception unavailable) { failed = true; }
                var view = result;
                boolean error = failed;
                if (closed) return;
                try {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        if (!operations.remove(ticket.actor(), ticket.token()) || active.get(ticket.actor()) != state
                                || state.ticket != ticket || state.bills != bills) return;
                        bills.loading = false;
                        if (!billVisible(state, ticket) || now() - bills.readAt >= OwnerDeskSessions.READ_MS) {
                            retire(ticket);
                            return;
                        }
                        if (error) {
                            retire(ticket);
                            note(state.session, "Bills could not be read right now. Nothing changed.");
                            return;
                        }
                        // With on-unpaid: hold-rent, the bill pages say which bills are pausing the home's rent.
                        bills.view = view.withHoldRent(bills.runtime.holdsRentNow());
                        showBills(state);
                    });
                } catch (IllegalStateException shutdown) { operations.remove(ticket.actor(), ticket.token()); }
            });
        } catch (RejectedExecutionException full) {
            retire(ticket);
            note(state.session, "The owner desk is busy. Try again shortly.");
        }
    }

    /** A paused row for this region under the configured backend. A row under another backend is not this desk's. */
    private boolean pausedHere(java.util.UUID world, String region) throws java.sql.SQLException {
        var repository = plugin.propertyRepository();
        if (repository == null) return false;
        try {
            return repository.findByWorldRegion(world, region, repository.backendId()).filter(p -> !p.enabled()).isPresent();
        } catch (PropertyRepository.EnrolledUnderOtherBackend elsewhere) {
            return false;
        }
    }

    private void showBills(Active state) {
        Bills bills = state.bills;
        if (!valid(state) || !billAuthority(state) || !rotateView(state)) { retire(state.ticket); return; }
        var ticket = state.ticket;
        BooleanSupplier authorized = () -> billVisible(state, ticket);
        try {
            var page = new BillPage(bills.view, bills.day, bills.page > 0,
                    OwnerDeskMenu.utilityLimit(state.data, plugin.utilityPrices()), state.data.canPayUtility());
            boolean nativePlayer = nativeUi != null && nativeUi.handles(ticket.actor());
            boolean shown;
            if (nativePlayer) shown = bills.selected == null ? nativeUi.showBills(state.session, ticket.token(), page, authorized)
                    : nativeUi.confirmBill(state.session, ticket.token(), page, bills.selected, authorized);
            else shown = bills.selected == null ? menu.showBills(state.session, ticket, page, authorized)
                    : menu.confirmBill(state.session, ticket, page, bills.selected, authorized);
            if (!shown || !authorized.getAsBoolean()) retire(ticket);
        } catch (RuntimeException | LinkageError unavailable) {
            retire(ticket);
            note(state.session, "The bill view could not open. Use /property bills.");
        }
    }

    private boolean billAuthority(Active state) {
        var bills = state.bills;
        if (bills == null || !allowed(state.session) || Bukkit.getPlayer(state.ticket.actor()) != state.session
                || plugin.billing() != bills.runtime || !bills.runtime.enabledNow() || !bills.day.equals(bills.runtime.today())
                || plugin.propertyService() == null) return false;
        var authority = plugin.propertyBackend().ownerReadAt(state.session.getLocation(), state.ticket.actor());
        var home = state.data.property();
        var current = plugin.propertyService().findByWorldRegion(home.worldUuid(), home.regionId());
        return authority.isPresent() && state.authority.sameIncarnation(authority.get()) && current.isPresent()
                && current.get().enabled() && home.propertyId().equals(current.get().propertyId())
                && home.ownerUuid().equals(current.get().ownerUuid()) && home.ownershipEpoch() == current.get().ownershipEpoch();
    }

    private boolean billVisible(Active state, OwnerDeskSessions.Ticket ticket) {
        return billAuthority(state) && active.get(ticket.actor()) == state && state.ticket == ticket && sessions.valid(ticket, now());
    }

    private void billAction(Active state, UUID token, int slot) {
        Bills bills = state.bills;
        if (!billVisible(state, state.ticket) || !state.ticket.token().equals(token)) {
            retire(state.ticket);
            note(state.session, "The bill view changed or expired. Open your property again.");
            return;
        }
        if (bills.loading) return;
        if (slot == 34) {
            if (bills.selected != null) {
                bills.selected = null;
                showBills(state);
            } else {
                state.bills = null;
                if (rotateView(state)) showExisting(state);
            }
            return;
        }
        if (bills.selected == null) {
            if (slot == 30 && bills.page > 0) loadBills(state, bills.page - 1, bills.pages.get(bills.page - 1));
            else if (slot == 32 && bills.view.hasOlder())
                loadBills(state, bills.page + 1, bills.view.invoices().getLast().day());
            else if (slot >= 9 && slot < 9 + bills.view.invoices().size()) {
                bills.selected = bills.view.invoices().get(slot - 9);
                showBills(state);
            } else if (slot == 42) {
                if (!state.data.canPayUtility()) {
                    note(state.session, "Today's utility bill belongs to earlier ownership and needs review.");
                    return;
                }
                var payments = plugin.utilityPayments();
                boolean accepted = payments != null && payments.request(state.data.property().propertyId(), state.ticket.actor(),
                        OwnerDeskMenu.utilityLimit(state.data, plugin.utilityPrices()), () -> billAuthority(state), message -> note(state.session, message));
                retire(state.ticket);
                note(state.session, accepted ? "Checking today's utility bill. Wait for the payment result."
                        : "Utility payments are busy or unavailable. No payment was started.");
            }
            return;
        }
        var invoice = bills.selected;
        var payment = bills.view.payments().get(invoice.invoiceId());
        if (slot == 38 && payment != null) {
            var receipts = plugin.purchaseService();
            retire(state.ticket);
            if (receipts != null) receipts.showReceipt(state.session, payment.requestId());
            else note(state.session, "Keep your reference: /property receipt " + payment.requestId());
        } else if (slot == 32 && BillText.payable(invoice, payment, bills.day)) {
            var payments = plugin.utilityPayments();
            // The view closes on admission; player and ownership checks continue through the debit.
            boolean accepted = payments != null && payments.requestBill(state.data.property().propertyId(), state.ticket.actor(),
                    invoice.invoiceId(), invoice.totalCents(), () -> billAuthority(state), message -> note(state.session, message));
            retire(state.ticket);
            note(state.session, accepted ? "Checking the selected bill and displayed maximum. Wait for the payment result."
                    : "Payments are busy or unavailable. No payment was started.");
        } else showBills(state);
    }

    void viewClosed(UUID actor, UUID token) {
        Active state = active.get(actor);
        if (state != null && state.ticket.token().equals(token)) retire(state.ticket, false);
    }
    private boolean rotateView(Active state) {
        var old = state.ticket;
        var next = sessions.rotate(old, now());
        if (next.isEmpty()) {
            retire(old);
            return false;
        }
        state.ticket = next.get();
        operations.remove(old.actor(), old.token());
        return true;
    }
    private void showExisting(Active state) {
        try {
            boolean shown = nativeUi != null && nativeUi.handles(state.ticket.actor())
                    ? nativeUi.show(state.session, state.ticket.token(), state.data,
                        () -> active.get(state.ticket.actor()) == state && valid(state))
                    : menu.show(state.session, state.ticket, state.data,
                        () -> active.get(state.ticket.actor()) == state && valid(state));
            if (!shown) retire(state.ticket);
        } catch (RuntimeException | LinkageError failure) {
            retire(state.ticket);
            note(state.session, "The owner desk could not reopen. No payment started.");
        }
    }
    public void dismiss(Player player) {
        requireMain();
        sessions.current(player.getUniqueId()).ifPresent(this::retire);
    }
    private void retire(OwnerDeskSessions.Ticket ticket) {
        retire(ticket, true);
    }
    private void retire(OwnerDeskSessions.Ticket ticket, boolean closeView) {
        if (!sessions.remove(ticket)) return;
        operations.remove(ticket.actor(), ticket.token());
        Active state = active.remove(ticket.actor());
        if (state != null && closeView) menu.closeOwned(state.session, ticket.token());
    }
    private void sweep() {
        for (var ticket : sessions.all()) {
            Active state = active.get(ticket.actor());
            if (!valid(state) || OwnerDeskSessions.undelivered(state.data != null, state.paused,
                    sessions.mayDeliver(ticket, now())) || (state.bills != null && state.bills.loading
                    && now() - state.bills.readAt >= OwnerDeskSessions.READ_MS)) retire(ticket);
        }
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        dismiss(event.getPlayer());
        sessions.quit(event.getPlayer().getUniqueId());
    }
    @EventHandler public void world(PlayerChangedWorldEvent event) { dismiss(event.getPlayer()); }
    @EventHandler public void otherInventory(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        Active state = active.get(player.getUniqueId());
        if (state != null && !menu.belongs(event.getInventory(), state.ticket.token())) retire(state.ticket, false);
    }
    private static void note(Player player, String message) {
        if (player != null) player.sendMessage(Component.text(message, NamedTextColor.GRAY));
    }
    @Override public void close() {
        requireMain();
        closed = true;
        watcher.cancel();
        for (var ticket : sessions.all()) retire(ticket);
        HandlerList.unregisterAll(this);
        HandlerList.unregisterAll(menu);
        sessions.clear();
        active.clear();
        operations.clear();
        worker.shutdownNow();
        // Read-only late work cannot publish after close; never join SQL from the main thread.
    }
}

package com.prosperitysmp.npc.interaction;

import com.prosperitysmp.npc.ProsperityNpcPlugin;
import com.prosperitysmp.npc.economy.ItemRepairCopy;
import com.prosperitysmp.npc.economy.ItemRepairRuntime;
import com.prosperitysmp.npc.property.OwnerDeskData;
import com.prosperitysmp.npc.util.Money;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.scheduler.BukkitTask;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.response.SimpleFormResponse;
import org.geysermc.cumulus.response.result.FormResponseResult;
import org.geysermc.floodgate.api.FloodgateApi;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** Optional Floodgate boundary. Forms only dispatch into the existing authorized controllers. */
public final class BedrockNpcUi implements ResidentEncounterController.NativeUi, OwnerDeskController.NativeUi, Listener {
    private static final int LIMIT = 128;
    private static final long TTL_NANOS = 90_000_000_000L;
    private static final class Pending {
        final Player login;
        final BooleanSupplier authorized;
        final IntConsumer action;
        final long started = System.nanoTime();
        final AtomicBoolean received = new AtomicBoolean(), abandoned = new AtomicBoolean();
        Pending(Player login, BooleanSupplier authorized, IntConsumer action) {
            this.login = login;
            this.authorized = authorized;
            this.action = action;
        }
    }
    private final ProsperityNpcPlugin plugin;
    private final ResidentEncounterController residents;
    private final OwnerDeskController owners;
    private final FloodgateApi api;
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final BukkitTask watcher;
    private boolean closed;

    public BedrockNpcUi(ProsperityNpcPlugin plugin, ResidentEncounterController residents, OwnerDeskController owners) {
        this.plugin = plugin;
        this.residents = residents;
        this.owners = owners;
        api = Objects.requireNonNull(FloodgateApi.getInstance());
        Bukkit.getPluginManager().registerEvents(this, plugin);
        try { watcher = Bukkit.getScheduler().runTaskTimer(plugin, this::sweep, 20, 20); }
        catch (RuntimeException | LinkageError failure) {
            HandlerList.unregisterAll(this);
            throw failure;
        }
    }
    private static void main() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("NPC form requires main thread"); }
    @Override public boolean handles(UUID player) {
        main();
        return !closed && api.isFloodgatePlayer(player);
    }

    @Override public boolean show(Player player, EncounterSessions.Ticket ticket, EncounterView data) {
        main();
        if (residents == null) return false;
        var topics = List.copyOf(data.topics());
        var actions = List.copyOf(data.actions());
        if (data.confirmation()) {
            // Partnership confirmations: exactly their own buttons (Confirm, Back). Closing the form ends the chat.
            return send(player, data.name(), data.line(), actions.stream().map(ResidentAction::label).toList(),
                    () -> residents.mayPresent(player, ticket), index -> {
                        if (index < 0 || index >= actions.size()) residents.viewClosed(player.getUniqueId(), ticket.token());
                        else residents.act(player, ticket.token(), index);
                    });
        }
        var labels = new ArrayList<>(actions.stream().map(ResidentAction::label).toList());
        labels.addAll(topics.stream().map(EncounterView::topicLabel).toList());
        labels.add("Goodbye");
        return send(player, data.name() + " | NPC", data.occupation() + " | " + data.familiarity() + "\n\n" + data.line(), labels,
                () -> residents.mayPresent(player, ticket), index -> {
                    if (index < 0 || index == topics.size() + actions.size()) residents.viewClosed(player.getUniqueId(), ticket.token());
                    else if (index < actions.size()) residents.act(player, ticket.token(), index);
                    else if (index < topics.size() + actions.size()) residents.choose(player, ticket.token(),
                            topics.get(index - actions.size()));
                });
    }

    @Override public boolean show(Player player, UUID token, OwnerDeskData data, BooleanSupplier authorized) {
        main();
        if (owners == null) return false;
        boolean pause = owners.pauseControls();
        var buttons = ownerButtons(data, pause);
        return send(player, "Property | " + data.property().regionId(),
                ownerBody(data, pause, owners.itemRepairLines(player.getUniqueId())),
                buttons.labels(), authorized, index -> {
                    owners.action(player, token, index >= 0 && index < buttons.slots().size() ? buttons.slots().get(index) : 40);
                });
    }

    record OwnerButtons(List<String> labels, List<Integer> slots) {
        OwnerButtons {
            labels = List.copyOf(labels);
            slots = List.copyOf(slots);
        }
    }
    static OwnerButtons ownerButtons(OwnerDeskData data) { return ownerButtons(data, false); }
    static OwnerButtons ownerButtons(OwnerDeskData data, boolean pauseControls) {
        var labels = new ArrayList<>(List.of("Refresh", "Read full details in chat", "Close",
                "Pay utility up to " + Money.format(OwnerDeskMenu.utilityLimit(data))));
        var slots = new ArrayList<>(List.of(36, 38, 40, 42));
        if (!data.issues().isEmpty()) {
            labels.add(OwnerDeskMenu.repairLabel(data));
            slots.add(22);
        }
        for (int slot : List.of(24, 26, 28)) {
            labels.add(OwnerDeskMenu.purchaseLabel(data, slot));
            slots.add(slot);
        }
        labels.add(OwnerDeskMenu.insuranceLabel(data));
        slots.add(30);
        labels.add("Auto-care settings");
        slots.add(34);
        if (pauseControls) {
            labels.add(OwnerDeskMenu.PAUSE_LABEL);
            slots.add(32);
        }
        for (var room : OwnerDeskMenu.rooms(data)) if (room.resident() != null) {
            labels.add("Manage tenancy: " + room.resident().name());
            slots.add(room.inventorySlot());
        }
        // Four occupied rooms plus an issue fill the form. Drop the read-only buttons first so every button that changes something stays.
        if (labels.size() > 12) {
            labels.remove(1);
            slots.remove(1);
        }
        if (labels.size() > 12) {
            labels.remove(0);
            slots.remove(0);
        }
        // Only reachable with pause controls: the form's own close control replaces the Close button.
        if (labels.size() > 12) {
            labels.remove(0);
            slots.remove(0);
        }
        return new OwnerButtons(labels, slots);
    }

    @Override public boolean confirmPause(Player player, UUID token, BooleanSupplier authorized) {
        main();
        if (owners == null) return false;
        return send(player, "Pause property", OwnerDeskMenu.pauseBody("this property"),
                List.of(OwnerDeskMenu.PAUSE_CONFIRM, "Keep it running"), authorized,
                index -> owners.action(player, token, index == 0 ? 32 : index == 1 ? 34 : 40));
    }

    @Override public boolean showPaused(Player player, UUID token, String region, BooleanSupplier authorized) {
        main();
        if (owners == null) return false;
        return send(player, "Property | Paused", OwnerDeskMenu.pausedBody(region),
                List.of(OwnerDeskMenu.RESUME_LABEL, "Close"), authorized,
                index -> owners.action(player, token, index == 0 ? 22 : 40));
    }

    @Override public boolean showAutoCare(Player player, UUID token, OwnerDeskData data, BooleanSupplier authorized) {
        main();
        if (owners == null) return false;
        var slots = List.of(22, 24, 26, 30, 38, 34, 40);
        return send(player, "Auto-care | " + data.property().regionId(),
                "Authorize a daily ceiling, not a flat fee. Utilities, one cash repair and maintenance below 80 condition. "
                + "No upgrades, insurance or eviction. No automatic payment retries. OFF stops new charges; accepted debits still settle.",
                List.of("Authorize up to $10.00 / day", "Authorize up to $30.00 / day", "Authorize up to $60.00 / day",
                        "Turn auto-care OFF", "Check saved consent", "Back to property", "Close"), authorized,
                index -> owners.action(player, token, index >= 0 && index < slots.size() ? slots.get(index) : 40));
    }

    @Override public boolean showRepairChoice(Player player, UUID token, OwnerDeskData.Issue issue,
                                              ItemRepairRuntime.Preview preview, BooleanSupplier authorized) {
        main();
        if (owners == null) return false;
        var form = repairChoiceForm(issue, preview);
        return send(player, form.title(), form.body(), form.labels(), authorized,
                index -> owners.action(player, token, index >= 0 && index < REPAIR_CHOICE_SLOTS.size() ? REPAIR_CHOICE_SLOTS.get(index)
                        : 40));
    }

    /** Bedrock repair choice: buttons in the Java order, and the form's own close is Close. */
    static final List<Integer> REPAIR_CHOICE_SLOTS = List.of(30, 32, 34);
    record RepairChoiceForm(String title, String body, List<String> labels) { }
    static RepairChoiceForm repairChoiceForm(OwnerDeskData.Issue issue, ItemRepairRuntime.Preview preview) {
        return new RepairChoiceForm(ItemRepairCopy.title(issue.id()),
                String.join("\n", OwnerDeskMenu.repairChoiceLines(issue, preview)),
                List.of(ItemRepairCopy.payCash(issue.repairCents()), OwnerDeskMenu.handInLabel(preview),
                        ItemRepairCopy.BACK));
    }

    @Override public boolean confirmEviction(Player player, UUID token, OwnerDeskData.Resident resident, BooleanSupplier authorized) {
        main();
        if (owners == null) return false;
        return send(player, "Tenancy | " + resident.name(),
                "End " + resident.name() + "'s current tenancy for $10.00? Their identity and history are retained. "
                + "An unavailable tenancy is refused without a new charge.",
                List.of(OwnerDeskMenu.evictionLabel(resident), "Keep tenancy"), authorized,
                index -> owners.action(player, token, index == 0 ? 32 : index == 1 ? 34 : 40));
    }

    static String ownerBody(OwnerDeskData data) { return ownerBody(data, false); }
    static String ownerBody(OwnerDeskData data, boolean pauseControls) { return ownerBody(data, pauseControls, List.of()); }
    /** itemRepairs: the item-repairs desk lines (none while that feature is off and nothing waits). */
    static String ownerBody(OwnerDeskData data, boolean pauseControls, List<String> itemRepairs) {
        var lines = new ArrayList<String>();
        lines.add("Residents: " + data.residents().size() + " / " + data.property().capacity());
        lines.add("Condition: " + data.property().conditionScore() + " / 100");
        for (var resident : data.residents()) lines.add("Room " + (resident.slot() + 1) + ": " + resident.name() + " | "
                + EncounterView.label(resident.mood()));
        lines.add("Open service issues: " + data.issues().size());
        lines.addAll(OwnerDeskMenu.issueLines(data, itemRepairs));
        lines.add(OwnerDeskMenu.insuranceLabel(data));
        lines.addAll(OwnerDeskMenu.insuranceLines(data));
        lines.addAll(OwnerDeskMenu.housingLines(data));
        lines.addAll(OwnerDeskMenu.statementAndLinkLines(data));
        lines.add("Utility payment authorizes today's bill up to the button's maximum. A higher bill is refused.");
        lines.add("Repair buttons authorize only the named incident up to the stated maximum. "
                + "Upgrade and maintenance buttons show the maximum charge.");
        if (pauseControls) lines.add("Asking rent: " + Money.format(data.property().askingRentCents()) + " / resident / day.");
        data.pendingRent().ifPresent(pending -> lines.add(pending.line()));
        lines.add("Set asking rent with /property rent <dollars-per-day> while standing in this property.");
        if (pauseControls) lines.add("Pause takes this property out of the NPC economy until you resume it.");
        return String.join("\n", lines);
    }

    private boolean send(Player player, String title, String body, List<String> labels, BooleanSupplier authorized, IntConsumer action) {
        main();
        sweep();
        if (closed || !player.isOnline() || Bukkit.getPlayer(player.getUniqueId()) != player || !authorized.getAsBoolean()
                || pending.containsKey(player.getUniqueId()) || pending.size() >= LIMIT) return false;
        if (labels.isEmpty() || labels.size() > 12 || title.length() > 160 || body.length() > 4096) return false;
        Pending request = new Pending(player, authorized, action);
        var form = form(title, body, labels, index -> receive(request, index));
        if (!authorized.getAsBoolean()) return false;
        pending.put(player.getUniqueId(), request);
        boolean accepted = false;
        try {
            accepted = api.sendForm(player.getUniqueId(), form);
            return accepted;
        }
        finally { if (!accepted) remove(request); }
    }

    static SimpleForm form(String title, String body, List<String> labels, IntConsumer response) {
        var buttons = List.copyOf(labels);
        var builder = SimpleForm.builder().title(title).content(body);
        buttons.forEach(builder::button);
        builder.validResultHandler((Consumer<SimpleFormResponse>) result -> response.accept(
                result.clickedButtonId() >= 0 && result.clickedButtonId() < buttons.size() ? result.clickedButtonId() : -1));
        builder.closedOrInvalidResultHandler((Consumer<FormResponseResult<SimpleFormResponse>>) result -> response.accept(-1));
        return builder.build();
    }

    private void receive(Pending request, int choice) {
        // Cumulus callbacks may run on a network thread; do not touch Bukkit state here.
        if (!request.received.compareAndSet(false, true)) return;
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (pending.get(request.login.getUniqueId()) != request) return;
                remove(request);
                if (closed || !request.login.isOnline() || Bukkit.getPlayer(request.login.getUniqueId()) != request.login) return;
                if (System.nanoTime() - request.started >= TTL_NANOS || (choice >= 0 && !request.authorized.getAsBoolean())) {
                    request.action.accept(-1);
                    request.login.sendMessage(Component.text(
                            "This view expired or is no longer available. Approach the resident or property and open it again.",
                            NamedTextColor.GRAY));
                    return;
                }
                request.action.accept(choice);
            });
        } catch (RuntimeException | LinkageError shutdown) { request.abandoned.set(true); }
    }
    private void remove(Pending request) {
        pending.remove(request.login.getUniqueId(), request);
        request.received.set(true);
    }
    private void sweep() {
        main();
        for (var request : List.copyOf(pending.values())) {
            if (request.abandoned.get() || !request.login.isOnline() || Bukkit.getPlayer(request.login.getUniqueId()) != request.login
                    || System.nanoTime() - request.started >= TTL_NANOS) remove(request);
        }
    }
    private void departed(Player player) {
        var request = pending.get(player.getUniqueId());
        if (request != null && request.login == player) remove(request);
    }
    @EventHandler public void quit(PlayerQuitEvent event) { departed(event.getPlayer()); }
    @EventHandler public void world(PlayerChangedWorldEvent event) { departed(event.getPlayer()); }
    @Override public void close() {
        main();
        if (closed) return;
        closed = true;
        watcher.cancel();
        HandlerList.unregisterAll(this);
        for (var request : List.copyOf(pending.values())) remove(request);
        // Never close all Floodgate forms: another plugin may own the currently visible form.
    }
}

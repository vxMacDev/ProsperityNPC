package com.prosperitysmp.npc.interaction;

import com.prosperitysmp.npc.resident.ResidentDialogue;
import net.kyori.adventure.text.Component;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Java chest presentation; items never represent an entitlement or a payment. */
final class ResidentMenu implements Listener {
    static final class View implements InventoryHolder {
        final EncounterSessions.Ticket ticket;
        final Map<Integer, ResidentDialogue.Topic> actions;
        int townActions;
        boolean tipsShown;
        boolean contractsShown;
        private Inventory inventory;
        View(EncounterSessions.Ticket ticket) {
            this.ticket = ticket;
            actions = Map.of(10, ResidentDialogue.Topic.ROUTINE, 12, ResidentDialogue.Topic.HOME,
                    14, ResidentDialogue.Topic.HISTORY, 16, ResidentDialogue.Topic.BUSINESS,
                    20, ResidentDialogue.Topic.ABOUT, 26, ResidentDialogue.Topic.COMMERCE,
                    6, ResidentDialogue.Topic.TIPS, 2, ResidentDialogue.Topic.CONTRACTS);
        }
        @Override public Inventory getInventory() { return inventory; }
    }

    private final ResidentEncounterController encounters;
    ResidentMenu(ResidentEncounterController encounters) { this.encounters = encounters; }

    boolean show(Player player, EncounterSessions.Ticket ticket, EncounterView data, BooleanSupplier authorized) {
        View view = new View(ticket);
        String title = data.name().length() > 25 ? data.name().substring(0, 25) : data.name();
        view.inventory = Bukkit.createInventory(view, 27, Component.text(title + " | NPC"));
        return OwnerDeskPublication.publish(() -> {
            player.openInventory(view.inventory);
            return player.getOpenInventory().getTopInventory() == view.inventory;
        }, authorized, () -> populate(view, data), () -> {
            view.inventory.clear();
            if (player.getOpenInventory().getTopInventory() == view.inventory) player.closeInventory();
        });
    }

    private static void populate(View view, EncounterView data) {
        List<String> body = new ArrayList<>();
        body.add(data.occupation() + " | " + data.familiarity());
        body.add("");
        body.addAll(EncounterView.wrap(data.line(), 38));
        view.inventory.setItem(4, item(Material.WRITABLE_BOOK, data.name(), body));
        view.inventory.setItem(10, item(Material.CLOCK, EncounterView.topicLabel(ResidentDialogue.Topic.ROUTINE), List.of()));
        view.inventory.setItem(12, item(Material.OAK_DOOR, EncounterView.topicLabel(ResidentDialogue.Topic.HOME), List.of()));
        view.inventory.setItem(14, item(Material.BOOK, EncounterView.topicLabel(ResidentDialogue.Topic.HISTORY), List.of()));
        view.inventory.setItem(16, item(Material.EMERALD, EncounterView.topicLabel(ResidentDialogue.Topic.BUSINESS), List.of()));
        view.inventory.setItem(20, item(Material.NAME_TAG, EncounterView.topicLabel(ResidentDialogue.Topic.ABOUT), List.of()));
        view.inventory.setItem(22, item(Material.BARRIER, "Goodbye", List.of()));
        view.inventory.setItem(26, item(Material.CHEST, EncounterView.topicLabel(ResidentDialogue.Topic.COMMERCE), List.of()));
        view.tipsShown = data.topics().contains(ResidentDialogue.Topic.TIPS);
        if (view.tipsShown)
            view.inventory.setItem(6, item(Material.LANTERN, EncounterView.topicLabel(ResidentDialogue.Topic.TIPS), List.of()));
        view.contractsShown = data.topics().contains(ResidentDialogue.Topic.CONTRACTS);
        if (view.contractsShown)
            view.inventory.setItem(2, item(Material.MAP, EncounterView.topicLabel(ResidentDialogue.Topic.CONTRACTS), List.of()));
        view.townActions = data.actions().size();
        for (int index = 0; index < data.actions().size(); index++)
            view.inventory.setItem(18 + index * 3, item(Material.PAPER, data.actions().get(index).label(), List.of()));
    }

    void closeOwned(Player player, UUID token) {
        var inventory = player.getOpenInventory().getTopInventory();
        if (inventory.getHolder() instanceof View view && view.ticket.token().equals(token)) {
            inventory.clear();
            player.closeInventory();
        }
    }

    private static ItemStack item(Material type, String name, List<String> lore) {
        ItemStack result = new ItemStack(type);
        var meta = result.getItemMeta();
        meta.displayName(Component.text(name, NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        meta.lore(lore.stream().map(line -> Component.text(line, NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false)).toList());
        result.setItemMeta(meta);
        return result;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void click(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof View view)) return;
        boolean vetoed = event.isCancelled();
        event.setCancelled(true);
        if (vetoed || !(event.getWhoClicked() instanceof Player player)
                || !player.getUniqueId().equals(view.ticket.playerId())
                || event.getClickedInventory() != view.inventory || event.getClick() != ClickType.LEFT) return;
        if (event.getRawSlot() == 22) {
            encounters.leave(player, view.ticket.token());
            return;
        }
        for (int index = 0; index < view.townActions; index++) if (event.getRawSlot() == 18 + index * 3) {
            encounters.act(player, view.ticket.token(), index);
            return;
        }
        if (event.getRawSlot() == 6 && !view.tipsShown) return;
        if (event.getRawSlot() == 2 && !view.contractsShown) return;
        var topic = view.actions.get(event.getRawSlot());
        if (topic != null) encounters.choose(player, view.ticket.token(), topic);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void drag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof View) event.setCancelled(true);
    }

    @EventHandler
    public void close(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof View view) {
            encounters.viewClosed(event.getPlayer().getUniqueId(), view.ticket.token());
        }
    }
}

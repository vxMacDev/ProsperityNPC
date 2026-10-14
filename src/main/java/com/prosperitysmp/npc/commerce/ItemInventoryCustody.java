package com.prosperitysmp.npc.commerce;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/** One adjacent main-thread mutation, across storage and ender chest; never a world drop. */
public final class ItemInventoryCustody {
    /** One plain material and how many to take. No feature-specific types, so every hand-in can use it. */
    public record Take(org.bukkit.Material material, int quantity) {
        public Take {
            java.util.Objects.requireNonNull(material);
            if (quantity < 1 || quantity > ItemCustody.MAX_QUANTITY) throw new IllegalArgumentException("Invalid take quantity");
        }
    }
    private ItemInventoryCustody() { }

    /** A fresh one-item stack: only stacks similar to it count, so named, enchanted or damaged items never do. */
    public static ItemStack prototype(org.bukkit.Material material) {
        return new ItemStack(material, 1);
    }

    /** Main only: plain units in inventory storage slots 0-35. Armor, offhand, cursor and ender chest are not counted. */
    public static int storageUnits(Player player, org.bukkit.Material material) {
        requireMain();
        ItemStack prototype = prototype(material);
        int total = 0;
        for (ItemStack stack : player.getInventory().getStorageContents())
            if (stack != null && !stack.isEmpty() && stack.isSimilar(prototype)) total = Math.addExact(total, stack.getAmount());
        return total;
    }

    /** Main only: plain units in storage plus the ender chest, the scope the save proof reads back. */
    public static int totalUnits(Player player, org.bukkit.Material material) {
        requireMain();
        return count(player, prototype(material));
    }

    /**
     * Main only, one tick: takes exactly each quantity of plain items from inventory storage, highest slot first so
     * the hotbar is touched last, then saves the player. Nothing is taken unless storage holds every line. Never
     * touches armor, offhand, the cursor or the ender chest, and never drops anything. Any failure after the first
     * slot change is reported as returnedNormally=false. durable starts true; the caller clears it unless the
     * saved file is proven.
     */
    public static ItemCollectionEvidence collect(Player player, java.util.UUID collectionId, java.util.List<Take> takes) {
        requireMain();
        java.util.Objects.requireNonNull(collectionId);
        if (takes.isEmpty() || takes.size() > ItemCollectionEvidence.MAX_LINES
                || takes.stream().map(Take::material).distinct().count() != takes.size())
            throw new IllegalArgumentException("Invalid collection lines");
        var prototypes = takes.stream().map(t -> prototype(t.material())).toList();
        var before = new java.util.ArrayList<Integer>();
        for (ItemStack prototype : prototypes) before.add(count(player, prototype));
        String beforeHash = inventoryHash(player);
        for (Take take : takes) {
            if (storageUnits(player, take.material()) < take.quantity())
                return new ItemCollectionEvidence(player.getUniqueId(), collectionId, beforeHash, beforeHash, before, before, true, true);
        }
        boolean normal = false;
        try {
            var inventory = player.getInventory();
            for (int line = 0; line < takes.size(); line++) {
                int left = takes.get(line).quantity();
                ItemStack prototype = prototypes.get(line);
                ItemStack[] storage = inventory.getStorageContents();
                for (int slot = storage.length - 1; slot >= 0 && left > 0; slot--) {
                    ItemStack stack = storage[slot];
                    if (stack == null || stack.isEmpty() || !stack.isSimilar(prototype)) continue;
                    int taken = Math.min(left, stack.getAmount());
                    inventory.setItem(slot, taken == stack.getAmount() ? null : stack.asQuantity(stack.getAmount() - taken));
                    left -= taken;
                }
                if (left != 0) throw new IllegalStateException("Storage changed during the take");
            }
            // The after marker lets the proof tell this save from any older file. Not durable proof by itself: the caller
            // reads back and forces the saved player file before any SQL apply.
            mark(player, ItemSaveProof.marker(collectionId, true));
            player.saveData();
            normal = true;
        } catch (RuntimeException | LinkageError uncertain) { /* Keep the adjacent evidence; never retry. */ }
        var after = new java.util.ArrayList<Integer>();
        for (ItemStack prototype : prototypes) after.add(count(player, prototype));
        return new ItemCollectionEvidence(player.getUniqueId(), collectionId, beforeHash, inventoryHash(player),
                before, after, normal, true);
    }

    /**
     * Main only, under the player's inventory gate and before a take: marks the saved data as this hand-in's before-take
     * save and saves. Returns the full state of the inventory (armor and offhand included) and ender chest, which the
     * take must still find. Not proof by itself: ItemSaveProof.baseline reads the file back and checks the marker.
     */
    public static String saveBeforeTake(Player player, java.util.UUID collectionId) {
        requireMain();
        mark(player, ItemSaveProof.marker(collectionId, false));
        player.saveData();
        return inventoryHash(player);
    }

    /** Main only: the full state of the inventory and ender chest, to compare with what saveBeforeTake returned. */
    public static String inventoryState(Player player) {
        requireMain();
        return inventoryHash(player);
    }

    /**
     * Main only, under the player's inventory gate and before a REPAIR_RETURN delivery: marks the saved data as this
     * return's before-delivery save and saves. Not proof by itself: ItemSaveProof.returnBaseline reads the file back
     * and checks the marker.
     */
    static void saveBeforeReturn(Player player, java.util.UUID requestNonce) {
        requireMain();
        mark(player, ItemSaveProof.RETURN_MARKER_KEY, ItemSaveProof.marker(requestNonce, false));
        player.saveData();
    }

    /**
     * Main only, in the delivery's own tick and right before {@link #deliver}: the return's after marker, so the save
     * deliver makes carries it and the proof can tell that save from any older file.
     */
    static void markReturnDelivered(Player player, java.util.UUID requestNonce) {
        requireMain();
        mark(player, ItemSaveProof.RETURN_MARKER_KEY, ItemSaveProof.marker(requestNonce, true));
    }

    private static void mark(Player player, String marker) {
        mark(player, ItemSaveProof.MARKER_KEY, marker);
    }

    private static void mark(Player player, String keyName, String marker) {
        org.bukkit.NamespacedKey key = Objects.requireNonNull(org.bukkit.NamespacedKey.fromString(keyName));
        player.getPersistentDataContainer().set(key, org.bukkit.persistence.PersistentDataType.STRING, marker);
    }

    static ItemStack decode(ItemCustody custody) {
        requireMain();
        ItemStack item = ItemStack.deserializeBytes(custody.payload().bytes());
        if (item == null || item.isEmpty() || item.getAmount() != custody.quantity()
                || !MessageDigest.isEqual(item.serializeAsBytes(), custody.payload().bytes()))
            throw new IllegalArgumentException("Stored item payload changed or has invalid quantity");
        return item;
    }

    static boolean canReceive(Player player, ItemStack item) {
        requireMain();
        return capacity(player.getInventory(), item)
                + capacity(player.getEnderChest(), item) >= item.getAmount();
    }

    static ItemDeliveryEvidence deliver(Player player, ItemCustody custody) {
        requireMain();
        if (!player.getUniqueId().equals(custody.playerId())) throw new IllegalArgumentException("Custody player mismatch");
        ItemStack item = decode(custody);
        int before = count(player, item);
        String beforeHash = inventoryHash(player);
        if (!canReceive(player, item))
            return new ItemDeliveryEvidence(player.getUniqueId(), custody.payload().sha256(), beforeHash,
                    beforeHash, before, before, custody.quantity(), true);
        int leftover = custody.quantity();
        boolean normal = false;
        try {
            var missing = player.getInventory().addItem(item.clone());
            leftover = missing.values().stream().mapToInt(ItemStack::getAmount).sum();
            if (leftover > 0) {
                var ender = player.getEnderChest().addItem(item.asQuantity(leftover));
                leftover = ender.values().stream().mapToInt(ItemStack::getAmount).sum();
            }
            // This call alone is NOT durable proof. Runtime reads and forces the exact player-data
            // file before SQL acknowledgement. A crash in that gap is ambiguous, never replayable.
            player.saveData();
            normal = true;
        } catch (RuntimeException | LinkageError uncertain) { /* Preserve adjacent post-call evidence. */ }
        return new ItemDeliveryEvidence(player.getUniqueId(), custody.payload().sha256(), beforeHash,
                inventoryHash(player), before, count(player, item), leftover, normal);
    }

    private static int capacity(Inventory inventory, ItemStack item) {
        int limit = Math.max(0, Math.min(inventory.getMaxStackSize(), item.getMaxStackSize()));
        int total = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack == null || stack.isEmpty()) total += limit;
            else if (stack.isSimilar(item)) total += Math.max(0, Math.min(stack.getMaxStackSize(), limit) - stack.getAmount());
        }
        return total;
    }

    private static int count(Player player, ItemStack item) {
        int total = 0;
        for (Inventory inventory : new Inventory[] {player.getInventory(), player.getEnderChest()})
            for (ItemStack stack : inventory.getStorageContents())
                if (stack != null && !stack.isEmpty() && stack.isSimilar(item)) total = Math.addExact(total, stack.getAmount());
        return total;
    }

    private static String inventoryHash(Player player) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            for (Inventory inventory : new Inventory[] {player.getInventory(), player.getEnderChest()}) {
                byte[] bytes = ItemStack.serializeItemsAsBytes(inventory.getContents());
                hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                hash.update(bytes);
            }
            return HexFormat.of().formatHex(hash.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static void requireMain() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Item custody requires main thread");
    }
}

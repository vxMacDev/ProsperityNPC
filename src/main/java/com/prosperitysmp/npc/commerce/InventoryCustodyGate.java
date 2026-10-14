package com.prosperitysmp.npc.commerce;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Main-thread, per-player inventory exclusion shared by every runtime that adds items to or takes items from a
 * player's inventory (deliveries, item repairs, later hand-ins). At most one mutation per player at a time, so
 * two save-and-read-back windows never overlap. Holds no SQL and no money.
 */
public final class InventoryCustodyGate {
    public static final int LIMIT = 16;
    private final Map<UUID, Object> holders = new HashMap<>();
    private final BooleanSupplier main;

    public InventoryCustodyGate() { this(org.bukkit.Bukkit::isPrimaryThread); }
    InventoryCustodyGate(BooleanSupplier main) { this.main = Objects.requireNonNull(main); }

    /** False when another operation holds this player or the gate is full. */
    public boolean tryHold(UUID player, Object owner) {
        requireMain(); Objects.requireNonNull(player); Objects.requireNonNull(owner);
        if (holders.size() >= LIMIT) return false;
        return holders.putIfAbsent(player, owner) == null;
    }

    public boolean holds(UUID player, Object owner) {
        requireMain();
        return holders.get(player) == owner;
    }

    public boolean held(UUID player) { requireMain(); return holders.containsKey(player); }

    /** Releases only the given owner's hold. */
    public boolean release(UUID player, Object owner) {
        requireMain();
        return holders.remove(player, owner);
    }

    public int size() { requireMain(); return holders.size(); }

    private void requireMain() {
        if (!main.getAsBoolean()) throw new IllegalStateException("Inventory custody gate requires main thread");
    }
}

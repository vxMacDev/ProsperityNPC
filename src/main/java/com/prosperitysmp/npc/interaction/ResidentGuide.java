package com.prosperitysmp.npc.interaction;

import com.prosperitysmp.npc.ProsperityNpcPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Private straight-line directions to authored public stops, not obstacle-aware pathfinding. */
final class ResidentGuide implements AutoCloseable {
    private record Journey(Player login, Location target, String label, double initialDistance, BossBar bar, long expires) { }
    private final ProsperityNpcPlugin plugin;
    private final Map<UUID, Journey> journeys = new HashMap<>();
    private final BukkitTask task;
    private boolean closed;

    ResidentGuide(ProsperityNpcPlugin plugin) {
        this.plugin = plugin;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20, 20);
    }
    private boolean allowed(Player player) {
        return !closed && plugin.isEnabled() && player.isOnline() && Bukkit.getPlayer(player.getUniqueId()) == player
                && player.hasPermission("prosperitynpc.use") && plugin.getConfig().getBoolean("town.enabled", false)
                && plugin.killSwitches().isSystemEnabled() && plugin.killSwitches().isRenderingEnabled();
    }
    boolean start(Player player, Location target, String label) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Guide requires main thread");
        if (!allowed(player) || target.getWorld() != player.getWorld() || label.length() > 80) return false;
        double distance = player.getLocation().distance(target);
        if (!Double.isFinite(distance)) return false;
        if (!journeys.containsKey(player.getUniqueId()) && journeys.size() >= 128) return false;
        stop(player.getUniqueId());
        BossBar bar = Bukkit.createBossBar(label, BarColor.GREEN, BarStyle.SOLID);
        try {
            bar.addPlayer(player);
            journeys.put(player.getUniqueId(), new Journey(player, target.clone(), label, Math.max(5, distance),
                    bar, System.nanoTime() + 300_000_000_000L));
            update(journeys.get(player.getUniqueId()));
            return true;
        } catch (RuntimeException | LinkageError failure) {
            journeys.remove(player.getUniqueId());
            bar.removeAll();
            return false;
        }
    }
    void stop(UUID player) {
        Journey journey = journeys.remove(player);
        if (journey != null) journey.bar().removeAll();
    }
    private void tick() {
        for (var journey : List.copyOf(journeys.values())) {
            Player player = journey.login();
            if (!allowed(player) || player.getWorld() != journey.target().getWorld()
                    || System.nanoTime() - journey.expires() >= 0) { stop(player.getUniqueId());
            continue; }
            try { update(journey); }
            catch (RuntimeException | LinkageError failure) { stop(player.getUniqueId()); }
        }
    }
    private void update(Journey journey) {
        Location from = journey.login().getLocation();
        double distance = from.distance(journey.target());
        if (distance <= 5) {
            stop(journey.login().getUniqueId());
            return;
        }
        String direction = GuideGeometry.direction(journey.target().getX() - from.getX(),
                journey.target().getZ() - from.getZ(), from.getYaw());
        journey.bar().setTitle(journey.label() + " | " + direction + " | " + Math.round(distance) + " blocks");
        journey.bar().setProgress(GuideGeometry.progress(distance, journey.initialDistance()));
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        task.cancel();
        for (UUID player : List.copyOf(journeys.keySet())) stop(player);
    }
}

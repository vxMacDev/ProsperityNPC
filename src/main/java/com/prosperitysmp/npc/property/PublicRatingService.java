package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.ProsperityNpcPlugin;
import com.prosperitysmp.npc.clock.EconomyClock;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Clock;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** {@code /property rating}: public, read-only, behind housing.public-rating-enabled. One bounded worker. */
public final class PublicRatingService implements AutoCloseable {
    private static final long COOLDOWN_NANOS = TimeUnit.SECONDS.toNanos(3);
    private final ProsperityNpcPlugin plugin;
    private final OwnerDeskRepository repository;
    private final EconomyClock clock;
    private final Map<UUID, Long> lastAsk = new HashMap<>(); // main thread only
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8), task -> {
                Thread t = new Thread(task, "prosperity-npc-public-rating");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());
    private volatile boolean closed;

    public PublicRatingService(ProsperityNpcPlugin plugin, OwnerDeskRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
        ZoneId zone = ZoneId.of(plugin.getConfig().getString("clock.timezone", "America/Chicago"));
        clock = new EconomyClock(Clock.systemUTC(), zone, plugin.getConfig().getInt("clock.daily-reset-hour", 4));
        plugin.trackDatabaseWorker(worker);
    }

    public void show(Player player) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Public rating requires the main thread");
        if (closed || !plugin.isEnabled() || plugin.armAdapter() == null || plugin.propertyService() == null) {
            say(player, "Property ratings are unavailable.");
            return;
        }
        long now = System.nanoTime();
        Long previous = lastAsk.get(player.getUniqueId());
        if (previous != null && now - previous < COOLDOWN_NANOS) {
            say(player, "Please wait a moment.");
            return;
        }
        lastAsk.put(player.getUniqueId(), now);
        if (lastAsk.size() > 512) lastAsk.values().removeIf(t -> now - t > COOLDOWN_NANOS);
        var at = plugin.armAdapter().regionAt(player.getLocation());
        var property = at.flatMap(a -> plugin.propertyService().findByWorldRegion(a.worldId(), a.regionId()))
                .filter(Property::enabled).orElse(null);
        if (property == null) {
            say(player, "Stand inside an enrolled property to see its rating.");
            return;
        }
        var day = clock.today();
        UUID viewer = player.getUniqueId();
        boolean away = plugin.ownerAway().isAway(property); // main thread; today's vacation decision
        try {
            worker.execute(() -> {
                if (closed) return;
                PublicRating rating = null;
                try {
                    rating = repository.read(property.worldUuid(), property.regionId(), property.ownerUuid(), day)
                            .map(PublicRating::from).orElse(null);
                } catch (Exception error) { /* reported as unavailable below */ }
                PublicRating result = rating;
                try {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Player p = Bukkit.getPlayer(viewer);
                        if (p == null) return;
                        if (result == null) say(p, "This property's rating is unavailable right now.");
                        else for (String line : result.lines(away)) say(p, line);
                    });
                } catch (IllegalStateException shutdown) { /* plugin disabled */ }
            });
        } catch (RejectedExecutionException busy) {
            say(player, "Ratings are busy. Please try again shortly.");
        }
    }

    private static void say(Player player, String message) {
        player.sendMessage(Component.text(message, NamedTextColor.GRAY));
    }

    @Override public void close() {
        closed = true;
        worker.shutdownNow();
    }
}

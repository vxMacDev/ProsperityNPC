package com.prosperitysmp.npc.interaction;

import com.prosperitysmp.npc.ProsperityNpcPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Color;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Temporary, private speech. All calls occur on the server thread. */
final class ResidentSpeech implements AutoCloseable {
    private record Bubble(Player viewer, Entity speaker, List<TextDisplay> lines,
                          long expires, BooleanSupplier authorized) { }
    private final ProsperityNpcPlugin plugin;
    private final Map<UUID, Bubble> bubbles = new HashMap<>();

    ResidentSpeech(ProsperityNpcPlugin plugin) { this.plugin = plugin; }

    boolean show(Player viewer, Entity speaker, String text, BooleanSupplier authorized) {
        remove(viewer.getUniqueId());
        List<String> textLines = wrap(text);
        if (textLines.isEmpty() || textLines.size() > 6 || bubbles.size() >= 24
                || !authorized.getAsBoolean() || !speaker.isValid()) return false;
        List<TextDisplay> spawned = new ArrayList<>();
        try {
                TextDisplay stand = speaker.getWorld().spawn(position(speaker), TextDisplay.class, entity -> {
                    // Configure before tracking, so another viewer never sees the text.
                    entity.setVisibleByDefault(false);
                    entity.setPersistent(false);
                    entity.setGravity(false);
                    entity.setInvulnerable(true);
                    entity.setSilent(true);
                    // Render text, not a custom name: no below-name scoreboard decoration.
                    entity.text(Component.text(String.join("\n", textLines), NamedTextColor.WHITE));
                    entity.setBillboard(Display.Billboard.CENTER);
                    entity.setAlignment(TextDisplay.TextAlignment.CENTER);
                    entity.setLineWidth(300);
                    entity.setDefaultBackground(false);
                    entity.setBackgroundColor(Color.fromARGB(140, 12, 15, 19));
                    entity.setShadowed(true);
                    entity.setSeeThrough(false);
                    entity.setTeleportDuration(5);
                });
                spawned.add(stand);
                if (!stand.isValid()) throw new IllegalStateException("Speech spawn rejected");
                viewer.showEntity(plugin, stand);
            bubbles.put(viewer.getUniqueId(), new Bubble(viewer, speaker, spawned,
                    System.nanoTime() + 15_000_000_000L, authorized));
            return true;
        } catch (RuntimeException | LinkageError failure) {
            spawned.forEach(Entity::remove);
            plugin.getLogger().warning("Resident speech unavailable: " + failure.getClass().getSimpleName());
            return false;
        }
    }

    private static Location position(Entity speaker) {
        return speaker.getLocation().add(0, speaker.getHeight() + 0.85, 0);
    }

    void tick() {
        for (UUID id : List.copyOf(bubbles.keySet())) {
            Bubble b = bubbles.get(id);
            if (System.nanoTime() >= b.expires || !b.viewer.isOnline() || !b.speaker.isValid()
                    || b.viewer.getWorld() != b.speaker.getWorld()
                    || !b.speaker.getWorld().isChunkLoaded(b.speaker.getLocation().getBlockX() >> 4,
                            b.speaker.getLocation().getBlockZ() >> 4)
                    || !b.authorized.getAsBoolean()
                    || b.viewer.getLocation().distanceSquared(b.speaker.getLocation()) > 64) {
                remove(id);
                continue;
            }
            for (int i = 0; i < b.lines.size(); i++) {
                TextDisplay line = b.lines.get(i);
                Location target = position(b.speaker);
                if (!line.isValid() || (line.getLocation().distanceSquared(target) > 0.01 && !line.teleport(target))) {
                    remove(id);
                    break;
                }
            }
        }
    }

    static List<String> wrap(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.strip().split("\\s+")) {
            // Oversized replies use the existing readable fallback, never truncate dialogue.
            if (word.length() > 48) return List.of();
            if (!line.isEmpty() && line.length() + 1 + word.length() > 48) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (!line.isEmpty()) line.append(' ');
            line.append(word);
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return List.copyOf(lines);
    }

    void remove(UUID viewer) {
        Bubble b = bubbles.remove(viewer);
        if (b != null) b.lines.forEach(Entity::remove);
    }

    @Override public void close() { List.copyOf(bubbles.keySet()).forEach(this::remove); }
}

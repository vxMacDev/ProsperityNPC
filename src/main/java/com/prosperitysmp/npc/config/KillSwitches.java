package com.prosperitysmp.npc.config;

import com.prosperitysmp.npc.ProsperityNpcPlugin;

import java.util.Locale;

/**
 * The five persisted kill switches. All default {@code false}. Toggling one via
 * {@code /npcadmin switch} persists the change to config.yml.
 *
 * <p>{@code SYSTEM} is the master gate for all simulation; {@code ECONOMY}
 * additionally gates every cash movement. Anything that moves money must check
 * that both are true.
 */
public final class KillSwitches {

    public enum Switch {
        SYSTEM("system-enabled"),
        ECONOMY("economy-enabled"),
        INCIDENTS("incidents-enabled"),
        MOVES("moves-enabled"),
        RENDERING("rendering-enabled");

        private final String key;

        Switch(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        public String path() {
            return "switches." + key;
        }

        public static Switch fromKey(String s) {
            for (Switch sw : values()) {
                if (sw.key.equalsIgnoreCase(s) || sw.name().equalsIgnoreCase(s)) {
                    return sw;
                }
            }
            return null;
        }
    }

    private final ProsperityNpcPlugin plugin;
    private boolean system;
    private boolean economy;
    private boolean incidents;
    private boolean moves;
    private boolean rendering;

    private KillSwitches(ProsperityNpcPlugin plugin) {
        this.plugin = plugin;
    }

    public static KillSwitches load(ProsperityNpcPlugin plugin) {
        KillSwitches k = new KillSwitches(plugin);
        k.reload();
        return k;
    }

    public void reload() {
        var cfg = plugin.getConfig();
        this.system = cfg.getBoolean(Switch.SYSTEM.path(), false);
        this.economy = cfg.getBoolean(Switch.ECONOMY.path(), false);
        this.incidents = cfg.getBoolean(Switch.INCIDENTS.path(), false);
        this.moves = cfg.getBoolean(Switch.MOVES.path(), false);
        this.rendering = cfg.getBoolean(Switch.RENDERING.path(), false);
    }

    public boolean isEnabled(Switch sw) {
        return switch (sw) {
            case SYSTEM -> system;
            case ECONOMY -> economy;
            case INCIDENTS -> incidents;
            case MOVES -> moves;
            case RENDERING -> rendering;
        };
    }

    /** Sets a switch and persists it to config.yml. */
    public void set(Switch sw, boolean value) {
        plugin.getConfig().set(sw.path(), value);
        plugin.saveConfig();
        reload();
    }

    public boolean isSystemEnabled() {
        return system;
    }

    public boolean isEconomyEnabled() {
        return economy;
    }

    public boolean isIncidentsEnabled() {
        return incidents;
    }

    public boolean isMovesEnabled() {
        return moves;
    }

    public boolean isRenderingEnabled() {
        return rendering;
    }

    public String summary() {
        return String.format(Locale.ROOT,
                "system=%b economy=%b incidents=%b moves=%b rendering=%b",
                system, economy, incidents, moves, rendering);
    }
}

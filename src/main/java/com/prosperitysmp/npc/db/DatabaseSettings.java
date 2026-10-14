package com.prosperitysmp.npc.db;

import com.prosperitysmp.npc.ProsperityNpcPlugin;
import org.bukkit.configuration.ConfigurationSection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Immutable connection settings for the dedicated {@code prosperity_npc} MariaDB.
 * The password is resolved from config, or — if blank — from a server-only
 * {@code db-password.txt} beside the plugin (never bundled in the jar or repo).
 */
public record DatabaseSettings(
        String host,
        int port,
        String database,
        String user,
        String password,
        int poolSize,
        long connectionTimeoutMs) {

    /** True only when a non-blank password is available (else the plugin runs DB-less). */
    public boolean isConfigured() {
        return password != null && !password.isBlank();
    }

    public String jdbcUrl() {
        return "jdbc:mariadb://" + host + ":" + port + "/" + database;
    }

    public static DatabaseSettings load(ProsperityNpcPlugin plugin) {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("database");
        String host = s != null ? s.getString("host", "127.0.0.1") : "127.0.0.1";
        int port = s != null ? s.getInt("port", 3306) : 3306;
        String database = s != null ? s.getString("name", "prosperity_npc") : "prosperity_npc";
        String user = s != null ? s.getString("user", "npcuser") : "npcuser";
        String password = s != null ? s.getString("password", "") : "";
        int poolSize = s != null ? s.getInt("pool-size", 6) : 6;
        long timeout = s != null ? s.getLong("connection-timeout-ms", 10_000L) : 10_000L;

        if (password == null || password.isBlank()) {
            password = readPasswordFile(plugin);
        }
        return new DatabaseSettings(host, port, database, user,
                password == null ? "" : password, Math.max(1, poolSize), Math.max(1_000L, timeout));
    }

    private static String readPasswordFile(ProsperityNpcPlugin plugin) {
        Path p = plugin.getDataFolder().toPath().resolve("db-password.txt");
        if (!Files.isReadable(p)) {
            return "";
        }
        try {
            return Files.readString(p, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            plugin.getLogger().warning("Could not read db-password.txt: " + e.getMessage());
            return "";
        }
    }
}

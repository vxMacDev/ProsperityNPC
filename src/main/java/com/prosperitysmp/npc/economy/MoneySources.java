package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.api.MoneySource;
import org.bukkit.Server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Builds the built-in money sources. The Vault source is loaded by name and only after Vault is found, so no class
 * outside economy/vault ever links against Vault and the plugin still loads on a server without it.
 */
public final class MoneySources {
    public static final String VAULT = "vault";
    static final String VAULT_CLASS = "com.prosperitysmp.npc.economy.vault.VaultMoneySource";

    private MoneySources() { }

    /** The Vault source, or an unavailable one with the reason. Never throws. */
    public static BuiltInMoneySource vault(Server server) {
        return vault(server, MoneySources.class.getClassLoader());
    }

    /** As vault(Server), loading the Vault source through {@code loader}. */
    static BuiltInMoneySource vault(Server server, ClassLoader loader) {
        if (server.getPluginManager().getPlugin("Vault") == null)
            return new UnavailableMoneySource(VAULT, "Vault is not installed");
        try {
            Class<?> type = Class.forName(VAULT_CLASS, true, loader);
            return (BuiltInMoneySource) type.getConstructor(Server.class).newInstance(server);
        } catch (LinkageError | ReflectiveOperationException | RuntimeException failure) {
            return new UnavailableMoneySource(VAULT, "Vault classes could not be loaded: " + failure.getClass().getSimpleName());
        }
    }

    /**
     * The first 8 hex characters of the provider_sha256 the journal would store for this source right now, or
     * "unavailable". Reads the provider's jar only; moves no money and reads no balance.
     */
    public static String artifactSha8(MoneySource source) {
        if (source == null) return "unavailable";
        try {
            var pinned = source.pin().orElse(null);
            if (pinned == null) return "unavailable";
            Path jar = pinned.artifact();
            return sha8(jar, UtilityPaymentRuntime::hash);
        } catch (RuntimeException | LinkageError unreadable) {
            return "unavailable";
        }
    }

    private record Hashed(long size, long modified, String sha8) { }
    // Diagnose runs on the main thread; a provider jar can be large. Rehashed only when its size or modified time changes.
    private static final Map<Path, Hashed> HASHES = new ConcurrentHashMap<>();

    /** The first 8 characters of {@code hash(jar)}, cached by path, size and modified time. Throws as hash does. */
    static String sha8(Path jar, Function<Path, String> hash) {
        Path key = jar.toAbsolutePath().normalize();
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(key, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException | RuntimeException unreadable) {
            HASHES.remove(key);
            return hash.apply(jar).substring(0, 8);
        }
        long size = attributes.size(), modified = attributes.lastModifiedTime().toMillis();
        Hashed cached = HASHES.get(key);
        if (cached != null && cached.size() == size && cached.modified() == modified) return cached.sha8();
        String sha8 = hash.apply(jar).substring(0, 8);
        if (HASHES.size() >= 16) HASHES.clear();
        HASHES.put(key, new Hashed(size, modified, sha8));
        return sha8;
    }
}

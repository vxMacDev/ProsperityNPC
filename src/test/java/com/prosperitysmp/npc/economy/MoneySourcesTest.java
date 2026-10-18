package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.economy.vault.VaultMoneySource;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The default money path: the class name MoneySources loads by name is the real Vault source, a server without Vault
 * gets an unavailable source with its reason, a Vault source that cannot link is reported instead of thrown, and
 * diagnose's provider hash is not recomputed for an unchanged jar.
 */
class MoneySourcesTest {

    private static Server server(boolean vaultInstalled) {
        Plugin vault = (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[] {Plugin.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getName", "toString" -> "Vault";
                    case "hashCode" -> 1;
                    case "equals" -> p == a[0];
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        PluginManager manager = (PluginManager) Proxy.newProxyInstance(PluginManager.class.getClassLoader(),
                new Class<?>[] {PluginManager.class}, (p, m, a) -> {
                    if (m.getName().equals("getPlugin")) return vaultInstalled && "Vault".equals(a[0]) ? vault : null;
                    throw new UnsupportedOperationException(m.getName());
                });
        return (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] {Server.class},
                (p, m, a) -> {
                    if (m.getName().equals("getPluginManager")) return manager;
                    throw new UnsupportedOperationException(m.getName());
                });
    }

    @Test void theNameLoadedByReflectionIsTheVaultSource() {
        assertEquals(VaultMoneySource.class.getName(), MoneySources.VAULT_CLASS);
        assertEquals(VaultMoneySource.ID, MoneySources.VAULT);
        var source = MoneySources.vault(server(true));
        assertInstanceOf(VaultMoneySource.class, source, "with Vault installed the real source loads");
    }

    @Test void withoutVaultTheSourceIsUnavailableWithItsReason() {
        var source = MoneySources.vault(server(false));
        assertInstanceOf(UnavailableMoneySource.class, source);
        assertEquals("vault", source.id());
        assertEquals("Vault is not installed", source.reason());
        assertTrue(source.pin().isEmpty(), "no pin, so no payment can start");
        assertFalse(source.probe());
    }

    @Test void aVaultSourceThatCannotLinkIsReportedNotThrown() {
        ClassLoader broken = new ClassLoader(MoneySourcesTest.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals(MoneySources.VAULT_CLASS)) throw new NoClassDefFoundError("vault economy api");
                return super.loadClass(name, resolve);
            }
        };
        var source = assertDoesNotThrow(() -> MoneySources.vault(server(true), broken));
        assertInstanceOf(UnavailableMoneySource.class, source);
        assertEquals("Vault classes could not be loaded: NoClassDefFoundError", source.reason());
        assertTrue(source.pin().isEmpty());
    }

    @Test void theProviderHashIsReusedUntilTheJarChanges() throws Exception {
        Path jar = Files.createTempFile("npc-provider-", ".jar");
        try {
            Files.writeString(jar, "provider classes");
            var calls = new AtomicInteger();
            java.util.function.Function<Path, String> hash = path -> {
                calls.incrementAndGet();
                return UtilityPaymentRuntime.hash(path);
            };
            String first = MoneySources.sha8(jar, hash);
            assertEquals(UtilityPaymentRuntime.hash(jar).substring(0, 8), first);
            assertEquals(first, MoneySources.sha8(jar, hash));
            assertEquals(first, MoneySources.sha8(jar, hash));
            assertEquals(1, calls.get(), "an unchanged jar is hashed once");
            Files.writeString(jar, "provider classes, updated");
            Files.setLastModifiedTime(jar, FileTime.fromMillis(Files.getLastModifiedTime(jar).toMillis() + 5_000));
            String second = MoneySources.sha8(jar, hash);
            assertEquals(2, calls.get(), "a changed jar is hashed again");
            assertEquals(UtilityPaymentRuntime.hash(jar).substring(0, 8), second);
            assertNotEquals(first, second);
        } finally {
            Files.deleteIfExists(jar);
        }
    }
}

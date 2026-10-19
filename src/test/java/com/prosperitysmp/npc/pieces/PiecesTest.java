package com.prosperitysmp.npc.pieces;

import com.prosperitysmp.npc.api.Capabilities;
import com.prosperitysmp.npc.api.GuardResult;
import com.prosperitysmp.npc.api.MoneySource;
import com.prosperitysmp.npc.api.OwnershipHooks;
import com.prosperitysmp.npc.api.OwnershipToken;
import com.prosperitysmp.npc.api.Presence;
import com.prosperitysmp.npc.api.PropertyBackend;
import com.prosperitysmp.npc.api.ProsperityNpcApi;
import com.prosperitysmp.npc.api.RegionRef;
import com.prosperitysmp.npc.api.RegionView;
import io.papermc.paper.plugin.configuration.PluginMeta;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The Bukkit side of piece selection over a proxied server and services manager. */
class PiecesTest {
    private final List<RegisteredServiceProvider<?>> registered = new ArrayList<>();

    private static Plugin plugin(String name, String version) {
        PluginMeta meta = (PluginMeta) Proxy.newProxyInstance(PluginMeta.class.getClassLoader(), new Class<?>[]{PluginMeta.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getVersion" -> version;
                    case "getName" -> name;
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == a[0];
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getName" -> name;
                    case "getPluginMeta" -> meta;
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == a[0];
                    case "toString" -> name;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private Server server() {
        ServicesManager services = (ServicesManager) Proxy.newProxyInstance(ServicesManager.class.getClassLoader(),
                new Class<?>[]{ServicesManager.class}, (p, m, a) -> {
                    if (m.getName().equals("getRegistrations") && a.length == 1 && a[0] instanceof Class<?> type) {
                        var out = new ArrayList<RegisteredServiceProvider<?>>();
                        for (var r : registered) if (r.getService() == type) out.add(r);
                        return out;
                    }
                    throw new UnsupportedOperationException(m.getName());
                });
        return (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getServicesManager" -> services;
                    case "hashCode" -> 1;
                    case "equals" -> p == a[0];
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private static PropertyBackend backend(String id, int major, int minor) {
        return new PropertyBackend() {
            @Override public String id() { return id; }
            @Override public int apiMajor() { return major; }
            @Override public int apiMinor() { return minor; }
            @Override public Capabilities capabilities() { return Capabilities.builder().build(); }
            @Override public String displayName() { return id.toUpperCase(); }
            @Override public boolean available() { return true; }
            @Override public GuardResult installGuard(OwnershipHooks hooks) { return GuardResult.failed("no"); }
            @Override public Optional<RegionRef> regionAt(Location at) { return Optional.empty(); }
            @Override public Optional<RegionView> findRegion(String worldName, String regionId) { return Optional.empty(); }
            @Override public Optional<OwnershipToken> ownerReadAt(Location at, UUID owner) { return Optional.empty(); }
            @Override public Optional<OwnershipToken> ownerReadRegion(UUID world, String regionId, UUID owner) { return Optional.empty(); }
            @Override public Presence observe(UUID world, String regionId) { return Presence.MISSING; }
            @Override public List<String> describe() { return List.of(id); }
        };
    }

    private static PropertyBackend backend(String id) {
        return backend(id, ProsperityNpcApi.MAJOR, ProsperityNpcApi.MINOR);
    }

    private <T> RegisteredServiceProvider<T> register(Class<T> type, T piece, ServicePriority priority, Plugin owner) {
        var r = new RegisteredServiceProvider<>(type, piece, priority, owner);
        registered.add(r);
        return r;
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   ", "\t", "\n"})
    void anUnsetOrBlankIdIsTheDefault(String value) {
        assertEquals("vault", Pieces.configuredId(value, "vault"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "'  ABC '|abc",
            "Lands|lands",
            "worldguard-listed|worldguard-listed",
            "'\tMy-Bank\n'|my-bank",
            "' a  B '|a  b"})
    void aSetIdIsTrimmedAndLowercased(String value, String expected) {
        assertEquals(expected, Pieces.configuredId(value, "vault"));
    }

    @Test void theDefaultIsReturnedUnchanged() {
        assertEquals("  Mixed  ", Pieces.configuredId(null, "  Mixed  "));
        assertNull(Pieces.configuredId("", null));
    }

    @Test void theConfigKeysAreTheDocumentedSettings() {
        assertEquals("money.source", Pieces.MONEY_KEY);
        assertEquals("property.backend", Pieces.PROPERTY_KEY);
        assertEquals("accounting.sink", Pieces.ACCOUNTING_KEY);
    }

    @Test void registrationsListOnlyTheRequestedKindInServicesManagerOrder() {
        var lands = backend("lands");
        var towny = backend("towny");
        register(PropertyBackend.class, lands, ServicePriority.Lowest, plugin("Lands", "7.1"));
        register(PropertyBackend.class, towny, ServicePriority.Highest, plugin("Towny", "0.99"));
        var list = Pieces.registrations(server(), PropertyBackend.class);
        assertEquals(2, list.size());
        assertSame(lands, list.get(0).piece());
        assertEquals("Lands", list.get(0).pluginName());
        assertEquals("7.1", list.get(0).pluginVersion());
        assertEquals(ServicePriority.Lowest.ordinal(), list.get(0).priority());
        assertSame(towny, list.get(1).piece());
        assertEquals(ServicePriority.Highest.ordinal(), list.get(1).priority());
        assertTrue(Pieces.registrations(server(), MoneySource.class).isEmpty());
    }

    @Test void aRegistrationWithoutAPluginIsNamedUnknown() {
        register(PropertyBackend.class, backend("lands"), ServicePriority.Normal, null);
        var only = Pieces.registrations(server(), PropertyBackend.class).getFirst();
        assertEquals("unknown", only.pluginName());
        assertEquals("?", only.pluginVersion());
    }

    @Test void selectPropertyPrefersTheBuiltInAndIgnoresRegistrations() {
        var arm = backend("arm");
        register(PropertyBackend.class, backend("lands"), ServicePriority.Normal, plugin("Lands", "7.1"));
        var pieces = new Pieces(server(), Map.of(), Map.of("arm", arm), null, null);
        var selection = pieces.selectProperty("arm");
        assertSame(arm, selection.selected());
        assertTrue(selection.builtIn());
        assertEquals("ProsperityNPC", selection.owner());
        assertEquals(1, selection.unused().size(), "the unnamed third-party piece is listed as unused");
    }

    @Test void selectPropertyFindsTheNamedThirdPartyPiece() {
        var lands = backend("lands");
        register(PropertyBackend.class, lands, ServicePriority.Normal, plugin("Lands", "7.1"));
        var selection = new Pieces(server(), Map.of(), Map.of("arm", backend("arm")), null, null).selectProperty("lands");
        assertSame(lands, selection.selected());
        assertFalse(selection.builtIn());
        assertEquals("Lands", selection.owner());
        assertEquals("Lands 7.1", selection.from());
    }

    @Test void selectPropertyReportsANewerApiMinor() {
        register(PropertyBackend.class, backend("lands", 1, 3), ServicePriority.Normal, plugin("Lands", "8.0"));
        var selection = new Pieces(server(), Map.of(), Map.of(), null, null).selectProperty("lands");
        assertFalse(selection.found());
        assertEquals("API 1.3, ProsperityNPC supports 1.2", selection.reason());
    }

    private static MoneySource money(String id, String scale) {
        return new MoneySource() {
            @Override public String id() { return id; }
            @Override public int apiMajor() { return ProsperityNpcApi.MAJOR; }
            @Override public int apiMinor() { return ProsperityNpcApi.MINOR; }
            @Override public Capabilities capabilities() {
                return Capabilities.builder().value("scale", scale).flag("artifact-hashable", true).build();
            }
            @Override public Optional<Pin> pin() { return Optional.empty(); }
        };
    }

    @Test void selectMoneyFindsTheNamedThirdPartySourceAndIgnoresPropertyRegistrations() {
        var bank = money("bank", "2");
        register(MoneySource.class, bank, ServicePriority.Low, plugin("BankPlugin", "2.0"));
        register(PropertyBackend.class, backend("bank"), ServicePriority.Normal, plugin("Lands", "7.1"));
        var selection = new Pieces(server(), Map.of(), Map.of(), null, null).selectMoney("bank");
        assertSame(bank, selection.selected());
        assertEquals("BankPlugin 2.0", selection.from());
        assertTrue(selection.refused().isEmpty());
    }

    @Test void selectMoneyRefusesASourceWithTheWrongScale() {
        register(MoneySource.class, money("bank", "3"), ServicePriority.Normal, plugin("BankPlugin", "2.0"));
        var selection = new Pieces(server(), Map.of(), Map.of(), null, null).selectMoney("bank");
        assertFalse(selection.found());
        assertEquals("scale '3' (only 2 decimal places are supported)", selection.reason());
        assertEquals("BankPlugin", selection.owner());
    }

    @Test void selectMoneyPrefersTheBuiltInVault() {
        var vault = money("vault", "2");
        register(MoneySource.class, money("bank", "2"), ServicePriority.Normal, plugin("BankPlugin", "2.0"));
        var selection = new Pieces(server(), Map.of("vault", vault), Map.of(), null, null).selectMoney("vault");
        assertSame(vault, selection.selected());
        assertTrue(selection.builtIn());
        assertEquals("ProsperityNPC", selection.from());
    }
}

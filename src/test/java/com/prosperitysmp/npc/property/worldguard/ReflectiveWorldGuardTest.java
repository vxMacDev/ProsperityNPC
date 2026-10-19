package com.prosperitysmp.npc.property.worldguard;

import com.prosperitysmp.npc.api.Presence;
import java.util.HashSet;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The reflective WorldGuard reader with WorldGuard absent from the class path, as on a server without it. */
class ReflectiveWorldGuardTest {
    private final List<String> asked = new ArrayList<>();

    private PluginManager plugins(Set<String> enabled, Plugin worldGuard) {
        return (PluginManager) Proxy.newProxyInstance(PluginManager.class.getClassLoader(), new Class<?>[]{PluginManager.class},
                (p, m, a) -> switch (m.getName()) {
                    case "isPluginEnabled" -> {
                        asked.add("enabled " + a[0]);
                        yield a[0] instanceof String name && enabled.contains(name);
                    }
                    case "getPlugin" -> {
                        asked.add("plugin " + a[0]);
                        yield "WorldGuard".equals(a[0]) ? worldGuard : null;
                    }
                    case "hashCode" -> 1;
                    case "equals" -> p == a[0];
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private static Plugin plugin() {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getName" -> "WorldGuard";
                    case "hashCode" -> 2;
                    case "equals" -> p == a[0];
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    @Test void aNullPluginManagerIsRefused() {
        assertThrows(NullPointerException.class, () -> new ReflectiveWorldGuard(null));
    }

    @ParameterizedTest
    @CsvSource({"true,true,true", "true,false,false", "false,true,false", "false,false,false"})
    void availableNeedsBothWorldGuardAndWorldEdit(boolean worldGuard, boolean worldEdit, boolean expected) {
        var enabled = new HashSet<String>();
        if (worldGuard) enabled.add("WorldGuard");
        if (worldEdit) enabled.add("WorldEdit");
        assertEquals(expected, new ReflectiveWorldGuard(plugins(enabled, null)).available());
    }

    @Test void pluginNamesAreMatchedExactly() {
        assertFalse(new ReflectiveWorldGuard(plugins(Set.of("worldguard", "worldedit"), null)).available());
        assertEquals("enabled WorldGuard", asked.getFirst());
    }

    @Test void readsWithoutTheWorldGuardPluginFailClosed() {
        var reader = new ReflectiveWorldGuard(plugins(Set.of(), null));
        var manager = assertThrows(IllegalStateException.class, () -> reader.manager(null));
        assertEquals("WorldGuard missing", manager.getMessage());
        var region = assertThrows(IllegalStateException.class, () -> reader.region(new Object(), "home-1"));
        assertEquals("WorldGuard missing", region.getMessage());
        var ids = assertThrows(IllegalStateException.class, () -> reader.idsAt(new Object(), 0, 64, 0));
        assertEquals("WorldGuard missing", ids.getMessage());
        assertEquals(List.of("plugin WorldGuard", "plugin WorldGuard", "plugin WorldGuard"), asked);
    }

    @Test void readsLoadWorldGuardClassesThroughThePluginsLoaderAndReportTheMissingClass() {
        var reader = new ReflectiveWorldGuard(plugins(Set.of("WorldGuard", "WorldEdit"), plugin()));
        var manager = assertThrows(ClassNotFoundException.class, () -> reader.manager(null));
        assertEquals("com.sk89q.worldguard.WorldGuard", manager.getMessage());
        var region = assertThrows(ClassNotFoundException.class, () -> reader.region(new Object(), "home-1"));
        assertEquals("com.sk89q.worldguard.protection.managers.RegionManager", region.getMessage());
        var ids = assertThrows(ClassNotFoundException.class, () -> reader.idsAt(new Object(), 0, 64, 0));
        assertEquals("com.sk89q.worldguard.protection.managers.RegionManager", ids.getMessage());
    }

    @Test void theBackendTreatsTheMissingClassesAsUnreadable() {
        var reader = new ReflectiveWorldGuard(plugins(Set.of("WorldGuard", "WorldEdit"), plugin()));
        var world = WorldGuardListedContractTest.WORLD_OBJECT;
        var backend = new WorldGuardListedBackend(reader, () -> true, id -> world, name -> world, at -> true);
        assertTrue(backend.available());
        assertTrue(backend.findRegion("town", "home-1").isEmpty());
        assertTrue(backend.ownerReadRegion(WorldGuardListedContractTest.WORLD, "home-1", new UUID(1, 1)).isEmpty());
        assertEquals(Presence.UNREADABLE, backend.observe(WorldGuardListedContractTest.WORLD, "home-1"));
        assertTrue(backend.regionAt(new Location(world, 1, 64, 1)).isEmpty());
    }
}

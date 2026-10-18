package com.prosperitysmp.npc.property.worldguard;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * WorldGuard 7's public API reached by reflection, as {@code ArmAdapter} does, so the plugin adds no compile
 * dependency on WorldGuard or WorldEdit. Main thread only. Every failure is thrown to the backend, which treats it
 * as "cannot read".
 */
public final class ReflectiveWorldGuard implements WorldGuardListedBackend.Reader {
    private final PluginManager plugins;

    public ReflectiveWorldGuard(PluginManager plugins) {
        this.plugins = Objects.requireNonNull(plugins);
    }

    @Override
    public boolean available() {
        return plugins.isPluginEnabled("WorldGuard") && plugins.isPluginEnabled("WorldEdit");
    }

    private ClassLoader loader() {
        Plugin worldGuard = plugins.getPlugin("WorldGuard");
        if (worldGuard == null) throw new IllegalStateException("WorldGuard missing");
        return worldGuard.getClass().getClassLoader();
    }

    @Override
    public Object manager(World world) throws ReflectiveOperationException {
        ClassLoader loader = loader();
        Class<?> worldGuardType = Class.forName("com.sk89q.worldguard.WorldGuard", false, loader);
        Object worldGuard = worldGuardType.getMethod("getInstance").invoke(null);
        Object platform = worldGuardType.getMethod("getPlatform").invoke(worldGuard);
        Object container = platform.getClass().getMethod("getRegionContainer").invoke(platform);
        Class<?> adapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter", false, loader);
        Object weWorld = adapter.getMethod("adapt", World.class).invoke(null, world);
        Class<?> weWorldType = Class.forName("com.sk89q.worldedit.world.World", false, loader);
        Class<?> containerType = Class.forName("com.sk89q.worldguard.protection.regions.RegionContainer", false, loader);
        return containerType.getMethod("get", weWorldType).invoke(container, weWorld);
    }

    @Override
    public WorldGuardListedBackend.RegionData region(Object manager, String regionId) throws ReflectiveOperationException {
        ClassLoader loader = loader();
        Class<?> managerType = Class.forName("com.sk89q.worldguard.protection.managers.RegionManager", false, loader);
        Class<?> regionType = Class.forName("com.sk89q.worldguard.protection.regions.ProtectedRegion", false, loader);
        Class<?> domainType = Class.forName("com.sk89q.worldguard.domains.DefaultDomain", false, loader);
        if (!managerType.isInstance(manager)) return null;
        Object raw = managerType.getMethod("getRegion", String.class).invoke(manager, regionId);
        // WorldGuard ids are case-insensitive; a home is the region with exactly the enrolled id.
        if (!regionType.isInstance(raw) || !regionId.equals(regionType.getMethod("getId").invoke(raw))) return null;
        Object domain = regionType.getMethod("getOwners").invoke(raw);
        if (!domainType.isInstance(domain)) return null;
        return new WorldGuardListedBackend.RegionData(raw, domainType.getMethod("getUniqueIds").invoke(domain),
                domainType.getMethod("getPlayers").invoke(domain), domainType.getMethod("getGroups").invoke(domain),
                regionType.getMethod("getParent").invoke(raw) != null);
    }

    @Override
    public List<String> idsAt(Object manager, int x, int y, int z) throws ReflectiveOperationException {
        ClassLoader loader = loader();
        Class<?> managerType = Class.forName("com.sk89q.worldguard.protection.managers.RegionManager", false, loader);
        Class<?> vectorType = Class.forName("com.sk89q.worldedit.math.BlockVector3", false, loader);
        Class<?> regionType = Class.forName("com.sk89q.worldguard.protection.regions.ProtectedRegion", false, loader);
        if (!managerType.isInstance(manager)) return null;
        Object point = vectorType.getMethod("at", int.class, int.class, int.class).invoke(null, x, y, z);
        Object set = managerType.getMethod("getApplicableRegions", vectorType).invoke(manager, point);
        if (!(set instanceof Iterable<?> regions)) return null;
        var out = new ArrayList<String>();
        for (Object region : regions) {
            if (!regionType.isInstance(region)) return null;
            out.add((String) regionType.getMethod("getId").invoke(region));
        }
        return out;
    }
}

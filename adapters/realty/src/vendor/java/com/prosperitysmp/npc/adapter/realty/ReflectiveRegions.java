package com.prosperitysmp.npc.adapter.realty;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * WorldGuard 7 by reflection. WorldGuard and WorldEdit are not on this adapter's compile classpath, and Realty
 * already needs both, so the adapter loads their classes from WorldGuard's own class loader at run time. Main
 * thread only. Every method throws when WorldGuard cannot be read; none returns a guess.
 */
final class ReflectiveRegions {
    /** The reflective handles, resolved once from WorldGuard's class loader. */
    private record Handles(Object container, Method adaptWorld, Method managerFor, Method regionById, Method allRegions,
                           Method applicableAt, Method vectorAt, Method setRegions, Method id, Method parent,
                           Method owners, Method uniqueIds, Method names, Method groups) { }

    private final Plugin worldGuard;
    private Handles handles;

    ReflectiveRegions(Plugin worldGuard) {
        this.worldGuard = worldGuard;
    }

    /** The region, or empty when WorldGuard readably has none with this id. */
    Optional<RealtyView.Guard> region(World world, String regionId) {
        Object region = call(handles().regionById(), manager(world), regionId);
        if (region == null) return Optional.empty();
        Object owners = call(handles().owners(), region);
        var ids = new ArrayList<UUID>();
        for (Object uuid : (Set<?>) call(handles().uniqueIds(), owners)) ids.add((UUID) uuid);
        int names = ((Set<?>) call(handles().names(), owners)).size();
        int groups = ((Set<?>) call(handles().groups(), owners)).size();
        return Optional.of(new RealtyView.Guard(idOf(region), region, ids, names, groups));
    }

    List<RealtyView.Candidate> regionsAt(Location at) {
        Object manager = manager(at.getWorld());
        Object point = call(handles().vectorAt(), null, at.getBlockX(), at.getBlockY(), at.getBlockZ());
        Object set = call(handles().applicableAt(), manager, point);
        var found = new ArrayList<RealtyView.Candidate>();
        for (Object region : (Set<?>) call(handles().setRegions(), set)) {
            found.add(new RealtyView.Candidate(idOf(region), ancestors(region)));
        }
        return found;
    }

    List<String> descendants(World world, String regionId) {
        var found = new ArrayList<String>();
        for (Object region : regions(world)) {
            if (ancestors(region).stream().anyMatch(regionId::equalsIgnoreCase)) found.add(idOf(region));
        }
        return found;
    }

    List<String> ancestorsOf(World world, String regionId) {
        Object region = call(handles().regionById(), manager(world), regionId);
        return region == null ? List.of() : ancestors(region);
    }

    Map<String, List<String>> childIndex(World world) {
        var index = new HashMap<String, List<String>>();
        for (Object region : regions(world)) {
            Object parent = call(handles().parent(), region);
            if (parent == null) continue;
            index.computeIfAbsent(idOf(parent).toLowerCase(Locale.ROOT), id -> new ArrayList<>()).add(idOf(region));
        }
        return index;
    }

    private Iterable<?> regions(World world) {
        return ((Map<?, ?>) call(handles().allRegions(), manager(world))).values();
    }

    /** Parent ids, nearest first. */
    private List<String> ancestors(Object region) {
        var ids = new LinkedHashSet<String>();
        Object parent = call(handles().parent(), region);
        while (parent != null) {
            // Adding to the set stops a parent cycle.
            if (!ids.add(idOf(parent))) break;
            parent = call(handles().parent(), parent);
        }
        return new ArrayList<>(ids);
    }

    private String idOf(Object region) {
        return (String) call(handles().id(), region);
    }

    private Object manager(World world) {
        Object adapted = call(handles().adaptWorld(), null, world);
        Object manager = call(handles().managerFor(), handles().container(), adapted);
        if (manager == null) throw new IllegalStateException("WorldGuard has no region manager for " + world.getName());
        return manager;
    }

    private Handles handles() {
        if (handles == null) handles = resolve();
        return handles;
    }

    private Handles resolve() {
        try {
            ClassLoader loader = worldGuard.getClass().getClassLoader();
            Class<?> guard = loader.loadClass("com.sk89q.worldguard.WorldGuard");
            Class<?> platform = loader.loadClass("com.sk89q.worldguard.internal.platform.WorldGuardPlatform");
            Class<?> container = loader.loadClass("com.sk89q.worldguard.protection.regions.RegionContainer");
            Class<?> manager = loader.loadClass("com.sk89q.worldguard.protection.managers.RegionManager");
            Class<?> region = loader.loadClass("com.sk89q.worldguard.protection.regions.ProtectedRegion");
            Class<?> domain = loader.loadClass("com.sk89q.worldguard.domains.DefaultDomain");
            Class<?> applicableSet = loader.loadClass("com.sk89q.worldguard.protection.ApplicableRegionSet");
            Class<?> editWorld = loader.loadClass("com.sk89q.worldedit.world.World");
            Class<?> vector = loader.loadClass("com.sk89q.worldedit.math.BlockVector3");
            Class<?> adapter = loader.loadClass("com.sk89q.worldedit.bukkit.BukkitAdapter");
            Object instance = guard.getMethod("getInstance").invoke(null);
            Object platformObject = guard.getMethod("getPlatform").invoke(instance);
            return new Handles(
                    platform.getMethod("getRegionContainer").invoke(platformObject),
                    adapter.getMethod("adapt", World.class),
                    container.getMethod("get", editWorld),
                    manager.getMethod("getRegion", String.class),
                    manager.getMethod("getRegions"),
                    manager.getMethod("getApplicableRegions", vector),
                    vector.getMethod("at", int.class, int.class, int.class),
                    applicableSet.getMethod("getRegions"),
                    region.getMethod("getId"),
                    region.getMethod("getParent"),
                    region.getMethod("getOwners"),
                    domain.getMethod("getUniqueIds"),
                    domain.getMethod("getPlayers"),
                    domain.getMethod("getGroups"));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("WorldGuard could not be read by reflection", failure);
        }
    }

    private static Object call(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("WorldGuard call failed: " + method.getName(), failure);
        }
    }
}

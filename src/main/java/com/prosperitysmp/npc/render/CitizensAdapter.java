package com.prosperitysmp.npc.render;

import com.prosperitysmp.npc.appearance.ResidentAppearanceCatalogue;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.ai.AbstractPathStrategy;
import net.citizensnpcs.api.ai.PathStrategy;
import net.citizensnpcs.api.ai.PathfinderType;
import net.citizensnpcs.api.ai.TargetType;
import net.citizensnpcs.api.ai.event.CancelReason;
import net.citizensnpcs.api.event.SpawnReason;
import net.citizensnpcs.api.npc.MemoryNPCDataStore;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.npc.ai.AStarNavigationStrategy;
import net.citizensnpcs.trait.SkinTrait;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Thin wrapper over the Citizens API. Uses an ANONYMOUS registry backed by an
 * in-memory data store, so avatars are transient and Citizens never persists them
 * to disk. Calls are main-thread only, fail closed, and expose a bounded diagnostic.
 * An incomplete cleanup disables new spawns until an explicit destroyAll succeeds.
 *
 * <p>References Citizens classes directly, so it is only ever instantiated when
 * Citizens is present and enabled (checked by the caller before construction).
 */
public final class CitizensAdapter {

    private final NPCRegistry registry;
    private final ResidentAppearanceCatalogue catalogue;
    private final Predicate<Location> spawnStandable;
    private final Predicate<Location> spawnLoaded;
    private final Set<NPC> failedCleanup = new HashSet<>();
    private String lastFailure = "none";
    private boolean cleanupFailed;

    /** {@code catalogue} is the startup skin universe; authored spawns outside it are refused. */
    public CitizensAdapter(ResidentAppearanceCatalogue catalogue) {
        requireMainThread();
        this.catalogue = Objects.requireNonNull(catalogue);
        this.registry = CitizensAPI.createAnonymousNPCRegistry(new MemoryNPCDataStore());
        this.spawnStandable = CitizensAdapter::standable;
        this.spawnLoaded = CitizensAdapter::loaded;
    }

    // Test seam for Paper's registry-dependent block checks; skin application stays real Citizens code.
    CitizensAdapter(NPCRegistry registry, Predicate<Location> spawnStandable,
                    Predicate<Location> spawnLoaded) {
        this(registry, spawnStandable, spawnLoaded, new ResidentAppearanceCatalogue());
    }

    CitizensAdapter(NPCRegistry registry, Predicate<Location> spawnStandable,
                    Predicate<Location> spawnLoaded, ResidentAppearanceCatalogue catalogue) {
        requireMainThread();
        this.catalogue = Objects.requireNonNull(catalogue);
        this.registry = Objects.requireNonNull(registry);
        this.spawnStandable = Objects.requireNonNull(spawnStandable);
        this.spawnLoaded = Objects.requireNonNull(spawnLoaded);
    }

    /** Creates a PLAYER-type NPC and spawns it at {@code loc}; returns the handle, or null on failure. */
    public NPC spawn(String name, Location loc) {
        return spawnInternal(name, loc, null);
    }

    /** Authored path: no name lookup or visible post-spawn reskinning. */
    public NPC spawnAuthored(String name, Location loc, ResidentAppearanceCatalogue.Applied appearance) {
        requireMainThread();
        if (appearance == null || appearance.skin() == null || appearance.assetKey() == null
                || !catalogue.allows(appearance.assetKey())
                || !catalogue.cacheKey(appearance.assetKey()).equals(appearance.citizensKey())) {
            lastFailure = "missing or invalid authored appearance";
            return null;
        }
        return spawnInternal(name, loc, appearance);
    }

    private NPC spawnInternal(String name, Location loc, ResidentAppearanceCatalogue.Applied appearance) {
        requireMainThread();
        if (cleanupFailed || !spawnStandable.test(loc)) {
            lastFailure = cleanupFailed ? "cleanup incomplete" : "spawn location unloaded or blocked";
            return null;
        }
        NPC npc = null;
        boolean spawned = false;
        try {
            npc = registry.createNPC(EntityType.PLAYER, name);
            if (appearance != null) {
                SkinTrait skin = npc.getOrAddTrait(SkinTrait.class);
                skin.setFetchDefaultSkin(false);
                skin.setShouldUpdateSkins(false);
                skin.setSkinPersistent(appearance.citizensKey(), appearance.skin().signature(), appearance.skin().value());
            }
            npc.setProtected(true);
            npc.data().set(NPC.Metadata.KEEP_CHUNK_LOADED, false);
            npc.data().set(NPC.Metadata.COLLIDABLE, false);
            npc.data().set(NPC.Metadata.FLUID_PUSHABLE, false);
            npc.data().set(NPC.Metadata.PATHFINDER_OPEN_DOORS, false);
            npc.data().set(NPC.Metadata.DISABLE_DEFAULT_STUCK_ACTION, true);
            npc.getNavigator().getDefaultParameters().stuckAction(null).destinationTeleportMargin(0);
            // PLUGIN avoids the explicit Chunk.load() used by COMMAND/CHUNK_LOAD in Citizens build 4220.
            spawned = spawnLoaded.test(loc) && npc.spawn(loc, SpawnReason.PLUGIN) && location(npc) != null;
            if (!spawned) {
                lastFailure = "Citizens rejected spawn";
            }
            return spawned ? npc : null;
        } catch (RuntimeException | LinkageError t) {
            lastFailure = "spawn: " + t.getClass().getSimpleName();
            return null;
        } finally {
            if (!spawned) {
                if (npc != null) {
                    despawn(npc);
                } else {
                    // createNPC may throw after registry insertion, before returning the handle.
                    destroyAll();
                }
            }
        }
    }

    public boolean walkSegment(NPC npc, Location from, Location to) {
        requireMainThread();
        if (!safeSegment(npc, from, to)) {
            lastFailure = "segment unloaded, blocked, or outside corridor";
            cancel(npc);
            return false;
        }
        try {
            var navigator = npc.getNavigator();
            // setTarget clones DEFAULT parameters in Citizens build 4220; local parameters are discarded.
            navigator.getDefaultParameters().speedModifier(0.9f).range(4f)
                    .pathfinderType(PathfinderType.CITIZENS).debug(false)
                    .stuckAction(null).destinationTeleportMargin(0)
                    .distanceMargin(0.2).pathDistanceMargin(0.2);
            Location start = from.clone();
            Location target = to.clone();
            navigator.setTarget(params -> new SegmentStrategy(npc, start, target,
                    new AStarNavigationStrategy(npc, List.of(target.toVector()), params)));
            return navigator.isNavigating();
        } catch (RuntimeException | LinkageError t) {
            lastFailure = "navigation: " + t.getClass().getSimpleName();
            cancel(npc);
            return false;
        }
    }

    public boolean isNavigating(NPC npc) {
        requireMainThread();
        try {
            return npc.getNavigator().isNavigating();
        } catch (RuntimeException | LinkageError t) {
            lastFailure = "navigation status: " + t.getClass().getSimpleName();
            return false;
        }
    }

    public Location location(NPC npc) {
        requireMainThread();
        try {
            return npc != null && npc.isSpawned() && npc.getEntity() != null
                    ? npc.getEntity().getLocation() : null;
        } catch (RuntimeException | LinkageError t) {
            lastFailure = "location: " + t.getClass().getSimpleName();
            return null;
        }
    }

    public void despawn(NPC npc) {
        requireMainThread();
        if (npc == null) {
            return;
        }
        cancel(npc);
        try {
            npc.destroy();
            if (npc.isSpawned() || registry.getById(npc.getId()) == npc) {
                throw new IllegalStateException("despawn cancelled");
            }
            failedCleanup.remove(npc);
        } catch (RuntimeException | LinkageError t) {
            // A trait exception must not prevent registry/entity cleanup.
            try {
                if (npc.getEntity() != null) {
                    npc.getEntity().remove();
                }
                registry.deregister(npc);
                if (registry.getById(npc.getId()) == npc
                        || (npc.getEntity() != null && npc.getEntity().isValid())) {
                    throw new IllegalStateException("cleanup incomplete");
                }
                failedCleanup.remove(npc);
            } catch (RuntimeException | LinkageError cleanup) {
                failedCleanup.add(npc);
                cleanupFailed = true;
                lastFailure = "cleanup: " + cleanup.getClass().getSimpleName();
            }
        }
    }

    public void destroyAll() {
        requireMainThread();
        try {
            Set<NPC> remaining = new HashSet<>(failedCleanup);
            registry.forEach(remaining::add);
            cleanupFailed = false;
            // Per-NPC isolation: a bad trait cannot abandon the rest of the registry.
            remaining.forEach(this::despawn);
        } catch (RuntimeException | LinkageError t) {
            cleanupFailed = true;
            lastFailure = "registry cleanup: " + t.getClass().getSimpleName();
        }
    }

    public String lastFailure() {
        return lastFailure;
    }

    public boolean cleanupFailed() {
        return cleanupFailed;
    }

    public boolean cancel(NPC npc) {
        requireMainThread();
        try {
            if (npc != null) {
                npc.getNavigator().cancelNavigation();
            }
            return true;
        } catch (RuntimeException | LinkageError t) {
            lastFailure = "cancel: " + t.getClass().getSimpleName();
            return false;
        }
    }

    public void face(NPC npc, Location target) {
        requireMainThread();
        Location from = location(npc);
        if (!loaded(from) || !loaded(target) || from.getWorld() != target.getWorld()
                || from.distanceSquared(target) > 64) return;
        try {
            // Pinned Citizens 4220 NMS look measures from entity feet, not eyes.
            // The caller supplies the visitor's eye position; translate that target
            // to the feet-origin convention without changing the caller's Location.
            Location adjusted = target.clone();
            if (npc.getEntity() instanceof LivingEntity living) {
                adjusted.subtract(0, living.getEyeHeight(), 0);
            }
            npc.faceLocation(adjusted);
        } catch (RuntimeException | LinkageError e) {
            lastFailure = "face: " + e.getClass().getSimpleName();
        }
    }

    static void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("NPC rendering requires the server main thread");
        }
    }

    /** Never use Location.getChunk or World.getChunkAt as a readiness check. */
    public static boolean loaded(Location location) {
        requireMainThread();
        if (location == null || !location.isWorldLoaded()
                || !Double.isFinite(location.getX()) || !Double.isFinite(location.getY())
                || !Double.isFinite(location.getZ())
                || Math.abs(location.getX()) > 30_000_000 || Math.abs(location.getZ()) > 30_000_000) {
            return false;
        }
        World world = location.getWorld();
        return world != null && Bukkit.getWorld(world.getUID()) == world
                && location.getY() > world.getMinHeight() && location.getY() + 2 < world.getMaxHeight()
                && world.isChunkLoaded((location.getBlockX() - 1) >> 4, (location.getBlockZ() - 1) >> 4)
                && world.isChunkLoaded((location.getBlockX() - 1) >> 4, (location.getBlockZ() + 1) >> 4)
                && world.isChunkLoaded((location.getBlockX() + 1) >> 4, (location.getBlockZ() - 1) >> 4)
                && world.isChunkLoaded((location.getBlockX() + 1) >> 4, (location.getBlockZ() + 1) >> 4);
    }

    public static boolean standable(Location location) {
        if (!loaded(location)) {
            return false;
        }
        World world = location.getWorld();
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        return world.getBlockAt(x, y - 1, z).getType().isSolid()
                && clear(world, x, y, z) && clear(world, x, y + 1, z);
    }

    private static boolean clear(World world, int x, int y, int z) {
        var block = world.getBlockAt(x, y, z);
        return block.isPassable() && !block.isLiquid();
    }

    private boolean safeSegment(NPC npc, Location from, Location to) {
        if (!loaded(from) || !loaded(to) || from.getWorld() != to.getWorld()
                || !RenderGeometry.adjacent(vec(from), vec(to))) {
            return false;
        }
        Location current = location(npc);
        if (!loaded(current) || current.getWorld() != from.getWorld()
                || !RenderGeometry.confined(vec(current), vec(from), vec(to))) {
            return false;
        }
        World world = from.getWorld();
        int lowY = Math.min(from.getBlockY(), to.getBlockY());
        int highY = Math.max(from.getBlockY(), to.getBlockY());
        // Includes both orthogonal columns of a diagonal; a new wall cannot be corner-cut.
        int minX = (int) Math.floor(Math.min(from.getX(), to.getX()) - 0.3);
        int maxX = (int) Math.floor(Math.max(from.getX(), to.getX()) + 0.3);
        int minZ = (int) Math.floor(Math.min(from.getZ(), to.getZ()) - 0.3);
        int maxZ = (int) Math.floor(Math.max(from.getZ(), to.getZ()) + 0.3);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    return false;
                }
                if (!clear(world, x, highY, z) || !clear(world, x, highY + 1, z)
                        || (!standable(new Location(world, x + 0.5, lowY, z + 0.5))
                        && !standable(new Location(world, x + 0.5, highY, z + 0.5)))) {
                    return false;
                }
            }
        }
        return true;
    }

    static RenderPolicy.Vec vec(Location location) {
        return new RenderPolicy.Vec(location.getX(), location.getY(), location.getZ());
    }

    /** Citizens build 4220's iterable strategy follows supplied vectors without a path search or detour. */
    private final class SegmentStrategy extends AbstractPathStrategy {
        private final NPC npc;
        private final Location from;
        private final Location to;
        private final PathStrategy delegate;

        SegmentStrategy(NPC npc, Location from, Location to, PathStrategy delegate) {
            super(TargetType.LOCATION);
            this.npc = npc;
            this.from = from;
            this.to = to;
            this.delegate = delegate;
        }

        @Override
        public boolean update() {
            requireMainThread();
            try {
                // Check before Citizens issues movement, on every Citizens navigation tick.
                if (!safeSegment(npc, from, to)) {
                    lastFailure = "segment became unloaded, blocked, or escaped";
                    setCancelReason(CancelReason.PLUGIN);
                    return true;
                }
                boolean done = delegate.update();
                setCancelReason(delegate.getCancelReason());
                return done;
            } catch (RuntimeException | LinkageError t) {
                lastFailure = "segment: " + t.getClass().getSimpleName();
                setCancelReason(CancelReason.PLUGIN);
                return true;
            }
        }

        @Override public Location getCurrentDestination() { return delegate.getCurrentDestination(); }
        @Override public Iterable<Vector> getPath() { return delegate.getPath(); }
        @Override public Location getTargetAsLocation() { return to.clone(); }
        @Override public void stop() { delegate.stop(); }
    }
}

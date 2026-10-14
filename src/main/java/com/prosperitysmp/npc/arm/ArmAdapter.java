package com.prosperitysmp.npc.arm;

import com.prosperitysmp.npc.ProsperityNpcPlugin;
import net.alex9849.arm.AdvancedRegionMarket;
import net.alex9849.arm.adapters.WGRegion;
import net.alex9849.arm.adapters.WorldGuardInterface;
import net.alex9849.arm.regions.Region;
import net.alex9849.arm.regions.RegionManager;
import net.alex9849.arm.regions.SellType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The single, read-only boundary to AdvancedRegionMarket. It reads region
 * ownership and identity; it never modifies ARM ownership, rent, flags,
 * members, or region access.
 *
 * <p>All ARM/Bukkit reads here MUST run on the server (main) thread.
 */
public final class ArmAdapter {

    private final ProsperityNpcPlugin plugin;

    public ArmAdapter(ProsperityNpcPlugin plugin) {
        this.plugin = plugin;
    }

    /** Opaque live references: retain on main thread only, never pass to SQL workers. */
    public record OwnerRead(UUID worldId, String regionId, UUID ownerId, Object provider,
                            Object manager, Object region, Object rawRegion, Object wgManager, World world) {
        public boolean sameIncarnation(OwnerRead other) {
            return other != null && worldId.equals(other.worldId) && regionId.equals(other.regionId)
                    && ownerId.equals(other.ownerId) && OwnershipReadGuard.sameAuthority(
                    new Object[]{provider, manager, region, rawRegion, wgManager, world},
                    new Object[]{other.provider, other.manager, other.region, other.rawRegion, other.wgManager, other.world});
        }
    }

    private long nextOwnerWarning;

    /** Current private-read eligibility only; this is NOT an asynchronous financial fence. */
    public Optional<OwnerRead> ownerReadAt(Location location, UUID owner) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("ARM owner reads require the main thread");
        if (!plugin.isEnabled() || location == null || owner == null || location.getWorld() == null
                || !Bukkit.getPluginManager().isPluginEnabled("WorldGuard") || !isAvailable()) return Optional.empty();
        World world = location.getWorld();
        if (Bukkit.getWorld(world.getUID()) != world || !world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4))
            return Optional.empty();
        return strictOwnerRead(world, owner, manager -> {
            List<Region> at = manager.getRegionsByLocation(location);
            return at != null && at.size() == 1 ? at.getFirst() : null;
        });
    }

    /** Strict metadata read by exact world/region identity; never loads terrain. */
    public Optional<OwnerRead> ownerReadRegion(UUID worldId, String regionId, UUID owner) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("ARM owner reads require the main thread");
        if (worldId == null || regionId == null || owner == null) return Optional.empty();
        World world = Bukkit.getWorld(worldId);
        if (world == null) return Optional.empty();
        return strictOwnerRead(world, owner, manager -> {
            Region found = null;
            for (Region candidate : manager.getRegionsBySelltype(SellType.SELL)) {
                if (candidate.getRegionworld() == world && candidate.getRegion() != null
                        && regionId.equals(candidate.getRegion().getId())) {
                    if (found != null) return null;
                    found = candidate;
                }
            }
            return found;
        });
    }

    private Optional<OwnerRead> strictOwnerRead(World world, UUID owner,
            java.util.function.Function<RegionManager, Region> select) {
        if (!plugin.isEnabled() || !isAvailable() || Bukkit.getWorld(world.getUID()) != world
                || !Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) return Optional.empty();
        try {
            AdvancedRegionMarket provider = AdvancedRegionMarket.getInstance();
            RegionManager manager = provider.getRegionManager();
            Region region = select.apply(manager);
            if (region == null || !manager.containsRegion(region) || region.getRegionworld() != world
                    || !region.isSold() || region.getSellType() != SellType.SELL || region.getParentRegion() != null)
                return Optional.empty();
            WGRegion wrapper = region.getRegion();
            if (wrapper == null || wrapper.getParent() != null) return Optional.empty();
            String id = wrapper.getId();
            Object raw = wrapper.unwrap();
            if (raw == null || id == null || id.isBlank() || id.length() > 128) return Optional.empty();
            // Pinned WG7 public API, without adding/shading a second WorldGuard dependency.
            ClassLoader loader = raw.getClass().getClassLoader();
            Class<?> regionType = Class.forName("com.sk89q.worldguard.protection.regions.ProtectedRegion", false, loader);
            Class<?> domainType = Class.forName("com.sk89q.worldguard.domains.DefaultDomain", false, loader);
            if (!regionType.isInstance(raw)) return Optional.empty();
            Object domain = regionType.getMethod("getOwners").invoke(raw);
            if (!domainType.isInstance(domain)) return Optional.empty();
            if (!OwnershipReadGuard.validOwner(owner, domainType.getMethod("getUniqueIds").invoke(domain),
                    domainType.getMethod("getPlayers").invoke(domain), domainType.getMethod("getGroups").invoke(domain),
                    regionType.getMethod("getParent").invoke(raw) != null)) return Optional.empty();
            WorldGuardInterface integration = provider.getWorldGuardInterface();
            if (integration == null) return Optional.empty();
            Object wgManager = WorldGuardInterface.class.getMethod("getRegionManager", World.class).invoke(integration, world);
            if (wgManager == null) return Optional.empty();
            Class<?> managerType = Class.forName("com.sk89q.worldguard.protection.managers.RegionManager", false, loader);
            if (!managerType.isInstance(wgManager)
                    || managerType.getMethod("getRegion", String.class).invoke(wgManager, id) != raw) return Optional.empty();
            return Optional.of(new OwnerRead(world.getUID(), id, owner, provider, manager, region, raw, wgManager, world));
        } catch (java.lang.reflect.InvocationTargetException invocation) {
            if (invocation.getCause() instanceof VirtualMachineError fatal) throw fatal;
            if (invocation.getCause() instanceof ThreadDeath fatal) throw fatal;
            ownerReadFailure(invocation);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            ownerReadFailure(failure);
        }
        return Optional.empty();
    }

    private void ownerReadFailure(Throwable failure) {
        long now = System.nanoTime();
        if (now >= nextOwnerWarning) {
            nextOwnerWarning = now + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            plugin.getLogger().warning("Owner desk region read unavailable: " + failure.getClass().getSimpleName());
        }
    }

    /** World and WorldGuard region id only, for the one top-level ARM region containing the location. No owner data. */
    public record RegionAt(UUID worldId, String regionId) { }

    public Optional<RegionAt> regionAt(Location location) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("ARM region reads require the main thread");
        if (location == null || location.getWorld() == null || !isAvailable()) return Optional.empty();
        try {
            List<Region> at = AdvancedRegionMarket.getInstance().getRegionManager().getRegionsByLocation(location);
            if (at == null || at.size() != 1) return Optional.empty();
            Region region = at.getFirst();
            WGRegion wrapper = region.getRegion();
            if (wrapper == null || region.getParentRegion() != null || wrapper.getId() == null
                    || wrapper.getId().isBlank() || wrapper.getId().length() > 128) return Optional.empty();
            return Optional.of(new RegionAt(location.getWorld().getUID(), wrapper.getId()));
        } catch (RuntimeException | LinkageError failure) {
            return Optional.empty();
        }
    }

    /** True when ARM is installed, enabled, and its singleton is reachable. */
    public boolean isAvailable() {
        if (!Bukkit.getPluginManager().isPluginEnabled("AdvancedRegionMarket")) {
            return false;
        }
        try {
            return AdvancedRegionMarket.getInstance() != null
                    && AdvancedRegionMarket.getInstance().getRegionManager() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Reads the ARM region identified by {@code regionId} in {@code worldName}.
     * Returns empty if ARM is unavailable, the region does not exist, or the
     * read fails. Never throws.
     */
    public Optional<ArmRegionView> findRegion(String worldName, String regionId) {
        if (!isAvailable()) {
            return Optional.empty();
        }
        try {
            RegionManager rm = AdvancedRegionMarket.getInstance().getRegionManager();
            // Iterate by WG region id + world, mirroring the proven Skript approach.
            // ARM's direct name lookup is unreliable; getRegion().getId() is authoritative.
            for (SellType sellType : SellType.values()) {
                for (Region region : rm.getRegionsBySelltype(sellType)) {
                    WGRegion wg = region.getRegion();
                    if (wg == null) {
                        continue;
                    }
                    World world = region.getRegionworld();
                    if (regionId.equalsIgnoreCase(wg.getId())
                            && world != null && worldName.equals(world.getName())) {
                        return Optional.of(toView(region));
                    }
                }
            }
            return Optional.empty();
        } catch (Throwable t) {
            plugin.getLogger().warning("ARM read failed for " + worldName + "/" + regionId + ": " + t);
            return Optional.empty();
        }
    }

    /** Debug: summarize what ARM reports per selltype (counts + a few id@world samples). */
    public String debugDump() {
        if (!isAvailable()) {
            return "ARM unavailable";
        }
        StringBuilder sb = new StringBuilder();
        try {
            RegionManager rm = AdvancedRegionMarket.getInstance().getRegionManager();
            for (SellType st : SellType.values()) {
                java.util.List<Region> list = rm.getRegionsBySelltype(st);
                sb.append(st.name()).append('=').append(list.size());
                int shown = 0;
                for (Region r : list) {
                    if (shown++ >= 3) {
                        break;
                    }
                    WGRegion wg = r.getRegion();
                    World w = r.getRegionworld();
                    sb.append(" [").append(wg == null ? "nullwg" : wg.getId())
                            .append('@').append(w == null ? "nullworld" : w.getName()).append(']');
                }
                sb.append("; ");
            }
        } catch (Throwable t) {
            sb.append("ERR ").append(t);
        }
        return sb.toString();
    }

    private ArmRegionView toView(Region region) {
        WGRegion wg = region.getRegion();
        // The OUTER Region has no getId(); the WG id lives on the inner WGRegion.
        String regionId = wg != null ? wg.getId() : null;
        World world = region.getRegionworld();
        String worldName = world != null ? world.getName() : null;
        UUID worldUuid = world != null ? world.getUID() : null;
        UUID owner = region.getOwner();
        String sellType = region.getSellType() != null ? region.getSellType().name() : "UNKNOWN";
        return new ArmRegionView(regionId, worldName, worldUuid, owner, sellType, region.isSold());
    }
}

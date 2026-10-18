package com.prosperitysmp.npc.arm;

import com.prosperitysmp.npc.ProsperityNpcPlugin;
import com.prosperitysmp.npc.api.Capabilities;
import com.prosperitysmp.npc.api.GuardMode;
import com.prosperitysmp.npc.api.GuardResult;
import com.prosperitysmp.npc.api.OwnershipHooks;
import com.prosperitysmp.npc.api.OwnershipToken;
import com.prosperitysmp.npc.api.Presence;
import com.prosperitysmp.npc.api.PropertyBackend;
import com.prosperitysmp.npc.api.ProsperityNpcApi;
import com.prosperitysmp.npc.api.RegionRef;
import com.prosperitysmp.npc.api.RegionView;
import net.alex9849.arm.AdvancedRegionMarket;
import net.alex9849.arm.adapters.WGRegion;
import net.alex9849.arm.adapters.WorldGuardInterface;
import net.alex9849.arm.regions.Region;
import net.alex9849.arm.regions.RegionManager;
import net.alex9849.arm.regions.SellType;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * The single, read-only boundary to AdvancedRegionMarket. It reads region
 * ownership and identity; it never modifies ARM ownership, rent, flags,
 * members, or region access.
 *
 * <p>All ARM/Bukkit reads here MUST run on the server (main) thread.
 *
 * <p>This is the built-in property backend, id {@code arm}.
 */
public final class ArmAdapter implements PropertyBackend {
    public static final String ID = "arm";

    private static final Capabilities CAPABILITIES = Capabilities.builder()
            .value("guard", "write-hook")
            .value("owner-model", "one UUID owner, no members")
            .flag("location-lookup", true)
            .flag("region-by-id", true)
            .flag("distinguishes-missing", false)
            .value("token-identity", "object")
            .flag("needs-worldguard", true)
            .value("region-id-max-length", "128")
            .flag("main-thread-only", true)
            .build();

    private final ProsperityNpcPlugin plugin;

    public ArmAdapter(ProsperityNpcPlugin plugin) {
        this.plugin = plugin;
    }

    @Override public String id() { return ID; }
    @Override public int apiMajor() { return ProsperityNpcApi.MAJOR; }
    @Override public int apiMinor() { return ProsperityNpcApi.MINOR; }
    @Override public Capabilities capabilities() { return CAPABILITIES; }
    @Override public String displayName() { return "ARM"; }

    /** Opaque live references: retain on main thread only, never pass to SQL workers. */
    public record OwnerRead(UUID worldId, String regionId, UUID ownerId, Object provider,
                            Object manager, Object region, Object rawRegion, Object wgManager, World world)
            implements OwnershipToken {
        @Override
        public boolean sameIncarnation(OwnershipToken token) {
            // Only another ARM read can be the same incarnation; a token from any other backend never is.
            return token instanceof OwnerRead other && worldId.equals(other.worldId) && regionId.equals(other.regionId)
                    && ownerId.equals(other.ownerId) && OwnershipReadGuard.sameAuthority(
                    new Object[]{provider, manager, region, rawRegion, wgManager, world},
                    new Object[]{other.provider, other.manager, other.region, other.rawRegion, other.wgManager, other.world});
        }
    }

    private long nextOwnerWarning;

    /** Current private-read eligibility only; this is NOT an asynchronous financial fence. */
    @Override
    public Optional<OwnershipToken> ownerReadAt(Location location, UUID owner) {
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
    @Override
    public Optional<OwnershipToken> ownerReadRegion(UUID worldId, String regionId, UUID owner) {
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

    private Optional<OwnershipToken> strictOwnerRead(World world, UUID owner,
            Function<RegionManager, Region> select) {
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
        } catch (InvocationTargetException invocation) {
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
            nextOwnerWarning = now + TimeUnit.SECONDS.toNanos(30);
            plugin.getLogger().warning("Owner desk region read unavailable: " + failure.getClass().getSimpleName());
        }
    }

    /** World and WorldGuard region id only, for the one top-level ARM region containing the location. No owner data. */
    public record RegionAt(UUID worldId, String regionId) implements RegionRef { }

    @Override
    public Optional<RegionRef> regionAt(Location location) {
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

    @Override
    public boolean available() {
        return isAvailable();
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
    @Override
    public Optional<RegionView> findRegion(String worldName, String regionId) {
        if (!isAvailable()) {
            return Optional.empty();
        }
        try {
            RegionManager rm = AdvancedRegionMarket.getInstance().getRegionManager();
            // Iterate by WG region id + world, mirroring the Skript lookup.
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

    /**
     * PRESENT when ARM lists the region in that world, MISSING otherwise. ARM cannot tell an absent region from a
     * failed read, so both are MISSING, and the capability distinguishes-missing says so. UNREADABLE only when the
     * world or ARM itself is not there. Never throws.
     */
    @Override
    public Presence observe(UUID worldId, String regionId) {
        try {
            World world = worldId == null ? null : Bukkit.getWorld(worldId);
            if (world == null || regionId == null || !isAvailable()) return Presence.UNREADABLE;
            return findRegion(world.getName(), regionId)
                    .filter(v -> ArmRegionView.sameRegion(v, worldId, regionId))
                    .map(v -> Presence.PRESENT).orElse(Presence.MISSING);
        } catch (RuntimeException | LinkageError failure) {
            return Presence.UNREADABLE;
        }
    }

    /**
     * Installs the cooperative ARM ownership hook: the patched ARM calls {@code hooks.beginWrite} before any owner
     * change and fails it while ProsperityNPC holds the region. Throws when the patched ARM is missing or speaks
     * another protocol; the caller reports that. A reflective failure is carried out unchanged inside
     * {@link GuardInstallFailure}.
     */
    @Override
    public GuardResult installGuard(OwnershipHooks hooks) {
        try {
            Plugin arm = Bukkit.getServer().getPluginManager().getPlugin("AdvancedRegionMarket");
            if (arm == null || !arm.isEnabled()) throw new IllegalStateException("ARM unavailable");
            Class<?> hook = Class.forName("net.alex9849.arm.NpcTransactionGuard", true, arm.getClass().getClassLoader());
            if (hook.getField("PROTOCOL").getInt(null) != 1) throw new IllegalStateException("Unsupported ownership hook");
            BiFunction<UUID, String, Runnable> writer = hooks::beginWrite;
            hook.getMethod("install", BiFunction.class).invoke(null, writer);
            return GuardResult.installed(GuardMode.WRITE_HOOK);
        } catch (ReflectiveOperationException reflective) {
            throw new GuardInstallFailure(reflective);
        }
    }

    /** A reflective failure from {@link #installGuard}; {@link #reflective()} is the original exception. */
    public static final class GuardInstallFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        GuardInstallFailure(ReflectiveOperationException cause) { super(cause); }
        public ReflectiveOperationException reflective() { return (ReflectiveOperationException) getCause(); }
    }

    @Override
    public List<String> describe() {
        return List.of("ARM " + (isAvailable() ? "available" : "UNAVAILABLE"), debugDump());
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
                List<Region> list = rm.getRegionsBySelltype(st);
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

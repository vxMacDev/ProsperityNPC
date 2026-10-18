package com.prosperitysmp.npc.property.worldguard;

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
import com.prosperitysmp.npc.arm.OwnershipReadGuard;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The built-in backend {@code worldguard-listed}: plain WorldGuard regions, where staff give a region exactly one
 * WorldGuard owner and that player is the home owner. Region ids are WorldGuard ids. WorldGuard owner edits fire no
 * event that can be vetoed, so the guard is none and payouts follow {@code property.when-unguarded}. A recreated
 * region gives a new token; an owner round trip on the same region object does not. Every read
 * runs on the main thread and never throws; WorldGuard itself is reached through {@link Reader}.
 */
public final class WorldGuardListedBackend implements PropertyBackend {
    public static final String ID = "worldguard-listed";
    public static final String DIAGNOSE_LINE =
            "worldguard-listed: one WorldGuard owner per region; guard none; payouts held by default";

    private static final Capabilities CAPABILITIES = Capabilities.builder()
            .value("guard", "none")
            .value("owner-model", "exactly one WorldGuard owner UUID; no names, groups or parent")
            .flag("location-lookup", true)
            .flag("region-by-id", true)
            .flag("distinguishes-missing", true)
            // A redefined or recreated region is a new object and is seen. WorldGuard edits the owner list in place,
            // so an owner round trip (A to B and back to A) keeps the same objects and is not.
            .value("token-identity", "object")
            .flag("sees-owner-round-trip", false)
            .flag("needs-worldguard", true)
            .value("region-id-max-length", "128")
            .flag("main-thread-only", true)
            .build();

    /** One region as WorldGuard holds it: its live object and its owner domain. */
    public record RegionData(Object identity, Object ownerUuids, Object ownerNames, Object ownerGroups, boolean hasParent) {
        public RegionData {
            Objects.requireNonNull(identity);
        }

        /** The single owner UUID, when the owner domain is exactly one UUID with no names or groups. */
        Optional<UUID> soleOwner() {
            if (!(ownerUuids instanceof Set<?> uuids) || uuids.size() != 1 || !(ownerNames instanceof Set<?> names)
                    || !names.isEmpty() || !(ownerGroups instanceof Set<?> groups) || !groups.isEmpty() || hasParent)
                return Optional.empty();
            return uuids.iterator().next() instanceof UUID owner ? Optional.of(owner) : Optional.empty();
        }
    }

    /** The WorldGuard calls this backend needs. The real one is {@link ReflectiveWorldGuard}. */
    public interface Reader {
        boolean available();

        /** The world's region manager, or null when it cannot be read. */
        Object manager(World world) throws ReflectiveOperationException;

        /** The region with exactly this id, or null. */
        RegionData region(Object manager, String regionId) throws ReflectiveOperationException;

        /** Ids of the regions that contain the block, without the global region. */
        List<String> idsAt(Object manager, int x, int y, int z) throws ReflectiveOperationException;
    }

    /** One owner read. The same incarnation only for the same live region and manager objects. */
    public record Token(UUID worldId, String regionId, UUID ownerId, Object region, Object manager) implements OwnershipToken {
        @Override
        public boolean sameIncarnation(OwnershipToken other) {
            return other instanceof Token t && worldId.equals(t.worldId) && regionId.equals(t.regionId)
                    && ownerId.equals(t.ownerId)
                    && OwnershipReadGuard.sameAuthority(new Object[]{region, manager}, new Object[]{t.region, t.manager});
        }
    }

    private record At(UUID worldId, String regionId) implements RegionRef { }

    private final Reader reader;
    private final BooleanSupplier mainThread;
    private final Function<UUID, World> worlds;
    private final Function<String, World> worldsByName;
    private final Predicate<Location> chunkLoaded;

    /**
     * @param worlds the loaded world for a UUID, or null
     * @param worldsByName the loaded world with a name, or null
     * @param chunkLoaded true when the location's chunk is loaded; a lookup never loads terrain
     */
    public WorldGuardListedBackend(Reader reader, BooleanSupplier mainThread, Function<UUID, World> worlds,
                                   Function<String, World> worldsByName, Predicate<Location> chunkLoaded) {
        this.reader = Objects.requireNonNull(reader);
        this.mainThread = Objects.requireNonNull(mainThread);
        this.worlds = Objects.requireNonNull(worlds);
        this.worldsByName = Objects.requireNonNull(worldsByName);
        this.chunkLoaded = Objects.requireNonNull(chunkLoaded);
    }

    @Override public String id() { return ID; }
    @Override public int apiMajor() { return ProsperityNpcApi.MAJOR; }
    @Override public int apiMinor() { return ProsperityNpcApi.MINOR; }
    @Override public Capabilities capabilities() { return CAPABILITIES; }
    @Override public String displayName() { return "WorldGuard"; }

    @Override
    public boolean available() {
        try {
            return reader.available();
        } catch (RuntimeException | LinkageError failure) {
            return false;
        }
    }

    /** WorldGuard has no owner-change event to veto: guard none. */
    @Override
    public GuardResult installGuard(OwnershipHooks hooks) {
        return GuardResult.installed(GuardMode.NONE);
    }

    @Override
    public Optional<RegionRef> regionAt(Location at) {
        requireMain();
        World world = at == null ? null : at.getWorld();
        if (world == null || worlds.apply(world.getUID()) != world || !available() || !chunkLoaded.test(at)) return Optional.empty();
        try {
            Object manager = reader.manager(world);
            if (manager == null) return Optional.empty();
            List<String> ids = reader.idsAt(manager, at.getBlockX(), at.getBlockY(), at.getBlockZ());
            if (ids == null || ids.size() != 1 || !validId(ids.getFirst())) return Optional.empty();
            return Optional.of(new At(world.getUID(), ids.getFirst()));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<RegionView> findRegion(String worldName, String regionId) {
        requireMain();
        if (worldName == null || !validId(regionId) || !available()) return Optional.empty();
        try {
            World world = worldsByName.apply(worldName);
            if (world == null) return Optional.empty();
            Object manager = reader.manager(world);
            RegionData data = manager == null ? null : reader.region(manager, regionId);
            if (data == null) return Optional.empty();
            UUID worldId = world.getUID();
            Optional<UUID> owner = data.soleOwner();
            return Optional.of(new RegionView() {
                @Override public UUID worldId() { return worldId; }
                @Override public String regionId() { return regionId; }
                @Override public String market() { return ID; }
                @Override public String kind() { return "owned"; }
                @Override public Optional<UUID> ownerUuid() { return owner; }
                @Override public boolean matches(String name, String id) { return worldName.equals(name) && regionId.equals(id); }
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<OwnershipToken> ownerReadAt(Location at, UUID owner) {
        return regionAt(at).flatMap(ref -> ownerReadRegion(ref.worldId(), ref.regionId(), owner));
    }

    @Override
    public Optional<OwnershipToken> ownerReadRegion(UUID worldId, String regionId, UUID owner) {
        requireMain();
        if (worldId == null || !validId(regionId) || owner == null || owner.equals(new UUID(0, 0)) || !available())
            return Optional.empty();
        try {
            World world = worlds.apply(worldId);
            Object manager = world == null ? null : reader.manager(world);
            RegionData data = manager == null ? null : reader.region(manager, regionId);
            if (data == null || !OwnershipReadGuard.validOwner(owner, data.ownerUuids(), data.ownerNames(),
                    data.ownerGroups(), data.hasParent())) return Optional.empty();
            return Optional.of(new Token(worldId, regionId, owner, data.identity(), manager));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return Optional.empty();
        }
    }

    @Override
    public Presence observe(UUID worldId, String regionId) {
        try {
            World world = worldId == null ? null : worlds.apply(worldId);
            if (world == null || regionId == null || !available()) return Presence.UNREADABLE;
            Object manager = reader.manager(world);
            if (manager == null) return Presence.UNREADABLE;
            return reader.region(manager, regionId) != null ? Presence.PRESENT : Presence.MISSING;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            return Presence.UNREADABLE;
        }
    }

    @Override
    public List<String> describe() {
        return List.of(DIAGNOSE_LINE, "WorldGuard " + (available() ? "available" : "UNAVAILABLE"));
    }

    private static boolean validId(String id) {
        return id != null && !id.isBlank() && id.length() <= 128 && !"__global__".equalsIgnoreCase(id);
    }

    private void requireMain() {
        if (!mainThread.getAsBoolean()) throw new IllegalStateException("WorldGuard reads require the main thread");
    }
}

package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.api.Capabilities;
import com.prosperitysmp.npc.api.GuardResult;
import com.prosperitysmp.npc.api.OwnershipHooks;
import com.prosperitysmp.npc.api.OwnershipToken;
import com.prosperitysmp.npc.api.Presence;
import com.prosperitysmp.npc.api.PropertyBackend;
import com.prosperitysmp.npc.api.ProsperityNpcApi;
import com.prosperitysmp.npc.api.RegionRef;
import com.prosperitysmp.npc.api.RegionView;
import com.prosperitysmp.npc.api.contract.PropertyBackendContract;
import org.bukkit.Location;
import org.bukkit.World;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The property-backend contract run against an in-memory backend. It proves the contract itself; ARM is proven by
 * its own tests over the real ARM classes and by the DEV positive control.
 */
public class FakeBackendContractTest extends PropertyBackendContract {
    static final UUID WORLD = new UUID(5, 5), OWNER = new UUID(6, 6);

    @Override
    protected Subject subject(Scenario s) {
        var backend = new FakeBackend();
        backend.regions.put("plot-1", new FakeBackend.Region(0, 0, 4, 4, OWNER));
        return new Subject() {
            @Override public PropertyBackend backend() { return backend; }
            @Override public boolean knowsRegions() { return true; }
            @Override public UUID world() { return WORLD; }
            @Override public String region() { return "plot-1"; }
            @Override public UUID owner() { return OWNER; }

            @Override
            public List<Location> cellsOf(String region) {
                var r = backend.regions.get(region);
                var out = new ArrayList<Location>();
                for (int x = r.x1; x <= r.x2; x++) for (int z = r.z1; z <= r.z2; z++) out.add(new Location(FakeBackend.WORLD_OBJECT, x, 64, z));
                return out;
            }

            @Override public Location outside() { return new Location(FakeBackend.WORLD_OBJECT, 100, 64, 100); }
            @Override public void setOwner(String region, UUID owner) { backend.regions.get(region).setOwner(owner); }
            @Override public void addMember(String region, UUID member) { backend.regions.get(region).members.add(member); }
            @Override public void unrelatedEdit(String region) { backend.regions.get(region).flags++; }
            @Override public void removeRegion(String region) { backend.regions.remove(region); }
            @Override public void makeUnreadable() { backend.readable = false; }
            @Override public List<UUID> unpayableOwners() { return List.of(new UUID(0, 0)); }
        };
    }

    /** One world of rectangular regions, one paying owner each. A new owner starts a new generation. */
    static final class FakeBackend implements PropertyBackend {
        static final World WORLD_OBJECT = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getUID" -> WORLD;
                    case "getName" -> "fake";
                    case "hashCode" -> 5;
                    case "equals" -> p == a[0];
                    case "toString" -> "fake-world";
                    default -> throw new UnsupportedOperationException(m.getName());
                });

        static final class Region {
            final int x1, z1, x2, z2;
            final Set<UUID> members = new HashSet<>();
            UUID owner;
            long generation;
            int flags;
            static long nextGeneration = 1;

            Region(int x1, int z1, int x2, int z2, UUID owner) {
                this.x1 = x1; this.z1 = z1; this.x2 = x2; this.z2 = z2;
                setOwner(owner);
            }

            void setOwner(UUID owner) {
                this.owner = owner;
                generation = nextGeneration++;
            }
        }

        record Token(UUID worldId, String regionId, UUID ownerId, long generation) implements OwnershipToken {
            @Override public boolean sameIncarnation(OwnershipToken other) {
                return other instanceof Token t && t.equals(this);
            }
        }

        final Map<String, Region> regions = new HashMap<>();
        boolean readable = true;

        @Override public String id() { return "fake"; }
        @Override public int apiMajor() { return ProsperityNpcApi.MAJOR; }
        @Override public int apiMinor() { return ProsperityNpcApi.MINOR; }
        @Override public Capabilities capabilities() {
            return Capabilities.builder().value("guard", "none").flag("distinguishes-missing", true)
                    .flag("sees-owner-round-trip", true).build();
        }
        @Override public String displayName() { return "Fake"; }
        @Override public boolean available() { return readable; }
        @Override public GuardResult installGuard(OwnershipHooks hooks) { return GuardResult.failed("test only"); }

        @Override
        public Optional<RegionRef> regionAt(Location at) {
            if (!readable || at.getWorld() == null || !WORLD.equals(at.getWorld().getUID())) return Optional.empty();
            for (var entry : regions.entrySet()) {
                Region r = entry.getValue();
                if (at.getBlockX() >= r.x1 && at.getBlockX() <= r.x2 && at.getBlockZ() >= r.z1 && at.getBlockZ() <= r.z2) {
                    String id = entry.getKey();
                    return Optional.of(new RegionRef() {
                        @Override public UUID worldId() { return WORLD; }
                        @Override public String regionId() { return id; }
                    });
                }
            }
            return Optional.empty();
        }

        @Override
        public Optional<RegionView> findRegion(String worldName, String regionId) {
            Region r = readable ? regions.get(regionId) : null;
            if (r == null) return Optional.empty();
            return Optional.of(new RegionView() {
                @Override public UUID worldId() { return WORLD; }
                @Override public String regionId() { return regionId; }
                @Override public String market() { return "fake"; }
                @Override public String kind() { return "owned"; }
                @Override public Optional<UUID> ownerUuid() { return Optional.ofNullable(r.owner); }
                @Override public boolean matches(String world, String id) { return regionId.equalsIgnoreCase(id); }
            });
        }

        @Override
        public Optional<OwnershipToken> ownerReadAt(Location at, UUID owner) {
            return regionAt(at).flatMap(ref -> ownerReadRegion(ref.worldId(), ref.regionId(), owner));
        }

        @Override
        public Optional<OwnershipToken> ownerReadRegion(UUID world, String regionId, UUID owner) {
            Region r = readable && WORLD.equals(world) ? regions.get(regionId) : null;
            if (r == null || owner == null || !owner.equals(r.owner) || owner.equals(new UUID(0, 0))) return Optional.empty();
            return Optional.of(new Token(world, regionId, owner, r.generation));
        }

        @Override
        public Presence observe(UUID world, String regionId) {
            if (!readable) return Presence.UNREADABLE;
            return WORLD.equals(world) && regions.containsKey(regionId) ? Presence.PRESENT : Presence.MISSING;
        }

        @Override public List<String> describe() { return List.of("fake: " + regions.size() + " region(s)"); }
    }
}

package com.prosperitysmp.npc.property.worldguard;

import com.prosperitysmp.npc.api.GuardMode;
import com.prosperitysmp.npc.api.PropertyBackend;
import com.prosperitysmp.npc.api.contract.PropertyBackendContract;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The built-in worldguard-listed backend against the property contract, over a fake WorldGuard reader. */
public class WorldGuardListedContractTest extends PropertyBackendContract {
    static final UUID WORLD = new UUID(9, 9), OWNER = new UUID(6, 6);
    static final World WORLD_OBJECT = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
            (p, m, a) -> switch (m.getName()) {
                case "getUID" -> WORLD;
                case "getName" -> "town";
                case "hashCode" -> 9;
                case "equals" -> p == a[0];
                case "toString" -> "town-world";
                default -> throw new UnsupportedOperationException(m.getName());
            });

    /** WorldGuard as the backend sees it: regions are replaced as whole objects when redefined. */
    static final class FakeWorldGuard implements WorldGuardListedBackend.Reader {
        static final class Region {
            final int x1, z1, x2, z2;
            final Set<UUID> owners = new HashSet<>();
            final Set<String> names = new HashSet<>(), groups = new HashSet<>();
            boolean parent;
            Region(int x1, int z1, int x2, int z2) { this.x1 = x1; this.z1 = z1; this.x2 = x2; this.z2 = z2; }
        }

        final Object manager = new Object();
        final Map<String, Region> regions = new HashMap<>();
        boolean up = true;

        @Override public boolean available() { return up; }

        @Override public Object manager(World world) { return up && world == WORLD_OBJECT ? manager : null; }

        @Override
        public WorldGuardListedBackend.RegionData region(Object manager, String regionId) {
            Region r = regions.get(regionId);
            return r == null ? null : new WorldGuardListedBackend.RegionData(r, Set.copyOf(r.owners), Set.copyOf(r.names),
                    Set.copyOf(r.groups), r.parent);
        }

        @Override
        public List<String> idsAt(Object manager, int x, int y, int z) {
            var out = new ArrayList<String>();
            regions.forEach((id, r) -> { if (x >= r.x1 && x <= r.x2 && z >= r.z1 && z <= r.z2) out.add(id); });
            return out;
        }
    }

    static WorldGuardListedBackend backend(FakeWorldGuard wg) {
        return new WorldGuardListedBackend(wg, () -> true, id -> WORLD.equals(id) ? WORLD_OBJECT : null,
                name -> "town".equals(name) ? WORLD_OBJECT : null, at -> true);
    }

    @Override
    protected Subject subject(Scenario s) {
        var wg = new FakeWorldGuard();
        var home = new FakeWorldGuard.Region(0, 0, 4, 4);
        home.owners.add(OWNER);
        wg.regions.put("home-1", home);
        var backend = backend(wg);
        return new Subject() {
            @Override public PropertyBackend backend() { return backend; }
            @Override public boolean knowsRegions() { return true; }
            @Override public UUID world() { return WORLD; }
            @Override public String region() { return "home-1"; }
            @Override public UUID owner() { return OWNER; }
            @Override public List<Location> cellsOf(String region) {
                var r = wg.regions.get(region);
                var out = new ArrayList<Location>();
                for (int x = r.x1; x <= r.x2; x++) for (int z = r.z1; z <= r.z2; z++) out.add(new Location(WORLD_OBJECT, x, 64, z));
                return out;
            }
            @Override public Location outside() { return new Location(WORLD_OBJECT, 100, 64, 100); }
            @Override public void setOwner(String region, UUID owner) {
                // WorldGuard keeps the region object and edits its owner domain in place.
                var r = wg.regions.get(region);
                r.owners.clear();
                r.owners.add(owner);
            }
            @Override public void addMember(String region, UUID member) { }
            @Override public void unrelatedEdit(String region) { }
            @Override public void removeRegion(String region) { wg.regions.remove(region); }
            @Override public void makeUnreadable() { wg.up = false; }
            @Override public List<UUID> unpayableOwners() { return List.of(new UUID(0, 0)); }
        };
    }

    @Test void twoOwnersANameOrAGroupIsNoOwnerAtAll() {
        var wg = new FakeWorldGuard();
        var r = new FakeWorldGuard.Region(0, 0, 4, 4);
        r.owners.add(OWNER);
        wg.regions.put("home-1", r);
        var backend = backend(wg);
        assertTrue(backend.ownerReadRegion(WORLD, "home-1", OWNER).isPresent());
        r.owners.add(new UUID(7, 7));
        assertTrue(backend.ownerReadRegion(WORLD, "home-1", OWNER).isEmpty(), "two owners");
        r.owners.remove(new UUID(7, 7));
        r.names.add("steve");
        assertTrue(backend.ownerReadRegion(WORLD, "home-1", OWNER).isEmpty(), "a name owner");
        r.names.clear();
        r.groups.add("builders");
        assertTrue(backend.ownerReadRegion(WORLD, "home-1", OWNER).isEmpty(), "a group owner");
        r.groups.clear();
        r.parent = true;
        assertTrue(backend.ownerReadRegion(WORLD, "home-1", OWNER).isEmpty(), "inherited ownership");
        assertTrue(backend.findRegion("town", "home-1").orElseThrow().ownerUuid().isEmpty());
    }

    @Test void aRedefinedRegionIsANewIncarnation() {
        var wg = new FakeWorldGuard();
        var r = new FakeWorldGuard.Region(0, 0, 4, 4);
        r.owners.add(OWNER);
        wg.regions.put("home-1", r);
        var backend = backend(wg);
        var before = backend.ownerReadRegion(WORLD, "home-1", OWNER).orElseThrow();
        var again = new FakeWorldGuard.Region(0, 0, 4, 4);
        again.owners.add(OWNER);
        wg.regions.put("home-1", again);
        assertFalse(before.sameIncarnation(backend.ownerReadRegion(WORLD, "home-1", OWNER).orElseThrow()));
    }

    @Test void overlappingRegionsAndTheGlobalRegionNeverResolve() {
        var wg = new FakeWorldGuard();
        wg.regions.put("a", new FakeWorldGuard.Region(0, 0, 4, 4));
        wg.regions.put("b", new FakeWorldGuard.Region(2, 2, 6, 6));
        var backend = backend(wg);
        assertTrue(backend.regionAt(new Location(WORLD_OBJECT, 3, 64, 3)).isEmpty(), "two regions: no answer");
        assertTrue(backend.regionAt(new Location(WORLD_OBJECT, 1, 64, 1)).isPresent());
        assertTrue(backend.ownerReadRegion(WORLD, "__global__", OWNER).isEmpty());
    }

    @Test void guardNoneDiagnoseAndMarket() {
        var wg = new FakeWorldGuard();
        var r = new FakeWorldGuard.Region(0, 0, 4, 4);
        r.owners.add(OWNER);
        wg.regions.put("home-1", r);
        var backend = backend(wg);
        assertEquals(GuardMode.NONE, backend.installGuard(null).mode().orElseThrow());
        assertEquals("worldguard-listed: one WorldGuard owner per region; guard none; payouts held by default",
                backend.describe().getFirst());
        var view = backend.findRegion("town", "home-1").orElseThrow();
        assertEquals("worldguard-listed", view.market());
        assertEquals("owned", view.kind());
        assertEquals("object", backend.capabilities().value("token-identity"));
        assertFalse(backend.capabilities().flag("sees-owner-round-trip"), "an owner round trip keeps the same objects");
    }
}

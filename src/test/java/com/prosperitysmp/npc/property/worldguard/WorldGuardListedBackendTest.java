package com.prosperitysmp.npc.property.worldguard;

import com.prosperitysmp.npc.api.GuardMode;
import com.prosperitysmp.npc.api.Presence;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Every refusal branch of the worldguard-listed backend, over a scripted WorldGuard reader. */
class WorldGuardListedBackendTest {
    private static final UUID WORLD = new UUID(0, 61);
    private static final UUID OWNER = new UUID(0, 62);
    private static final World TOWN = world(WORLD, "town");
    private static final World TWIN = world(WORLD, "town");

    private static World world(UUID id, String name) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getUID" -> id;
                    case "getName" -> name;
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == a[0];
                    case "toString" -> name;
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    /** A reader whose answers each test sets directly. */
    private static final class Scripted implements WorldGuardListedBackend.Reader {
        boolean up = true;
        RuntimeException availableThrows;
        Object manager = new Object();
        Exception managerThrows;
        final Map<String, WorldGuardListedBackend.RegionData> regions = new TreeMap<>();
        List<String> ids = List.of("home-1");
        Exception idsThrows;
        final List<String> calls = new ArrayList<>();

        @Override public boolean available() {
            if (availableThrows != null) throw availableThrows;
            return up;
        }

        @Override public Object manager(World world) throws ReflectiveOperationException {
            calls.add("manager " + world);
            throwIfSet(managerThrows);
            return manager;
        }

        @Override public WorldGuardListedBackend.RegionData region(Object manager, String regionId) {
            calls.add("region " + regionId);
            return regions.get(regionId);
        }

        @Override public List<String> idsAt(Object manager, int x, int y, int z) throws ReflectiveOperationException {
            calls.add("idsAt " + x + "," + y + "," + z);
            throwIfSet(idsThrows);
            return ids;
        }

        private static void throwIfSet(Exception e) throws ReflectiveOperationException {
            if (e instanceof ReflectiveOperationException r) throw r;
            if (e instanceof RuntimeException r) throw r;
        }
    }

    private final Scripted wg = new Scripted();
    private boolean main = true;
    private boolean chunkLoaded = true;
    private final WorldGuardListedBackend backend = new WorldGuardListedBackend(wg, () -> main,
            id -> WORLD.equals(id) ? TOWN : null, name -> "town".equals(name) ? TOWN : null, at -> chunkLoaded);

    private final Object regionObject = new Object();

    private static WorldGuardListedBackend.RegionData data(Object identity, Object uuids, Object names, Object groups, boolean parent) {
        return new WorldGuardListedBackend.RegionData(identity, uuids, names, groups, parent);
    }

    private void ownedHome() {
        wg.regions.put("home-1", data(regionObject, Set.of(OWNER), Set.of(), Set.of(), false));
    }

    private static Location at(World world, double x, double y, double z) {
        return new Location(world, x, y, z);
    }

    @Test void identityAndCapabilities() {
        assertEquals("worldguard-listed", backend.id());
        assertEquals("WorldGuard", backend.displayName());
        assertEquals(1, backend.apiMajor());
        assertEquals(2, backend.apiMinor());
        var expected = new TreeMap<String, String>();
        expected.put("guard", "none");
        expected.put("owner-model", "exactly one WorldGuard owner UUID; no names, groups or parent");
        expected.put("location-lookup", "true");
        expected.put("region-by-id", "true");
        expected.put("distinguishes-missing", "true");
        expected.put("token-identity", "object");
        expected.put("sees-owner-round-trip", "false");
        expected.put("needs-worldguard", "true");
        expected.put("region-id-max-length", "128");
        expected.put("main-thread-only", "true");
        assertEquals(expected, backend.capabilities().all());
    }

    @Test void theGuardIsAlwaysNoneAndNeedsNoHooks() {
        var result = backend.installGuard(null);
        assertTrue(result.isInstalled());
        assertEquals(GuardMode.NONE, result.mode().orElseThrow());
        assertTrue(backend.guardBindings().isEmpty());
    }

    @Test void constructorArgumentsAreRequired() {
        assertThrows(NullPointerException.class, () -> new WorldGuardListedBackend(null, () -> true, id -> null, n -> null, a -> true));
        assertThrows(NullPointerException.class, () -> new WorldGuardListedBackend(wg, null, id -> null, n -> null, a -> true));
        assertThrows(NullPointerException.class, () -> new WorldGuardListedBackend(wg, () -> true, null, n -> null, a -> true));
        assertThrows(NullPointerException.class, () -> new WorldGuardListedBackend(wg, () -> true, id -> null, null, a -> true));
        assertThrows(NullPointerException.class, () -> new WorldGuardListedBackend(wg, () -> true, id -> null, n -> null, null));
    }

    @Test void availabilityFollowsTheReaderAndAThrowIsUnavailable() {
        assertTrue(backend.available());
        wg.up = false;
        assertFalse(backend.available());
        wg.up = true;
        wg.availableThrows = new IllegalStateException("broken");
        assertFalse(backend.available());
    }

    @Test void describeNamesAvailability() {
        assertEquals(List.of(WorldGuardListedBackend.DIAGNOSE_LINE, "WorldGuard available"), backend.describe());
        wg.up = false;
        assertEquals(List.of(WorldGuardListedBackend.DIAGNOSE_LINE, "WorldGuard UNAVAILABLE"), backend.describe());
    }

    @Test void everyMainThreadReadRefusesOffMain() {
        main = false;
        Location home = at(TOWN, 1, 64, 1);
        for (Runnable read : List.<Runnable>of(() -> backend.regionAt(home), () -> backend.findRegion("town", "home-1"),
                () -> backend.ownerReadRegion(WORLD, "home-1", OWNER), () -> backend.ownerReadAt(home, OWNER))) {
            var failure = assertThrows(IllegalStateException.class, read::run);
            assertEquals("WorldGuard reads require the main thread", failure.getMessage());
        }
        assertTrue(wg.calls.isEmpty(), wg.calls.toString());
    }

    @Test void observeNeverThrowsOffMain() {
        ownedHome();
        main = false;
        assertEquals(Presence.PRESENT, backend.observe(WORLD, "home-1"));
    }

    @Test void regionAtReadsTheBlockCoordinates() {
        var ref = backend.regionAt(at(TOWN, 2.7, 64.2, -0.5)).orElseThrow();
        assertEquals(WORLD, ref.worldId());
        assertEquals("home-1", ref.regionId());
        assertEquals(List.of("manager town", "idsAt 2,64,-1"), wg.calls);
    }

    @Test void regionAtRefusesAMissingLocationOrWorld() {
        assertEquals(Optional.empty(), backend.regionAt(null));
        assertEquals(Optional.empty(), backend.regionAt(at(null, 1, 64, 1)));
        assertTrue(wg.calls.isEmpty());
    }

    @Test void regionAtRefusesAWorldObjectThatIsNotTheLoadedOne() {
        assertEquals(Optional.empty(), backend.regionAt(at(TWIN, 1, 64, 1)));
        assertTrue(wg.calls.isEmpty(), "a stale world object with the same UUID is never read");
    }

    @Test void regionAtNeverLoadsTerrain() {
        chunkLoaded = false;
        assertEquals(Optional.empty(), backend.regionAt(at(TOWN, 1, 64, 1)));
        assertTrue(wg.calls.isEmpty());
    }

    @Test void regionAtRefusesWhileWorldGuardIsDown() {
        wg.up = false;
        assertEquals(Optional.empty(), backend.regionAt(at(TOWN, 1, 64, 1)));
        assertTrue(wg.calls.isEmpty());
    }

    @Test void regionAtRefusesAMissingManagerOrIdList() {
        wg.manager = null;
        assertEquals(Optional.empty(), backend.regionAt(at(TOWN, 1, 64, 1)));
        wg.manager = new Object();
        wg.ids = null;
        assertEquals(Optional.empty(), backend.regionAt(at(TOWN, 1, 64, 1)));
    }

    @Test void regionAtNeedsExactlyOneRegion() {
        wg.ids = List.of();
        assertEquals(Optional.empty(), backend.regionAt(at(TOWN, 1, 64, 1)));
        wg.ids = List.of("home-1", "home-2");
        assertEquals(Optional.empty(), backend.regionAt(at(TOWN, 1, 64, 1)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"__global__", "__GLOBAL__", "", "  "})
    void regionAtRefusesTheGlobalRegionAndBlankIds(String id) {
        wg.ids = List.of(id);
        assertEquals(Optional.empty(), backend.regionAt(at(TOWN, 1, 64, 1)));
    }

    @Test void regionIdsUpToOneHundredTwentyEightCharactersAreAccepted() {
        wg.ids = List.of("a".repeat(128));
        assertEquals("a".repeat(128), backend.regionAt(at(TOWN, 1, 64, 1)).orElseThrow().regionId());
        wg.ids = List.of("a".repeat(129));
        assertEquals(Optional.empty(), backend.regionAt(at(TOWN, 1, 64, 1)));
    }

    @Test void regionAtTurnsReaderFailuresIntoEmpty() {
        wg.managerThrows = new ClassNotFoundException("com.sk89q.worldguard.WorldGuard");
        assertEquals(Optional.empty(), backend.regionAt(at(TOWN, 1, 64, 1)));
        wg.managerThrows = null;
        wg.idsThrows = new IllegalStateException("bad set");
        assertEquals(Optional.empty(), backend.regionAt(at(TOWN, 1, 64, 1)));
    }

    @Test void findRegionGivesAnOwnedViewOfTheRequestedId() {
        ownedHome();
        var view = backend.findRegion("town", "home-1").orElseThrow();
        assertEquals(WORLD, view.worldId());
        assertEquals("home-1", view.regionId());
        assertEquals("worldguard-listed", view.market());
        assertEquals("owned", view.kind());
        assertEquals(Optional.of(OWNER), view.ownerUuid());
        assertTrue(view.matches("town", "home-1"));
        assertFalse(view.matches("Town", "home-1"));
        assertFalse(view.matches("town", "HOME-1"), "WorldGuard-listed ids match exactly");
        assertFalse(view.matches(null, "home-1"));
    }

    @Test void findRegionRefusesBadInput() {
        ownedHome();
        assertEquals(Optional.empty(), backend.findRegion(null, "home-1"));
        assertEquals(Optional.empty(), backend.findRegion("town", null));
        assertEquals(Optional.empty(), backend.findRegion("town", "__global__"));
        assertEquals(Optional.empty(), backend.findRegion("town", "x".repeat(129)));
        assertEquals(Optional.empty(), backend.findRegion("nether", "home-1"));
        assertTrue(wg.calls.isEmpty(), wg.calls.toString());
    }

    @Test void findRegionRefusesAMissingManagerOrRegion() {
        assertEquals(Optional.empty(), backend.findRegion("town", "home-1"));
        ownedHome();
        wg.manager = null;
        assertEquals(Optional.empty(), backend.findRegion("town", "home-1"));
        wg.manager = new Object();
        wg.up = false;
        assertEquals(Optional.empty(), backend.findRegion("town", "home-1"));
    }

    @Test void findRegionShowsNoOwnerForASharedRegion() {
        wg.regions.put("home-1", data(regionObject, Set.of(OWNER, new UUID(0, 63)), Set.of(), Set.of(), false));
        assertEquals(Optional.empty(), backend.findRegion("town", "home-1").orElseThrow().ownerUuid());
    }

    @Test void findRegionTurnsReaderFailuresIntoEmpty() {
        ownedHome();
        wg.managerThrows = new NoSuchMethodException("getRegionContainer");
        assertEquals(Optional.empty(), backend.findRegion("town", "home-1"));
    }

    @Test void ownerReadRegionGivesATokenForTheSoleOwner() {
        ownedHome();
        var token = (WorldGuardListedBackend.Token) backend.ownerReadRegion(WORLD, "home-1", OWNER).orElseThrow();
        assertEquals(WORLD, token.worldId());
        assertEquals("home-1", token.regionId());
        assertEquals(OWNER, token.ownerId());
        assertSame(regionObject, token.region());
        assertSame(wg.manager, token.manager());
        assertTrue(token.sameIncarnation(backend.ownerReadRegion(WORLD, "home-1", OWNER).orElseThrow()));
    }

    @Test void ownerReadRegionRefusesAnyoneButTheOwner() {
        ownedHome();
        assertEquals(Optional.empty(), backend.ownerReadRegion(WORLD, "home-1", new UUID(0, 64)));
        assertEquals(Optional.empty(), backend.ownerReadRegion(WORLD, "home-1", null));
        assertEquals(Optional.empty(), backend.ownerReadRegion(WORLD, "home-1", new UUID(0, 0)));
    }

    @Test void theNilUuidIsRefusedEvenWhenWorldGuardListsIt() {
        wg.regions.put("home-1", data(regionObject, Set.of(new UUID(0, 0)), Set.of(), Set.of(), false));
        assertEquals(Optional.empty(), backend.ownerReadRegion(WORLD, "home-1", new UUID(0, 0)));
        assertTrue(wg.calls.isEmpty(), "refused before WorldGuard is read");
    }

    @Test void ownerReadRegionRefusesBadIdentity() {
        ownedHome();
        assertEquals(Optional.empty(), backend.ownerReadRegion(null, "home-1", OWNER));
        assertEquals(Optional.empty(), backend.ownerReadRegion(new UUID(0, 99), "home-1", OWNER));
        assertEquals(Optional.empty(), backend.ownerReadRegion(WORLD, "HOME-1", OWNER));
        assertEquals(Optional.empty(), backend.ownerReadRegion(WORLD, "__global__", OWNER));
        assertEquals(Optional.empty(), backend.ownerReadRegion(WORLD, "", OWNER));
    }

    @Test void ownerReadRegionRefusesWhileDownOrUnreadable() {
        ownedHome();
        wg.up = false;
        assertEquals(Optional.empty(), backend.ownerReadRegion(WORLD, "home-1", OWNER));
        wg.up = true;
        wg.manager = null;
        assertEquals(Optional.empty(), backend.ownerReadRegion(WORLD, "home-1", OWNER));
        wg.manager = new Object();
        wg.managerThrows = new IllegalAccessException("closed");
        assertEquals(Optional.empty(), backend.ownerReadRegion(WORLD, "home-1", OWNER));
    }

    @Test void aTokenFromAnotherManagerOrRegionObjectIsANewIncarnation() {
        ownedHome();
        var first = backend.ownerReadRegion(WORLD, "home-1", OWNER).orElseThrow();
        wg.manager = new Object();
        assertFalse(first.sameIncarnation(backend.ownerReadRegion(WORLD, "home-1", OWNER).orElseThrow()));
        var token = new WorldGuardListedBackend.Token(WORLD, "home-1", OWNER, regionObject, wg.manager);
        assertFalse(token.sameIncarnation(new WorldGuardListedBackend.Token(WORLD, "home-1", new UUID(0, 65), regionObject, wg.manager)));
        assertFalse(token.sameIncarnation(new WorldGuardListedBackend.Token(WORLD, "home-2", OWNER, regionObject, wg.manager)));
        assertFalse(token.sameIncarnation(null));
    }

    @Test void ownerReadAtResolvesTheRegionThenTheOwner() {
        ownedHome();
        var token = backend.ownerReadAt(at(TOWN, 1, 64, 1), OWNER).orElseThrow();
        assertEquals("home-1", token.regionId());
        assertEquals(Optional.empty(), backend.ownerReadAt(at(TOWN, 1, 64, 1), new UUID(0, 66)));
        wg.ids = List.of();
        assertEquals(Optional.empty(), backend.ownerReadAt(at(TOWN, 1, 64, 1), OWNER));
    }

    @Test void observeTellsPresentMissingAndUnreadableApart() {
        ownedHome();
        assertEquals(Presence.PRESENT, backend.observe(WORLD, "home-1"));
        assertEquals(Presence.MISSING, backend.observe(WORLD, "home-2"));
        assertEquals(Presence.UNREADABLE, backend.observe(null, "home-1"));
        assertEquals(Presence.UNREADABLE, backend.observe(new UUID(0, 99), "home-1"));
        assertEquals(Presence.UNREADABLE, backend.observe(WORLD, null));
    }

    @Test void observeIsUnreadableWhenWorldGuardCannotBeRead() {
        ownedHome();
        wg.up = false;
        assertEquals(Presence.UNREADABLE, backend.observe(WORLD, "home-1"));
        wg.up = true;
        wg.manager = null;
        assertEquals(Presence.UNREADABLE, backend.observe(WORLD, "home-1"));
        wg.manager = new Object();
        wg.managerThrows = new IllegalStateException("gone");
        assertEquals(Presence.UNREADABLE, backend.observe(WORLD, "home-1"));
    }

    @Test void aRegionNeedsALiveIdentityObject() {
        assertThrows(NullPointerException.class, () -> data(null, Set.of(OWNER), Set.of(), Set.of(), false));
    }

    @Test void theSoleOwnerIsTheOnlyUuidWithNoNamesGroupsOrParent() {
        assertEquals(Optional.of(OWNER), data(regionObject, Set.of(OWNER), Set.of(), Set.of(), false).soleOwner());
        assertEquals(Optional.empty(), data(regionObject, Set.of(), Set.of(), Set.of(), false).soleOwner());
        assertEquals(Optional.empty(), data(regionObject, Set.of(OWNER, new UUID(0, 67)), Set.of(), Set.of(), false).soleOwner());
        assertEquals(Optional.empty(), data(regionObject, Set.of(OWNER), Set.of("steve"), Set.of(), false).soleOwner());
        assertEquals(Optional.empty(), data(regionObject, Set.of(OWNER), Set.of(), Set.of("builders"), false).soleOwner());
        assertEquals(Optional.empty(), data(regionObject, Set.of(OWNER), Set.of(), Set.of(), true).soleOwner());
    }

    @Test void anOwnerDomainOfTheWrongShapeHasNoOwner() {
        assertEquals(Optional.empty(), data(regionObject, List.of(OWNER), Set.of(), Set.of(), false).soleOwner());
        assertEquals(Optional.empty(), data(regionObject, Set.of(OWNER.toString()), Set.of(), Set.of(), false).soleOwner());
        assertEquals(Optional.empty(), data(regionObject, Set.of(OWNER), null, Set.of(), false).soleOwner());
        assertEquals(Optional.empty(), data(regionObject, Set.of(OWNER), Set.of(), null, false).soleOwner());
        assertEquals(Optional.empty(), data(regionObject, null, Set.of(), Set.of(), false).soleOwner());
    }
}

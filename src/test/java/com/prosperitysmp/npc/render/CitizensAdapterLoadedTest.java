package com.prosperitysmp.npc.render;

import com.prosperitysmp.npc.render.RenderPolicy.Vec;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The readiness check before an avatar is placed or walked: world, bounds and the four surrounding chunks. */
class CitizensAdapterLoadedTest {
    private static final UUID WORLD_ID = new UUID(0, 99);
    private Field serverField;
    private Object previousServer;
    private boolean mainThread;
    private World registered;
    private final Set<Long> unloaded = new HashSet<>();
    private World world;

    private static long chunk(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

    private World fakeWorld(UUID id, int min, int max) {
        return (World) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {World.class}, (proxy, method, args) -> switch (method.getName()) {
            case "getUID" -> id;
            case "getMinHeight" -> min;
            case "getMaxHeight" -> max;
            case "isChunkLoaded" -> !unloaded.contains(chunk((Integer) args[0], (Integer) args[1]));
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "fake world";
            default -> throw new AssertionError("Unexpected World call: " + method.getName());
        });
    }

    @BeforeEach void install() throws Exception {
        serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        previousServer = serverField.get(null);
        world = fakeWorld(WORLD_ID, -64, 320);
        registered = world;
        mainThread = true;
        serverField.set(null, Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Server.class}, (proxy, method, args) -> switch (method.getName()) {
            case "isPrimaryThread" -> mainThread;
            case "getWorld" -> args[0] instanceof UUID id && id.equals(WORLD_ID) ? registered : null;
            case "hashCode" -> 1;
            case "equals" -> proxy == args[0];
            default -> throw new AssertionError("Unexpected Server call: " + method.getName());
        }));
    }

    @AfterEach void restore() throws Exception {
        serverField.set(null, previousServer);
    }

    private Location at(double x, double y, double z) { return new Location(world, x, y, z); }

    @Test void aLocationInALoadedWorldAndChunkIsLoaded() {
        assertTrue(CitizensAdapter.loaded(at(8.5, 64, 8.5)));
    }

    @Test void noLocationAndAWorldlessLocationAreNotLoaded() {
        assertFalse(CitizensAdapter.loaded(null));
        assertFalse(CitizensAdapter.loaded(new Location(null, 0.5, 64, 0.5)));
    }

    @Test void offTheMainThreadItRefusesToAnswerAtAll() {
        mainThread = false;
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> CitizensAdapter.loaded(at(8.5, 64, 8.5)));
        assertEquals("NPC rendering requires the server main thread", failure.getMessage());
        assertThrows(IllegalStateException.class, () -> CitizensAdapter.loaded(null));
    }

    @ParameterizedTest
    @CsvSource({"NaN,64,0", "0,NaN,0", "0,64,NaN", "Infinity,64,0", "0,Infinity,0", "0,64,-Infinity"})
    void aCoordinateThatIsNotAFiniteNumberIsNotLoaded(double x, double y, double z) {
        assertFalse(CitizensAdapter.loaded(at(x, y, z)));
    }

    @Test void theWorldBorderOfThirtyMillionBlocksIsInclusiveOnEachAxis() {
        assertTrue(CitizensAdapter.loaded(at(30_000_000, 64, 0.5)));
        assertTrue(CitizensAdapter.loaded(at(-30_000_000, 64, 0.5)));
        assertTrue(CitizensAdapter.loaded(at(0.5, 64, 30_000_000)));
        assertFalse(CitizensAdapter.loaded(at(30_000_001, 64, 0.5)));
        assertFalse(CitizensAdapter.loaded(at(-30_000_001, 64, 0.5)));
        assertFalse(CitizensAdapter.loaded(at(0.5, 64, 30_000_001)));
        assertFalse(CitizensAdapter.loaded(at(0.5, 64, -30_000_001)));
    }

    @Test void theHeightMustBeAboveTheFloorAndLeaveTwoBlocksUnderTheCeiling() {
        assertFalse(CitizensAdapter.loaded(at(8.5, -64, 8.5)));
        assertTrue(CitizensAdapter.loaded(at(8.5, -63.5, 8.5)));
        assertTrue(CitizensAdapter.loaded(at(8.5, 317, 8.5)));
        assertFalse(CitizensAdapter.loaded(at(8.5, 318, 8.5)));
        assertFalse(CitizensAdapter.loaded(at(8.5, 400, 8.5)));
        assertFalse(CitizensAdapter.loaded(at(8.5, -100, 8.5)));
    }

    @Test void aWorldTheServerNoLongerKnowsIsNotLoaded() {
        registered = null;
        assertFalse(CitizensAdapter.loaded(at(8.5, 64, 8.5)));
    }

    @Test void aDifferentWorldObjectWithTheSameIdIsNotTheLoadedWorld() {
        registered = fakeWorld(WORLD_ID, -64, 320);
        assertFalse(CitizensAdapter.loaded(at(8.5, 64, 8.5)));
    }

    @ParameterizedTest @CsvSource({"0,0", "0,1", "1,0", "1,1"})
    void eachOfTheFourCornerChunksAroundTheAvatarMustBeLoaded(int chunkX, int chunkZ) {
        Location location = at(16.5, 64, 16.5);
        assertTrue(CitizensAdapter.loaded(location));
        unloaded.add(chunk(chunkX, chunkZ));
        assertFalse(CitizensAdapter.loaded(location));
    }

    @Test void aChunkBeyondTheCornersDoesNotMatter() {
        unloaded.add(chunk(2, 2));
        unloaded.add(chunk(-1, 1));
        unloaded.add(chunk(1, 2));
        assertTrue(CitizensAdapter.loaded(at(16.5, 64, 16.5)));
    }

    @Test void insideOneChunkOnlyThatChunkIsNeeded() {
        unloaded.add(chunk(1, 0));
        unloaded.add(chunk(0, 1));
        assertTrue(CitizensAdapter.loaded(at(8.5, 64, 8.5)));
        unloaded.add(chunk(0, 0));
        assertFalse(CitizensAdapter.loaded(at(8.5, 64, 8.5)));
    }

    @Test void negativeCoordinatesLookAtTheChunksOnEachSideOfTheOrigin() {
        Location location = at(-0.5, 64, -0.5);
        assertTrue(CitizensAdapter.loaded(location));
        for (int cx : new int[] {-1, 0}) {
            for (int cz : new int[] {-1, 0}) {
                unloaded.clear();
                unloaded.add(chunk(cx, cz));
                assertFalse(CitizensAdapter.loaded(location), cx + "," + cz);
            }
        }
        unloaded.clear();
        unloaded.add(chunk(-2, -2));
        assertTrue(CitizensAdapter.loaded(location));
    }

    @Test void aBlockOnAChunkEdgeNeedsTheNeighbourToo() {
        Location edge = at(15.5, 64, 8.5);
        assertTrue(CitizensAdapter.loaded(edge));
        unloaded.add(chunk(1, 0));
        assertFalse(CitizensAdapter.loaded(edge), "the block east of the edge is in the next chunk");
        unloaded.clear();
        Location west = at(16.5, 64, 8.5);
        unloaded.add(chunk(0, 0));
        assertFalse(CitizensAdapter.loaded(west), "the block west of the edge is in the previous chunk");
    }

    @Test void theVectorOfALocationIsItsThreeCoordinatesAsGiven() {
        assertEquals(new Vec(1.5, 64.25, -3.75), CitizensAdapter.vec(new Location(null, 1.5, 64.25, -3.75)));
        assertEquals(new Vec(0.0, 0.0, 0.0), CitizensAdapter.vec(new Location(null, 0, 0, 0)));
        assertEquals(new Vec(-0.5, -64, 1e7), CitizensAdapter.vec(at(-0.5, -64, 1e7)));
    }

    @Test void theMainThreadGuardNamesItsMessage() {
        mainThread = false;
        assertEquals("NPC rendering requires the server main thread",
                assertThrows(IllegalStateException.class, CitizensAdapter::requireMainThread).getMessage());
        mainThread = true;
        assertDoesNotThrow(CitizensAdapter::requireMainThread);
    }
}

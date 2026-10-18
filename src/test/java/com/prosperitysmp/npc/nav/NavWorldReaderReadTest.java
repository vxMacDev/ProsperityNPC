package com.prosperitysmp.npc.nav;

import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The cases of a sweep's cell read that need no block: a chunk that is not loaded, a height outside the world,
 * and the chunk a block belongs to. (Reading a real block asks the server what its material is.)
 */
class NavWorldReaderReadTest {

    private static World world(boolean loaded, int minHeight, int maxHeight, List<String> log, AtomicInteger blockReads) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "isChunkLoaded":
                    log.add("chunk " + args[0] + "," + args[1]);
                    return loaded;
                case "getMinHeight":
                    return minHeight;
                case "getMaxHeight":
                    return maxHeight;
                case "getBlockAt":
                    blockReads.incrementAndGet();
                    throw new AssertionError("no block may be read");
                default:
                    throw new UnsupportedOperationException(method.getName());
            }
        });
    }

    @Test
    void anUnloadedChunkIsReportedAsNotLoadedAndNoBlockIsRead() {
        AtomicInteger reads = new AtomicInteger();
        NavWorldReader reader = NavWorldReader.anySolid(world(false, -64, 320, new ArrayList<>(), reads));
        assertEquals(NavSweep.NOT_LOADED, reader.read(5, 64, 5));
        assertEquals(0, reads.get());
    }

    @Test
    void anUnloadedChunkIsReportedEvenForAHeightOutsideTheWorld() {
        NavWorldReader reader = NavWorldReader.anySolid(world(false, -64, 320, new ArrayList<>(), new AtomicInteger()));
        assertEquals(NavSweep.NOT_LOADED, reader.read(5, 9999, 5));
        assertEquals(NavSweep.NOT_LOADED, reader.read(5, -9999, 5));
    }

    @ParameterizedTest
    @CsvSource({"0,0,0,0", "15,15,0,0", "16,16,1,1", "-1,-1,-1,-1", "-16,-17,-1,-2", "31,-32,1,-2", "-1000,1000,-63,62"})
    void theChunkAskedAboutIsTheBlocksXAndZShiftedRightByFour(int x, int z, int chunkX, int chunkZ) {
        List<String> asked = new ArrayList<>();
        NavWorldReader.anySolid(world(false, -64, 320, asked, new AtomicInteger())).read(x, 64, z);
        assertEquals(List.of("chunk " + chunkX + "," + chunkZ), asked);
    }

    @ParameterizedTest
    @CsvSource({"-65", "-1000", "320", "321", "100000", "2147483647", "-2147483648"})
    void aHeightOutsideTheWorldReadsAsClearWithoutTouchingABlock(int y) {
        AtomicInteger reads = new AtomicInteger();
        NavWorldReader reader = NavWorldReader.anySolid(world(true, -64, 320, new ArrayList<>(), reads));
        assertEquals(NavTerrainCapture.CLEAR, reader.read(0, y, 0));
        assertEquals(0, reads.get());
    }

    @ParameterizedTest
    @CsvSource({"-1,0,256", "256,0,256", "0,64,256", "63,64,256", "40,64,128", "128,64,128"})
    void theWorldsOwnHeightLimitsDecideWhatIsOutside(int y, int minHeight, int maxHeight) {
        AtomicInteger reads = new AtomicInteger();
        NavWorldReader reader = NavWorldReader.anySolid(world(true, minHeight, maxHeight, new ArrayList<>(), reads));
        assertEquals(NavTerrainCapture.CLEAR, reader.read(0, y, 0));
        assertEquals(0, reads.get());
    }

    @Test
    void theLoadedCheckComesBeforeTheHeightCheck() {
        List<String> asked = new ArrayList<>();
        NavWorldReader.anySolid(world(true, -64, 320, asked, new AtomicInteger())).read(3, 5000, 3);
        assertEquals(List.of("chunk 0,0"), asked);
    }

    @Test
    void theSolidFlagIsTheOneBitTheTerrainCaptureLeavesFree() {
        assertEquals(0, NavSweep.SOLID & (NavTerrainCapture.SUPPORT | NavTerrainCapture.CLEAR | NavTerrainCapture.SURFACE_MASK));
        assertEquals(0x20, NavSweep.SOLID);
        assertEquals((byte) 0x80, NavSweep.NOT_LOADED);
    }
}

package com.prosperitysmp.npc.nav;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The chunk keys the loader packs and the chunks a block rectangle touches. */
class NavChunkKeysTest {

    @ParameterizedTest
    @CsvSource({"0,0", "1,-1", "-1,1", "-1,-1", "2097151,-2097152", "1875000,1875000", "-1875000,-1875000", "2147483647,-2147483648",
            "-2147483648,2147483647", "2147483647,2147483647", "-2147483648,-2147483648"})
    void aKeyUnpacksToTheChunkItWasMadeFrom(int chunkX, int chunkZ) {
        long key = NavChunkLoader.key(chunkX, chunkZ);
        assertEquals(chunkX, NavChunkLoader.chunkX(key));
        assertEquals(chunkZ, NavChunkLoader.chunkZ(key));
    }

    @Test
    void theKeyPutsXInTheHighHalfAndZInTheLowHalf() {
        assertEquals(1L << 32, NavChunkLoader.key(1, 0));
        assertEquals(1L, NavChunkLoader.key(0, 1));
        assertEquals(0xFFFFFFFFL, NavChunkLoader.key(0, -1));
        assertEquals(-1L, NavChunkLoader.key(-1, -1));
        assertEquals(0L, NavChunkLoader.key(0, 0));
    }

    @Test
    void aNegativeZDoesNotLeakIntoTheXHalf() {
        assertEquals(0, NavChunkLoader.chunkX(NavChunkLoader.key(0, -5)));
        assertEquals(-5, NavChunkLoader.chunkZ(NavChunkLoader.key(0, -5)));
    }

    @Test
    void swappedCoordinatesMakeDifferentKeys() {
        assertEquals(false, NavChunkLoader.key(3, 9) == NavChunkLoader.key(9, 3));
    }

    @Test
    void aSingleBlockTouchesOneChunk() {
        assertEquals(List.of(NavChunkLoader.key(0, 0)), NavChunkLoader.chunksOf(5, 5, 5, 5));
        assertEquals(List.of(NavChunkLoader.key(-1, -1)), NavChunkLoader.chunksOf(-1, -1, -1, -1));
    }

    @Test
    void chunkBoundariesFallBetweenBlocks15And16AndBetweenMinus1And0() {
        assertEquals(List.of(NavChunkLoader.key(0, 0)), NavChunkLoader.chunksOf(0, 15, 0, 15));
        assertEquals(List.of(NavChunkLoader.key(0, 0), NavChunkLoader.key(0, 1)), NavChunkLoader.chunksOf(0, 15, 0, 16));
        assertEquals(List.of(NavChunkLoader.key(-1, 0), NavChunkLoader.key(0, 0)), NavChunkLoader.chunksOf(-1, 0, 0, 0));
        assertEquals(List.of(NavChunkLoader.key(-1, -1)), NavChunkLoader.chunksOf(-16, -1, -16, -1));
        assertEquals(List.of(NavChunkLoader.key(-2, -1)), NavChunkLoader.chunksOf(-17, -17, -1, -1));
    }

    @Test
    void chunksAreListedXOuterThenZInner() {
        assertEquals(List.of(NavChunkLoader.key(0, 0), NavChunkLoader.key(0, 1), NavChunkLoader.key(1, 0), NavChunkLoader.key(1, 1)),
                NavChunkLoader.chunksOf(0, 31, 0, 31));
    }

    @Test
    void aWideBoxListsEveryChunkOnceAndTheCountIsTheProductOfTheSpans() {
        List<Long> keys = NavChunkLoader.chunksOf(-40, 70, -5, 100);
        assertEquals(8 * 8, keys.size());
        Set<Long> distinct = new HashSet<>(keys);
        assertEquals(keys.size(), distinct.size());
        assertEquals(true, distinct.contains(NavChunkLoader.key(-3, -1)));
        assertEquals(true, distinct.contains(NavChunkLoader.key(4, 6)));
        assertEquals(false, distinct.contains(NavChunkLoader.key(5, 6)));
    }

    @Test
    void anInvertedRectangleTouchesNothing() {
        assertEquals(List.of(), NavChunkLoader.chunksOf(32, 0, 0, 0));
        assertEquals(List.of(), NavChunkLoader.chunksOf(0, 0, 32, 0));
    }
}

package com.prosperitysmp.npc.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapAllocatorTest {

    private static long sum(long[] a) {
        long s = 0;
        for (long x : a) {
            s += x;
        }
        return s;
    }

    @Test
    void underCap_returnsRequestedUnchanged() {
        long[] req = {100, 200, 300};
        assertArrayEquals(req, CapAllocator.allocate(req, 1000));
    }

    @Test
    void overCap_sumsExactlyToCap() {
        long[] req = {100, 200, 300}; // total 600
        long[] out = CapAllocator.allocate(req, 300);
        assertEquals(300, sum(out));
        // proportional: ~50/100/150
        assertArrayEquals(new long[]{50, 100, 150}, out);
    }

    @Test
    void leftoverCentsDistributedByLargestRemainder() {
        // three equal requests, cap 100 -> 33.33 each; leftover 1 cent to first (tie -> lowest index).
        long[] out = CapAllocator.allocate(new long[]{100, 100, 100}, 100);
        assertEquals(100, sum(out));
        assertArrayEquals(new long[]{34, 33, 33}, out);
    }

    @Test
    void neverExceedsRequestPerEntryWhenScaling() {
        long[] req = {1, 1, 1000};
        long[] out = CapAllocator.allocate(req, 500);
        assertEquals(500, sum(out));
        for (int i = 0; i < req.length; i++) {
            assertTrue(out[i] <= req[i], "entry " + i + " exceeded its request");
        }
    }

    @Test
    void emptyAndZero() {
        assertEquals(0, CapAllocator.allocate(new long[]{}, 100).length);
        assertArrayEquals(new long[]{0, 0}, CapAllocator.allocate(new long[]{0, 0}, 100));
    }

    @Test
    void capZero_allZero() {
        long[] out = CapAllocator.allocate(new long[]{100, 200}, 0);
        assertEquals(0, sum(out));
    }
}

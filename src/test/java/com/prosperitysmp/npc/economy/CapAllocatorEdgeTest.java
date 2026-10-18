package com.prosperitysmp.npc.economy;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** The pro-rata allocator at its edges: negative inputs, ties, order and very large requests. */
class CapAllocatorEdgeTest {

    private static long sum(long[] values) {
        long total = 0;
        for (long value : values) total += value;
        return total;
    }

    @Test
    void negativeRequestsCountAsZeroAndAreReturnedAsZero() {
        assertArrayEquals(new long[]{0, 10, 0}, CapAllocator.allocate(new long[]{-5, 10, Long.MIN_VALUE}, 100));
    }

    @Test
    void negativeRequestsGetNothingEvenWhenTheCapBinds() {
        assertArrayEquals(new long[]{0, 50, 50}, CapAllocator.allocate(new long[]{-100, 100, 100}, 100));
    }

    @Test
    void aNegativeCapIsTreatedAsZero() {
        assertArrayEquals(new long[]{0, 0}, CapAllocator.allocate(new long[]{10, 20}, -5));
        assertArrayEquals(new long[]{0, 0}, CapAllocator.allocate(new long[]{0, 0}, Long.MIN_VALUE));
    }

    @Test
    void aTotalExactlyAtTheCapIsPaidInFull() {
        assertArrayEquals(new long[]{40, 60}, CapAllocator.allocate(new long[]{40, 60}, 100));
    }

    @Test
    void oneCentOverTheCapScalesEveryRequest() {
        assertArrayEquals(new long[]{40, 60}, CapAllocator.allocate(new long[]{40, 61}, 100));
        assertArrayEquals(new long[]{99}, CapAllocator.allocate(new long[]{100}, 99));
    }

    @Test
    void leftoverCentsGoToTheLargestRemainderNotTheLowestIndex() {
        // 7, 11, 13 of a 10 cent cap: 2.26, 3.55, 4.19 -> the one leftover cent goes to 11.
        assertArrayEquals(new long[]{2, 4, 4}, CapAllocator.allocate(new long[]{7, 11, 13}, 10));
    }

    @Test
    void distinctRemaindersGiveEachRequestTheSameShareInAnyOrder() {
        assertArrayEquals(new long[]{4, 2, 4}, CapAllocator.allocate(new long[]{13, 7, 11}, 10));
        assertArrayEquals(new long[]{4, 4, 2}, CapAllocator.allocate(new long[]{11, 13, 7}, 10));
    }

    @Test
    void tiedRemaindersFavourTheLowerIndex() {
        assertArrayEquals(new long[]{1, 1, 0}, CapAllocator.allocate(new long[]{5, 5, 5}, 2));
        assertArrayEquals(new long[]{1, 1, 1, 0}, CapAllocator.allocate(new long[]{1, 1, 1, 1}, 3));
    }

    @Test
    void theInputArrayIsNotChanged() {
        long[] requested = {300, 200, 100};
        CapAllocator.allocate(requested, 60);
        assertArrayEquals(new long[]{300, 200, 100}, requested);
    }

    @Test
    void seededRandomPoolsAlwaysSumToTheSmallerOfTotalAndCapAndNeverExceedARequest() {
        Random random = new Random(20261018L);
        for (int round = 0; round < 500; round++) {
            int n = 1 + random.nextInt(12);
            long[] requested = new long[n];
            long total = 0;
            for (int i = 0; i < n; i++) {
                requested[i] = random.nextInt(20_000) - 2_000;
                total += Math.max(0, requested[i]);
            }
            long cap = random.nextInt(60_000) - 1_000;
            long[] out = CapAllocator.allocate(requested, cap);
            assertEquals(Math.min(total, Math.max(0, cap)), sum(out), "round " + round);
            for (int i = 0; i < n; i++) {
                assertTrue(out[i] >= 0, "round " + round);
                assertTrue(out[i] <= Math.max(0, requested[i]), "round " + round);
                if (total > Math.max(0, cap) && total > 0)
                    assertTrue(out[i] >= Math.max(0, requested[i]) * Math.max(0, cap) / total, "round " + round);
            }
        }
    }

    @Test
    void aProductTooLargeForALongFailsInsteadOfWrapping() {
        long half = Long.MAX_VALUE / 2;
        assertThrows(ArithmeticException.class, () -> CapAllocator.allocate(new long[]{half, half}, 3));
    }

    @Test
    void requestsWhoseTotalOverflowsNeverAllocateMoreThanTheCap() {
        long[] out = CapAllocator.allocate(new long[]{Long.MAX_VALUE, Long.MAX_VALUE}, 100);
        BigInteger total = BigInteger.valueOf(out[0]).add(BigInteger.valueOf(out[1]));
        assertEquals(BigInteger.valueOf(100), total, "allocated " + out[0] + " and " + out[1] + " under a cap of 100");
    }
}

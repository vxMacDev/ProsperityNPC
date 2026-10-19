package com.prosperitysmp.npc.nav;

import com.prosperitysmp.npc.nav.NavScanLogic.Bounds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The terrain snapshot's size, slice budgets, read order and the checks that guard what it hands to a scan. */
class NavTerrainCaptureLimitsTest {
    // 3 columns along x, 2 along z, y 64..65, headroom 1: height 3 (64..66), 6 columns, 18 cells.
    private static final Bounds BOX = new Bounds(10, 64, 12, 65, 20, 21);

    private static NavTerrainCapture capture() {
        return new NavTerrainCapture(BOX, 1, 1);
    }

    private static final NavTerrainCapture.Reader AIR = (x, y, z) -> NavTerrainCapture.CLEAR;

    @Test
    void theTotalIsColumnsTimesTheHeightIncludingTheHeadroomRows() {
        NavTerrainCapture capture = capture();
        assertEquals(18, capture.total());
        assertEquals(0, capture.captured());
        assertFalse(capture.complete());
    }

    @Test
    void headroomAddsRowsAboveTheBoxTop() {
        assertEquals(6 * 5, new NavTerrainCapture(BOX, 3, 1).total());
        assertEquals(6 * 3, new NavTerrainCapture(BOX, 1, 0).total());
    }

    @Test
    void aBoxTheScanWouldRefuseIsRefusedHereToo() {
        assertThrows(IllegalArgumentException.class, () -> new NavTerrainCapture(new Bounds(5, 0, 4, 0, 0, 0), 2, 1));
        assertThrows(IllegalArgumentException.class, () -> new NavTerrainCapture(BOX, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new NavTerrainCapture(BOX, 2, 2));
    }

    @Test
    void cellsAreReadAlongYFirstThenZThenXStartingAtTheBoxCorner() {
        List<String> order = new ArrayList<>();
        NavTerrainCapture capture = capture();
        capture.advance((x, y, z) -> {
            order.add(x + "," + y + "," + z);
            return NavTerrainCapture.CLEAR;
        }, 4096, Long.MAX_VALUE, () -> 0L);
        assertEquals(18, order.size());
        assertEquals(List.of("10,64,20", "10,65,20", "10,66,20", "10,64,21", "10,65,21", "10,66,21", "11,64,20"), order.subList(0, 7));
        assertEquals("12,66,21", order.get(17));
    }

    @Test
    void aSliceReadsNoMoreThanItsReadBudgetAndTheNextSliceContinuesWhereItStopped() {
        NavTerrainCapture capture = capture();
        AtomicLong reads = new AtomicLong();
        NavTerrainCapture.Reader counting = (x, y, z) -> {
            reads.incrementAndGet();
            return NavTerrainCapture.CLEAR;
        };
        assertFalse(capture.advance(counting, 5, Long.MAX_VALUE, () -> 0L));
        assertEquals(5, capture.captured());
        assertEquals(5, reads.get());
        assertFalse(capture.advance(counting, 5, Long.MAX_VALUE, () -> 0L));
        assertEquals(10, capture.captured());
        assertTrue(capture.advance(counting, 100, Long.MAX_VALUE, () -> 0L));
        assertEquals(18, capture.captured());
        assertEquals(18, reads.get());
        assertTrue(capture.complete());
    }

    @Test
    void aCompleteCaptureReadsNothingMore() {
        NavTerrainCapture capture = capture();
        assertTrue(capture.advance(AIR, 4096, Long.MAX_VALUE, () -> 0L));
        AtomicLong reads = new AtomicLong();
        assertTrue(capture.advance((x, y, z) -> {
            reads.incrementAndGet();
            return NavTerrainCapture.CLEAR;
        }, 10, Long.MAX_VALUE, () -> 0L));
        assertEquals(0, reads.get());
    }

    @Test
    void theClockStopsASliceOnceItsTimeBudgetIsSpentButNeverBeforeTheFirstRead() {
        NavTerrainCapture capture = capture();
        AtomicLong now = new AtomicLong();
        // Each read advances the clock by 10; a budget of 25 allows reads while elapsed < 25.
        NavTerrainCapture.Reader slow = (x, y, z) -> {
            now.addAndGet(10);
            return NavTerrainCapture.CLEAR;
        };
        capture.advance(slow, 4096, 25, now::get);
        assertEquals(3, capture.captured());
    }

    @Test
    void aSliceWhoseTimeBudgetIsSpentBeforeItsFirstReadReadsNothing() {
        NavTerrainCapture capture = capture();
        AtomicLong now = new AtomicLong(1000);
        capture.advance(AIR, 4096, 1, () -> now.getAndAdd(5));
        assertEquals(0, capture.captured());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 4097, 100_000})
    void aReadBudgetOutsideOneTo4096IsRefused(int budget) {
        assertThrows(IllegalArgumentException.class, () -> capture().advance(AIR, budget, 1000, () -> 0L));
    }

    @ParameterizedTest
    @CsvSource({"1, 1, false", "4096, 18, true"})
    void theEdgesOfTheReadBudgetRangeAreAccepted(int budget, int captured, boolean complete) {
        NavTerrainCapture capture = capture();
        assertEquals(complete, capture.advance(AIR, budget, 1000, () -> 0L));
        assertEquals(captured, capture.captured());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void aTimeBudgetBelowOneNanosecondIsRefused(long nanos) {
        assertThrows(IllegalArgumentException.class, () -> capture().advance(AIR, 10, nanos, () -> 0L));
    }

    @Test
    void aMissingReaderOrClockIsRefused() {
        assertThrows(NullPointerException.class, () -> capture().advance(null, 10, 1000, () -> 0L));
        assertThrows(NullPointerException.class, () -> capture().advance(AIR, 10, 1000, null));
    }

    @Test
    void nothingCanBeAskedOfACaptureThatIsNotComplete() {
        NavTerrainCapture capture = capture();
        capture.advance(AIR, 5, Long.MAX_VALUE, () -> 0L);
        assertThrows(IllegalStateException.class, () -> capture.isSupport(10, 64, 20));
        assertThrows(IllegalStateException.class, () -> capture.isClear(10, 64, 20));
        assertThrows(IllegalStateException.class, () -> capture.surface(10, 64, 20));
    }

    @Test
    void aCompleteCaptureAnswersFromWhatTheReaderReturnedForEachCell() {
        NavTerrainCapture capture = capture();
        capture.advance((x, y, z) -> {
            if (x == 11 && z == 20 && y == 64) return (byte) (NavTerrainCapture.SUPPORT | NavSurface.ROAD.code() << NavTerrainCapture.SURFACE_SHIFT);
            if (x == 11 && z == 20 && y == 65) return NavTerrainCapture.CLEAR;
            return 0;
        }, 4096, Long.MAX_VALUE, () -> 0L);
        assertTrue(capture.isSupport(11, 64, 20));
        assertFalse(capture.isClear(11, 64, 20));
        assertEquals(NavSurface.ROAD, capture.surface(11, 64, 20));
        assertFalse(capture.isSupport(11, 65, 20));
        assertTrue(capture.isClear(11, 65, 20));
        assertEquals(NavSurface.UNKNOWN, capture.surface(11, 65, 20));
        assertFalse(capture.isSupport(12, 64, 21));
        assertFalse(capture.isClear(12, 64, 21));
    }

    @Test
    void theHeadroomRowsAboveTheBoxAreAnswerableButOneBeyondIsNot() {
        NavTerrainCapture capture = capture();
        capture.advance(AIR, 4096, Long.MAX_VALUE, () -> 0L);
        assertTrue(capture.isClear(10, 66, 20), "the headroom row above the box top");
        assertThrows(IllegalArgumentException.class, () -> capture.isClear(10, 67, 20));
        assertThrows(IllegalArgumentException.class, () -> capture.isClear(10, 63, 20));
    }

    @Test
    void cellsOutsideTheBoxOnEitherHorizontalAxisAreRefused() {
        NavTerrainCapture capture = capture();
        capture.advance(AIR, 4096, Long.MAX_VALUE, () -> 0L);
        assertThrows(IllegalArgumentException.class, () -> capture.isSupport(9, 64, 20));
        assertThrows(IllegalArgumentException.class, () -> capture.isSupport(13, 64, 20));
        assertThrows(IllegalArgumentException.class, () -> capture.isSupport(10, 64, 19));
        assertThrows(IllegalArgumentException.class, () -> capture.isSupport(10, 64, 22));
        assertFalse(capture.isSupport(12, 64, 21), "the far corner is inside");
    }

    @ParameterizedTest
    @ValueSource(ints = {4, 8, 16, 32, 64, 128})
    void aReaderValueWithASurfaceButNoSupportOrABitOutsideTheLayoutIsRefused(int value) {
        assertThrows(IllegalArgumentException.class, () -> capture().advance((x, y, z) -> (byte) value, 5, Long.MAX_VALUE, () -> 0L));
    }

    @Test
    void theFlagLayoutIsTheDocumentedOne() {
        assertEquals(1, NavTerrainCapture.SUPPORT);
        assertEquals(2, NavTerrainCapture.CLEAR);
        assertEquals(2, NavTerrainCapture.SURFACE_SHIFT);
        assertEquals(0b11100, NavTerrainCapture.SURFACE_MASK);
    }

    @Test
    void aFailedReadLeavesTheCaptureWhereItWasSoTheNextSliceRetriesThatCell() {
        NavTerrainCapture capture = capture();
        AtomicLong calls = new AtomicLong();
        NavTerrainCapture.Reader failing = (x, y, z) -> {
            if (calls.incrementAndGet() == 3) throw new IllegalStateException("chunk unloaded");
            return NavTerrainCapture.CLEAR;
        };
        assertThrows(IllegalStateException.class, () -> capture.advance(failing, 100, Long.MAX_VALUE, () -> 0L));
        assertEquals(2, capture.captured());
        assertTrue(capture.advance(AIR, 100, Long.MAX_VALUE, () -> 0L));
        assertEquals(18, capture.captured());
    }
}

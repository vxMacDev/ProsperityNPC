package com.prosperitysmp.npc.nav;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class NavTerrainCaptureTest {
    private static final NavScanLogic.Bounds BOX = new NavScanLogic.Bounds(-2, 60, 2, 65, -2, 2);

    @Test
    void slicesRespectReadBudgetAndCompletedTerrainMatchesDirectScan() {
        var captured = new NavTerrainCapture(BOX, 2, 1);
        var reads = new AtomicInteger();
        NavTerrainCapture.Reader reader = (x, y, z) -> {
            reads.incrementAndGet();
            return y == 64 ? NavTerrainCapture.SUPPORT : NavTerrainCapture.CLEAR;
        };
        assertThrows(IllegalStateException.class, () -> captured.isSupport(0, 64, 0));
        while (!captured.complete()) {
            int before = reads.get();
            captured.advance(reader, 7, 100, () -> 0L);
            assertTrue(reads.get() - before <= 7);
        }
        assertEquals(captured.total(), reads.get());
        var graph = NavScanLogic.scan(BOX, captured, 2, 1);
        var direct = NavScanLogic.scan(BOX, new NavScanLogic.Terrain() {
            public boolean isSupport(int x, int y, int z) { return y == 64; }
            public boolean isClear(int x, int y, int z) { return y != 64; }
        }, 2, 1);
        assertEquals(direct.nodeCount(), graph.nodeCount());
        assertEquals(direct.edgeCount(), graph.edgeCount());
        for (var node : direct.nodes()) {
            assertEquals(node, graph.node(node.id()));
            assertEquals(direct.neighbors(node.id()), graph.neighbors(node.id()));
        }
        assertTrue(captured.advance((x,y,z) -> { throw new AssertionError("completed capture read again"); }, 7, 100, () -> 0L));
    }

    @Test
    void timeBudgetStopsBetweenReadsAndAllowsResume() {
        var captured = new NavTerrainCapture(BOX, 2, 1);
        var clock = new AtomicLong();
        assertFalse(captured.advance((x,y,z) -> NavTerrainCapture.CLEAR, 512, 3, clock::getAndIncrement));
        assertEquals(2, captured.captured());
        assertFalse(captured.advance((x,y,z) -> NavTerrainCapture.CLEAR, 1, 3, clock::getAndIncrement));
        assertEquals(3, captured.captured());
    }

    @Test
    void failedReadNeverMarksMissingTerrainAsCaptured() {
        var captured = new NavTerrainCapture(BOX, 2, 1);
        assertThrows(IllegalStateException.class, () -> captured.advance((x,y,z) -> {
            throw new IllegalStateException("unloaded chunk");
        }, 512, 100, () -> 0L));
        assertEquals(0, captured.captured());
        assertFalse(captured.complete());
        assertThrows(IllegalStateException.class, () -> captured.isClear(0,64,0));
    }

    private static byte surfaced(NavSurface surface) {
        return (byte) (NavTerrainCapture.SUPPORT | surface.code() << NavTerrainCapture.SURFACE_SHIFT);
    }

    @Test
    void surfaceBitsRoundTripForEveryClass() {
        for (NavSurface surface : NavSurface.values()) {
            var captured = new NavTerrainCapture(BOX, 2, 1);
            captured.advance((x, y, z) -> y == 64 ? surfaced(surface) : NavTerrainCapture.CLEAR, 4096, Long.MAX_VALUE, () -> 0L);
            while (!captured.complete()) {
                captured.advance((x, y, z) -> y == 64 ? surfaced(surface) : NavTerrainCapture.CLEAR, 4096, Long.MAX_VALUE, () -> 0L);
            }
            assertEquals(surface, captured.surface(0, 64, 0));
            assertTrue(captured.isSupport(0, 64, 0));
            assertEquals(NavSurface.UNKNOWN, captured.surface(0, 63, 0), "a clear cell has no class");
        }
    }

    @Test
    void surfaceWithoutSupportAnUnknownCodeOrStrayBitsAreRejected() {
        int roadBits = NavSurface.ROAD.code() << NavTerrainCapture.SURFACE_SHIFT;
        for (int bad : new int[]{roadBits, NavTerrainCapture.CLEAR | roadBits,
                NavTerrainCapture.SUPPORT | 5 << NavTerrainCapture.SURFACE_SHIFT,
                NavTerrainCapture.SUPPORT | 7 << NavTerrainCapture.SURFACE_SHIFT,
                NavTerrainCapture.SUPPORT | 0b100000, 0b1000000, 0x80}) {
            var captured = new NavTerrainCapture(BOX, 2, 1);
            assertThrows(IllegalArgumentException.class,
                    () -> captured.advance((x, y, z) -> (byte) bad, 512, Long.MAX_VALUE, () -> 0L), "value " + bad);
            assertEquals(0, captured.captured(), "a rejected value is never kept");
        }
    }

    @Test
    void streetScanOverCapturedSurfacesBuildsTheSameCrossingsAsADirectScan() {
        NavTerrainCapture.Reader reader = (x, y, z) -> y != 64 ? NavTerrainCapture.CLEAR
                : surfaced(z == 0 ? NavSurface.ROAD : NavSurface.SIDEWALK);
        var captured = new NavTerrainCapture(BOX, 2, 1);
        while (!captured.complete()) captured.advance(reader, 4096, Long.MAX_VALUE, () -> 0L);
        var viaCapture = NavScanLogic.scan(BOX, captured, 2, 1, StreetRules.LEGACY);
        var direct = NavScanLogic.scan(BOX, new NavScanLogic.Terrain() {
            public boolean isSupport(int x, int y, int z) { return y == 64; }
            public boolean isClear(int x, int y, int z) { return y != 64; }
            public NavSurface surface(int x, int y, int z) { return z == 0 ? NavSurface.ROAD : NavSurface.SIDEWALK; }
        }, 2, 1, StreetRules.LEGACY);
        assertEquals(20, viaCapture.stats().nodes().get(NavSurface.SIDEWALK));
        assertEquals(5, viaCapture.stats().nodes().get(NavSurface.ROAD), "one crossing cell per column");
        assertEquals(direct.stats(), viaCapture.stats());
        assertEquals(direct.graph().edges(), viaCapture.graph().edges());
    }
}

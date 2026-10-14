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
}

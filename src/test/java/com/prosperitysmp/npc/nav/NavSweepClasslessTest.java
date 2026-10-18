package com.prosperitysmp.npc.nav;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The classless sweep a waypoint or file graph built without a palette is revalidated with. */
class NavSweepClasslessTest {

    /** Solid ground at y 63 under x 0..5, z 0..2 with no class at all (an "any solid block" reader). */
    private static final class Reader implements NavSweep.Reader {
        final Map<String, Integer> changed = new HashMap<>();

        @Override public byte read(int x, int y, int z) {
            Integer override = changed.get(x + "," + y + "," + z);
            if (override != null) return (byte) (int) override;
            boolean ground = x >= 0 && x <= 5 && z >= 0 && z <= 2;
            if (ground && y < 63) return NavSweep.SOLID;
            if (ground && y == 63) return (byte) (NavSweep.SOLID | NavTerrainCapture.SUPPORT);
            return NavTerrainCapture.CLEAR;
        }
    }

    private static NavGraph sidewalk() {
        NavGraph.Builder b = NavGraph.builder();
        for (int x = 0; x <= 5; x++) b.addNode(x + 1, x, 64, 1, NavSurface.SIDEWALK);
        for (int x = 0; x < 5; x++) b.addBiEdge(x + 1, x + 2, 10);
        return b.build();
    }

    private static NavSweep.Revalidation run(NavSweep sweep, Reader reader) {
        int rounds = 0;
        while (!sweep.step(reader, 512, Long.MAX_VALUE, () -> 0L)) {
            if (++rounds > 10_000) throw new AssertionError("the sweep must finish");
        }
        return sweep.revalidation("fp");
    }

    @Test
    void classlessModeSkipsTheClassCompare() {
        // With classes checked, a SIDEWALK node on a block of no class is CHANGED_CLASS; classless, it is fine.
        assertEquals(NavSweep.Verdict.DRIFTED, run(NavSweep.forRevalidation(sidewalk(), "fp", true), new Reader()).verdict());
        NavSweep.Revalidation classless = run(NavSweep.forRevalidation(sidewalk(), "fp", false), new Reader());
        assertEquals(NavSweep.Verdict.CLEAN, classless.verdict());
        assertEquals(6, classless.ok());
    }

    /** Mutant guard: a classless mode that also skipped the solid check would pass the test above. */
    @Test
    void classlessModeStillReportsUnwalkableAndBlocked() {
        Reader reader = new Reader();
        reader.changed.put("2,63,1", (int) NavTerrainCapture.CLEAR);
        reader.changed.put("4,65,1", 0);
        NavSweep.Revalidation r = run(NavSweep.forRevalidation(sidewalk(), "fp", false), reader);
        assertEquals(NavSweep.Verdict.DRIFTED, r.verdict());
        assertEquals(1, r.unwalkable());
        assertEquals(1, r.blocked());
        assertEquals(0, r.changedClass());
    }

    @Test
    void theOldFactoryStillChecksClasses() {
        assertEquals(NavSweep.Verdict.DRIFTED, run(NavSweep.forRevalidation(sidewalk(), "fp"), new Reader()).verdict());
    }
}

package com.prosperitysmp.npc.nav;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The sliced world read behind /npcnav revalidate and the live audit, driven by a fake world. */
class NavSweepTest {

    /** A reader over a drawn street, with cells that tests can change and chunks they can unload. */
    private static final class Reader implements NavSweep.Reader {
        final StreetWorld world;
        final Map<String, Byte> changed = new HashMap<>();
        final Set<String> unloaded = new HashSet<>();
        final AtomicInteger reads = new AtomicInteger();

        Reader(StreetWorld world) {
            this.world = world;
        }

        void set(int x, int y, int z, int value) {
            changed.put(x + "," + y + "," + z, (byte) value);
        }

        void unloadChunk(int cx, int cz) {
            unloaded.add(cx + "," + cz);
        }

        @Override public byte read(int x, int y, int z) {
            reads.incrementAndGet();
            if (unloaded.contains((x >> 4) + "," + (z >> 4))) return NavSweep.NOT_LOADED;
            Byte override = changed.get(x + "," + y + "," + z);
            if (override != null) return override;
            boolean ground = "SARWU".indexOf(world.at(x, z)) >= 0;
            int top = world.ground(x, z);
            if (ground && y < top) return NavSweep.SOLID;
            if (ground && y == top) {
                return (byte) (NavSweep.SOLID | NavTerrainCapture.SUPPORT
                        | world.surface(x, y, z).code() << NavTerrainCapture.SURFACE_SHIFT);
            }
            return NavTerrainCapture.CLEAR;
        }
    }

    private static final String HASH = "hash-now";

    private static void run(NavSweep sweep, NavSweep.Reader reader) {
        long clock = 0;
        while (!sweep.step(reader, 512, Long.MAX_VALUE, () -> 0L)) {
            clock++;
            assertTrue(clock < 100_000, "the sweep must finish");
        }
    }

    /** Reads a class sweep the way the command does: the node classes, then the width probes they call for. */
    private static void runClasses(NavSweep sweep, NavSweep.Reader reader, NavSweep.MaterialNamer namer) {
        long clock = 0;
        while (!sweep.step(reader, namer, 512, Long.MAX_VALUE, () -> 0L)) {
            clock++;
            assertTrue(clock < 100_000, "the sweep must finish");
        }
        assertTrue(sweep.needsWidthProbes());
        sweep.planWidthProbes(StreetWorld.RULES.maxCrossingLength());
        while (!sweep.step(reader, namer, 512, Long.MAX_VALUE, () -> 0L)) {
            clock++;
            assertTrue(clock < 100_000, "the sweep must finish");
        }
        assertFalse(sweep.needsWidthProbes());
    }

    private static void runClasses(NavSweep sweep, NavSweep.Reader reader) {
        runClasses(sweep, reader, null);
    }

    private static NavGraph tagged(StreetWorld world, int x, int z, String tag) {
        NavGraph graph = world.graph();
        return StreetWorld.tag(graph, Map.of(StreetWorld.node(graph, x, z).id(), tag));
    }

    private static StreetWorld street() {
        return StreetWorld.strip(5, 20).build();
    }

    private static int solidSupport(NavSurface surface) {
        return NavSweep.SOLID | NavTerrainCapture.SUPPORT | surface.code() << NavTerrainCapture.SURFACE_SHIFT;
    }

    @Test
    void anUnchangedWorldIsClean() {
        StreetWorld world = street();
        NavGraph graph = tagged(world, 3, 1, "home");
        NavSweep sweep = NavSweep.forRevalidation(graph, HASH);
        run(sweep, new Reader(world));
        var r = sweep.revalidation(HASH);
        assertEquals(NavSweep.Verdict.CLEAN, r.verdict(), () -> r.lines().toString());
        assertEquals(graph.nodeCount(), r.ok());
        assertEquals(0, r.notLoaded());
        assertEquals("same", r.palette());
        assertTrue(r.drifts().isEmpty());
        assertNull(r.driftBox());
    }

    @Test
    void aTaggedNodesSupportTurnedToRoadIsDriftListedFirst() {
        StreetWorld world = street();
        NavGraph graph = tagged(world, 3, 1, "home");
        Reader reader = new Reader(world);
        reader.set(9, 63, 1, solidSupport(NavSurface.ROAD)); // an untagged sidewalk node
        reader.set(3, 63, 1, solidSupport(NavSurface.ROAD)); // the tagged one
        NavSweep sweep = NavSweep.forRevalidation(graph, HASH);
        run(sweep, reader);
        var r = sweep.revalidation(HASH);
        assertEquals(NavSweep.Verdict.DRIFTED, r.verdict());
        assertEquals(2, r.changedClass());
        assertEquals("CHANGED_CLASS", r.drifts().getFirst().kind());
        assertTrue(r.drifts().getFirst().tagged());
        assertEquals(3, r.drifts().getFirst().x());
        assertTrue(r.lines().getFirst().contains("[tagged]") && r.lines().getFirst().contains("stored SIDEWALK now ROAD"));
        assertFalse(r.drifts().get(1).tagged());
        assertTrue(java.util.Arrays.equals(new int[]{3, 64, 1, 9, 64, 1}, r.driftBox()));
    }

    @Test
    void airUnderANodeAndABlockOverItAreCaught() {
        StreetWorld world = street();
        NavGraph graph = world.graph();
        Reader reader = new Reader(world);
        reader.set(5, 63, 1, NavTerrainCapture.CLEAR); // nothing underneath
        reader.set(12, 65, 1, NavSweep.SOLID);          // something built over the walkway
        NavSweep sweep = NavSweep.forRevalidation(graph, HASH);
        run(sweep, reader);
        var r = sweep.revalidation(HASH);
        assertEquals(NavSweep.Verdict.DRIFTED, r.verdict());
        assertEquals(1, r.unwalkable());
        assertEquals(1, r.blocked());
        assertTrue(r.drifts().stream().anyMatch(d -> d.kind().equals("UNWALKABLE") && d.x() == 5));
        assertTrue(r.drifts().stream().anyMatch(d -> d.kind().equals("BLOCKED") && d.x() == 12));
    }

    @Test
    void anUnclassifiedNodeOnlyNeedsItsBlockToStillBeInThePalette() {
        // A legacy node (class UNKNOWN) is fine on any palette block, and gone when the block leaves the palette.
        StreetWorld world = StreetWorld.strip(5, 20).build(false);
        NavGraph graph = world.graph();
        Reader reader = new Reader(world);
        NavSweep clean = NavSweep.forRevalidation(graph, null);
        run(clean, reader);
        assertEquals(NavSweep.Verdict.CLEAN, clean.revalidation(HASH).verdict());
        assertEquals("legacy", clean.revalidation(HASH).palette());
        reader.set(4, 63, 1, NavSweep.SOLID); // solid but no longer a palette block
        NavSweep gone = NavSweep.forRevalidation(graph, null);
        run(gone, reader);
        assertEquals(1, gone.revalidation(HASH).unwalkable());
        assertEquals(NavSweep.Verdict.DRIFTED, gone.revalidation(HASH).verdict());
    }

    @Test
    void aBlockedCornerColumnOfADiagonalEdgeIsDriftEvenWhenEveryNodeIsFine() {
        // Two nodes joined diagonally; the other two columns of the 2x2 footprint have no node of their own.
        NavGraph graph = NavGraph.builder().addNode(1, 0, 64, 0, NavSurface.SIDEWALK).addNode(2, 1, 64, 1, NavSurface.SIDEWALK)
                .addBiEdge(1, 2, 14).build();
        NavSweep.Reader open = (x, y, z) -> (byte) (y < 64 ? solidSupport(NavSurface.SIDEWALK) : NavTerrainCapture.CLEAR);
        NavSweep fine = NavSweep.forRevalidation(graph, HASH);
        run(fine, open);
        assertEquals(NavSweep.Verdict.CLEAN, fine.revalidation(HASH).verdict());

        NavSweep.Reader corner = (x, y, z) -> x == 1 && z == 0 && y == 64 ? NavSweep.SOLID : open.read(x, y, z);
        NavSweep blocked = NavSweep.forRevalidation(graph, HASH);
        run(blocked, corner);
        var r = blocked.revalidation(HASH);
        assertEquals(0, r.blocked(), "both nodes themselves are fine");
        assertEquals(1, r.footprint(), "the edge is counted once, not once per direction");
        assertEquals(NavSweep.Verdict.DRIFTED, r.verdict());
        assertEquals("FOOTPRINT", r.drifts().getFirst().kind());
    }

    @Test
    void anUnloadedWorldIsIncompleteNeverClean() {
        StreetWorld world = street();
        NavGraph graph = world.graph();
        Reader reader = new Reader(world);
        reader.unloadChunk(0, 0);
        reader.unloadChunk(1, 0);
        NavSweep sweep = NavSweep.forRevalidation(graph, HASH);
        run(sweep, reader);
        var r = sweep.revalidation(HASH);
        assertEquals(NavSweep.Verdict.INCOMPLETE, r.verdict());
        assertEquals(0, r.ok());
        assertTrue(r.notLoaded() >= graph.nodeCount());
    }

    @Test
    void oneUnloadedChunkWithADriftElsewhereIsDriftedNotIncomplete() {
        StreetWorld world = street();
        Reader reader = new Reader(world);
        reader.unloadChunk(1, 0);                 // x 16..19
        reader.set(2, 63, 1, NavTerrainCapture.CLEAR);
        NavSweep sweep = NavSweep.forRevalidation(world.graph(), HASH);
        run(sweep, reader);
        var r = sweep.revalidation(HASH);
        assertEquals(NavSweep.Verdict.DRIFTED, r.verdict());
        assertTrue(r.notLoaded() > 0);
        // Without the drift the same partial read is INCOMPLETE.
        Reader partial = new Reader(world);
        partial.unloadChunk(1, 0);
        NavSweep onlyUnloaded = NavSweep.forRevalidation(world.graph(), HASH);
        run(onlyUnloaded, partial);
        assertEquals(NavSweep.Verdict.INCOMPLETE, onlyUnloaded.revalidation(HASH).verdict());
    }

    @Test
    void aChangedPaletteHashIsDriftEvenWhenEveryBlockMatches() {
        StreetWorld world = street();
        NavSweep sweep = NavSweep.forRevalidation(world.graph(), "hash-then");
        run(sweep, new Reader(world));
        var r = sweep.revalidation(HASH);
        assertEquals("changed", r.palette());
        assertEquals(NavSweep.Verdict.DRIFTED, r.verdict());
        assertTrue(r.drifts().isEmpty());
        assertEquals(NavSweep.Verdict.CLEAN, sweep.revalidation("hash-then").verdict());
    }

    @Test
    void eachStepRespectsTheReadAndTimeBudgetsAndAFailedReadMarksNothing() {
        StreetWorld world = street();
        NavSweep sweep = NavSweep.forRevalidation(world.graph(), HASH);
        Reader reader = new Reader(world);
        int total = sweep.total();
        assertTrue(total > 500);
        assertFalse(sweep.step(reader, 7, Long.MAX_VALUE, () -> 0L));
        assertEquals(7, reader.reads.get());
        assertEquals(7, sweep.read());
        AtomicLong clock = new AtomicLong();
        assertFalse(sweep.step(reader, 512, 3, clock::getAndIncrement));
        assertEquals(9, sweep.read(), "the time budget stops it between reads");
        assertThrows(IllegalStateException.class, () -> sweep.revalidation(HASH), "a half-read sweep has no verdict");
        NavSweep failing = NavSweep.forRevalidation(world.graph(), HASH);
        assertThrows(IllegalStateException.class, () -> failing.step((x, y, z) -> {
            throw new IllegalStateException("chunk unloaded");
        }, 512, Long.MAX_VALUE, () -> 0L));
        assertEquals(0, failing.read());
        assertFalse(failing.done());
        assertThrows(IllegalArgumentException.class, () -> failing.step(reader, 0, 1, () -> 0L));
        assertThrows(IllegalArgumentException.class, () -> failing.step(reader, 5000, 1, () -> 0L));
    }

    @Test
    void aHundredDriftsGiveTwentyLinesTheOmittedCountAndTheLogLine() {
        StreetWorld world = StreetWorld.strip(5, 60).build();
        NavGraph graph = world.graph();
        Reader reader = new Reader(world);
        int changed = 0;
        for (int x = 0; x < 60 && changed < 100; x++) {
            for (int z = 0; z < 3 && changed < 100; z++) {
                reader.set(x, 63, z, solidSupport(NavSurface.ROAD));
                changed++;
            }
        }
        NavSweep sweep = NavSweep.forRevalidation(graph, HASH);
        run(sweep, reader);
        var r = sweep.revalidation(HASH);
        assertTrue(r.drifts().size() >= 100);
        assertEquals(21, r.lines().size());
        assertEquals("(" + (r.drifts().size() - 20) + " more omitted)", r.lines().getLast());
        String log = r.logLine("g1", "c001", 0);
        assertTrue(log.startsWith("NPCNAV-REVALIDATE graph=g1 region=c001 verdict=DRIFTED ok="));
        assertTrue(log.contains("changed=" + r.changedClass()) && log.endsWith("palette=same tickets_remaining=0"));
    }

    @Test
    void theSweepReportsEveryChunkItNeeds() {
        StreetWorld world = street();
        NavSweep sweep = NavSweep.forRevalidation(world.graph(), HASH);
        assertEquals(java.util.List.of(NavChunkLoader.key(0, 0), NavChunkLoader.key(1, 0)), sweep.chunkKeys());
    }

    // ---- the live class sweep behind /npcnav audit live ----

    @Test
    void liveClassesComeFromTheWorldAndFlagBlocksInNoClass() {
        StreetWorld world = street();
        NavGraph graph = tagged(world, 3, 1, "home");
        Reader reader = new Reader(world);
        reader.set(6, 63, 2, NavSweep.SOLID); // a solid block that is in no class
        NavSweep sweep = NavSweep.forClasses(graph);
        runClasses(sweep, reader, (x, y, z) -> "MARKER_" + x + "_" + y + "_" + z);
        var live = sweep.live();
        long off = StreetWorld.node(graph, 6, 2).id();
        assertEquals(Set.of(off), live.offPalette());
        assertEquals(0, live.unchecked());
        assertEquals(NavSurface.SIDEWALK, live.classes().get(StreetWorld.node(graph, 3, 1).id()));
        assertEquals(NavSurface.ROAD, live.classes().get(StreetWorld.node(graph, 3, 4).id()));
        assertEquals(graph.nodeCount() - 1, live.classes().size());
        assertEquals(Map.of(StreetWorld.node(graph, 3, 1).id(), "MARKER_3_63_1"), live.materials(),
                "only tagged nodes are named, by their support block");
    }

    @Test
    void blockNamesAreTakenInsideTheSlicedReadsAndTheVerdictAsksTheWorldNothing() {
        StreetWorld world = street();
        NavGraph graph = tagged(world, 3, 1, "home");
        AtomicInteger asked = new AtomicInteger();
        NavSweep.MaterialNamer namer = (x, y, z) -> {
            asked.incrementAndGet();
            return "MARKER_" + x + "_" + y + "_" + z;
        };
        NavSweep sweep = NavSweep.forClasses(graph);
        runClasses(sweep, new Reader(world), namer);
        assertEquals(1, asked.get(), "only the tagged node's support is named, once, while it is read");
        var live = sweep.live();
        assertEquals(1, asked.get(), "turning the cells into a verdict touches no world");
        assertEquals(Map.of(StreetWorld.node(graph, 3, 1).id(), "MARKER_3_63_1"), live.materials());
        // A sweep read with no namer names nothing, and a block in an unloaded chunk is never asked about.
        NavSweep plain = NavSweep.forClasses(graph);
        runClasses(plain, new Reader(world));
        assertTrue(plain.live().materials().isEmpty());
        Reader unloaded = new Reader(world);
        unloaded.unloadChunk(0, 0);
        asked.set(0);
        NavSweep gone = NavSweep.forClasses(graph);
        runClasses(gone, unloaded, namer);
        assertEquals(0, asked.get());
        assertTrue(gone.live().materials().isEmpty());
    }

    @Test
    void liveSweepMeasuresHowFarTheStreetRunsSidewaysAtEachCrossing() {
        StreetWorld world = street();
        NavGraph graph = world.graph();
        NavSweep sweep = NavSweep.forClasses(graph);
        runClasses(sweep, new Reader(world));
        var live = sweep.live();
        // Column 10, five road cells: five more cells of road on either side are measured, plus itself.
        long middle = StreetWorld.node(graph, 10, 5).id();
        assertEquals(11, live.extents().get(middle));
        // Column 0 sits at the box edge of the drawing: nothing on its left, five on its right.
        assertEquals(6, live.extents().get(StreetWorld.node(graph, 0, 5).id()));
        assertTrue(live.materials().isEmpty());
    }

    @Test
    void theSweepReportsTheNarrowestCellOfACrossingNotItsMiddle() {
        StreetWorld world = StreetWorld.strip(9, 30).build();
        NavGraph graph = world.graph();
        Reader reader = new Reader(world);
        // Beside the first road cell of column 15 the street stops, as at the mouth of a stub. The middle of
        // that column still sits in the wide road.
        reader.set(14, 63, 3, solidSupport(NavSurface.SIDEWALK));
        reader.set(16, 63, 3, solidSupport(NavSurface.SIDEWALK));
        NavSweep sweep = NavSweep.forClasses(graph);
        runClasses(sweep, reader);
        var live = sweep.live();
        assertEquals(1, live.extents().get(StreetWorld.node(graph, 15, 7).id()),
                "keyed by the middle cell, but the width is the narrowest cell's");
        NavSweep intact = NavSweep.forClasses(graph);
        runClasses(intact, new Reader(world));
        assertEquals(19, intact.live().extents().get(StreetWorld.node(graph, 15, 7).id()), "control: the same column in the unchanged world");
    }

    private static final StreetAudit.Options AUDIT =
            new StreetAudit.Options(StreetWorld.RULES, 60_000_000_000L, 1024, System::nanoTime);

    @Test
    void aLegacyGraphAuditedLiveFailsWithAClassifiedReadAndCannotWithItsOwnListPalette() {
        // The graph was scanned with a list palette: road cells are ordinary UNKNOWN ground, so routes cut across.
        NavGraph legacy = StreetWorld.strip(5, 20).build(false).graph();
        NavGraph tagged = StreetWorld.tag(legacy, Map.of(StreetWorld.node(legacy, 2, 1).id(), "home",
                StreetWorld.node(legacy, 14, 9).id(), "work"));

        // Reading the world through a classified palette tells the audit which cells are road.
        NavSweep classified = NavSweep.forClasses(tagged);
        runClasses(classified, new Reader(StreetWorld.strip(5, 20).build()));
        var withClasses = StreetAudit.audit(tagged, classified.live(), AUDIT);
        assertEquals(StreetAudit.Verdict.FAIL, withClasses.verdict());
        assertTrue(withClasses.count(StreetAudit.Code.LATERAL) > 0 || withClasses.count(StreetAudit.Code.NO_SIDEWALK_EXIT) > 0);

        // Reading it through the graph's own list palette puts every block in the same unknown class.
        NavSweep unclassified = NavSweep.forClasses(tagged);
        runClasses(unclassified, new Reader(StreetWorld.strip(5, 20).build(false)));
        var withoutClasses = StreetAudit.audit(tagged, unclassified.live(), AUDIT);
        assertEquals(StreetAudit.Verdict.INCONCLUSIVE, withoutClasses.verdict(), "it can never say FAIL, which is the bug");
        assertTrue(withoutClasses.unclassified() > 0);
    }

    @Test
    void widthProbesFollowTheClassesTheWorldShowsNotTheOnesStoredInAnOldGraph() {
        // One column of the world: sidewalk, five road blocks, sidewalk. Nothing beside it, so the road is one block wide.
        var drawing = StreetWorld.grid(0, 0, 3, 7).set(0, 0, 'S').fill(0, 1, 0, 5, 'R').set(0, 6, 'S');
        StreetWorld legacyWorld = drawing.build(false);
        NavGraph base = legacyWorld.graph();
        assertTrue(base.nodes().stream().allMatch(n -> n.surface() == NavSurface.UNKNOWN), "an old graph stores no classes");
        NavGraph graph = StreetWorld.tag(base, Map.of(StreetWorld.node(base, 0, 0).id(), "home",
                StreetWorld.node(base, 0, 6).id(), "work"));
        NavSweep sweep = NavSweep.forClasses(graph);
        int firstRound = sweep.total();
        runClasses(sweep, new Reader(drawing.build()));
        assertTrue(sweep.total() > firstRound, "probes were planned once the world showed a road");
        var live = sweep.live();
        assertEquals(1, live.extents().size());
        assertEquals(1, live.extents().values().iterator().next());
        var result = StreetAudit.audit(graph, live, AUDIT);
        assertEquals(StreetAudit.Verdict.FAIL, result.verdict());
        assertTrue(result.count(StreetAudit.Code.ALONG_ROAD) > 0, result.counts().toString());
        assertEquals(0, result.unchecked());
    }

    @Test
    void aLiveAuditSeesRoadUnderTheCornersOfADiagonalStepNoPathNodeStandsOn() {
        // A 2x2 sidewalk square with opposite corners tagged: the cheapest walk is the diagonal between them.
        StreetWorld square = StreetWorld.grid(0, 0, 2, 2).fill(0, 0, 1, 1, 'S').build();
        NavGraph base = square.graph();
        NavGraph graph = StreetWorld.tag(base, Map.of(StreetWorld.node(base, 0, 0).id(), "home",
                StreetWorld.node(base, 1, 1).id(), "work"));
        // Control: the world as scanned is clean.
        NavSweep before = NavSweep.forClasses(graph);
        runClasses(before, new Reader(square));
        assertEquals(StreetAudit.Verdict.PASS, StreetAudit.audit(graph, before.live(), AUDIT).verdict());
        // Now the two corner blocks beside the diagonal are road.
        Reader reader = new Reader(square);
        reader.set(1, 63, 0, solidSupport(NavSurface.ROAD));
        reader.set(0, 63, 1, solidSupport(NavSurface.ROAD));
        NavSweep sweep = NavSweep.forClasses(graph);
        runClasses(sweep, reader);
        var result = StreetAudit.audit(graph, sweep.live(), AUDIT);
        assertEquals(StreetAudit.Verdict.FAIL, result.verdict(), () -> result.examples().toString());
        assertTrue(result.count(StreetAudit.Code.LATERAL) > 0);
    }

    private static StreetAudit.Result liveAudit(NavGraph graph, Reader reader) {
        NavSweep sweep = NavSweep.forClasses(graph);
        runClasses(sweep, reader);
        return StreetAudit.audit(graph, sweep.live(), AUDIT);
    }

    @Test
    void aLiveAuditReadsTheCornersOfADiagonalStepEvenWhenNoNodeStandsThereAnyMore() {
        // A 2x2 sidewalk square after corridor compaction: only the two ends of the diagonal are left in the graph.
        StreetWorld square = StreetWorld.grid(0, 0, 2, 2).fill(0, 0, 1, 1, 'S').build();
        NavGraph graph = NavGraph.builder().addNode(1, 0, 64, 0, NavSurface.SIDEWALK, "home")
                .addNode(2, 1, 64, 1, NavSurface.SIDEWALK, "work").addBiEdge(1, 2, 14).build();
        // Control: the corners are still sidewalk.
        assertEquals(StreetAudit.Verdict.PASS, liveAudit(graph, new Reader(square)).verdict());
        // The cells beside the diagonal are planned: three heights for each of the two corner columns.
        NavSweep planned = NavSweep.forClasses(graph);
        int firstRound = planned.total();
        runClasses(planned, new Reader(square));
        assertEquals(firstRound + 6, planned.total());

        // Both corner blocks have become road.
        Reader roads = new Reader(square);
        roads.set(1, 63, 0, solidSupport(NavSurface.ROAD));
        roads.set(0, 63, 1, solidSupport(NavSurface.ROAD));
        var both = liveAudit(graph, roads);
        assertEquals(StreetAudit.Verdict.FAIL, both.verdict(), () -> both.examples().toString());
        assertTrue(both.count(StreetAudit.Code.LATERAL) > 0);
        assertEquals(0, both.unchecked());
        // One is enough.
        Reader one = new Reader(square);
        one.set(0, 63, 1, solidSupport(NavSurface.ROAD));
        assertEquals(StreetAudit.Verdict.FAIL, liveAudit(graph, one).verdict());
        // Road far below the diagonal is another street, not its corner.
        Reader below = new Reader(square);
        below.set(1, 63, 0, NavTerrainCapture.CLEAR);
        below.set(1, 62, 0, NavTerrainCapture.CLEAR);
        below.set(1, 61, 0, solidSupport(NavSurface.ROAD));
        assertEquals(StreetAudit.Verdict.PASS, liveAudit(graph, below).verdict());
        // Road under a sidewalk block is not what a walker stands on.
        Reader beneath = new Reader(square);
        beneath.set(1, 62, 0, solidSupport(NavSurface.ROAD));
        beneath.set(0, 62, 1, solidSupport(NavSurface.ROAD));
        assertEquals(StreetAudit.Verdict.PASS, liveAudit(graph, beneath).verdict());
    }

    @Test
    void aDiagonalCornerTheWorldReadCouldNotSeeIsUncheckedNotPassed() {
        // The ends stand in loaded chunks; one corner column is in the chunk beside them that is not loaded.
        StreetWorld square = StreetWorld.grid(0, 0, 17, 17).fill(0, 0, 16, 16, 'S').build();
        NavGraph graph = NavGraph.builder().addNode(1, 15, 64, 15, NavSurface.SIDEWALK, "home")
                .addNode(2, 16, 64, 16, NavSurface.SIDEWALK, "work").addBiEdge(1, 2, 14).build();
        assertEquals(StreetAudit.Verdict.PASS, liveAudit(graph, new Reader(square)).verdict(), "control: all loaded");
        Reader reader = new Reader(square);
        reader.unloadChunk(1, 0); // the corner at 16,15
        NavSweep sweep = NavSweep.forClasses(graph);
        runClasses(sweep, reader);
        var live = sweep.live();
        assertEquals(0, live.unchecked(), "both ends were read");
        var result = StreetAudit.audit(graph, live, AUDIT);
        assertEquals(StreetAudit.Verdict.INCONCLUSIVE, result.verdict());
        assertEquals(1, result.unchecked());
        // A corner that is plainly road is still a failure, whatever the other one could not show.
        reader.set(15, 63, 16, solidSupport(NavSurface.ROAD));
        assertEquals(StreetAudit.Verdict.FAIL, liveAudit(graph, reader).verdict());
    }

    /** One column of the world with its top support at {@code supportY}: solid below, clear above. */
    private static void column(Reader reader, int x, int z, int supportY, NavSurface surface) {
        for (int y = 50; y <= 80; y++) {
            reader.set(x, y, z, y < supportY ? NavSweep.SOLID : y == supportY ? solidSupport(surface) : NavTerrainCapture.CLEAR);
        }
    }

    /**
     * An eight-cell road crossing in column 0, between two sidewalks. Beside it the street runs on to the
     * right, each column's top support at the height {@code supportAt} gives for its distance from the crossing.
     */
    private static StreetAudit.Result crossingWithRoadBeside(java.util.function.IntUnaryOperator supportAt, int[] extent) {
        var drawing = StreetWorld.grid(0, 0, 1, 10).set(0, 0, 'S').fill(0, 1, 0, 8, 'R').set(0, 9, 'S');
        NavGraph base = drawing.build(false).graph();
        NavGraph graph = StreetWorld.tag(base, Map.of(StreetWorld.node(base, 0, 0).id(), "home",
                StreetWorld.node(base, 0, 9).id(), "work"));
        Reader reader = new Reader(drawing.build());
        for (int x = 1; x <= 9; x++) {
            for (int z = 1; z <= 8; z++) {
                column(reader, x, z, supportAt.applyAsInt(x), NavSurface.ROAD);
            }
        }
        NavSweep sweep = NavSweep.forClasses(graph);
        runClasses(sweep, reader);
        var live = sweep.live();
        assertEquals(1, live.extents().size(), "the one crossing is measured");
        extent[0] = live.extents().values().iterator().next();
        return StreetAudit.audit(graph, live, AUDIT);
    }

    @Test
    void theLiveWidthFollowsRoadThatStaysWithinOneStepOfTheCellBesideItNotAnyRoadInABand() {
        int[] extent = new int[1];
        // Control: level road beside the crossing is as wide as the crossing is long, so the lengthwise route is fine.
        var level = crossingWithRoadBeside(x -> 63, extent);
        assertEquals(9, extent[0]);
        assertEquals(StreetAudit.Verdict.PASS, level.verdict(), () -> level.examples().toString());
        // Control: a street climbing one block a column is one road, however high it ends up.
        var slope = crossingWithRoadBeside(x -> 63 + x, extent);
        assertEquals(9, extent[0]);
        assertEquals(StreetAudit.Verdict.PASS, slope.verdict(), () -> slope.examples().toString());
        // Supports alternating between two blocks under and two over: each is a step of two from the one before.
        var jumps = crossingWithRoadBeside(x -> x % 2 == 1 ? 62 : 64, extent);
        assertEquals(2, extent[0]);
        assertEquals(StreetAudit.Verdict.FAIL, jumps.verdict());
        assertTrue(jumps.count(StreetAudit.Code.ALONG_ROAD) > 0, jumps.counts().toString());
    }

    @Test
    void aCrossingWhoseSidewaysCellsAreInAnUnloadedChunkIsUncheckedNotPassed() {
        StreetWorld world = StreetWorld.strip(5, 16).build();
        NavGraph graph = world.graph();
        graph = StreetWorld.tag(graph, Map.of(StreetWorld.node(graph, 2, 1).id(), "home", StreetWorld.node(graph, 14, 9).id(), "work"));
        // Control: everything loaded is a clean pass.
        NavSweep whole = NavSweep.forClasses(graph);
        runClasses(whole, new Reader(world));
        assertEquals(StreetAudit.Verdict.PASS, StreetAudit.audit(graph, whole.live(), AUDIT).verdict());
        // Every node stands in the loaded chunk, but the road beside the last crossings runs into the next one.
        Reader reader = new Reader(world);
        reader.unloadChunk(1, 0);
        NavSweep sweep = NavSweep.forClasses(graph);
        runClasses(sweep, reader);
        var live = sweep.live();
        assertEquals(0, live.unchecked(), "every node's own support was read");
        var result = StreetAudit.audit(graph, live, AUDIT);
        assertEquals(StreetAudit.Verdict.INCONCLUSIVE, result.verdict());
        assertEquals(5, result.unchecked(), "the crossings at x 11..15 need cells at x 16 or beyond");
    }

    @Test
    void unloadedCellsAreUncheckedInALiveSweepAndLeaveNoWidth() {
        StreetWorld world = street();
        NavGraph graph = world.graph();
        Reader reader = new Reader(world);
        reader.unloadChunk(1, 0);
        NavSweep sweep = NavSweep.forClasses(graph);
        runClasses(sweep, reader);
        var live = sweep.live();
        assertTrue(live.unchecked() > 0);
        assertTrue(live.extents().containsKey(StreetWorld.node(graph, 10, 5).id()), "control: a width fully inside chunk 0");
        assertFalse(live.extents().containsKey(StreetWorld.node(graph, 14, 5).id()),
                "a width that needed an unread cell is not reported at all");
    }

    @Test
    void theTwoSweepKindsRefuseToAnswerTheOtherQuestion() {
        StreetWorld world = street();
        NavSweep footprints = NavSweep.forRevalidation(world.graph(), HASH);
        run(footprints, new Reader(world));
        assertThrows(IllegalStateException.class, footprints::live);
        NavSweep classes = NavSweep.forClasses(world.graph());
        runClasses(classes, new Reader(world));
        assertThrows(IllegalStateException.class, () -> classes.revalidation(HASH));
        // A class sweep with its first round read but no probes planned has no answer yet.
        NavSweep firstRound = NavSweep.forClasses(world.graph());
        run(firstRound, new Reader(world));
        assertThrows(IllegalStateException.class, firstRound::live);
        assertThrows(IllegalStateException.class, () -> footprints.planWidthProbes(16));
    }

    /** Seven-wide roads every thirty blocks, both ways, with nothing else: an old graph joins them all into one network. */
    private static StreetWorld.Builder roadGrid(int size) {
        var drawing = StreetWorld.grid(0, 0, size, size);
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                if (x % 30 < 7 || z % 30 < 7) drawing.set(x, z, 'R');
            }
        }
        return drawing;
    }

    private static NavGraph legacyRoadGrid(StreetWorld.Builder drawing) {
        NavGraph base = drawing.build(false).graph();
        return StreetWorld.tag(base, Map.of(StreetWorld.node(base, 3, 3).id(), "home",
                StreetWorld.node(base, 3, 33).id(), "work"));
    }

    @Test
    void aStreetNetworkLongerThanAnyCrossingGetsNoSidewaysProbes() {
        var drawing = roadGrid(60);
        NavGraph graph = legacyRoadGrid(drawing);
        NavSweep sweep = NavSweep.forClasses(graph);
        int firstRound = sweep.total();
        runClasses(sweep, new Reader(drawing.build()));
        assertEquals(firstRound, sweep.total(), "nothing is read beside a run that is already too long to be a crossing");
        var live = sweep.live();
        assertTrue(live.extents().isEmpty());
        var result = StreetAudit.audit(graph, live, AUDIT);
        assertEquals(StreetAudit.Verdict.FAIL, result.verdict());
        assertTrue(result.count(StreetAudit.Code.ALONG_ROAD) > 0, result.counts().toString());
        assertEquals(0, result.unchecked(), "a run that long needs no width reading");
    }

    @Test
    void probesStopAtTheLongestCrossingAndNotOneBlockPast() {
        for (int length : new int[]{16, 17}) {
            var drawing = StreetWorld.grid(0, 0, 3, length + 2).set(0, 0, 'S').fill(0, 1, 0, length, 'R').set(0, length + 1, 'S');
            NavGraph base = drawing.build(false).graph();
            NavGraph graph = StreetWorld.tag(base, Map.of(StreetWorld.node(base, 0, 0).id(), "home",
                    StreetWorld.node(base, 0, length + 1).id(), "work"));
            NavSweep sweep = NavSweep.forClasses(graph);
            int firstRound = sweep.total();
            runClasses(sweep, new Reader(drawing.build()));
            var live = sweep.live();
            if (length == 16) {
                assertTrue(sweep.total() > firstRound, "a run at the limit is a crossing, so it is probed");
                assertEquals(1, live.extents().size());
            } else {
                assertEquals(firstRound, sweep.total());
                assertTrue(live.extents().isEmpty());
            }
            var result = StreetAudit.audit(graph, live, AUDIT);
            assertEquals(StreetAudit.Verdict.FAIL, result.verdict(), "length " + length);
            assertTrue(result.count(StreetAudit.Code.ALONG_ROAD) > 0, "length " + length + " " + result.counts());
        }
    }

    @Test
    void aLegacyGridOfThirteenThousandNodesPlansQuicklyWithBoundedCellsAndAudits() {
        var drawing = roadGrid(180);
        NavGraph graph = legacyRoadGrid(drawing);
        assertTrue(graph.nodeCount() > 13_000 && graph.nodeCount() < 14_000, "nodes " + graph.nodeCount());
        long started = System.nanoTime();
        NavSweep sweep = NavSweep.forClasses(graph);
        int firstRound = sweep.total();
        runClasses(sweep, new Reader(drawing.build()));
        assertEquals(firstRound, sweep.total());
        var result = StreetAudit.audit(graph, sweep.live(), AUDIT);
        long millis = (System.nanoTime() - started) / 1_000_000L;
        assertEquals(StreetAudit.Verdict.FAIL, result.verdict());
        assertTrue(result.count(StreetAudit.Code.ALONG_ROAD) > 0);
        assertTrue(millis < 20_000, "planning and auditing took " + millis + " ms");
    }
}

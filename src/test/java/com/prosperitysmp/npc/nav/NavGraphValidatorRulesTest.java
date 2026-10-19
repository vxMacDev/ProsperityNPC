package com.prosperitysmp.npc.nav;

import com.prosperitysmp.npc.nav.NavGraphValidator.Issue;
import com.prosperitysmp.npc.nav.NavGraphValidator.Report;
import com.prosperitysmp.npc.nav.NavGraphValidator.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Each validator finding with its code, severity and exact message, and the order they are reported in. */
class NavGraphValidatorRulesTest {

    private static List<String> codes(Report report) {
        return report.issues().stream().map(Issue::code).toList();
    }

    private static Issue only(Report report, String code) {
        List<Issue> matching = report.issues().stream().filter(i -> i.code().equals(code)).toList();
        assertEquals(1, matching.size(), report.issues().toString());
        return matching.get(0);
    }

    /** Two spawn-tagged, connected sidewalk nodes: a graph with nothing to report. */
    private static NavGraph.Builder clean() {
        return NavGraph.builder().addNode(1, 0, 64, 0, NavSurface.SIDEWALK, "spawn").addNode(2, 1, 64, 0, NavSurface.SIDEWALK)
                .addBiEdge(1, 2, 10);
    }

    @Test
    void aCleanGraphHasNoIssuesAtAll() {
        Report report = NavGraphValidator.validate(clean().build());
        assertEquals(List.of(), report.issues());
        assertTrue(report.valid());
        assertEquals(0, report.errors());
        assertEquals(0, report.warnings());
    }

    @Test
    void anEmptyGraphIsOneErrorAndNothingElseIsChecked() {
        Report report = NavGraphValidator.validate(NavGraph.builder().build());
        assertEquals(List.of(new Issue(Severity.ERROR, "EMPTY", "graph has no nodes")), report.issues());
        assertFalse(report.valid());
        assertEquals(1, report.errors());
    }

    @Test
    void anEdgeFromAMissingNodeIsDanglingFromAndItsTargetIsNotReportedForIt() {
        Report report = NavGraphValidator.validate(clean().addEdge(77, 1, 10).build());
        assertEquals("edge references missing from-node 77", only(report, "DANGLING_FROM").message());
        assertEquals(Severity.ERROR, only(report, "DANGLING_FROM").severity());
        assertFalse(codes(report).contains("DANGLING_TO"));
        assertFalse(report.valid());
    }

    @Test
    void anEdgeToAMissingNodeIsDanglingTo() {
        Report report = NavGraphValidator.validate(clean().addEdge(1, 88, 10).build());
        assertEquals("edge references missing to-node 88", only(report, "DANGLING_TO").message());
        assertFalse(codes(report).contains("DANGLING_FROM"));
    }

    @Test
    void anEdgeBetweenTwoMissingNodesReportsBothEndsInOrder() {
        Report report = NavGraphValidator.validate(clean().addEdge(70, 80, 10).build());
        assertEquals(List.of("DANGLING_FROM", "DANGLING_TO"), codes(report));
    }

    @Test
    void aSelfLoopNamesTheNode() {
        Report report = NavGraphValidator.validate(clean().addEdge(2, 2, 10).build());
        assertEquals("self-loop edge at node 2", only(report, "SELF_LOOP").message());
        assertEquals(Severity.ERROR, only(report, "SELF_LOOP").severity());
    }

    @Test
    void aSelfLoopOnAMissingNodeIsAlsoDangling() {
        Report report = NavGraphValidator.validate(clean().addEdge(9, 9, 10).build());
        assertEquals(List.of("DANGLING_FROM", "DANGLING_TO", "SELF_LOOP"), codes(report));
    }

    @Test
    void zeroAndNegativeCostsAreBadCostsWithTheCostInTheMessage() {
        Report zero = NavGraphValidator.validate(clean().addEdge(1, 2, 0).build());
        assertEquals("edge 1->2 has non-positive cost 0", only(zero, "BAD_COST").message());
        Report negative = NavGraphValidator.validate(clean().addEdge(2, 1, -3).build());
        assertEquals("edge 2->1 has non-positive cost -3", only(negative, "BAD_COST").message());
    }

    @Test
    void aCostOfOneIsFine() {
        assertTrue(NavGraphValidator.validate(clean().addEdge(1, 2, 1).build()).valid());
    }

    @Test
    void aNodeWithNoEdgesIsAnOrphanWarningNamingIt() {
        Report report = NavGraphValidator.validate(clean().addNode(5, 9, 64, 9, NavSurface.SIDEWALK).build());
        Issue orphan = only(report, "ORPHAN");
        assertEquals(Severity.WARN, orphan.severity());
        assertEquals("node 5 has no edges (unreachable)", orphan.message());
        assertTrue(report.valid());
        assertEquals(List.of("ORPHAN", "DISCONNECTED"), codes(report));
    }

    @Test
    void aNodeOnlyTheTargetOfADanglingEdgeStillCountsAsTouched() {
        NavGraph graph = NavGraph.builder().addNode(1, 0, 64, 0, NavSurface.SIDEWALK, "spawn").addEdge(2, 1, 10).build();
        assertFalse(codes(NavGraphValidator.validate(graph)).contains("ORPHAN"));
    }

    @Test
    void aTagOnARoadNodeIsAnErrorNamingTheSurface() {
        NavGraph graph = clean().addNode(new NavGraph.Node(3, 2, 64, 0, Set.of("home"), NavSurface.ROAD))
                .addBiEdge(2, 3, 10).build();
        Issue issue = only(NavGraphValidator.validate(graph), "STREET_TAG");
        assertEquals("node 3 is on a road and cannot be a stop", issue.message());
        assertEquals(Severity.ERROR, issue.severity());
    }

    @Test
    void aTagOnACrossingNodeNamesItAsACrossing() {
        NavGraph graph = clean().addNode(new NavGraph.Node(3, 2, 64, 0, Set.of("market"), NavSurface.CROSSING))
                .addBiEdge(2, 3, 10).build();
        assertEquals("node 3 is on a crossing and cannot be a stop", only(NavGraphValidator.validate(graph), "STREET_TAG").message());
    }

    @Test
    void aRoadNodeWithNoTagsIsNotAStreetTagError() {
        NavGraph graph = clean().addNode(3, 2, 64, 0, NavSurface.ROAD).addBiEdge(2, 3, 10).build();
        assertFalse(codes(NavGraphValidator.validate(graph)).contains("STREET_TAG"));
    }

    @Test
    void aDiagonalStepOntoARoadIsNotAStraightStep() {
        NavGraph graph = clean().addNode(3, 2, 64, 1, NavSurface.ROAD).addEdge(2, 3, 10).build();
        Issue issue = only(NavGraphValidator.validate(graph), "STREET_SHAPE");
        assertEquals("edge 2->3 touches a road but is not a straight step", issue.message());
    }

    @Test
    void aStepStraightUpOntoARoadIsNotAStraightStepEither() {
        NavGraph graph = clean().addNode(3, 1, 65, 0, NavSurface.ROAD).addEdge(2, 3, 10).build();
        assertEquals("edge 2->3 touches a road but is not a straight step",
                only(NavGraphValidator.validate(graph), "STREET_SHAPE").message());
    }

    @Test
    void aStraightStepOntoARoadIsFine() {
        NavGraph graph = clean().addNode(3, 2, 64, 0, NavSurface.ROAD).addEdge(2, 3, 10).build();
        assertFalse(codes(NavGraphValidator.validate(graph)).contains("STREET_SHAPE"));
    }

    @Test
    void aDiagonalBetweenTwoSidewalksIsNotAStreetShapeProblem() {
        NavGraph graph = clean().addNode(3, 1, 64, 1, NavSurface.SIDEWALK).addBiEdge(2, 3, 14).build();
        assertFalse(codes(NavGraphValidator.validate(graph)).contains("STREET_SHAPE"));
    }

    @Test
    void streetShapeIsSkippedForAnEdgeWhoseNodeIsMissing() {
        NavGraph graph = clean().addEdge(2, 99, 10).build();
        assertFalse(codes(NavGraphValidator.validate(graph)).contains("STREET_SHAPE"));
    }

    @Test
    void aRoadCellJoinedOnBothAxesIsATurnAndEachTurnNodeIsNamedInIdOrder() {
        NavGraph.Builder b = NavGraph.builder();
        // Road cell 9 at the middle of a plus: curbs on x and on z both step onto it.
        b.addNode(9, 5, 64, 5, NavSurface.ROAD);
        b.addNode(1, 4, 64, 5, NavSurface.SIDEWALK, "spawn").addNode(2, 5, 64, 4, NavSurface.SIDEWALK);
        b.addBiEdge(1, 9, 10).addBiEdge(2, 9, 10);
        Report report = NavGraphValidator.validate(b.build());
        Issue turn = only(report, "STREET_TURN");
        assertEquals("node 9 is on a road and joins road cells on both axes, so a path could turn on it", turn.message());
        assertEquals(Severity.ERROR, turn.severity());
    }

    @Test
    void severalTurnsAreReportedInAscendingNodeIdOrder() {
        NavGraph.Builder b = NavGraph.builder();
        b.addNode(30, 5, 64, 5, NavSurface.ROAD).addNode(20, 15, 64, 5, NavSurface.ROAD);
        b.addNode(1, 4, 64, 5, NavSurface.SIDEWALK, "spawn").addNode(2, 5, 64, 4, NavSurface.SIDEWALK);
        b.addNode(3, 14, 64, 5, NavSurface.SIDEWALK).addNode(4, 15, 64, 4, NavSurface.SIDEWALK);
        b.addBiEdge(1, 30, 10).addBiEdge(2, 30, 10).addBiEdge(3, 20, 10).addBiEdge(4, 20, 10);
        List<String> messages = NavGraphValidator.validate(b.build()).issues().stream()
                .filter(i -> i.code().equals("STREET_TURN")).map(Issue::message).toList();
        assertEquals(2, messages.size());
        assertTrue(messages.get(0).startsWith("node 20 "), messages.toString());
        assertTrue(messages.get(1).startsWith("node 30 "), messages.toString());
    }

    @Test
    void aRoadCellStepsOnlyAlongOneAxisIsNotATurn() {
        NavGraph.Builder b = NavGraph.builder();
        b.addNode(5, 5, 64, 5, NavSurface.ROAD);
        b.addNode(1, 4, 64, 5, NavSurface.SIDEWALK, "spawn").addNode(2, 6, 64, 5, NavSurface.SIDEWALK);
        b.addBiEdge(1, 5, 10).addBiEdge(5, 2, 10);
        assertFalse(codes(NavGraphValidator.validate(b.build())).contains("STREET_TURN"));
    }

    @Test
    void unclassifiedNodesAreCountedInOneWarning() {
        NavGraph graph = NavGraph.builder().addNode(1, 0, 64, 0, "spawn").addNode(2, 1, 64, 0).addNode(3, 2, 64, 0)
                .addBiEdge(1, 2, 10).addBiEdge(2, 3, 10).build();
        Issue issue = only(NavGraphValidator.validate(graph), "UNKNOWN_SURFACE");
        assertEquals(Severity.WARN, issue.severity());
        assertEquals("3 nodes have no surface class (scanned without a classified palette)", issue.message());
    }

    @Test
    void weakComponentsCountEdgesInEitherDirection() {
        NavGraph oneWay = NavGraph.builder().addNode(1, 0, 64, 0, NavSurface.SIDEWALK, "spawn").addNode(2, 1, 64, 0, NavSurface.SIDEWALK)
                .addEdge(1, 2, 10).build();
        assertFalse(codes(NavGraphValidator.validate(oneWay)).contains("DISCONNECTED"));
    }

    @Test
    void separateGroupsAreCountedInTheDisconnectedWarning() {
        NavGraph graph = clean().addNode(3, 9, 64, 9, NavSurface.SIDEWALK).addNode(4, 10, 64, 9, NavSurface.SIDEWALK)
                .addNode(5, 20, 64, 9, NavSurface.SIDEWALK).addNode(6, 21, 64, 9, NavSurface.SIDEWALK)
                .addBiEdge(3, 4, 10).addBiEdge(5, 6, 10).build();
        Issue issue = only(NavGraphValidator.validate(graph), "DISCONNECTED");
        assertEquals(Severity.WARN, issue.severity());
        assertEquals("3 disconnected components; avatars cannot path between them", issue.message());
    }

    @Test
    void anEdgeToAMissingNodeDoesNotJoinAnything() {
        NavGraph graph = clean().addNode(3, 9, 64, 9, NavSurface.SIDEWALK).addEdge(3, 99, 10).build();
        assertEquals("2 disconnected components; avatars cannot path between them",
                only(NavGraphValidator.validate(graph), "DISCONNECTED").message());
    }

    @Test
    void aGraphWithNoSpawnTagWarnsAndOneWithAnySpawnTagDoesNot() {
        NavGraph noSpawn = NavGraph.builder().addNode(1, 0, 64, 0, NavSurface.SIDEWALK, "home").addNode(2, 1, 64, 0, NavSurface.SIDEWALK)
                .addBiEdge(1, 2, 10).build();
        Issue issue = only(NavGraphValidator.validate(noSpawn), "NO_SPAWN");
        assertEquals(Severity.WARN, issue.severity());
        assertEquals("no node is tagged 'spawn'", issue.message());
        assertFalse(codes(NavGraphValidator.validate(clean().build())).contains("NO_SPAWN"));
    }

    @Test
    void warningsNeverMakeAGraphInvalid() {
        NavGraph graph = NavGraph.builder().addNode(1, 0, 64, 0).addNode(2, 9, 64, 9).build();
        Report report = NavGraphValidator.validate(graph);
        assertTrue(report.valid());
        assertEquals(0, report.errors());
        assertEquals(5, report.warnings());
        assertEquals(List.of("ORPHAN", "ORPHAN", "UNKNOWN_SURFACE", "DISCONNECTED", "NO_SPAWN"), codes(report));
    }

    @Test
    void findingsAreReportedEdgesFirstThenNodesThenStreetRulesThenTheWholeGraphChecks() {
        NavGraph.Builder b = NavGraph.builder();
        b.addNode(1, 0, 64, 0);
        b.addNode(new NavGraph.Node(2, 1, 64, 1, Set.of("home"), NavSurface.ROAD));
        b.addNode(3, 8, 64, 8, NavSurface.SIDEWALK);
        b.addEdge(1, 2, 0).addEdge(1, 1, 10);
        List<String> codes = codes(NavGraphValidator.validate(b.build()));
        assertEquals(List.of("BAD_COST", "SELF_LOOP"), codes.subList(0, 2));
        assertEquals(Set.of("ORPHAN", "STREET_TAG"), Set.copyOf(codes.subList(2, 4)));
        assertEquals(List.of("STREET_SHAPE", "UNKNOWN_SURFACE", "DISCONNECTED", "NO_SPAWN"), codes.subList(4, 8));
        assertEquals(8, codes.size());
    }

    @Test
    void reportCountsSplitErrorsFromWarnings() {
        Report report = new Report(List.of(new Issue(Severity.ERROR, "A", "a"), new Issue(Severity.WARN, "B", "b"),
                new Issue(Severity.WARN, "C", "c")));
        assertEquals(1, report.errors());
        assertEquals(2, report.warnings());
        assertFalse(report.valid());
        assertTrue(new Report(List.of(new Issue(Severity.WARN, "B", "b"))).valid());
        assertTrue(new Report(List.of()).valid());
    }
}

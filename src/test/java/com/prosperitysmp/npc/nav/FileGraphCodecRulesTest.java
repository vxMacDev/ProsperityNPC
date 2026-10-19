package com.prosperitysmp.npc.nav;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The route file reader and writer: each refusal's exact message, the costs it works out, and what it writes. */
class FileGraphCodecRulesTest {
    private static final StreetRules RULES = new StreetRules(12, 16, 0.4);
    private static final FileGraphCodec.Limits CAPS = FileGraphCodec.Limits.DEFAULT;
    private static final String HEAD = "format: prosperity-nav-graph/1\n";
    private static final String MARKER = "SECRET_MARKER_58";

    private static NavGraph read(String text) {
        return FileGraphCodec.read(text, RULES, 1, CAPS);
    }

    private static String refusal(String text) {
        return assertThrows(FileGraphCodec.Refused.class, () -> read(text)).getMessage();
    }

    private static Map<String, Integer> costs(NavGraph graph) {
        Map<String, Integer> out = new HashMap<>();
        graph.edges().forEach(e -> out.put(e.from() + ">" + e.to(), e.cost()));
        return out;
    }

    private static NavGraph.Node node(long id, int x, int y, int z, NavSurface surface) {
        return new NavGraph.Node(id, x, y, z, Set.of(), surface);
    }

    // ---- top level ----

    @Test
    void aFileThatIsNotAMapIsRefused() {
        assertEquals("the file must be a map.", refusal("- 1\n- 2\n"));
    }

    @Test
    void anUnknownTopLevelKeyIsRefused() {
        assertEquals("the file has an unknown key.", refusal(HEAD + "nodes: []\nextra: 1\n"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"format: other\n", "format: prosperity-nav-graph/2\nnodes: []\n", "nodes: []\n", "format: 1\n"})
    void aMissingOrWrongFormatIsRefusedWithTheExpectedLine(String text) {
        assertEquals("The file must start with format: prosperity-nav-graph/1", refusal(text));
    }

    @Test
    void aFileWithNoNodesIsRefused() {
        assertEquals("The file has no nodes.", refusal(HEAD + "nodes: []\n"));
        assertEquals("The file has no nodes.", refusal(HEAD));
    }

    @Test
    void nodesThatAreNotAListAreRefused() {
        assertEquals("nodes must be a list.", refusal(HEAD + "nodes: {a: 1}\n"));
    }

    @Test
    void edgesThatAreNotAListAreRefused() {
        assertEquals("edges must be a list.", refusal(HEAD + "nodes: [{id: 1, at: [0, 64, 0], surface: sidewalk}]\nedges: 5\n"));
    }

    @Test
    void aMissingEdgesKeyMeansNoEdges() {
        NavGraph graph = read(HEAD + "nodes: [{id: 1, at: [0, 64, 0], surface: sidewalk}]\n");
        assertEquals(1, graph.nodeCount());
        assertEquals(0, graph.edgeCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"road", "crossing"})
    void aStreetSurfaceCannotBeTheDefault(String word) {
        assertEquals("default-surface is sidewalk or access, never road or crossing.",
                refusal(HEAD + "default-surface: " + word + "\nnodes: [{id: 1, at: [0, 64, 0]}]\n"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Sidewalk", "unknown", "5", "''"})
    void aDefaultSurfaceOutsideTheFourWordsIsRefused(String word) {
        assertEquals("default-surface is sidewalk, access, road or crossing.",
                refusal(HEAD + "default-surface: " + word + "\nnodes: [{id: 1, at: [0, 64, 0]}]\n"));
    }

    @Test
    void aDefaultSurfaceOfAccessIsGivenToNodesThatNameNone() {
        NavGraph graph = read(HEAD + "default-surface: access\nnodes: [{id: 1, at: [0, 64, 0]}, {id: 2, at: [1, 64, 0], surface: sidewalk}]\n");
        assertEquals(NavSurface.ACCESS, graph.node(1).surface());
        assertEquals(NavSurface.SIDEWALK, graph.node(2).surface());
    }

    @Test
    void theNodeCapMessageNamesTheCountAndTheLimit() {
        StringBuilder text = new StringBuilder(HEAD + "default-surface: sidewalk\nnodes:\n");
        for (int i = 1; i <= 6; i++) text.append("  - {id: ").append(i).append(", at: [").append(i).append(", 64, 0]}\n");
        String message = assertThrows(FileGraphCodec.Refused.class,
                () -> FileGraphCodec.read(text.toString(), RULES, 1, new FileGraphCodec.Limits(5, 100))).getMessage();
        assertEquals("The file has 6 nodes; the limit is 5.", message);
    }

    @Test
    void theEdgeCapCountsBothDirectionsOfAPair() {
        String text = HEAD + "default-surface: sidewalk\nnodes: [{id: 1, at: [0, 64, 0]}, {id: 2, at: [1, 64, 0]}, {id: 3, at: [2, 64, 0]}]\n"
                + "edges: [[1, 2], [2, 3]]\n";
        String message = assertThrows(FileGraphCodec.Refused.class,
                () -> FileGraphCodec.read(text, RULES, 1, new FileGraphCodec.Limits(10, 3))).getMessage();
        assertEquals("The file has 4 directed edges; the limit is 3.", message);
        assertEquals(4, FileGraphCodec.read(text, RULES, 1, new FileGraphCodec.Limits(10, 4)).edgeCount());
    }

    // ---- nodes ----

    private static final String ONE = HEAD + "nodes:\n  - {id: 1, at: [0, 64, 0], surface: sidewalk}\n";

    @Test
    void aNodeThatIsNotAMapIsRefusedWithItsIndex() {
        assertEquals("node 1 must be a map.", refusal(ONE + "  - plain\n"));
    }

    @Test
    void aNodeWithoutAnIdIsRefusedWithItsIndex() {
        assertEquals("node 1 id must be a whole number.", refusal(ONE + "  - {at: [1, 64, 0], surface: sidewalk}\n"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "-9"})
    void anIdThatIsNotPositiveIsRefusedWithTheNodeIndex(String id) {
        assertEquals("node 1: id must be a positive whole number.", refusal(ONE + "  - {id: " + id + ", at: [1, 64, 0], surface: sidewalk}\n"));
    }

    @Test
    void anIdBeyondIntRangeIsStillAWholeNumber() {
        NavGraph graph = read(HEAD + "nodes: [{id: 5000000000, at: [0, 64, 0], surface: sidewalk}]\n");
        assertTrue(graph.hasNode(5_000_000_000L));
    }

    @Test
    void aRepeatedIdIsRefusedWithTheId() {
        assertEquals("node 1 appears twice.", refusal(ONE + "  - {id: 1, at: [1, 64, 0], surface: sidewalk}\n"));
    }

    @ParameterizedTest
    @CsvSource({"'[]'", "'[0, 64]'", "'[0, 64, 0, 0]'"})
    void anAtOfTheWrongLengthIsRefusedWithTheId(String at) {
        assertEquals("node 2: at must be [x, y, z].", refusal(ONE + "  - {id: 2, at: " + at + ", surface: sidewalk}\n"));
    }

    @Test
    void aNodeWithNoAtIsRefusedAsTheWrongLength() {
        assertEquals("node 2: at must be [x, y, z].", refusal(ONE + "  - {id: 2, surface: sidewalk}\n"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[1.5, 64, 0]", "['1', 64, 0]", "[true, 64, 0]", "[99999999999, 64, 0]", "[null, 64, 0]"})
    void aCoordinateThatIsNotAnIntIsRefused(String at) {
        assertEquals("node 2: coordinates must be whole numbers.", refusal(ONE + "  - {id: 2, at: " + at + ", surface: sidewalk}\n"));
    }

    @Test
    void twoNodesOnTheSameBlockAreRefusedNamingTheSecond() {
        assertEquals("node 2 stands on the same block as another node.", refusal(ONE + "  - {id: 2, at: [0, 64, 0], surface: access}\n"));
    }

    @Test
    void twoNodesInTheSameColumnAtDifferentHeightsAreFine() {
        assertEquals(2, read(ONE + "  - {id: 2, at: [0, 65, 0], surface: sidewalk}\n").nodeCount());
    }

    @Test
    void aNodeWithNoSurfaceAndNoDefaultIsRefused() {
        assertEquals("node 2 has no surface and the file has no default-surface.", refusal(ONE + "  - {id: 2, at: [1, 64, 0]}\n"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "Road", "5", "''", "null"})
    void aSurfaceOutsideTheFourWordsIsRefused(String word) {
        assertEquals("node 2 surface is sidewalk, access, road or crossing.",
                refusal(ONE + "  - {id: 2, at: [1, 64, 0], surface: " + word + "}\n"));
    }

    @ParameterizedTest
    @CsvSource({"sidewalk,SIDEWALK", "access,ACCESS", "road,ROAD", "crossing,CROSSING"})
    void eachSurfaceWordIsReadAsItsSurface(String word, NavSurface expected) {
        assertEquals(expected, read(HEAD + "nodes: [{id: 1, at: [0, 64, 0], surface: " + word + "}]\n").node(1).surface());
    }

    @Test
    void aTagThatIsNotARouteTagIsRefusedWithTheId() {
        assertEquals("node 2 has a tag that is not a route tag.", refusal(ONE + "  - {id: 2, at: [1, 64, 0], surface: sidewalk, tags: [bank]}\n"));
        assertEquals("node 2 has a tag that is not a route tag.", refusal(ONE + "  - {id: 2, at: [1, 64, 0], surface: sidewalk, tags: [5]}\n"));
    }

    @Test
    void tagsThatAreNotAListAreRefused() {
        assertEquals("node 2 tags must be a list.", refusal(ONE + "  - {id: 2, at: [1, 64, 0], surface: sidewalk, tags: home}\n"));
    }

    @Test
    void nineDistinctTagsAreOverTheCapAndEightAreNot() {
        String eight = "spawn, home, work, market, leisure, work:cook, work:courier, work:gardener";
        assertEquals(8, read(ONE + "  - {id: 2, at: [1, 64, 0], surface: sidewalk, tags: [" + eight + "]}\n").node(2).tags().size());
        String uuid = new java.util.UUID(0, 3).toString();
        assertEquals("node 2 has more than 8 tags.",
                refusal(ONE + "  - {id: 2, at: [1, 64, 0], surface: sidewalk, tags: [" + eight + ", market:" + uuid + "]}\n"));
    }

    @Test
    void aStopOnARoadOrCrossingIsRefused() {
        assertEquals("node 2 is on a road and cannot be a stop.", refusal(ONE + "  - {id: 2, at: [1, 64, 0], surface: road, tags: [home]}\n"));
        assertEquals("node 2 is on a road and cannot be a stop.", refusal(ONE + "  - {id: 2, at: [1, 64, 0], surface: crossing, tags: [home]}\n"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "256", "-3"})
    void aWidthOutsideOneTo255IsRefused(String width) {
        assertEquals("node 2: width is only for road and crossing nodes, 1 to 255.",
                refusal(ONE + "  - {id: 2, at: [1, 64, 0], surface: road, width: " + width + "}\n"));
    }

    @Test
    void aWidthOnASidewalkIsRefused() {
        assertEquals("node 2: width is only for road and crossing nodes, 1 to 255.",
                refusal(ONE + "  - {id: 2, at: [1, 64, 0], surface: sidewalk, width: 4}\n"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "255"})
    void aWidthAtEitherEndOfTheRangeIsKept(String width) {
        NavGraph graph = read(HEAD + "nodes: [{id: 1, at: [0, 64, 0], surface: crossing, width: " + width + "}]\n");
        assertEquals(Integer.parseInt(width), graph.node(1).roadWidth());
    }

    @Test
    void aRoadNodeWithNoWidthRecordsTheLengthOfItsOwnCrossing() {
        NavGraph graph = read(HEAD + "nodes:\n  - {id: 1, at: [0, 64, 0], surface: sidewalk}\n  - {id: 2, at: [1, 64, 0], surface: road}\n"
                + "  - {id: 3, at: [2, 64, 0], surface: road}\n  - {id: 4, at: [3, 64, 0], surface: sidewalk}\nedges: [[1, 2], [2, 3], [3, 4]]\n");
        assertEquals(2, graph.node(2).roadWidth());
        assertEquals(2, graph.node(3).roadWidth());
        assertEquals(0, graph.node(1).roadWidth());
    }

    @Test
    void anExplicitWidthWinsOverTheCrossingLength() {
        NavGraph graph = read(HEAD + "nodes:\n  - {id: 1, at: [0, 64, 0], surface: sidewalk}\n  - {id: 2, at: [1, 64, 0], surface: road, width: 9}\n"
                + "  - {id: 3, at: [2, 64, 0], surface: sidewalk}\nedges: [[1, 2], [2, 3]]\n");
        assertEquals(9, graph.node(2).roadWidth());
    }

    @Test
    void tagsAreDeduplicatedAndKept() {
        NavGraph graph = read(ONE + "  - {id: 2, at: [1, 64, 0], surface: sidewalk, tags: [home, home, work]}\n");
        assertEquals(Set.of("home", "work"), graph.node(2).tags());
    }

    // ---- edges ----

    private static final String TWO = HEAD + "default-surface: sidewalk\nnodes:\n  - {id: 1, at: [0, 64, 0]}\n  - {id: 2, at: [1, 64, 0]}\n"
            + "  - {id: 3, at: [1, 64, 1]}\nedges:\n";

    @Test
    void aPairOfTheWrongSizeIsRefusedWithItsIndex() {
        assertEquals("edge 0: a pair is [from, to].", refusal(TWO + "  - [1]\n"));
        assertEquals("edge 1: a pair is [from, to].", refusal(TWO + "  - [1, 2]\n  - [1, 2, 3]\n"));
    }

    @Test
    void anEdgeThatIsNeitherAPairNorAMapIsRefused() {
        assertEquals("edge 0 must be a map.", refusal(TWO + "  - 5\n"));
    }

    @Test
    void anEdgeMapWithAnUnknownKeyIsRefused() {
        assertEquals("edge 0 has an unknown key.", refusal(TWO + "  - {from: 1, to: 2, speed: 3}\n"));
    }

    @Test
    void anEdgeEndThatIsNotANumberIsRefused() {
        assertEquals("edge 0 from must be a whole number.", refusal(TWO + "  - {to: 2}\n"));
        assertEquals("edge 0 to must be a whole number.", refusal(TWO + "  - {from: 1, to: '2'}\n"));
        assertEquals("edge 0 must be a whole number.", refusal(TWO + "  - [1, x]\n"));
    }

    @Test
    void anEdgeToANodeThatIsNotThereNamesTheEdgeAndTheMissingId() {
        assertEquals("edge 0 names node 9, which does not exist.", refusal(TWO + "  - [1, 9]\n"));
        assertEquals("edge 0 names node 8, which does not exist.", refusal(TWO + "  - [8, 1]\n"));
        assertEquals("edge 0 names node 8, which does not exist.", refusal(TWO + "  - [8, 9]\n"));
    }

    @Test
    void aRepeatedStepIsRefusedAndAPairReversedIsARepeatToo() {
        assertEquals("edge 1 repeats the step 1 to 2.", refusal(TWO + "  - {from: 1, to: 2}\n  - {from: 1, to: 2}\n"));
        assertEquals("edge 1 repeats the step 2 to 1.", refusal(TWO + "  - [1, 2]\n  - {from: 2, to: 1}\n"));
    }

    @Test
    void aStepBetweenNodesThatAreNotNeighboursIsRefused() {
        String far = HEAD + "default-surface: sidewalk\nnodes: [{id: 1, at: [0, 64, 0]}, {id: 2, at: [2, 64, 0]}]\nedges: [[1, 2]]\n";
        assertEquals("edge 0: nodes 1 and 2 are not next to each other.", refusal(far));
    }

    @Test
    void aStepUpOfOneBlockIsAllowedAndOfTwoIsNot() {
        String up1 = HEAD + "default-surface: sidewalk\nnodes: [{id: 1, at: [0, 64, 0]}, {id: 2, at: [1, 65, 0]}]\nedges: [[1, 2]]\n";
        assertEquals(2, read(up1).edgeCount());
        String up2 = up1.replace("[1, 65, 0]", "[1, 66, 0]");
        assertEquals("edge 0: nodes 1 and 2 are not next to each other.", refusal(up2));
    }

    @Test
    void theMaxStepArgumentDecidesHowHighAStepMayBe() {
        String up1 = HEAD + "default-surface: sidewalk\nnodes: [{id: 1, at: [0, 64, 0]}, {id: 2, at: [1, 65, 0]}]\nedges: [[1, 2]]\n";
        assertEquals("edge 0: nodes 1 and 2 are not next to each other.",
                assertThrows(FileGraphCodec.Refused.class, () -> FileGraphCodec.read(up1, RULES, 0, CAPS)).getMessage());
        assertEquals(2, FileGraphCodec.read(up1, RULES, 1, CAPS).edgeCount());
    }

    @Test
    void aDiagonalStepToARoadIsNotAStraightStep() {
        String text = HEAD + "nodes: [{id: 1, at: [0, 64, 0], surface: sidewalk}, {id: 2, at: [1, 64, 1], surface: road}]\nedges: [[1, 2]]\n";
        assertEquals("edge 0 touches a road but is not a straight step.", refusal(text));
    }

    @Test
    void aCostOnAStepThatTouchesARoadIsRefused() {
        String text = HEAD + "nodes: [{id: 1, at: [0, 64, 0], surface: sidewalk}, {id: 2, at: [1, 64, 0], surface: road}]\n"
                + "edges: [{from: 1, to: 2, cost: 30}]\n";
        assertEquals("edge 0 sets a cost on a road step; road costs are worked out by the plugin.", refusal(text));
    }

    @ParameterizedTest
    @CsvSource({"10,9,100", "14,13,140"})
    void aCostOnAnOrdinaryStepMustBeBetweenTheDefaultAndTenTimesIt(int base, int below, int top) {
        String z = base == 10 ? "0" : "1";
        String head = HEAD + "default-surface: sidewalk\nnodes: [{id: 1, at: [0, 64, 0]}, {id: 2, at: [1, 64, " + z + "]}]\nedges:\n";
        assertEquals("edge 0: cost must be " + base + " to " + top + ".", refusal(head + "  - {from: 1, to: 2, cost: " + below + "}\n"));
        assertEquals("edge 0: cost must be " + base + " to " + top + ".", refusal(head + "  - {from: 1, to: 2, cost: " + (top + 1) + "}\n"));
        assertEquals(top, costs(read(head + "  - {from: 1, to: 2, cost: " + top + "}\n")).get("1>2"));
        assertEquals(base, costs(read(head + "  - {from: 1, to: 2, cost: " + base + "}\n")).get("1>2"));
    }

    @Test
    void aCostThatIsNotAWholeNumberIsRefused() {
        assertEquals("edge 0 cost must be a whole number.", refusal(TWO + "  - {from: 1, to: 2, cost: fast}\n"));
        assertEquals("edge 0 cost must be a whole number.", refusal(TWO + "  - {from: 1, to: 2, cost: 12.5}\n"));
    }

    @Test
    void aPairGivesBothDirectionsTheDefaultCostAndAMapGivesOneDirection() {
        Map<String, Integer> costs = costs(read(TWO + "  - [1, 2]\n  - {from: 2, to: 3}\n"));
        assertEquals(Map.of("1>2", 10, "2>1", 10, "2>3", 10), costs);
    }

    @Test
    void aDiagonalStepDefaultsToFourteen() {
        assertEquals(14, costs(read(TWO + "  - [1, 3]\n")).get("1>3"));
        assertEquals(14, costs(read(TWO + "  - [1, 3]\n")).get("3>1"));
    }

    @Test
    void edgesAreEmittedInAscendingFromThenToOrderWhateverTheFileOrder() {
        NavGraph graph = read(TWO + "  - [2, 3]\n  - [1, 2]\n");
        assertEquals("1>2 2>1 2>3 3>2", graph.edges().stream().map(e -> e.from() + ">" + e.to()).reduce((a, b) -> a + " " + b).orElseThrow());
    }

    // ---- street costs ----

    private static String crossing(String surface, int cells) {
        StringBuilder text = new StringBuilder(HEAD + "nodes:\n  - {id: 1, at: [0, 64, 0], surface: sidewalk}\n");
        StringBuilder edges = new StringBuilder("edges:\n  - [1, 2]\n");
        for (int i = 0; i < cells; i++) {
            text.append("  - {id: ").append(i + 2).append(", at: [").append(i + 1).append(", 64, 0], surface: ").append(surface).append("}\n");
            if (i > 0) edges.append("  - [").append(i + 1).append(", ").append(i + 2).append("]\n");
        }
        text.append("  - {id: ").append(cells + 2).append(", at: [").append(cells + 1).append(", 64, 0], surface: sidewalk}\n");
        edges.append("  - [").append(cells + 1).append(", ").append(cells + 2).append("]\n");
        return text.toString() + edges;
    }

    @Test
    void steppingOntoAPlainCrossingPaysTheEntryPenaltyOnBothCurbs() {
        Map<String, Integer> costs = costs(read(crossing("road", 4)));
        assertEquals(10 + 20 * 12, costs.get("1>2"));
        assertEquals(10 + 20 * 12, costs.get("6>5"));
        assertEquals(10, costs.get("2>1"), "leaving a crossing costs the ordinary step");
        assertEquals(10, costs.get("2>3"), "walking over a crossing costs the ordinary step");
    }

    @Test
    void steppingOntoAPaintedCrosswalkCostsNothingExtra() {
        Map<String, Integer> costs = costs(read(crossing("crossing", 4)));
        assertEquals(10, costs.get("1>2"));
        assertEquals(10, costs.get("6>5"));
    }

    @Test
    void aShortPaintedCrossingWithTooFewInnerCellsIsStillPlain() {
        Map<String, Integer> costs = costs(read(crossing("crossing", 3)));
        assertEquals(10 + 20 * 12, costs.get("1>2"));
    }

    @Test
    void theEntryPenaltyFollowsTheStreetRulesPassedIn() {
        NavGraph graph = FileGraphCodec.read(crossing("road", 4), new StreetRules(5, 16, 0.4), 1, CAPS);
        assertEquals(10 + 20 * 5, costs(graph).get("1>2"));
    }

    // ---- write ----

    @Test
    void anUnclassifiedNodeCannotBeExported() {
        NavGraph graph = NavGraph.builder().addNode(7, 0, 64, 0).build();
        FileGraphCodec.Refused refused = assertThrows(FileGraphCodec.Refused.class, () -> FileGraphCodec.write(graph, RULES));
        assertEquals("Node 7 has no surface class; only classified graphs can be exported.", refused.getMessage());
    }

    @Test
    void nodesAreRenumberedFromOneInPositionOrderWithTheirTagsQuotedAndSorted() {
        NavGraph graph = NavGraph.builder()
                .addNode(new NavGraph.Node(90, 5, 64, 0, new TreeSet<>(Set.of("work", "home")), NavSurface.SIDEWALK))
                .addNode(node(20, 1, 64, 0, NavSurface.ACCESS))
                .build();
        assertEquals("format: prosperity-nav-graph/1\nnodes:\n"
                + "  - {id: 1, at: [1, 64, 0], surface: access}\n"
                + "  - {id: 2, at: [5, 64, 0], surface: sidewalk, tags: [\"home\", \"work\"]}\n"
                + "edges: []\n", FileGraphCodec.write(graph, RULES));
    }

    @Test
    void aRoadNodeWritesItsRecordedWidthAndNoOtherNodeDoes() {
        NavGraph graph = NavGraph.builder()
                .addNode(new NavGraph.Node(1, 0, 64, 0, Set.of(), NavSurface.ROAD, 7))
                .addNode(new NavGraph.Node(2, 1, 64, 0, Set.of(), NavSurface.CROSSING, 0))
                .addNode(new NavGraph.Node(3, 2, 64, 0, Set.of(), NavSurface.SIDEWALK, 5))
                .build();
        String text = FileGraphCodec.write(graph, RULES);
        assertTrue(text.contains("{id: 1, at: [0, 64, 0], surface: road, width: 7}"), text);
        assertTrue(text.contains("{id: 2, at: [1, 64, 0], surface: crossing}"), text);
        assertTrue(text.contains("{id: 3, at: [2, 64, 0], surface: sidewalk}"), text);
    }

    @Test
    void stepsWithTheDefaultCostsAreWrittenAsPairs() {
        NavGraph graph = NavGraph.builder().addNode(node(1, 0, 64, 0, NavSurface.SIDEWALK)).addNode(node(2, 1, 64, 0, NavSurface.SIDEWALK))
                .addNode(node(3, 1, 64, 1, NavSurface.SIDEWALK))
                .addBiEdge(1, 2, 10).addBiEdge(1, 3, 14).build();
        String text = FileGraphCodec.write(graph, RULES);
        assertTrue(text.endsWith("edges:\n  - [1, 2]\n  - [1, 3]\n"), text);
    }

    @Test
    void aSlowerStepIsWrittenWithItsCostAndAOneWayStepAsASingleMap() {
        NavGraph graph = NavGraph.builder().addNode(node(1, 0, 64, 0, NavSurface.SIDEWALK)).addNode(node(2, 1, 64, 0, NavSurface.SIDEWALK))
                .addEdge(1, 2, 30).addEdge(2, 1, 10).build();
        String text = FileGraphCodec.write(graph, RULES);
        assertTrue(text.endsWith("edges:\n  - {from: 1, to: 2, cost: 30}\n  - {from: 2, to: 1}\n"), text);
        NavGraph oneWay = NavGraph.builder().addNode(node(1, 0, 64, 0, NavSurface.SIDEWALK)).addNode(node(2, 1, 64, 0, NavSurface.SIDEWALK))
                .addEdge(2, 1, 10).build();
        assertTrue(FileGraphCodec.write(oneWay, RULES).endsWith("edges:\n  - {from: 2, to: 1}\n"));
    }

    @Test
    void aGraphReadBackFromItsOwnExportHasTheSameNodesAndCosts() {
        NavGraph original = read(crossing("road", 4));
        NavGraph again = FileGraphCodec.read(FileGraphCodec.write(original, RULES), RULES, 1, CAPS);
        assertEquals(costs(original), costs(again));
        assertEquals(original.nodeCount(), again.nodeCount());
        for (NavGraph.Node n : original.nodes()) {
            assertEquals(n, again.node(n.id()));
        }
    }

    @Test
    void anEdgeToAMissingNodeIsLeftOutOfTheExport() {
        NavGraph graph = NavGraph.builder().addNode(node(1, 0, 64, 0, NavSurface.SIDEWALK)).addEdge(1, 77, 10).build();
        assertTrue(FileGraphCodec.write(graph, RULES).endsWith("edges: []\n"));
    }

    // ---- adjacency and step cost ----

    @ParameterizedTest
    @CsvSource({"0,0,1,0,0,true", "0,0,1,1,0,true", "0,0,0,1,1,true", "0,0,2,0,0,false", "0,0,1,2,0,false", "0,0,0,0,0,false",
            "0,0,1,0,1,true", "0,0,1,0,2,false", "0,0,1,0,-1,true", "0,0,1,0,-2,false"})
    void twoNodesAreNeighboursWhenOneBlockApartSidewaysAndWithinOneInHeight(int ax, int az, int bx, int bz, int dy, boolean expected) {
        NavGraph.Node a = node(1, ax, 64, az, NavSurface.SIDEWALK);
        NavGraph.Node b = node(2, bx, 64 + dy, bz, NavSurface.SIDEWALK);
        assertEquals(expected, FileGraphCodec.adjacent(a, b, 1));
    }

    @Test
    void adjacencyUsesTheMaxStepGiven() {
        NavGraph.Node a = node(1, 0, 64, 0, NavSurface.SIDEWALK);
        NavGraph.Node b = node(2, 1, 65, 0, NavSurface.SIDEWALK);
        assertTrue(FileGraphCodec.adjacent(a, b, 1));
        assertFalse(FileGraphCodec.adjacent(a, b, 0));
    }

    @Test
    void coordinatesAtOppositeEndsOfTheIntRangeAreNeverAdjacent() {
        NavGraph.Node a = node(1, Integer.MIN_VALUE, 64, 0, NavSurface.SIDEWALK);
        NavGraph.Node b = node(2, Integer.MAX_VALUE, 64, 0, NavSurface.SIDEWALK);
        assertFalse(FileGraphCodec.adjacent(a, b, 1));
    }

    @Test
    void aStraightStepCostsTenAndADiagonalFourteen() {
        assertEquals(10, FileGraphCodec.stepCost(node(1, 0, 64, 0, NavSurface.SIDEWALK), node(2, 1, 64, 0, NavSurface.SIDEWALK)));
        assertEquals(10, FileGraphCodec.stepCost(node(1, 0, 64, 0, NavSurface.SIDEWALK), node(2, 0, 64, 1, NavSurface.SIDEWALK)));
        assertEquals(14, FileGraphCodec.stepCost(node(1, 0, 64, 0, NavSurface.SIDEWALK), node(2, 1, 64, 1, NavSurface.SIDEWALK)));
        assertEquals(10, FileGraphCodec.stepCost(node(1, 0, 64, 0, NavSurface.SIDEWALK), node(2, 1, 65, 0, NavSurface.SIDEWALK)));
    }

    // ---- limits and refusals ----

    @Test
    void theDefaultLimitsAreTheRuntimeSearchLimits() {
        assertEquals(new FileGraphCodec.Limits(Dijkstra.MAX_VISITED, Dijkstra.MAX_EDGES_EXAMINED), FileGraphCodec.Limits.DEFAULT);
    }

    @Test
    void limitsAboveTheGraphLimitsAreRefusedForEitherNumber() {
        assertThrows(IllegalArgumentException.class, () -> new FileGraphCodec.Limits(Dijkstra.MAX_GRAPH_NODES + 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new FileGraphCodec.Limits(1, Dijkstra.MAX_GRAPH_EDGES + 1));
        assertEquals(Dijkstra.MAX_GRAPH_NODES, new FileGraphCodec.Limits(Dijkstra.MAX_GRAPH_NODES, Dijkstra.MAX_GRAPH_EDGES).maxNodes());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "nodes: [{id: 1, at: [0, 64, 0], surface: sidewalk, SECRET_MARKER_58: 1}]",
            "nodes: &SECRET_MARKER_58 []",
            "nodes: [{id: 1, at: [0, 64, 0], surface: SECRET_MARKER_58}]",
            "nodes: [{id: 1, at: [0, 64, 0], surface: sidewalk, tags: [SECRET_MARKER_58]}]",
            "SECRET_MARKER_58: 1",
            "nodes: [1\nSECRET_MARKER_58"
    })
    void noRefusalEverQuotesTheFile(String body) {
        assertFalse(refusal(HEAD + body + "\n").contains(MARKER));
    }
}

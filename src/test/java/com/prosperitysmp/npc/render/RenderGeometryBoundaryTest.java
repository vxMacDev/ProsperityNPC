package com.prosperitysmp.npc.render;

import com.prosperitysmp.npc.nav.NavGraph;
import com.prosperitysmp.npc.render.RenderPolicy.Vec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** The exact edges of the walking checks: arrival radius, the one-block neighbour rule and the corridor width. */
class RenderGeometryBoundaryTest {
    @Test void theTwoToleranceConstantsAreThirtyFiveAndFortyFiveHundredths() {
        assertEquals(0.35, RenderGeometry.ARRIVAL);
        assertEquals(0.45, RenderGeometry.CORRIDOR);
    }

    @Test void aNodePositionIsTheCentreOfItsBlockColumnAtItsHeight() {
        Vec p = RenderGeometry.position(new NavGraph.Node(1, 10, 64, -3, java.util.Set.of()));
        assertEquals(10.5, p.x());
        assertEquals(64.0, p.y());
        assertEquals(-2.5, p.z());
    }

    @Test void arrivalAtExactlyTheToleranceCounts() {
        Vec target = new Vec(0.35, 64, 0);
        assertTrue(RenderGeometry.arrived(new Vec(0, 64, 0), target));
        assertTrue(RenderGeometry.arrived(target, target));
    }

    @Test void arrivalJustBeyondTheToleranceDoesNot() {
        assertFalse(RenderGeometry.arrived(new Vec(0, 64, 0), new Vec(0.3500001, 64, 0)));
        assertFalse(RenderGeometry.arrived(new Vec(0, 64, 0), new Vec(0, 64.3500001, 0)));
        assertFalse(RenderGeometry.arrived(new Vec(0, 64, 0), new Vec(0, 64, 0.3500001)));
    }

    @Test void arrivalIsMeasuredInThreeDimensionsNotAlongOneAxis() {
        // 0.2 along each axis is 0.346 away in a straight line, inside; 0.21 is 0.364, outside.
        assertTrue(RenderGeometry.arrived(new Vec(0, 0, 0), new Vec(0.2, 0.2, 0.2)));
        assertFalse(RenderGeometry.arrived(new Vec(0, 0, 0), new Vec(0.21, 0.21, 0.21)));
    }

    @Test void arrivalNeverHoldsForNotANumberOrInfinity() {
        Vec at = new Vec(0, 64, 0);
        assertFalse(RenderGeometry.arrived(new Vec(Double.NaN, 64, 0), at));
        assertFalse(RenderGeometry.arrived(new Vec(0, Double.NaN, 0), at));
        assertFalse(RenderGeometry.arrived(new Vec(0, 64, Double.POSITIVE_INFINITY), at));
        assertFalse(RenderGeometry.arrived(new Vec(Double.NEGATIVE_INFINITY, 64, 0), at));
    }

    @ParameterizedTest
    @CsvSource({"1,0,0,true", "-1,0,0,true", "0,0,1,true", "0,0,-1,true", "1,0,1,true", "-1,0,-1,true", "1,1,0,true",
            "1,-1,1,true", "0,1,1,true", "0,0,0,false", "0,1,0,false", "0,-1,0,false", "2,0,0,false", "0,0,2,false",
            "1.0001,0,0,false", "0,0,1.0001,false", "1,1.0001,0,false", "1,-1.0001,0,false", "0.5,0.5,0.5,true"})
    void aNeighbourIsWithinOneBlockHorizontallyAndOneVerticallyButNeverTheSamePoint(double dx, double dy, double dz, boolean adjacent) {
        Vec from = new Vec(10, 64, 10);
        Vec to = new Vec(10 + dx, 64 + dy, 10 + dz);
        assertEquals(adjacent, RenderGeometry.adjacent(from, to));
        assertEquals(adjacent, RenderGeometry.adjacent(to, from), "adjacency is symmetric");
    }

    @Test void notANumberIsNeverAdjacent() {
        Vec at = new Vec(0, 64, 0);
        assertFalse(RenderGeometry.adjacent(at, new Vec(Double.NaN, 64, 0)));
        assertFalse(RenderGeometry.adjacent(at, new Vec(0, 64, Double.NaN)));
        assertFalse(RenderGeometry.adjacent(at, new Vec(0, Double.NaN, 1)));
    }

    @Test void aZeroLengthSegmentConfinesOnlyWhatIsWithinTheArrivalTolerance() {
        Vec from = new Vec(0, 64, 0);
        assertTrue(RenderGeometry.confined(new Vec(0.35, 64, 0), from, from));
        assertFalse(RenderGeometry.confined(new Vec(0.3500001, 64, 0), from, from));
        assertFalse(RenderGeometry.confined(new Vec(0.4, 64, 0), from, from), "the corridor width does not apply to a point");
    }

    @Test void theCorridorIsExactlyFortyFiveHundredthsWide() {
        Vec from = new Vec(0, 0, 0);
        Vec to = new Vec(2, 0, 0);
        assertTrue(RenderGeometry.confined(new Vec(1, 0, 0.45), from, to));
        assertTrue(RenderGeometry.confined(new Vec(1, 0, -0.45), from, to));
        assertTrue(RenderGeometry.confined(new Vec(1, 0.45, 0), from, to));
        assertFalse(RenderGeometry.confined(new Vec(1, 0, 0.4500001), from, to));
        assertFalse(RenderGeometry.confined(new Vec(1, 0.4500001, 0), from, to));
    }

    @Test void thePointsPastEitherEndAreMeasuredFromTheNearerEnd() {
        Vec from = new Vec(0, 0, 0);
        Vec to = new Vec(2, 0, 0);
        assertTrue(RenderGeometry.confined(new Vec(-0.45, 0, 0), from, to));
        assertFalse(RenderGeometry.confined(new Vec(-0.4500001, 0, 0), from, to));
        assertTrue(RenderGeometry.confined(new Vec(2.4, 0, 0), from, to));
        assertFalse(RenderGeometry.confined(new Vec(2.5, 0, 0), from, to));
    }

    @Test void aSlopedSegmentConfinesAlongTheSlopeNotAlongTheFloor() {
        Vec from = new Vec(0, 0, 0);
        Vec to = new Vec(1, 1, 0);
        assertTrue(RenderGeometry.confined(new Vec(0.5, 0.5, 0), from, to));
        assertTrue(RenderGeometry.confined(new Vec(0.5, 0.5, 0.4), from, to));
        assertTrue(RenderGeometry.confined(new Vec(0.5, 0, 0), from, to), "0.35 from the slope, inside the corridor");
        assertFalse(RenderGeometry.confined(new Vec(1, 0, 0), from, to), "the foot of the slope is off the segment");
        assertFalse(RenderGeometry.confined(new Vec(0, 1, 0), from, to));
    }

    @Test void aSegmentWithNotANumberOrInfiniteEndsFallsBackToTheArrivalCheckOnItsStart() {
        Vec from = new Vec(0, 64, 0);
        assertTrue(RenderGeometry.confined(new Vec(0.1, 64, 0), from, new Vec(Double.NaN, 64, 0)));
        assertFalse(RenderGeometry.confined(new Vec(0.4, 64, 0), from, new Vec(Double.NaN, 64, 0)));
        assertTrue(RenderGeometry.confined(new Vec(0.1, 64, 0), from, new Vec(Double.POSITIVE_INFINITY, 64, 0)));
    }

    @Test void aNotANumberPointIsNeverConfined() {
        assertFalse(RenderGeometry.confined(new Vec(Double.NaN, 64, 0), new Vec(0, 64, 0), new Vec(2, 64, 0)));
    }

    @ParameterizedTest @ValueSource(doubles = {0.0, 0.1, 0.45})
    void everyPointOnTheSegmentItselfIsConfined(double along) {
        Vec from = new Vec(3, 70, -4);
        Vec to = new Vec(4, 70, -3);
        Vec point = new Vec(3 + along, 70, -4 + along);
        assertTrue(RenderGeometry.confined(point, from, to));
    }

    private static NavGraph graph() {
        return NavGraph.builder().addNode(1, 0, 64, 0).addNode(2, 1, 64, 0).addNode(3, 1, 64, 1).addNode(4, 5, 64, 5)
                .addEdge(1, 2, 3).addEdge(2, 3, 3).addEdge(3, 1, 3).addEdge(1, 4, 3).build();
    }

    @Test void anEdgeNeedsBothEndsAnAdjacentPairAndAPublishedDirectedLink() {
        NavGraph g = graph();
        assertTrue(RenderGeometry.edge(g, 1, 2));
        assertTrue(RenderGeometry.edge(g, 2, 3));
        assertTrue(RenderGeometry.edge(g, 3, 1), "the diagonal back to the start is one block apart in each axis");
        assertFalse(RenderGeometry.edge(g, 2, 1), "the link is one way");
        assertFalse(RenderGeometry.edge(g, 1, 4), "published but five blocks apart");
        assertFalse(RenderGeometry.edge(g, 1, 3), "adjacent but not published");
        assertFalse(RenderGeometry.edge(g, 1, 99));
        assertFalse(RenderGeometry.edge(g, 99, 1));
    }

    @Test void theNearestNodeOfAnEmptyGraphIsMinusOneAndOfOneNodeIsThatNode() {
        assertEquals(-1, RenderGeometry.nearest(NavGraph.builder().build(), new Vec(0, 0, 0)));
        assertEquals(7, RenderGeometry.nearest(NavGraph.builder().addNode(7, 100, 64, 100).build(), new Vec(0, 0, 0)));
    }

    @Test void theNearestNodeIsTheClosestInThreeDimensions() {
        NavGraph g = graph();
        assertEquals(4, RenderGeometry.nearest(g, new Vec(5.6, 64, 5.4)));
        assertEquals(1, RenderGeometry.nearest(g, new Vec(0.4, 64, 0.6)));
        assertEquals(2, RenderGeometry.nearest(g, new Vec(1.4, 64, 0.5)));
    }
}

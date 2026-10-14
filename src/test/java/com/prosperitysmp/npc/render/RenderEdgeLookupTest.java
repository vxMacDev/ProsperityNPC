package com.prosperitysmp.npc.render;

import com.prosperitysmp.npc.nav.NavGraph;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RenderEdgeLookupTest {
    @Test void directedPositiveMembershipPreservesGeometryChecks() {
        var graph = NavGraph.builder().addNode(1,0,64,0).addNode(2,1,64,0)
                .addNode(3,2,64,0).addNode(4,0,66,0)
                .addEdge(1,2,10).addEdge(1,3,10).addEdge(1,4,10).build();
        assertTrue(graph.hasPositiveEdge(1,2));
        assertFalse(graph.hasPositiveEdge(2,1));
        assertTrue(RenderGeometry.edge(graph,1,2));
        assertFalse(RenderGeometry.edge(graph,2,1));
        assertTrue(graph.hasPositiveEdge(1,3));
        assertFalse(RenderGeometry.edge(graph,1,3), "membership never authorizes a long segment");
        assertFalse(RenderGeometry.edge(graph,1,4), "membership never authorizes a vertical jump");
    }

    @Test void invalidCostsAndDanglingEndpointsDoNotBecomeWalkable() {
        var graph = NavGraph.builder().addNode(1,0,64,0).addNode(2,1,64,0)
                .addEdge(1,2,0).addEdge(2,1,-1).addEdge(1,99,10).addEdge(98,2,10).build();
        assertFalse(graph.hasPositiveEdge(1,2));
        assertFalse(graph.hasPositiveEdge(2,1));
        assertFalse(graph.hasPositiveEdge(1,99));
        assertFalse(graph.hasPositiveEdge(98,2));
        assertFalse(RenderGeometry.edge(graph,1,99));
        assertEquals(4,graph.edgeCount(), "invalid draft edges remain visible to the validator");
        assertEquals(2,graph.neighbors(1).size());
    }

    @Test void mixedDuplicatesKeepPositiveExistenceAndFreezeTheBuilder() {
        var builder = NavGraph.builder().addNode(1,0,64,0).addNode(2,1,64,0)
                .addEdge(1,2,0).addEdge(1,2,10).addEdge(1,2,-4);
        var graph = builder.build();
        builder.addEdge(2,1,20).addNode(3,2,64,0);
        assertTrue(graph.hasPositiveEdge(1,2));
        assertFalse(graph.hasPositiveEdge(2,1));
        assertFalse(graph.hasNode(3));
        assertEquals(3,graph.neighbors(1).size());
        assertThrows(UnsupportedOperationException.class, () -> graph.neighbors(1).clear());
    }

    @Test void denseDraftMembershipAgreesWithTheOriginalEdgeSemantics() {
        var builder = NavGraph.builder().addNode(1,0,64,0);
        for (int i=2;i<=12_000;i++) {
            builder.addNode(i,i,64,0).addEdge(1,i,i%2 == 0 ? 10 : 0);
            for (int duplicate=0;duplicate<5;duplicate++) builder.addEdge(1,i,-1);
        }
        var graph = builder.build();
        assertEquals(71_994,graph.neighbors(1).size());
        for (int i=2;i<=12_000;i++) {
            assertEquals(i%2 == 0,graph.hasPositiveEdge(1,i));
            assertFalse(graph.hasPositiveEdge(i,1));
        }
        assertFalse(graph.hasPositiveEdge(1,12_001));
    }
}

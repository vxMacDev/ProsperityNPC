package com.prosperitysmp.npc.nav;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NavCorridorTest {
    @Test void denseCaptureBecomesBoundedWithoutInventedEdgesOrLostTags() {
        var b = NavGraph.builder();
        for (int x=0; x<70; x++) for (int z=0; z<20; z++) {
            long id=x*20+z;
            b.addNode(id,x,72,z,id==0 ? new String[]{"home","work"}
                    : id==1399 ? new String[]{"leisure"} : new String[0]);
            if (x>0) b.addBiEdge(id,id-20,10);
            if (z>0) b.addBiEdge(id,id-1,10);
        }
        var source=b.build();
        assertEquals(Dijkstra.SearchStatus.BUDGET_EXHAUSTED,
                Dijkstra.findNearest(source,0,id->id==1399,e->true).status());
        var result=NavCorridor.compact(source);
        assertTrue(result.nodeCount()<source.nodeCount());
        assertEquals(source.node(0).tags(),result.node(0).tags());
        for (var edge: result.edges()) assertTrue(source.edges().contains(edge));
        for (long start:new long[]{0,1399}) {
            long goal=start==0?1399:0;
            assertEquals(Dijkstra.SearchStatus.FOUND,
                    Dijkstra.findNearest(result,start,id->id==goal,e->true).status());
        }
    }
    @Test void refusesDisconnectedAndMissingAnchors() {
        var b=NavGraph.builder().addNode(1,0,72,0,"home").addNode(2,2,72,0,"market");
        assertThrows(IllegalArgumentException.class,()->NavCorridor.compact(b.build()));
        assertThrows(IllegalArgumentException.class,()->NavCorridor.compact(
                NavGraph.builder().addNode(1,0,72,0).build()));
    }
    @Test void refusesUncapturedShortcutGeometry() {
        var b=NavGraph.builder().addNode(1,0,72,0,"home").addNode(2,10,72,0,"market")
                .addBiEdge(1,2,1);
        assertThrows(IllegalArgumentException.class,()->NavCorridor.compact(b.build()));
    }
}

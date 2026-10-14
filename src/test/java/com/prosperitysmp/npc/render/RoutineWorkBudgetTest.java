package com.prosperitysmp.npc.render;

import com.prosperitysmp.npc.nav.NavGraph;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.Set;
import java.util.HashMap;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class RoutineWorkBudgetTest {
    @Test void hundredAvatarsAndFastTimerStillOnlyAllowTwoPlansPerSecond() {
        var budget = new RenderManager.PlanBudget();
        var ids = java.util.stream.IntStream.range(0,100).mapToObj(i -> new UUID(0,i)).toList();
        int admitted = 0;
        for (int millis = 0; millis < 1000; millis += 50) {
            budget.begin(Set.copyOf(ids),millis);
            for (UUID id : ids) if (budget.acquire(id)) admitted++;
        }
        assertEquals(2, admitted);
        budget.begin(Set.copyOf(ids),1000);
        int next = 0;
        for (UUID id : ids) if (budget.acquire(id)) next++;
        assertEquals(2,next);
    }
    @Test void negativeClockDoesNotAdmitAndDelayedTickCannotAccumulateCredit() {
        var budget = new RenderManager.PlanBudget();
        UUID a = new UUID(0,1), b = new UUID(0,2), c = new UUID(0,3);
        budget.begin(Set.of(a,b,c),-1);
        assertFalse(budget.acquire(a));
        budget.begin(Set.of(a,b,c),30_000);
        assertTrue(budget.acquire(a));
        assertTrue(budget.acquire(b));
        assertFalse(budget.acquire(c));
    }
    @Test void failedPlansCannotStarveFortyOfOneHundredResidents() {
        var budget = new RenderManager.PlanBudget();
        var due = new HashMap<UUID,Long>();
        var firstPlan = new HashSet<UUID>();
        var ids = java.util.stream.IntStream.range(0,100).mapToObj(i -> new UUID(0,i)).toList();
        for (long time=0;time<60_000;time+=50) {
            Set<UUID> ready = new HashSet<>();
            for (UUID id:ids) if (time >= due.getOrDefault(id,0L)) ready.add(id);
            budget.begin(ready,time);
            for (UUID id:ids) if (ready.contains(id) && budget.acquire(id)) {
                firstPlan.add(id);
                due.put(id,time+30_000);
            }
        }
        assertEquals(100,firstPlan.size(),"thirty-second failures must rejoin behind still-waiting residents");
    }
    @Test void departedAndPausedHeadsCannotBlockReadyResidents() {
        var budget = new RenderManager.PlanBudget();
        UUID a=new UUID(0,1), b=new UUID(0,2), c=new UUID(0,3);
        budget.begin(Set.of(a,b,c),0);
        budget.begin(Set.of(b,c),0);
        assertFalse(budget.acquire(a));
        assertTrue(budget.acquire(b));
        assertTrue(budget.acquire(c));
    }
    @Test void previewRefreshIncludesEveryViewerAndActivePreviewWorld() {
        UUID a=UUID.randomUUID(),b=UUID.randomUUID(),c=UUID.randomUUID();
        assertEquals(Set.of(a,b,c),RenderManager.scopeWorlds(Set.of(a),Set.of(a,b),Set.of(c)));
        assertEquals(Set.of(b),RenderManager.scopeWorlds(Set.of(),Set.of(b),Set.of()));
        assertEquals(Set.of(),RenderManager.scopeWorlds(Set.of(),Set.of(),Set.of()));
    }
    @Test void publishedAdjacencyCannotBeMutatedBehindTheRouteValidator() {
        var graph = NavGraph.builder().addNode(1,0,64,0).addNode(2,1,64,0).addEdge(1,2,10).build();
        assertThrows(UnsupportedOperationException.class, () -> graph.neighbors(1).clear());
        assertThrows(UnsupportedOperationException.class, () -> graph.neighbors(1).add(new NavGraph.Edge(1,99,1)));
        assertEquals(1, graph.edgeCount());
        assertEquals(2, graph.neighbors(1).getFirst().to());
    }
    @Test void displacedArrivalReentersPlanQueueWithoutSkippingRetryDelay() {
        var graph = NavGraph.builder().addNode(1,0,64,0,"work").build();
        var person = com.prosperitysmp.npc.resident.ResidentCatalogue.betaCast().getFirst();
        var route = com.prosperitysmp.npc.resident.ResidentRoutePlanner.plan(person,
                com.prosperitysmp.npc.resident.ResidentRoutine.at(person,5000),graph,1);
        boolean original = RenderManager.atDestination(graph,route,new RenderPolicy.Vec(0.5,64,0.5));
        boolean displaced = RenderManager.atDestination(graph,route,new RenderPolicy.Vec(1.5,64,0.5));
        assertTrue(original);
        assertFalse(displaced);
        assertFalse(RenderManager.routePlanDue(true,original,30_000,30_000));
        assertFalse(RenderManager.routePlanDue(true,displaced,29_999,30_000));
        assertTrue(RenderManager.routePlanDue(true,displaced,30_000,30_000));
        assertTrue(RenderManager.routePlanDue(false,original,1,30_000));
        assertFalse(RenderManager.atDestination(NavGraph.builder().build(),route,new RenderPolicy.Vec(0.5,64,0.5)));
    }
}

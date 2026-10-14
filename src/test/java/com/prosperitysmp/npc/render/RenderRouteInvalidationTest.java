package com.prosperitysmp.npc.render;

import com.prosperitysmp.npc.nav.NavGraph;
import com.prosperitysmp.npc.property.Property;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RenderRouteInvalidationTest {
    private static final UUID WORLD = new UUID(0, 1);
    private static final UUID OTHER_WORLD = new UUID(0, 2);
    private static final NavGraph GRAPH = NavGraph.builder().addNode(1, 415, 72, -128, "home").build();

    @Test void masksAllChangedDistrictDataButPreservesSameNameInAnotherWorld() {
        var changed = new RenderManager.GraphKey(WORLD, "beta");
        var elsewhere = new RenderManager.GraphKey(OTHER_WORLD, "beta");
        var neighbor = new RenderManager.GraphKey(WORLD, "next");
        var a = candidate(changed, 10);
        var b = candidate(elsewhere, 11);
        var c = candidate(neighbor, 12);
        var before = new RenderManager.Snapshot(Map.of(a.property().propertyId(), a,
                b.property().propertyId(), b, c.property().propertyId(), c),
                Map.of(changed, GRAPH, elsewhere, GRAPH, neighbor, GRAPH), Set.of(changed, elsewhere, neighbor));
        var after = before.without(changed);
        assertEquals(Map.of(b.property().propertyId(), b, c.property().propertyId(), c), after.candidates());
        assertEquals(Map.of(elsewhere, GRAPH, neighbor, GRAPH), after.graphs());
        assertEquals(Set.of(elsewhere, neighbor), after.checked());
        assertSame(b, after.candidates().get(b.property().propertyId()));
        assertEquals(3, before.candidates().size());
        assertEquals(3, before.graphs().size());
        assertEquals(3, before.checked().size());
        assertEquals(after, after.without(changed));
        assertThrows(UnsupportedOperationException.class, () -> after.graphs().put(changed, GRAPH));
    }

    @Test void invalidatesNegativeLookupAfterFirstPublish() {
        var key = new RenderManager.GraphKey(WORLD, "new");
        var before = new RenderManager.Snapshot(Map.of(), Map.of(), Set.of(key));
        assertTrue(before.without(key).checked().isEmpty());
        assertEquals(Set.of(key), before.checked());
    }

    @Test void mixedCaseDistrictCannotKeepAnOldGraphAfterPublication() {
        var key = new RenderManager.GraphKey(WORLD, "Beta_ROUTE");
        var before = new RenderManager.Snapshot(Map.of(), Map.of(key, GRAPH), Set.of(key));
        var after = before.without(new RenderManager.GraphKey(WORLD, "beta_route"));
        assertTrue(after.graphs().isEmpty());
        assertTrue(after.checked().isEmpty());
        assertEquals("beta_route", key.region());
    }

    @Test void candidateRemovalDoesNotDependOnGraphBeingPresent() {
        var key = new RenderManager.GraphKey(WORLD, "beta");
        var candidate = candidate(key, 10);
        var before = new RenderManager.Snapshot(Map.of(candidate.property().propertyId(), candidate), Map.of(), Set.of());
        assertTrue(before.without(key).candidates().isEmpty());
    }

    @Test void absentDistrictIsANoOpForSnapshotContents() {
        var key = new RenderManager.GraphKey(WORLD, "beta");
        var before = new RenderManager.Snapshot(Map.of(), Map.of(key, GRAPH), Set.of(key));
        assertEquals(before, before.without(new RenderManager.GraphKey(OTHER_WORLD, "absent")));
    }

    @Test void compiledPublicationHandlerInvalidatesThenRefreshesAndBindsEveryAvatarPath() throws IOException {
        try (var bytes = RenderManager.class.getResourceAsStream("RenderManager.class")) {
            assertNotNull(bytes);
            var model = ClassFile.of().parse(bytes.readAllBytes());
            var changed = model.methods().stream().filter(m -> m.methodName().equalsString("routesChanged"))
                    .findFirst().orElseThrow();
            var calls = changed.code().orElseThrow().elementStream().filter(InvokeInstruction.class::isInstance)
                    .map(InvokeInstruction.class::cast).map(c -> c.name().stringValue()).toList();
            assertEquals("requireMainThread", calls.getFirst());
            assertTrue(calls.indexOf("invalidate") < calls.indexOf("despawn"));
            assertTrue(calls.indexOf("despawn") < calls.indexOf("requestSnapshot"));
            assertEquals(1, calls.stream().filter("invalidate"::equals).count());
            assertEquals(1, calls.stream().filter("requestSnapshot"::equals).count());
            var bindings = model.methods().stream().flatMap(m -> m.code().stream())
                    .flatMap(code -> code.elementStream()).filter(InvokeInstruction.class::isInstance)
                    .map(InvokeInstruction.class::cast).filter(c -> c.name().equalsString("view")).toList();
            assertEquals(3, bindings.size(), "preview, debug test and population must all bind a district");
            assertTrue(bindings.stream().allMatch(c -> c.type().stringValue().contains("RenderManager$GraphKey;")));
        }
    }

    private static RenderManager.Candidate candidate(RenderManager.GraphKey key, long id) {
        var property = new Property(new UUID(0, id), key.worldId(), key.region(), "test", "home",
                1, 100, 100, null, 1, 100, true);
        return new RenderManager.Candidate(property, "fixture", GRAPH, null);
    }
}

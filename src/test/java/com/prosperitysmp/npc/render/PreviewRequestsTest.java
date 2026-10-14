package com.prosperitysmp.npc.render;

import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentProfile;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.InvokeInstruction;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreviewRequestsTest {
    private static final UUID WORLD = new UUID(0, 100);
    private static final RenderPolicy.Vec ORIGIN = new RenderPolicy.Vec(415.5, 72, -127.5);
    private static PreviewRequests.Request request(int owner, int resident) {
        ResidentProfile source = ResidentCatalogue.betaCast().get(0);
        ResidentProfile profile = new ResidentProfile(new UUID(1, resident), source.name(), source.household(),
                source.generation(), source.occupation(), source.means(), source.lifeStage(), source.temperament(),
                source.preference(), source.wardrobe());
        return new PreviewRequests.Request(new UUID(0, owner), profile, WORLD, ORIGIN, "test_route", 11000);
    }
    private static PreviewRequests.Admission offer(PreviewRequests queue, PreviewRequests.Request request) {
        return queue.offer(request, Set.of(), 0, 20);
    }

    @Test void pendingVisitsHaveAHardGlobalLimit() {
        var queue = new PreviewRequests();
        for (int i = 1; i <= 3; i++) assertEquals(PreviewRequests.Admission.ACCEPTED, offer(queue, request(i, i)));
        assertEquals(PreviewRequests.Admission.FULL, offer(queue, request(4, 4)));
        assertEquals(3, queue.snapshot().size());
    }
    @Test void pendingAndRenderedVisitsShareThePreviewLimit() {
        var queue = new PreviewRequests();
        offer(queue, request(1, 1));
        assertEquals(PreviewRequests.Admission.FULL, queue.offer(request(2, 2), Set.of(new UUID(1, 8), new UUID(1, 9)), 2, 20));
    }
    @Test void pendingAndAllAvatarsShareServerCap() {
        var queue = new PreviewRequests();
        offer(queue, request(1, 1));
        assertEquals(PreviewRequests.Admission.FULL, queue.offer(request(2, 2), Set.of(new UUID(1, 9)), 0, 2));
        assertEquals(PreviewRequests.Admission.FULL, new PreviewRequests().offer(request(1, 1), Set.of(), 0, 0));
    }
    @Test void sameResidentCannotBeReservedByTwoOwners() {
        var queue = new PreviewRequests();
        offer(queue, request(1, 1));
        assertEquals(PreviewRequests.Admission.DUPLICATE, offer(queue, request(2, 1)));
    }
    @Test void activeResidentCannotBeQueuedAgain() {
        var req = request(1, 1);
        assertEquals(PreviewRequests.Admission.DUPLICATE,
                new PreviewRequests().offer(req, Set.of(req.profile().id()), 1, 20));
    }
    @Test void sameOwnerReplacesRatherThanAccumulates() {
        var queue = new PreviewRequests();
        offer(queue, request(1, 1));
        offer(queue, request(2, 2));
        offer(queue, request(3, 3));
        var replacement = request(1, 4);
        assertEquals(PreviewRequests.Admission.ACCEPTED, offer(queue, replacement));
        assertEquals(3, queue.snapshot().size());
        assertSame(replacement, queue.snapshot().getFirst());
    }
    @Test void rejectedReplacementDoesNotDestroyEarlierReservation() {
        var queue = new PreviewRequests();
        var original = request(1, 1);
        offer(queue, original);
        offer(queue, request(2, 2));
        assertEquals(PreviewRequests.Admission.DUPLICATE, offer(queue, request(1, 2)));
        assertSame(original, queue.snapshot().getFirst());
    }
    @Test void oldCompletionCannotClearAnEqualButNewerRequest() {
        var queue = new PreviewRequests();
        var original = request(1, 1);
        var replacement = request(1, 1);
        assertEquals(original, replacement);
        offer(queue, original); offer(queue, replacement);
        assertFalse(queue.remove(original));
        assertSame(replacement, queue.snapshot().getFirst());
        assertTrue(queue.remove(replacement));
        assertFalse(queue.remove(replacement));
    }
    @Test void dismissalAndShutdownRemovePendingState() {
        var queue = new PreviewRequests();
        offer(queue, request(1, 1)); offer(queue, request(2, 2));
        queue.cancel(new UUID(0, 1));
        assertEquals(1, queue.snapshot().size());
        queue.clear(); assertTrue(queue.isEmpty());
    }
    @Test void snapshotsAreNotMutableQueueViews() {
        var queue = new PreviewRequests(); offer(queue, request(1, 1));
        var snapshot = queue.snapshot(); queue.clear();
        assertEquals(1, snapshot.size());
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
    }
    @Test void requestRequiresValidExactDistrict() {
        for (String invalid : List.of("", "../route", "a b", "a".repeat(65))) {
            var req = request(1, 1);
            assertThrows(IllegalArgumentException.class, () -> new PreviewRequests.Request(req.owner(), req.profile(),
                    WORLD, ORIGIN, invalid, 11000));
        }
    }
    @Test void validityRequiresFreshPermissionAndOnlineFacts() {
        assertEquals(PreviewRequests.Eligibility.REVOKED,
                PreviewRequests.eligible(request(1, 1), 1000, false, null, null));
        assertEquals(PreviewRequests.Eligibility.READY,
                PreviewRequests.eligible(request(1, 1), 1000, true, WORLD, ORIGIN));
    }
    @Test void expiryUsesExactTenSecondDeadline() {
        var req = request(1, 1);
        assertEquals(1000 + PreviewRequests.TIMEOUT_MS, req.expiresMs());
        assertEquals(PreviewRequests.Eligibility.READY, PreviewRequests.eligible(req, 10999, true, WORLD, ORIGIN));
        assertEquals(PreviewRequests.Eligibility.EXPIRED, PreviewRequests.eligible(req, 11000, true, WORLD, ORIGIN));
    }
    @Test void changingWorldOrMovingVerticallyCancels() {
        var req = request(1, 1);
        assertEquals(PreviewRequests.Eligibility.MOVED, PreviewRequests.eligible(req, 1000, true, new UUID(0, 101), ORIGIN));
        assertEquals(PreviewRequests.Eligibility.MOVED, PreviewRequests.eligible(req, 1000, true, WORLD,
                new RenderPolicy.Vec(ORIGIN.x(), ORIGIN.y() + 1.01, ORIGIN.z())));
        assertEquals(PreviewRequests.Eligibility.READY, PreviewRequests.eligible(req, 1000, true, WORLD,
                new RenderPolicy.Vec(ORIGIN.x() + 1, ORIGIN.y(), ORIGIN.z())));
    }
    @Test void nonFinitePositionCannotPassDistanceGuard() {
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
            assertEquals(PreviewRequests.Eligibility.MOVED, PreviewRequests.eligible(request(1, 1), 1000, true,
                    WORLD, new RenderPolicy.Vec(invalid, 72, 0)));
    }
    @Test void actualSpawnPathChecksEligibilityAndAuthority() throws IOException {
        var calls = calls("completePreview");
        assertTrue(calls.contains("spawnAuthored"));
        assertFalse(calls.contains("spawn"));
        for (String gate : List.of("previewEnabled", "isOnline", "hasPermission", "eligible", "serverCap")) {
            assertTrue(calls.contains(gate), gate);
            assertTrue(calls.indexOf(gate) < calls.indexOf("spawnAuthored"), gate + " after spawn");
        }
        assertTrue(calls.indexOf("forSpawn") >= 0 && calls.indexOf("forSpawn") < calls.indexOf("spawnAuthored"));
    }
    @Test void actualMaintenanceCompletesPendingVisits() throws IOException {
        assertTrue(calls("maintain").contains("completePreview"));
        assertTrue(calls("requestSnapshot").contains("expedite"));
        assertTrue(calls("requestSnapshot").contains("snapshot"));
        assertTrue(calls("dismissPreviews").contains("cancel"));
        assertTrue(calls("despawnAll").contains("clear"));
    }
    private static List<String> calls(String method) throws IOException {
        try (var bytes = RenderManager.class.getResourceAsStream("RenderManager.class")) {
            assertNotNull(bytes);
            var parsed = ClassFile.of().parse(bytes.readAllBytes()).methods().stream()
                    .filter(m -> m.methodName().equalsString(method)).findFirst().orElseThrow();
            return parsed.code().orElseThrow().elementStream().filter(InvokeInstruction.class::isInstance)
                    .map(InvokeInstruction.class::cast).map(c -> c.name().stringValue()).toList();
        }
    }
}

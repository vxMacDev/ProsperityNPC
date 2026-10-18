package com.prosperitysmp.npc.api.contract;

import com.prosperitysmp.npc.api.ApiTestSupport;
import com.prosperitysmp.npc.api.OwnershipHooks;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The stand-in for ProsperityNPC that contract suites hand to a guard must record exactly what the guard did. */
class RecordingHooksTest {
    private static final UUID WORLD = new UUID(1, 2);
    private static final UUID OTHER_WORLD = new UUID(3, 4);

    @Test void aNewRecorderHasHeldNothingAndRecordedNothing() {
        var hooks = new RecordingHooks();
        assertFalse(hooks.isHeld(WORLD, "r1"));
        assertEquals(List.of(), hooks.bumps());
        assertEquals(List.of(), hooks.offMainMisuse());
        assertEquals(List.of(), hooks.failures());
        assertEquals(0, hooks.bumpsOf(WORLD, "r1"));
    }

    @Test void aKeyIsTheWorldIdAndTheRegionIdJoinedWithASlash() {
        assertEquals("00000000-0000-0001-0000-000000000002/r1", RecordingHooks.key(WORLD, "r1"));
    }

    @Test void aHeldRegionIsHeldUntilReleased() {
        var hooks = new RecordingHooks();
        hooks.hold(WORLD, "r1");
        assertTrue(hooks.isHeld(WORLD, "r1"));
        hooks.release(WORLD, "r1");
        assertFalse(hooks.isHeld(WORLD, "r1"));
    }

    @Test void holdingOneRegionHoldsNeitherAnotherRegionNorAnotherWorldsRegionOfTheSameId() {
        var hooks = new RecordingHooks();
        hooks.hold(WORLD, "r1");
        assertFalse(hooks.isHeld(WORLD, "r2"));
        assertFalse(hooks.isHeld(OTHER_WORLD, "r1"));
    }

    @Test void holdingTwiceNeedsOnlyOneRelease() {
        var hooks = new RecordingHooks();
        hooks.hold(WORLD, "r1");
        hooks.hold(WORLD, "r1");
        hooks.release(WORLD, "r1");
        assertFalse(hooks.isHeld(WORLD, "r1"));
    }

    @Test void releasingARegionThatWasNeverHeldChangesNothing() {
        var hooks = new RecordingHooks();
        hooks.hold(WORLD, "r1");
        hooks.release(WORLD, "r2");
        assertTrue(hooks.isHeld(WORLD, "r1"));
    }

    @Test void externalChangeAndMarkDirtyBothRecordABumpInOrder() {
        var hooks = new RecordingHooks();
        hooks.externalChange(WORLD, "r1");
        hooks.markDirty(WORLD, "r2");
        hooks.externalChange(OTHER_WORLD, "r1");
        assertEquals(List.of(RecordingHooks.key(WORLD, "r1"), RecordingHooks.key(WORLD, "r2"),
                RecordingHooks.key(OTHER_WORLD, "r1")), hooks.bumps());
    }

    @Test void bumpsOfCountsOnlyThatRegionInThatWorld() {
        var hooks = new RecordingHooks();
        hooks.externalChange(WORLD, "r1");
        hooks.externalChange(WORLD, "r1");
        hooks.markDirty(WORLD, "r1");
        hooks.externalChange(WORLD, "r2");
        hooks.externalChange(OTHER_WORLD, "r1");
        assertEquals(3, hooks.bumpsOf(WORLD, "r1"));
        assertEquals(1, hooks.bumpsOf(WORLD, "r2"));
        assertEquals(1, hooks.bumpsOf(OTHER_WORLD, "r1"));
        assertEquals(0, hooks.bumpsOf(OTHER_WORLD, "r2"));
    }

    @Test void clearBumpsForgetsBumpsButNotHeldRegionsOrFailures() {
        var hooks = new RecordingHooks();
        hooks.hold(WORLD, "r1");
        hooks.externalChange(WORLD, "r1");
        hooks.guarded("handler", () -> { throw new IllegalStateException("x"); });
        hooks.clearBumps();
        assertEquals(List.of(), hooks.bumps());
        assertEquals(0, hooks.bumpsOf(WORLD, "r1"));
        assertTrue(hooks.isHeld(WORLD, "r1"));
        assertEquals(1, hooks.failures().size());
    }

    @Test void bumpsRecordedAfterAClearAreCountedAgain() {
        var hooks = new RecordingHooks();
        hooks.externalChange(WORLD, "r1");
        hooks.clearBumps();
        hooks.externalChange(WORLD, "r1");
        assertEquals(1, hooks.bumpsOf(WORLD, "r1"));
    }

    @Test void theListsReturnedAreSnapshotsAndCannotBeChanged() {
        var hooks = new RecordingHooks();
        hooks.externalChange(WORLD, "r1");
        List<String> snapshot = hooks.bumps();
        hooks.externalChange(WORLD, "r2");
        assertEquals(1, snapshot.size());
        assertEquals(2, hooks.bumps().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.add("x"));
        assertThrows(UnsupportedOperationException.class, () -> hooks.offMainMisuse().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> hooks.failures().add("x"));
    }

    @Test void beginWriteIsRefusedBecauseEventBasedGuardsNeverCallIt() {
        var hooks = new RecordingHooks();
        var thrown = assertThrows(UnsupportedOperationException.class, () -> hooks.beginWrite(WORLD, "r1"));
        assertEquals("event-based guards never call beginWrite", thrown.getMessage());
        assertEquals(List.of(), hooks.offMainMisuse(), "on the main thread it is not misuse");
        assertEquals(List.of(), hooks.bumps());
    }

    @Test void externalChangeOnTheBuildingThreadIsNotMisuse() {
        var hooks = new RecordingHooks();
        hooks.externalChange(WORLD, "r1");
        assertEquals(List.of(), hooks.offMainMisuse());
    }

    @Test void externalChangeOffTheMainThreadIsRecordedAsMisuseAndStillBumps() {
        var hooks = new RecordingHooks();
        ApiTestSupport.onAnotherThread(() -> hooks.externalChange(WORLD, "r1"));
        assertEquals(List.of("externalChange"), hooks.offMainMisuse());
        assertEquals(1, hooks.bumpsOf(WORLD, "r1"));
    }

    @Test void markDirtyOffTheMainThreadIsAllowed() {
        var hooks = new RecordingHooks();
        ApiTestSupport.onAnotherThread(() -> hooks.markDirty(WORLD, "r1"));
        assertEquals(List.of(), hooks.offMainMisuse());
        assertEquals(1, hooks.bumpsOf(WORLD, "r1"));
    }

    @Test void isHeldOffTheMainThreadIsAllowed() {
        var hooks = new RecordingHooks();
        hooks.hold(WORLD, "r1");
        boolean[] seen = new boolean[1];
        ApiTestSupport.onAnotherThread(() -> seen[0] = hooks.isHeld(WORLD, "r1"));
        assertTrue(seen[0]);
        assertEquals(List.of(), hooks.offMainMisuse());
    }

    @Test void beginWriteOffTheMainThreadIsRecordedBeforeItIsRefused() {
        var hooks = new RecordingHooks();
        ApiTestSupport.onAnotherThread(() ->
                assertThrows(UnsupportedOperationException.class, () -> hooks.beginWrite(WORLD, "r1")));
        assertEquals(List.of("beginWrite"), hooks.offMainMisuse());
    }

    @Test void theMainThreadIsTheOneThatBuiltTheRecorder() {
        var built = new RecordingHooks[1];
        ApiTestSupport.onAnotherThread(() -> built[0] = new RecordingHooks());
        built[0].externalChange(WORLD, "r1");
        assertEquals(List.of("externalChange"), built[0].offMainMisuse(),
                "this thread did not build it, so it counts as off the main thread");
    }

    @Test void guardedRunsTheBodyOnceAndRecordsNothingWhenItSucceeds() {
        var hooks = new RecordingHooks();
        var runs = new AtomicInteger();
        hooks.guarded("ClaimTransferEvent", runs::incrementAndGet);
        assertEquals(1, runs.get());
        assertEquals(List.of(), hooks.failures());
    }

    @Test void guardedRecordsWhatFailedAndTheClassOfTheThrowableWithoutRethrowing() {
        var hooks = new RecordingHooks();
        assertDoesNotThrow(() -> hooks.guarded("ClaimDeletedEvent", () -> { throw new IllegalStateException("boom"); }));
        assertEquals(List.of("ClaimDeletedEvent: IllegalStateException"), hooks.failures());
    }

    @Test void guardedAlsoCatchesALinkageError() {
        var hooks = new RecordingHooks();
        assertDoesNotThrow(() -> hooks.guarded("ClaimChangeEvent", () -> { throw new NoClassDefFoundError("vendor/Claim"); }));
        assertEquals(List.of("ClaimChangeEvent: NoClassDefFoundError"), hooks.failures());
    }

    @Test void guardedKeepsFailuresInOrderAndKeepsRunningLaterBodies() {
        var hooks = new RecordingHooks();
        var after = new ArrayList<String>();
        hooks.guarded("first", () -> { throw new IllegalArgumentException(); });
        hooks.guarded("second", () -> after.add("ran"));
        hooks.guarded("third", () -> { throw new UnsupportedOperationException(); });
        assertEquals(List.of("first: IllegalArgumentException", "third: UnsupportedOperationException"), hooks.failures());
        assertEquals(List.of("ran"), after);
    }

    @Test void guardedWorksFromAnyThread() {
        var hooks = new RecordingHooks();
        ApiTestSupport.onAnotherThread(() -> hooks.guarded("off-main", () -> { throw new RuntimeException(); }));
        assertEquals(List.of("off-main: RuntimeException"), hooks.failures());
        assertEquals(List.of(), hooks.offMainMisuse());
    }

    @Test void concurrentMarkDirtyCallsAreAllCounted() throws Exception {
        var hooks = new RecordingHooks();
        var threads = new ArrayList<Thread>();
        for (int t = 0; t < 4; t++) {
            Thread thread = new Thread(() -> {
                for (int i = 0; i < 250; i++) hooks.markDirty(WORLD, "r1");
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) thread.join(10_000);
        assertEquals(1000, hooks.bumpsOf(WORLD, "r1"));
        assertEquals(1000, hooks.bumps().size());
    }

    @Test void theRecorderIsAnOwnershipHooksImplementation() {
        OwnershipHooks hooks = new RecordingHooks();
        hooks.externalChange(WORLD, "r1");
        assertFalse(hooks.isHeld(WORLD, "r1"));
    }
}

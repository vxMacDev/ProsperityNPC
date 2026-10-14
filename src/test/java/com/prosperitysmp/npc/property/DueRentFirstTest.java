package com.prosperitysmp.npc.property;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Staff rent and matching apply due rent changes first, or start nothing; off is the direct call. */
class DueRentFirstTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 13);
    private static final String NOT_STARTED = "batch not started";

    /** Records every step; the rent-change step's completion is held until the test releases it. */
    private static final class Rig implements DueRentFirst.Steps {
        boolean due = true, elsewhere, applyAccepted = true, allowed = true, batchAccepted = true, running;
        LocalDate today = DAY;
        BiConsumer<Boolean, String> pending;
        final List<String> calls = new ArrayList<>();
        final List<String> replies = new ArrayList<>();

        @Override public boolean rentChangesDue() { calls.add("due"); return due; }
        @Override public boolean stepOwnedElsewhere() { return elsewhere; }
        @Override public void stepRunning(boolean on) { running = on; calls.add("running=" + on); }
        @Override public LocalDate today() { return today; }
        @Override public boolean applyDueRentChanges(LocalDate day, BiConsumer<Boolean, String> done) {
            calls.add("apply " + day);
            if (applyAccepted) pending = done;
            return applyAccepted;
        }
        @Override public boolean stillAllowed() { calls.add("allowed"); return allowed; }
        @Override public boolean start(Consumer<String> reply) {
            calls.add("start");
            if (batchAccepted) reply.accept("batch started");
            return batchAccepted;
        }
        boolean request() { return DueRentFirst.request(this, replies::add, NOT_STARTED); }
        void finish(boolean ok) { var done = pending; pending = null; done.accept(ok, ok ? "No rent changes due." : "step failed"); }
    }

    @Test
    void offStartsTheBatchDirectlyAndNeverRunsTheStep() {
        var rig = new Rig();
        rig.due = false;
        assertTrue(rig.request());
        assertEquals(List.of("due", "start"), rig.calls, "flag off and nothing pending: the batch starts directly");
        assertEquals(List.of("batch started"), rig.replies);
        rig.calls.clear(); rig.replies.clear();
        rig.batchAccepted = false;
        assertFalse(rig.request(), "off: the batch's own refusal is returned unchanged");
        assertEquals(List.of("due", "start"), rig.calls);
        assertTrue(rig.replies.isEmpty());
    }

    @Test
    void dueChangesApplyBeforeTheBatchStarts() {
        var rig = new Rig();
        assertTrue(rig.request());
        assertEquals(List.of("due", "running=true", "apply " + DAY), rig.calls, "no batch before the step finishes");
        assertTrue(rig.running, "the daily cycle cannot start while the staff step runs");
        rig.finish(true);
        assertEquals(List.of("due", "running=true", "apply " + DAY, "running=false", "allowed", "start"), rig.calls);
        assertEquals(List.of("No rent changes due.", "batch started"), rig.replies);
        assertFalse(rig.running);
    }

    @Test
    void failedOrUnreconciledStepStartsNoBatch() {
        var rig = new Rig();
        assertTrue(rig.request());
        rig.finish(false);
        assertFalse(rig.calls.contains("start"));
        assertFalse(rig.calls.contains("allowed"));
        assertEquals(List.of("step failed", DueRentFirst.STEP_FAILED), rig.replies);
        assertFalse(rig.running);
    }

    @Test
    void busyStepRefusesAtOnce() {
        var rig = new Rig();
        rig.applyAccepted = false;
        assertFalse(rig.request());
        assertFalse(rig.calls.contains("start"));
        assertFalse(rig.running, "a refused step leaves no guard behind");
        assertEquals(List.of(DueRentFirst.STEP_BUSY), rig.replies);
    }

    @Test
    void cycleOrAnotherStepInProgressRefusesWithoutTouchingAnything() {
        var rig = new Rig();
        rig.elsewhere = true;
        assertFalse(rig.request());
        assertEquals(List.of("due"), rig.calls);
        assertEquals(List.of(DueRentFirst.OWNED_ELSEWHERE), rig.replies);
    }

    @Test
    void authorityOrSwitchLostDuringTheHopStartsNoBatch() {
        var rig = new Rig();
        assertTrue(rig.request());
        rig.allowed = false;
        rig.finish(true);
        assertFalse(rig.calls.contains("start"));
        assertEquals(List.of("No rent changes due.", DueRentFirst.STOPPED), rig.replies);
        assertFalse(rig.running);
    }

    @Test
    void dayRolloverDuringTheHopStartsNoBatch() {
        var rig = new Rig();
        assertTrue(rig.request());
        rig.today = DAY.plusDays(1);
        rig.finish(true);
        assertFalse(rig.calls.contains("start"));
        assertEquals(List.of("No rent changes due.", DueRentFirst.DAY_CHANGED), rig.replies);
    }

    @Test
    void batchRefusalAfterTheStepIsReported() {
        var rig = new Rig();
        rig.batchAccepted = false;
        assertTrue(rig.request());
        rig.finish(true);
        assertTrue(rig.calls.contains("start"));
        assertEquals(List.of("No rent changes due.", NOT_STARTED), rig.replies);
    }

    @Test
    void throwingReplyNeverLeavesTheGuardSet() {
        var rig = new Rig();
        rig.batchAccepted = false; // the batch itself does not reply; only the gate's own (guarded) replies run
        assertTrue(DueRentFirst.request(rig, message -> { throw new IllegalStateException("sender gone"); }, NOT_STARTED));
        rig.finish(true);
        assertFalse(rig.running);
        assertTrue(rig.calls.contains("start"));
    }

    @Test
    void staffCommandsAndTheCycleAreWiredThroughTheGate() throws Exception {
        String command = Files.readString(Path.of("src/main/java/com/prosperitysmp/npc/command/NpcAdminCommand.java"));
        assertTrue(command.contains("boolean accepted=plugin.requestStaffRent("));
        assertTrue(command.contains("boolean accepted = plugin.requestStaffMatch("));
        assertFalse(command.contains("requestRentBatch("), "staff rent never calls the batch directly");
        assertFalse(command.contains("housingRuntime().request("), "staff matching never calls the planner directly");

        String plugin = Files.readString(Path.of("src/main/java/com/prosperitysmp/npc/ProsperityNpcPlugin.java"));
        assertTrue(plugin.contains("if(dailyCycleRunning || staffRentChangeStep || !isEnabled()"),
                "the daily cycle never starts while a staff rent-change step runs");
        assertTrue(plugin.contains("r->utilityPayments!=null && utilityPayments.requestRentBatch(authority,r)"));
        assertTrue(plugin.contains("r->housingRuntime!=null && housingRuntime.request(authority,r)"));
        assertTrue(plugin.contains("@Override public boolean rentChangesDue() { return propertyService!=null && propertyService.rentChangesDue(); }"));
        assertTrue(plugin.contains("return propertyService!=null && propertyService.applyDueRentChanges(day,done);"));
        assertEquals(2, plugin.split("utilityPayments.requestRentBatch\\(", -1).length - 1,
                "rent batch entry points: the daily cycle's phase 4 (after its own step) and the staff gate");

        String runtime = Files.readString(Path.of("src/main/java/com/prosperitysmp/npc/economy/UtilityPaymentRuntime.java"));
        int first = runtime.indexOf("boolean holdDueRent = rentChangesDue();");
        int reread = runtime.indexOf("boolean holdNow = holdDueRent || rentChangesDue();");
        int sql = runtime.indexOf("return reserveRentBatchCounted(rents, retireNow, holdNow, day, properties, limits, plugin.getLogger()::warning);");
        assertTrue(first > 0 && reread > first && sql > reread, "read on main, re-read after the hop, then the SQL step");
    }
}

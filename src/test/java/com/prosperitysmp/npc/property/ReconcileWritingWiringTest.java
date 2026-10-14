package com.prosperitysmp.npc.property;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Wiring of the Codex round 4 fixes in classes that need a live server (Bukkit) to run, checked in their source:
 * which admissions refuse while the ownership check is writing a home (F1), the install-before-release order of
 * the steps that change a home's SQL row under its lease (F3), and the health and warning lines (F5).
 * The decisions themselves are unit tested (OwnershipReconcilePumpTest, LeasedRowInstallTest).
 */
class ReconcileWritingWiringTest {
    private static String source(String path) throws Exception {
        return Files.readString(Path.of("src/main/java/com/prosperitysmp/npc/" + path));
    }
    private static String between(String text, String from, String to) {
        int a = text.indexOf(from);
        assertTrue(a >= 0, "missing: " + from);
        int b = text.indexOf(to, a);
        assertTrue(b > a, "missing after it: " + to);
        return text.substring(a, b);
    }

    @Test void paymentAdmissionRefusesWhileTheOwnershipCheckWritesTheHome() throws Exception {
        String runtime = source("economy/UtilityPaymentRuntime.java");
        String admission = between(runtime, "Consumer<String> reply,\n                                com.prosperitysmp.npc.property.AutoCareRepository.Claim recurring",
                "busy = true;");
        int refusal = admission.indexOf("if (plugin.propertyService().reconcileWriting(propertyId)) return false;");
        int lease = admission.indexOf("lease = plugin.ownershipFence().acquire(owner);");
        assertTrue(refusal > 0 && lease > refusal, "refused before any lease is taken, like a Busy fence");
        // Batch paths keep their own behavior: no new refusal in the rent batch.
        assertFalse(between(runtime, "public boolean requestRentBatch(", "UUID batchId").contains("reconcileWriting"));
    }

    @Test void ownerRentChangeAndDisableRefuseWhileTheOwnershipCheckWritesTheHome() throws Exception {
        String service = source("property/PropertyService.java");
        String rent = between(service, "public boolean setRentCoordinated(", "admissionPending = true;");
        assertTrue(rent.indexOf("|| pump.writing(property.propertyId())") > 0
                && rent.indexOf("pump.writing(property.propertyId())") < rent.indexOf("plugin.ownershipFence().acquire(owner)"));
        // Polarity: writing is part of the return-false condition, never negated.
        assertFalse(rent.contains("!pump.writing("));
        int open = rent.indexOf("if (admissionPending");
        String guard = rent.substring(open, rent.indexOf(") return false;", open));
        assertTrue(guard.contains("|| pump.writing(property.propertyId())"), "part of the refusal condition");
        String disable = between(service, "public boolean unenrollCoordinated(", "admissionPending=true;");
        assertTrue(disable.indexOf("if(pump.writing(property.propertyId()))return false;") > 0
                && disable.indexOf("pump.writing(") < disable.indexOf("plugin.ownershipFence().acquire(owner)"));
    }

    @Test void incidentChecksInstallTheirRowsBeforeReleasingTheLeases() throws Exception {
        String housing = source("market/HousingRuntime.java");
        String step = between(housing, "public boolean requestIncidents(", "public static List<com.prosperitysmp.npc.property.Property> dailyTargets(");
        int reread = step.indexOf("service.readRow(p.propertyId())");
        int install = step.indexOf("service.installLeased(properties,reread,\"Daily property checks\");");
        int release = step.indexOf("for(var lease:leases)lease.releaseReconciled();\n                        busy=false;");
        int roll = step.indexOf("rolls.roll(");
        assertTrue(roll > 0 && reread > 0 && install > 0 && release > 0, "every step is present");
        assertTrue(reread > roll, "re-read after the rolls, on the worker");
        assertTrue(install > reread && release > install, "installed on main, then released");
        int reply = step.indexOf("replies.add(()->{", reread);
        assertFalse(step.substring(reply, install).contains("releaseReconciled"), "no lease is released before the install");
    }

    @Test void aFailedReReadMakesTheLeasedHomesWaitForTheOwnershipCheck() throws Exception {
        // installLeased needs the plugin, so its rows == null branch is pinned here; markAhead itself is unit tested.
        String service = source("property/PropertyService.java");
        String failed = between(service, "public int installLeased(", "var outcome = installLeasedRows(");
        int branch = failed.indexOf("if (rows == null) {");
        int mark = failed.indexOf("pump.markAhead(leased.stream().map(Property::propertyId).toList());");
        int done = failed.indexOf("return 0;");
        assertTrue(branch > 0 && mark > branch && done > mark, "every leased home is marked before the step returns");
        // A paid repair whose row is missing is treated as a failed re-read, not as nothing to install.
        String runtime = source("economy/UtilityPaymentRuntime.java");
        assertTrue(runtime.contains("readRow(property.propertyId()).map(List::of).orElse(null)"));
    }

    @Test void autoCareWaitsForTheOwnershipCheckInsteadOfLosingTheDay() throws Exception {
        // AutoCareRuntime needs a live server, so its wiring is pinned here; the refusal itself is requestCore's.
        String runtime = source("property/AutoCareRuntime.java");
        String select = between(runtime, "Authority captured = found == null ? null : consentFence.capture(found.consent());",
                "submit(() -> {");
        assertTrue(select.contains("|| checking(found.consent().propertyId())) { busy = false; return; }"),
                "a home being checked is skipped before the day's action is reserved");
        String reserved = between(runtime, "if (!reservation.newlyReserved()) { busy = false; return; }", "}, () -> busy = false);");
        assertTrue(reserved.contains("admitWhenChecked(reservation.claim(), c, captured, 0);"));
        assertFalse(reserved.contains("requestAutoCare"), "admission only goes through the wait");
        String admit = between(runtime, "private void admitWhenChecked(", "/** Worker-only selection");
        int wait = admit.indexOf("if (checking(claim.propertyId()) && waited < CHECK_WAIT_SECONDS) {");
        int later = admit.indexOf("runTaskLater(plugin, () -> admitWhenChecked(claim, c, captured, waited + 1), 20L); return;");
        int request = admit.indexOf("plugin.utilityPayments().requestAutoCare(");
        int hold = admit.indexOf("repository.hold(claim.claimId());");
        assertTrue(wait > 0 && later > wait && request > later && hold > request, "wait, then admit, then hold only if refused");
        assertEquals(1, runtime.split("requestAutoCare\\(", -1).length - 1, "one admission site");
        String checking = between(runtime, "private boolean checking(UUID property) {", "}");
        assertTrue(checking.contains("service != null && service.reconcileWriting(property)"));
    }

    @Test void paidRepairOrPurchaseInstallsItsRowBeforeReleasingTheLease() throws Exception {
        String runtime = source("economy/UtilityPaymentRuntime.java");
        String core = between(runtime, "Consumer<String> reply,\n                                com.prosperitysmp.npc.property.AutoCareRepository.Claim recurring",
                "/** Explicit current-day batch;");
        int reread = core.indexOf("reread.set(plugin.propertyService().readRow(property.propertyId())");
        int complete = core.indexOf("var result = complete(prepared, requestId, receipt);");
        int install = core.indexOf("plugin.propertyService().installLeased(List.of(property), rows, \"Property payment \" + requestId);");
        int release = core.indexOf("try { lease.releaseReconciled(); }\n                        catch (RuntimeException failureToRelease)");
        assertTrue(complete > 0 && reread > complete, "re-read after the purchase completion commits");
        assertTrue(install > reread && release > install, "installed on main before the lease is released");
    }

    @Test void healthAndScheduledWarningShowHomesWaitingOnABusyHome() throws Exception {
        assertTrue(source("command/NpcAdminCommand.java").contains("+ PropertyService.waitingOnBusy(waiting));"));
        assertTrue(source("command/NpcAdminCommand.java").contains("int waiting = ps.waitingOnBusyCount();"));
        assertTrue(source("ProsperityNpcPlugin.java").contains(
                "if (PropertyService.reconcileIncomplete(r)) getLogger().warning(PropertyService.reconcileIncompleteText(r));"));
        assertTrue(source("property/PropertyService.java").contains("if (reconciled(r)) lastReconcileMillis = System.currentTimeMillis();"));
    }
}

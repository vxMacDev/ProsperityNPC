package com.prosperitysmp.npc.property.guard;

import com.prosperitysmp.npc.api.GuardMode;
import com.prosperitysmp.npc.api.GuardResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PropertyGuardTest {

    @Test void aFailureInsideTheAvailabilityReadCannotReportReady() {
        var guard = PropertyGuard.waiting("x");
        guard.installed(GuardResult.installed(GuardMode.NONE), PropertyGuard.Identity.STRONG, () -> {
            guard.fail("failed during availability");
            return true;
        });
        guard.reconciled();
        guard.whenUnguarded("reread-before-payout");
        assertFalse(guard.ready());
        assertFalse(guard.payoutsAllowed());
    }

    @Test void armsWriteHookIsReadyAndAllowsPayoutsInEveryState() {
        var guard = PropertyGuard.writeHook();
        assertTrue(guard.ready());
        assertTrue(guard.payoutsAllowed());
        assertFalse(guard.needsStartupReconcile());
        assertEquals("guard write-hook installed", guard.describe(), "diagnose reads exactly as before");
        guard.whenUnguarded("hold-payouts");
        guard.fail("ignored for ARM");
        assertTrue(guard.ready(), "a third-party failure path never touches ARM's guard");
        assertTrue(guard.payoutsAllowed());
        assertEquals("", guard.payoutRefusal());
    }

    @Test void aBackendThatIsNotBoundYetHoldsEverything() {
        var guard = PropertyGuard.waiting("waiting for lands to bind at server startup");
        assertFalse(guard.ready());
        assertFalse(guard.payoutsAllowed());
        assertTrue(guard.describe().startsWith("guard unavailable (waiting for lands"));
    }

    @Test void guardNoneHoldsPayoutsByDefaultButAllowsDebits() {
        var guard = PropertyGuard.waiting("x");
        guard.installed(GuardResult.installed(GuardMode.NONE), PropertyGuard.Identity.STRONG, () -> true);
        guard.reconciled();
        assertTrue(guard.ready(), "debits and moves may start");
        assertFalse(guard.payoutsAllowed());
        assertEquals(PropertyGuard.PAYOUTS_HELD_REPLY, guard.payoutRefusal());
        assertEquals("Rent waits: the home plugin cannot block owner changes (property.when-unguarded=hold-payouts).",
                guard.payoutRefusal());
    }

    @Test void weakIdentityHoldsPayoutsEvenWithAVeto() {
        var guard = PropertyGuard.waiting("x");
        guard.installed(GuardResult.installed(GuardMode.VETO_EVENTS), PropertyGuard.Identity.WEAK, () -> true);
        guard.reconciled();
        assertFalse(guard.payoutsAllowed());
        guard.whenUnguarded("reread-before-payout");
        assertTrue(guard.payoutsAllowed(), "the opt-in allows them");
        guard.whenUnguarded("nonsense");
        assertFalse(guard.payoutsAllowed(), "an unknown value is the safe default");
        guard.whenUnguarded(null);
        assertFalse(guard.payoutsAllowed());
    }

    @Test void aStrongVetoAllowsPayoutsOnlyAfterTheStartupPass() {
        var guard = PropertyGuard.waiting("x");
        guard.installed(GuardResult.installed(GuardMode.VETO_EVENTS), PropertyGuard.Identity.STRONG, () -> true);
        assertTrue(guard.ready());
        assertTrue(guard.needsStartupReconcile());
        assertFalse(guard.payoutsAllowed(), "a payout before the startup pass is refused");
        assertEquals(PropertyGuard.NOT_RECONCILED_REPLY, guard.payoutRefusal());
        guard.whenUnguarded("reread-before-payout");
        assertFalse(guard.payoutsAllowed(), "the opt-in never skips the startup pass");
        guard.reconciled();
        assertTrue(guard.payoutsAllowed());
    }

    @Test void aFailureIsPermanentForTheRun() {
        var guard = PropertyGuard.waiting("x");
        guard.installed(GuardResult.installed(GuardMode.VETO_EVENTS), PropertyGuard.Identity.STRONG, () -> true);
        guard.reconciled();
        guard.fail("org.example.LandOwnerChangeEvent: NoSuchMethodError");
        assertFalse(guard.ready());
        assertFalse(guard.payoutsAllowed());
        guard.installed(GuardResult.installed(GuardMode.VETO_EVENTS), PropertyGuard.Identity.STRONG, () -> true);
        assertFalse(guard.ready(), "a later install cannot revive it");
        assertTrue(guard.reason().contains("NoSuchMethodError"));
    }

    @Test void aLostBackendIsNotReady() {
        boolean[] available = {true};
        var guard = PropertyGuard.waiting("x");
        guard.installed(GuardResult.installed(GuardMode.NONE), PropertyGuard.Identity.STRONG, () -> available[0]);
        assertTrue(guard.ready());
        available[0] = false;
        assertFalse(guard.ready());
        assertEquals("the backend is unavailable", guard.reason());
        var throwing = PropertyGuard.waiting("x");
        throwing.installed(GuardResult.installed(GuardMode.NONE), PropertyGuard.Identity.STRONG,
                () -> { throw new NoClassDefFoundError("vendor"); });
        assertFalse(throwing.ready());
    }

    @Test void anIgnoredWhenUnguardedValueGetsOneWarningNamingIt() {
        var guard = PropertyGuard.waiting("x");
        var warnings = new java.util.ArrayList<String>();
        guard.whenUnguarded("reread-before-payuot", warnings::add);
        assertEquals(java.util.List.of("property.when-unguarded 'reread-before-payuot' is not hold-payouts or "
                + "reread-before-payout; it is ignored and payouts follow hold-payouts."), warnings);
        assertEquals(PropertyGuard.WhenUnguarded.HOLD_PAYOUTS, guard.whenUnguarded());
        warnings.clear();
        guard.whenUnguarded(Boolean.TRUE, warnings::add);
        assertEquals(1, warnings.size(), "a value that is not text is ignored too");
        assertTrue(warnings.get(0).contains("'true'"));
        warnings.clear();
        guard.whenUnguarded("bad§c\nvalue" + "x".repeat(200), warnings::add);
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("'badcvalue" + "x".repeat(71) + "'"), "no control or section characters, 80 at most");
        warnings.clear();
        for (Object fine : new Object[] {null, "hold-payouts", " Reread-Before-Payout ", "reread-before-payout"})
            guard.whenUnguarded(fine, warnings::add);
        assertEquals(java.util.List.of(), warnings, "a missing or known value is not warned about");
        assertEquals(PropertyGuard.WhenUnguarded.REREAD_BEFORE_PAYOUT, guard.whenUnguarded());
    }

    @Test void aFailedInstallOrAClaimedWriteHookIsUnavailable() {
        var failed = PropertyGuard.waiting("x");
        failed.installed(GuardResult.failed("no vendor"), PropertyGuard.Identity.STRONG, () -> true);
        assertFalse(failed.ready());
        assertEquals("no vendor", failed.reason());
        var claimed = PropertyGuard.waiting("x");
        claimed.installed(GuardResult.installed(GuardMode.WRITE_HOOK), PropertyGuard.Identity.STRONG, () -> true);
        assertFalse(claimed.ready(), "only ARM's own hook is a write hook");
    }
}

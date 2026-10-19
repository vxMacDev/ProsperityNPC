package com.prosperitysmp.npc.property.guard;

import com.prosperitysmp.npc.api.GuardMode;
import com.prosperitysmp.npc.api.GuardResult;
import com.prosperitysmp.npc.property.guard.PropertyGuard.WhenUnguarded;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WhenUnguardedTest {
    @ParameterizedTest
    @ValueSource(strings = {"reread-before-payout", "REREAD-BEFORE-PAYOUT ", "  Reread-Before-Payout", "\treread-before-payout\n"})
    void theOptInIsReadIgnoringCaseAndSurroundingSpace(String value) {
        assertEquals(WhenUnguarded.REREAD_BEFORE_PAYOUT, WhenUnguarded.parse(value));
        assertTrue(WhenUnguarded.known(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"hold-payouts", "HOLD-PAYOUTS", " Hold-Payouts "})
    void theDefaultSpelledOutIsKnownAndHolds(String value) {
        assertEquals(WhenUnguarded.HOLD_PAYOUTS, WhenUnguarded.parse(value));
        assertTrue(WhenUnguarded.known(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "x", "reread_before_payout", "reread before payout", "rereadbeforepayout",
            "reread-before-payouts", "hold", "true", "REREAD_BEFORE_PAYOUT"})
    void anUnknownTextHoldsPayoutsAndIsReportedUnknown(String value) {
        assertEquals(WhenUnguarded.HOLD_PAYOUTS, WhenUnguarded.parse(value));
        assertFalse(WhenUnguarded.known(value));
    }

    @Test void aMissingSettingHoldsAndIsKnown() {
        assertEquals(WhenUnguarded.HOLD_PAYOUTS, WhenUnguarded.parse(null));
        assertTrue(WhenUnguarded.known(null));
    }

    @Test void aNonTextSettingHoldsAndIsUnknown() {
        for (Object value : List.of(5, true, 1.5, List.of("reread-before-payout"), WhenUnguarded.REREAD_BEFORE_PAYOUT)) {
            assertEquals(WhenUnguarded.HOLD_PAYOUTS, WhenUnguarded.parse(value), String.valueOf(value));
            assertFalse(WhenUnguarded.known(value), String.valueOf(value));
        }
    }

    @Test void aWarningShowsTheValueWithoutControlOrSectionCharacters() {
        var warnings = new ArrayList<String>();
        var guard = PropertyGuard.waiting("not bound");
        guard.whenUnguarded("re\u00a7cread\u0007\n", warnings::add);
        assertEquals(List.of("property.when-unguarded 'recread' is not hold-payouts or reread-before-payout; "
                + "it is ignored and payouts follow hold-payouts."), warnings);
        assertEquals(WhenUnguarded.HOLD_PAYOUTS, guard.whenUnguarded());
    }

    @Test void aWarningCutsALongValueToEightyCharacters() {
        var warnings = new ArrayList<String>();
        PropertyGuard.waiting("not bound").whenUnguarded("y".repeat(200), warnings::add);
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("'" + "y".repeat(80) + "'"), warnings.getFirst());
        assertFalse(warnings.getFirst().contains("y".repeat(81)));
    }

    @Test void aNonTextValueIsShownByItsText() {
        var warnings = new ArrayList<String>();
        PropertyGuard.waiting("not bound").whenUnguarded(42, warnings::add);
        assertEquals(List.of("property.when-unguarded '42' is not hold-payouts or reread-before-payout; "
                + "it is ignored and payouts follow hold-payouts."), warnings);
    }

    @Test void knownValuesGiveNoWarning() {
        var warnings = new ArrayList<String>();
        var guard = PropertyGuard.waiting("not bound");
        guard.whenUnguarded(null, warnings::add);
        guard.whenUnguarded("hold-payouts", warnings::add);
        guard.whenUnguarded("reread-before-payout", warnings::add);
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertEquals(WhenUnguarded.REREAD_BEFORE_PAYOUT, guard.whenUnguarded());
    }

    @Test void theOptInLetsANoneGuardPayOnlyAfterTheStartupPass() {
        var guard = PropertyGuard.waiting("waiting");
        guard.installed(GuardResult.installed(GuardMode.NONE), PropertyGuard.Identity.STRONG, () -> true);
        guard.whenUnguarded("reread-before-payout");
        assertFalse(guard.payoutsAllowed());
        assertEquals(PropertyGuard.NOT_RECONCILED_REPLY, guard.payoutRefusal());
        guard.reconciled();
        assertTrue(guard.payoutsAllowed());
        assertEquals("", guard.payoutRefusal());
        assertEquals("guard none installed; identity strong; payouts allowed", guard.describe());
        guard.whenUnguarded("nonsense");
        assertFalse(guard.payoutsAllowed(), "a reload to an unknown value falls back to holding");
        assertEquals(PropertyGuard.PAYOUTS_HELD_REPLY, guard.payoutRefusal());
        assertEquals("guard none installed; identity strong; payouts held (property.when-unguarded=hold-payouts)",
                guard.describe());
    }

    @Test void aNoneGuardBeforeTheStartupPassDescribesTheWait() {
        var guard = PropertyGuard.waiting("waiting");
        guard.installed(GuardResult.installed(GuardMode.NONE), PropertyGuard.Identity.WEAK, () -> true);
        assertTrue(guard.needsStartupReconcile());
        assertEquals("guard none installed; identity weak; payouts held until the startup ownership check reads every home",
                guard.describe());
    }

    @Test void aLostBackendNamesTheReasonInTheRefusal() {
        var guard = PropertyGuard.waiting("waiting");
        boolean[] up = {true};
        guard.installed(GuardResult.installed(GuardMode.VETO_EVENTS), PropertyGuard.Identity.STRONG, () -> up[0]);
        guard.reconciled();
        assertTrue(guard.payoutsAllowed());
        up[0] = false;
        assertEquals("the backend is unavailable", guard.reason());
        assertEquals("Rent waits: the home plugin is unavailable (the backend is unavailable).", guard.payoutRefusal());
        assertEquals("guard unavailable (the backend is unavailable; payments held)", guard.describe());
        assertTrue(guard.mode().isEmpty());
    }

    @Test void aBlankFailureReasonReadsBackendFailed() {
        var guard = PropertyGuard.waiting("waiting");
        guard.fail("  ");
        assertEquals("backend failed", guard.reason());
        guard.fail(null);
        assertEquals("backend failed", guard.reason());
    }

    @Test void armsGuardIgnoresFailures() {
        var guard = PropertyGuard.writeHook();
        guard.fail("lost");
        assertTrue(guard.ready());
        assertEquals("", guard.reason());
        assertFalse(guard.needsStartupReconcile());
        assertTrue(guard.isWriteHook());
    }
}

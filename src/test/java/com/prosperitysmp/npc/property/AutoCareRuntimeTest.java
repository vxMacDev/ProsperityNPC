package com.prosperitysmp.npc.property;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AutoCareRuntimeTest {
    private static AutoCareRepository.Consent consent(long property, int epoch, long revision, long ceiling) {
        return new AutoCareRepository.Consent(new UUID(0, property), new UUID(0, 2401), epoch, revision,
                true, ceiling, Set.of(AutoCareRepository.Action.UTILITY, AutoCareRepository.Action.CASH_REPAIR));
    }

    @Test void staleOnCallbackCannotUndoNewerOffIntake() {
        var fence = new AutoCareRuntime.ConsentFence();
        var saved = consent(1, 0, 3, 6000);
        long on = fence.revoke(saved.propertyId());
        long off = fence.revoke(saved.propertyId());
        assertFalse(fence.acknowledge(saved.propertyId(), on, saved));
        assertNull(fence.capture(saved));
        var disabled = new AutoCareRepository.Consent(saved.propertyId(), saved.ownerUuid(), 0, 4, false, 0, Set.of());
        assertTrue(fence.acknowledge(saved.propertyId(), off, disabled));
        assertNull(fence.capture(saved));
        assertNull(fence.capture(disabled));
    }

    @Test void offThenLowerCeilingOnNeverRestoresOldRevisionAuthority() {
        var fence = new AutoCareRuntime.ConsentFence();
        var original = consent(1, 0, 2, 6000);
        var oldAuthority = fence.capture(original); assertTrue(fence.permits(oldAuthority));
        fence.revoke(original.propertyId()); assertFalse(fence.permits(oldAuthority));
        long on = fence.revoke(original.propertyId());
        var lower = consent(1, 0, 4, 1000);
        assertTrue(fence.acknowledge(lower.propertyId(), on, lower));
        assertFalse(fence.permits(oldAuthority)); assertNull(fence.capture(original));
        assertTrue(fence.permits(fence.capture(lower)));
        long sameSettings = fence.revoke(lower.propertyId());
        var previous = new AutoCareRuntime.Authority(lower, on);
        assertTrue(fence.acknowledge(lower.propertyId(), sameSettings, lower));
        assertFalse(fence.permits(previous));
        assertTrue(fence.permits(fence.capture(lower)));
    }

    @Test void epochAndRevisionAreExactAndPropertyFencesAreIndependent() {
        var fence = new AutoCareRuntime.ConsentFence();
        var original = consent(1, 0, 2, 1000);
        var other = fence.capture(consent(2, 0, 2, 1000));
        fence.capture(original);
        assertNull(fence.capture(consent(1, 1, 2, 1000)));
        assertNull(fence.capture(consent(1, 0, 3, 1000)));
        long change = fence.revoke(original.propertyId());
        var nextEpoch = consent(1, 1, 0, 1000);
        assertTrue(fence.acknowledge(nextEpoch.propertyId(), change, nextEpoch));
        assertNull(fence.capture(original)); assertTrue(fence.permits(fence.capture(nextEpoch)));
        assertTrue(fence.permits(other));
        fence.clear(); assertFalse(fence.permits(other));
    }

    @Test void refusedCandidateAdvancesShortPageAndRemainingBudgetSkipsUnaffordableRepair() {
        var first = consent(1, 0, 0, 2000); var second = consent(2, 0, 0, 6000);
        var page = List.of(first, second);
        assertEquals(first.propertyId(), AutoCareRuntime.scanCursor(page, first));
        assertEquals(second.propertyId(), AutoCareRuntime.scanCursor(List.of(second), second));
        assertNull(AutoCareRuntime.scanCursor(List.of(), null));
        assertNull(AutoCareRuntime.scanCursor(page, null));
        var full = new ArrayList<AutoCareRepository.Consent>();
        for (int i = 1; i <= 16; i++) full.add(consent(i, 0, 0, 6000));
        assertEquals(full.getLast().propertyId(), AutoCareRuntime.scanCursor(full, null));
        long remaining = AutoCareRuntime.remainingBudget(2000, 100);
        assertEquals(1900, remaining); assertTrue(2000 > remaining); assertTrue(1000 <= remaining);
        assertEquals(0, AutoCareRuntime.remainingBudget(1000, 2000));
        assertEquals(1000, AutoCareRuntime.remainingBudget(1000, 0));
        assertThrows(IllegalArgumentException.class, () -> AutoCareRuntime.remainingBudget(1000, -1));
    }
}

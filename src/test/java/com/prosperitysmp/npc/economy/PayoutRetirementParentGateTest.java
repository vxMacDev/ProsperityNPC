package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.property.PropertyService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * economy.payout-retirement-enabled defaults on, so it must be inert with its parent (payments in use) off.
 * payoutRetirementActive is the one gate every retirement entry point uses: the daily-cycle pass, the in-batch
 * pass and resume-time retirement. Pure: no Bukkit, no database.
 */
class PayoutRetirementParentGateTest {
    private static final UUID HOME = new UUID(0, 4401);
    private static final LocalDate DAY = LocalDate.of(2026, 9, 8);

    @Test
    void activeOnlyWithTheFlagBothSwitchesAndPaymentsOptedIn() {
        // Positive control: every gate on.
        assertTrue(UtilityPaymentRuntime.payoutRetirementActive(Boolean.TRUE, true, true, Boolean.TRUE, null));
        assertTrue(UtilityPaymentRuntime.payoutRetirementActive(Boolean.TRUE, true, true, null, Boolean.TRUE),
                "payments-enabled unset falls back to the legacy smoke opt-in, as ready() does");
        assertTrue(UtilityPaymentRuntime.payoutRetirementActive(Boolean.TRUE, true, true, Boolean.TRUE, Boolean.FALSE));
        // Flag off in any form.
        for (Object flag : new Object[]{null, Boolean.FALSE, "true", 1})
            assertFalse(UtilityPaymentRuntime.payoutRetirementActive(flag, true, true, Boolean.TRUE, null));
        // Parent off: payments off (the shipped default), unset with smoke off or unset, either switch off.
        assertFalse(UtilityPaymentRuntime.payoutRetirementActive(Boolean.TRUE, true, true, Boolean.FALSE, null));
        assertFalse(UtilityPaymentRuntime.payoutRetirementActive(Boolean.TRUE, true, true, Boolean.FALSE, Boolean.TRUE),
                "an explicit payments-enabled: false wins over the legacy flag");
        assertFalse(UtilityPaymentRuntime.payoutRetirementActive(Boolean.TRUE, true, true, null, Boolean.FALSE));
        assertFalse(UtilityPaymentRuntime.payoutRetirementActive(Boolean.TRUE, true, true, null, null));
        assertFalse(UtilityPaymentRuntime.payoutRetirementActive(Boolean.TRUE, true, true, "true", null));
        assertFalse(UtilityPaymentRuntime.payoutRetirementActive(Boolean.TRUE, false, true, Boolean.TRUE, null));
        assertFalse(UtilityPaymentRuntime.payoutRetirementActive(Boolean.TRUE, true, false, Boolean.TRUE, null));
    }

    @Test
    void resumeRetirementIsNullWithTheParentOffEvenWithTheFlagOn() {
        assertNull(PropertyService.resumeRetirement(Boolean.TRUE, false, HOME, DAY, line -> fail(line)));
        assertNull(PropertyService.resumeRetirement(Boolean.FALSE, true, HOME, DAY, line -> fail(line)));
        assertNull(PropertyService.resumeRetirement(null, false, HOME, DAY, line -> fail(line)));
        // Positive control: flag and parent on yields a preparation.
        assertNotNull(PropertyService.resumeRetirement(Boolean.TRUE, true, HOME, DAY, line -> { }));
    }
}

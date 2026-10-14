package com.prosperitysmp.npc.economy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PropertyPaymentSettingsTest {
    @Test void explicitReleaseGateOverridesLegacyAndNeverParsesTruthyStrings() {
        assertFalse(UtilityPaymentRuntime.paymentOptIn(null, null));
        assertTrue(UtilityPaymentRuntime.paymentOptIn(null, true));
        assertFalse(UtilityPaymentRuntime.paymentOptIn(false, true));
        assertFalse(UtilityPaymentRuntime.paymentOptIn("true", true));
        assertTrue(UtilityPaymentRuntime.paymentOptIn(true, false));
    }
}

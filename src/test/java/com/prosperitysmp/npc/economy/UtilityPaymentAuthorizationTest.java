package com.prosperitysmp.npc.economy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UtilityPaymentAuthorizationTest {
    @Test void disconnectRevokesNewDebitButNotCompletionOfAcknowledgedDebit() {
        assertFalse(UtilityPaymentRuntime.actorAuthorization(TransferProof.Leg.DEBIT, () -> false));
        assertTrue(UtilityPaymentRuntime.actorAuthorization(TransferProof.Leg.DEBIT, () -> true));
        assertTrue(UtilityPaymentRuntime.actorAuthorization(TransferProof.Leg.CREDIT, () -> {
            fail("Credit must not depend on the departed player's session"); return false;
        }));
    }
}

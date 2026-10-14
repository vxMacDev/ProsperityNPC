package com.prosperitysmp.npc.economy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The retired API must be inert even without a working database or configuration. */
class LegacyBridgeHoldTest {
    @SuppressWarnings("deprecation")
    @Test
    void heartbeatCannotReleaseLegacyClaimsOrBooleanAcknowledgements() {
        BridgeApi bridge = new BridgeApi(null, null);
        bridge.heartbeat();
        assertTrue(bridge.isBridgeConnected());
        assertFalse(bridge.supportsConfirmedTransfers());
        for (int size : new int[]{Integer.MIN_VALUE, 0, 1, 20, Integer.MAX_VALUE}) {
            assertTrue(bridge.claimBatch(size).isEmpty());
        }
        for (long id : new long[]{-1, 0, 1, Long.MAX_VALUE}) {
            for (long amount : new long[]{-1, 0, 1, 10_000, Long.MAX_VALUE}) {
                assertFalse(bridge.finalizeEvent(id, true, amount));
                assertFalse(bridge.finalizeEvent(id, false, amount));
            }
        }
    }
}

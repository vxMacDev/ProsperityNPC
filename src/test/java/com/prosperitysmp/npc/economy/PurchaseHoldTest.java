package com.prosperitysmp.npc.economy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PurchaseHoldTest {
    @Test
    void constructionHasNoPollingOrReceiptRead() {
        // No Bukkit server or plugin is supplied: construction/hold must not consult either.
        try (PurchaseService service = new PurchaseService(null, request -> {
            fail("Construction or admission must not start even a receipt read");
            return java.util.Optional.empty();
        })) {
            BridgeApi bridge = new BridgeApi(null, null);
            bridge.heartbeat();
            assertTrue(bridge.isBridgeConnected());
        }
    }
}

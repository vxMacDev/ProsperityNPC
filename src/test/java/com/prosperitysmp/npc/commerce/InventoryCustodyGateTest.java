package com.prosperitysmp.npc.commerce;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** One inventory mutation per player, shared by deliveries and item repairs. */
class InventoryCustodyGateTest {
    @Test void onlyOneHolderPerPlayerAndOnlyTheHolderReleases() {
        var gate = new InventoryCustodyGate(() -> true);
        UUID player = new UUID(3, 1);
        Object delivery = new Object(), repair = new Object();
        assertTrue(gate.tryHold(player, delivery));
        assertFalse(gate.tryHold(player, repair), "a second mutation for the same player waits");
        assertTrue(gate.holds(player, delivery));
        assertFalse(gate.release(player, repair), "another runtime cannot release this hold");
        assertTrue(gate.held(player));
        assertTrue(gate.release(player, delivery));
        assertTrue(gate.tryHold(player, repair), "free again after release");
    }

    @Test void boundedAndMainThreadOnly() {
        var gate = new InventoryCustodyGate(() -> true);
        for (int i = 0; i < InventoryCustodyGate.LIMIT; i++) assertTrue(gate.tryHold(new UUID(4, i), new Object()));
        assertFalse(gate.tryHold(new UUID(5, 0), new Object()));
        assertEquals(InventoryCustodyGate.LIMIT, gate.size());
        assertThrows(IllegalStateException.class, () -> new InventoryCustodyGate(() -> false).tryHold(new UUID(6, 0), new Object()));
    }

    @Test void commerceGatingIsUnchangedAndReturnsNeedOnlySystemAndDatabase() {
        boolean[] flags = {false, true};
        for (boolean system : flags) for (boolean economy : flags) for (boolean commerce : flags) for (boolean db : flags) {
            boolean old = system && economy && commerce; // the dev.58 delivery gate
            assertEquals(old, ItemDeliveryRuntime.sourceAllowed(ItemCustody.Source.WORKER_OUTPUT, system, economy, commerce, db));
            assertEquals(old, ItemDeliveryRuntime.sourceAllowed(ItemCustody.Source.MERCHANT_GOODS, system, economy, commerce, db));
            assertEquals(system && db, ItemDeliveryRuntime.sourceAllowed(ItemCustody.Source.REPAIR_RETURN, system, economy, commerce, db));
        }
    }
}

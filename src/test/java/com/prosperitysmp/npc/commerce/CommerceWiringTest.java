package com.prosperitysmp.npc.commerce;

import com.prosperitysmp.npc.commerce.CommerceWiring.Part;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Commerce with item repairs off behaves as dev.58. The oracles below are the dev.58 ProsperityNpcPlugin logic,
 * read from the dev.58 source and checked against the dev.58 jar's bytecode: commerceStatus(), the delivery gate
 * lambda in initCommerce, and the commerce block of onDisable.
 */
class CommerceWiringTest {
    private static final boolean[] FLAGS = {false, true};

    /** dev.58: commercePayments == null || commerce == null || itemDelivery == null ? "HELD_RUNTIME" : status + ... */
    private static String dev58Status(boolean payments, boolean commerce, boolean delivery, String status, boolean ready) {
        return !payments || !commerce || !delivery ? "HELD_RUNTIME" : status + "; item custody " + (ready ? "READY" : "STARTUP_PENDING");
    }

    @Test void statusIsTheDev58TextForEveryCombination() {
        for (boolean payments : FLAGS) for (boolean commerce : FLAGS) for (boolean delivery : FLAGS) for (boolean ready : FLAGS)
            for (String status : List.of("READY", "HELD_PAYMENTS", "")) {
                int[] reads = new int[2];
                String actual = CommerceWiring.commerceStatus(payments, commerce, delivery,
                        () -> { reads[0]++; return status; }, () -> { reads[1]++; return ready; });
                assertEquals(dev58Status(payments, commerce, delivery, status, ready), actual);
                int expectedReads = payments && commerce && delivery ? 1 : 0;
                assertArrayEquals(new int[]{expectedReads, expectedReads}, reads, "status and readiness are read only when all three exist");
            }
        // Literal pins of the exact strings, independent of the oracle above.
        assertEquals("HELD_RUNTIME", CommerceWiring.commerceStatus(true, true, false, () -> "x", () -> true));
        assertEquals("OPEN; item custody READY", CommerceWiring.commerceStatus(true, true, true, () -> "OPEN", () -> true));
        assertEquals("OPEN; item custody STARTUP_PENDING", CommerceWiring.commerceStatus(true, true, true, () -> "OPEN", () -> false));
    }

    @Test void commerceGoodsUseTheDev58GateAndReadItLive() {
        for (boolean system : FLAGS) for (boolean economy : FLAGS) for (boolean commerce : FLAGS) for (boolean db : FLAGS) {
            boolean[] dbRead = {false};
            var gate = CommerceWiring.deliveryGate(() -> system, () -> economy, () -> commerce, () -> { dbRead[0] = true; return db; });
            boolean dev58 = system && economy && commerce;
            assertEquals(dev58, gate.test(ItemCustody.Source.WORKER_OUTPUT));
            assertEquals(dev58, gate.test(ItemCustody.Source.MERCHANT_GOODS));
            assertFalse(dbRead[0], "commerce goods never read the database switch, as in dev.58");
            assertEquals(system && db, gate.test(ItemCustody.Source.REPAIR_RETURN));
            assertEquals(dev58, ItemDeliveryRuntime.sourceAllowed(ItemCustody.Source.MERCHANT_GOODS, system, economy, commerce, db));
            assertEquals(system && db, ItemDeliveryRuntime.sourceAllowed(ItemCustody.Source.REPAIR_RETURN, system, economy, commerce, db));
        }
        // dev.58 short-circuits left to right; a later switch is not read once an earlier one is off.
        int[] reads = new int[3];
        var gate = CommerceWiring.deliveryGate(() -> { reads[0]++; return false; }, () -> { reads[1]++; return true; },
                () -> { reads[2]++; return true; }, () -> true);
        assertFalse(gate.test(ItemCustody.Source.MERCHANT_GOODS));
        assertArrayEquals(new int[]{1, 0, 0}, reads);
        // Live: a switch flipped after the gate was built takes effect on the next delivery.
        boolean[] commerceOn = {false};
        var live = CommerceWiring.deliveryGate(() -> true, () -> true, () -> commerceOn[0], () -> true);
        assertFalse(live.test(ItemCustody.Source.WORKER_OUTPUT));
        commerceOn[0] = true;
        assertTrue(live.test(ItemCustody.Source.WORKER_OUTPUT));
    }

    @Test void closeOrderWithoutItemRepairsIsDev58() {
        for (boolean commerce : FLAGS) for (boolean payments : FLAGS) for (boolean delivery : FLAGS) {
            // dev.58 onDisable: commerce, then commercePayments, then itemDelivery, each only if it exists.
            var dev58 = new ArrayList<Part>();
            if (commerce) dev58.add(Part.COMMERCE);
            if (payments) dev58.add(Part.COMMERCE_PAYMENTS);
            if (delivery) dev58.add(Part.ITEM_DELIVERY);
            assertEquals(dev58, CommerceWiring.closeOrder(commerce, payments, false, delivery));
        }
        assertEquals(List.of(Part.COMMERCE, Part.COMMERCE_PAYMENTS, Part.ITEM_REPAIRS, Part.ITEM_DELIVERY),
                CommerceWiring.closeOrder(true, true, true, true), "item repairs close before the delivery their returns use");
        assertEquals(List.of(Part.ITEM_REPAIRS, Part.ITEM_DELIVERY), CommerceWiring.closeOrder(false, false, true, true),
                "commerce held, returns still running");
    }
}

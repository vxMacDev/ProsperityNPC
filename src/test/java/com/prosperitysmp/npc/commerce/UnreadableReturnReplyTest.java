package com.prosperitysmp.npc.commerce;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A return whose items cannot be read hears HELD only when the database holds it; anything else held nothing. */
class UnreadableReturnReplyTest {
    @Test void heldOnlyWhenTheRowIsHeld() {
        for (var state : ItemCustodyRepository.State.values())
            assertEquals(state == ItemCustodyRepository.State.AMBIGUOUS ? ItemDeliveryRuntime.Result.HELD : ItemDeliveryRuntime.Result.NOT_READY,
                    ItemDeliveryRuntime.unreadableReply(state), state.name());
    }

    /** A return whose start failed began nothing only when its row reads back as RESERVED. Any other state, or no row, is held. */
    @Test void aFailedStartBeganNothingOnlyWhenTheRowIsStillReserved() {
        var request = new ItemCustody(new java.util.UUID(3, 3), new java.util.UUID(5, 5), new java.util.UUID(4, 4),
                ItemCustody.Source.REPAIR_RETURN, 64, new CustodyBytes(new byte[]{1}), java.time.Instant.EPOCH,
                java.time.Instant.EPOCH.plusSeconds(900));
        for (var state : ItemCustodyRepository.State.values()) {
            boolean started = state == ItemCustodyRepository.State.DELIVERY_INTENT || state == ItemCustodyRepository.State.DELIVERED
                    || state == ItemCustodyRepository.State.AMBIGUOUS || state == ItemCustodyRepository.State.RECONCILED_NOT_DELIVERED;
            var row = new ItemCustodyRepository.Receipt(request, state, started ? new java.util.UUID(1, 1) : null, null);
            assertEquals(state == ItemCustodyRepository.State.RESERVED, ItemDeliveryRuntime.nothingStarted(java.util.Optional.of(row)), state.name());
        }
        assertFalse(ItemDeliveryRuntime.nothingStarted(java.util.Optional.empty()));
    }

    /** After the player left only a return's caller is told, so its claim clears and a held alert goes out. Commerce as dev.58. */
    @Test void onlyAReturnCallerIsToldAfterThePlayerLeft() {
        for (var source : ItemCustody.Source.values()) {
            assertTrue(ItemDeliveryRuntime.tellsCaller(false, source), source.name());
            assertEquals(source == ItemCustody.Source.REPAIR_RETURN, ItemDeliveryRuntime.tellsCaller(true, source), source.name());
        }
    }
}

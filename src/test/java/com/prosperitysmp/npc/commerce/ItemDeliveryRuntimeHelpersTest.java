package com.prosperitysmp.npc.commerce;

import com.prosperitysmp.npc.commerce.ItemCustodyRepository.Receipt;
import com.prosperitysmp.npc.commerce.ItemCustodyRepository.State;
import com.prosperitysmp.npc.commerce.ItemDeliveryRuntime.Result;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** The pure decisions of a marked order delivery (town.commerce.delivery-save-marker), without a server. */
class ItemDeliveryRuntimeHelpersTest {
    private static final UUID NONCE = new UUID(8, 1), OTHER = new UUID(8, 2);

    private static Receipt row(UUID nonce, State state) {
        var request = new ItemCustody(nonce, new UUID(5, 5), new UUID(4, 4), ItemCustody.Source.MERCHANT_GOODS, 8,
                new CustodyBytes(new byte[]{1}), Instant.EPOCH, Instant.EPOCH.plusSeconds(900));
        boolean started = state == State.DELIVERY_INTENT || state == State.DELIVERED || state == State.AMBIGUOUS
                || state == State.RECONCILED_NOT_DELIVERED;
        return new Receipt(request, state, started ? new UUID(1, 1) : null, null);
    }

    @Test void markedNeverReadsTheFlagForAReturn() {
        assertTrue(ItemDeliveryRuntime.marked(ItemCustody.Source.REPAIR_RETURN, () -> { throw new AssertionError("read the key"); }),
                "a return is always marked and never reads the commerce key");
        for (var source : new ItemCustody.Source[] {ItemCustody.Source.MERCHANT_GOODS, ItemCustody.Source.WORKER_OUTPUT}) {
            int[] reads = {0};
            assertTrue(ItemDeliveryRuntime.marked(source, () -> { reads[0]++; return true; }), source.name());
            assertFalse(ItemDeliveryRuntime.marked(source, () -> { reads[0]++; return false; }), "flag off: " + source);
            assertEquals(2, reads[0], "commerce reads the key once per call");
        }
    }

    @Test void preflightReplyMirrorsANonNewStart() {
        assertNull(ItemDeliveryRuntime.preflightReply(Optional.empty(), NONCE), "nothing refused: the save may go ahead");
        assertEquals(Result.DELIVERED, ItemDeliveryRuntime.preflightReply(Optional.of(row(NONCE, State.DELIVERED)), NONCE),
                "a replay of this delivered order");
        // RESERVED here is this row refused because another delivery for the player is running or held.
        for (State state : new State[] {State.RESERVED, State.DELIVERY_INTENT, State.AMBIGUOUS, State.RECONCILED_NOT_DELIVERED,
                State.CANCELLED, State.EXPIRED})
            assertEquals(Result.HELD, ItemDeliveryRuntime.preflightReply(Optional.of(row(NONCE, state)), NONCE), state.name());
        assertEquals(Result.HELD, ItemDeliveryRuntime.preflightReply(Optional.of(row(OTHER, State.DELIVERED)), NONCE),
                "another nonce's delivered row is not this order's receipt");
        // The same mapping dispatch gives a start that was not new.
        for (State state : State.values())
            assertEquals(state == State.DELIVERED ? Result.DELIVERED : Result.HELD,
                    ItemDeliveryRuntime.preflightReply(Optional.of(row(NONCE, state)), NONCE), state.name());
    }

    @Test void startRefusalRefusesWithNothingStarted() {
        assertNull(ItemDeliveryRuntime.startRefusal(true, true, Boolean.TRUE), "the start goes ahead");
        for (boolean same : new boolean[] {false, true})
            for (Boolean receivable : new Boolean[] {null, Boolean.FALSE, Boolean.TRUE})
                assertEquals(Result.UNAUTHORIZED, ItemDeliveryRuntime.startRefusal(false, same, receivable), "lost authority or dead");
        for (Boolean receivable : new Boolean[] {null, Boolean.FALSE, Boolean.TRUE})
            assertEquals(Result.NOT_READY, ItemDeliveryRuntime.startRefusal(true, false, receivable), "the items changed since the save");
        assertEquals(Result.NO_SPACE, ItemDeliveryRuntime.startRefusal(true, true, Boolean.FALSE));
        assertEquals(Result.NOT_READY, ItemDeliveryRuntime.startRefusal(true, true, null), "the space check threw");
        for (boolean alive : new boolean[] {false, true}) for (boolean same : new boolean[] {false, true})
            for (Boolean receivable : new Boolean[] {null, Boolean.FALSE, Boolean.TRUE}) {
                Result refused = ItemDeliveryRuntime.startRefusal(alive, same, receivable);
                assertNotEquals(Result.HELD, refused, "nothing started, so nothing is held");
                assertNotEquals(Result.DELIVERED, refused);
            }
    }

    @Test void onlyAMarkedCommerceDeliveryWritesMarkerConfirmed() {
        assertEquals("MARKER_CONFIRMED", ItemDeliveryRuntime.confirmedOutcome(ItemCustody.Source.MERCHANT_GOODS, true));
        assertEquals("MARKER_CONFIRMED", ItemDeliveryRuntime.confirmedOutcome(ItemCustody.Source.WORKER_OUTPUT, true));
        assertEquals("INVENTORY_CONFIRMED", ItemDeliveryRuntime.confirmedOutcome(ItemCustody.Source.MERCHANT_GOODS, false),
                "unmarked commerce records INVENTORY_CONFIRMED");
        assertEquals("INVENTORY_CONFIRMED", ItemDeliveryRuntime.confirmedOutcome(ItemCustody.Source.WORKER_OUTPUT, false));
        assertEquals("INVENTORY_CONFIRMED", ItemDeliveryRuntime.confirmedOutcome(ItemCustody.Source.REPAIR_RETURN, true),
                "a return records INVENTORY_CONFIRMED");
        assertEquals("INVENTORY_CONFIRMED", ItemDeliveryRuntime.confirmedOutcome(ItemCustody.Source.REPAIR_RETURN, false));
    }
}

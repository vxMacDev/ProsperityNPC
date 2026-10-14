package com.prosperitysmp.npc.interaction;

import com.prosperitysmp.npc.commerce.CommerceOrder;
import com.prosperitysmp.npc.commerce.ItemCustody;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommerceActionTest {
    @Test void orderActionsBindTheWholeServerQuoteNotClientAmountOrMaterial() {
        UUID nonce = UUID.randomUUID(), player = UUID.randomUUID(), resident = UUID.randomUUID();
        LocalDate day = LocalDate.of(2026, 9, 29);
        var quote = new CommerceOrder(CommerceOrder.idFor(nonce, player, ItemCustody.Source.MERCHANT_GOODS, day),
                nonce, player, resident, ItemCustody.Source.MERCHANT_GOODS, "supplies-stone", "STONE", 32, 1444,
                day, Instant.parse("2026-09-29T22:00:00Z"), 0);
        var action = new ResidentAction(ResidentAction.Kind.ORDER, quote.catalogueId(), quote.orderId(), resident, "Buy", quote);
        assertSame(quote, action.order());
        assertThrows(IllegalArgumentException.class, () -> new ResidentAction(ResidentAction.Kind.ORDER,
                quote.catalogueId(), quote.orderId(), UUID.randomUUID(), "Buy", quote));
        assertThrows(IllegalArgumentException.class, () -> new ResidentAction(ResidentAction.Kind.ORDER,
                "work-stone", quote.orderId(), resident, "Buy", quote));
        assertThrows(IllegalArgumentException.class, () -> new ResidentAction(ResidentAction.Kind.ORDER,
                quote.catalogueId(), quote.orderId(), resident, "Buy"));
        assertThrows(IllegalArgumentException.class, () -> new ResidentAction(ResidentAction.Kind.COLLECT,
                null, quote.orderId(), resident, "Collect", quote));
        assertNull(new ResidentAction(ResidentAction.Kind.COLLECT, null, quote.orderId(), resident, "Collect").order());
    }
}

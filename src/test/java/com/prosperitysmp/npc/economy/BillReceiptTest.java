package com.prosperitysmp.npc.economy;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class BillReceiptTest {
    private static BillPayments.Receipt receipt(BillPayments.State state) {
        return new BillPayments.Receipt(new UUID(0, 1), new UUID(0, 2), new UUID(0, 3), 0,
                LocalDate.of(2026, 10, 17), 525, new UUID(0, 4), 1, 1, state,
                state == BillPayments.State.PAID ? "TRANSFER_CONFIRMED" : null,
                state == BillPayments.State.PAID ? Instant.parse("2026-10-17T12:00:00Z") : null);
    }

    @Test void receiptTextUsesTheFrozenAmountAndFullReference() {
        var receipt = receipt(BillPayments.State.PAID);
        assertEquals(List.of("Bill for 2026-10-17: $5.25.", "Bill paid.", "Bill: " + receipt.invoiceId(),
                "/property receipt " + receipt.requestId()), PurchaseService.billLines(receipt));
    }

    @Test void pendingAndHeldReceiptsNeverClaimNoMoneyMovedOrInviteAnotherPayment() {
        for (var state : List.of(BillPayments.State.WAITING, BillPayments.State.REVIEW_REQUIRED)) {
            String text = String.join(" ", PurchaseService.billLines(receipt(state)));
            assertTrue(text.contains("Do not pay again"));
            assertFalse(text.contains("Bill paid."));
            assertFalse(text.contains("No money moved"));
        }
        assertTrue(PurchaseService.billLines(receipt(BillPayments.State.DECLINED)).get(1).contains("Check the bill"));
    }

    @Test void aReceiptReaderHasNoPaymentWriterAndConstructionDoesNotPoll() {
        var methods = PurchaseService.BillReceiptReader.class.getDeclaredMethods();
        assertEquals(1, methods.length);
        assertEquals("find", methods[0].getName());
        assertArrayEquals(new Class<?>[]{UUID.class, UUID.class}, methods[0].getParameterTypes());
        try (var service = new PurchaseService(null,
                request -> { fail("constructor read purchases"); return java.util.Optional.empty(); },
                (request, payer) -> { fail("constructor read item repairs"); return java.util.Optional.empty(); },
                (request, payer) -> { fail("constructor read bills"); return java.util.Optional.empty(); })) {
            assertNotNull(service);
        }
    }
}

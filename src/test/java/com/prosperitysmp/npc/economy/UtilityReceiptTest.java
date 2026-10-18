package com.prosperitysmp.npc.economy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UtilityReceiptTest {
    private static UtilityRepository.Receipt receipt(UtilityRepository.State state) {
        var bill = new UtilityBill(new UUID(0, 1), new UUID(0, 2), 0, 0,
                LocalDate.of(2026, 10, 18), 2, 0, 0, Instant.parse("2026-10-18T12:00:00Z"),
                "UTC", 4, new UtilityPrices(1777, 0, 0));
        return new UtilityRepository.Receipt(bill, new UUID(0, 3), 1, 1, state,
                state == UtilityRepository.State.PAID ? "TRANSFER_CONFIRMED" : null,
                state == UtilityRepository.State.PAID ? Instant.parse("2026-10-18T12:00:01Z") : null);
    }

    @Test void paidTextKeepsOriginalDayPriceAndReference() {
        var paid = receipt(UtilityRepository.State.PAID);
        assertEquals(List.of("Utility bill for 2026-10-18: $17.77.", "Utility bill paid.",
                "/property receipt " + paid.requestId()), PurchaseService.utilityLines(paid));
    }

    @Test void pendingAndReviewNeverClaimSuccessOrInviteAnotherPayment() {
        for (var state : List.of(UtilityRepository.State.WAITING, UtilityRepository.State.REVIEW_REQUIRED)) {
            String text = String.join(" ", PurchaseService.utilityLines(receipt(state)));
            assertTrue(text.contains("Do not pay again"));
            assertFalse(text.contains("Utility bill paid."));
            assertFalse(text.contains("No money moved"));
        }
    }

    @Test void readCapabilityHasNoWriterAndConstructionDoesNotQuery() {
        var methods = PurchaseService.UtilityReceiptReader.class.getDeclaredMethods();
        assertEquals(1, methods.length);
        assertEquals("find", methods[0].getName());
        assertArrayEquals(new Class<?>[]{UUID.class, UUID.class}, methods[0].getParameterTypes());
        try (var service = new PurchaseService(null, request -> Optional.empty(),
                (request, payer) -> Optional.empty(), (request, payer) -> Optional.empty(),
                (request, payer) -> { fail("constructor read utilities"); return Optional.empty(); })) {
            assertNotNull(service);
        }
    }
}

package com.prosperitysmp.npc.interaction;

import com.prosperitysmp.npc.charge.BillText;
import com.prosperitysmp.npc.charge.ChargeInvoiceRepository;
import com.prosperitysmp.npc.economy.BillPayments;
import com.prosperitysmp.npc.property.OwnerDeskData;
import com.prosperitysmp.npc.property.Property;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic display records; native payment and real client checks are separate. */
class BillDeskTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 17);
    private static final UUID HOME = new UUID(0, 1), OWNER = new UUID(0, 2), REQUEST = new UUID(0, 3);

    private ChargeInvoiceRepository.Invoice invoice(int index, ChargeInvoiceRepository.State state) {
        var lines = new ArrayList<ChargeInvoiceRepository.Line>();
        for (int i = 1; i <= 16; i++) lines.add(new ChargeInvoiceRepository.Line(i, "bill-" + i, 1,
                String.format("%064x", i), "flat", 1L, null, null, "{}", 200, 100, true));
        return new ChargeInvoiceRepository.Invoice(new UUID(1, index), HOME, DAY.minusDays(index), OWNER, OWNER, 0,
                1, Instant.parse("2026-10-17T12:00:00Z"), DAY.plusDays(90), 1600, state, "a".repeat(64), List.copyOf(lines));
    }
    private OwnerDeskController.BillPage page(List<ChargeInvoiceRepository.Invoice> invoices,
                                              Map<UUID, ChargeInvoiceRepository.PaymentRef> payments) {
        var titles = new HashMap<String, String>();
        for (int i = 1; i <= 16; i++) titles.put(String.format("%064x", i), "A".repeat(32));
        return new OwnerDeskController.BillPage(new ChargeInvoiceRepository.OwnerView(invoices, titles, true, payments), DAY, true, 600);
    }

    @Test void fullPageFitsNativeButtonsWithBothDirectionsUtilityAndBack() {
        var invoices = new ArrayList<ChargeInvoiceRepository.Invoice>();
        for (int i = 0; i < 8; i++) invoices.add(invoice(i, ChargeInvoiceRepository.State.ISSUED));
        var buttons = BedrockNpcUi.billButtons(page(invoices, Map.of()));
        assertEquals(12, buttons.labels().size());
        assertEquals(List.of(9, 10, 11, 12, 13, 14, 15, 16, 30, 32, 42, 34), buttons.slots());
        assertEquals(12, new HashSet<>(buttons.slots()).size());
        assertTrue(buttons.labels().contains("Pay utility up to $6.00"));
        invoices.add(invoice(8, ChargeInvoiceRepository.State.ISSUED));
        assertThrows(IllegalArgumentException.class, () -> page(invoices, Map.of()));
    }

    @Test void confirmationShowsFrozenLinesMaximumAndReceiptWithinNativeBodyLimit() {
        var invoice = invoice(0, ChargeInvoiceRepository.State.ISSUED);
        var ref = new ChargeInvoiceRepository.PaymentRef(REQUEST, BillPayments.State.DECLINED);
        var page = page(List.of(invoice), Map.of(invoice.invoiceId(), ref));
        String body = String.join("\n", OwnerDeskMenu.billLines(page, invoice));
        assertTrue(body.length() <= 4096);
        assertTrue(body.contains(invoice.invoiceId().toString()));
        assertTrue(body.contains("Total: $16.00"));
        assertTrue(body.contains("/property receipt " + REQUEST));
        assertEquals(16, body.lines().filter(line -> line.contains("(capped at its maximum)")).count());
        var buttons = BedrockNpcUi.billConfirmButtons(page, invoice);
        assertEquals(List.of("Pay bill up to $16.00", "Read payment receipt", "Back to bills"), buttons.labels());
        assertEquals(List.of(32, 38, 34), buttons.slots());
        assertEquals("/property bills pay " + invoice.invoiceId() + " 16.00", BillText.payCommand(invoice));
    }

    @Test void pendingAndHeldAttemptsHaveNoPaymentButtonOrPayableClaim() {
        var invoice = invoice(0, ChargeInvoiceRepository.State.ISSUED);
        for (var state : List.of(BillPayments.State.WAITING, BillPayments.State.REVIEW_REQUIRED)) {
            var ref = new ChargeInvoiceRepository.PaymentRef(REQUEST, state);
            var page = page(List.of(invoice), Map.of(invoice.invoiceId(), ref));
            assertFalse(BedrockNpcUi.billConfirmButtons(page, invoice).slots().contains(32));
            String body = String.join(" ", OwnerDeskMenu.billLines(page, invoice));
            assertTrue(body.contains("Do not pay again"));
            assertFalse(body.contains("can be paid"));
            assertFalse(BillText.payable(invoice, ref, DAY));
        }
        for (var state : List.of(ChargeInvoiceRepository.State.PAID, ChargeInvoiceRepository.State.EXPIRED)) {
            var closed = invoice(0, state);
            assertFalse(BedrockNpcUi.billConfirmButtons(page(List.of(closed), Map.of()), closed).slots().contains(32));
        }
        assertFalse(BillText.payable(invoice, null, DAY.minusDays(1)));
        assertFalse(BillText.payable(invoice, null, DAY.plusDays(91)));
    }

    @Test void theBillPageItselfSaysWhichBillsPauseRentOnlyUnderHoldRent() {
        var held = invoice(1, ChargeInvoiceRepository.State.ISSUED);
        var view = new ChargeInvoiceRepository.OwnerView(List.of(held), Map.of());
        var off = new OwnerDeskController.BillPage(view, DAY, false, 100);
        assertFalse(OwnerDeskMenu.billPageBody(off).contains("Rent is paused"));
        var on = new OwnerDeskController.BillPage(view.withHoldRent(true), DAY, false, 100);
        assertTrue(OwnerDeskMenu.billPageBody(on).contains("Bill from 2026-10-16: Rent is paused for this home until this "
                + "bill is paid or expires on 2027-01-16."), OwnerDeskMenu.billPageBody(on));
        assertTrue(OwnerDeskMenu.billPageBody(on).length() <= 4096);
    }

    @Test void theDeskNoLongerRepeatsThePauseAsAChatNoteOnEveryLoad() throws Exception {
        String desk = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/prosperitysmp/npc/interaction/OwnerDeskController.java"));
        assertFalse(desk.contains("rentPausedNotes"), "the bill page carries the line; no chat note");
    }

    @Test void emptyPageStillOffersUtilityAndBackWithoutAnInvoicePayment() {
        var page = new OwnerDeskController.BillPage(new ChargeInvoiceRepository.OwnerView(List.of(), Map.of()), DAY, false, 100);
        assertEquals(List.of(42, 34), BedrockNpcUi.billButtons(page).slots());
        assertTrue(OwnerDeskMenu.billPageBody(page).contains("no bills"));
    }

    @Test void enablingBillsKeepsAllExistingRootActionsWithinTwelveButtons() {
        var residents = new ArrayList<OwnerDeskData.Resident>();
        for (int i = 0; i < 4; i++) residents.add(new OwnerDeskData.Resident(new UUID(2, i), "Resident " + i,
                i, "STANDARD", "NEUTRAL", 50));
        var data = new OwnerDeskData(new Property(HOME, new UUID(0, 4), "home", "town", "room", 4,
                1000, 1000, OWNER, 0, 80, true), 1, Instant.EPOCH, residents,
                List.of(new OwnerDeskData.Issue(1, "PLUMBING", 2000, Instant.EPOCH)), List.of(),
                new OwnerDeskData.Statement(DAY.minusDays(6), DAY, 0, 0, 0, 0));
        var old = BedrockNpcUi.ownerButtons(data, true);
        assertEquals(old, BedrockNpcUi.ownerButtons(data, true, false));
        var bills = BedrockNpcUi.ownerButtons(data, true, true);
        assertEquals(12, bills.labels().size());
        var expected = new HashSet<>(old.slots()); expected.remove(42); expected.add(35);
        assertEquals(expected, new HashSet<>(bills.slots()));
        assertTrue(bills.labels().contains("Payments and bills"));
    }
}

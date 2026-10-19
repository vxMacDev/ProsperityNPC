package com.prosperitysmp.npc.charge;

import com.prosperitysmp.npc.economy.BillPayments;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class BillTextFormatTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 18);
    private static final UUID OWNER = UUID.fromString("7a1b2c3d-0000-4000-8000-0000000000a1");
    private static final UUID HOME = UUID.fromString("00000000-0000-4000-8000-00000000f001");
    private static final UUID BILL = UUID.fromString("1234abcd-5678-4000-8000-000000000001");
    private static final UUID REQUEST = UUID.fromString("99999999-0000-4000-8000-000000000002");

    private static ChargeInvoiceRepository.Line line(int no, String policy, String sha, long raw, long fin, boolean capped) {
        return new ChargeInvoiceRepository.Line(no, policy, 2, sha, "flat", 1L, null, null, "{}", raw, fin, capped);
    }

    private static ChargeInvoiceRepository.Invoice invoice(UUID id, ChargeInvoiceRepository.State state, long total,
                                                           List<ChargeInvoiceRepository.Line> lines) {
        return new ChargeInvoiceRepository.Invoice(id, HOME, DAY, OWNER, OWNER, 0, 0, Instant.parse("2026-10-18T12:00:00Z"),
                DAY.plusDays(6), total, state, "a".repeat(64), lines);
    }

    private static ChargeInvoiceRepository.Invoice issued(UUID id) {
        return invoice(id, ChargeInvoiceRepository.State.ISSUED, 250, List.of());
    }

    private static ChargeInvoiceRepository.PaymentRef payment(BillPayments.State state) {
        return new ChargeInvoiceRepository.PaymentRef(REQUEST, state);
    }

    @Test void theShortIdIsTheFirstEightCharacters() {
        assertEquals("1234abcd", BillText.shortId(issued(BILL)));
    }

    @Test void matchNeedsAtLeastEightCharacters() {
        var invoices = List.of(issued(BILL));
        assertEquals(Optional.empty(), BillText.match(invoices, null));
        assertEquals(Optional.empty(), BillText.match(invoices, "1234abc"));
        assertEquals(Optional.of(invoices.getFirst()), BillText.match(invoices, "1234abcd"));
    }

    @Test void matchIgnoresCaseAndAcceptsTheWholeId() {
        var invoices = List.of(issued(BILL));
        assertEquals(Optional.of(invoices.getFirst()), BillText.match(invoices, "1234ABCD"));
        assertEquals(Optional.of(invoices.getFirst()), BillText.match(invoices, BILL.toString().toUpperCase()));
        assertEquals(Optional.empty(), BillText.match(invoices, "1234abce"));
        assertEquals(Optional.empty(), BillText.match(List.of(), "1234abcd"));
    }

    @Test void anAmbiguousPrefixMatchesNothingUntilItIsLonger() {
        var first = issued(BILL);
        var second = issued(UUID.fromString("1234abcd-9999-4000-8000-000000000001"));
        var both = List.of(first, second);
        assertEquals(Optional.empty(), BillText.match(both, "1234abcd"));
        assertEquals(Optional.of(second), BillText.match(both, "1234abcd-9"));
        assertEquals(Optional.of(first), BillText.match(both, "1234abcd-5"));
    }

    @Test void theStateTextFollowsTheStoredStateAndTheLastPayableDay() {
        assertEquals("paid", BillText.state(invoice(BILL, ChargeInvoiceRepository.State.PAID, 250, List.of()), DAY));
        assertEquals("expired, nothing owed", BillText.state(invoice(BILL, ChargeInvoiceRepository.State.EXPIRED, 250, List.of()), DAY));
        assertEquals("unpaid, can be paid until 2026-10-24", BillText.state(issued(BILL), DAY.plusDays(6)));
        assertEquals("expired, nothing owed", BillText.state(issued(BILL), DAY.plusDays(7)));
    }

    @Test void aPendingOrHeldPaymentOverridesTheStateText() {
        assertEquals("payment pending", BillText.state(issued(BILL), payment(BillPayments.State.WAITING), DAY));
        assertEquals("held for a staff check", BillText.state(issued(BILL), payment(BillPayments.State.REVIEW_REQUIRED), DAY));
        assertEquals("unpaid, can be paid until 2026-10-24", BillText.state(issued(BILL), payment(BillPayments.State.DECLINED), DAY));
        assertEquals("paid", BillText.state(invoice(BILL, ChargeInvoiceRepository.State.PAID, 250, List.of()),
                payment(BillPayments.State.PAID), DAY));
        assertEquals("unpaid, can be paid until 2026-10-24", BillText.state(issued(BILL), null, DAY));
    }

    @Test void anIssuedBillIsPayableFromItsDayToItsLastPayableDay() {
        var bill = issued(BILL);
        assertFalse(BillText.payable(bill, null, DAY.minusDays(1)));
        assertTrue(BillText.payable(bill, null, DAY));
        assertTrue(BillText.payable(bill, null, DAY.plusDays(6)));
        assertFalse(BillText.payable(bill, null, DAY.plusDays(7)));
    }

    @Test void onlyADeclinedEarlierAttemptAllowsPayingAgain() {
        var bill = issued(BILL);
        assertTrue(BillText.payable(bill, payment(BillPayments.State.DECLINED), DAY));
        assertFalse(BillText.payable(bill, payment(BillPayments.State.WAITING), DAY));
        assertFalse(BillText.payable(bill, payment(BillPayments.State.REVIEW_REQUIRED), DAY));
        assertFalse(BillText.payable(bill, payment(BillPayments.State.PAID), DAY));
        assertFalse(BillText.payable(invoice(BILL, ChargeInvoiceRepository.State.PAID, 250, List.of()), null, DAY));
        assertFalse(BillText.payable(invoice(BILL, ChargeInvoiceRepository.State.EXPIRED, 250, List.of()), null, DAY));
    }

    @ParameterizedTest(name = "{0} cents")
    @CsvSource({"250, 2.50", "5, 0.05", "100, 1.00", "1234, 12.34", "20000, 200.00"})
    void thePayCommandStatesTheExactMaximum(long cents, String dollars) {
        var bill = invoice(BILL, ChargeInvoiceRepository.State.ISSUED, cents, List.of());
        assertEquals("/property bills pay " + BILL + " " + dollars, BillText.payCommand(bill));
    }

    @Test void anEmptyListSaysSo() {
        assertEquals(List.of("This home has no bills."), BillText.list(List.of(), Map.of(), DAY));
    }

    @Test void theListShowsEachBillAndItsLinesWithFrozenOrFallbackTitles() {
        var bill = invoice(BILL, ChargeInvoiceRepository.State.ISSUED, 350,
                List.of(line(1, "power", "s1", 100, 100, false), line(2, "water", "s2", 300, 250, true)));
        assertEquals(List.of(
                "1234abcd  2026-10-18  $3.50  unpaid, can be paid until 2026-10-24",
                "    Power: $1.00",
                "    water v2: $2.50 (capped at its maximum)"), BillText.list(List.of(bill), Map.of("s1", "Power"), DAY));
    }

    @Test void theListShowsAPendingPaymentFromTheView() {
        var bill = issued(BILL);
        var view = new ChargeInvoiceRepository.OwnerView(List.of(bill), Map.of(), false,
                Map.of(BILL, payment(BillPayments.State.WAITING)));
        assertEquals(List.of("1234abcd  2026-10-18  $2.50  payment pending"), BillText.list(view, DAY));
    }

    @Test void theDetailListsTheBillLinesAndPaymentAdvice() {
        var bill = invoice(BILL, ChargeInvoiceRepository.State.ISSUED, 350,
                List.of(line(1, "power", "s1", 100, 100, false), line(2, "water", "s2", 300, 250, true)));
        assertEquals(List.of(
                "Bill: " + BILL,
                "Economy day: 2026-10-18",
                "Total: $3.50",
                "payment pending",
                "Power: $1.00",
                "water v2: $2.50 (capped at its maximum)",
                "Payment is still being checked. Do not pay again yet.",
                "/property receipt " + REQUEST), BillText.detail(bill, Map.of("s1", "Power"), payment(BillPayments.State.WAITING), DAY));
    }

    @Test void theDetailForAHeldPaymentSaysNotToPayAgain() {
        var lines = BillText.detail(issued(BILL), Map.of(), payment(BillPayments.State.REVIEW_REQUIRED), DAY);
        assertEquals(List.of("Bill: " + BILL, "Economy day: 2026-10-18", "Total: $2.50", "held for a staff check",
                "Payment needs a staff check. Do not pay again.", "/property receipt " + REQUEST), lines);
    }

    @Test void theDetailForADeclinedPaymentOnlyLinksTheReceipt() {
        var lines = BillText.detail(issued(BILL), Map.of(), payment(BillPayments.State.DECLINED), DAY);
        assertEquals(List.of("Bill: " + BILL, "Economy day: 2026-10-18", "Total: $2.50", "unpaid, can be paid until 2026-10-24",
                "/property receipt " + REQUEST), lines);
        assertThrows(UnsupportedOperationException.class, () -> lines.add("x"));
    }

    @Test void theDetailWithoutAPaymentHasNoReceiptLine() {
        assertEquals(List.of("Bill: " + BILL, "Economy day: 2026-10-18", "Total: $2.50", "unpaid, can be paid until 2026-10-24"),
                BillText.detail(issued(BILL), Map.of(), null, DAY));
    }
}

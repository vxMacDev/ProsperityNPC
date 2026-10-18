package com.prosperitysmp.npc.charge;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BillTextRentPausedTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 18);
    private static final UUID OWNER = UUID.fromString("7a1b2c3d-0000-4000-8000-0000000000a1");
    private static final UUID HOME = UUID.fromString("00000000-0000-4000-8000-00000000f001");
    private static final String PAUSED = "Rent is paused for this home until this bill is paid or expires on ";

    private static ChargeInvoiceRepository.Invoice invoice(LocalDate day, LocalDate payableUntil, ChargeInvoiceRepository.State state) {
        return new ChargeInvoiceRepository.Invoice(UUID.nameUUIDFromBytes(day.toString().getBytes()), HOME, day, OWNER, OWNER,
                0, 0, Instant.parse("2026-10-10T12:00:00Z"), payableUntil, 100, state, "a".repeat(64), List.of());
    }

    @Test void onlyAnEarlierUnpaidStillPayableBillPausesRent() {
        var earlier = invoice(DAY.minusDays(2), DAY.plusDays(4), ChargeInvoiceRepository.State.ISSUED);
        var lastDay = invoice(DAY.minusDays(6), DAY, ChargeInvoiceRepository.State.ISSUED);
        assertTrue(BillText.holdsRent(earlier, DAY));
        assertTrue(BillText.holdsRent(lastDay, DAY));
        assertFalse(BillText.holdsRent(invoice(DAY, DAY.plusDays(6), ChargeInvoiceRepository.State.ISSUED), DAY), "today");
        assertFalse(BillText.holdsRent(invoice(DAY.minusDays(7), DAY.minusDays(1), ChargeInvoiceRepository.State.ISSUED), DAY), "past");
        assertFalse(BillText.holdsRent(invoice(DAY.minusDays(2), DAY.plusDays(4), ChargeInvoiceRepository.State.PAID), DAY), "paid");
        assertFalse(BillText.holdsRent(invoice(DAY.minusDays(2), DAY.plusDays(4), ChargeInvoiceRepository.State.EXPIRED), DAY));
        // Rent comes back the day after the last payable day, when the bill expires.
        assertEquals(PAUSED + "2026-10-23.", BillText.rentPaused(earlier));
        assertEquals(PAUSED + "2026-10-19.", BillText.rentPaused(lastDay));
    }

    @Test void theOwnerListAndDeskSayWhichBillPausesRentOnlyUnderHoldRent() {
        var held = invoice(DAY.minusDays(2), DAY.plusDays(4), ChargeInvoiceRepository.State.ISSUED);
        var fresh = invoice(DAY, DAY.plusDays(6), ChargeInvoiceRepository.State.ISSUED);
        var view = new ChargeInvoiceRepository.OwnerView(List.of(fresh, held), Map.of());
        assertFalse(view.holdRent());
        assertTrue(BillText.list(view, DAY).stream().noneMatch(line -> line.contains("Rent is paused")));
        assertEquals(List.of(), BillText.rentPausedNotes(view, DAY));
        assertTrue(BillText.detail(held, Map.of(), null, DAY).stream().noneMatch(line -> line.contains("Rent is paused")));

        var holding = view.withHoldRent(true);
        var lines = BillText.list(holding, DAY);
        assertEquals(1, lines.stream().filter(line -> line.contains("Rent is paused")).count());
        assertEquals("    " + PAUSED + "2026-10-23.", lines.getLast());
        assertEquals(List.of("Bill from 2026-10-16: " + PAUSED + "2026-10-23."), BillText.rentPausedNotes(holding, DAY));
        assertEquals(PAUSED + "2026-10-23.", BillText.detail(held, Map.of(), null, DAY, true).getLast());
        assertTrue(BillText.detail(fresh, Map.of(), null, DAY, true).stream().noneMatch(line -> line.contains("Rent is paused")));
    }

    @Test void howBillsWorkExplainsTheRentPause() {
        assertTrue(BillText.HOW_BILLS_WORK.contains("If this server holds rent for unpaid bills, a home's rent is paused "
                + "while a bill from an earlier day is unpaid, until every such bill is paid or expires."));
    }

    private static final String SHARED = "Rent is paused for this home until every unpaid bill from an earlier day is "
            + "paid or expires. This bill expires on ";

    @Test void withMoreThanOneBillPausingRentNoBillClaimsPayingItAloneEndsThePause() {
        var older = invoice(DAY.minusDays(3), DAY.plusDays(3), ChargeInvoiceRepository.State.ISSUED);
        var newer = invoice(DAY.minusDays(1), DAY.plusDays(5), ChargeInvoiceRepository.State.ISSUED);
        var two = new ChargeInvoiceRepository.OwnerView(List.of(newer, older), Map.of()).withHoldRent(true);
        assertTrue(BillText.pauseShared(two, DAY));
        assertEquals(List.of("    " + SHARED + "2026-10-24.", "    " + SHARED + "2026-10-22."),
                BillText.list(two, DAY).stream().filter(line -> line.contains("Rent is paused")).toList());
        assertEquals(List.of("Bill from 2026-10-17: " + SHARED + "2026-10-24.", "Bill from 2026-10-15: " + SHARED + "2026-10-22."),
                BillText.rentPausedNotes(two, DAY));
        assertEquals(SHARED + "2026-10-22.", BillText.detail(older, Map.of(), null, DAY, true, true).getLast());
        assertTrue(BillText.list(two, DAY).stream().noneMatch(line -> line.contains("until this bill is paid")));

        // One pausing bill here, but older pages exist: an older bill may be pausing rent too.
        var paged = new ChargeInvoiceRepository.OwnerView(List.of(newer), Map.of(), true).withHoldRent(true);
        assertTrue(BillText.pauseShared(paged, DAY));
        assertEquals("    " + SHARED + "2026-10-24.", BillText.list(paged, DAY).getLast());

        // Exactly one pausing bill and nothing older: the plain line.
        var one = new ChargeInvoiceRepository.OwnerView(List.of(newer, invoice(DAY, DAY.plusDays(6),
                ChargeInvoiceRepository.State.ISSUED)), Map.of()).withHoldRent(true);
        assertFalse(BillText.pauseShared(one, DAY));
        assertEquals(List.of("Bill from 2026-10-17: " + PAUSED + "2026-10-24."), BillText.rentPausedNotes(one, DAY));
    }
}

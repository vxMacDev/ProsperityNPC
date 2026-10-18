package com.prosperitysmp.npc.charge;

import com.prosperitysmp.npc.economy.BillPayments;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChargeInvoicePayloadTest {
    private static final UUID INVOICE = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID HOME = new UUID(0, 1);
    private static final UUID OWNER = new UUID(0, 2);
    private static final LocalDate DAY = LocalDate.of(2026, 10, 17);
    private static final Instant ISSUED = Instant.parse("2026-10-17T12:00:00Z");
    private static final ChargeInvoiceRepository.Line LINE = new ChargeInvoiceRepository.Line(1, "tax", 1, "c".repeat(64), "flat",
            1L, null, null, "{}", 250, 250, false);

    private static ChargeInvoiceRepository.Invoice invoice(long total, ChargeInvoiceRepository.State state, String sha,
                                                           List<ChargeInvoiceRepository.Line> lines) {
        return new ChargeInvoiceRepository.Invoice(INVOICE, HOME, DAY, OWNER, OWNER, 0, 5, ISSUED, DAY.plusDays(6), total,
                state, sha, lines);
    }

    private static ChargeCatalogue.Policy policy(String id, Map<String, Object> extra) throws Exception {
        Map<String, Object> p = new HashMap<>(Map.of("version", "1", "title", "Bill", "amount", "100", "max-cents", "1000"));
        p.putAll(extra);
        return ChargeCatalogue.load(Map.of("policies", Map.of(id, p)), Map.of()).policy(id).orElseThrow();
    }

    @Test void theInvoiceIdIsAFixedNameBasedUuid() {
        assertEquals(UUID.fromString("4f5c43b7-d7dd-3bab-9fc6-03f2448e5e02"),
                ChargeInvoiceRepository.invoiceId(new UUID(0, 1), new UUID(0, 2), 0, LocalDate.of(2026, 10, 1)));
        assertEquals(3, ChargeInvoiceRepository.invoiceId(HOME, OWNER, 0, DAY).version());
    }

    @Test void theInvoiceIdNeedsEveryPart() {
        assertThrows(NullPointerException.class, () -> ChargeInvoiceRepository.invoiceId(null, OWNER, 0, DAY));
        assertThrows(NullPointerException.class, () -> ChargeInvoiceRepository.invoiceId(HOME, null, 0, DAY));
        assertThrows(NullPointerException.class, () -> ChargeInvoiceRepository.invoiceId(HOME, OWNER, 0, null));
    }

    @Test void thePayloadHashIsTheShaOfTheFrozenFields() {
        var stamped = ChargeInvoiceRepository.withPayload(invoice(250, ChargeInvoiceRepository.State.ISSUED, null, List.of(LINE)));
        assertEquals("44f5c2264927b9ab48897e0030dd5363948ebf890d1b9908917d2fe15e16d017", stamped.payloadSha());
        var empty = ChargeInvoiceRepository.withPayload(invoice(0, ChargeInvoiceRepository.State.ISSUED, null, List.of()));
        assertEquals("14e27d75a6e56a65bcd64315fa3c3834ea1f452c8acc843957f6d3118589aff6", empty.payloadSha());
    }

    @Test void stampingKeepsEveryOtherFieldAndReplacesAnOldHash() {
        var original = invoice(250, ChargeInvoiceRepository.State.ISSUED, "stale", List.of(LINE));
        var stamped = ChargeInvoiceRepository.withPayload(original);
        assertEquals(invoice(250, ChargeInvoiceRepository.State.ISSUED, stamped.payloadSha(), List.of(LINE)), stamped);
        assertEquals(stamped, ChargeInvoiceRepository.withPayload(stamped));
    }

    @Test void theStateIsNotPartOfThePayload() {
        String issued = ChargeInvoiceRepository.withPayload(invoice(250, ChargeInvoiceRepository.State.ISSUED, null, List.of(LINE))).payloadSha();
        String paid = ChargeInvoiceRepository.withPayload(invoice(250, ChargeInvoiceRepository.State.PAID, null, List.of(LINE))).payloadSha();
        assertEquals(issued, paid);
    }

    @Test void anyChangeToAnAmountOrLineChangesThePayload() {
        String base = ChargeInvoiceRepository.withPayload(invoice(250, ChargeInvoiceRepository.State.ISSUED, null, List.of(LINE))).payloadSha();
        List<ChargeInvoiceRepository.Line> changed = new ArrayList<>();
        changed.add(new ChargeInvoiceRepository.Line(1, "tax", 1, "c".repeat(64), "flat", 1L, null, null, "{}", 250, 249, false));
        changed.add(new ChargeInvoiceRepository.Line(1, "tax", 1, "c".repeat(64), "flat", 1L, null, null, "{}", 251, 250, false));
        changed.add(new ChargeInvoiceRepository.Line(1, "tax", 1, "c".repeat(64), "flat", 1L, null, null, "{}", 250, 250, true));
        changed.add(new ChargeInvoiceRepository.Line(2, "tax", 1, "c".repeat(64), "flat", 1L, null, null, "{}", 250, 250, false));
        changed.add(new ChargeInvoiceRepository.Line(1, "tax", 2, "c".repeat(64), "flat", 1L, null, null, "{}", 250, 250, false));
        changed.add(new ChargeInvoiceRepository.Line(1, "tax", 1, "c".repeat(64), "flat", 2L, null, null, "{}", 250, 250, false));
        changed.add(new ChargeInvoiceRepository.Line(1, "tax", 1, "c".repeat(64), "flat", 1L, false, null, "{}", 250, 250, false));
        changed.add(new ChargeInvoiceRepository.Line(1, "tax", 1, "c".repeat(64), "flat", 1L, null, "d".repeat(64), "{}", 250, 250, false));
        changed.add(new ChargeInvoiceRepository.Line(1, "tax", 1, "c".repeat(64), "flat", 1L, null, null, "{\"units\":1}", 250, 250, false));
        for (var line : changed)
            assertNotEquals(base, ChargeInvoiceRepository.withPayload(invoice(250, ChargeInvoiceRepository.State.ISSUED, null,
                    List.of(line))).payloadSha(), line.toString());
        assertNotEquals(base, ChargeInvoiceRepository.withPayload(invoice(251, ChargeInvoiceRepository.State.ISSUED, null,
                List.of(LINE))).payloadSha());
        assertNotEquals(base, ChargeInvoiceRepository.withPayload(new ChargeInvoiceRepository.Invoice(INVOICE, HOME, DAY, OWNER,
                OWNER, 0, 5, ISSUED.plusMillis(1), DAY.plusDays(6), 250, ChargeInvoiceRepository.State.ISSUED, null, List.of(LINE)))
                .payloadSha());
        assertNotEquals(base, ChargeInvoiceRepository.withPayload(new ChargeInvoiceRepository.Invoice(INVOICE, HOME, DAY, OWNER,
                new UUID(0, 3), 0, 5, ISSUED, DAY.plusDays(6), 250, ChargeInvoiceRepository.State.ISSUED, null, List.of(LINE)))
                .payloadSha());
    }

    @Test void theRepositoryHashMatchesTheCatalogueHash() {
        assertEquals(ChargeCatalogue.sha256("bills"), ChargeInvoiceRepository.sha256("bills"));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ChargeInvoiceRepository.sha256("abc"));
    }

    @Test void applicableFiltersByOccupancyClassMarketAndProperty() throws Exception {
        var all = policy("all-homes", Map.of());
        var occupied = policy("occupied", Map.of("applies-to", "occupied"));
        var vacant = policy("vacant", Map.of("applies-to", "vacant"));
        var rooms = policy("rooms", Map.of("classes", List.of("room")));
        var east = policy("east", Map.of("markets", List.of("east")));
        var mine = policy("mine", Map.of("properties", List.of(HOME.toString())));
        var policies = List.of(all, occupied, vacant, rooms, east, mine);
        var home = new ChargeInvoiceRepository.Home(HOME, OWNER, 0, "room", "town");
        assertEquals(List.of(all, occupied, rooms, mine), ChargeInvoiceRepository.applicable(policies, home, Map.of("tenants", 2L)));
        assertEquals(List.of(all, vacant, rooms, mine), ChargeInvoiceRepository.applicable(policies, home, Map.of("tenants", 0L)));
        var other = new ChargeInvoiceRepository.Home(new UUID(0, 9), OWNER, 0, "house", "east");
        assertEquals(List.of(all, vacant, east), ChargeInvoiceRepository.applicable(policies, other, Map.of()));
    }

    @Test void anOwnerViewCopiesItsInputsAndTogglesHoldRent() {
        var invoices = new ArrayList<ChargeInvoiceRepository.Invoice>();
        invoices.add(invoice(250, ChargeInvoiceRepository.State.ISSUED, "x", List.of(LINE)));
        var view = new ChargeInvoiceRepository.OwnerView(invoices, new HashMap<>(Map.of("s", "Title")));
        invoices.clear();
        assertEquals(1, view.invoices().size());
        assertFalse(view.hasOlder());
        assertFalse(view.holdRent());
        assertEquals(Map.of(), view.payments());
        assertSame(view, view.withHoldRent(false));
        var holding = view.withHoldRent(true);
        assertTrue(holding.holdRent());
        assertEquals(view.invoices(), holding.invoices());
        assertEquals(view.titles(), holding.titles());
        assertSame(holding, holding.withHoldRent(true));
        var payments = Map.of(INVOICE, new ChargeInvoiceRepository.PaymentRef(new UUID(5, 5), BillPayments.State.WAITING));
        var full = new ChargeInvoiceRepository.OwnerView(List.of(), Map.of(), true, payments);
        assertTrue(full.hasOlder());
        assertEquals(payments, full.payments());
        assertThrows(UnsupportedOperationException.class, () -> full.payments().clear());
    }
}

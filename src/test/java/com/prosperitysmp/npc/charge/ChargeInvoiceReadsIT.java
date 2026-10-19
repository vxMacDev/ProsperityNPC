package com.prosperitysmp.npc.charge;

import com.prosperitysmp.npc.economy.BillPayments;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChargeInvoiceReadsIT {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 17);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID RESERVE = new UUID(68, 1);
    private static BillsTestDatabase db;
    private ChargeInvoiceRepository invoices;

    @BeforeAll static void boot() throws Exception {
        db = BillsTestDatabase.boot("npc_bill_reads");
    }

    @AfterAll static void stop() throws Exception {
        if (db != null) db.close();
    }

    @BeforeEach void reset() throws SQLException {
        for (String table : List.of("npc_charge_invoice_lines", "npc_charge_payments", "npc_charge_invoices",
                "npc_meter_baselines", "npc_charge_policies", "npc_property_funding", "npc_property_ownership", "npc_properties"))
            db.execute("DELETE FROM " + table);
        invoices = new ChargeInvoiceRepository(db.database(), new BillPayments(db.database(), RESERVE, CLOCK), null, () -> false, CLOCK);
    }

    private ChargeCatalogue.Catalogue catalogue(Map<String, Object> extra) throws Exception {
        Map<String, Object> policy = new LinkedHashMap<>(Map.of("version", "1", "title", "Reads bill", "amount", "100",
                "max-cents", "1000"));
        policy.putAll(extra);
        var catalogue = ChargeCatalogue.load(Map.of("enabled", true,
                "limits", Map.of("per-owner-daily-max-cents", "1000", "server-daily-max-cents", "100000"),
                "policies", Map.of("reads-bill", policy)), Map.of());
        invoices.record(catalogue, DAY.minusDays(100), DAY.minusDays(99));
        return catalogue;
    }

    private static ChargeInvoiceRepository.Home home(UUID property, UUID owner) {
        return new ChargeInvoiceRepository.Home(property, owner, 0, "room", "town");
    }

    private ChargeInvoiceRepository.Invoice issue(ChargeInvoiceRepository.Home home, LocalDate day,
                                                  ChargeCatalogue.Catalogue catalogue) throws SQLException {
        return invoices.issue(home, day, catalogue.policies(), Map.of(), Map.of(), 1000, 100_000, 90, Set.of())
                .invoice().orElseThrow();
    }

    @Test void aHomeIsNotBilledUntilItsFirstInvoice() throws Exception {
        var catalogue = catalogue(Map.of());
        UUID owner = UUID.randomUUID();
        var home = home(db.home(owner), owner);
        var other = home(db.home(owner), owner);
        assertFalse(invoices.everBilled(home.propertyId()));
        issue(home, DAY, catalogue);
        assertTrue(invoices.everBilled(home.propertyId()));
        assertFalse(invoices.everBilled(other.propertyId()));
        assertFalse(invoices.everBilled(UUID.randomUUID()));
    }

    @Test void recentListsTheNewestBillsFirstUpToTheLimit() throws Exception {
        var catalogue = catalogue(Map.of());
        UUID owner = UUID.randomUUID();
        var home = home(db.home(owner), owner);
        for (int i = 4; i >= 0; i--) issue(home, DAY.minusDays(i), catalogue);
        assertEquals(List.of(DAY, DAY.minusDays(1)),
                invoices.recent(home.propertyId(), 2).stream().map(ChargeInvoiceRepository.Invoice::day).toList());
        assertEquals(List.of(DAY, DAY.minusDays(1), DAY.minusDays(2), DAY.minusDays(3), DAY.minusDays(4)),
                invoices.recent(home.propertyId(), 10).stream().map(ChargeInvoiceRepository.Invoice::day).toList());
    }

    @Test void recentReadsTheStoredLinesOfEachBill() throws Exception {
        var catalogue = catalogue(Map.of());
        UUID owner = UUID.randomUUID();
        var home = home(db.home(owner), owner);
        var issued = issue(home, DAY, catalogue);
        var read = invoices.recent(home.propertyId(), 1).getFirst();
        assertEquals(issued, read);
        assertEquals(1, read.lines().size());
        assertEquals(100, read.lines().getFirst().finalCents());
        assertEquals(issued, invoices.find(issued.invoiceId()).orElseThrow());
        assertTrue(invoices.find(UUID.randomUUID()).isEmpty());
    }

    @Test void aLimitOfZeroOrLessStillReturnsTheNewestBill() throws Exception {
        var catalogue = catalogue(Map.of());
        UUID owner = UUID.randomUUID();
        var home = home(db.home(owner), owner);
        issue(home, DAY.minusDays(1), catalogue);
        issue(home, DAY, catalogue);
        assertEquals(List.of(DAY), invoices.recent(home.propertyId(), 0).stream().map(ChargeInvoiceRepository.Invoice::day).toList());
        assertEquals(List.of(DAY), invoices.recent(home.propertyId(), -5).stream().map(ChargeInvoiceRepository.Invoice::day).toList());
        assertEquals(List.of(DAY), invoices.recent(home.propertyId(), Integer.MIN_VALUE).stream()
                .map(ChargeInvoiceRepository.Invoice::day).toList());
    }

    @Test void recentReturnsAtMostFiftyBills() throws Exception {
        var catalogue = catalogue(Map.of());
        UUID owner = UUID.randomUUID();
        var home = home(db.home(owner), owner);
        for (int i = 0; i < 51; i++) issue(home, DAY.minusDays(i), catalogue);
        var recent = invoices.recent(home.propertyId(), Integer.MAX_VALUE);
        assertEquals(50, recent.size());
        assertEquals(DAY, recent.getFirst().day());
        assertEquals(DAY.minusDays(49), recent.getLast().day());
    }

    @Test void recentOnlyListsTheGivenHome() throws Exception {
        var catalogue = catalogue(Map.of());
        UUID owner = UUID.randomUUID();
        var home = home(db.home(owner), owner);
        var other = home(db.home(owner), owner);
        issue(other, DAY, catalogue);
        assertEquals(List.of(), invoices.recent(home.propertyId(), 10));
        assertEquals(1, invoices.recent(other.propertyId(), 10).size());
    }

    @Test void aPreviewWorksOutTheLinesWithoutWritingAnything() throws Exception {
        var catalogue = catalogue(Map.of());
        UUID owner = UUID.randomUUID();
        var home = home(db.home(owner), owner);
        var plan = invoices.preview(home, DAY, catalogue.policies(), Map.of(), Map.of());
        assertEquals(List.of(100L), plan.drafts().stream().map(InvoicePlanner.Draft::rawCents).toList());
        assertEquals(List.of(), plan.skipped());
        assertEquals(0, db.count("npc_charge_invoices"));
        assertFalse(invoices.everBilled(home.propertyId()));
        assertEquals(100, issue(home, DAY, catalogue).totalCents());
    }

    @Test void aPreviewReadsTheHomeFactsFromTheTables() throws Exception {
        var catalogue = catalogue(Map.of("meter", "derived:capacity", "amount", "units * 7 + owner_homes * 100 + condition"));
        UUID owner = UUID.randomUUID();
        var home = home(db.home(owner), owner);
        db.home(owner);
        var draft = invoices.preview(home, DAY, catalogue.policies(), Map.of(), Map.of()).drafts().getFirst();
        assertEquals(2 * 7 + 2 * 100 + 80, draft.rawCents());
        assertEquals("{\"condition\":80,\"owner_homes\":2,\"units\":2}", draft.inputsJson());
        assertEquals(2L, draft.meterUnits());
    }

    @Test void aPreviewForAHomeThatChangedHandsIsEmpty() throws Exception {
        var catalogue = catalogue(Map.of());
        UUID owner = UUID.randomUUID();
        UUID property = db.home(owner);
        for (var stale : List.of(home(property, UUID.randomUUID()), new ChargeInvoiceRepository.Home(property, owner, 1, "room", "town"),
                new ChargeInvoiceRepository.Home(property, owner, 0, "house", "town"),
                new ChargeInvoiceRepository.Home(property, owner, 0, "room", "east"), home(UUID.randomUUID(), owner))) {
            var plan = invoices.preview(stale, DAY, catalogue.policies(), Map.of(), Map.of());
            assertEquals(new InvoicePlanner.Plan(List.of(), List.of()), plan, stale.toString());
        }
    }

    @Test void aPreviewSkipsPoliciesThatDoNotCoverTheHome() throws Exception {
        var catalogue = catalogue(Map.of("classes", List.of("house")));
        UUID owner = UUID.randomUUID();
        var home = home(db.home(owner), owner);
        assertEquals(new InvoicePlanner.Plan(List.of(), List.of()), invoices.preview(home, DAY, catalogue.policies(), Map.of(), Map.of()));
        var occupied = catalogue(Map.of("applies-to", "occupied", "version", "2"));
        assertEquals(List.of(), invoices.preview(home, DAY, occupied.policies(), Map.of(), Map.of()).drafts());
    }

    @Test void aPreviewReportsAMissingMeterReading() throws Exception {
        var catalogue = catalogue(Map.of("meter", "power", "amount", "units"));
        UUID owner = UUID.randomUUID();
        var home = home(db.home(owner), owner);
        var plan = invoices.preview(home, DAY, catalogue.policies(), Map.of(), Map.of("reads-bill", "meter power is not available"));
        assertEquals(List.of(new InvoicePlanner.Skipped("reads-bill", true, "meter power is not available")), plan.skipped());
        assertEquals(0, db.count("npc_charge_invoices"));
    }

    @Test void aHalfUpPolicyCanBeRecordedAndBilled() throws Exception {
        var catalogue = catalogue(Map.of("rounding", "half-up", "amount", "100.5"));
        UUID owner = UUID.randomUUID();
        var home = home(db.home(owner), owner);
        assertEquals(101, issue(home, DAY, catalogue).totalCents());
    }

    @Test void theUsageOfADayWithNoBillsIsZero() throws Exception {
        assertEquals(new ChargeInvoiceRepository.Usage(0, 0, 0, 0, 0), invoices.usage(DAY));
    }

    @Test void theUsageCountsOnlyTheGivenDay() throws Exception {
        var catalogue = catalogue(Map.of());
        UUID owner = UUID.randomUUID();
        var home = home(db.home(owner), owner);
        issue(home, DAY, catalogue);
        issue(home, DAY.minusDays(1), catalogue);
        assertEquals(new ChargeInvoiceRepository.Usage(1, 0, 0, 100, 0), invoices.usage(DAY));
        assertEquals(new ChargeInvoiceRepository.Usage(0, 0, 0, 0, 0), invoices.usage(DAY.plusDays(1)));
    }
}

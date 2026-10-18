package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.charge.BillingRuntime;
import com.prosperitysmp.npc.charge.ChargeCatalogue;
import com.prosperitysmp.npc.charge.ChargeInvoiceRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.prosperitysmp.npc.economy.LinkedAccountsHarness.*;
import static org.junit.jupiter.api.Assertions.*;

class HoldRentIT {
    private static LinkedAccountsHarness db;
    private BillPayments payments;
    private ChargeInvoiceRepository invoices;

    @BeforeAll static void boot() throws Exception { db = LinkedAccountsHarness.boot("npc_bill_rent_hold"); }
    @AfterAll static void stop() throws Exception { if (db != null) db.close(); }
    @BeforeEach void fresh() throws Exception {
        db.reset();
        payments = new BillPayments(db.database, RESERVE, Clock.systemUTC());
        invoices = new ChargeInvoiceRepository(db.database, payments, null, () -> false, db.clock);
        invoices.record(catalogue("hold-rent"), DAY.minusDays(10), DAY.minusDays(9));
    }

    private ChargeCatalogue.Catalogue catalogue(String unpaid) throws Exception {
        return ChargeCatalogue.load(Map.of("enabled", true, "on-unpaid", unpaid,
                "limits", Map.of("per-owner-daily-max-cents", "1000", "server-daily-max-cents", "10000"),
                "policies", Map.of("test-bill", Map.of("version", "1", "title", "Bill", "amount", "100",
                        "max-cents", "1000"))), Map.of());
    }

    private ChargeInvoiceRepository.Invoice issue(UUID id, LocalDate day) throws Exception {
        var p = db.property(id);
        var home = new ChargeInvoiceRepository.Home(id, p.ownerUuid(), p.ownershipEpoch(), p.classKey(), p.marketId());
        return invoices.issue(home, day, catalogue("hold-rent").policies(), Map.of(), Map.of(),
                1000, 10000, 7, Set.of()).invoice().orElseThrow();
    }

    @Test void noneAndDisabledNeverReadTheCacheAndKeepNormalRent() throws Exception {
        var homes = db.properties(List.of(db.operating(JAVA, 1000, "first"), db.operating(BEDROCK, 1000, "second")));
        for (var catalogue : List.of(catalogue("none"), ChargeCatalogue.Catalogue.DISABLED)) {
            var held = BillingRuntime.rentHolds(catalogue, DAY, () -> { throw new AssertionError("cache read"); });
            assertEquals(Set.of(), held.orElseThrow());
            assertEquals(UtilityPaymentRuntime.rentCandidates(homes, Set.of()),
                    UtilityPaymentRuntime.rentCandidates(homes, held.orElseThrow()));
        }
        var selected = UtilityPaymentRuntime.rentCandidates(homes, BillingRuntime.rentHolds(catalogue("none"), DAY,
                () -> { throw new AssertionError("cache read"); }).orElseThrow());
        var reserved = db.legacyRents().reserve(DAY, selected, limits(15000, 4500, 1500, 1500));
        assertEquals(List.of(1000L, 1000L), reserved.stream().map(RentReservationRepository.Reserved::cents).toList());
        assertEquals(2, db.count("npc_rent_reservations"));
    }

    @Test void onlyEarlierStillPayableBillsExcludeHomesBeforeReservation() throws Exception {
        UUID late = db.operating(JAVA, 1000, "late"), today = db.operating(JAVA, 1000, "today");
        UUID lastDay = db.operating(JAVA, 1000, "last-day"), expired = db.operating(JAVA, 1000, "expired");
        UUID away = db.operating(JAVA, 1000, "away");
        issue(late, DAY.minusDays(1));
        issue(today, DAY);
        issue(lastDay, DAY.minusDays(6));
        issue(expired, DAY.minusDays(7));
        var open = invoices.openInvoices();
        var held = BillingRuntime.rentHolds(catalogue("hold-rent"), DAY, () -> open);
        assertEquals(Set.of(late, lastDay), held.orElseThrow());
        var excluded = new HashSet<>(held.orElseThrow());
        excluded.add(away);
        var selected = UtilityPaymentRuntime.rentCandidates(db.properties(List.of(late, today, lastDay, expired, away)), excluded);
        assertEquals(Set.of(today, expired), selected.stream().map(p -> p.propertyId()).collect(java.util.stream.Collectors.toSet()));
        var reserved = db.legacyRents().reserve(DAY, selected, limits(15000, 4500, 1500, 1500));
        assertEquals(2, reserved.size());
        assertEquals(0, db.count("npc_rent_reservations WHERE property_id IN (?,?,?)", late, lastDay, away));
        assertEquals(2, db.count("npc_economy_events WHERE source='npc-property-rent'"));
    }

    @Test void anUnloadedHoldCacheWaitsInsteadOfPassingHomesThrough() throws Exception {
        db.operating(JAVA, 1000, "unloaded");
        assertTrue(BillingRuntime.rentHolds(catalogue("hold-rent"), DAY, () -> null).isEmpty());
        assertEquals(0, db.count("npc_rent_reservations"));
        assertEquals(0, db.count("npc_economy_events WHERE source='npc-property-rent'"));
    }

    @Test void anEarlierOwnersUnpaidBillNeverHoldsTheNewOwnersRent() throws Exception {
        UUID sold = db.operating(JAVA, 1000, "sold"), kept = db.operating(JAVA, 1000, "kept");
        issue(sold, DAY.minusDays(1));
        issue(kept, DAY.minusDays(1));
        var beforeSale = invoices.openInvoices();
        assertEquals(Set.of(sold, kept), BillingRuntime.rentHolds(catalogue("hold-rent"), DAY, () -> beforeSale).orElseThrow());

        db.execute("UPDATE npc_properties SET owner_uuid=?,ownership_epoch=ownership_epoch+1 WHERE property_id=?", STRANGER, sold);
        // The earlier owner's bill stays ISSUED, but nobody can see or pay it now; it expires at the end of its window.
        assertEquals(1, db.count("npc_charge_invoices WHERE property_id=? AND state='ISSUED' AND owner_uuid=?", sold, JAVA));
        var afterSale = invoices.openInvoices();
        assertEquals(List.of(kept), afterSale.stream().map(BillingRuntime.Open::propertyId).toList());
        assertEquals(Set.of(kept), BillingRuntime.rentHolds(catalogue("hold-rent"), DAY, () -> afterSale).orElseThrow());

        // A cache read before the sale must not hold the new owner either: the home as cached now decides.
        var homes = db.properties(List.of(sold, kept));
        assertEquals(STRANGER, homes.getFirst().ownerUuid());
        var held = BillingRuntime.rentHolds(true, true, "hold-rent", catalogue("hold-rent"), DAY, () -> beforeSale, homes);
        assertEquals(Set.of(kept), held.orElseThrow());
        assertEquals(List.of(sold), UtilityPaymentRuntime.rentCandidates(homes, held.orElseThrow()).stream()
                .map(com.prosperitysmp.npc.property.Property::propertyId).toList());

        // A home that leaves the economy holds nothing either.
        db.execute("UPDATE npc_properties SET enabled=0 WHERE property_id=?", kept);
        assertEquals(List.of(), invoices.openInvoices());
    }

    @Test void payingAnOldBillAndRefreshingTheCacheAllowsRentToBeReserved() throws Exception {
        UUID id = db.operating(JAVA, 1000, "settled");
        var invoice = issue(id, DAY.minusDays(1));
        var homes = db.properties(List.of(id));
        var before = invoices.openInvoices();
        assertTrue(UtilityPaymentRuntime.rentCandidates(homes,
                BillingRuntime.rentHolds(catalogue("hold-rent"), DAY, () -> before).orElseThrow()).isEmpty());
        assertEquals(0, db.count("npc_rent_reservations"));
        var receipt = payments.preparePayment(UUID.randomUUID(), invoice.invoiceId(), JAVA, 0, DAY, 100, 1000, id).receipt();
        var balances = new HashMap<>(Map.of(JAVA, 100.0, RESERVE, 100.0));
        var transfer = new TransferDispatchFixture.Vault(db.journal(), balances, leg -> false, new ArrayList<>(),
                (leg, refusal) -> fail("unexpected refusal"));
        var handle = new TransferJournal.Handle(UUID.randomUUID(), receipt.eventId(), UUID.randomUUID(), "a".repeat(64));
        assertEquals(TransferJournal.State.PAID, transfer.submit(handle).toCompletableFuture().join().state());
        assertEquals(BillPayments.State.PAID, payments.reconcile(receipt.eventId()).orElseThrow().state());
        var refreshed = invoices.openInvoices();
        var selected = UtilityPaymentRuntime.rentCandidates(homes,
                BillingRuntime.rentHolds(catalogue("hold-rent"), DAY, () -> refreshed).orElseThrow());
        var reserved = db.legacyRents().reserve(DAY, selected, limits(15000, 4500, 1500, 1500));
        assertEquals(1, reserved.size());
        assertEquals(id, reserved.getFirst().property().propertyId());
        assertEquals(1000, reserved.getFirst().cents());
        assertEquals(Map.of(JAVA, 99.0, RESERVE, 101.0), balances);
    }
}

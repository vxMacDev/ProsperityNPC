package com.prosperitysmp.npc.charge;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.charge.meter.MeterRead;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.economy.BillPayments;
import com.prosperitysmp.npc.economy.AccountLinkRepository;
import com.prosperitysmp.npc.economy.EconomyEvent;
import com.prosperitysmp.npc.economy.EconomyEventRepository;
import com.prosperitysmp.npc.economy.EventSource;
import com.prosperitysmp.npc.economy.TransferDispatchFixture;
import com.prosperitysmp.npc.economy.TransferJournal;
import com.prosperitysmp.npc.economy.TransferProof;
import com.prosperitysmp.npc.economy.UtilityRepository;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyRepository;
import com.prosperitysmp.npc.property.OwnerDeskData;
import com.prosperitysmp.npc.property.OwnerDeskRepository;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChargeInvoiceIT {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 17);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID RESERVE = new UUID(65, 1);
    private static DB maria;
    private static Database database;
    private ChargeInvoiceRepository invoices;
    private BillPayments payments;

    @BeforeAll static void boot() throws Exception {
        var config = DBConfigurationBuilder.newBuilder().setPort(0).addArg("--bind-address=127.0.0.1");
        maria = DB.newEmbeddedDB(config.build());
        maria.start();
        String schema = "npc_bill_test_" + UUID.randomUUID().toString().replace("-", "");
        maria.createDB(schema);
        database = new Database();
        database.connect(new DatabaseSettings("localhost", config.getPort(), schema, "root", "", 6, 10_000));
        var field = MigrationRunner.class.getDeclaredField("MIGRATIONS");
        field.setAccessible(true);
        for (Object migration : (List<?>) field.get(null)) {
            var resource = migration.getClass().getDeclaredMethod("resource");
            resource.setAccessible(true);
            try (InputStream input = ChargeInvoiceIT.class.getResourceAsStream("/" + resource.invoke(migration))) {
                assertNotNull(input);
                for (String sql : MigrationRunner.splitStatements(new String(input.readAllBytes(), StandardCharsets.UTF_8))) execute(sql);
            }
        }
    }

    @AfterAll static void stop() throws Exception {
        try { if (database != null) database.close(); }
        finally { if (maria != null) maria.stop(); }
    }

    @BeforeEach void reset() throws SQLException {
        execute("DROP TRIGGER IF EXISTS npc_bill_delay");
        for (String table : List.of("npc_charge_invoice_lines", "npc_charge_payments", "npc_charge_invoices",
                "npc_meter_baselines", "npc_charge_policies", "npc_utility_payments", "npc_utility_bills",
                "npc_refund_claims", "npc_refund_claim_payers",
                "npc_transfer_resolutions", "npc_transfer_observations",
                "npc_transfer_attempts", "npc_economy_events", "npc_linked_account_days", "npc_account_links",
                "npc_property_funding", "npc_property_ownership", "npc_properties"))
            execute("DELETE FROM " + table);
        payments = new BillPayments(database, RESERVE, CLOCK);
        invoices = new ChargeInvoiceRepository(database, payments, null, () -> false, CLOCK);
    }

    private ChargeCatalogue.Catalogue catalogue(long amount, String meter) throws Exception {
        return catalogue(Long.toString(amount), meter);
    }

    private ChargeCatalogue.Catalogue catalogue(String amount, String meter) throws Exception {
        var catalogue = definition(amount, meter, 1);
        invoices.record(catalogue, DAY.minusDays(1), DAY);
        return catalogue;
    }

    private ChargeCatalogue.Catalogue definition(String amount, String meter, int version) throws Exception {
        var policy = Map.of("version", Integer.toString(version), "title", "Test bill", "amount", amount,
                "max-cents", "1000", "meter", meter, "classes", List.of("room"), "markets", List.of("town"));
        return ChargeCatalogue.load(Map.of("enabled", true,
                "limits", Map.of("per-owner-daily-max-cents", "1000", "server-daily-max-cents", "10000"),
                "policies", Map.of("test-bill", policy)), Map.of());
    }

    private ChargeInvoiceRepository.Home home(UUID owner) throws SQLException {
        UUID id = UUID.randomUUID();
        new PropertyRepository(database).insertEnrollment(new Property(id, UUID.randomUUID(), "bill-" + id,
                "town", "room", 2, 1000, 1000, owner, 0, 80, true), 50_000);
        return new ChargeInvoiceRepository.Home(id, owner, 0, "room", "town");
    }

    private ChargeInvoiceRepository.Issued issue(ChargeInvoiceRepository.Home home, ChargeCatalogue.Catalogue catalogue,
                                                long ownerMax, long serverMax) throws SQLException {
        return invoices.issue(home, DAY, catalogue.policies(), Map.of(), Map.of(), ownerMax, serverMax, 7, Set.of());
    }

    @Test void changedTermsStartTomorrowAndOldInvoicesRemainExplainableAfterRestart() throws Exception {
        var first = catalogue(100, "flat");
        var home = home(UUID.randomUUID());
        var original = issue(home, first, 1000, 10000).invoice().orElseThrow();
        invoices.record(definition("200", "flat", 2), DAY, DAY.plusDays(1));
        invoices = new ChargeInvoiceRepository(database, payments, null, () -> false, CLOCK);
        var periods = invoices.policyPeriods(DAY);
        assertEquals(1, BillingRuntime.inForce(periods, DAY).getFirst().version());
        assertEquals(2, BillingRuntime.inForce(periods, DAY.plusDays(1)).getFirst().version());
        assertEquals(original, invoices.find(original.invoiceId()).orElseThrow());
        var line = original.lines().getFirst();
        assertEquals(100, invoices.policy(line.policyId(), line.version(), line.policySha()).amountCents(Map.of()));
        Clock next = Clock.fixed(DAY.plusDays(1).atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        var tomorrow = new ChargeInvoiceRepository(database, new BillPayments(database, RESERVE, next), null, () -> false, next);
        var issued = tomorrow.issue(home, DAY.plusDays(1), BillingRuntime.inForce(periods, DAY.plusDays(1)),
                Map.of(), Map.of(), 1000, 10000, 7, Set.of()).invoice().orElseThrow();
        assertEquals(200, issued.totalCents());
        assertEquals(0, count("npc_economy_events"));
    }

    private ChargeCatalogue.Catalogue emptyCatalogue() {
        return new ChargeCatalogue.Catalogue(true, false, ChargeCatalogue.OnUnpaid.NONE, 7, 1000, 10000, List.of());
    }

    @Test void ownerBillListKeepsFrozenTitlesAndHidesPreviousOwnership() throws Exception {
        var first = catalogue(100, "flat");
        var home = home(UUID.randomUUID());
        var old = issue(home, first, 1000, 10000).invoice().orElseThrow();
        var next = ChargeCatalogue.load(Map.of("enabled", true,
                "limits", Map.of("per-owner-daily-max-cents", "1000", "server-daily-max-cents", "10000"),
                "policies", Map.of("test-bill", Map.of("version", "2", "title", "Renamed bill",
                        "amount", "200", "max-cents", "1000"))), Map.of());
        invoices.record(next, DAY, DAY.plusDays(1));
        var newer = invoices.issue(home, DAY.plusDays(1), next.policies(), Map.of(), Map.of(),
                1000, 10000, 7, Set.of()).invoice().orElseThrow();
        var view = invoices.ownerView(home.propertyId(), home.ownerId(), 0);
        assertEquals(List.of(newer.invoiceId(), old.invoiceId()), view.invoices().stream().map(ChargeInvoiceRepository.Invoice::invoiceId).toList());
        var text = BillText.list(view.invoices(), view.titles(), DAY.plusDays(1));
        assertTrue(text.contains("    Test bill: $1.00"));
        assertTrue(text.contains("    Renamed bill: $2.00"));
        assertThrows(UnsupportedOperationException.class, () -> view.invoices().clear());
        assertThrows(UnsupportedOperationException.class, () -> view.titles().clear());
        assertTrue(invoices.ownerView(home.propertyId(), UUID.randomUUID(), 0).invoices().isEmpty());
        assertTrue(invoices.ownerView(home.propertyId(), home.ownerId(), 1).invoices().isEmpty());
        UUID buyer = UUID.randomUUID();
        new PropertyRepository(database).applyOwnerChange(home.propertyId(), home.ownerId(), buyer, 1, "list sale");
        assertTrue(invoices.ownerView(home.propertyId(), home.ownerId(), 0).invoices().isEmpty());
        assertTrue(invoices.ownerView(home.propertyId(), buyer, 1).invoices().isEmpty());
        assertEquals(2, count("npc_charge_invoices"));
        assertEquals(0, count("npc_economy_events"));
    }

    @Test void ownerBillListIsBoundedAndMissingTermsDoNotUseAnotherTitle() throws Exception {
        var catalogue = catalogue(100, "flat");
        var home = home(UUID.randomUUID());
        for (int i = 0; i < 12; i++) invoices.issue(home, DAY.minusDays(i), catalogue.policies(), Map.of(), Map.of(),
                1000, 10000, 90, Set.of());
        execute("UPDATE npc_charge_policies SET definition_json=NULL");
        var view = invoices.ownerView(home.propertyId(), home.ownerId(), 0);
        assertEquals(8, view.invoices().size());
        assertEquals(DAY, view.invoices().getFirst().day());
        assertEquals(DAY.minusDays(7), view.invoices().getLast().day());
        assertTrue(view.hasOlder());
        var older = invoices.ownerView(home.propertyId(), home.ownerId(), 0, view.invoices().getLast().day());
        assertEquals(List.of(DAY.minusDays(8), DAY.minusDays(9), DAY.minusDays(10), DAY.minusDays(11)),
                older.invoices().stream().map(ChargeInvoiceRepository.Invoice::day).toList());
        assertFalse(older.hasOlder());
        assertEquals(12, java.util.stream.Stream.concat(view.invoices().stream(), older.invoices().stream())
                .map(ChargeInvoiceRepository.Invoice::invoiceId).distinct().count());
        var oldest = older.invoices().getLast();
        assertEquals(oldest, invoices.ownerInvoice(home.propertyId(), home.ownerId(), 0, oldest.invoiceId()).orElseThrow());
        assertTrue(invoices.ownerInvoice(home.propertyId(), UUID.randomUUID(), 0, oldest.invoiceId()).isEmpty());
        var otherHome = home(home.ownerId());
        assertTrue(invoices.ownerInvoice(otherHome.propertyId(), home.ownerId(), 0, oldest.invoiceId()).isEmpty());
        assertTrue(BillText.payable(oldest, null, DAY));
        var payment = payments.preparePayment(UUID.randomUUID(), oldest.invoiceId(), home.ownerId(), 0, DAY, 100, 1000);
        assertTrue(payment.newlyQueued());
        var waiting = invoices.ownerView(home.propertyId(), home.ownerId(), 0, view.invoices().getLast().day());
        assertEquals(payment.receipt().requestId(), waiting.payments().get(oldest.invoiceId()).requestId());
        assertFalse(BillText.payable(oldest, waiting.payments().get(oldest.invoiceId()), DAY));
        assertTrue(String.join(" ", BillText.detail(oldest, waiting.titles(), waiting.payments().get(oldest.invoiceId()), DAY))
                .contains("Do not pay again"));
        assertTrue(view.titles().isEmpty());
        var wrongTitle = Map.of("test-bill", "Today's new title");
        assertTrue(BillText.list(view.invoices(), wrongTitle, DAY).contains("    test-bill v1: $1.00"));
        assertFalse(String.join(" ", BillText.list(view.invoices(), wrongTitle, DAY)).contains("Today's new title"));
        execute("UPDATE npc_properties SET enabled=FALSE");
        assertTrue(invoices.ownerView(home.propertyId(), home.ownerId(), 0).invoices().isEmpty());
        assertTrue(invoices.ownerInvoice(home.propertyId(), home.ownerId(), 0, oldest.invoiceId()).isEmpty());
        assertEquals(12, count("npc_charge_invoices"));
    }

    @Test void undoingAFutureRemovalKeepsTodaysTermsInForce() throws Exception {
        var original = catalogue(100, "flat");
        invoices.record(emptyCatalogue(), DAY, DAY.plusDays(1));
        var retiring = invoices.policyPeriods(DAY);
        assertEquals(1, BillingRuntime.inForce(retiring, DAY).size());
        assertTrue(BillingRuntime.inForce(retiring, DAY.plusDays(1)).isEmpty());
        invoices.record(original, DAY, DAY.plusDays(1));
        var restored = invoices.policyPeriods(DAY);
        assertEquals(DAY, restored.getFirst().from());
        assertEquals(1, BillingRuntime.inForce(restored, DAY).size());
        assertEquals(1, BillingRuntime.inForce(restored, DAY.plusDays(1)).size());
    }

    @Test void replacingAStagedVersionDoesNotLeaveTwoVersionsActiveTomorrow() throws Exception {
        catalogue(100, "flat");
        invoices.record(definition("200", "flat", 2), DAY, DAY.plusDays(1));
        invoices.record(definition("300", "flat", 3), DAY, DAY.plusDays(1));
        var periods = invoices.policyPeriods(DAY);
        assertEquals(2, periods.size());
        assertEquals(1, BillingRuntime.inForce(periods, DAY).getFirst().version());
        assertEquals(3, BillingRuntime.inForce(periods, DAY.plusDays(1)).getFirst().version());
        assertEquals(3, invoices.recordedPolicies().size());
    }

    @Test void reinstatingARetiredVersionWaitsUntilTheNextDay() throws Exception {
        var original = catalogue(100, "flat");
        invoices.record(emptyCatalogue(), DAY, DAY.plusDays(1));
        invoices.record(original, DAY.plusDays(1), DAY.plusDays(2));
        var periods = invoices.policyPeriods(DAY.plusDays(1));
        assertTrue(BillingRuntime.inForce(periods, DAY.plusDays(1)).isEmpty());
        assertEquals(1, BillingRuntime.inForce(periods, DAY.plusDays(2)).getFirst().version());
    }

    @Test void aSupersededLoadRollsBackItsDefinitionsAndRetirements() throws Exception {
        catalogue(100, "flat");
        var before = invoices.recordedPolicies();
        var reads = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(SQLException.class, () -> invoices.record(definition("200", "flat", 2), DAY, DAY.plusDays(1),
                () -> reads.incrementAndGet() == 1));
        assertEquals(2, reads.get());
        assertEquals(before, invoices.recordedPolicies());
        assertEquals(1, BillingRuntime.inForce(invoices.policyPeriods(DAY), DAY.plusDays(1)).getFirst().version());
    }

    @Test void overlappingRecordedVersionsRefuseTheWholeCatalogue() throws Exception {
        catalogue(100, "flat");
        invoices.record(definition("200", "flat", 2), DAY, DAY.plusDays(1));
        execute("UPDATE npc_charge_policies SET effective_day='" + DAY + "' WHERE version=2");
        assertThrows(SQLException.class, () -> invoices.policyPeriods(DAY));
    }

    @Test void concurrentHomesCannotBothSpendTheLastServerHeadroom() throws Exception {
        var catalogue = catalogue(750, "flat");
        var a = home(UUID.randomUUID());
        var b = home(UUID.randomUUID());
        execute("CREATE TRIGGER npc_bill_delay BEFORE INSERT ON npc_charge_invoices FOR EACH ROW DO SLEEP(0.3)");
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> { ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS)); return issue(a, catalogue, 1000, 1000); });
            var second = workers.submit(() -> { ready.countDown(); assertTrue(start.await(5, TimeUnit.SECONDS)); return issue(b, catalogue, 1000, 1000); });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertEquals(750, invoices.usage(DAY).issuedCents());
        assertEquals(1, invoices.usage(DAY).issued());
    }

    @Test void cancelledWorkAfterABudgetLockWaitCannotIssueAnInvoice() throws Exception {
        var catalogue = catalogue(100, "flat");
        var home = home(UUID.randomUUID());
        var current = new AtomicBoolean(true);
        try (var pool = Executors.newSingleThreadExecutor(); var lock = database.getConnection()) {
            // A table lock exposes the wait without relying on engine-specific lock tables.
            try (var statement = lock.createStatement()) { statement.execute("LOCK TABLES npc_rent_mutex WRITE"); }
            try {
                var pending = pool.submit(() -> invoices.issue(home, DAY, catalogue.policies(), Map.of(), Map.of(),
                        1000, 10000, 7, Set.of(), current::get));
                awaitBudgetWait(pending);
                current.set(false);
                try (var statement = lock.createStatement()) { statement.execute("UNLOCK TABLES"); }
                var refused = assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
                assertInstanceOf(SQLException.class, refused.getCause());
            } finally {
                try (var statement = lock.createStatement()) { statement.execute("UNLOCK TABLES"); }
            }
        }
        assertEquals(0, count("npc_charge_invoices"));
        assertEquals(0, count("npc_charge_invoice_lines"));
        assertEquals(0, count("npc_economy_events"));
        assertEquals(100, issue(home, catalogue, 1000, 10000).invoice().orElseThrow().totalCents());
    }

    private void awaitBudgetWait(Future<?> pending) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        try (var observer = database.getConnection(); var statement = observer.prepareStatement("SHOW FULL PROCESSLIST")) {
            statement.setQueryTimeout(1);
            while (System.nanoTime() < deadline) {
                assertFalse(pending.isDone(), "invoice must wait on the held budget row");
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String sql = rows.getString("Info"), state = rows.getString("State");
                        if (sql != null && sql.contains("FROM npc_rent_mutex WHERE singleton=1")
                                && state != null && state.toLowerCase(java.util.Locale.ROOT).contains("lock")) return;
                    }
                }
                Thread.sleep(10);
            }
        }
        fail("invoice did not reach the budget lock wait");
    }

    @Test void cancellationBeforeCommitRollsBackInvoiceLinesAndBudget() throws Exception {
        var catalogue = catalogue(100, "flat");
        var home = home(UUID.randomUUID());
        var checks = new AtomicInteger();
        assertThrows(SQLException.class, () -> invoices.issue(home, DAY, catalogue.policies(), Map.of(), Map.of(),
                1000, 10000, 7, Set.of(), () -> checks.incrementAndGet() == 1));
        assertEquals(2, checks.get());
        assertEquals(0, count("npc_charge_invoices"));
        assertEquals(0, count("npc_charge_invoice_lines"));
        assertEquals(0, invoices.usage(DAY).issuedCents());
        assertEquals(100, issue(home, catalogue, 1000, 10000).invoice().orElseThrow().totalCents());
        assertEquals(0, count("npc_economy_events"));
    }

    @Test void aCancelledFirstCumulativeReadingLeavesNoBaseline() throws Exception {
        var catalogue = catalogue("units", "power");
        var home = home(UUID.randomUUID());
        var checks = new AtomicInteger();
        assertThrows(SQLException.class, () -> invoices.issue(home, DAY, catalogue.policies(),
                Map.of("test-bill", new MeterRead.Frozen("power", 100L, null, "a".repeat(64))), Map.of(),
                1000, 10000, 7, Set.of("power"), () -> checks.incrementAndGet() == 1));
        assertEquals(2, checks.get());
        assertEquals(0, count("npc_meter_baselines"));
        assertEquals(0, count("npc_charge_invoices"));
        assertTrue(cumulative(home, catalogue, DAY, 100).invoice().isEmpty());
        assertEquals(30, cumulative(home, catalogue, DAY.plusDays(1), 130).invoice().orElseThrow().totalCents());
    }

    @Test void aCancelledExpiryLeavesTheInvoicePayable() throws Exception {
        var catalogue = catalogue(100, "flat");
        var invoice = issue(home(UUID.randomUUID()), catalogue, 1000, 10000).invoice().orElseThrow();
        var checks = new AtomicInteger();
        assertThrows(SQLException.class, () -> invoices.expire(DAY.plusDays(7), () -> checks.incrementAndGet() == 1));
        assertEquals(2, checks.get());
        assertEquals(ChargeInvoiceRepository.State.ISSUED, invoices.find(invoice.invoiceId()).orElseThrow().state());
        assertEquals(0, count("npc_charge_payments"));
        assertEquals(0, count("npc_economy_events"));
        assertEquals(1, invoices.expire(DAY.plusDays(7)));
    }

    @Test void aRetryKeepsItsOriginalInvoiceAndTheOwnerCapCoversOtherHomes() throws Exception {
        var catalogue = catalogue(750, "flat");
        UUID owner = UUID.randomUUID();
        var a = home(owner);
        var first = issue(a, catalogue, 1000, 10000);
        var stored = first.invoice().orElseThrow();
        var replay = issue(a, catalogue, 1000, 10000);
        assertTrue(replay.replay());
        assertEquals(stored, replay.invoice().orElseThrow());
        assertTrue(issue(home(owner), catalogue, 1000, 10000).invoice().isEmpty());
        assertEquals(750, invoices.usage(DAY).issuedCents());
        assertEquals(0, count("npc_economy_events"));
    }

    @Test void aStaleClassOrMarketCannotSelectTheWrongTax() throws Exception {
        var catalogue = catalogue(100, "flat");
        var a = home(UUID.randomUUID());
        execute("UPDATE npc_properties SET class_key='house',market_id='other-town'");
        assertTrue(issue(a, catalogue, 1000, 10000).invoice().isEmpty());
        assertEquals(0, count("npc_charge_invoices"));
    }

    @Test void expiryMovesNoMoneyAndDoesNotEraseAnUnresolvedPayment() throws Exception {
        var catalogue = catalogue(100, "flat");
        var a = home(UUID.randomUUID());
        var invoice = issue(a, catalogue, 1000, 10000).invoice().orElseThrow();
        assertEquals(0, invoices.expire(DAY.plusDays(6)));
        assertEquals(1, invoices.expire(DAY.plusDays(7)));
        assertEquals(ChargeInvoiceRepository.State.EXPIRED, invoices.find(invoice.invoiceId()).orElseThrow().state());
        assertEquals(0, count("npc_economy_events"));
        var b = home(UUID.randomUUID());
        var pending = issue(b, catalogue, 1000, 10000).invoice().orElseThrow();
        payments.preparePayment(UUID.randomUUID(), pending.invoiceId(), b.ownerId(), b.epoch(), DAY, 100, 1000);
        assertEquals(0, invoices.expire(DAY.plusDays(7)));
        assertEquals(ChargeInvoiceRepository.State.ISSUED, invoices.find(pending.invoiceId()).orElseThrow().state());
        assertEquals(1, count("npc_charge_payments"));
    }

    @Test void linkedAccountsCannotReopenTheDailyOwnerBudgetByTurningTheFlagOff() throws Exception {
        var catalogue = catalogue(750, "flat");
        UUID java = new UUID(66, 1), bedrock = new UUID(0, 6501);
        var links = new AccountLinkRepository(database);
        links.observe(java, bedrock, AccountLinkRepository.Source.FLOODGATE_LOGIN, CLOCK.instant(), DAY, RESERVE);
        invoices = new ChargeInvoiceRepository(database, payments, links, () -> true, CLOCK);
        var first = issue(home(java), catalogue, 1000, 10000).invoice().orElseThrow();
        invoices = new ChargeInvoiceRepository(database, payments, links, () -> false, CLOCK);
        assertTrue(issue(home(bedrock), catalogue, 1000, 10000).invoice().isEmpty());
        assertEquals(750, invoices.usage(DAY).issuedCents());
        assertEquals(1, count("npc_linked_account_days"));
        assertNotNull(first.benefitUnit());
    }

    @Test void cumulativeReadingsFreezeTheIncreaseAndAReplayDoesNotAdvanceTheBaseline() throws Exception {
        var catalogue = catalogue("units", "power");
        var home = home(UUID.randomUUID());
        assertTrue(cumulative(home, catalogue, DAY, 100).invoice().isEmpty());
        var invoice = cumulative(home, catalogue, DAY.plusDays(1), 130).invoice().orElseThrow();
        assertEquals(30, invoice.totalCents());
        assertEquals(30L, invoice.lines().getFirst().meterUnits());
        assertEquals(invoice, cumulative(home, catalogue, DAY.plusDays(1), 1000).invoice().orElseThrow());
        assertEquals(10, cumulative(home, catalogue, DAY.plusDays(2), 140).invoice().orElseThrow().totalCents());
        assertTrue(cumulative(home, catalogue, DAY.plusDays(3), 5).invoice().isEmpty());
        var afterReset = cumulative(home, catalogue, DAY.plusDays(4), 12).invoice().orElseThrow();
        assertEquals(7, afterReset.totalCents());
    }

    private ChargeInvoiceRepository.Issued cumulative(ChargeInvoiceRepository.Home home, ChargeCatalogue.Catalogue catalogue,
                                                     LocalDate day, long units) throws SQLException {
        Clock at = Clock.fixed(day.atTime(12, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        var repository = new ChargeInvoiceRepository(database, new BillPayments(database, RESERVE, at), null, () -> false, at);
        return repository.issue(home, day, catalogue.policies(),
                Map.of("test-bill", new MeterRead.Frozen("power", units, null, "a".repeat(64))), Map.of(),
                1000, 10000, 7, Set.of("power"));
    }

    @Test void replayingTheNewMigrationsPreservesAnExistingInvoice() throws Exception {
        var catalogue = catalogue(100, "flat");
        var invoice = issue(home(UUID.randomUUID()), catalogue, 1000, 10000).invoice().orElseThrow();
        for (String name : List.of("V41__charge_catalogue.sql", "V42__charge_invoices.sql", "V43__meter_baselines.sql")) {
            try (InputStream input = getClass().getResourceAsStream("/db/migration/" + name)) {
                assertNotNull(input);
                for (String sql : MigrationRunner.splitStatements(new String(input.readAllBytes(), StandardCharsets.UTF_8))) execute(sql);
            }
        }
        assertEquals(invoice, invoices.find(invoice.invoiceId()).orElseThrow());
        assertEquals(1, count("npc_properties"));
        assertEquals(1, count("npc_charge_invoice_lines"));
        assertEquals(0, count("npc_economy_events"));
    }

    @Test void rentFormulaUsesOnlyThisHomesProvedRentFromThePreviousDay() throws Exception {
        var catalogue = catalogue("rent_paid_cents", "flat");
        var home = home(UUID.randomUUID());
        var other = home(home.ownerId());
        var balances = new HashMap<>(Map.of(home.ownerId(), 100.0, RESERVE, 200.0));
        var calls = new ArrayList<String>();
        var transfer = new TransferDispatchFixture.Vault(new TransferJournal(database, RESERVE), balances,
                leg -> false, calls, (leg, refusal) -> fail("unexpected refusal"));
        payRent(home, DAY.minusDays(2), 150, transfer);
        payRent(home, DAY.minusDays(1), 275, transfer);
        payRent(home, DAY, 700, transfer);
        payRent(other, DAY.minusDays(1), 425, transfer);

        // A paid label alone does not prove that any rent reached the owner.
        long unproved = rentEvent(home, DAY.minusDays(1), 901);
        execute("UPDATE npc_economy_events SET state='PAID',paid_amount_cents=901,finalized_at=? WHERE event_id=?",
                CLOCK.instant(), unproved);
        var before = Map.copyOf(balances);
        var callsBefore = List.copyOf(calls);
        var invoice = issue(home, catalogue, 1000, 10000).invoice().orElseThrow();
        assertEquals(275, invoice.totalCents());
        assertEquals(275, invoice.lines().getFirst().rawCents());
        assertTrue(invoice.lines().getFirst().inputsJson().contains("\"rent_paid_cents\":275"));
        invoices = new ChargeInvoiceRepository(database, payments, null, () -> false, CLOCK);
        var replay = issue(home, catalogue, 1000, 10000);
        assertTrue(replay.replay());
        assertEquals(invoice, replay.invoice().orElseThrow());
        assertEquals(before, balances);
        assertEquals(callsBefore, calls);
        assertEquals(5, count("npc_economy_events"));
        assertEquals(4, count("npc_transfer_attempts"));
        assertEquals(8, count("npc_transfer_observations"));
        assertEquals(0, count("npc_charge_payments"));
    }

    private long rentEvent(ChargeInvoiceRepository.Home home, LocalDate day, long cents) throws SQLException {
        return new EconomyEventRepository(database).insertPending(EconomyEvent.pending("rent-test|" + UUID.randomUUID(),
                cents, EventSource.RENT, home.propertyId(), home.ownerId(), home.epoch(), home.ownerId(), day.toString()));
    }

    private void payRent(ChargeInvoiceRepository.Home home, LocalDate day, long cents,
                         TransferDispatchFixture.Vault transfer) throws SQLException {
        var handle = new TransferJournal.Handle(UUID.randomUUID(), rentEvent(home, day, cents), UUID.randomUUID(), "a".repeat(64));
        assertEquals(TransferJournal.State.PAID, transfer.submit(handle).toCompletableFuture().join().state());
    }

    @Test void aPaidBillConservesBothBalancesAndAddsOnlyUnfundedCash() throws Exception {
        var catalogue = catalogue(525, "flat");
        var home = home(UUID.randomUUID());
        var invoice = issue(home, catalogue, 1000, 10000).invoice().orElseThrow();
        payments = new BillPayments(database, RESERVE, Clock.systemUTC());
        UUID request = UUID.randomUUID();
        var prepared = payments.preparePayment(request, invoice.invoiceId(), home.ownerId(), 0, DAY, 525, 10_000);
        var replay = payments.preparePayment(request, invoice.invoiceId(), home.ownerId(), 0, DAY, 525, 10_000);
        assertTrue(prepared.newlyQueued());
        assertFalse(replay.newlyQueued());
        assertEquals(prepared.receipt(), replay.receipt());
        var balances = new HashMap<>(Map.of(home.ownerId(), 100.0, RESERVE, 50.0));
        var calls = new ArrayList<String>();
        var transfer = new TransferDispatchFixture.Vault(new TransferJournal(database, RESERVE), balances,
                leg -> false, calls, (leg, refusal) -> fail("unexpected refusal"));
        var handle = handle(prepared.receipt());
        assertEquals(TransferJournal.State.PAID, transfer.submit(handle).toCompletableFuture().join().state());
        assertEquals(9475, TransferProof.toCents(balances.get(home.ownerId())).orElseThrow());
        assertEquals(5525, TransferProof.toCents(balances.get(RESERVE)).orElseThrow());
        assertEquals(15_000, balances.values().stream().mapToLong(v -> TransferProof.toCents(v).orElseThrow()).sum());
        assertEquals(BillPayments.State.PAID, payments.reconcile(prepared.receipt().eventId()).orElseThrow().state());
        assertEquals(ChargeInvoiceRepository.State.PAID, invoices.find(invoice.invoiceId()).orElseThrow().state());
        var beforeReplay = calls.stream().filter(call -> !call.equals("release")).toList();
        payments = new BillPayments(database, RESERVE, Clock.systemUTC());
        assertEquals(BillPayments.State.PAID, payments.reconcile(prepared.receipt().eventId()).orElseThrow().state());
        assertEquals(TransferJournal.State.PAID, transfer.submit(handle).toCompletableFuture().join().state());
        assertEquals(beforeReplay, calls.stream().filter(call -> !call.equals("release")).toList());
        assertEquals(0, funding("successful_cash_sinks_cents"));
        assertEquals(0, funding("successful_cash_payouts_cents"));
        assertEquals(525, funding("successful_cash_unfunded_cents"));
        assertEquals(1, count("npc_charge_payments"));
        assertEquals(2, count("npc_transfer_observations"));
        var statement = desk(home, DAY, true);
        assertEquals(new OwnerDeskData.Bills(1, 0, 0), statement.bills().orElseThrow());
        assertEquals(525, statement.statement().expenseCents());
        assertEquals(0, statement.statement().grossCents());
        assertEquals(-525, statement.statement().netCents());
        assertTrue(desk(home, DAY, false).bills().isEmpty());
        var receipt = BillPayments.findByRequest(database, request, home.ownerId()).orElseThrow();
        assertEquals(BillPayments.State.PAID, receipt.state());
        assertEquals(525, receipt.cents());
        UUID nextOwner = UUID.randomUUID();
        new PropertyRepository(database).applyOwnerChange(home.propertyId(), home.ownerId(), nextOwner, 1, "receipt sale");
        assertEquals(receipt, BillPayments.findByRequest(database, request, home.ownerId()).orElseThrow());
        assertTrue(BillPayments.findByRequest(database, request, nextOwner).isEmpty());
        var sold = new ChargeInvoiceRepository.Home(home.propertyId(), nextOwner, 1, "room", "town");
        assertTrue(desk(sold, DAY, true).bills().isEmpty());
    }

    @Test void billSummaryUsesOnlyThisOwnershipAndTheSevenDayStatementWindow() throws Exception {
        var catalogue = catalogue(100, "flat");
        var home = home(UUID.randomUUID());
        assertTrue(desk(home, DAY, true).bills().isEmpty());
        for (int offset = -7; offset <= 1; offset++) {
            LocalDate day = DAY.plusDays(offset);
            invoices.issue(home, day, catalogue.policies(), Map.of(), Map.of(), 1000, 10000, 7, Set.of());
        }
        execute("UPDATE npc_charge_invoices SET state='EXPIRED' WHERE property_id=? AND economy_day=?",
                home.propertyId(), DAY.minusDays(2).toString());
        execute("UPDATE npc_charge_invoices SET payable_until_day=? WHERE property_id=? AND economy_day=?",
                DAY.minusDays(1).toString(), home.propertyId(), DAY.minusDays(3).toString());
        assertEquals(new OwnerDeskData.Bills(0, 5, 2), desk(home, DAY, true).bills().orElseThrow());
        assertEquals(9, count("npc_charge_invoices"));
        assertEquals(0, count("npc_charge_payments"));
        assertEquals(0, count("npc_economy_events"));
        var other = home(UUID.randomUUID());
        issue(other, catalogue, 1000, 10000);
        assertEquals(new OwnerDeskData.Bills(0, 5, 2), desk(home, DAY, true).bills().orElseThrow());
        UUID newOwner = UUID.randomUUID();
        new PropertyRepository(database).applyOwnerChange(home.propertyId(), home.ownerId(), newOwner, 1, "summary sale");
        new PropertyRepository(database).applyOwnerChange(home.propertyId(), newOwner, home.ownerId(), 2, "summary return");
        var returned = new ChargeInvoiceRepository.Home(home.propertyId(), home.ownerId(), 2, "room", "town");
        assertTrue(desk(returned, DAY, true).bills().isEmpty());
    }

    private OwnerDeskData desk(ChargeInvoiceRepository.Home home, LocalDate day, boolean includeBills) throws SQLException {
        var property = new PropertyRepository(database).findById(home.propertyId()).orElseThrow();
        return new OwnerDeskRepository(database).read(property.worldUuid(), property.regionId(), home.ownerId(), day,
                includeBills).orElseThrow();
    }

    @Test void pendingReceiptsArePayerScopedAndReadWithoutInventingRows() throws Exception {
        var catalogue = catalogue(100, "flat");
        var home = home(UUID.randomUUID());
        var invoice = issue(home, catalogue, 1000, 10000).invoice().orElseThrow();
        UUID request = UUID.randomUUID();
        var receipt = payments.preparePayment(request, invoice.invoiceId(), home.ownerId(), 0, DAY, 100, 1000).receipt();
        assertEquals(receipt, BillPayments.findByRequest(database, request, home.ownerId()).orElseThrow());
        assertTrue(BillPayments.findByRequest(database, request, UUID.randomUUID()).isEmpty());
        assertTrue(BillPayments.findByRequest(database, UUID.randomUUID(), home.ownerId()).isEmpty());
        assertEquals(1, count("npc_charge_payments"));
        assertEquals(1, count("npc_economy_events"));
        assertEquals(0, count("npc_transfer_attempts"));
        assertEquals(0, funding("successful_cash_unfunded_cents"));
    }

    @Test void aReceiptRefusesAnEventWhosePayerNoLongerMatchesItsInvoice() throws Exception {
        var catalogue = catalogue(100, "flat");
        var home = home(UUID.randomUUID());
        var invoice = issue(home, catalogue, 1000, 10000).invoice().orElseThrow();
        var receipt = payments.preparePayment(UUID.randomUUID(), invoice.invoiceId(), home.ownerId(), 0, DAY, 100, 1000).receipt();
        execute("UPDATE npc_economy_events SET counterparty_uuid=? WHERE event_id=?", UUID.randomUUID(), receipt.eventId());
        assertThrows(SQLException.class, () -> BillPayments.findByRequest(database, receipt.requestId(), home.ownerId()));
        assertEquals(1, count("npc_charge_payments"));
        assertEquals(0, count("npc_transfer_attempts"));
    }

    @Test void paidLabelsWithoutTransferProofCannotBecomeAReceipt() throws Exception {
        var catalogue = catalogue(100, "flat");
        var home = home(UUID.randomUUID());
        var invoice = issue(home, catalogue, 1000, 10000).invoice().orElseThrow();
        var receipt = payments.preparePayment(UUID.randomUUID(), invoice.invoiceId(), home.ownerId(), 0, DAY, 100, 1000).receipt();
        execute("UPDATE npc_economy_events SET state='PAID',paid_amount_cents=100,finalized_at='2026-10-17 12:00:00' WHERE event_id=?", receipt.eventId());
        execute("UPDATE npc_charge_payments SET state='PAID',outcome_code='TRANSFER_CONFIRMED',paid_at='2026-10-17 12:00:00' WHERE request_id=?", receipt.requestId());
        execute("UPDATE npc_charge_invoices SET state='PAID' WHERE invoice_id=?", invoice.invoiceId());
        assertThrows(SQLException.class, () -> BillPayments.findByRequest(database, receipt.requestId(), home.ownerId()));
        assertEquals(0, count("npc_transfer_attempts"));
        assertEquals(0, count("npc_transfer_observations"));
        assertEquals(0, funding("successful_cash_unfunded_cents"));
    }

    @Test void aPendingAttemptHoldsOnlyItsInvoiceAndPreservesExactReplay() throws Exception {
        var catalogue = catalogue(100, "flat");
        var home = home(UUID.randomUUID());
        var invoice = issue(home, catalogue, 1000, 10000).invoice().orElseThrow();
        UUID request = UUID.randomUUID();
        var first = payments.preparePayment(request, invoice.invoiceId(), home.ownerId(), 0, DAY, 100, 1000);
        assertThrows(BillPayments.InvoiceHeldException.class, () -> payments.preparePayment(UUID.randomUUID(),
                invoice.invoiceId(), home.ownerId(), 0, DAY, 100, 1000));
        var replay = payments.preparePayment(request, invoice.invoiceId(), home.ownerId(), 0, DAY, 100, 1000);
        assertFalse(replay.newlyQueued());
        assertEquals(first.receipt(), replay.receipt());
        assertEquals(BillPayments.State.WAITING, payments.history(invoice.invoiceId()).getFirst().state());
        assertEquals(ChargeInvoiceRepository.State.ISSUED, invoices.find(invoice.invoiceId()).orElseThrow().state());
        assertEquals(1, count("npc_charge_payments"));
        assertEquals(1, count("npc_economy_events"));
        assertEquals(0, count("npc_transfer_attempts"));
        assertEquals(0, count("npc_transfer_observations"));
    }

    @Test void anUnprovedStoredDeclineRemainsAnError() throws Exception {
        var catalogue = catalogue(100, "flat");
        var home = home(UUID.randomUUID());
        var invoice = issue(home, catalogue, 1000, 10000).invoice().orElseThrow();
        var first = payments.preparePayment(UUID.randomUUID(), invoice.invoiceId(), home.ownerId(), 0, DAY, 100, 1000);
        try (var c = database.getConnection(); var ps = c.prepareStatement(
                "UPDATE npc_charge_payments SET state='DECLINED',outcome_code='PAYMENT_DECLINED' WHERE request_id=?")) {
            ps.setBytes(1, Uuids.toBytes(first.receipt().requestId()));
            assertEquals(1, ps.executeUpdate());
        }
        SQLException failure = assertThrows(SQLException.class, () -> payments.preparePayment(UUID.randomUUID(),
                invoice.invoiceId(), home.ownerId(), 0, DAY, 100, 1000));
        assertFalse(failure instanceof BillPayments.InvoiceHeldException);
        assertEquals(1, count("npc_charge_payments"));
        assertEquals(1, count("npc_economy_events"));
    }

    @Test void theProvedDeclineLimitHoldsOnlyItsInvoice() throws Exception {
        var catalogue = catalogue(100, "flat");
        var home = home(UUID.randomUUID());
        var invoice = issue(home, catalogue, 1000, 10000).invoice().orElseThrow();
        payments = new BillPayments(database, RESERVE, Clock.systemUTC());
        var balances = new HashMap<>(Map.of(home.ownerId(), 10.0, RESERVE, 50.0));
        var calls = new ArrayList<String>();
        var dispatch = new TransferDispatchFixture.Vault(new TransferJournal(database, RESERVE), balances,
                leg -> leg == TransferProof.Leg.DEBIT, calls, (leg, refusal) -> { });
        for (int i = 0; i < 16; i++) {
            var receipt = payments.preparePayment(UUID.randomUUID(), invoice.invoiceId(), home.ownerId(), 0, DAY, 100, 1000).receipt();
            assertEquals(TransferJournal.State.DECLINED, dispatch.submit(handle(receipt)).toCompletableFuture().join().state());
            assertEquals(BillPayments.State.DECLINED, payments.reconcile(receipt.eventId()).orElseThrow().state());
        }
        assertThrows(BillPayments.InvoiceHeldException.class, () -> payments.preparePayment(UUID.randomUUID(),
                invoice.invoiceId(), home.ownerId(), 0, DAY, 100, 1000));
        assertEquals(16, count("npc_charge_payments"));
        assertEquals(16, count("npc_economy_events"));
        assertEquals(Map.of(home.ownerId(), 10.0, RESERVE, 50.0), balances);
        assertTrue(calls.stream().noneMatch(call -> call.startsWith("withdraw") || call.startsWith("deposit")));
    }

    @Test void aProvedDeclineCanRetryWithoutMovingTheFirstAttemptTwice() throws Exception {
        var catalogue = catalogue(100, "flat");
        var home = home(UUID.randomUUID());
        var invoice = issue(home, catalogue, 1000, 10000).invoice().orElseThrow();
        payments = new BillPayments(database, RESERVE, Clock.systemUTC());
        var first = payments.preparePayment(UUID.randomUUID(), invoice.invoiceId(), home.ownerId(), 0, DAY, 100, 1000).receipt();
        var balances = new HashMap<>(Map.of(home.ownerId(), 10.0, RESERVE, 50.0));
        var calls = new ArrayList<String>();
        var journal = new TransferJournal(database, RESERVE);
        var declined = new TransferDispatchFixture.Vault(journal, balances,
                leg -> leg == TransferProof.Leg.DEBIT, calls, (leg, refusal) -> { });
        assertEquals(TransferJournal.State.DECLINED, declined.submit(handle(first)).toCompletableFuture().join().state());
        assertEquals(BillPayments.State.DECLINED, payments.reconcile(first.eventId()).orElseThrow().state());
        assertEquals(BillPayments.State.DECLINED,
                BillPayments.findByRequest(database, first.requestId(), home.ownerId()).orElseThrow().state());
        assertEquals(Map.of(home.ownerId(), 10.0, RESERVE, 50.0), balances);
        assertTrue(calls.stream().noneMatch(call -> call.startsWith("withdraw") || call.startsWith("deposit")));
        assertEquals(ChargeInvoiceRepository.State.ISSUED, invoices.find(invoice.invoiceId()).orElseThrow().state());
        payments = new BillPayments(database, RESERVE, Clock.systemUTC());
        var retry = payments.preparePayment(UUID.randomUUID(), invoice.invoiceId(), home.ownerId(), 0, DAY, 100, 1000).receipt();
        assertEquals(2, retry.attemptNumber());
        var paid = new TransferDispatchFixture.Vault(journal, balances, leg -> false, calls, (leg, refusal) -> fail());
        assertEquals(TransferJournal.State.PAID, paid.submit(handle(retry)).toCompletableFuture().join().state());
        assertEquals(BillPayments.State.PAID, payments.reconcile(retry.eventId()).orElseThrow().state());
        assertEquals(BillPayments.State.DECLINED,
                BillPayments.findByRequest(database, first.requestId(), home.ownerId()).orElseThrow().state());
        assertEquals(List.of(BillPayments.State.DECLINED, BillPayments.State.PAID),
                payments.history(invoice.invoiceId()).stream().map(BillPayments.Receipt::state).toList());
        assertEquals(Map.of(home.ownerId(), 9.0, RESERVE, 51.0), balances);
        assertEquals(100, funding("successful_cash_unfunded_cents"));
        assertEquals(0, funding("successful_cash_sinks_cents"));
    }

    @Test void anUncertainCreditFreezesTheInvoiceWithoutARetryOrExpiry() throws Exception {
        var catalogue = catalogue(100, "flat");
        var home = home(UUID.randomUUID());
        var invoice = issue(home, catalogue, 1000, 10000).invoice().orElseThrow();
        payments = new BillPayments(database, RESERVE, Clock.systemUTC());
        var first = payments.preparePayment(UUID.randomUUID(), invoice.invoiceId(), home.ownerId(), 0, DAY, 100, 1000).receipt();
        var balances = new HashMap<>(Map.of(home.ownerId(), 10.0, RESERVE, 50.0));
        var calls = new ArrayList<String>();
        var transfer = new TransferDispatchFixture.Vault(new TransferJournal(database, RESERVE), balances,
                leg -> leg == TransferProof.Leg.CREDIT, calls, (leg, refusal) -> { });
        assertEquals(TransferJournal.State.AMBIGUOUS, transfer.submit(handle(first)).toCompletableFuture().join().state());
        assertEquals(BillPayments.State.REVIEW_REQUIRED, payments.reconcile(first.eventId()).orElseThrow().state());
        assertEquals(BillPayments.State.REVIEW_REQUIRED,
                BillPayments.findByRequest(database, first.requestId(), home.ownerId()).orElseThrow().state());
        assertThrows(SQLException.class, () -> payments.preparePayment(UUID.randomUUID(), invoice.invoiceId(),
                home.ownerId(), 0, DAY, 100, 1000));
        assertEquals(0, invoices.expire(DAY.plusDays(7)));
        assertEquals(ChargeInvoiceRepository.State.ISSUED, invoices.find(invoice.invoiceId()).orElseThrow().state());
        assertEquals(Map.of(home.ownerId(), 9.0, RESERVE, 50.0), balances);
        assertEquals(1, calls.stream().filter(call -> call.startsWith("withdraw")).count());
        assertTrue(calls.stream().noneMatch(call -> call.startsWith("deposit")));
        assertEquals(0, funding("successful_cash_unfunded_cents"));
        assertEquals(1, count("npc_charge_payments"));
    }

    @Test void aVerifiedRefundSettlesAPreviouslyReviewedBillWithoutMovingMoney() throws Exception {
        var catalogue = catalogue(100, "flat");
        var home = home(UUID.randomUUID());
        var invoice = issue(home, catalogue, 1000, 10000).invoice().orElseThrow();
        var first = payments.preparePayment(UUID.randomUUID(), invoice.invoiceId(), home.ownerId(), 0, DAY, 100, 1000).receipt();
        var journal = new TransferJournal(database, RESERVE);
        var handle = handle(first);
        var balances = new HashMap<>(Map.of(home.ownerId(), 10.0, RESERVE, 50.0));
        var calls = new ArrayList<String>();
        var transfer = new TransferDispatchFixture.Vault(journal, balances,
                leg -> leg == TransferProof.Leg.CREDIT, calls, (leg, refusal) -> { });
        assertEquals(TransferJournal.State.AMBIGUOUS, transfer.submit(handle).toCompletableFuture().join().state());
        assertEquals(BillPayments.State.REVIEW_REQUIRED, payments.reconcile(first.eventId()).orElseThrow().state());
        var callsBeforeRecovery = List.copyOf(calls);
        TransferDispatchFixture.checkBillRecovery(database, RESERVE, first.eventId(), home.ownerId());
        assertThrows(IllegalStateException.class, () -> TransferDispatchFixture.checkBillRecovery(database, RESERVE,
                first.eventId(), UUID.randomUUID()));
        UUID resolution = UUID.randomUUID();
        long before = Double.doubleToRawLongBits(9.0), after = Double.doubleToRawLongBits(10.0);
        assertThrows(SQLException.class, () -> journal.resolveRefundedDebit(handle, resolution, "staff:test",
                "b".repeat(64), "c".repeat(64), before, Double.doubleToRawLongBits(10.5)));
        assertEquals(TransferJournal.State.COMPENSATED, journal.resolveRefundedDebit(handle, resolution, "staff:test",
                "b".repeat(64), "c".repeat(64), before, after, false, false).state());
        assertEquals(0, count("npc_transfer_resolutions"));
        assertEquals(0, count("npc_refund_claims"));
        assertEquals(TransferJournal.State.AMBIGUOUS, journal.find(first.eventId()).orElseThrow().state());
        assertEquals(BillPayments.State.REVIEW_REQUIRED, payments.reconcile(first.eventId()).orElseThrow().state());

        // Staff refunded the debit separately; recovery only records that evidence.
        balances.put(home.ownerId(), 10.0);
        var resolved = journal.resolveRefundedDebit(handle, resolution, "staff:test", "b".repeat(64),
                "c".repeat(64), before, after);
        assertEquals(TransferJournal.State.COMPENSATED, resolved.state());
        assertEquals("bill", TransferDispatchFixture.recoveryDomain(database, first.eventId()));
        payments = new BillPayments(database, RESERVE, CLOCK);
        assertEquals("PAYMENT_DECLINED", TransferDispatchFixture.completeBillRecovery(database, RESERVE, first.eventId()));
        var completed = payments.reconcile(first.eventId()).orElseThrow();
        assertEquals(BillPayments.State.DECLINED, completed.state());
        assertEquals("PAYMENT_DECLINED", completed.outcome());
        assertNull(completed.paidAt());
        assertEquals(completed, BillPayments.findByRequest(database, first.requestId(), home.ownerId()).orElseThrow());
        assertEquals(completed, payments.reconcile(first.eventId()).orElseThrow());
        TransferDispatchFixture.checkBillRecovery(database, RESERVE, first.eventId(), home.ownerId());
        assertEquals("PAYMENT_DECLINED", TransferDispatchFixture.completeBillRecovery(database, RESERVE, first.eventId()));
        assertEquals(resolved, journal.resolveRefundedDebit(handle, resolution, "staff:test", "b".repeat(64),
                "c".repeat(64), before, after));
        assertThrows(SQLException.class, () -> journal.resolveRefundedDebit(handle, UUID.randomUUID(), "staff:test",
                "b".repeat(64), "c".repeat(64), before, after));
        assertEquals(ChargeInvoiceRepository.State.ISSUED, invoices.find(invoice.invoiceId()).orElseThrow().state());
        assertEquals(1, count("npc_transfer_resolutions"));
        assertEquals(1, count("npc_refund_claims"));
        assertEquals(0, funding("successful_cash_unfunded_cents"));
        assertEquals(0, funding("successful_cash_sinks_cents"));
        assertEquals(0, funding("successful_cash_payouts_cents"));
        assertEquals(callsBeforeRecovery, calls);
        assertEquals(Map.of(home.ownerId(), 10.0, RESERVE, 50.0), balances);
        var retry = payments.preparePayment(UUID.randomUUID(), invoice.invoiceId(), home.ownerId(), 0, DAY, 100, 1000);
        assertTrue(retry.newlyQueued());
        assertEquals(2, retry.receipt().attemptNumber());
        assertEquals(callsBeforeRecovery, calls);
    }

    @Test void aFailedLedgerLabelCannotReleaseAReviewedBillWithoutTransferProof() throws Exception {
        var catalogue = catalogue(100, "flat");
        var home = home(UUID.randomUUID());
        var invoice = issue(home, catalogue, 1000, 10000).invoice().orElseThrow();
        var first = payments.preparePayment(UUID.randomUUID(), invoice.invoiceId(), home.ownerId(), 0, DAY, 100, 1000).receipt();
        execute("UPDATE npc_charge_payments SET state='REVIEW_REQUIRED',outcome_code='TRANSFER_UNCERTAIN' WHERE event_id=?",
                first.eventId());
        execute("UPDATE npc_economy_events SET state='FAILED',paid_amount_cents=0,claimed_at=CURRENT_TIMESTAMP(3),"
                + "finalized_at=CURRENT_TIMESTAMP(3) WHERE event_id=?", first.eventId());
        execute("UPDATE npc_charge_payments SET outcome_code='UNKNOWN_HOLD' WHERE event_id=?", first.eventId());
        assertThrows(SQLException.class, () -> payments.reconcile(first.eventId()));
        assertThrows(IllegalStateException.class, () -> TransferDispatchFixture.checkBillRecovery(database, RESERVE,
                first.eventId(), home.ownerId()));
        execute("UPDATE npc_charge_payments SET outcome_code='TRANSFER_UNCERTAIN' WHERE event_id=?", first.eventId());
        assertEquals(BillPayments.State.REVIEW_REQUIRED, payments.reconcile(first.eventId()).orElseThrow().state());
        assertThrows(IllegalStateException.class, () -> TransferDispatchFixture.completeBillRecovery(database, RESERVE,
                first.eventId()));
        assertThrows(BillPayments.InvoiceHeldException.class, () -> payments.preparePayment(UUID.randomUUID(),
                invoice.invoiceId(), home.ownerId(), 0, DAY, 100, 1000));
        assertEquals(ChargeInvoiceRepository.State.ISSUED, invoices.find(invoice.invoiceId()).orElseThrow().state());
        assertEquals(0, count("npc_transfer_attempts"));
        assertEquals(0, count("npc_transfer_resolutions"));
        assertEquals(1, count("npc_charge_payments"));
    }

    @Test void recoveryPreflightRefusesAnUnmanagedBillEvent() throws Exception {
        var catalogue = catalogue(100, "flat");
        var home = home(UUID.randomUUID());
        var invoice = issue(home, catalogue, 1000, 10000).invoice().orElseThrow();
        var bill = payments.preparePayment(UUID.randomUUID(), invoice.invoiceId(), home.ownerId(), 0, DAY, 100, 1000).receipt();
        new EconomyEventRepository(database).insertPending(EconomyEvent.pending("orphan-bill|" + UUID.randomUUID(), 100,
                EventSource.BILLS, home.propertyId(), home.ownerId(), 0, home.ownerId(), DAY.toString()));
        assertThrows(SQLException.class, () -> TransferDispatchFixture.checkBillRecovery(database, RESERVE,
                bill.eventId(), home.ownerId()));
        assertEquals(0, count("npc_transfer_resolutions"));
        assertEquals(0, count("npc_transfer_attempts"));
        assertEquals(1, count("npc_charge_payments"));
    }

    @Test void recoveryStillFindsUtilityReceiptsWithoutTheConfiguredBillsTable() throws Exception {
        var home = home(UUID.randomUUID());
        var utility = new UtilityRepository(database, RESERVE, CLOCK, ZoneOffset.UTC, 0)
                .preparePayment(UUID.randomUUID(), home.propertyId(), home.ownerId(), 0, DAY).receipt();
        execute("RENAME TABLE npc_charge_payments TO npc_charge_payments_saved");
        try { assertEquals("utility", TransferDispatchFixture.recoveryDomain(database, utility.eventId())); }
        finally { execute("RENAME TABLE npc_charge_payments_saved TO npc_charge_payments"); }
        assertEquals(0, count("npc_transfer_attempts"));
    }

    @Test void recoveryRejectsAnEventClaimedByTwoReceiptTables() throws Exception {
        var catalogue = catalogue(100, "flat");
        var home = home(UUID.randomUUID());
        var invoice = issue(home, catalogue, 1000, 10000).invoice().orElseThrow();
        var bill = payments.preparePayment(UUID.randomUUID(), invoice.invoiceId(), home.ownerId(), 0, DAY, 100, 1000).receipt();
        var utility = new UtilityRepository(database, RESERVE, CLOCK, ZoneOffset.UTC, 0)
                .preparePayment(UUID.randomUUID(), home.propertyId(), home.ownerId(), 0, DAY).receipt();
        execute("UPDATE npc_charge_payments SET event_id=? WHERE event_id=?", utility.eventId(), bill.eventId());
        assertThrows(IllegalStateException.class, () -> TransferDispatchFixture.recoveryDomain(database, utility.eventId()));
        assertEquals(0, count("npc_transfer_resolutions"));
        assertEquals(0, count("npc_transfer_attempts"));
    }

    @Test void seededInvoicesConserveMoneyAcrossOwnersWithoutFundingRent() throws Exception {
        var catalogue = catalogue("100 + condition", "flat");
        payments = new BillPayments(database, RESERVE, Clock.systemUTC());
        var random = new java.util.Random(65);
        var balances = new HashMap<UUID, Double>();
        balances.put(RESERVE, 500.0);
        var calls = new ArrayList<String>();
        var transfer = new TransferDispatchFixture.Vault(new TransferJournal(database, RESERVE), balances,
                leg -> false, calls, (leg, refusal) -> fail());
        long total = 0;
        for (int i = 0; i < 16; i++) {
            var home = home(UUID.randomUUID());
            int condition = random.nextInt(101);
            try (var c = database.getConnection(); var ps = c.prepareStatement(
                    "UPDATE npc_properties SET condition_score=? WHERE property_id=?")) {
                ps.setInt(1, condition);
                ps.setBytes(2, Uuids.toBytes(home.propertyId()));
                assertEquals(1, ps.executeUpdate());
            }
            var invoice = issue(home, catalogue, 1000, 10000).invoice().orElseThrow();
            assertEquals(100 + condition, invoice.totalCents());
            balances.put(home.ownerId(), 100.0);
            long before = balances.values().stream().mapToLong(v -> TransferProof.toCents(v).orElseThrow()).sum();
            var receipt = payments.preparePayment(UUID.randomUUID(), invoice.invoiceId(), home.ownerId(), 0, DAY,
                    invoice.totalCents(), 10_000).receipt();
            assertEquals(TransferJournal.State.PAID, transfer.submit(handle(receipt)).toCompletableFuture().join().state());
            assertEquals(before, balances.values().stream().mapToLong(v -> TransferProof.toCents(v).orElseThrow()).sum());
            assertEquals(BillPayments.State.PAID, payments.reconcile(receipt.eventId()).orElseThrow().state());
            total += invoice.totalCents();
        }
        assertEquals(total, funding("successful_cash_unfunded_cents"));
        assertEquals(0, funding("successful_cash_sinks_cents"));
        assertEquals(0, funding("successful_cash_payouts_cents"));
        assertEquals(50_000 + total, TransferProof.toCents(balances.get(RESERVE)).orElseThrow());
        assertEquals(16, calls.stream().filter(call -> call.startsWith("withdraw")).count());
        assertEquals(16, calls.stream().filter(call -> call.startsWith("deposit")).count());
    }

    @Test void expectedHomeIsCheckedBeforePreparingOrReplayingAnAttempt() throws Exception {
        var catalogue = catalogue(100, "flat");
        var home = home(UUID.randomUUID());
        var other = home(home.ownerId());
        var invoice = issue(home, catalogue, 1000, 10000).invoice().orElseThrow();
        UUID request = UUID.randomUUID();
        assertThrows(BillPayments.InvoiceUnavailableException.class, () -> payments.preparePayment(request,
                invoice.invoiceId(), home.ownerId(), 0, DAY, 100, 1000, other.propertyId()));
        assertEquals(0, count("npc_charge_payments"));
        assertEquals(0, count("npc_economy_events"));
        var first = payments.preparePayment(request, invoice.invoiceId(), home.ownerId(), 0, DAY, 100, 1000, home.propertyId());
        assertTrue(first.newlyQueued());
        var replay = payments.preparePayment(request, invoice.invoiceId(), home.ownerId(), 0, DAY, 100, 1000, home.propertyId());
        assertFalse(replay.newlyQueued());
        assertEquals(first.receipt(), replay.receipt());
        assertThrows(BillPayments.InvoiceUnavailableException.class, () -> payments.preparePayment(request,
                invoice.invoiceId(), home.ownerId(), 0, DAY, 100, 1000, other.propertyId()));
        assertEquals(1, count("npc_charge_payments"));
        assertEquals(1, count("npc_economy_events"));
        assertEquals(0, count("npc_transfer_attempts"));
        assertEquals(0, funding("successful_cash_unfunded_cents"));
    }

    @Test void maxFundsExpiryAndOwnershipRefusalsWriteNoAttempt() throws Exception {
        var catalogue = catalogue(100, "flat");
        var home = home(UUID.randomUUID());
        var invoice = issue(home, catalogue, 1000, 10000).invoice().orElseThrow();
        assertThrows(BillPayments.MaximumExceededException.class, () -> payments.preparePayment(UUID.randomUUID(),
                invoice.invoiceId(), home.ownerId(), 0, DAY, 99, 1000));
        assertThrows(BillPayments.InsufficientFundsException.class, () -> payments.preparePayment(UUID.randomUUID(),
                invoice.invoiceId(), home.ownerId(), 0, DAY, 100, 99));
        assertThrows(BillPayments.InvoiceUnavailableException.class, () -> payments.preparePayment(UUID.randomUUID(),
                invoice.invoiceId(), home.ownerId(), 0, DAY.plusDays(7), 100, 1000));
        execute("UPDATE npc_properties SET ownership_epoch=ownership_epoch+1");
        assertThrows(BillPayments.InvoiceUnavailableException.class, () -> payments.preparePayment(UUID.randomUUID(),
                invoice.invoiceId(), home.ownerId(), 0, DAY, 100, 1000));
        assertEquals(0, count("npc_charge_payments"));
        assertEquals(0, count("npc_economy_events"));
        assertEquals(0, count("npc_transfer_attempts"));
    }

    private static TransferJournal.Handle handle(BillPayments.Receipt receipt) {
        return new TransferJournal.Handle(UUID.randomUUID(), receipt.eventId(), UUID.randomUUID(), "a".repeat(64));
    }

    private static long funding(String column) throws SQLException {
        try (var c = database.getConnection(); var statement = c.createStatement();
             var rows = statement.executeQuery("SELECT COALESCE(SUM(" + column + "),0) FROM npc_property_funding")) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static void execute(String sql) throws SQLException {
        try (var c = database.getConnection(); var statement = c.createStatement()) { statement.execute(sql); }
    }

    private static void execute(String sql, Object... values) throws SQLException {
        try (var c = database.getConnection(); var statement = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                if (values[i] instanceof UUID uuid) statement.setBytes(i + 1, Uuids.toBytes(uuid));
                else statement.setObject(i + 1, values[i]);
            }
            statement.executeUpdate();
        }
    }

    private static long count(String table) throws SQLException {
        try (var c = database.getConnection(); var statement = c.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }
}

package com.prosperitysmp.npc.it;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.commerce.CommerceOrder;
import com.prosperitysmp.npc.commerce.CommerceOrderRepository;
import com.prosperitysmp.npc.commerce.CommerceOrderRepository.Budgets;
import com.prosperitysmp.npc.commerce.CommerceOrderRepository.PaymentDecision;
import com.prosperitysmp.npc.commerce.CommerceOrderRepository.PaymentResolution;
import com.prosperitysmp.npc.commerce.CustodyBytes;
import com.prosperitysmp.npc.commerce.ItemCustody.Source;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.economy.EconomyEvent;
import com.prosperitysmp.npc.economy.EconomyEventRepository;
import com.prosperitysmp.npc.economy.EventSource;
import com.prosperitysmp.npc.economy.RefundClaims;
import com.prosperitysmp.npc.economy.RefundEvidence;
import com.prosperitysmp.npc.economy.TransferJournal;
import com.prosperitysmp.npc.economy.TransferJournal.Handle;
import com.prosperitysmp.npc.economy.TransferJournal.Receipt;
import com.prosperitysmp.npc.economy.TransferJournal.State;
import com.prosperitysmp.npc.economy.TransferProof.Observation;
import com.prosperitysmp.npc.economy.TransferProof.Refusal;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyRepository;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * One staff refund closes one held payment. A refund can only reverse a debit made before it, so a refund already
 * recorded after a payment's debit began may be that payment's own refund, and the payment cannot use a refund of
 * the same balances again. Where a payer has two held payments of $10 and staff make one refund, that refund
 * resolves one of them, in either the transfer journal or commerce, but never both. A refund recorded before a
 * payment's debit began is a different refund, so a later payment with the same balances is still resolved.
 * One refund-observed.json resolves one payment, whoever paid and in either table, and a refund that ends where the
 * payment's own debit left the payer shows no refund at all.
 * Real MariaDB with every migration applied; no provider calls.
 */
@Timeout(300)
class RefundReuseIT {
    private static final String DAY = "2026-10-14";
    private static final LocalDate COMMERCE_DAY = LocalDate.of(2026, 10, 14);
    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    private static final UUID WORLD = new UUID(0, 6301);
    private static final String PROVIDER = "a".repeat(64);
    private static final String OPERATOR = "staff:test";
    private static final String EVIDENCE = "e".repeat(64);
    private static final Budgets BUDGETS = new Budgets(100, 6400, 6400, 1_000_000);
    private static final CustodyBytes CASE = new CustodyBytes(new byte[]{6, 3});
    private static final String REUSED = "This refund was already used to resolve another held payment. If staff made a"
            + " second, separate refund for this held payment, run the tool again with --apply --distinct-refund";
    private static final String EVIDENCE_REUSED = "This refund evidence file was already used to resolve another held payment";
    private static final String NOT_MOVED = "The refund ends at the balance this payment's debit left, so it shows no refund";
    private static final String OTHER_PAYMENT = "refund-observed.json names another payment";
    private static DB mariaDb;
    private static Database database;
    private static UUID resident;
    private UUID owner;
    private UUID reserve;
    private UUID property;
    private UUID run;
    private TransferJournal journal;
    private EconomyEventRepository events;
    private CommerceOrderRepository commerce;

    @BeforeAll
    static void boot() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        mariaDb.start();
        String schema = "npc_refund_" + UUID.randomUUID().toString().replace("-", "");
        mariaDb.createDB(schema);
        database = new Database();
        database.connect(new DatabaseSettings("localhost", cfg.getPort(), schema, "root", "", 6, 10_000L));
        var field = MigrationRunner.class.getDeclaredField("MIGRATIONS");
        field.setAccessible(true);
        try (Connection c = database.getConnection()) {
            for (Object migration : (List<?>) field.get(null)) {
                var resource = migration.getClass().getDeclaredMethod("resource");
                resource.setAccessible(true);
                try (InputStream in = RefundReuseIT.class.getResourceAsStream("/" + resource.invoke(migration))) {
                    assertNotNull(in);
                    for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8)))
                        try (Statement st = c.createStatement()) { st.execute(sql); }
                }
            }
        }
        var profile = ResidentCatalogue.betaCast().getFirst();
        new ResidentRepository(database).register(profile);
        resident = profile.id();
    }

    @AfterAll
    static void stop() throws Exception {
        try {
            if (database != null) database.close();
        } finally {
            if (mariaDb != null) mariaDb.stop();
        }
    }

    @BeforeEach
    void freshPayer() throws SQLException {
        owner = UUID.randomUUID();
        reserve = UUID.randomUUID();
        run = UUID.randomUUID();
        anotherHome();
        journal = new TransferJournal(database, reserve);
        events = new EconomyEventRepository(database);
        commerce = new CommerceOrderRepository(database, reserve);
    }

    /** Enrolls another home for the same owner; later transfers are paid for it. */
    private void anotherHome() throws SQLException {
        property = UUID.randomUUID();
        new PropertyRepository(database).insertEnrollment(new Property(property, WORLD, "refund-" + property, "downtown", "room",
                2, 1_000, 1_000, owner, 0, 80, true), 50_000);
    }

    @Test
    void oneRefundResolvesOnlyOneOfTwoHeldTransfers() throws Exception {
        Handle first = heldTransfer(50.0, 40.0), second = heldTransfer(61.5, 51.5);
        laterMillisecond();
        UUID resolution = UUID.randomUUID();
        String firstFile = file();
        Receipt done = journal.resolveRefundedDebit(first, resolution, OPERATOR, EVIDENCE, firstFile, bits(40.0), bits(50.0));
        assertEquals(State.COMPENSATED, done.state());

        // The same refund, offered again for the second held row: refused, in a dry run and for real.
        SQLException dry = assertThrows(SQLException.class, () -> journal.resolveRefundedDebit(second, UUID.randomUUID(),
                OPERATOR, EVIDENCE, file(), bits(40.0), bits(50.0), false, false));
        assertEquals(REUSED, dry.getMessage());
        SQLException reused = assertThrows(SQLException.class, () -> journal.resolveRefundedDebit(second, UUID.randomUUID(),
                OPERATOR, EVIDENCE, file(), bits(40.0), bits(50.0)));
        assertEquals(REUSED, reused.getMessage());
        assertEquals(State.AMBIGUOUS, transferState(second), "the second row stays held");

        // Replaying the first resolution exactly still returns it.
        assertEquals(done, journal.resolveRefundedDebit(first, resolution, OPERATOR, EVIDENCE, firstFile, bits(40.0), bits(50.0)));
        // A second, real refund that starts where the first ended resolves the second row.
        assertEquals(State.COMPENSATED, journal.resolveRefundedDebit(second, UUID.randomUUID(), OPERATOR, EVIDENCE, file(),
                bits(50.0), bits(60.0)).state());
    }

    @Test
    void oneRefundResolvesOnlyOneOfTwoHeldOrders() throws Exception {
        // Commerce holds one payment per player at a time, so the second order is paid for only after the first is
        // resolved. It was issued before that, and an order's debit is timed from its issue, so the refund recorded
        // for the first order counts as recorded after the second order's debit began.
        CommerceOrder first = heldOrder(owner, 50.0, 40.0);
        CommerceOrder second = issuedOrder(owner);
        laterMillisecond();
        PaymentResolution refund = refund(first, 40.0, 50.0);
        var done = commerce.reconcilePayment(first, handle(first), refund);
        assertEquals(CommerceOrderRepository.State.COMPENSATED, done.state());
        held(second, 61.5, 51.5);

        SQLException dry = assertThrows(SQLException.class,
                () -> commerce.reconcilePayment(second, handle(second), refund(second, 40.0, 50.0), false, false));
        assertEquals(REUSED, dry.getMessage());
        SQLException reused = assertThrows(SQLException.class,
                () -> commerce.reconcilePayment(second, handle(second), refund(second, 40.0, 50.0)));
        assertEquals(REUSED, reused.getMessage());
        assertEquals(CommerceOrderRepository.State.AMBIGUOUS, orderState(second), "the second order stays held");

        assertEquals(done, commerce.reconcilePayment(first, handle(first), refund), "exact replay");
        assertEquals(CommerceOrderRepository.State.COMPENSATED,
                commerce.reconcilePayment(second, handle(second), refund(second, 50.0, 60.0)).state());
    }

    @Test
    void oneRefundCannotResolveOneHeldPaymentInEachTable() throws Exception {
        // Transfer first, then commerce.
        Handle transfer = heldTransfer(50.0, 40.0);
        CommerceOrder order = heldOrder(owner, 50.0, 40.0);
        laterMillisecond();
        assertEquals(State.COMPENSATED, journal.resolveRefundedDebit(transfer, UUID.randomUUID(), OPERATOR, EVIDENCE, file(),
                bits(40.0), bits(50.0)).state());
        assertEquals(REUSED, assertThrows(SQLException.class,
                () -> commerce.reconcilePayment(order, handle(order), refund(order, 40.0, 50.0))).getMessage());
        assertEquals(CommerceOrderRepository.State.AMBIGUOUS, orderState(order));

        // Commerce first, then transfer, for another payer.
        freshPayer();
        Handle laterTransfer = heldTransfer(50.0, 40.0);
        CommerceOrder earlierOrder = heldOrder(owner, 50.0, 40.0);
        laterMillisecond();
        assertEquals(CommerceOrderRepository.State.COMPENSATED,
                commerce.reconcilePayment(earlierOrder, handle(earlierOrder), refund(earlierOrder, 40.0, 50.0)).state());
        assertEquals(REUSED, assertThrows(SQLException.class, () -> journal.resolveRefundedDebit(laterTransfer, UUID.randomUUID(),
                OPERATOR, EVIDENCE, file(), bits(40.0), bits(50.0))).getMessage());
        assertEquals(State.AMBIGUOUS, transferState(laterTransfer));
    }

    @Test
    void racingResolutionsInBothTablesLetOnlyOneUseTheRefund() throws Exception {
        Handle transfer = heldTransfer(50.0, 40.0);
        CommerceOrder order = heldOrder(owner, 50.0, 40.0);
        laterMillisecond();
        List<Throwable> refused = race(List.of(
                () -> journal.resolveRefundedDebit(transfer, UUID.randomUUID(), OPERATOR, EVIDENCE, file(), bits(40.0), bits(50.0)),
                () -> commerce.reconcilePayment(order, handle(order), refund(order, 40.0, 50.0))));
        assertEquals(1, refused.size(), "only one held payment can use the refund");
        assertEquals(REUSED, refused.getFirst().getMessage());
        assertEquals(1, (transferState(transfer) == State.COMPENSATED ? 1 : 0)
                + (orderState(order) == CommerceOrderRepository.State.COMPENSATED ? 1 : 0));
    }

    @Test
    void racingResolutionsInOneTableLetOnlyOneUseTheRefund() throws Exception {
        // No unique key stops two claims of the same balances, so each payer's claims must take turns. Each row is for
        // a different home, so the home's own lock does not already make them wait for each other.
        for (int round = 0; round < 4; round++) {
            freshPayer();
            List<Handle> rows = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                anotherHome();
                rows.add(heldTransfer(50.0, 40.0));
            }
            laterMillisecond();
            List<Callable<Object>> calls = new ArrayList<>();
            for (Handle row : rows)
                calls.add(() -> journal.resolveRefundedDebit(row, UUID.randomUUID(), OPERATOR, EVIDENCE, file(), bits(40.0), bits(50.0)));
            List<Throwable> refused = race(calls);
            assertEquals(2, refused.size(), "round " + round + ": only one held payment can use the refund");
            for (Throwable failure : refused) assertEquals(REUSED, failure.getMessage());
            int compensated = 0;
            for (Handle row : rows) if (transferState(row) == State.COMPENSATED) compensated++;
            assertEquals(1, compensated);
        }
    }

    @Test
    void aLaterPaymentWithTheSameBalancesIsResolvedByItsOwnRefund() throws Exception {
        // Payment A takes the player from $100 to $90, its credit is refused, and staff refund $90 back to $100.
        Handle a = heldTransfer(100.0, 90.0);
        laterMillisecond();
        assertEquals(State.COMPENSATED, journal.resolveRefundedDebit(a, UUID.randomUUID(), OPERATOR, EVIDENCE, file(),
                bits(90.0), bits(100.0)).state());
        // Later, payment B does exactly the same and staff genuinely refund it too. A's refund was recorded before
        // B's debit began, so it cannot be B's refund and does not block it.
        laterMillisecond();
        Handle b = heldTransfer(100.0, 90.0);
        laterMillisecond();
        assertEquals(State.COMPENSATED, journal.resolveRefundedDebit(b, UUID.randomUUID(), OPERATOR, EVIDENCE, file(),
                bits(90.0), bits(100.0)).state());
        // A later $5 payment whose refund shares only the after balance with both earlier refunds is resolved too.
        laterMillisecond();
        Handle c = heldTransfer(500, 100.0, 95.0);
        laterMillisecond();
        assertEquals(State.COMPENSATED, journal.resolveRefundedDebit(c, UUID.randomUUID(), OPERATOR, EVIDENCE, file(),
                bits(95.0), bits(100.0)).state());
        // The same holds in commerce.
        laterMillisecond();
        CommerceOrder order = heldOrder(owner, 100.0, 90.0);
        laterMillisecond();
        assertEquals(CommerceOrderRepository.State.COMPENSATED,
                commerce.reconcilePayment(order, handle(order), refund(order, 90.0, 100.0)).state());
        laterMillisecond();
        CommerceOrder next = heldOrder(owner, 100.0, 90.0);
        laterMillisecond();
        assertEquals(CommerceOrderRepository.State.COMPENSATED,
                commerce.reconcilePayment(next, handle(next), refund(next, 90.0, 100.0)).state());
    }

    @Test
    void aRefundRecordedAfterADebitBeganBlocksThatPaymentEvenWhenOneBalanceDiffers() throws Exception {
        // B and C were debited before A's refund was recorded, so that refund may be B's or C's own.
        Handle a = heldTransfer(100.0, 90.0);
        Handle b = heldTransfer(100.0, 90.0);
        Handle c = heldTransfer(500, 100.0, 95.0);
        laterMillisecond();
        assertEquals(State.COMPENSATED, journal.resolveRefundedDebit(a, UUID.randomUUID(), OPERATOR, EVIDENCE, file(),
                bits(90.0), bits(100.0)).state());
        assertEquals(REUSED, assertThrows(SQLException.class, () -> journal.resolveRefundedDebit(b, UUID.randomUUID(),
                OPERATOR, EVIDENCE, file(), bits(90.0), bits(100.0))).getMessage());
        // Sharing only the after balance is enough to refuse.
        assertEquals(REUSED, assertThrows(SQLException.class, () -> journal.resolveRefundedDebit(c, UUID.randomUUID(),
                OPERATOR, EVIDENCE, file(), bits(95.0), bits(100.0))).getMessage());
        assertEquals(State.AMBIGUOUS, transferState(b));
        assertEquals(State.AMBIGUOUS, transferState(c));
        // Another payer's refund of the same balances is that payer's own and does not block.
        UUID firstOwner = owner;
        freshPayer();
        assertNotEquals(firstOwner, owner);
        Handle other = heldTransfer(100.0, 90.0);
        laterMillisecond();
        assertEquals(State.COMPENSATED, journal.resolveRefundedDebit(other, UUID.randomUUID(), OPERATOR, EVIDENCE, file(),
                bits(90.0), bits(100.0)).state());
    }

    @Test
    void aRefundInTheOtherTableIsTimedTheSameWay() throws Exception {
        Handle transfer = heldTransfer(100.0, 90.0);
        laterMillisecond();
        assertEquals(State.COMPENSATED, journal.resolveRefundedDebit(transfer, UUID.randomUUID(), OPERATOR, EVIDENCE, file(),
                bits(90.0), bits(100.0)).state());
        laterMillisecond();
        CommerceOrder order = heldOrder(owner, 100.0, 90.0);
        laterMillisecond();
        assertEquals(CommerceOrderRepository.State.COMPENSATED,
                commerce.reconcilePayment(order, handle(order), refund(order, 90.0, 100.0)).state());
        laterMillisecond();
        Handle later = heldTransfer(100.0, 90.0);
        laterMillisecond();
        assertEquals(State.COMPENSATED, journal.resolveRefundedDebit(later, UUID.randomUUID(), OPERATOR, EVIDENCE, file(),
                bits(90.0), bits(100.0)).state());
    }

    @Test
    void oneEvidenceFileResolvesOnePaymentWhoeverPaidAndInEitherTable() throws Exception {
        String shared = file();
        Handle first = heldTransfer(50.0, 40.0);
        laterMillisecond();
        UUID resolution = UUID.randomUUID();
        Receipt done = journal.resolveRefundedDebit(first, resolution, OPERATOR, EVIDENCE, shared, bits(40.0), bits(50.0));
        assertEquals(State.COMPENSATED, done.state());
        assertEquals(done, journal.resolveRefundedDebit(first, resolution, OPERATOR, EVIDENCE, shared, bits(40.0), bits(50.0)),
                "exact replay");

        // Another payer, other balances: the file is spent, in a dry run and for real, in either table.
        freshPayer();
        Handle transfer = heldTransfer(80.0, 70.0);
        CommerceOrder order = heldOrder(owner, 81.0, 71.0);
        laterMillisecond();
        assertEquals(EVIDENCE_REUSED, refusal(() -> journal.resolveRefundedDebit(transfer, UUID.randomUUID(), OPERATOR, EVIDENCE,
                shared, bits(70.0), bits(80.0), false, false)));
        assertEquals(EVIDENCE_REUSED, refusal(() -> journal.resolveRefundedDebit(transfer, UUID.randomUUID(), OPERATOR, EVIDENCE,
                shared, bits(70.0), bits(80.0))));
        assertEquals(EVIDENCE_REUSED, refusal(() -> commerce.reconcilePayment(order, handle(order), refund(order, 71.0, 81.0, shared))));
        assertEquals(State.AMBIGUOUS, transferState(transfer));
        assertEquals(CommerceOrderRepository.State.AMBIGUOUS, orderState(order));

        // Each is resolved by its own file, and a file first used in commerce is spent for the journal too.
        String orderFile = file();
        assertEquals(CommerceOrderRepository.State.COMPENSATED,
                commerce.reconcilePayment(order, handle(order), refund(order, 71.0, 81.0, orderFile)).state());
        assertEquals(EVIDENCE_REUSED, refusal(() -> journal.resolveRefundedDebit(transfer, UUID.randomUUID(), OPERATOR, EVIDENCE,
                orderFile, bits(70.0), bits(80.0))));
        assertEquals(State.COMPENSATED, journal.resolveRefundedDebit(transfer, UUID.randomUUID(), OPERATOR, EVIDENCE, file(),
                bits(70.0), bits(80.0)).state());
    }

    @Test
    void twoPayersRacingWithOneEvidenceFileLetOnlyOneUseIt() throws Exception {
        // Different payers do not wait for each other's lock, so only the unique file hash stops the second.
        for (int round = 0; round < 4; round++) {
            String shared = file();
            freshPayer();
            Handle transfer = heldTransfer(50.0, 40.0);
            TransferJournal transferJournal = journal;
            freshPayer();
            CommerceOrder order = heldOrder(owner, 60.0, 50.0);
            laterMillisecond();
            List<Throwable> refused = race(List.of(
                    () -> transferJournal.resolveRefundedDebit(transfer, UUID.randomUUID(), OPERATOR, EVIDENCE, shared,
                            bits(40.0), bits(50.0)),
                    () -> commerce.reconcilePayment(order, handle(order), refund(order, 50.0, 60.0, shared))));
            assertEquals(1, refused.size(), "round " + round + ": only one payment can use the file");
            assertEquals(EVIDENCE_REUSED, refused.getFirst().getMessage());
        }
    }

    // The scenarios S1 to S5 from the dev.63 money check, as tests. Each resolution cites its own evidence file, as
    // staff write one per refund. S1 to S3 and S5 come out as before; S4 is now refused.

    @Test
    void scenarioS1OneRefundForTwoHeldRowsClosesOnlyOne() throws Exception {
        Handle a = heldTransfer(50.0, 40.0), b = heldTransfer(50.0, 40.0);
        laterMillisecond();
        assertEquals(State.COMPENSATED, resolve(a, 40.0, 50.0));
        assertEquals(REUSED, refusal(() -> resolve(b, 40.0, 50.0)));
        assertEquals(State.AMBIGUOUS, transferState(b));
        assertEquals(State.COMPENSATED, resolve(b, 50.0, 60.0), "a genuine second refund");
    }

    @Test
    void scenarioS2ALaterIdenticalPaymentIsResolvedByItsOwnRefund() throws Exception {
        Handle a = heldTransfer(100.0, 90.0);
        laterMillisecond();
        assertEquals(State.COMPENSATED, resolve(a, 90.0, 100.0));
        laterMillisecond();
        Handle b = heldTransfer(100.0, 90.0);
        laterMillisecond();
        assertEquals(State.COMPENSATED, resolve(b, 90.0, 100.0));
    }

    @Test
    void scenarioS3ARefundRecordedAfterTwoDebitsClosesOnlyOne() throws Exception {
        Handle a = heldTransfer(100.0, 90.0), c = heldTransfer(500, 100.0, 95.0);
        laterMillisecond();
        assertEquals(State.COMPENSATED, resolve(a, 90.0, 100.0));
        assertEquals(REUSED, refusal(() -> resolve(c, 95.0, 100.0)));
        assertEquals(State.AMBIGUOUS, transferState(c));
    }

    @Test
    void scenarioS4AnOldRefundCannotCloseALaterDebitThatLeftThePayerAtItsAfterBalance() throws Exception {
        // A takes the payer from $100 to $90 and staff refund $90 back to $100. Later D takes $10 from $110 to $100,
        // its credit is refused, and no refund is made: the payer is still at $100, where A's refund also ended.
        Handle a = heldTransfer(100.0, 90.0);
        laterMillisecond();
        String aFile = file();
        assertEquals(State.COMPENSATED, journal.resolveRefundedDebit(a, UUID.randomUUID(), OPERATOR, EVIDENCE, aFile,
                bits(90.0), bits(100.0)).state());
        laterMillisecond();
        Handle d = heldTransfer(110.0, 100.0);
        laterMillisecond();
        Properties copied = evidence(90.0, 100.0);
        assertDoesNotThrow(() -> RefundEvidence.requireSaved(copied, bits(100.0)), "the saved balance alone cannot tell");
        // A's refund under a new file, in a dry run and for real, and under A's own file: all refused.
        assertEquals(NOT_MOVED, refusal(() -> journal.resolveRefundedDebit(d, UUID.randomUUID(), OPERATOR, EVIDENCE, file(),
                bits(90.0), bits(100.0), false, false)));
        assertEquals(NOT_MOVED, refusal(() -> resolve(d, 90.0, 100.0)));
        assertThrows(SQLException.class, () -> journal.resolveRefundedDebit(d, UUID.randomUUID(), OPERATOR, EVIDENCE, aFile,
                bits(90.0), bits(100.0)));
        // A's record names A, so the tools refuse it for D before the journal is asked.
        assertEquals(OTHER_PAYMENT, assertThrows(IllegalArgumentException.class, () -> RefundEvidence.check(copied, owner,
                d.attemptId(), false, observed(owner, a.attemptId(), 90.0, 100.0))).getMessage());
        assertEquals(State.AMBIGUOUS, transferState(d), "D stays held for a real refund");
        // A real refund for D moves the payer: $100 back up to $110.
        assertEquals(State.COMPENSATED, resolve(d, 100.0, 110.0));

        // The same in commerce, for another payer.
        freshPayer();
        CommerceOrder first = heldOrder(owner, 100.0, 90.0);
        laterMillisecond();
        assertEquals(CommerceOrderRepository.State.COMPENSATED,
                commerce.reconcilePayment(first, handle(first), refund(first, 90.0, 100.0)).state());
        laterMillisecond();
        CommerceOrder later = heldOrder(owner, 110.0, 100.0);
        laterMillisecond();
        assertEquals(NOT_MOVED, refusal(() -> commerce.reconcilePayment(later, handle(later), refund(later, 90.0, 100.0))));
        assertEquals(CommerceOrderRepository.State.AMBIGUOUS, orderState(later));
        assertEquals(CommerceOrderRepository.State.COMPENSATED,
                commerce.reconcilePayment(later, handle(later), refund(later, 100.0, 110.0)).state());
    }

    @Test
    void scenarioS5AnOldRefundIsNoEvidenceForALaterDebitWithNoRefund() throws Exception {
        Handle a = heldTransfer(100.0, 90.0);
        laterMillisecond();
        String aFile = file();
        assertEquals(State.COMPENSATED, journal.resolveRefundedDebit(a, UUID.randomUUID(), OPERATOR, EVIDENCE, aFile,
                bits(90.0), bits(100.0)).state());
        laterMillisecond();
        Handle e = heldTransfer(100.0, 90.0);
        laterMillisecond();
        // No refund for E: the payer is still at $90, which is not the old refund's after value.
        Properties copied = evidence(90.0, 100.0);
        assertEquals("The payer's saved balance is not the refund's after value", assertThrows(IllegalArgumentException.class,
                () -> RefundEvidence.requireSaved(copied, bits(90.0))).getMessage());
        // A's own file and A's record are refused as well.
        assertEquals(EVIDENCE_REUSED, refusal(() -> journal.resolveRefundedDebit(e, UUID.randomUUID(), OPERATOR, EVIDENCE, aFile,
                bits(90.0), bits(100.0))));
        assertEquals(OTHER_PAYMENT, assertThrows(IllegalArgumentException.class, () -> RefundEvidence.check(copied, owner,
                e.attemptId(), false, observed(owner, a.attemptId(), 90.0, 100.0))).getMessage());
        assertEquals(State.AMBIGUOUS, transferState(e));
    }

    @Test
    void staffCanAssertASecondSeparateRefundForAnotherHeldTransfer() throws Exception {
        // A and B are both held: A took the payer from $100 to $90, then B from $90 to $80. After other trading,
        // staff refund A from $90 back up to $100.
        Handle a = heldTransfer(100.0, 90.0), b = heldTransfer(90.0, 80.0);
        laterMillisecond();
        String aFile = file();
        UUID aResolution = UUID.randomUUID();
        assertEquals(State.COMPENSATED, journal.resolveRefundedDebit(a, aResolution, OPERATOR, EVIDENCE, aFile,
                bits(90.0), bits(100.0)).state());
        laterMillisecond();
        // The payer spends $10, and staff make a second, separate refund for B: $90 back up to $100, in B's own file.
        // It matches A's refund, which was recorded after B's debit began, so by default it is refused.
        String bFile = file();
        UUID bResolution = UUID.randomUUID();
        String refused = refusal(() -> journal.resolveRefundedDebit(b, bResolution, OPERATOR, EVIDENCE, bFile,
                bits(90.0), bits(100.0)));
        assertEquals(REUSED, refused);
        assertTrue(refused.contains("--distinct-refund"), "the refusal tells staff the override exists");
        assertEquals(State.AMBIGUOUS, transferState(b));

        // The override skips only that rule. A spent file and a refund that leaves the payer where B's debit did
        // are still refused.
        assertEquals(EVIDENCE_REUSED, refusal(() -> distinctTransfer(b, UUID.randomUUID(), aFile, 90.0, 100.0)));
        assertEquals(NOT_MOVED, refusal(() -> distinctTransfer(b, UUID.randomUUID(), file(), 70.0, 80.0)));
        // The tools' own checks never see the flag: A's record names A, and the saved balance must be the refund's after.
        Properties record = evidence(90.0, 100.0);
        assertEquals(OTHER_PAYMENT, assertThrows(IllegalArgumentException.class, () -> RefundEvidence.check(record, owner,
                b.attemptId(), false, observed(owner, a.attemptId(), 90.0, 100.0))).getMessage());
        assertEquals("The payer's saved balance is not the refund's after value", assertThrows(IllegalArgumentException.class,
                () -> RefundEvidence.requireSaved(record, bits(90.0))).getMessage());
        assertEquals(State.AMBIGUOUS, transferState(b));

        Receipt done = distinctTransfer(b, bResolution, bFile, 90.0, 100.0);
        assertEquals(State.COMPENSATED, done.state());
        assertTrue(distinctOverride("TRANSFER", bResolution), "the claim records that staff asserted a distinct refund");
        assertFalse(distinctOverride("TRANSFER", aResolution));
        assertEquals(done, distinctTransfer(b, bResolution, bFile, 90.0, 100.0), "exact replay");

        // The tool's report names the other claim with the same balances, so a refund told apart from the wrong one
        // can be found later. A resolution recorded without the flag says the flag changed nothing.
        String report = report(RefundClaims.Source.TRANSFER, b.attemptId(), bResolution);
        assertTrue(report.startsWith(RefundClaims.distinctRefundNotice(b.attemptId(), OPERATOR)
                + " Claims with the same balances: TRANSFER resolution " + aResolution + " at "), report);
        assertFalse(report.contains(bResolution.toString()), "the claim itself is not listed");
        assertEquals("--distinct-refund was given for payment " + a.attemptId() + ", but resolution " + aResolution
                + " was recorded earlier without it, so the flag changed nothing.",
                report(RefundClaims.Source.TRANSFER, a.attemptId(), aResolution));
        assertEquals("--distinct-refund was given for payment " + b.attemptId() + ", but resolution " + bResolution
                + " has no refund claim.", report(RefundClaims.Source.COMMERCE, b.attemptId(), bResolution));
    }

    @Test
    void staffCanAssertASecondSeparateRefundForAnotherHeldOrder() throws Exception {
        // The first order took the payer from $100 to $90. The second was issued before the first was refunded,
        // then paid from $100 to $90, and staff refund it separately.
        CommerceOrder first = heldOrder(owner, 100.0, 90.0);
        CommerceOrder second = issuedOrder(owner);
        laterMillisecond();
        PaymentResolution firstRefund = refund(first, 90.0, 100.0);
        assertEquals(CommerceOrderRepository.State.COMPENSATED,
                commerce.reconcilePayment(first, handle(first), firstRefund).state());
        held(second, 100.0, 90.0);
        laterMillisecond();
        PaymentResolution secondRefund = refund(second, 90.0, 100.0);
        assertEquals(REUSED, refusal(() -> commerce.reconcilePayment(second, handle(second), secondRefund)));
        assertEquals(EVIDENCE_REUSED, refusal(() -> commerce.reconcilePayment(second, handle(second),
                refund(second, 90.0, 100.0, firstRefund.refundEvidenceSha256()), false, true, true)));
        assertEquals(NOT_MOVED, refusal(() -> commerce.reconcilePayment(second, handle(second), refund(second, 80.0, 90.0),
                false, true, true)));
        assertEquals(CommerceOrderRepository.State.AMBIGUOUS, orderState(second));

        assertEquals(CommerceOrderRepository.State.COMPENSATED,
                commerce.reconcilePayment(second, handle(second), secondRefund, false, true, true).state());
        assertTrue(distinctOverride("COMMERCE", secondRefund.resolutionId()));
        assertFalse(distinctOverride("COMMERCE", firstRefund.resolutionId()));
        String report = report(RefundClaims.Source.COMMERCE, second.orderId(), secondRefund.resolutionId());
        assertTrue(report.startsWith(RefundClaims.distinctRefundNotice(second.orderId(), OPERATOR)
                + " Claims with the same balances: COMMERCE resolution " + firstRefund.resolutionId() + " at "), report);
    }

    @Test
    void theDistinctRefundReportNeverThrows() throws Exception {
        // It runs after the resolution has committed and its domain side has settled. A logger that throws and a
        // database that cannot be read are both swallowed, and the unreadable claim is still reported.
        Logger broken = Logger.getAnonymousLogger();
        broken.setUseParentHandlers(false);
        broken.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { throw new NoClassDefFoundError("logging"); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        UUID payment = UUID.randomUUID(), resolution = UUID.randomUUID();
        RefundClaims.reportDistinctRefund(database, RefundClaims.Source.TRANSFER, payment, resolution, OPERATOR, broken);
        List<String> lines = new ArrayList<>();
        try (Database closed = new Database()) {
            RefundClaims.reportDistinctRefund(closed, RefundClaims.Source.TRANSFER, payment, resolution, OPERATOR, broken);
            RefundClaims.reportDistinctRefund(closed, RefundClaims.Source.TRANSFER, payment, resolution, OPERATOR, capture(lines));
        }
        assertEquals(List.of("--distinct-refund was given for payment " + payment + ", but its claim could not be read back"
                + " (java.sql.SQLException); check npc_refund_claims for resolution " + resolution + "."), lines);
    }

    @Test
    void theDistinctRefundOverrideAppliesOnlyToARefund() throws Exception {
        CommerceOrder order = heldOrder(owner, 100.0, 90.0);
        PaymentResolution paid = new PaymentResolution(UUID.randomUUID(), "operator", "case-63", "Checked",
                order.requestSha256(), PaymentDecision.VERIFIED_PAID, CASE, null, null, null, NOW.plusSeconds(100));
        assertThrows(IllegalArgumentException.class,
                () -> commerce.reconcilePayment(order, handle(order), paid, false, true, true));
        assertEquals(CommerceOrderRepository.State.AMBIGUOUS, orderState(order));
    }

    /** Resolves a held transfer with staff asserting the refund is a separate one. */
    private Receipt distinctTransfer(Handle handle, UUID resolution, String file, double before, double after) throws SQLException {
        return journal.resolveRefundedDebit(handle, resolution, OPERATOR, EVIDENCE, file, bits(before), bits(after),
                false, true, true);
    }

    /** What a recovery tool would print and log for this resolution after an applied --distinct-refund run. */
    private static String report(RefundClaims.Source source, UUID payment, UUID resolution) {
        List<String> lines = new ArrayList<>();
        RefundClaims.reportDistinctRefund(database, source, payment, resolution, OPERATOR, capture(lines));
        assertEquals(1, lines.size());
        return lines.getFirst();
    }

    private static Logger capture(List<String> lines) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { lines.add(record.getMessage()); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        return logger;
    }

    private static boolean distinctOverride(String source, UUID resolution) throws SQLException {
        try (Connection c = database.getConnection(); var ps = c.prepareStatement(
                "SELECT distinct_override FROM npc_refund_claims WHERE source=? AND resolution_id=?")) {
            ps.setString(1, source);
            ps.setBytes(2, com.prosperitysmp.npc.db.Uuids.toBytes(resolution));
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next(), "claim recorded");
                return rs.getBoolean(1);
            }
        }
    }

    /** Starts the calls together and returns why each refused one failed. */
    private static List<Throwable> race(List<Callable<Object>> calls) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(calls.size());
        List<Throwable> refused = new ArrayList<>();
        try {
            List<Future<Object>> results = new ArrayList<>();
            for (Callable<Object> call : calls) results.add(workers.submit(() -> { start.await(); return call.call(); }));
            start.countDown();
            for (Future<Object> result : results) {
                try {
                    result.get(60, TimeUnit.SECONDS);
                } catch (ExecutionException failure) {
                    refused.add(failure.getCause());
                }
            }
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS));
        }
        return refused;
    }

    /**
     * Debits and refunds are compared by the database's own times, to the millisecond. Waiting a few out keeps
     * the steps in the order the database records them.
     */
    private static void laterMillisecond() throws InterruptedException {
        Thread.sleep(5);
    }

    /** A $10 payment whose debit was observed in full and whose credit was refused before its call: held. */
    private Handle heldTransfer(double before, double after) throws SQLException {
        return heldTransfer(1_000, before, after);
    }

    private Handle heldTransfer(long cents, double before, double after) throws SQLException {
        long event = events.insertPending(EconomyEvent.pending("refund|" + UUID.randomUUID(), cents, EventSource.UPGRADE,
                property, owner, 0, owner, DAY));
        Handle handle = new Handle(UUID.randomUUID(), event, run, PROVIDER);
        assertTrue(journal.beginDebit(handle).newlyStarted());
        assertEquals(owner, journal.find(event).orElseThrow().payer());
        assertEquals(State.CREDIT_READY, journal.recordDebit(handle,
                new Observation(true, true, cents / 100.0, before, after, false)).state());
        assertTrue(journal.beginCredit(handle).newlyStarted());
        assertEquals(State.AMBIGUOUS, journal.recordCredit(handle, refused()).state());
        return handle;
    }

    private CommerceOrder heldOrder(UUID player, double before, double after) throws SQLException {
        return held(issuedOrder(player), before, after);
    }

    /** A $10 order, issued and not yet paid for. */
    private CommerceOrder issuedOrder(UUID player) throws SQLException {
        UUID nonce = UUID.randomUUID();
        CommerceOrder order = new CommerceOrder(CommerceOrder.idFor(nonce, player, Source.MERCHANT_GOODS, COMMERCE_DAY), nonce,
                player, resident, Source.MERCHANT_GOODS, "merchant.stone", "STONE", 1, 1000, COMMERCE_DAY, NOW, 0);
        commerce.reserve(order, BUDGETS);
        return order;
    }

    /** Pays for the order: its debit is observed in full and its credit is refused before its call, so it is held. */
    private CommerceOrder held(CommerceOrder order, double before, double after) throws SQLException {
        CommerceOrderRepository.Handle h = handle(order);
        assertTrue(commerce.beginDebit(order, h).newlyStarted());
        commerce.recordDebit(h, new Observation(true, true, 10.0, before, after, false));
        assertTrue(commerce.beginCredit(h).newlyStarted());
        assertEquals(CommerceOrderRepository.State.AMBIGUOUS, commerce.recordCredit(h, refused(), NOW).state());
        return order;
    }

    private CommerceOrderRepository.Handle handle(CommerceOrder order) {
        return new CommerceOrderRepository.Handle(order.orderId(), run, order.playerId(), reserve, PROVIDER);
    }

    private static PaymentResolution refund(CommerceOrder order, double before, double after) {
        return refund(order, before, after, file());
    }

    private static PaymentResolution refund(CommerceOrder order, double before, double after, String file) {
        return new PaymentResolution(UUID.randomUUID(), "operator", "case-63", "Staff refund checked against saved balance",
                order.requestSha256(), PaymentDecision.VERIFIED_REFUNDED_DEBIT, CASE, bits(before), bits(after), file,
                NOW.plusSeconds(100));
    }

    /** Resolves a held transfer with a refund recorded in its own new file. */
    private State resolve(Handle handle, double before, double after) throws SQLException {
        return journal.resolveRefundedDebit(handle, UUID.randomUUID(), OPERATOR, EVIDENCE, file(), bits(before), bits(after)).state();
    }

    /** The SHA-256 of a new refund-observed.json: staff record each refund in its own file. */
    private static String file() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
    }

    private static String refusal(Executable call) {
        return assertThrows(SQLException.class, call).getMessage();
    }

    /** The evidence properties of a player's refund; the reserve never took part. */
    private static Properties evidence(double before, double after) {
        Properties p = new Properties();
        p.setProperty("refund_completed", "true");
        p.setProperty("refund_before_bits", Long.toString(bits(before)));
        p.setProperty("refund_after_bits", Long.toString(bits(after)));
        p.setProperty("reserve_before", "5000.00");
        p.setProperty("reserve_after", "5000.00");
        return p;
    }

    private static byte[] observed(UUID account, UUID payment, double before, double after) {
        return ("{\"account\":\"" + account + "\",\"payment\":\"" + payment + "\",\"before_bits\":" + bits(before)
                + ",\"after_bits\":" + bits(after) + "}").getBytes(StandardCharsets.UTF_8);
    }

    private State transferState(Handle handle) throws SQLException {
        return journal.find(handle.eventId()).orElseThrow().state();
    }

    private CommerceOrderRepository.State orderState(CommerceOrder order) throws SQLException {
        return commerce.find(order.playerId(), order.orderId()).orElseThrow().state();
    }

    private static Observation refused() {
        return Observation.refused(Refusal.LEASE_REFUSED, new IllegalStateException("private"), null, null);
    }

    private static long bits(double value) {
        return Double.doubleToRawLongBits(value);
    }
}

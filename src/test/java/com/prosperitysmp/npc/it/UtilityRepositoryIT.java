package com.prosperitysmp.npc.it;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.economy.BridgeApi;
import com.prosperitysmp.npc.economy.EconomyEvent;
import com.prosperitysmp.npc.economy.EconomyEventRepository;
import com.prosperitysmp.npc.economy.EventSource;
import com.prosperitysmp.npc.economy.TransferJournal;
import com.prosperitysmp.npc.economy.TransferJournal.Handle;
import com.prosperitysmp.npc.economy.TransferProof.Observation;
import com.prosperitysmp.npc.economy.UtilityBill;
import com.prosperitysmp.npc.economy.UtilityRepository;
import com.prosperitysmp.npc.economy.UtilityRepository.Preparation;
import com.prosperitysmp.npc.economy.UtilityRepository.Receipt;
import com.prosperitysmp.npc.economy.UtilityRepository.State;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyRepository;
import com.prosperitysmp.npc.property.Tenant;
import com.prosperitysmp.npc.property.TenantRepository;
import com.prosperitysmp.npc.property.UpgradeRepository;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Isolated real MariaDB, real utility/journal APIs, fixture observations only; never Vault. */
@Timeout(45)
class UtilityRepositoryIT {
    private static final Instant NOW = Instant.parse("2026-09-07T12:00:00.001Z");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 7);
    private static final ZoneId ZONE = ZoneId.of("America/Chicago");
    private static final int RESET = 4;
    private static final UUID WORLD = new UUID(0, 1900);
    private static final UUID OWNER = new UUID(0, 1901);
    private static final UUID RESERVE = new UUID(0, 1902);
    private static final String V13 = "/db/migration/V13__utility_obligations.sql";
    private static DB mariaDb;
    private static Database database;
    private static String schema;
    private static List<String> tables;
    private UUID property;
    private MutableClock clock;

    @BeforeAll
    static void bootDatabase() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        try {
            mariaDb.start();
            schema = "npc_utility_" + UUID.randomUUID().toString().replace("-", "");
            mariaDb.createDB(schema);
            database = new Database();
            database.connect(new DatabaseSettings("localhost", cfg.getPort(), schema, "root", "", 6, 10_000L));
            for (String file : List.of("V1__initial_schema.sql", "V2__resident_identity.sql",
                    "V3__purchase_receipts.sql", "V4__transfer_journal.sql", "V5__resident_route_tags.sql",
                    "V6__incident_repairs.sql", "V7__incident_rolls.sql", "V8__eviction_tenancies.sql",
                    "V9__housing_registry.sql", "V10__housing_moves.sql", "V11__housing_history_order.sql",
                    "V12__incident_service_receipts.sql", "V13__utility_obligations.sql",
                    "V16__rent_reservations.sql", "V33__payout_retirements.sql")) {
                applyResource("/db/migration/" + file);
            }
            // V40: the refusal columns the transfer and commerce readers expect, on the tables this schema has.
            try (var v40 = database.getConnection()) { com.prosperitysmp.npc.db.V40Fixture.apply(v40); }
            tables = rows("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() "
                    + "AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME").stream().map(List::getFirst).toList();
            assertTrue(tables.containsAll(List.of("npc_utility_bills", "npc_utility_payments")));
        } catch (Exception | AssertionError failure) {
            try { stopDatabase(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        try { if (database != null) database.close(); }
        finally {
            if (mariaDb != null) {
                DB stopping = mariaDb;
                mariaDb = null;
                stopping.stop();
            }
        }
    }

    @BeforeEach
    void freshFixture() throws SQLException {
        dropTriggers();
        try (Connection c = database.getConnection()) {
            execute(c, "SET SESSION foreign_key_checks=0");
            try { for (String table : tables) execute(c, "DELETE FROM " + table); }
            finally { execute(c, "SET SESSION foreign_key_checks=1"); }
        }
        clock = new MutableClock(NOW);
        execute("INSERT INTO npc_rent_mutex(singleton) VALUES (1)");
        property = enroll();
        occupy(property, 0);
        occupy(property, 3);
        new UpgradeRepository(database).setLevel(property, "ADVERTISING", 2, 1000);
        new UpgradeRepository(database).setLevel(property, "FACILITIES", 1, 1000);
        execute("UPDATE npc_upgrades SET disabled_incident_id=99 WHERE property_id=? AND upgrade_type='ADVERTISING'", property);
    }

    @Test
    void enrollmentSeedPoolSerializesAndDisabledPropertyDoesNotReplenishIt() throws Exception {
        PropertyRepository repo = new PropertyRepository(database);
        Property a = new Property(UUID.randomUUID(), WORLD, "seed-a", "test", "room", 1, 300, 300, OWNER, 0, 80, true);
        Property b = new Property(UUID.randomUUID(), WORLD, "seed-b", "test", "room", 1, 300, 300, OWNER, 0, 80, true);
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            Future<?> first = workers.submit(() -> { repo.insertEnrollmentWithSeedPool(a, 5000, 10000); return null; });
            Future<?> second = workers.submit(() -> { repo.insertEnrollmentWithSeedPool(b, 5000, 10000); return null; });
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertEquals(List.of(List.of("10000")), rows("SELECT SUM(one_time_seed_cents) FROM npc_property_funding"));
        assertEquals(List.of(List.of("0"), List.of("5000")), rows("SELECT one_time_seed_cents FROM npc_property_funding "
                + "WHERE property_id<>? ORDER BY one_time_seed_cents", property));
        execute("UPDATE npc_properties SET enabled=0");
        Property next = new Property(UUID.randomUUID(), WORLD, "seed-c", "test", "room", 1, 300, 300, OWNER, 0, 80, true);
        repo.insertEnrollmentWithSeedPool(next, 5000, 10000);
        assertEquals(List.of(List.of("0")), rows("SELECT one_time_seed_cents FROM npc_property_funding WHERE property_id=?", next.propertyId()));
        assertEquals(List.of(List.of("0")), rows("SELECT COUNT(*) FROM npc_economy_events"));
    }

    @Test
    void pauseResumePreservesHistoryAndReconcilesNewOwnerWithoutReseeding() throws Exception {
        PropertyRepository properties = new PropertyRepository(database);
        Property original = properties.findById(property).orElseThrow();
        long[] funding = properties.fundingOf(property);
        assertTrue(properties.disableExpected(original));
        Property paused = properties.findById(property).orElseThrow();
        UUID newOwner = UUID.randomUUID();
        Property resumed = properties.resumeExpected(paused, newOwner);
        assertTrue(resumed.enabled());
        assertEquals(newOwner, resumed.ownerUuid());
        assertEquals(original.ownershipEpoch() + 1, resumed.ownershipEpoch());
        assertArrayEquals(funding, properties.fundingOf(property));
        assertEquals(List.of(List.of("2")), rows("SELECT COUNT(*) FROM npc_tenants WHERE property_id=?", property));
        assertEquals(List.of(List.of("0")), rows("SELECT COUNT(*) FROM npc_economy_events"));
        assertThrows(PropertyRepository.ResumeRefused.class, () -> properties.resumeExpected(paused, newOwner));
    }

    @Test
    void pauseResumeRefusesPendingUtilityRatherThanDiscardingIt() throws Exception {
        Receipt payment = repo().preparePayment(UUID.randomUUID(), property, OWNER, 0, DAY).receipt();
        PropertyRepository properties = new PropertyRepository(database);
        assertTrue(properties.disableExpected(properties.findById(property).orElseThrow()));
        Property paused = properties.findById(property).orElseThrow();
        assertThrows(PropertyRepository.ResumeRefused.class, () -> properties.resumeExpected(paused, OWNER));
        assertFalse(properties.findById(property).orElseThrow().enabled());
        assertEquals(List.of(List.of("PENDING")), rows("SELECT state FROM npc_economy_events WHERE event_id=?", payment.eventId()));
    }

    @AfterEach
    void dropTriggers() throws SQLException {
        if (database != null && database.isConnected()) {
            execute("DROP TRIGGER IF EXISTS utility_insert_failure");
            execute("DROP TRIGGER IF EXISTS utility_clock_gate");
        }
    }

    @Test
    void rentRequiresPaidUtilityAndPendingOrUnprovedFailedPayoutKeepsServerBudgetReserved() throws Exception {
        var constructor=com.prosperitysmp.npc.config.EconomyLimits.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        var limits=(com.prosperitysmp.npc.config.EconomyLimits)constructor.newInstance(1000L,4500L,1500L,600L,0L,3500000L,5000L,50000L,0.9);
        var rents=new com.prosperitysmp.npc.economy.RentReservationRepository(database,RESERVE);
        var p=new PropertyRepository(database).findById(property).orElseThrow();
        assertTrue(rents.reserve(DAY,List.of(p),limits).isEmpty());
        var utility=prepare(UUID.randomUUID()).receipt();pay(utility,clock.instant().plusSeconds(1));
        assertEquals(State.PAID,repo().reconcile(utility.eventId()).orElseThrow().state());
        var reserved=rents.reserve(DAY,List.of(p),limits);
        assertEquals(1,reserved.size());assertEquals(1000,reserved.getFirst().cents());
        assertTrue(rents.reserve(DAY,List.of(p),limits).isEmpty());
        UUID second=enroll();occupy(second,0);
        var utility2=repo().preparePayment(UUID.randomUUID(),second,OWNER,0,DAY).receipt();
        pay(utility2,clock.instant().plusSeconds(1));repo().reconcile(utility2.eventId());
        var p2=new PropertyRepository(database).findById(second).orElseThrow();
        assertTrue(rents.reserve(DAY,List.of(p2),limits).isEmpty(),"Pending rent consumes the server cap");
        execute("UPDATE npc_economy_events SET state='FAILED' WHERE event_id=?",reserved.getFirst().eventId());
        assertTrue(rents.reserve(DAY,List.of(p2),limits).isEmpty(),"An unproved failure cannot free budget");
        assertEquals(1,count("npc_rent_reservations","1=1"));
    }

    @Test
    void maximumChargeRejectionLeavesNoBillOrAttemptAndExactLimitCanProceed() throws Exception {
        var before = data();
        UUID request = UUID.randomUUID();
        assertThrows(UtilityRepository.MaximumExceededException.class,
                () -> repo().preparePayment(request, property, OWNER, 0, DAY, 399));
        assertEquals(before, data(), "over-limit preparation must roll back all new rows");
        var accepted = repo().preparePayment(request, property, OWNER, 0, DAY, 400);
        assertTrue(accepted.newlyQueued());
        assertEquals(400, accepted.receipt().bill().cents());
    }

    @Test
    void preparationPinsExactInputsAndQueuesOneUntouchedLedgerWithoutRuntimeEffects() throws Exception {
        List<List<String>> runtime = dataExcept("npc_utility_bills", "npc_utility_payments", "npc_economy_events");
        UUID request = UUID.randomUUID();
        Preparation preparation = prepare(request);
        Receipt receipt = preparation.receipt();
        UtilityBill expected = new UtilityBill(property, OWNER, 0, 0, DAY, 4, 2, 3, NOW, ZONE.getId(), RESET);
        assertTrue(preparation.newlyQueued());
        assertEquals(expected, receipt.bill());
        assertEquals(400, receipt.bill().cents(), "disabled installed levels still incur utilities");
        assertEquals(request, receipt.requestId());
        assertEquals(1, receipt.attemptNumber());
        assertTrue(receipt.eventId() > 0);
        assertEquals(State.WAITING, receipt.state());
        assertNull(receipt.outcome());
        assertNull(receipt.paidAt());
        assertEquals(expected.fingerprint(), scalar("SELECT payload_sha256 FROM npc_utility_bills"));
        assertEquals(NOW, timestamp("SELECT issued_at FROM npc_utility_bills"));
        assertEquals(expected.periodStart(), timestamp("SELECT period_start FROM npc_utility_bills"));
        assertEquals(expected.dueAt(), timestamp("SELECT due_at FROM npc_utility_bills"));
        assertEquals(List.of(List.of("400", EventSource.UTILITY.key(), "TRANSFER_FROM_PUBLIC", hex(property),
                        hex(OWNER), "0", DAY.toString(), "PENDING")),
                rows("SELECT requested_amount_cents,source,direction,property_id,counterparty_uuid,"
                        + "ownership_epoch,economy_day,state FROM npc_economy_events WHERE event_id=?", receipt.eventId()));
        assertNull(scalar("SELECT paid_amount_cents FROM npc_economy_events WHERE event_id=?", receipt.eventId()));
        assertNull(scalar("SELECT claimed_at FROM npc_economy_events WHERE event_id=?", receipt.eventId()));
        assertNull(scalar("SELECT finalized_at FROM npc_economy_events WHERE event_id=?", receipt.eventId()));
        assertEquals("utility|" + request, scalar("SELECT idempotency_key FROM npc_economy_events WHERE event_id=?", receipt.eventId()));
        assertEquals(runtime, dataExcept("npc_utility_bills", "npc_utility_payments", "npc_economy_events"));
        List<List<String>> beforeReplay = data();
        assertEquals(new Preparation(false, receipt), prepare(request));
        assertEquals(List.of(receipt), repo().history(property, DAY));
        assertEquals(receipt, repo().reconcile(receipt.eventId()).orElseThrow());
        assertEquals(beforeReplay, data());
    }

    @Test
    void emptyReadsAreReadOnlyAndMigrationRepeatsPreserveRowsAndSupportingIndexes() throws Exception {
        List<List<String>> empty = data();
        assertTrue(repo().history(property, DAY).isEmpty());
        assertThrows(SQLException.class, () -> repo().history(UUID.randomUUID(), DAY));
        assertTrue(repo().reconcile(Long.MAX_VALUE).isEmpty());
        assertEquals(empty, data());
        Receipt waiting = prepare(UUID.randomUUID()).receipt();
        List<List<String>> before = data();
        List<List<String>> indexes = rows("SELECT TABLE_NAME,INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME "
                + "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN "
                + "('npc_utility_bills','npc_utility_payments','npc_economy_events') ORDER BY 1,2,4");
        assertIndex("npc_utility_bills", "PRIMARY", false, "property_id", "economy_day");
        assertIndex("npc_utility_bills", "idx_utility_owner_due", true, "owner_uuid", "due_at", "property_id");
        assertIndex("npc_utility_payments", "uq_utility_attempt", false, "property_id", "economy_day", "attempt_number");
        assertIndex("npc_utility_payments", "uq_utility_event", false, "event_id");
        assertIndex("npc_economy_events", "idx_evt_utility_scope", true, "property_id", "source", "economy_day", "event_id");
        applyResource(V13);
        applyResource(V13);
        assertEquals(indexes, rows("SELECT TABLE_NAME,INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME "
                + "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN "
                + "('npc_utility_bills','npc_utility_payments','npc_economy_events') ORDER BY 1,2,4"));
        assertEquals(List.of(waiting), repo().history(property, DAY));
        assertEquals(before, data());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAID", "DECLINED"})
    void terminalNullOutcomeIsRejectedByMariaDbConstraintNotOnlyTheReader(String state) throws SQLException {
        Receipt waiting = prepare(UUID.randomUUID()).receipt();
        List<List<String>> before = data();
        SQLException failure = assertThrows(SQLException.class, () -> execute(
                "UPDATE npc_utility_payments SET state=?,paid_at=?,outcome_code=NULL WHERE event_id=?",
                state, state.equals("PAID") ? NOW : null, waiting.eventId()));
        assertTrue(failure.getMessage().contains("ck_utility_state"), failure::getMessage);
        assertEquals(before, data(), "SQL UNKNOWN must not bypass the terminal outcome constraint");
        assertEquals(List.of(waiting), repo().history(property, DAY));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "owner", "epoch", "disabled", "unowned", "past-day", "future-day"})
    void firstPreparationRequiresCurrentEnabledSqlOwnershipAndEconomyDay(String invalid) throws SQLException {
        UUID target = property;
        UUID payer = OWNER;
        int epoch = 0;
        LocalDate day = DAY;
        switch (invalid) {
            case "missing" -> target = UUID.randomUUID();
            case "owner" -> payer = UUID.randomUUID();
            case "epoch" -> epoch = 1;
            case "disabled" -> new PropertyRepository(database).setEnabled(property, false);
            case "unowned" -> execute("UPDATE npc_properties SET owner_uuid=NULL WHERE property_id=?", property);
            case "past-day" -> day = DAY.minusDays(1);
            case "future-day" -> day = DAY.plusDays(1);
            default -> throw new AssertionError(invalid);
        }
        UUID finalTarget = target;
        UUID finalPayer = payer;
        int finalEpoch = epoch;
        LocalDate finalDay = day;
        rejectedWithoutWrites(() -> repo().preparePayment(UUID.randomUUID(), finalTarget, finalPayer, finalEpoch, finalDay));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, 0, 1})
    void resetBoundaryIsInclusiveAndUsesConfiguredZoneNotUtcMidnight(long offset) throws SQLException {
        Instant reset = start(DAY);
        clock.set(reset.plusMillis(offset));
        LocalDate expectedDay = offset < 0 ? DAY.minusDays(1) : DAY;
        LocalDate wrongDay = offset < 0 ? DAY : DAY.minusDays(1);
        rejectedWithoutWrites(() -> repo().preparePayment(UUID.randomUUID(), property, OWNER, 0, wrongDay));
        Receipt receipt = repo().preparePayment(UUID.randomUUID(), property, OWNER, 0, expectedDay).receipt();
        assertEquals(expectedDay, receipt.bill().day());
        assertEquals(reset.plusMillis(offset), receipt.bill().issuedAt());
        assertEquals(start(expectedDay), receipt.bill().periodStart());
        assertEquals(start(expectedDay.plusDays(1)), receipt.bill().dueAt());
    }

    @Test
    void exactReplaySurvivesSaleDisabledPropertyDayAndClockPolicyChangesWithoutRepricing() throws SQLException {
        UUID request = UUID.randomUUID();
        Receipt original = prepare(request).receipt();
        new PropertyRepository(database).applyOwnerChange(property, OWNER, UUID.randomUUID(), 1, "utility IT sale");
        new PropertyRepository(database).setEnabled(property, false);
        execute("UPDATE npc_tenants SET property_id=NULL,slot=NULL,row_version=row_version+1 WHERE property_id=?", property);
        execute("UPDATE npc_upgrades SET level=3,disabled_incident_id=NULL WHERE property_id=?", property);
        clock.set(NOW.plusSeconds(3 * 86400));
        UtilityRepository changed = new UtilityRepository(database, RESERVE, clock, ZoneOffset.UTC, 0);
        List<List<String>> before = data();
        assertEquals(new Preparation(false, original), changed.preparePayment(request, property, OWNER, 0, DAY));
        assertEquals(List.of(original), changed.history(property, DAY));
        assertEquals(before, data());
    }

    @Test
    void sameRequestWithDifferentIdentityOrDayRejectsAndDoesNotLeaveAnOrphanBill() throws SQLException {
        UUID request = UUID.randomUUID();
        prepare(request);
        UUID other = enroll();
        rejectedWithoutWrites(() -> repo().preparePayment(request, other, OWNER, 0, DAY));
        rejectedWithoutWrites(() -> repo().preparePayment(request, property, UUID.randomUUID(), 0, DAY));
        rejectedWithoutWrites(() -> repo().preparePayment(request, property, OWNER, 1, DAY));
        clock.set(NOW.plusSeconds(86400));
        rejectedWithoutWrites(() -> repo().preparePayment(request, property, OWNER, 0, DAY.plusDays(1)));
        assertEquals(0, count("npc_utility_bills", "property_id=?", other));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void saleCannotReassignOriginalObligationEvenAfterProvenDecline(boolean sameOwner) throws SQLException {
        UUID request = UUID.randomUUID();
        Receipt declined = decline(prepare(request).receipt());
        UUID buyer = sameOwner ? OWNER : UUID.randomUUID();
        new PropertyRepository(database).applyOwnerChange(property, OWNER, buyer, 1, "utility IT sale");
        rejectedWithoutWrites(() -> repo().preparePayment(UUID.randomUUID(), property, buyer, 1, DAY));
        rejectedWithoutWrites(() -> prepare(UUID.randomUUID()));
        assertEquals(new Preparation(false, declined), prepare(request));
        assertEquals(OWNER, repo().history(property, DAY).getFirst().bill().ownerId());
    }

    @Test
    void actualPaymentUsesTypedLedgerFinalizationAndRemainsOriginalOwnerPaidAfterSale() throws SQLException {
        Receipt waiting = prepare(UUID.randomUUID()).receipt();
        Instant paidAt = NOW.plusMillis(10);
        pay(waiting, paidAt);
        new PropertyRepository(database).applyOwnerChange(property, OWNER, UUID.randomUUID(), 1, "utility IT paid sale");
        clock.set(NOW.plusSeconds(2 * 86400));
        Receipt paid = repo().reconcile(waiting.eventId()).orElseThrow();
        assertEquals(State.PAID, paid.state());
        assertEquals("TRANSFER_CONFIRMED", paid.outcome());
        assertEquals(paidAt, paid.paidAt());
        assertEquals(paidAt, timestamp("SELECT paid_at FROM npc_utility_payments WHERE event_id=?", waiting.eventId()));
        assertEquals(waiting.bill(), paid.bill());
        List<List<String>> before = data();
        assertEquals(paid, repo().reconcile(waiting.eventId()).orElseThrow());
        assertEquals(new Preparation(false, paid), prepare(waiting.requestId()));
        assertEquals(List.of(paid), repo().history(property, DAY));
        assertEquals(before, data());
        assertArrayEquals(new long[]{5000, waiting.bill().cents(), 0}, new PropertyRepository(database).fundingOf(property));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "DEBIT_INTENT", "CREDIT_READY", "CREDIT_INTENT"})
    void unfinishedPaymentsStayWaitingAndReplayNeverProducesAnotherDispatch(String phase) throws SQLException {
        Receipt waiting = prepare(UUID.randomUUID()).receipt();
        TransferJournal journal = journal();
        Handle handle = handle(waiting);
        if (!phase.equals("PENDING")) {
            assertTrue(journal.beginDebit(handle).newlyStarted());
            if (!phase.equals("DEBIT_INTENT")) {
                assertEquals(TransferJournal.State.CREDIT_READY, journal.recordDebit(handle, debit(waiting)).state());
                if (phase.equals("CREDIT_INTENT")) assertTrue(journal.beginCredit(handle).newlyStarted());
            }
        }
        List<List<String>> before = data();
        assertEquals(waiting, repo().reconcile(waiting.eventId()).orElseThrow());
        assertEquals(new Preparation(false, waiting), prepare(waiting.requestId()));
        assertEquals(List.of(waiting), repo().history(property, DAY));
        rejectedWithoutWrites(() -> prepare(UUID.randomUUID()));
        if (!phase.equals("PENDING")) assertFalse(journal.beginDebit(handle).newlyStarted());
        if (phase.equals("CREDIT_INTENT")) assertFalse(journal.beginCredit(handle).newlyStarted());
        assertEquals(before, data());
        assertArrayEquals(new long[]{5000, 0, 0}, new PropertyRepository(database).fundingOf(property));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAID", "FAILED", "AMBIGUOUS"})
    void bareLedgerLabelsAreReviewNotPaymentOrRetryAuthority(String label) throws SQLException {
        Receipt waiting = prepare(UUID.randomUUID()).receipt();
        execute("UPDATE npc_economy_events SET state=?,paid_amount_cents=?,claimed_at=?,finalized_at=? WHERE event_id=?",
                label, label.equals("PAID") ? Long.valueOf(waiting.bill().cents()) : label.equals("FAILED") ? Long.valueOf(0) : null,
                NOW, NOW.plusMillis(1), waiting.eventId());
        clock.set(NOW.plusSeconds(1));
        assertReview(waiting);
        assertEquals(0, count("npc_transfer_attempts", "1=1"));
        assertArrayEquals(new long[]{5000, 0, 0}, new PropertyRepository(database).fundingOf(property));
    }

    @Test
    void ambiguousActualDebitCannotBeRetriedOrAttributedAsPaid() throws SQLException {
        Receipt waiting = prepare(UUID.randomUUID()).receipt();
        Handle handle = handle(waiting);
        assertTrue(journal().beginDebit(handle).newlyStarted());
        assertEquals(TransferJournal.State.AMBIGUOUS,
                journal().recordDebit(handle, new Observation(false, false, null, 1000.0, null, true)).state());
        assertReview(waiting);
        assertEquals(1, count("npc_transfer_observations", "attempt_id=?", handle.attemptId()));
    }

    @Test
    void provenDeclinesAllowExplicitBoundedRetriesUsingTheOriginalBillAndContiguousHistory() throws SQLException {
        Receipt first = decline(prepare(UUID.randomUUID()).receipt());
        execute("UPDATE npc_tenants SET property_id=NULL,slot=NULL,row_version=row_version+1 WHERE property_id=?", property);
        execute("UPDATE npc_upgrades SET level=3,disabled_incident_id=NULL WHERE property_id=?", property);
        new PropertyRepository(database).setAskingRent(property, 9999);
        clock.set(NOW.plusSeconds(60));
        List<Receipt> expected = new ArrayList<>();
        expected.add(first);
        for (int attempt = 2; attempt <= 16; attempt++) {
            Preparation next = prepare(UUID.randomUUID());
            assertTrue(next.newlyQueued());
            assertEquals(attempt, next.receipt().attemptNumber());
            assertEquals(first.bill(), next.receipt().bill());
            assertNotEquals(expected.getLast().eventId(), next.receipt().eventId());
            expected.add(decline(next.receipt()));
        }
        List<List<String>> before = data();
        assertEquals(expected, repo().history(property, DAY));
        for (Receipt old : expected) assertEquals(new Preparation(false, old), prepare(old.requestId()));
        rejectedWithoutWrites(() -> prepare(UUID.randomUUID()));
        assertEquals(before, data());
        assertEquals(1, count("npc_utility_bills", "1=1"));
        assertEquals(16, count("npc_utility_payments", "1=1"));
        assertArrayEquals(new long[]{5000, 0, 0}, new PropertyRepository(database).fundingOf(property));
    }

    @ParameterizedTest
    @ValueSource(strings = {"receipt-only", "journal-only", "deleted-proof", "changed-proof"})
    void bothPersistedDeclineAndActualNoMovementProofAreRequiredForRetry(String missingGate) throws SQLException {
        Receipt waiting = prepare(UUID.randomUUID()).receipt();
        if (missingGate.equals("receipt-only")) {
            execute("UPDATE npc_utility_payments SET state='DECLINED',outcome_code='PAYMENT_DECLINED' WHERE event_id=?", waiting.eventId());
            execute("UPDATE npc_economy_events SET state='FAILED',paid_amount_cents=0,claimed_at=?,finalized_at=? WHERE event_id=?",
                    NOW, NOW, waiting.eventId());
        } else {
            recordDecline(waiting);
            if (!missingGate.equals("journal-only")) {
                assertEquals(State.DECLINED, repo().reconcile(waiting.eventId()).orElseThrow().state());
                if (missingGate.equals("deleted-proof")) execute("DELETE o FROM npc_transfer_observations o "
                        + "JOIN npc_transfer_attempts a ON a.attempt_id=o.attempt_id WHERE a.event_id=?", waiting.eventId());
                else execute("UPDATE npc_transfer_observations o JOIN npc_transfer_attempts a ON a.attempt_id=o.attempt_id "
                        + "SET o.after_bits=? WHERE a.event_id=?", Double.doubleToRawLongBits(999.0), waiting.eventId());
            }
        }
        rejectedWithoutWrites(() -> prepare(UUID.randomUUID()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rawLegacyUtilityEventBlocksNewOrRetriedBillWithoutInventingAttemptHistory(boolean existingBill) throws SQLException {
        if (existingBill) decline(prepare(UUID.randomUUID()).receipt());
        long legacy = new EconomyEventRepository(database).insertPending(EconomyEvent.pending("legacy-utility-" + UUID.randomUUID(),
                400, EventSource.UTILITY, property, OWNER, 0, OWNER, DAY.toString()));
        assertTrue(legacy > 0);
        rejectedWithoutWrites(() -> prepare(UUID.randomUUID()));
        rejectedWithoutWrites(() -> repo().history(property, DAY));
        assertEquals(existingBill ? 1 : 0, count("npc_utility_bills", "1=1"));
        assertEquals(existingBill ? 1 : 0, count("npc_utility_payments", "1=1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"capacity-zero", "capacity-five", "slot-null", "slot-negative", "slot-at-capacity",
            "duplicate-slot", "fifth-tenant", "unknown-upgrade", "negative-level", "fourth-level", "negative-revision"})
    void malformedCurrentSnapshotRejectsWithoutPartialBilling(String malformed) throws SQLException {
        switch (malformed) {
            case "capacity-zero" -> unchecked("UPDATE npc_properties SET capacity=0 WHERE property_id=?", property);
            case "capacity-five" -> unchecked("UPDATE npc_properties SET capacity=5 WHERE property_id=?", property);
            case "slot-null" -> execute("UPDATE npc_tenants SET slot=NULL WHERE property_id=? AND slot=3", property);
            case "slot-negative" -> execute("UPDATE npc_tenants SET slot=-1 WHERE property_id=? AND slot=3", property);
            case "slot-at-capacity" -> execute("UPDATE npc_tenants SET slot=4 WHERE property_id=? AND slot=3", property);
            case "duplicate-slot" -> execute("UPDATE npc_tenants SET slot=0 WHERE property_id=? AND slot=3", property);
            case "fifth-tenant" -> { occupy(property, 1); occupy(property, 2); occupy(property, 3); }
            case "unknown-upgrade" -> execute("UPDATE npc_upgrades SET upgrade_type='UNKNOWN' WHERE property_id=? AND upgrade_type='FACILITIES'", property);
            case "negative-level" -> unchecked("UPDATE npc_upgrades SET level=-1 WHERE property_id=?", property);
            case "fourth-level" -> unchecked("UPDATE npc_upgrades SET level=4 WHERE property_id=?", property);
            case "negative-revision" -> execute("UPDATE npc_properties SET row_version=-1 WHERE property_id=?", property);
            default -> throw new AssertionError(malformed);
        }
        rejectedWithoutWrites(() -> prepare(UUID.randomUUID()));
    }

    @ParameterizedTest
    @CsvSource({"1,0,0,100", "1,1,0,175", "4,4,6,700"})
    void validCapacityOccupancyAndUpgradeEndpointsUseOnlyActuallyOccupiedSlots(int capacity, int occupied, int levels, long cents)
            throws SQLException {
        execute("UPDATE npc_tenants SET property_id=NULL,slot=NULL WHERE property_id=?", property);
        execute("UPDATE npc_properties SET capacity=? WHERE property_id=?", capacity, property);
        execute("UPDATE npc_upgrades SET level=? WHERE property_id=?", levels / 2, property);
        for (int slot = 0; slot < occupied; slot++) occupy(property, slot);
        Receipt receipt = prepare(UUID.randomUUID()).receipt();
        assertEquals(occupied, receipt.bill().occupiedTenants());
        assertEquals(levels, receipt.bill().installedUpgradeLevels());
        assertEquals(cents, receipt.bill().cents());
    }

    @ParameterizedTest
    @ValueSource(strings = {"capacity=3", "property_revision=property_revision+1", "owner_uuid=UNHEX(REPEAT('ab',16))",
            "occupied_tenants=1,amount_cents=325", "installed_upgrade_levels=2,amount_cents=350",
            "issued_at=TIMESTAMPADD(MICROSECOND,1000,issued_at)", "period_start=TIMESTAMPADD(MICROSECOND,1000,period_start)",
            "due_at=TIMESTAMPADD(MICROSECOND,1000,due_at)", "clock_zone='UTC'", "reset_hour=5", "payload_sha256=REPEAT('0',64)"})
    void changedImmutableBillInputsOrFingerprintFailClosedOnAllEntrypoints(String assignment) throws SQLException {
        Receipt waiting = prepare(UUID.randomUUID()).receipt();
        unchecked("UPDATE npc_utility_bills SET " + assignment + " WHERE property_id=?", property);
        rejectedWithoutWrites(() -> repo().history(property, DAY));
        rejectedWithoutWrites(() -> prepare(waiting.requestId()));
        rejectedWithoutWrites(() -> repo().reconcile(waiting.eventId()));
        rejectedWithoutWrites(() -> prepare(UUID.randomUUID()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"counterparty_uuid=UNHEX(REPEAT('ab',16))", "ownership_epoch=1",
            "requested_amount_cents=requested_amount_cents+1", "source='npc-property-rent'",
            "economy_day='2026-09-08'", "benefit_unit_id=UNHEX(REPEAT('ab',16))",
            "idempotency_key='changed-utility-request'", "finalized_at=NULL"})
    void paidLedgerIdentityOrMissingFinalizationRejectsWithoutInventingPayment(String assignment) throws SQLException {
        Receipt waiting = prepare(UUID.randomUUID()).receipt();
        pay(waiting, NOW.plusMillis(10));
        execute("UPDATE npc_economy_events SET " + assignment + " WHERE event_id=?", waiting.eventId());
        rejectedWithoutWrites(() -> repo().reconcile(waiting.eventId()));
        rejectedWithoutWrites(() -> prepare(UUID.randomUUID()));
    }

    @Test
    void paymentBeforeIssuanceOrInTheFutureCannotBecomePaid() throws SQLException {
        Receipt first = prepare(UUID.randomUUID()).receipt();
        pay(first, NOW.plusMillis(10));
        execute("UPDATE npc_economy_events SET finalized_at=? WHERE event_id=?", NOW.minusMillis(1), first.eventId());
        rejectedWithoutWrites(() -> repo().reconcile(first.eventId()));
        UUID other = enroll();
        Receipt second = repo().preparePayment(UUID.randomUUID(), other, OWNER, 0, DAY).receipt();
        pay(second, clock.instant().plusMillis(10));
        execute("UPDATE npc_economy_events SET finalized_at=? WHERE event_id=?", clock.instant().plusMillis(1), second.eventId());
        rejectedWithoutWrites(() -> repo().reconcile(second.eventId()));
    }

    @Test
    void missingRequiredPaidJournalObservationIsNotPaymentProof() throws SQLException {
        Receipt waiting = prepare(UUID.randomUUID()).receipt();
        pay(waiting, NOW.plusMillis(10));
        execute("DELETE o FROM npc_transfer_observations o JOIN npc_transfer_attempts a ON a.attempt_id=o.attempt_id "
                + "WHERE a.event_id=? AND o.leg='CREDIT'", waiting.eventId());
        rejectedWithoutWrites(() -> repo().reconcile(waiting.eventId()));
        rejectedWithoutWrites(() -> prepare(UUID.randomUUID()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"gap", "overflow"})
    void historyCannotSilentlyTruncateOrAcceptNoncontiguousAttempts(String corruption) throws SQLException {
        Receipt first = decline(prepare(UUID.randomUUID()).receipt());
        if (corruption.equals("gap")) {
            prepare(UUID.randomUUID());
            execute("DELETE FROM npc_utility_payments WHERE event_id=?", first.eventId());
        } else {
            unchecked("UPDATE npc_utility_payments SET attempt_number=17 WHERE event_id=?", first.eventId());
        }
        rejectedWithoutWrites(() -> repo().history(property, DAY));
        rejectedWithoutWrites(() -> prepare(UUID.randomUUID()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"npc_utility_bills", "npc_economy_events", "npc_utility_payments"})
    void eachInsertFailureRollsBackTheEntireDatabaseContentSnapshot(String table) throws SQLException {
        String fault = "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='forced utility insert failure'";
        if (table.equals("npc_utility_payments")) {
            fault = "BEGIN IF (SELECT COUNT(*) FROM npc_utility_bills WHERE property_id=NEW.property_id "
                    + "AND economy_day=NEW.economy_day)=1 AND (SELECT COUNT(*) FROM npc_economy_events "
                    + "WHERE event_id=NEW.event_id AND state='PENDING')=1 THEN " + fault + "; "
                    + "ELSE SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture did not observe bill and event before attempt'; END IF; END";
        }
        execute("CREATE TRIGGER utility_insert_failure BEFORE INSERT ON " + table + " FOR EACH ROW " + fault);
        List<List<String>> before = data();
        SQLException failure = assertThrows(SQLException.class, () -> prepare(UUID.randomUUID()));
        assertTrue(failure.getMessage().contains("forced utility insert failure"), failure::getMessage);
        assertEquals(before, data(), "bill, event, attempt and unrelated state must all roll back; sequence gaps are not data");
    }

    @Test
    void failedRetryAttemptInsertPreservesOriginalDeclineAndEveryOtherTable() throws SQLException {
        Receipt declined = decline(prepare(UUID.randomUUID()).receipt());
        execute("CREATE TRIGGER utility_insert_failure BEFORE INSERT ON npc_utility_payments FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='forced utility retry failure'");
        List<List<String>> before = data();
        SQLException failure = assertThrows(SQLException.class, () -> prepare(UUID.randomUUID()));
        assertTrue(failure.getMessage().contains("forced utility retry failure"), failure::getMessage);
        assertEquals(before, data());
        assertEquals(List.of(declined), repo().history(property, DAY));
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void concurrentSamePropertyCreationAndRetryQueueExactlyOneEvent(boolean retry, boolean sameRequest) throws Exception {
        Receipt previous = retry ? decline(prepare(UUID.randomUUID()).receipt()) : null;
        UUID leftId = UUID.randomUUID();
        UUID rightId = sameRequest ? leftId : UUID.randomUUID();
        List<Optional<Preparation>> results = race(property, property,
                () -> attempt(leftId, property), () -> attempt(rightId, property));
        List<Preparation> accepted = results.stream().flatMap(Optional::stream).toList();
        assertEquals(sameRequest ? 2 : 1, accepted.size());
        assertEquals(1, accepted.stream().filter(Preparation::newlyQueued).count());
        Receipt winner = accepted.getFirst().receipt();
        if (sameRequest) assertEquals(winner, accepted.getLast().receipt());
        assertEquals(retry ? 2 : 1, winner.attemptNumber());
        assertEquals(1, count("npc_utility_bills", "1=1"));
        assertEquals(retry ? 2 : 1, count("npc_economy_events", "1=1"));
        assertEquals(retry ? List.of(previous, winner) : List.of(winner), repo().history(property, DAY));
    }

    @Test
    void concurrentSameRequestOnDifferentPropertiesRollsBackLosingBillAndLedger() throws Exception {
        UUID other = enroll();
        UUID request = UUID.randomUUID();
        List<List<String>> runtime = dataExcept("npc_utility_bills", "npc_utility_payments", "npc_economy_events");
        List<Optional<Preparation>> results = race(property, other,
                () -> attempt(request, property), () -> attempt(request, other));
        assertEquals(1, results.stream().filter(Optional::isPresent).count());
        Preparation accepted = results.stream().flatMap(Optional::stream).findFirst().orElseThrow();
        assertTrue(accepted.newlyQueued());
        UUID loser = accepted.receipt().bill().propertyId().equals(property) ? other : property;
        assertEquals(0, count("npc_utility_bills", "property_id=?", loser));
        assertEquals(0, count("npc_economy_events", "property_id=?", loser));
        assertEquals(1, count("npc_utility_payments", "1=1"));
        assertEquals(runtime, dataExcept("npc_utility_bills", "npc_utility_payments", "npc_economy_events"));
    }

    @ParameterizedTest
    @CsvSource({"BEFORE,npc_utility_bills", "AFTER,npc_utility_bills", "AFTER,npc_utility_payments"})
    void actualInsertPhaseDayRolloverRollsBackAllRows(String phase, String table) throws Exception {
        clock.set(start(DAY.plusDays(1)).minusMillis(1));
        List<List<String>> before = data();
        String gate = "utility-gate-" + UUID.randomUUID();
        String reached = "utility-phase-" + UUID.randomUUID();
        // The marker is acquired only inside the real INSERT trigger, not on a Clock read.
        execute("CREATE TRIGGER utility_clock_gate " + phase + " INSERT ON " + table + " FOR EACH ROW BEGIN "
                + "DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN DO RELEASE_LOCK('" + gate + "'); "
                + "DO RELEASE_LOCK('" + reached + "'); RESIGNAL; END; "
                + "IF GET_LOCK('" + reached + "',0)<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='phase marker failed'; END IF; "
                + "IF GET_LOCK('" + gate + "',8)<>1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='phase gate timeout'; END IF; "
                + "DO RELEASE_LOCK('" + gate + "'); DO RELEASE_LOCK('" + reached + "'); END");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<SQLException> result;
            try (Connection blocker = database.getConnection()) {
                assertEquals(1, namedLock(blocker, "GET_LOCK", gate));
                try {
                    result = worker.submit(() -> assertThrows(SQLException.class, () -> prepare(UUID.randomUUID())));
                    awaitMarker(reached);
                    assertFalse(result.isDone(), "the actual INSERT is still blocked at the requested SQL phase");
                    clock.set(start(DAY.plusDays(1)));
                } finally { assertEquals(1, namedLock(blocker, "RELEASE_LOCK", gate)); }
            }
            SQLException failure = result.get(10, TimeUnit.SECONDS);
            assertFalse(failure.getMessage().toLowerCase(Locale.ROOT).contains("timeout"), failure::getMessage);
            assertFalse(failure.getMessage().toLowerCase(Locale.ROOT).contains("interrupted"), failure::getMessage);
            assertEquals(before, data(), phase + " " + table + " rollover must abort the complete preparation");
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(15, TimeUnit.SECONDS), "rollover worker terminated");
        }
    }

    @SuppressWarnings("deprecation")
    @Test
    void utilityWorkDoesNotRecoverAnotherActiveTransferOrReleaseTheHeldRuntimeBridge() throws SQLException {
        UUID other = enroll();
        long event = new EconomyEventRepository(database).insertPending(EconomyEvent.pending("active-" + UUID.randomUUID(),
                100, EventSource.UPGRADE, other, OWNER, 0, OWNER, DAY.toString()));
        Handle handle = new Handle(UUID.randomUUID(), event, UUID.randomUUID(), "b".repeat(64));
        assertTrue(journal().beginDebit(handle).newlyStarted());
        assertEquals(TransferJournal.State.CREDIT_READY,
                journal().recordDebit(handle, new Observation(true, true, 1.0, 100.0, 99.0, false)).state());
        List<List<String>> active = activeRows(other, event, handle);
        List<List<String>> nonfinancial = dataExcept("npc_utility_bills", "npc_utility_payments", "npc_economy_events",
                "npc_transfer_attempts", "npc_transfer_observations", "npc_property_funding");
        Receipt waiting = prepare(UUID.randomUUID()).receipt();
        BridgeApi bridge = new BridgeApi(null, new EconomyEventRepository(database));
        bridge.heartbeat();
        assertFalse(bridge.supportsConfirmedTransfers());
        assertTrue(bridge.claimBatch(100).isEmpty());
        assertFalse(bridge.finalizeEvent(waiting.eventId(), true, waiting.bill().cents()));
        pay(waiting, NOW.plusMillis(10));
        assertEquals(State.PAID, repo().reconcile(waiting.eventId()).orElseThrow().state());
        rejectedWithoutWrites(() -> prepare(UUID.randomUUID()));
        assertEquals(active, activeRows(other, event, handle));
        assertEquals(nonfinancial, dataExcept("npc_utility_bills", "npc_utility_payments", "npc_economy_events",
                "npc_transfer_attempts", "npc_transfer_observations", "npc_property_funding"));
    }

    @Test
    void realJdbcStatementsHaveDeadlinesAndCollectionReadsAreExplicitlyBounded() throws Exception {
        try (SqlAudit audit = new SqlAudit()) {
            UtilityRepository checked = new UtilityRepository(audit.routed, RESERVE, clock, ZONE, RESET);
            Receipt receipt = checked.preparePayment(UUID.randomUUID(), property, OWNER, 0, DAY).receipt();
            assertEquals(List.of(receipt), checked.history(property, DAY));
            assertEquals(receipt, checked.reconcile(receipt.eventId()).orElseThrow());
            assertTrue(audit.sql.size() < 100, "bounded single-property work, not a server-wide scan");
            for (String table : List.of("NPC_TENANTS", "NPC_UPGRADES", "NPC_UTILITY_PAYMENTS")) {
                List<String> collectionReads = audit.sql.stream().filter(sql -> sql.startsWith("SELECT")
                        && sql.contains("FROM " + table) && sql.contains("WHERE PROPERTY_ID=")).toList();
                assertFalse(collectionReads.isEmpty(), "observed real collection read of " + table);
                assertTrue(collectionReads.stream().allMatch(sql -> sql.contains("LIMIT") && sql.contains("ROWS EXAMINED")),
                        collectionReads::toString);
            }
        }
    }

    private UtilityRepository repo() { return new UtilityRepository(database, RESERVE, clock, ZONE, RESET); }
    private static TransferJournal journal() { return new TransferJournal(database, RESERVE); }
    private Preparation prepare(UUID request) throws SQLException { return repo().preparePayment(request, property, OWNER, 0, DAY); }

    private UUID enroll() throws SQLException {
        UUID id = UUID.randomUUID();
        new PropertyRepository(database).insertEnrollment(new Property(id, WORLD, "utility-" + id, "test", "room",
                4, 1000, 1000, OWNER, 0, 80, true), 5000);
        return id;
    }

    private static void occupy(UUID property, int slot) throws SQLException {
        UUID tenant = UUID.randomUUID();
        assertTrue(new ResidentRepository(database).register(ResidentCatalogue.draft(tenant, "Mara Vale")));
        new TenantRepository(database).insert(new Tenant(tenant, "Mara Vale", property, slot, "STANDARD", "COOK", 50));
    }

    private static Handle handle(Receipt receipt) {
        return new Handle(UUID.randomUUID(), receipt.eventId(), UUID.randomUUID(), "a".repeat(64));
    }

    private static Observation debit(Receipt receipt) {
        double dollars = receipt.bill().cents() / 100.0;
        return new Observation(true, true, dollars, 1000.0, 1000.0 - dollars, false);
    }

    private void pay(Receipt receipt, Instant at) throws SQLException {
        Handle handle = handle(receipt);
        assertTrue(journal().beginDebit(handle).newlyStarted());
        assertEquals(TransferJournal.State.CREDIT_READY, journal().recordDebit(handle, debit(receipt)).state());
        assertTrue(journal().beginCredit(handle).newlyStarted());
        double dollars = receipt.bill().cents() / 100.0;
        assertEquals(TransferJournal.State.PAID,
                journal().recordCredit(handle, new Observation(true, true, dollars, 2000.0, 2000.0 + dollars, false)).state());
        // Journal has a DB wall clock. Pin only fixture chronology after obtaining real durable proofs.
        execute("UPDATE npc_economy_events SET created_at=?,claimed_at=?,finalized_at=? WHERE event_id=?",
                receipt.bill().issuedAt(), at.minusMillis(4), at, receipt.eventId());
        execute("UPDATE npc_transfer_attempts SET created_at=?,credit_intent_at=?,updated_at=? WHERE event_id=?",
                at.minusMillis(4), at.minusMillis(2), at, receipt.eventId());
        execute("UPDATE npc_transfer_observations o JOIN npc_transfer_attempts a ON a.attempt_id=o.attempt_id "
                        + "SET o.observed_at=CASE WHEN o.leg='DEBIT' THEN ? ELSE ? END WHERE a.event_id=?",
                at.minusMillis(3), at.minusMillis(1), receipt.eventId());
        clock.set(at.plusMillis(1));
    }

    private void recordDecline(Receipt receipt) throws SQLException {
        Handle handle = handle(receipt);
        assertTrue(journal().beginDebit(handle).newlyStarted());
        assertEquals(TransferJournal.State.DECLINED,
                journal().recordDebit(handle, new Observation(true, false, 0.0, 1000.0, 1000.0, false)).state());
        Instant at = clock.instant().plusMillis(10);
        execute("UPDATE npc_economy_events SET created_at=?,claimed_at=?,finalized_at=? WHERE event_id=?",
                receipt.bill().issuedAt(), at.minusMillis(2), at, receipt.eventId());
        execute("UPDATE npc_transfer_attempts SET created_at=?,updated_at=? WHERE event_id=?", at.minusMillis(2), at, receipt.eventId());
        execute("UPDATE npc_transfer_observations o JOIN npc_transfer_attempts a ON a.attempt_id=o.attempt_id "
                + "SET o.observed_at=? WHERE a.event_id=?", at.minusMillis(1), receipt.eventId());
        clock.set(at.plusMillis(1));
    }

    private Receipt decline(Receipt waiting) throws SQLException {
        recordDecline(waiting);
        Receipt declined = repo().reconcile(waiting.eventId()).orElseThrow();
        assertEquals(State.DECLINED, declined.state());
        assertEquals("PAYMENT_DECLINED", declined.outcome());
        assertNull(declined.paidAt());
        assertEquals(waiting.bill(), declined.bill());
        return declined;
    }

    private void assertReview(Receipt waiting) throws SQLException {
        List<List<String>> evidence = dataExcept("npc_utility_payments");
        Receipt review = repo().reconcile(waiting.eventId()).orElseThrow();
        assertEquals(State.REVIEW_REQUIRED, review.state());
        assertNotNull(review.outcome());
        assertNull(review.paidAt());
        assertEquals(waiting.bill(), review.bill());
        assertEquals(evidence, dataExcept("npc_utility_payments"), "review must not repair or destroy original evidence");
        rejectedWithoutWrites(() -> prepare(UUID.randomUUID()));
        List<List<String>> before = data();
        assertEquals(review, repo().reconcile(waiting.eventId()).orElseThrow());
        assertEquals(before, data());
    }

    private static void rejectedWithoutWrites(Executable operation) throws SQLException {
        List<List<String>> before = data();
        assertThrows(SQLException.class, operation);
        assertEquals(before, data(), "rejection must preserve the full isolated database contents");
    }

    private Optional<Preparation> attempt(UUID request, UUID target) throws SQLException {
        try { return Optional.of(repo().preparePayment(request, target, OWNER, 0, DAY)); }
        catch (SQLException conflict) { return Optional.empty(); }
    }

    private static <T> List<T> race(UUID leftProperty, UUID rightProperty, Callable<T> left, Callable<T> right) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<T> first;
            Future<T> second;
            try (Connection blocker = database.getConnection()) {
                blocker.setAutoCommit(false);
                try {
                    execute(blocker, "SELECT property_id FROM npc_properties WHERE property_id IN (?,?) ORDER BY property_id FOR UPDATE",
                            leftProperty, rightProperty);
                    first = workers.submit(left);
                    second = workers.submit(right);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    List<List<String>> pending;
                    do {
                        pending = rows("SELECT INFO FROM information_schema.PROCESSLIST WHERE DB=? AND ID<>CONNECTION_ID() "
                                + "AND INFO LIKE '%npc_properties%' AND INFO LIKE '%FOR UPDATE%'", schema);
                        if (pending.size() == 2) break;
                        Thread.sleep(10);
                    } while (System.nanoTime() < deadline);
                    assertEquals(2, pending.size(), "both real property-lock readers must be pending before release");
                    assertFalse(first.isDone());
                    assertFalse(second.isDone());
                } finally { blocker.rollback(); }
            }
            return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS), "utility race workers terminated");
        }
    }

    private static void awaitMarker(String marker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        do {
            if (scalar("SELECT IS_USED_LOCK(?)", marker) != null) return;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        fail("real INSERT trigger did not acquire its phase marker");
    }

    private static int namedLock(Connection c, String operation, String name) throws SQLException {
        assertTrue(operation.equals("GET_LOCK") || operation.equals("RELEASE_LOCK"));
        try (PreparedStatement ps = c.prepareStatement("SELECT " + operation + "(?" + (operation.equals("GET_LOCK") ? ",0" : "") + ")")) {
            ps.setQueryTimeout(3);
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                int result = rs.getInt(1);
                assertFalse(rs.wasNull());
                return result;
            }
        }
    }

    private static List<List<String>> activeRows(UUID property, long event, Handle handle) throws SQLException {
        List<List<String>> result = new ArrayList<>();
        result.addAll(rows("SELECT * FROM npc_economy_events WHERE event_id=?", event));
        result.addAll(rows("SELECT * FROM npc_transfer_attempts WHERE event_id=?", event));
        result.addAll(rows("SELECT * FROM npc_transfer_observations WHERE attempt_id=? ORDER BY leg", handle.attemptId()));
        result.addAll(rows("SELECT * FROM npc_property_funding WHERE property_id=?", property));
        return result;
    }

    private static void assertIndex(String table, String index, boolean nonUnique, String... columns) throws SQLException {
        List<List<String>> expected = new ArrayList<>();
        for (int i = 0; i < columns.length; i++) expected.add(List.of(nonUnique ? "1" : "0", Integer.toString(i + 1), columns[i]));
        assertEquals(expected, rows("SELECT NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND INDEX_NAME=? ORDER BY SEQ_IN_INDEX", table, index));
    }

    /** Forward every call to the real pool; observe deadlines and SQL without mocking results. */
    private static final class SqlAudit implements AutoCloseable {
        private final Database routed = new Database();
        private final List<String> sql = new ArrayList<>();

        private SqlAudit() throws ReflectiveOperationException {
            HikariDataSource forwarding = new HikariDataSource() {
                @Override public Connection getConnection() throws SQLException {
                    Connection actual = database.getConnection();
                    return proxy(Connection.class, (ignored, method, args) -> {
                        Object value = invoke(actual, method, args);
                        if (method.getName().equals("prepareStatement") && args[0] instanceof String text) {
                            String normalized = text.strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
                            PreparedStatement statement = (PreparedStatement) value;
                            return proxy(PreparedStatement.class, (unused, operation, parameters) -> {
                                if (operation.getName().startsWith("execute")) {
                                    if (normalized.startsWith("SELECT")) {
                                        assertTrue(statement.getQueryTimeout() > 0 && statement.getQueryTimeout() <= 5,
                                                "bounded JDBC deadline: " + normalized);
                                    }
                                    sql.add(normalized);
                                }
                                return invoke(statement, operation, parameters);
                            });
                        }
                        return value;
                    });
                }
            };
            Field source = Database.class.getDeclaredField("dataSource");
            source.setAccessible(true);
            source.set(routed, forwarding);
        }

        @Override public void close() { routed.close(); }
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;
        private MutableClock(Instant now) { this.now = now; }
        void set(Instant at) { now = at; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) {
            return new Clock() {
                @Override public ZoneId getZone() { return zone; }
                @Override public Clock withZone(ZoneId next) { return MutableClock.this.withZone(next); }
                @Override public Instant instant() { return now; }
            };
        }
        @Override public Instant instant() { return now; }
    }

    private static Instant start(LocalDate day) { return day.atTime(RESET, 0).atZone(ZONE).toInstant(); }
    private static LocalDateTime utc(Instant at) { return LocalDateTime.ofInstant(at, ZoneOffset.UTC); }
    private static String hex(UUID id) { return HexFormat.of().formatHex(Uuids.toBytes(id)); }
    private static List<List<String>> data() throws SQLException { return dataExcept(); }

    private static List<List<String>> dataExcept(String... excluded) throws SQLException {
        List<List<String>> result = new ArrayList<>();
        for (String table : tables) {
            if (List.of(excluded).contains(table)) continue;
            result.add(List.of(table));
            List<List<String>> contents = rows("SELECT * FROM " + table);
            contents.sort(Comparator.comparing(Object::toString));
            result.addAll(contents);
        }
        return result;
    }

    private static long count(String table, String where, Object... args) throws SQLException {
        assertTrue(tables.contains(table));
        return Long.parseLong(scalar("SELECT COUNT(*) FROM " + table + " WHERE " + where, args));
    }

    private static String scalar(String sql, Object... args) throws SQLException {
        List<List<String>> result = rows(sql, args);
        assertEquals(1, result.size(), sql);
        return result.getFirst().getFirst();
    }

    private static Instant timestamp(String sql, Object... args) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(5);
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                LocalDateTime value = rs.getObject(1, LocalDateTime.class);
                assertNotNull(value);
                assertFalse(rs.next());
                return value.toInstant(ZoneOffset.UTC);
            }
        }
    }

    private static List<List<String>> rows(String sql, Object... args) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(5);
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<List<String>> result = new ArrayList<>();
                while (rs.next()) {
                    List<String> row = new ArrayList<>();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                        int type = rs.getMetaData().getColumnType(i);
                        Object value = type == Types.TIMESTAMP ? rs.getObject(i, LocalDateTime.class) : rs.getObject(i);
                        row.add(value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : value == null ? null : value.toString());
                    }
                    result.add(row);
                }
                return result;
            }
        }
    }

    private static void unchecked(String sql, Object... args) throws SQLException {
        try (Connection c = database.getConnection()) {
            execute(c, "SET SESSION check_constraint_checks=0");
            try { execute(c, sql, args); }
            finally { execute(c, "SET SESSION check_constraint_checks=1"); }
        }
    }

    private static void execute(String sql, Object... args) throws SQLException {
        try (Connection c = database.getConnection()) { execute(c, sql, args); }
    }

    private static void execute(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(5);
            bind(ps, args);
            ps.execute();
        }
    }

    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof UUID id) ps.setBytes(i + 1, Uuids.toBytes(id));
            else if (args[i] instanceof Instant at) ps.setObject(i + 1, utc(at));
            else if (args[i] instanceof LocalDate day) ps.setString(i + 1, day.toString());
            else ps.setObject(i + 1, args[i]);
        }
    }

    private static void applyResource(String path) throws Exception {
        try (InputStream in = UtilityRepositoryIT.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8))) execute(sql);
        }
    }
}

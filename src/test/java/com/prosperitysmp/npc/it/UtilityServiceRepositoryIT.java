package com.prosperitysmp.npc.it;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.economy.EconomyEvent;
import com.prosperitysmp.npc.economy.EconomyEventRepository;
import com.prosperitysmp.npc.economy.EventSource;
import com.prosperitysmp.npc.economy.TransferJournal;
import com.prosperitysmp.npc.economy.TransferJournal.Handle;
import com.prosperitysmp.npc.economy.TransferProof.Observation;
import com.prosperitysmp.npc.economy.UtilityBill;
import com.prosperitysmp.npc.economy.UtilityRepository;
import com.prosperitysmp.npc.economy.UtilityRepository.Receipt;
import com.prosperitysmp.npc.economy.UtilityRepository.State;
import com.prosperitysmp.npc.economy.UtilityServiceRepository;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyRepository;
import com.prosperitysmp.npc.property.UtilityServiceAssessment;
import com.prosperitysmp.npc.property.UtilityServiceAssessment.Case;
import com.prosperitysmp.npc.property.UtilityServiceAssessment.Outcome;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
import java.time.Clock;
import java.time.Duration;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Isolated MariaDB; real utility/journal producers with fixture observations, never Vault. */
@Timeout(45)
class UtilityServiceRepositoryIT {
    private static final Instant NOW = Instant.parse("2026-09-09T09:00:00Z");
    private static final Instant FROM = NOW.minus(Duration.ofDays(30));
    private static final LocalDate DAY = LocalDate.of(2026, 9, 7);
    private static final ZoneId ZONE = ZoneId.of("America/Chicago");
    private static final int RESET = 4;
    private static final UUID WORLD = new UUID(0, 2100);
    private static final UUID OWNER = new UUID(0, 2101);
    private static final UUID RESERVE = new UUID(0, 2102);
    private static final UUID BUYER = new UUID(0, 2103);
    private static DB mariaDb;
    private static Database database;
    private static List<String> tables;
    private static String v14;

    @BeforeAll
    static void bootDatabase() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        try {
            mariaDb.start();
            String schema = "npc_utility_service_" + UUID.randomUUID().toString().replace("-", "");
            mariaDb.createDB(schema);
            database = new Database();
            database.connect(new DatabaseSettings("localhost", cfg.getPort(), schema, "root", "", 6, 10_000L));
            // Use the registered migration resources, including V14, without a second filename list.
            Field migrations = MigrationRunner.class.getDeclaredField("MIGRATIONS");
            migrations.setAccessible(true);
            for (Object migration : (List<?>) migrations.get(null)) {
                Method version = migration.getClass().getDeclaredMethod("version");
                Method resource = migration.getClass().getDeclaredMethod("resource");
                version.setAccessible(true);
                resource.setAccessible(true);
                int number = (Integer) version.invoke(migration);
                if (number > 14) continue;
                String path = "/" + resource.invoke(migration);
                applyResource(path);
                if (number == 14) v14 = path;
            }
            assertNotNull(v14, "V14 must be registered in MigrationRunner, not just present on disk");
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
    void freshDatabase() throws SQLException {
        try (Connection c = database.getConnection()) {
            execute(c, "SET SESSION foreign_key_checks=0");
            try { for (String table : tables) execute(c, "DELETE FROM " + table); }
            finally { execute(c, "SET SESSION foreign_key_checks=1"); }
        }
    }

    @Test
    void emptyOwnerIsUnknownAndRepeatedReadsLeaveEveryStoredByteAlone() throws SQLException {
        List<List<String>> before = data();
        UtilityServiceAssessment result = read(OWNER);
        assertEquals(OWNER, result.ownerId());
        assertEquals(NOW, result.capturedAt());
        assertEquals(FROM, result.dueFrom());
        assertTrue(result.cases().isEmpty());
        assertEquals(0, result.assessedCount());
        assertEquals(0, result.distinctBillDays());
        assertTrue(result.onTimeRate().isEmpty());
        assertEquals(result, read(OWNER));
        assertEquals(before, data());
    }

    @ParameterizedTest
    @CsvSource({"-1,ON_TIME", "0,ON_TIME", "1,LATE"})
    void proofCheckedProducerPaymentUsesInclusiveDeadlineMillisecond(long offset, Outcome outcome) throws SQLException {
        Receipt waiting = open(DAY);
        Instant paidAt = waiting.bill().dueAt().plusMillis(offset);
        Receipt paid = settle(waiting, paidAt);
        List<List<String>> before = data();
        Case result = assertCase(read(OWNER), paid, outcome);
        assertEquals(paidAt, result.paidAt());
        assertEquals(1, read(OWNER).assessedCount());
        assertEquals(outcome == Outcome.ON_TIME ? 1.0 : 0.0, read(OWNER).onTimeRate().orElseThrow(), 0.0);
        assertEquals(before, data());
    }

    @Test
    void maturedCohortIncludesBothDeadlineEdgesButNotEitherNeighboringDay() throws SQLException {
        LocalDate firstDay = utc(FROM).toLocalDate().minusDays(1);
        Receipt first = open(firstDay);
        Receipt last = open(utc(NOW).toLocalDate().minusDays(1));
        open(firstDay.minusDays(1));
        assertEquals(Set.of(first.bill().propertyId()), properties(read(OWNER, NOW.minusMillis(1), FROM)));
        open(utc(NOW).toLocalDate(), NOW);
        assertEquals(FROM, first.bill().dueAt());
        assertEquals(NOW, last.bill().dueAt());
        UtilityServiceAssessment result = read(OWNER);
        assertEquals(Set.of(first.bill().propertyId(), last.bill().propertyId()), properties(result));
        assertEquals(2, result.count(Outcome.OVERDUE));
        assertEquals(2, result.distinctBillDays());
        assertEquals(Set.of(last.bill().propertyId()), properties(read(OWNER, NOW, FROM.plusNanos(1))));
    }

    @Test
    void windowMustBePositiveAndAtMostThirtyDaysWithOneMillisecondAccepted() throws SQLException {
        Receipt dueNow = open(utc(NOW).toLocalDate().minusDays(1));
        assertCase(read(OWNER, NOW, NOW.minusMillis(1)), dueNow, Outcome.OVERDUE);
        assertCase(read(OWNER), dueNow, Outcome.OVERDUE);
        List<List<String>> before = data();
        for (Instant invalid : List.of(NOW, NOW.plusMillis(1), FROM.minusNanos(1))) {
            assertThrows(SQLException.class, () -> read(OWNER, NOW, invalid));
            assertEquals(before, data());
        }
    }

    @Test
    void lowClockCoarseDayPaddingDoesNotUnderflowSqlCalendar() throws SQLException {
        Instant first = Instant.parse("0001-01-01T00:00:00Z");
        List<List<String>> before = data();
        UtilityServiceAssessment result = read(OWNER, first.plusMillis(1), first);
        assertTrue(result.cases().isEmpty());
        assertTrue(result.onTimeRate().isEmpty());
        assertEquals(first.plusMillis(1), result.capturedAt());
        assertEquals(before, data());
    }

    @Test
    void earlyPaymentCannotEarnCreditUntilItsDeadlineMatures() throws SQLException {
        Receipt waiting = open(utc(NOW).toLocalDate(), NOW);
        Receipt paid = settle(waiting, NOW.plusMillis(10));
        Instant earlyCapture = NOW.plusMillis(20);
        assertTrue(read(OWNER, earlyCapture, NOW.minusSeconds(1)).cases().isEmpty());
        assertTrue(read(OWNER, earlyCapture, NOW.minusSeconds(1)).onTimeRate().isEmpty());
        assertCase(read(OWNER, paid.bill().dueAt(), NOW), paid, Outcome.ON_TIME);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unpaidOwnerChangeOrSameOwnerNewEpochRequiresHandoverReview(boolean sameOwner) throws SQLException {
        Receipt waiting = open(DAY);
        assertCase(read(OWNER), waiting, Outcome.OVERDUE);
        new PropertyRepository(database).applyOwnerChange(waiting.bill().propertyId(), OWNER,
                sameOwner ? OWNER : BUYER, 1, "utility service IT handover");
        Case result = assertCase(read(OWNER), waiting, Outcome.HANDOVER_REVIEW);
        assertNull(result.paidAt());
        assertEquals(0, read(OWNER).assessedCount());
        assertTrue(read(OWNER).onTimeRate().isEmpty());
        if (!sameOwner) assertTrue(read(BUYER).cases().isEmpty());
    }

    @Test
    void paidOriginalOwnerKeepsCreditAfterSaleAndDisabledProperty() throws SQLException {
        Receipt waiting = open(DAY);
        Receipt paid = settle(waiting, waiting.bill().dueAt());
        UtilityServiceAssessment beforeSale = read(OWNER);
        new PropertyRepository(database).applyOwnerChange(paid.bill().propertyId(), OWNER, BUYER, 1, "paid sale");
        new PropertyRepository(database).setEnabled(paid.bill().propertyId(), false);
        assertEquals(beforeSale, read(OWNER));
        assertCase(read(OWNER), paid, Outcome.ON_TIME);
        assertTrue(read(BUYER).cases().isEmpty());
        assertTrue(read(BUYER).onTimeRate().isEmpty());
    }

    @Test
    void billWithoutAnyAttemptIsUnknownNotAnUnpaidSuccessOrOverdueInference() throws SQLException {
        Receipt waiting = open(DAY);
        execute("DELETE FROM npc_utility_payments WHERE event_id=?", waiting.eventId());
        execute("DELETE FROM npc_economy_events WHERE event_id=?", waiting.eventId());
        List<List<String>> before = data();
        UtilityServiceAssessment result = read(OWNER);
        assertEquals(List.of(new Case(waiting.bill(), null, null, Outcome.UNVERIFIED_PAYMENT)), result.cases());
        assertEquals(0, result.assessedCount());
        assertTrue(result.onTimeRate().isEmpty());
        assertEquals(before, data());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLAIMED", "PAID", "FAILED", "AMBIGUOUS"})
    void bareUnreconciledLedgerStateCannotOptimisticallyBecomePaidOrOverdue(String state) throws SQLException {
        Receipt waiting = open(DAY);
        Instant claimed = waiting.bill().issuedAt().plusMillis(1);
        Long amount = state.equals("PAID") ? Long.valueOf(waiting.bill().cents())
                : state.equals("FAILED") ? Long.valueOf(0) : null;
        execute("UPDATE npc_economy_events SET state=?,paid_amount_cents=?,claimed_at=?,finalized_at=? WHERE event_id=?",
                state, amount, claimed, state.equals("CLAIMED") ? null : claimed.plusMillis(1), waiting.eventId());
        assertEquals("0", scalar("SELECT COUNT(*) FROM npc_transfer_attempts"));
        List<List<String>> before = data();
        assertNull(assertCase(read(OWNER), waiting, Outcome.UNVERIFIED_PAYMENT).paidAt());
        assertTrue(read(OWNER).onTimeRate().isEmpty());
        assertEquals(before, data(), "reader must not reconcile a WAITING receipt or dispatch payment");
    }

    @Test
    void actualAmbiguousDebitAndProducerReviewRemainUnverified() throws SQLException {
        Receipt waiting = open(DAY);
        Handle handle = handle(waiting);
        assertTrue(journal().beginDebit(handle).newlyStarted());
        assertEquals(TransferJournal.State.AMBIGUOUS,
                journal().recordDebit(handle, new Observation(false, false, null, 1000.0, null, true)).state());
        Instant at = waiting.bill().issuedAt().plusMillis(10);
        pinJournal(waiting, at, false);
        assertCase(read(OWNER), waiting, Outcome.UNVERIFIED_PAYMENT);
        Receipt review = utilities(at.plusMillis(1)).reconcile(waiting.eventId()).orElseThrow();
        assertEquals(State.REVIEW_REQUIRED, review.state());
        List<List<String>> before = data();
        assertCase(read(OWNER), review, Outcome.UNVERIFIED_PAYMENT);
        assertTrue(read(OWNER).onTimeRate().isEmpty());
        assertEquals(before, data());
    }

    @Test
    void latestDeclineThenExplicitRetryUsesOneBillAndLatestReceiptOnly() throws SQLException {
        Receipt first = open(DAY);
        Instant declinedAt = first.bill().issuedAt().plusMillis(10);
        Receipt declined = decline(first, declinedAt);
        assertCase(read(OWNER), declined, Outcome.OVERDUE);
        Receipt retry = retry(declined, declinedAt.plusMillis(10));
        assertEquals(2, retry.attemptNumber());
        assertEquals(first.bill(), retry.bill());
        assertCase(read(OWNER), retry, Outcome.OVERDUE);
        pay(retry, retry.bill().dueAt().plusMillis(1));
        assertCase(read(OWNER), retry, Outcome.UNVERIFIED_PAYMENT);
        Receipt paid = utilities(NOW).reconcile(retry.eventId()).orElseThrow();
        assertEquals(State.PAID, paid.state());
        assertCase(read(OWNER), paid, Outcome.LATE);
        assertEquals(1, read(OWNER).assessedCount());
        assertEquals(1, read(OWNER).distinctBillDays());
        assertEquals("2", scalar("SELECT COUNT(*) FROM npc_utility_payments"));
    }

    @Test
    void persistedProofCheckedPaymentIsAnAssessmentFactNotARequestToReverifyRawObservations() throws SQLException {
        Receipt waiting = open(DAY);
        Receipt paid = settle(waiting, waiting.bill().dueAt());
        UtilityServiceAssessment expected = read(OWNER);
        assertCase(expected, paid, Outcome.ON_TIME);
        assertEquals("2", scalar("SELECT COUNT(*) FROM npc_transfer_observations"));
        execute("DELETE FROM npc_transfer_observations");
        List<List<String>> before = data();
        assertEquals(expected, read(OWNER), "assessment binds persisted producer facts, not raw forensic retention");
        assertEquals(before, data());
    }

    @Test
    void mixedCohortCountsBillsNotAttemptsAndUnknownsDoNotImproveRateOrWriteAnything() throws SQLException {
        Receipt onTime = open(DAY.minusDays(1));
        settle(onTime, onTime.bill().dueAt());
        Receipt late = open(DAY.minusDays(1));
        settle(late, late.bill().dueAt().plusMillis(1));
        open(DAY);
        Receipt unknown = open(DAY);
        pay(unknown, unknown.bill().dueAt());
        Receipt handover = open(DAY);
        new PropertyRepository(database).applyOwnerChange(handover.bill().propertyId(), OWNER, BUYER, 1, "mixed handover");
        List<List<String>> before = data();
        UtilityServiceAssessment result = read(OWNER);
        assertEquals(5, result.cases().size());
        for (Outcome outcome : Outcome.values()) assertEquals(1, result.count(outcome), outcome.name());
        assertEquals(3, result.assessedCount());
        assertEquals(2, result.distinctBillDays());
        assertTrue(result.onTimeRate().isEmpty(), "an incomplete cohort cannot publish a usable partial rate");
        assertEquals(result, read(OWNER));
        assertEquals(before, data(), "all tables, including funding, transfers, receipts and runtime state");
    }

    @ParameterizedTest
    @ValueSource(strings = {"property", "ledger"})
    void missingJoinedRowRejectsWholeViewInsteadOfDroppingOneBill(String missing) throws SQLException {
        Receipt paid = paidWithHealthySibling();
        if (missing.equals("property")) unchecked("DELETE FROM npc_properties WHERE property_id=?", paid.bill().propertyId());
        else unchecked("DELETE FROM npc_economy_events WHERE event_id=?", paid.eventId());
        assertReadRejectedWithoutWrites();
    }

    @ParameterizedTest
    @ValueSource(strings = {"identity", "paid-amount", "paid-time"})
    void latestReceiptAndLedgerBindingDriftRejectsWholeView(String drift) throws SQLException {
        Receipt paid = paidWithHealthySibling();
        switch (drift) {
            case "identity" -> execute("UPDATE npc_economy_events SET counterparty_uuid=? WHERE event_id=?", BUYER, paid.eventId());
            case "paid-amount" -> execute("UPDATE npc_economy_events SET paid_amount_cents=paid_amount_cents+1 WHERE event_id=?", paid.eventId());
            case "paid-time" -> execute("UPDATE npc_utility_payments SET paid_at=TIMESTAMPADD(MICROSECOND,1000,paid_at) WHERE event_id=?", paid.eventId());
            default -> throw new AssertionError(drift);
        }
        assertReadRejectedWithoutWrites();
    }

    @Test
    void currentEpochRegressionRejectsEvenAnOtherwiseValidPaidCase() throws SQLException {
        Receipt paid = paidWithHealthySibling();
        execute("UPDATE npc_properties SET ownership_epoch=-1 WHERE property_id=?", paid.bill().propertyId());
        assertReadRejectedWithoutWrites();
    }

    @Test
    void billHashAndCalendarValidationPrecedeDueWindowFiltering() throws SQLException {
        Receipt paid = paidWithHealthySibling();
        UtilityBill bill = paid.bill();
        execute("UPDATE npc_utility_bills SET due_at=? WHERE property_id=?", NOW.plus(Duration.ofDays(100)), bill.propertyId());
        assertReadRejectedWithoutWrites();
        execute("UPDATE npc_utility_bills SET due_at=?,payload_sha256=REPEAT('0',64) WHERE property_id=?",
                bill.dueAt(), bill.propertyId());
        assertReadRejectedWithoutWrites();
        execute("UPDATE npc_utility_bills SET payload_sha256=?,clock_zone='Not/AZone' WHERE property_id=?",
                bill.fingerprint(), bill.propertyId());
        assertReadRejectedWithoutWrites();
        execute("UPDATE npc_utility_bills SET clock_zone=? WHERE property_id=?", bill.zoneId(), bill.propertyId());
        assertEquals(2, read(OWNER).cases().size());
        Receipt old = open(utc(FROM).toLocalDate().minusDays(2));
        assertEquals(2, read(OWNER).cases().size(), "a valid old deadline is excluded after validation");
        execute("UPDATE npc_utility_bills SET due_at=? WHERE property_id=?", NOW.plusSeconds(1), old.bill().propertyId());
        assertReadRejectedWithoutWrites();
    }

    @Test
    void futureSettlementAndFutureClaimCannotPassALowReaderClock() throws SQLException {
        Receipt paid = paidWithHealthySibling();
        Instant future = NOW.plusMillis(1);
        execute("UPDATE npc_utility_payments SET paid_at=? WHERE event_id=?", future, paid.eventId());
        execute("UPDATE npc_economy_events SET finalized_at=? WHERE event_id=?", future, paid.eventId());
        assertReadRejectedWithoutWrites();
        execute("UPDATE npc_utility_payments SET paid_at=? WHERE event_id=?", paid.paidAt(), paid.eventId());
        execute("UPDATE npc_economy_events SET finalized_at=? WHERE event_id=?", paid.paidAt(), paid.eventId());
        execute("UPDATE npc_economy_events SET state='CLAIMED',claimed_at=? WHERE state='PENDING'", future);
        assertReadRejectedWithoutWrites();
    }

    @ParameterizedTest
    @ValueSource(strings = {"gap", "prior-not-declined"})
    void incompleteAttemptSequenceOrNondeclinedPriorStateRejectsWholeView(String fault) throws SQLException {
        Receipt first = open(DAY);
        Receipt declined = decline(first, first.bill().issuedAt().plusMillis(10));
        Receipt second = retry(declined, first.bill().issuedAt().plusMillis(20));
        assertCase(read(OWNER), second, Outcome.OVERDUE);
        if (fault.equals("gap")) {
            execute("UPDATE npc_utility_payments SET attempt_number=3 WHERE event_id=?", second.eventId());
        } else {
            execute("UPDATE npc_utility_payments SET state='WAITING',outcome_code=NULL WHERE event_id=?", first.eventId());
        }
        assertReadRejectedWithoutWrites();
    }

    @Test
    void unmanagedExtraUtilityEventCannotHideBehindValidLatestReceipt() throws SQLException {
        Receipt paid = paidWithHealthySibling();
        UtilityBill bill = paid.bill();
        long extra = new EconomyEventRepository(database).insertPending(EconomyEvent.pending(
                "legacy-utility-" + UUID.randomUUID(), bill.cents(), EventSource.UTILITY,
                bill.propertyId(), OWNER, bill.ownershipEpoch(), OWNER, bill.day().toString()));
        assertTrue(extra > 0);
        execute("UPDATE npc_economy_events SET created_at=? WHERE event_id=?", bill.issuedAt(), extra);
        assertReadRejectedWithoutWrites();
    }

    @Test
    void consistentSnapshotPrecedesSelectAndSurvivesConcurrentReconcileCommit() throws Exception {
        Receipt waiting = open(DAY);
        pay(waiting, waiting.bill().dueAt());
        UtilityServiceAssessment before = read(OWNER);
        assertCase(before, waiting, Outcome.UNVERIFIED_PAYMENT);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ReadProbe probe = new ReadProbe(true)) {
            try {
                Future<UtilityServiceAssessment> pending = worker.submit(
                        () -> new UtilityServiceRepository(probe.routed, fixed(NOW)).read(OWNER, FROM));
                probe.awaitSnapshot();
                assertFalse(pending.isDone());
                Receipt paid = utilities(NOW).reconcile(waiting.eventId()).orElseThrow();
                assertEquals(State.PAID, paid.state());
                List<List<String>> afterWriter = data();
                probe.release();
                assertEquals(before, pending.get(10, TimeUnit.SECONDS),
                        "the real SELECT executes after the writer but must use START's earlier snapshot");
                assertCase(read(OWNER), paid, Outcome.ON_TIME);
                assertEquals(afterWriter, data());
                probe.assertReadOnlySnapshot();
            } finally {
                probe.release();
                worker.shutdownNow();
                assertTrue(worker.awaitTermination(15, TimeUnit.SECONDS), "utility service worker terminated");
            }
        }
    }

    @Test
    void registeredV14IsRepeatableAndRealQueriesUseBoundedOwnerDayIndexWithoutRawProofReads() throws Exception {
        Receipt waiting = open(DAY);
        settle(waiting, waiting.bill().dueAt());
        List<List<String>> before = data();
        List<List<String>> indexes = rows("SELECT TABLE_NAME,INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME "
                + "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() ORDER BY 1,2,4");
        List<List<String>> ownerDay = rows("SELECT INDEX_NAME,GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) "
                + "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='npc_utility_bills' "
                + "GROUP BY INDEX_NAME HAVING GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX)='owner_uuid,economy_day,property_id'");
        assertEquals(1, ownerDay.size(), "dedicated owner/day/property index");
        applyResource(v14);
        applyResource(v14);
        assertEquals(indexes, rows("SELECT TABLE_NAME,INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME "
                + "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() ORDER BY 1,2,4"));
        try (ReadProbe probe = new ReadProbe(false)) {
            assertEquals(read(OWNER), new UtilityServiceRepository(probe.routed, fixed(NOW)).read(OWNER, FROM));
            probe.assertReadOnlySnapshot();
            List<String> evidence = probe.sql.stream().filter(sql -> sql.startsWith("SELECT")
                    && sql.contains("NPC_UTILITY_BILLS")).toList();
            assertEquals(1, evidence.size());
            String query = evidence.getFirst();
            assertTrue(query.contains("OWNER_UUID=?") && query.contains("ECONOMY_DAY>=?")
                    && query.contains("ECONOMY_DAY<=?"), "indexed owner/day range, not a due_at filter: " + query);
            assertTrue(query.contains("ROWS EXAMINED"), "server-side work is bounded");
            assertTrue(query.matches("(?s).*LIMIT (4097|\\?).*"), "4096 candidates plus overflow sentinel");
            assertTrue(probe.parameters.contains(hex(OWNER)));
            assertTrue(probe.parameters.contains(utc(FROM).toLocalDate().minusDays(2).toString()));
            assertTrue(probe.parameters.contains(utc(NOW).toLocalDate().plusDays(1).toString()));
            assertTrue(probe.sql.stream().noneMatch(sql -> sql.contains("NPC_TRANSFER_ATTEMPTS")
                    || sql.contains("NPC_TRANSFER_OBSERVATIONS")), "read persisted producer facts, not raw financial proof");
        }
        assertEquals(before, data());
    }

    @Test
    void candidateOverflowRejectsWholeViewEvenWhenNoDeadlineHasMatured() throws SQLException {
        Receipt seed = open(utc(NOW).toLocalDate(), NOW);
        execute("DELETE FROM npc_utility_payments WHERE event_id=?", seed.eventId());
        execute("DELETE FROM npc_economy_events WHERE event_id=?", seed.eventId());
        insertCandidateBills(seed.bill(), 4095);
        assertEquals("4096", scalar("SELECT COUNT(*) FROM npc_utility_bills"));
        assertTrue(read(OWNER).cases().isEmpty(), "the candidate bound is inclusive, even for excluded deadlines");
        insertCandidateBills(seed.bill(), 1);
        assertEquals("4097", scalar("SELECT COUNT(*) FROM npc_utility_bills"));
        assertReadRejectedWithoutWrites();
    }

    @Test
    void equalCountsCannotHideEarlierAttemptPointingAtUnrelatedEvent() throws SQLException {
        Receipt first = open(DAY);
        Receipt declined = decline(first, first.bill().issuedAt().plusSeconds(1));
        Receipt second = retry(declined, first.bill().issuedAt().plusSeconds(2));
        settle(second, second.bill().dueAt());
        assertEquals(1.0, read(OWNER).onTimeRate().orElseThrow(), 0.0);
        long unrelated = new EconomyEventRepository(database).insertPending(EconomyEvent.pending(
                "unrelated-rent-" + UUID.randomUUID(), 100, EventSource.RENT, first.bill().propertyId(),
                OWNER, first.bill().ownershipEpoch(), OWNER, first.bill().day().toString()));
        execute("UPDATE npc_utility_payments SET event_id=? WHERE request_id=?", unrelated, first.requestId());
        assertEquals("2", scalar("SELECT COUNT(*) FROM npc_utility_payments"));
        assertEquals("2", scalar("SELECT COUNT(*) FROM npc_economy_events WHERE source='npc-property-utility'"));
        assertReadRejectedWithoutWrites();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-claim", "created-after-finalization", "created-before-bill", "claim-before-creation"})
    void incompleteOrContradictoryCreationAndClaimTimesCannotEarnOnTimeCredit(String fault) throws SQLException {
        Receipt paid = paidWithHealthySibling();
        switch (fault) {
            case "missing-claim" -> execute("UPDATE npc_economy_events SET claimed_at=NULL WHERE event_id=?", paid.eventId());
            case "created-after-finalization" -> execute("UPDATE npc_economy_events SET created_at=? WHERE event_id=?",
                    paid.paidAt().plusMillis(1), paid.eventId());
            case "created-before-bill" -> execute("UPDATE npc_economy_events SET created_at=? WHERE event_id=?",
                    paid.bill().issuedAt().minusMillis(1), paid.eventId());
            case "claim-before-creation" -> execute("UPDATE npc_economy_events SET created_at=? WHERE event_id=?",
                    paid.paidAt().minusMillis(3), paid.eventId());
            default -> throw new AssertionError(fault);
        }
        assertReadRejectedWithoutWrites();
    }

    private static Receipt open(LocalDate day) throws SQLException { return open(day, start(day).plusSeconds(3600)); }

    private static Receipt open(LocalDate day, Instant issued) throws SQLException {
        UUID property = UUID.randomUUID();
        new PropertyRepository(database).insertEnrollment(new Property(property, WORLD, "utility-service-" + property,
                "test", "room", 4, 1000, 1000, OWNER, 0, 80, true), 5000);
        Receipt waiting = utilities(issued).preparePayment(UUID.randomUUID(), property, OWNER, 0, day).receipt();
        assertEquals(State.WAITING, waiting.state());
        // Producers default ledger creation to DB wall time; only fixture chronology is pinned.
        execute("UPDATE npc_economy_events SET created_at=? WHERE event_id=?", issued, waiting.eventId());
        return waiting;
    }

    private static Receipt retry(Receipt first, Instant at) throws SQLException {
        Receipt next = utilities(at).preparePayment(UUID.randomUUID(), first.bill().propertyId(), OWNER,
                first.bill().ownershipEpoch(), first.bill().day()).receipt();
        execute("UPDATE npc_economy_events SET created_at=? WHERE event_id=?", at, next.eventId());
        return next;
    }

    private static UtilityRepository utilities(Instant at) {
        return new UtilityRepository(database, RESERVE, fixed(at), ZONE, RESET);
    }

    private static TransferJournal journal() { return new TransferJournal(database, RESERVE); }
    private static Handle handle(Receipt receipt) {
        return new Handle(UUID.randomUUID(), receipt.eventId(), UUID.randomUUID(), "a".repeat(64));
    }

    private static void pay(Receipt receipt, Instant at) throws SQLException {
        Handle handle = handle(receipt);
        TransferJournal journal = journal();
        assertTrue(journal.beginDebit(handle).newlyStarted());
        double dollars = receipt.bill().cents() / 100.0;
        assertEquals(TransferJournal.State.CREDIT_READY,
                journal.recordDebit(handle, new Observation(true, true, dollars, 1000.0, 1000.0 - dollars, false)).state());
        assertTrue(journal.beginCredit(handle).newlyStarted());
        assertEquals(TransferJournal.State.PAID,
                journal.recordCredit(handle, new Observation(true, true, dollars, 2000.0, 2000.0 + dollars, false)).state());
        pinJournal(receipt, at, true);
    }

    private static Receipt settle(Receipt receipt, Instant at) throws SQLException {
        pay(receipt, at);
        Receipt paid = utilities(at.plusMillis(1)).reconcile(receipt.eventId()).orElseThrow();
        assertEquals(State.PAID, paid.state());
        assertEquals("TRANSFER_CONFIRMED", paid.outcome());
        assertEquals(at, paid.paidAt());
        return paid;
    }

    private static Receipt decline(Receipt receipt, Instant at) throws SQLException {
        Handle handle = handle(receipt);
        TransferJournal journal = journal();
        assertTrue(journal.beginDebit(handle).newlyStarted());
        assertEquals(TransferJournal.State.DECLINED,
                journal.recordDebit(handle, new Observation(true, false, 0.0, 1000.0, 1000.0, false)).state());
        pinJournal(receipt, at, false);
        Receipt declined = utilities(at.plusMillis(1)).reconcile(receipt.eventId()).orElseThrow();
        assertEquals(State.DECLINED, declined.state());
        assertEquals("PAYMENT_DECLINED", declined.outcome());
        assertNull(declined.paidAt());
        return declined;
    }

    private static void pinJournal(Receipt receipt, Instant at, boolean credited) throws SQLException {
        // Preserve real observations/terminal producer results; change only the isolated fixture's times.
        execute("UPDATE npc_economy_events SET claimed_at=?,finalized_at=? WHERE event_id=?",
                at.minusMillis(4), at, receipt.eventId());
        execute("UPDATE npc_transfer_attempts SET created_at=?,credit_intent_at=?,updated_at=? WHERE event_id=?",
                at.minusMillis(4), credited ? at.minusMillis(2) : null, at, receipt.eventId());
        execute("UPDATE npc_transfer_observations o JOIN npc_transfer_attempts a ON a.attempt_id=o.attempt_id "
                        + "SET o.observed_at=CASE WHEN o.leg='DEBIT' THEN ? ELSE ? END WHERE a.event_id=?",
                at.minusMillis(3), at.minusMillis(1), receipt.eventId());
    }

    private static Receipt paidWithHealthySibling() throws SQLException {
        open(DAY.minusDays(1));
        Receipt waiting = open(DAY);
        Receipt paid = settle(waiting, waiting.bill().dueAt());
        assertEquals(2, read(OWNER).cases().size(), "start corruption with a complete, valid cohort");
        assertEquals(0.5, read(OWNER).onTimeRate().orElseThrow(), 0.0);
        return paid;
    }

    private static UtilityServiceAssessment read(UUID owner) throws SQLException { return read(owner, NOW, FROM); }
    private static UtilityServiceAssessment read(UUID owner, Instant at, Instant from) throws SQLException {
        return new UtilityServiceRepository(database, fixed(at)).read(owner, from);
    }

    private static Case assertCase(UtilityServiceAssessment result, Receipt receipt, Outcome outcome) {
        assertEquals(1, result.cases().size());
        Case value = result.cases().getFirst();
        assertEquals(receipt.bill(), value.bill());
        assertEquals(Long.valueOf(receipt.eventId()), value.paymentEventId());
        assertEquals(outcome, value.outcome());
        if (outcome != Outcome.ON_TIME && outcome != Outcome.LATE) assertNull(value.paidAt());
        return value;
    }

    private static Set<UUID> properties(UtilityServiceAssessment result) {
        return result.cases().stream().map(value -> value.bill().propertyId()).collect(Collectors.toSet());
    }

    private static void assertReadRejectedWithoutWrites() throws SQLException {
        List<List<String>> before = data();
        assertThrows(SQLException.class, () -> read(OWNER));
        assertEquals(before, data(), "fail closed without fixing evidence or returning a partial cohort");
    }

    /** Cheap bound fixture: valid frozen bills, no fabricated receipts or successful payment facts. */
    private static void insertCandidateBills(UtilityBill seed, int count) throws SQLException {
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement properties = c.prepareStatement("""
                    INSERT INTO npc_properties(property_id,world_uuid,region_id,market_id,class_key,capacity,
                      reference_rent_cents,asking_rent_cents,owner_uuid,ownership_epoch,condition_score,enabled)
                    VALUES (?,?,?,'test','room',4,1000,1000,?,0,80,TRUE)
                    """);
                 PreparedStatement bills = c.prepareStatement("""
                    INSERT INTO npc_utility_bills(property_id,economy_day,owner_uuid,ownership_epoch,property_revision,
                      capacity,occupied_tenants,installed_upgrade_levels,issued_at,period_start,due_at,clock_zone,
                      reset_hour,amount_cents,payload_sha256) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """)) {
                properties.setQueryTimeout(5);
                bills.setQueryTimeout(5);
                for (int i = 0; i < count; i++) {
                    UUID property = UUID.randomUUID();
                    UtilityBill bill = new UtilityBill(property, OWNER, 0, 0, seed.day(), 4, 0, 0,
                            seed.issuedAt(), seed.zoneId(), seed.resetHour());
                    bind(properties, property, WORLD, "utility-bound-" + property, OWNER);
                    properties.addBatch();
                    bind(bills, property, bill.day(), OWNER, 0, 0L, 4, 0, 0, bill.issuedAt(), bill.periodStart(),
                            bill.dueAt(), bill.zoneId(), bill.resetHour(), bill.cents(), bill.fingerprint());
                    bills.addBatch();
                    if ((i + 1) % 256 == 0 || i + 1 == count) {
                        properties.executeBatch();
                        bills.executeBatch();
                        properties.clearBatch();
                        bills.clearBatch();
                    }
                }
                c.commit();
            } catch (SQLException | RuntimeException failure) {
                try { c.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }

    /** Every statement still executes on MariaDB; optionally pause between real START and SELECT. */
    private static final class ReadProbe implements AutoCloseable {
        private final Database routed = new Database();
        private final List<String> sql = new ArrayList<>();
        private final List<String> parameters = new ArrayList<>();
        private final CountDownLatch snapshot = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean armed;
        private int connections;

        private ReadProbe(boolean gate) throws ReflectiveOperationException {
            armed = new AtomicBoolean(gate);
            HikariDataSource forwarding = new HikariDataSource() {
                @Override public Connection getConnection() throws SQLException {
                    connections++;
                    Connection actual = database.getConnection();
                    return proxy(Connection.class, (ignored, method, args) -> {
                        if (method.getName().equals("prepareStatement") && args != null && args[0] instanceof String text) {
                            String normalized = text.strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
                            if (normalized.startsWith("SELECT") && normalized.contains("NPC_UTILITY_BILLS")
                                    && armed.compareAndSet(true, false)) {
                                assertTrue(started.get(), "real START must finish before the evidence query is prepared");
                                snapshot.countDown();
                                try {
                                    if (!resume.await(15, TimeUnit.SECONDS)) throw new AssertionError("utility snapshot gate timed out");
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                    throw new AssertionError("utility snapshot gate interrupted", interrupted);
                                }
                            }
                            PreparedStatement statement = (PreparedStatement) invoke(actual, method, args);
                            return proxy(PreparedStatement.class, (unused, operation, values) -> {
                                if (operation.getName().startsWith("set") && values != null && values.length >= 2) {
                                    parameters.add(values[1] instanceof byte[] bytes ? HexFormat.of().formatHex(bytes)
                                            : String.valueOf(values[1]));
                                }
                                if (operation.getName().startsWith("execute")) {
                                    assertTrue(statement.getQueryTimeout() > 0 && statement.getQueryTimeout() <= 5,
                                            "bounded real JDBC deadline: " + normalized);
                                    sql.add(normalized);
                                    if (normalized.startsWith("START TRANSACTION")) {
                                        assertEquals(Connection.TRANSACTION_REPEATABLE_READ, actual.getTransactionIsolation());
                                        Object result = invoke(statement, operation, values);
                                        started.set(true);
                                        return result;
                                    }
                                }
                                return invoke(statement, operation, values);
                            });
                        }
                        return invoke(actual, method, args);
                    });
                }
            };
            // Independent facade only: do not replace or close the shared fixture pool.
            Field source = Database.class.getDeclaredField("dataSource");
            source.setAccessible(true);
            source.set(routed, forwarding);
        }

        void awaitSnapshot() throws InterruptedException {
            assertTrue(snapshot.await(10, TimeUnit.SECONDS), "no START then evidence SELECT observed");
        }

        void assertReadOnlySnapshot() {
            assertEquals(1, connections, "one connection owns the entire assessment snapshot");
            List<String> starts = sql.stream().filter(value -> value.startsWith("START TRANSACTION")).toList();
            assertEquals(1, starts.size());
            assertTrue(starts.getFirst().contains("WITH CONSISTENT SNAPSHOT"));
            assertTrue(starts.getFirst().contains("READ ONLY"));
            assertTrue(sql.stream().allMatch(value -> value.startsWith("SELECT") || value.startsWith("START TRANSACTION")
                    || value.startsWith("SET ") || value.equals("ROLLBACK")), sql::toString);
            assertTrue(sql.stream().noneMatch(value -> value.contains("FOR UPDATE") || value.contains("LOCK IN SHARE MODE")));
        }

        void release() { resume.countDown(); }
        @Override public void close() { release(); routed.close(); }
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
    }

    private static Clock fixed(Instant at) { return Clock.fixed(at, ZoneOffset.UTC); }
    private static Instant start(LocalDate day) { return day.atTime(RESET, 0).atZone(ZONE).toInstant(); }
    private static LocalDateTime utc(Instant at) { return LocalDateTime.ofInstant(at, ZoneOffset.UTC); }
    private static String hex(UUID id) { return HexFormat.of().formatHex(Uuids.toBytes(id)); }

    private static List<List<String>> data() throws SQLException {
        List<List<String>> result = new ArrayList<>();
        try (Connection c = database.getConnection()) {
            for (String table : tables) {
                result.add(List.of(table));
                List<List<String>> contents = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement("SELECT * FROM " + table)) {
                    ps.setQueryTimeout(5);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            List<String> row = new ArrayList<>();
                            for (int column = 1; column <= rs.getMetaData().getColumnCount(); column++) {
                                int type = rs.getMetaData().getColumnType(column);
                                Object value = type == java.sql.Types.TIMESTAMP
                                        ? rs.getObject(column, LocalDateTime.class) : rs.getObject(column);
                                row.add(value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes)
                                        : value == null ? null : value.toString());
                            }
                            contents.add(row);
                        }
                    }
                }
                contents.sort(Comparator.comparing(Object::toString));
                result.addAll(contents);
            }
        }
        return result;
    }

    private static String scalar(String sql, Object... values) throws SQLException {
        List<List<String>> result = rows(sql, values);
        assertEquals(1, result.size(), sql);
        return result.getFirst().getFirst();
    }

    private static List<List<String>> rows(String sql, Object... values) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(5);
            bind(ps, values);
            try (ResultSet rs = ps.executeQuery()) {
                List<List<String>> result = new ArrayList<>();
                while (rs.next()) {
                    List<String> row = new ArrayList<>();
                    for (int column = 1; column <= rs.getMetaData().getColumnCount(); column++) row.add(rs.getString(column));
                    result.add(row);
                }
                return result;
            }
        }
    }

    private static void unchecked(String sql, Object... values) throws SQLException {
        try (Connection c = database.getConnection()) {
            execute(c, "SET SESSION foreign_key_checks=0");
            execute(c, "SET SESSION check_constraint_checks=0");
            try { execute(c, sql, values); }
            finally {
                execute(c, "SET SESSION check_constraint_checks=1");
                execute(c, "SET SESSION foreign_key_checks=1");
            }
        }
    }

    private static void execute(String sql, Object... values) throws SQLException {
        try (Connection c = database.getConnection()) { execute(c, sql, values); }
    }

    private static void execute(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(5);
            bind(ps, values);
            ps.execute();
        }
    }

    private static void bind(PreparedStatement ps, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) {
            if (values[i] instanceof UUID id) ps.setBytes(i + 1, Uuids.toBytes(id));
            else if (values[i] instanceof Instant at) ps.setObject(i + 1, utc(at));
            else if (values[i] instanceof LocalDate day) ps.setString(i + 1, day.toString());
            else ps.setObject(i + 1, values[i]);
        }
    }

    private static void applyResource(String path) throws Exception {
        try (InputStream in = UtilityServiceRepositoryIT.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8))) execute(sql);
        }
    }
}

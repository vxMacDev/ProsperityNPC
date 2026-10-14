package com.prosperitysmp.npc.it;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.economy.*;
import com.prosperitysmp.npc.economy.PurchaseRepository.Receipt;
import com.prosperitysmp.npc.economy.PurchaseRepository.State;
import com.prosperitysmp.npc.economy.PurchaseRequest.Kind;
import com.prosperitysmp.npc.economy.TransferJournal.Handle;
import com.prosperitysmp.npc.economy.TransferProof.Observation;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyRepository;
import com.prosperitysmp.npc.property.Tenant;
import com.prosperitysmp.npc.property.TenantRepository;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/** Isolated MariaDB evidence only; no Vault calls, production access, or runtime write authorization. */
@Timeout(45)
class EvictionTenancyIT {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 6);
    private static final Instant AT = Instant.parse("2026-09-07T09:00:00.123Z");
    private static final UUID WORLD = new UUID(0, 800);
    private static final long SEED = 5_000;
    private static final String V8 = "/db/migration/V8__eviction_tenancies.sql";
    private static final String OLD_COLUMNS = "event_id,request_id,property_id,payer_uuid,ownership_epoch,"
            + "kind,upgrade_type,target_level,tenant_id,economy_day,quoted_amount_cents,action_key,"
            + "state,outcome_code,created_at,completed_at,repair_incident_id,repair_type,repair_snapshot_sha256";
    private static final List<String> TABLES = List.of("npc_tenancy_departures", "npc_transfer_observations", "npc_transfer_attempts",
            "npc_purchase_intents", "npc_economy_events", "npc_incident_rolls", "npc_resident_memory",
            "npc_resident_profiles", "npc_tenants", "npc_upgrades", "npc_incidents", "npc_property_ownership",
            "npc_property_funding", "npc_properties");
    private static DB mariaDb;
    private static Database database;
    private static String schema;
    private PurchaseRepository purchases;
    private TransferJournal journal;
    private PropertyRepository properties;
    private TenantRepository tenants;
    private UUID property;
    private UUID owner;
    private UUID reserve;
    private UUID tenant;

    @BeforeAll
    static void bootDatabase() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        try {
            mariaDb.start();
            schema = "npc_eviction_" + UUID.randomUUID().toString().replace("-", "");
            mariaDb.createDB(schema);
            database = new Database();
            database.connect(new DatabaseSettings("localhost", cfg.getPort(), schema, "root", "", 6, 10_000L));
            for (String file : List.of("V1__initial_schema.sql", "V2__resident_identity.sql",
                    "V3__purchase_receipts.sql", "V4__transfer_journal.sql", "V5__resident_route_tags.sql",
                    "V6__incident_repairs.sql", "V7__incident_rolls.sql")) applyResource("/db/migration/" + file);
            applyResource(V8);
            applyResource("/db/migration/V15__tenancy_departures.sql");
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
    void freshTenancy() throws SQLException {
        dropFaultTrigger();
        for (String table : TABLES) execute("DELETE FROM " + table);
        property = UUID.randomUUID();
        owner = UUID.randomUUID();
        reserve = UUID.randomUUID();
        tenant = UUID.randomUUID();
        properties = new PropertyRepository(database);
        tenants = new TenantRepository(database);
        purchases = repository();
        journal = new TransferJournal(database, reserve);
        enroll(property);
        assertTrue(new ResidentRepository(database).register(ResidentCatalogue.draft(tenant, "Mara Vale")));
        tenants.insert(new Tenant(tenant, "Mara Vale", property, 0, "STANDARD", "GENERIC", 50));
    }

    @AfterEach
    void dropFaultTrigger() throws SQLException {
        if (database != null && database.isConnected()) execute("DROP TRIGGER IF EXISTS eviction_intent_failure");
    }

    @Test
    void quoteReadsCurrentFieldsWithoutWritesAndRoundTripsAtTenDollars() throws SQLException {
        execute("UPDATE npc_tenants SET row_version=17,slot=3 WHERE tenant_id=?", tenant);
        List<List<String>> before = allRows();
        PurchaseRequest request = quote();
        assertEquals(before, allRows());
        assertEquals(new EvictionQuote(17, 3), request.eviction());
        assertEquals(tenant, request.tenantId());
        assertEquals(property, request.propertyId());
        assertEquals(owner, request.payerId());
        assertEquals(0, request.ownershipEpoch());
        assertEquals(DAY, request.day());
        assertNull(request.repair());
        assertEquals(1_000, request.cents());
        Receipt receipt = purchases.enqueue(request);
        assertFalse(purchases.selectReadyIds(100).contains(receipt.eventId()), "bound pending payment is not ready");
        assertEquals(request, purchases.findByRequest(request.requestId()).orElseThrow().request());
        assertEquals(receipt, purchases.enqueue(request));
        assertEquals("17", scalar("SELECT evict_tenancy_revision FROM npc_purchase_intents"));
        assertEquals("3", scalar("SELECT evict_slot FROM npc_purchase_intents"));
        assertArrayEquals(new long[]{SEED, 0, 0}, properties.fundingOf(property));
        assertEquals(0, count("npc_transfer_attempts"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "epoch", "disabled", "missing", "vacate", "move", "slot-negative",
            "slot-four", "capacity", "revision-negative", "revision-exhausted"})
    void invalidCurrentScopeRejectsQuoteWithoutWriting(String mutation) throws SQLException {
        mutate(mutation);
        List<List<String>> before = allRows();
        SQLException refusal = assertThrows(SQLException.class, this::quote);
        if (List.of("missing", "vacate", "move").contains(mutation)) {
            assertInstanceOf(PurchaseRepository.EvictionUnavailableException.class, refusal);
            assertEquals(0, refusal.getSuppressed().length);
        }
        assertEquals(before, allRows());
    }

    @Test
    void futureEconomyDayCannotQuoteOrEnqueue() throws SQLException {
        // AT is exactly after Chicago's 04:00 reset: September 7 is current, September 8 is future.
        PurchaseRequest current = quote();
        PurchaseRequest future = new PurchaseRequest(UUID.randomUUID(), property, owner, 0, Kind.EVICT,
                null, null, tenant, DAY.plusDays(2), null, current.eviction());
        List<List<String>> before = allRows();
        assertThrows(SQLException.class, () -> purchases.quoteEviction(UUID.randomUUID(), property, owner,
                0, tenant, DAY.plusDays(2)));
        assertThrows(SQLException.class, () -> purchases.enqueue(future));
        assertEquals(before, allRows());
    }

    @ParameterizedTest
    @ValueSource(strings = {"move", "vacate", "rehouse", "slot", "revision", "owner", "epoch", "disabled", "capacity"})
    void changedTenancyRejectsOldQuoteBeforeEnqueueAndAfterRealPayment(String mutation) throws SQLException {
        PurchaseRequest request = quote();
        mutate(mutation);
        List<List<String>> before = allRows();
        SQLException refusal = assertThrows(SQLException.class, () -> purchases.enqueue(request));
        if (List.of("missing", "vacate", "move").contains(mutation)) {
            assertInstanceOf(PurchaseRepository.EvictionUnavailableException.class, refusal);
            assertEquals(0, refusal.getSuppressed().length);
        }
        assertEquals(before, allRows());

        freshTenancy();
        Receipt paid = purchases.enqueue(quote());
        pay(paid);
        mutate(mutation);
        List<List<String>> benefits = benefitRows();
        List<List<String>> ledger = ledgerRows();
        if (mutation.equals("capacity")) assertThrows(SQLException.class, () -> purchases.complete(paid.eventId()));
        else assertEquals(State.REVIEW_REQUIRED, purchases.complete(paid.eventId()).orElseThrow().state());
        assertEquals(benefits, benefitRows());
        assertEquals(ledger, ledgerRows());
    }

    @Test
    void bothRealJournalLegsAreRequiredThenVacancyAndReplayAreExactlyOnce() throws SQLException {
        PurchaseRequest request = quote();
        List<List<String>> before = allRows();
        var refusal = assertThrows(PurchaseRepository.InsufficientFundsException.class,
                () -> purchases.enqueue(request, request.cents(), request.cents() - 1));
        assertEquals(0, refusal.getSuppressed().length);
        assertEquals(before, allRows());
        Receipt receipt = purchases.enqueue(request, request.cents(), request.cents());
        List<List<String>> benefits = benefitRows();
        assertEquals(State.WAITING, purchases.complete(receipt.eventId()).orElseThrow().state());
        Handle handle = begin(receipt);
        assertEquals(State.WAITING, purchases.complete(receipt.eventId()).orElseThrow().state());
        assertEquals(TransferJournal.State.CREDIT_READY, journal.recordDebit(handle, debit(1_000)).state());
        assertTrue(journal.beginCredit(handle).newlyStarted());
        assertEquals(State.WAITING, purchases.complete(receipt.eventId()).orElseThrow().state());
        assertEquals(benefits, benefitRows());
        assertEquals(TransferJournal.State.PAID, journal.recordCredit(handle, credit(1_000)).state());
        pinPaymentTime(receipt.eventId());
        List<List<String>> ledger = ledgerRows();
        Receipt applied = purchases.complete(receipt.eventId()).orElseThrow();
        assertEquals(State.APPLIED, applied.state());
        assertVacant(1);
        List<List<String>> after = allRows();
        assertEquals(applied, purchases.complete(receipt.eventId()).orElseThrow());
        assertEquals(applied, purchases.enqueue(receipt.request()));
        assertEquals(applied, purchases.enqueue(retry(receipt.request())));
        assertEquals(after, allRows());
        assertThrows(PurchaseRepository.EvictionUnavailableException.class, this::quote);
        assertEquals(after, allRows());
        assertEquals(ledger, ledgerRows());
        assertArrayEquals(new long[]{SEED, 1_000, 0}, properties.fundingOf(property));
    }

    @Test
    void completedTenancyAllowsAnotherChargeOnlyAfterSameResidentReenters() throws SQLException {
        Receipt first = purchases.enqueue(quote());
        pay(first);
        Receipt applied = purchases.complete(first.eventId()).orElseThrow();
        assertEquals(State.APPLIED, applied.state());
        tenants.rehouse(tenant, revision(), property, 0);
        PurchaseRequest next = quote();
        assertEquals(new EvictionQuote(2, 0), next.eviction());
        assertNotEquals(first.request().actionKey(), next.actionKey());
        Receipt second = purchases.enqueue(next);
        assertNotEquals(first.eventId(), second.eventId());
        assertEquals(applied, purchases.enqueue(first.request()));
        pay(second);
        assertEquals(State.APPLIED, purchases.complete(second.eventId()).orElseThrow().state());
        assertVacant(3);
        assertEquals(2, count("npc_economy_events"));
        assertArrayEquals(new long[]{SEED, 2_000, 0}, properties.fundingOf(property));
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void pendingOrReviewOlderRevisionCannotBeBypassedEvenAcrossOwnershipEpoch(boolean review, boolean newEpoch)
            throws SQLException {
        Receipt first = purchases.enqueue(quote());
        if (review) purchases.markReview(first.eventId(), "FIXTURE_REVIEW");
        mutate("rehouse");
        if (newEpoch) execute("UPDATE npc_properties SET ownership_epoch=1 WHERE property_id=?", property);
        PurchaseRequest fresh = purchases.quoteEviction(UUID.randomUUID(), property, owner,
                newEpoch ? 1 : 0, tenant, DAY);
        assertNotEquals(first.request().eviction(), fresh.eviction());
        assertBlockedWithoutWrites(fresh, first.eventId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAID", "FAILED"})
    void bareLedgerFlagsRequireReviewAndNeverPermitAnotherCharge(String flag) throws SQLException {
        Receipt receipt = purchases.enqueue(quote());
        EconomyEventRepository events = new EconomyEventRepository(database);
        assertTrue(events.claim(receipt.eventId()));
        if (flag.equals("PAID")) events.finalizePaid(receipt.eventId(), 1_000);
        else events.finalizeFailed(receipt.eventId());
        pinPaymentTime(receipt.eventId()); // Keep chronology valid so only missing proof can reject.
        List<List<String>> benefits = benefitRows();
        List<List<String>> ledger = ledgerRows();
        Receipt held = purchases.complete(receipt.eventId()).orElseThrow();
        assertEquals(State.REVIEW_REQUIRED, held.state());
        assertEquals(flag.equals("PAID") ? "TRANSFER_PROOF_MISSING" : "DECLINE_PROOF_MISSING", held.outcome());
        assertEquals(benefits, benefitRows());
        assertEquals(ledger, ledgerRows());
        assertBlockedWithoutWrites(retry(receipt.request()), receipt.eventId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-debit", "missing-credit", "debit-delta", "credit-delta", "fake-paid"})
    void missingOrContradictoryRawJournalProofRollsBackWithoutEffects(String mutation) throws SQLException {
        Receipt receipt = purchases.enqueue(quote());
        Handle handle;
        if (mutation.equals("fake-paid")) {
            handle = begin(receipt);
            execute("UPDATE npc_transfer_attempts SET state='PAID',credit_intent_at=CURRENT_TIMESTAMP(3) WHERE event_id=?", receipt.eventId());
            execute("UPDATE npc_economy_events SET state='PAID',paid_amount_cents=1000,finalized_at=CURRENT_TIMESTAMP(3) WHERE event_id=?", receipt.eventId());
            pinPaymentTime(receipt.eventId());
        } else {
            handle = pay(receipt);
            String leg = mutation.contains("debit") ? "DEBIT" : "CREDIT";
            if (mutation.startsWith("missing")) execute("DELETE FROM npc_transfer_observations WHERE attempt_id=? AND leg=?", handle.attemptId(), leg);
            else execute("UPDATE npc_transfer_observations SET after_bits=before_bits WHERE attempt_id=? AND leg=?", handle.attemptId(), leg);
        }
        List<List<String>> before = allRows();
        assertThrows(SQLException.class, () -> purchases.complete(receipt.eventId()));
        assertEquals(before, allRows());
        assertBlockedWithoutWrites(retry(receipt.request()), receipt.eventId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"paid", "bare-failed", "legacy-failed"})
    void forgedDeclinedLabelCannotAuthorizeRetry(String evidence) throws SQLException {
        Receipt receipt;
        if (evidence.equals("legacy-failed")) {
            PurchaseRequest legacy = legacy();
            insertLegacy(legacy, "WAITING");
            receipt = purchases.findByRequest(legacy.requestId()).orElseThrow();
        } else receipt = purchases.enqueue(quote());
        if (evidence.equals("paid")) pay(receipt);
        else {
            EconomyEventRepository events = new EconomyEventRepository(database);
            assertTrue(events.claim(receipt.eventId()));
            events.finalizeFailed(receipt.eventId());
        }
        execute("UPDATE npc_purchase_intents SET state='DECLINED' WHERE event_id=?", receipt.eventId());
        List<List<String>> before = allRows();
        assertThrows(SQLException.class, () -> purchases.enqueue(retry(receipt.request())));
        assertEquals(before, allRows());
        mutate("rehouse");
        PurchaseRequest newTenancy = quote();
        List<List<String>> afterReentry = allRows();
        assertThrows(SQLException.class, () -> purchases.enqueue(newTenancy));
        assertEquals(afterReentry, allRows(), "older unproven declines cannot be bypassed by a new revision");
    }

    @Test
    void realNoMovementDeclineAllowsFreshUuidButNeverRetriesOldUuid() throws SQLException {
        Receipt first = purchases.enqueue(quote());
        Handle handle = begin(first);
        assertEquals(TransferJournal.State.DECLINED,
                journal.recordDebit(handle, new Observation(true, false, 0.0, 1_000.0, 1_000.0, false)).state());
        List<List<String>> benefits = benefitRows();
        Receipt declined = purchases.complete(first.eventId()).orElseThrow();
        assertEquals(State.DECLINED, declined.state());
        assertEquals(benefits, benefitRows());
        assertEquals(declined, purchases.enqueue(first.request()));
        Receipt next = purchases.enqueue(retry(first.request()));
        assertNotEquals(first.eventId(), next.eventId());
        pay(next);
        assertEquals(State.APPLIED, purchases.complete(next.eventId()).orElseThrow().state());
        assertEquals(declined, purchases.enqueue(first.request()));
        assertVacant(1);
        assertEquals(2, count("npc_economy_events"));
        assertArrayEquals(new long[]{SEED, 1_000, 0}, properties.fundingOf(property));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "CLAIMED", "PAID", "FAILED"})
    void legacyWaitingRowsQuarantineEvenPendingWithoutInventedPaymentOrVacancy(String eventState) throws SQLException {
        PurchaseRequest legacy = legacy();
        List<List<String>> before = allRows();
        assertThrows(SQLException.class, () -> purchases.enqueue(legacy));
        assertEquals(before, allRows());
        long event = insertLegacy(legacy, "WAITING");
        if (!eventState.equals("PENDING")) {
            EconomyEventRepository events = new EconomyEventRepository(database);
            assertTrue(events.claim(event));
            if (eventState.equals("PAID")) events.finalizePaid(event, 1_000);
            if (eventState.equals("FAILED")) events.finalizeFailed(event);
        }
        Receipt old = purchases.findByRequest(legacy.requestId()).orElseThrow();
        assertEquals(legacy, old.request());
        assertNull(old.request().eviction());
        assertEquals(old, purchases.enqueue(legacy));
        List<List<String>> benefits = benefitRows();
        List<List<String>> ledger = ledgerRows();
        assertTrue(purchases.selectReadyIds(100).contains(event), "legacy quarantine is ready even without terminal payment");
        Receipt held = purchases.complete(event).orElseThrow();
        assertEquals(State.REVIEW_REQUIRED, held.state());
        assertEquals("LEGACY_EVICTION_UNBOUND", held.outcome());
        assertEquals(held, purchases.complete(event).orElseThrow());
        assertEquals(benefits, benefitRows());
        assertEquals(ledger, ledgerRows());
        assertBlockedWithoutWrites(quote(), event);
    }

    @Test
    void appliedLegacyHistoryRemainsReadableWithoutReplayingAnEffect() throws SQLException {
        PurchaseRequest legacy = legacy();
        long event = insertLegacy(legacy, "APPLIED");
        List<List<String>> before = allRows();
        Receipt receipt = purchases.findByRequest(legacy.requestId()).orElseThrow();
        assertEquals(legacy, receipt.request());
        assertEquals(State.APPLIED, receipt.state());
        assertEquals(receipt, purchases.complete(event).orElseThrow());
        assertEquals(receipt, purchases.enqueue(legacy));
        assertEquals(legacy.eventKey(), scalar("SELECT idempotency_key FROM npc_economy_events WHERE event_id=?", event));
        assertEquals(before, allRows());
    }

    @Test
    void failureAtAppliedReceiptRollsBackVacancyAndCanCompleteOnceAfterRecovery() throws SQLException {
        Receipt receipt = purchases.enqueue(quote());
        pay(receipt);
        List<List<String>> before = allRows();
        execute("CREATE TRIGGER eviction_intent_failure AFTER UPDATE ON npc_purchase_intents FOR EACH ROW BEGIN "
                + "IF NEW.state='APPLIED' THEN IF (SELECT COUNT(*) FROM npc_tenants WHERE tenant_id=NEW.tenant_id "
                + "AND property_id IS NULL AND slot IS NULL AND row_version=1)=1 THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='vacancy reached before receipt failure'; "
                + "ELSE SIGNAL SQLSTATE '45001' SET MESSAGE_TEXT='receipt preceded vacancy'; END IF; END IF; END");
        SQLException failure = assertThrows(SQLException.class, () -> purchases.complete(receipt.eventId()));
        assertEquals("45000", failure.getSQLState());
        assertEquals(before, allRows());
        dropFaultTrigger();
        assertEquals(State.APPLIED, purchases.complete(receipt.eventId()).orElseThrow().state());
        assertVacant(1);
    }

    @Test
    void completionBeforeRealPaymentFinalizationRollsBackWithoutVacancy() throws SQLException {
        Receipt receipt = purchases.enqueue(quote());
        pay(receipt);
        PurchaseRepository earlier = new PurchaseRepository(database, reserve,
                Clock.fixed(AT.minusSeconds(121), ZoneOffset.UTC), ZoneId.of("America/Chicago"), 4);
        List<List<String>> before = allRows();
        assertThrows(SQLException.class, () -> earlier.complete(receipt.eventId()));
        assertEquals(before, allRows());
    }

    @Test
    void concurrentQuotesEnqueuesAndCompletionsUsePropertyLockAndApplyOnce() throws Exception {
        List<List<String>> before = allRows();
        List<PurchaseRequest> quotes = behindPropertyLock(this::quote, this::quote);
        assertEquals(quotes.getFirst().eviction(), quotes.getLast().eviction());
        assertEquals(before, allRows());
        List<Receipt> queued = behindPropertyLock(() -> repository().enqueue(quotes.getFirst()),
                () -> repository().enqueue(quotes.getLast()));
        assertEquals(queued.getFirst(), queued.getLast());
        assertEquals(1, count("npc_economy_events"));
        Receipt receipt = queued.getFirst();
        pay(receipt);
        List<List<String>> ledger = ledgerRows();
        List<Receipt> applied = behindPropertyLock(() -> repository().complete(receipt.eventId()).orElseThrow(),
                () -> repository().complete(receipt.eventId()).orElseThrow());
        assertEquals(applied.getFirst(), applied.getLast());
        assertEquals(State.APPLIED, applied.getFirst().state());
        assertVacant(1);
        assertEquals(ledger, ledgerRows());
    }

    @Test
    void deferredRuntimeWriterAndConcurrentMissingProofCannotEvict() throws Exception {
        Receipt receipt = purchases.enqueue(quote());
        EconomyEventRepository events = new EconomyEventRepository(database);
        assertTrue(events.claim(receipt.eventId()));
        events.finalizePaid(receipt.eventId(), 1_000);
        List<List<String>> before = allRows();
        try (PurchaseService service = new PurchaseService(null, purchases::findByRequest)) {
            BridgeApi bridge = new BridgeApi(null, null);
            bridge.heartbeat();
            assertTrue(bridge.isBridgeConnected());
            assertEquals(before, allRows());
        }
        List<List<String>> benefits = benefitRows();
        List<List<String>> ledger = ledgerRows();
        List<Receipt> held = behindPropertyLock(() -> repository().complete(receipt.eventId()).orElseThrow(),
                () -> repository().complete(receipt.eventId()).orElseThrow());
        assertEquals(held.getFirst(), held.getLast());
        assertEquals(State.REVIEW_REQUIRED, held.getFirst().state());
        assertEquals(benefits, benefitRows());
        assertEquals(ledger, ledgerRows());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void v8ResumeAndRepeatPreserveHistoricalRowsAndFingerprints(int completedSteps) throws Exception {
        // Rebuild only this embedded fixture's intent table, using the actual pre-V8 resources.
        execute("DROP TABLE npc_purchase_intents");
        try {
            applyResource("/db/migration/V3__purchase_receipts.sql");
            applyResource("/db/migration/V6__incident_repairs.sql");
            PurchaseRequest legacy = legacy();
            long event = insertLegacy(legacy, "WAITING");
            List<List<String>> old = rows("SELECT " + OLD_COLUMNS + " FROM npc_purchase_intents ORDER BY event_id");
            List<List<String>> ledger = ledgerRows();
            List<String> steps = resourceStatements(V8);
            assertEquals(3, steps.size(), "cover each interruption boundary, including already complete");
            for (int i = 0; i < completedSteps; i++) execute(steps.get(i));
            assertEquals(old, rows("SELECT " + OLD_COLUMNS + " FROM npc_purchase_intents ORDER BY event_id"));
            applyResource(V8);
            applyResource(V8);
            assertEquals(old, rows("SELECT " + OLD_COLUMNS + " FROM npc_purchase_intents ORDER BY event_id"));
            assertEquals(ledger, ledgerRows());
            Receipt read = purchases.findByRequest(legacy.requestId()).orElseThrow();
            assertEquals(legacy, read.request());
            assertNull(read.request().eviction());
            assertEquals("1", scalar("SELECT COUNT(*) FROM npc_purchase_intents WHERE evict_tenancy_revision IS NULL AND evict_slot IS NULL"));
            assertEquals(legacy.eventKey(), scalar("SELECT idempotency_key FROM npc_economy_events WHERE event_id=?", event));
            List<List<String>> before = allRows();
            assertThrows(SQLException.class, () -> execute("UPDATE npc_purchase_intents SET evict_slot=0 WHERE event_id=?", event));
            assertEquals(before, allRows());
        } finally {
            applyResource("/db/migration/V6__incident_repairs.sql");
            applyResource(V8);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"evict_tenancy_revision=NULL", "evict_slot=NULL", "evict_tenancy_revision=-1",
            "evict_tenancy_revision=9223372036854775807", "evict_slot=-1", "evict_slot=4",
            "kind='MAINTAIN',tenant_id=NULL", "kind='UPGRADE',tenant_id=NULL,upgrade_type='ADVERTISING',target_level=1",
            "kind='REPAIR',tenant_id=NULL,repair_incident_id=1,repair_type='PESTS',repair_snapshot_sha256=REPEAT('a',64)"})
    void v8ConstraintRejectsMalformedOrWrongKindBindings(String assignment) throws SQLException {
        Receipt receipt = purchases.enqueue(quote());
        List<List<String>> before = allRows();
        assertThrows(SQLException.class, () -> execute("UPDATE npc_purchase_intents SET " + assignment + " WHERE event_id=?", receipt.eventId()));
        assertEquals(before, allRows());
    }

    private PurchaseRepository repository() {
        return new PurchaseRepository(database, reserve, Clock.fixed(AT, ZoneOffset.UTC), ZoneId.of("America/Chicago"), 4);
    }

    private PurchaseRequest quote() throws SQLException {
        return purchases.quoteEviction(UUID.randomUUID(), property, owner, 0, tenant, DAY);
    }

    private PurchaseRequest legacy() {
        return new PurchaseRequest(UUID.randomUUID(), property, owner, 0, Kind.EVICT, null, null, tenant, DAY);
    }

    private static PurchaseRequest retry(PurchaseRequest q) {
        return new PurchaseRequest(UUID.randomUUID(), q.propertyId(), q.payerId(), q.ownershipEpoch(), q.kind(),
                q.upgrade(), q.targetLevel(), q.tenantId(), q.day(), q.repair(), q.eviction());
    }

    private long insertLegacy(PurchaseRequest q, String state) throws SQLException {
        long event = new EconomyEventRepository(database).insertPending(EconomyEvent.pending(q.eventKey(), q.cents(),
                q.source(), q.propertyId(), q.payerId(), q.ownershipEpoch(), q.payerId(), q.day().toString()));
        execute("INSERT INTO npc_purchase_intents (event_id,request_id,property_id,payer_uuid,ownership_epoch,kind,"
                        + "tenant_id,economy_day,quoted_amount_cents,action_key,state) VALUES (?,?,?,?,?,'EVICT',?,?,?,?,?)",
                event, q.requestId(), q.propertyId(), q.payerId(), q.ownershipEpoch(), q.tenantId(),
                q.day().toString(), q.cents(), q.actionKey(), state);
        return event;
    }

    private void enroll(UUID id) throws SQLException {
        properties.insertEnrollment(new Property(id, WORLD, "eviction-" + id, "downtown", "room",
                4, 1_000, 1_000, owner, 0, 80, true), SEED);
    }

    private void mutate(String mutation) throws SQLException {
        switch (mutation) {
            case "owner" -> execute("UPDATE npc_properties SET owner_uuid=? WHERE property_id=?", UUID.randomUUID(), property);
            case "epoch" -> execute("UPDATE npc_properties SET ownership_epoch=ownership_epoch+1 WHERE property_id=?", property);
            case "disabled" -> properties.setEnabled(property, false);
            case "missing" -> execute("DELETE FROM npc_tenants WHERE tenant_id=?", tenant);
            case "vacate" -> assertTrue(tenants.vacate(tenant));
            case "rehouse" -> {
                assertTrue(tenants.vacate(tenant));
                tenants.rehouse(tenant, revision(), property, 0);
            }
            case "move" -> {
                UUID destination = UUID.randomUUID();
                enroll(destination);
                tenants.moveTenant(tenant, destination, 0, DAY.toString());
            }
            case "slot" -> execute("UPDATE npc_tenants SET slot=1 WHERE tenant_id=?", tenant);
            case "slot-negative" -> execute("UPDATE npc_tenants SET slot=-1 WHERE tenant_id=?", tenant);
            case "slot-four" -> execute("UPDATE npc_tenants SET slot=4 WHERE tenant_id=?", tenant);
            case "capacity" -> {
                execute("UPDATE npc_properties SET capacity=1 WHERE property_id=?", property);
                execute("UPDATE npc_tenants SET slot=1 WHERE tenant_id=?", tenant);
            }
            case "revision" -> execute("UPDATE npc_tenants SET row_version=row_version+1 WHERE tenant_id=?", tenant);
            case "revision-negative" -> execute("UPDATE npc_tenants SET row_version=-1 WHERE tenant_id=?", tenant);
            case "revision-exhausted" -> execute("UPDATE npc_tenants SET row_version=? WHERE tenant_id=?", Long.MAX_VALUE, tenant);
            default -> throw new AssertionError(mutation);
        }
    }

    private long revision() throws SQLException {
        return Long.parseLong(scalar("SELECT row_version FROM npc_tenants WHERE tenant_id=?", tenant));
    }

    private void assertVacant(long expectedRevision) throws SQLException {
        assertEquals(1, count("npc_tenants"));
        assertNull(scalar("SELECT property_id FROM npc_tenants WHERE tenant_id=?", tenant));
        assertNull(scalar("SELECT slot FROM npc_tenants WHERE tenant_id=?", tenant));
        assertEquals(expectedRevision, revision());
        assertEquals(1, count("npc_resident_profiles"));
        assertEquals(0, count("npc_resident_memory"));
    }

    private void assertBlockedWithoutWrites(PurchaseRequest request, long originalEvent) throws SQLException {
        List<List<String>> before = allRows();
        // Blocking may reject admission or return the unresolved receipt; neither may mint a new event.
        try {
            Receipt blocked = purchases.enqueue(request);
            assertEquals(originalEvent, blocked.eventId());
            assertTrue(blocked.state() == State.WAITING || blocked.state() == State.REVIEW_REQUIRED);
        } catch (SQLException rejected) {
            assertEquals(before, allRows());
        }
        assertEquals(before, allRows());
    }

    private Handle begin(Receipt receipt) throws SQLException {
        Handle handle = new Handle(UUID.randomUUID(), receipt.eventId(), UUID.randomUUID(), "a".repeat(64));
        assertTrue(journal.beginDebit(handle).newlyStarted());
        return handle;
    }

    private Handle pay(Receipt receipt) throws SQLException {
        Handle handle = begin(receipt);
        assertEquals(TransferJournal.State.CREDIT_READY, journal.recordDebit(handle, debit(receipt.quotedCents())).state());
        assertTrue(journal.beginCredit(handle).newlyStarted());
        assertEquals(TransferJournal.State.PAID, journal.recordCredit(handle, credit(receipt.quotedCents())).state());
        pinPaymentTime(receipt.eventId());
        return handle;
    }

    private static Observation debit(long cents) {
        double dollars = cents / 100.0;
        return new Observation(true, true, dollars, 1_000.0, 1_000.0 - dollars, false);
    }

    private static Observation credit(long cents) {
        double dollars = cents / 100.0;
        return new Observation(true, true, dollars, 2_000.0, 2_000.0 + dollars, false);
    }

    private static void pinPaymentTime(long event) throws SQLException {
        // Same real-journal helper as IncidentRepairIT; align SQL wall time with the injected clock.
        LocalDateTime paid = LocalDateTime.ofInstant(AT.minusSeconds(120), ZoneOffset.UTC);
        execute("UPDATE npc_economy_events SET created_at=?,claimed_at=?,finalized_at=? WHERE event_id=?", paid.minusSeconds(5), paid.minusSeconds(4), paid, event);
        execute("UPDATE npc_purchase_intents SET created_at=? WHERE event_id=?", paid.minusSeconds(5), event);
        execute("UPDATE npc_transfer_attempts SET created_at=?,credit_intent_at=?,updated_at=? WHERE event_id=?", paid.minusSeconds(4), paid.minusSeconds(2), paid, event);
        execute("UPDATE npc_transfer_observations o JOIN npc_transfer_attempts a ON a.attempt_id=o.attempt_id "
                + "SET o.observed_at=CASE WHEN o.leg='DEBIT' THEN ? ELSE ? END WHERE a.event_id=?", paid.minusSeconds(3), paid.minusSeconds(1), event);
    }

    private <T> List<T> behindPropertyLock(Callable<T> left, Callable<T> right) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<T> first;
            Future<T> second;
            try (Connection blocker = database.getConnection()) {
                blocker.setAutoCommit(false);
                try {
                    try (PreparedStatement ps = blocker.prepareStatement("SELECT property_id FROM npc_properties WHERE property_id=? FOR UPDATE")) {
                        ps.setQueryTimeout(3);
                        bind(ps, property);
                        try (ResultSet rs = ps.executeQuery()) { assertTrue(rs.next()); }
                    }
                    first = workers.submit(left);
                    second = workers.submit(right);
                    assertLockingWaiters();
                    assertFalse(first.isDone());
                    assertFalse(second.isDone());
                } finally { blocker.rollback(); }
            }
            return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS), "eviction workers terminated");
        }
    }

    private static void assertLockingWaiters() throws SQLException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            List<List<String>> queries = rows("SELECT INFO FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID() "
                    + "AND DB=? AND INFO LIKE '%npc_properties%' LIMIT 6", schema);
            if (queries.size() == 2) {
                for (List<String> row : queries) {
                    String sql = row.getFirst().stripLeading().toLowerCase(Locale.ROOT).replaceFirst(
                            "^set\\s+statement\\s+max_statement_time\\s*=\\s*\\d+(?:\\.\\d+)?\\s+for\\s+", "");
                    assertTrue(sql.matches("(?s)select\\s+.*\\s+for\\s+update\\s*"), "initial property locking read: " + sql);
                }
                return;
            }
            Thread.yield();
        }
        fail("Both eviction workers must reach the initial property lock");
    }

    private static List<List<String>> allRows() throws SQLException { return tableRows(TABLES); }

    private static List<List<String>> benefitRows() throws SQLException {
        return tableRows(List.of("npc_properties", "npc_tenants", "npc_resident_profiles", "npc_resident_memory", "npc_upgrades", "npc_incidents"));
    }

    private static List<List<String>> ledgerRows() throws SQLException {
        return tableRows(List.of("npc_economy_events", "npc_transfer_attempts", "npc_transfer_observations", "npc_property_funding"));
    }

    private static List<List<String>> tableRows(List<String> tables) throws SQLException {
        List<List<String>> result = new ArrayList<>();
        for (String table : tables) {
            String order = switch (table) {
                case "npc_transfer_observations" -> "attempt_id,leg";
                case "npc_transfer_attempts", "npc_economy_events", "npc_purchase_intents" -> "event_id";
                case "npc_resident_memory" -> "resident_id,player_id,source_ref";
                case "npc_resident_profiles" -> "resident_id";
                case "npc_tenants" -> "tenant_id";
                case "npc_tenancy_departures" -> "resident_id,occupancy_revision_before";
                case "npc_upgrades", "npc_property_ownership" -> "id";
                case "npc_incidents" -> "incident_id";
                case "npc_incident_rolls" -> "property_id,economy_day";
                case "npc_properties", "npc_property_funding" -> "property_id";
                default -> throw new IllegalArgumentException(table);
            };
            result.add(List.of(table));
            result.addAll(rows("SELECT * FROM " + table + " ORDER BY " + order));
        }
        return result;
    }

    private static List<List<String>> rows(String sql, Object... values) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(3);
            bind(ps, values);
            try (ResultSet rs = ps.executeQuery()) {
                List<List<String>> result = new ArrayList<>();
                while (rs.next()) {
                    List<String> row = new ArrayList<>();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                        Object value = rs.getObject(i);
                        row.add(value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : rs.getString(i));
                    }
                    result.add(row);
                }
                return result;
            }
        }
    }

    private static long count(String table) throws SQLException {
        if (!TABLES.contains(table)) throw new IllegalArgumentException(table);
        return Long.parseLong(scalar("SELECT COUNT(*) FROM " + table));
    }

    private static String scalar(String sql, Object... values) throws SQLException {
        List<List<String>> result = rows(sql, values);
        assertEquals(1, result.size(), sql);
        return result.getFirst().getFirst();
    }

    private static void execute(String sql, Object... values) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(3);
            bind(ps, values);
            ps.execute();
        }
    }

    private static void bind(PreparedStatement ps, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) {
            if (values[i] instanceof UUID uuid) ps.setBytes(i + 1, Uuids.toBytes(uuid));
            else ps.setObject(i + 1, values[i]);
        }
    }

    private static List<String> resourceStatements(String path) throws Exception {
        try (InputStream in = EvictionTenancyIT.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static void applyResource(String path) throws Exception {
        for (String sql : resourceStatements(path)) execute(sql);
    }
}

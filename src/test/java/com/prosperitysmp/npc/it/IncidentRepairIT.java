package com.prosperitysmp.npc.it;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.economy.EconomyEvent;
import com.prosperitysmp.npc.economy.EconomyEventRepository;
import com.prosperitysmp.npc.economy.PurchaseRepository;
import com.prosperitysmp.npc.economy.PurchaseRepository.Receipt;
import com.prosperitysmp.npc.economy.PurchaseRepository.State;
import com.prosperitysmp.npc.economy.PurchaseRequest;
import com.prosperitysmp.npc.economy.PurchaseRequest.Kind;
import com.prosperitysmp.npc.economy.TransferJournal;
import com.prosperitysmp.npc.economy.TransferJournal.Handle;
import com.prosperitysmp.npc.economy.TransferProof.Observation;
import com.prosperitysmp.npc.formulas.IncidentType;
import com.prosperitysmp.npc.formulas.UpgradeType;
import com.prosperitysmp.npc.property.IncidentRepository;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyRepository;
import com.prosperitysmp.npc.property.Tenant;
import com.prosperitysmp.npc.property.TenantRepository;
import com.prosperitysmp.npc.property.UpgradeRepository;
import com.prosperitysmp.npc.resident.RelationshipMemory;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Isolated MariaDB evidence fixtures only: no Vault calls, network money, or runtime authorization. */
@Timeout(45)
class IncidentRepairIT {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 6);
    private static final Instant AT = Instant.parse("2026-09-07T09:00:00.123Z");
    private static final Instant CREATED = Instant.parse("2026-09-06T10:00:00Z");
    private static final Instant DUE = Instant.parse("2026-09-08T10:00:00Z");
    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private static final UUID WORLD = new UUID(0, 700);
    private static final long SEED = 5_000;
    private static final String V6 = "/db/migration/V6__incident_repairs.sql";
    private static final String OLD_COLUMNS = "event_id,request_id,property_id,payer_uuid,ownership_epoch,"
            + "kind,upgrade_type,target_level,tenant_id,economy_day,quoted_amount_cents,action_key,"
            + "state,outcome_code,created_at,completed_at";
    private static final List<String> TABLES = List.of("npc_tenancy_departures", "npc_incident_service_receipts", "npc_incident_rolls",
            "npc_transfer_observations", "npc_transfer_attempts",
            "npc_purchase_intents", "npc_economy_events", "npc_resident_memory", "npc_resident_profiles",
            "npc_tenants", "npc_upgrades", "npc_incidents", "npc_property_ownership",
            "npc_property_funding", "npc_properties");
    private static DB mariaDb;
    private static Database database;
    private static String schemaName;
    private PurchaseRepository purchases;
    private TransferJournal journal;
    private PropertyRepository properties;
    private TenantRepository tenants;
    private ResidentRepository residents;
    private UUID property;
    private UUID owner;
    private UUID reserve;
    private List<UUID> occupants;
    private long incident;

    @BeforeAll
    static void bootDatabase() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        try {
            mariaDb.start();
            schemaName = "npc_repair_" + UUID.randomUUID().toString().replace("-", "");
            mariaDb.createDB(schemaName);
            database = new Database();
            database.connect(new DatabaseSettings("localhost", cfg.getPort(), schemaName, "root", "", 6, 10_000L));
            for (String file : List.of("V1__initial_schema.sql", "V2__resident_identity.sql",
                    "V3__purchase_receipts.sql", "V4__transfer_journal.sql", "V5__resident_route_tags.sql",
                    "V6__incident_repairs.sql", "V7__incident_rolls.sql", "V8__eviction_tenancies.sql",
                    "V12__incident_service_receipts.sql", "V15__tenancy_departures.sql")) applyResource("/db/migration/" + file);
        } catch (Exception | AssertionError failure) {
            try { stopDatabase(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        try {
            if (database != null) database.close();
        } finally {
            if (mariaDb != null) {
                DB stopping = mariaDb;
                mariaDb = null;
                stopping.stop();
            }
        }
    }

    @BeforeEach
    void freshRepair() throws SQLException {
        dropFaultTriggers();
        for (String table : TABLES) execute("DELETE FROM " + table);
        property = UUID.randomUUID();
        owner = UUID.randomUUID();
        reserve = UUID.randomUUID();
        properties = new PropertyRepository(database);
        tenants = new TenantRepository(database);
        residents = new ResidentRepository(database);
        purchases = repositoryAt(AT);
        journal = new TransferJournal(database, reserve);
        enroll(property);
        occupants = List.of(new UUID(0, 11), new UUID(Long.MIN_VALUE, 12));
        addResident(occupants.get(0), property, 0, "Mara Vale");
        addResident(occupants.get(1), property, 1, "Ellis Ward");
        UpgradeRepository upgrades = new UpgradeRepository(database);
        upgrades.setLevel(property, "ADVERTISING", 1, UpgradeType.ADVERTISING.costToLevelCents(1));
        upgrades.setLevel(property, "FACILITIES", 1, UpgradeType.FACILITIES.costToLevelCents(1));
        incident = createIncident(property, IncidentType.PESTS, "ADVERTISING", DAY, false);
        execute("UPDATE npc_upgrades SET disabled_incident_id=? WHERE property_id=? AND upgrade_type='ADVERTISING'",
                incident, property);
    }

    @AfterEach
    void dropFaultTriggers() throws SQLException {
        if (database != null && database.isConnected()) {
            execute("DROP TRIGGER IF EXISTS repair_memory_failure");
            execute("DROP TRIGGER IF EXISTS repair_intent_failure");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"000", "001", "010", "070", "100", "123", "700", "999"})
    void upgradeTimestampRoundTripsSqlMilliseconds(String fraction) throws SQLException {
        String raw = "2026-09-06 09:00:00." + fraction;
        execute("UPDATE npc_upgrades SET installed_at=? WHERE property_id=?", raw, property);
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT installed_at,CAST(installed_at AS CHAR) AS stored FROM npc_upgrades WHERE property_id=?")) {
            ps.setBytes(1, Uuids.toBytes(property));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(LocalDateTime.parse(raw.replace(' ', 'T')),
                        rs.getObject("installed_at", LocalDateTime.class));
                assertEquals(LocalDateTime.parse(raw.replace(' ', 'T')),
                        LocalDateTime.parse(rs.getString("stored").replace(' ', 'T')),
                        "SQL text must preserve all fractional zeros");
            }
        }
        quote();
    }

    @ParameterizedTest
    @CsvSource({"PESTS,2000,5", "PLUMBING,3500,8", "BREAK_IN,5000,12"})
    void canonicalRepairNeedsBothLegsThenAppliesAndRemembersOnceAcrossReplay(
            IncidentType type, long cents, int damage) throws SQLException {
        canonicalType(type);
        List<List<String>> untouched = allRows();
        PurchaseRequest request = quote();
        assertEquals(untouched, allRows(), "quoting may lock but must not write any row");
        assertEquals(type, request.repair().type());
        assertEquals(cents, request.cents());
        assertEquals(incident, request.repair().incidentId());
        assertTrue(request.repair().snapshotSha256().matches("[a-f0-9]{64}"));
        var refusal = assertThrows(PurchaseRepository.InsufficientFundsException.class,
                () -> purchases.enqueue(request, cents, cents - 1));
        assertEquals(0, refusal.getSuppressed().length);
        assertEquals(untouched, allRows());
        Receipt waiting = purchases.enqueue(request, cents, cents);
        assertEquals(waiting, purchases.enqueue(request));
        assertEquals(request, purchases.findByRequest(request.requestId()).orElseThrow().request());
        assertEquals(Long.toString(incident), scalar("SELECT repair_incident_id FROM npc_purchase_intents"));
        assertEquals(type.name(), scalar("SELECT repair_type FROM npc_purchase_intents"));
        assertEquals(request.repair().snapshotSha256(), scalar("SELECT repair_snapshot_sha256 FROM npc_purchase_intents"));
        List<List<String>> benefits = benefitRows();
        assertEquals(State.WAITING, purchases.complete(waiting.eventId()).orElseThrow().state());
        Handle handle = begin(waiting);
        assertEquals(State.WAITING, purchases.complete(waiting.eventId()).orElseThrow().state());
        journal.recordDebit(handle, debit(cents));
        journal.beginCredit(handle);
        assertEquals(State.WAITING, purchases.complete(waiting.eventId()).orElseThrow().state());
        assertEquals(benefits, benefitRows());
        assertEquals(TransferJournal.State.PAID, journal.recordCredit(handle, credit(cents)).state());
        pinPaymentTime(waiting.eventId());
        List<List<String>> paid = ledgerRows();
        Receipt applied = purchases.complete(waiting.eventId()).orElseThrow();
        assertEquals(State.APPLIED, applied.state());
        assertApplied(applied, 80 + damage, AT, DAY.plusDays(1));
        assertEquals(paid, ledgerRows(), "benefit completion never repeats or edits the paid transfer");
        assertArrayEquals(new long[]{SEED, cents, 0}, properties.fundingOf(property));
        List<List<String>> completed = allRows();
        purchases = repositoryAt(AT.plusSeconds(86_400));
        residents = new ResidentRepository(database);
        assertEquals(applied, purchases.findByRequest(request.requestId()).orElseThrow());
        assertEquals(applied, purchases.complete(waiting.eventId()).orElseThrow());
        assertEquals(applied, purchases.enqueue(retry(request)));
        assertEquals(applied, purchases.enqueue(request));
        assertEquals(completed, allRows());
        assertApplied(applied, 80 + damage, AT, DAY.plusDays(1));
        assertEquals(1, count("npc_economy_events"));
        assertEquals(1, count("npc_purchase_intents"));
    }

    @ParameterizedTest
    @EnumSource(IncidentType.class)
    void fixedDamageAllowanceIsCappedAtOneHundred(IncidentType type) throws SQLException {
        canonicalType(type);
        properties.setCondition(property, 99);
        Receipt receipt = purchases.enqueue(quote());
        pay(receipt);
        purchases.complete(receipt.eventId());
        assertApplied(receipt, 100, AT, DAY.plusDays(1));
    }

    @Test
    void maintenanceStillAddsFiveWithoutIncidentResolutionOrMemory() throws SQLException {
        canonicalType(IncidentType.BREAK_IN);
        List<List<String>> incidents = rows("SELECT * FROM npc_incidents ORDER BY incident_id");
        List<List<String>> upgrades = rows("SELECT * FROM npc_upgrades ORDER BY id");
        Receipt receipt = purchases.enqueue(new PurchaseRequest(UUID.randomUUID(), property, owner, 0,
                Kind.MAINTAIN, null, null, null, DAY));
        pay(receipt);
        assertEquals(State.APPLIED, purchases.complete(receipt.eventId()).orElseThrow().state());
        assertEquals("85", scalar("SELECT condition_score FROM npc_properties WHERE property_id=?", property));
        assertEquals(incidents, rows("SELECT * FROM npc_incidents ORDER BY incident_id"));
        assertEquals(upgrades, rows("SELECT * FROM npc_upgrades ORDER BY id"));
        assertEquals(0, count("npc_resident_memory"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-owner", "epoch", "disabled", "missing-incident", "prevented", "resolved"})
    void invalidCurrentScopeRejectsQuoteAndEnqueueWithoutWriting(String mutation) throws SQLException {
        PurchaseRequest original = quote();
        mutate(mutation);
        List<List<String>> before = allRows();
        assertThrows(SQLException.class, this::quote);
        assertThrows(SQLException.class, () -> purchases.enqueue(original));
        assertEquals(before, allRows());
    }

    @Test
    void missingAndForeignPropertyCannotQuoteTheIncident() throws SQLException {
        UUID foreign = UUID.randomUUID();
        enroll(foreign);
        List<List<String>> before = allRows();
        for (UUID id : List.of(UUID.randomUUID(), foreign)) {
            assertThrows(SQLException.class, () -> purchases.quoteRepair(UUID.randomUUID(), id, owner, 0, incident, DAY));
        }
        assertEquals(before, allRows());
    }

    @ParameterizedTest
    @ValueSource(strings = {"cash", "type", "damage", "items", "due", "created", "incident-day", "affected-upgrade",
            "tenant-move", "tenant-reentry", "tenant-version", "tenant-slot", "new-tenant", "disabled-upgrade", "upgrade-level",
            "upgrade-cost", "upgrade-installed"})
    void staleFullQuoteCannotEnqueueOrGrantPaidBenefits(String mutation) throws SQLException {
        PurchaseRequest original = quote();
        List<List<String>> originalState = benefitRows();
        mutate(mutation);
        List<List<String>> changed = allRows();
        assertNotEquals(originalState, benefitRows(), "fixture must actually change quoted state");
        assertThrows(SQLException.class, () -> purchases.enqueue(original));
        assertEquals(changed, allRows());

        freshRepair();
        Receipt receipt = purchases.enqueue(quote());
        pay(receipt);
        mutate(mutation);
        List<List<String>> benefits = benefitRows();
        List<List<String>> ledger = ledgerRows();
        assertEquals(State.REVIEW_REQUIRED, purchases.complete(receipt.eventId()).orElseThrow().state());
        assertEquals(benefits, benefitRows());
        assertEquals(ledger, ledgerRows());
        assertEquals(1, count("npc_purchase_intents"));
        assertEquals(1, count("npc_economy_events"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"property-condition", "property-version"})
    void unrelatedMaintenanceDoesNotInvalidateIncidentQuote(String mutation) throws SQLException {
        PurchaseRequest request = quote();
        mutate(mutation);
        assertEquals(request.repair(), quote().repair());
        Receipt receipt = purchases.enqueue(request);
        pay(receipt);
        mutate(mutation);
        int before = Integer.parseInt(scalar("SELECT condition_score FROM npc_properties WHERE property_id=?", property));
        assertEquals(State.APPLIED, purchases.complete(receipt.eventId()).orElseThrow().state());
        assertApplied(receipt, before + IncidentType.PESTS.conditionDamage(), AT, DAY.plusDays(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-owner", "epoch", "disabled", "missing-incident", "prevented", "resolved", "missing-profile"})
    void invalidTargetAfterPaymentRequiresReviewAndRetainsPaidLedger(String mutation) throws SQLException {
        Receipt receipt = purchases.enqueue(quote());
        pay(receipt);
        mutate(mutation);
        List<List<String>> before = benefitRows();
        List<List<String>> paid = ledgerRows();
        assertEquals(State.REVIEW_REQUIRED, purchases.complete(receipt.eventId()).orElseThrow().state());
        assertEquals(before, benefitRows());
        assertEquals(paid, ledgerRows());
        assertPaidLedger(receipt);
    }

    @Test
    void genericPaidBooleanWithoutTransferJournalCannotAuthorizeRepair() throws SQLException {
        Receipt receipt = purchases.enqueue(quote());
        EconomyEventRepository events = new EconomyEventRepository(database);
        assertTrue(events.claim(receipt.eventId()));
        events.finalizePaid(receipt.eventId(), receipt.quotedCents());
        pinPaymentTime(receipt.eventId());
        List<List<String>> before = benefitRows();
        List<List<String>> paid = ledgerRows();
        Receipt held = purchases.complete(receipt.eventId()).orElseThrow();
        assertEquals(State.REVIEW_REQUIRED, held.state());
        assertEquals("TRANSFER_PROOF_MISSING", held.outcome());
        assertEquals(before, benefitRows());
        assertEquals(paid, ledgerRows());
        assertEquals(0, count("npc_transfer_attempts"));
        assertEquals(held, purchases.enqueue(retry(receipt.request())));
    }

    @Test
    void ambiguousDebitNeverGrantsOrQueuesAnotherCharge() throws SQLException {
        Receipt receipt = purchases.enqueue(quote());
        Handle handle = begin(receipt);
        assertEquals(TransferJournal.State.AMBIGUOUS,
                journal.recordDebit(handle, new Observation(false, false, null, null, null, true)).state());
        List<List<String>> before = benefitRows();
        List<List<String>> ledger = ledgerRows();
        Receipt held = purchases.complete(receipt.eventId()).orElseThrow();
        assertEquals(State.REVIEW_REQUIRED, held.state());
        assertEquals(held, purchases.enqueue(retry(receipt.request())));
        assertEquals(before, benefitRows());
        assertEquals(ledger, ledgerRows());
        assertEquals(1, count("npc_economy_events"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-debit", "missing-credit", "debit-delta", "credit-delta", "debit-threw",
            "credit-response", "attempt-payer", "attempt-payee", "attempt-fingerprint", "fake-paid"})
    void paidFlagsWithMissingOrContradictoryRawProofThrowAndLeaveEverythingUnchanged(String mutation) throws SQLException {
        Receipt receipt = purchases.enqueue(quote());
        Handle handle;
        if (mutation.equals("fake-paid")) {
            handle = begin(receipt);
            execute("UPDATE npc_transfer_attempts SET state='PAID',credit_intent_at=CURRENT_TIMESTAMP(3) WHERE event_id=?",
                    receipt.eventId());
            execute("UPDATE npc_economy_events SET state='PAID',paid_amount_cents=?,finalized_at=CURRENT_TIMESTAMP(3) WHERE event_id=?",
                    receipt.quotedCents(), receipt.eventId());
            pinPaymentTime(receipt.eventId());
        } else {
            handle = pay(receipt);
            switch (mutation) {
                case "missing-debit", "missing-credit" -> execute(
                        "DELETE FROM npc_transfer_observations WHERE attempt_id=? AND leg=?",
                        handle.attemptId(), mutation.equals("missing-debit") ? "DEBIT" : "CREDIT");
                case "debit-delta", "credit-delta" -> execute(
                        "UPDATE npc_transfer_observations SET after_bits=before_bits WHERE attempt_id=? AND leg=?",
                        handle.attemptId(), mutation.equals("debit-delta") ? "DEBIT" : "CREDIT");
                case "debit-threw" -> execute("UPDATE npc_transfer_observations SET threw=TRUE WHERE attempt_id=? AND leg='DEBIT'",
                        handle.attemptId());
                case "credit-response" -> execute("UPDATE npc_transfer_observations SET response_present=FALSE WHERE attempt_id=? AND leg='CREDIT'",
                        handle.attemptId());
                case "attempt-payer" -> execute("UPDATE npc_transfer_attempts SET payer_uuid=? WHERE event_id=?", UUID.randomUUID(), receipt.eventId());
                case "attempt-payee" -> execute("UPDATE npc_transfer_attempts SET payee_uuid=? WHERE event_id=?", UUID.randomUUID(), receipt.eventId());
                case "attempt-fingerprint" -> execute("UPDATE npc_transfer_attempts SET request_sha256=? WHERE event_id=?", "f".repeat(64), receipt.eventId());
                default -> throw new AssertionError(mutation);
            }
        }
        List<List<String>> before = allRows();
        assertThrows(SQLException.class, () -> purchases.complete(receipt.eventId()));
        assertEquals(before, allRows());
        assertEquals(State.WAITING, purchases.findByRequest(receipt.request().requestId()).orElseThrow().state());
        assertEquals(0, count("npc_resident_memory"));
    }

    @Test
    void wrongReserveCannotConsumeOtherwiseValidPaidProof() throws SQLException {
        Receipt receipt = purchases.enqueue(quote());
        pay(receipt);
        List<List<String>> before = allRows();
        PurchaseRepository wrong = new PurchaseRepository(database, UUID.randomUUID(), Clock.fixed(AT, ZoneOffset.UTC), CHICAGO, 4);
        assertThrows(SQLException.class, () -> wrong.complete(receipt.eventId()));
        assertEquals(before, allRows());
    }

    @Test
    void bareFailedFlagCannotDeclineRepairOrPermitASecondCharge() throws SQLException {
        Receipt receipt = purchases.enqueue(quote());
        EconomyEventRepository events = new EconomyEventRepository(database);
        assertTrue(events.claim(receipt.eventId()));
        events.finalizeFailed(receipt.eventId());
        List<List<String>> benefits = benefitRows();
        Receipt held = purchases.complete(receipt.eventId()).orElseThrow();
        assertEquals(State.REVIEW_REQUIRED, held.state());
        assertEquals("DECLINE_PROOF_MISSING", held.outcome());
        assertEquals(held, purchases.enqueue(retry(receipt.request())));
        assertEquals(held, purchases.enqueue(receipt.request()));
        assertEquals(benefits, benefitRows());
        assertEquals(1, count("npc_economy_events"));
        assertEquals(1, count("npc_purchase_intents"));
        assertArrayEquals(new long[]{SEED, 0, 0}, properties.fundingOf(property));
    }

    @ParameterizedTest
    @ValueSource(strings = {"paid-both-legs", "bare-failed"})
    void forgedDeclinedIntentCannotAuthorizeFreshUuidOrInsertAnotherPendingCharge(String evidence) throws SQLException {
        Receipt receipt = purchases.enqueue(quote());
        if (evidence.equals("paid-both-legs")) pay(receipt);
        else {
            EconomyEventRepository events = new EconomyEventRepository(database);
            assertTrue(events.claim(receipt.eventId()));
            events.finalizeFailed(receipt.eventId());
        }
        execute("UPDATE npc_purchase_intents SET state='DECLINED' WHERE event_id=?", receipt.eventId());
        List<List<String>> before = allRows();
        assertThrows(SQLException.class, () -> purchases.enqueue(retry(receipt.request())));
        assertEquals(before, allRows());
        assertEquals(1, count("npc_economy_events"));
        assertEquals(1, count("npc_purchase_intents"));
        assertEquals("0", scalar("SELECT COUNT(*) FROM npc_economy_events WHERE state='PENDING'"));
        assertEquals(0, count("npc_resident_memory"));
    }

    @Test
    void journalProvenNoMovementDeclineAllowsOnlyFreshUuidToRetryOnce() throws SQLException {
        Receipt first = purchases.enqueue(quote());
        Handle failed = begin(first);
        assertEquals(TransferJournal.State.DECLINED,
                journal.recordDebit(failed, new Observation(true, false, 0.0, 1_000.0, 1_000.0, false)).state());
        List<List<String>> benefits = benefitRows();
        Receipt declined = purchases.complete(first.eventId()).orElseThrow();
        assertEquals(State.DECLINED, declined.state());
        assertEquals(declined, purchases.enqueue(first.request()));
        assertEquals(benefits, benefitRows());
        assertArrayEquals(new long[]{SEED, 0, 0}, properties.fundingOf(property));
        Receipt next = purchases.enqueue(retry(first.request()));
        assertNotEquals(first.eventId(), next.eventId());
        assertEquals(State.WAITING, next.state());
        pay(next);
        assertEquals(State.APPLIED, purchases.complete(next.eventId()).orElseThrow().state());
        assertEquals(declined, purchases.enqueue(first.request()));
        assertApplied(next, 85, AT, DAY.plusDays(1));
        assertEquals(2, count("npc_economy_events"));
        assertEquals(2, count("npc_purchase_intents"));
        assertArrayEquals(new long[]{SEED, next.quotedCents(), 0}, properties.fundingOf(property));
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "final-intent"})
    void failureAfterAllBenefitsAndMemoryRollsBackEverythingExceptAlreadyPaidLedgerThenRetriesOnce(String stage)
            throws SQLException {
        Receipt receipt = purchases.enqueue(quote());
        pay(receipt);
        List<List<String>> before = allRows();
        String trigger = stage.equals("memory") ? "repair_memory_failure" : "repair_intent_failure";
        String tableAndOperation = stage.equals("memory") ? "INSERT ON npc_resident_memory" : "UPDATE ON npc_purchase_intents";
        String guard = stage.equals("memory")
                ? "NEW.kind='REPAIR_COMPLETED' AND (SELECT COUNT(*) FROM npc_resident_memory)=2"
                : "NEW.state='APPLIED'";
        // 45000 proves the trigger observed every benefit and both memories before aborting.
        execute("CREATE TRIGGER " + trigger + " AFTER " + tableAndOperation + " FOR EACH ROW BEGIN IF " + guard + " THEN "
                + "IF (SELECT COUNT(*) FROM npc_incidents WHERE resolved_at IS NOT NULL AND resolver_uuid IS NOT NULL)=1 "
                + "AND (SELECT COUNT(*) FROM npc_properties WHERE condition_score=85)=1 "
                + "AND (SELECT COUNT(*) FROM npc_upgrades WHERE disabled_incident_id IS NOT NULL)=0 "
                + "AND (SELECT COUNT(*) FROM npc_resident_memory WHERE kind='REPAIR_COMPLETED')=2 "
                + "AND (SELECT COUNT(*) FROM npc_economy_events WHERE state='PAID')=1 THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='all repair effects reached then failed'; "
                + "ELSE SIGNAL SQLSTATE '45001' SET MESSAGE_TEXT='fixture did not reach all repair effects'; END IF; END IF; END");
        SQLException failure = assertThrows(SQLException.class, () -> purchases.complete(receipt.eventId()));
        assertEquals("45000", failure.getSQLState());
        assertEquals(before, allRows(), "incident, condition, upgrade, every memory and intent roll back together");
        assertPaidLedger(receipt);
        assertEquals(State.WAITING, purchases.findByRequest(receipt.request().requestId()).orElseThrow().state());
        execute("DROP TRIGGER " + trigger);
        purchases = repositoryAt(AT);
        Receipt applied = purchases.complete(receipt.eventId()).orElseThrow();
        assertEquals(State.APPLIED, applied.state());
        assertApplied(applied, 85, AT, DAY.plusDays(1));
        List<List<String>> completed = allRows();
        assertEquals(applied, purchases.complete(receipt.eventId()).orElseThrow());
        assertEquals(applied, purchases.enqueue(retry(receipt.request())));
        assertEquals(completed, allRows());
        assertPaidLedger(receipt);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-profile", "five-residents", "over-capacity", "duplicate-slot", "invalid-slot"})
    void unboundedOrUnknownResidentsFailClosedAtQuoteAndAfterPayment(String mutation) throws SQLException {
        mutate(mutation);
        List<List<String>> before = allRows();
        assertThrows(SQLException.class, this::quote);
        assertEquals(before, allRows());

        freshRepair();
        Receipt receipt = purchases.enqueue(quote());
        pay(receipt);
        mutate(mutation);
        List<List<String>> benefits = benefitRows();
        List<List<String>> paid = ledgerRows();
        assertEquals(State.REVIEW_REQUIRED, purchases.complete(receipt.eventId()).orElseThrow().state());
        assertEquals(benefits, benefitRows());
        assertEquals(paid, ledgerRows());
    }

    @ParameterizedTest
    @ValueSource(ints = {RelationshipMemory.MAX_EVENTS, RelationshipMemory.MAX_EVENTS + 1})
    void fullOrOverflowingResidentMemoryAbortsBenefitsWithoutDiscardingPaidEvidence(int size) throws SQLException {
        Receipt receipt = purchases.enqueue(quote());
        pay(receipt);
        seedMemory(occupants.get(0), size);
        List<List<String>> before = allRows();
        assertThrows(SQLException.class, () -> purchases.complete(receipt.eventId()));
        assertEquals(before, allRows());
        assertPaidLedger(receipt);
        assertEquals(State.WAITING, purchases.findByRequest(receipt.request().requestId()).orElseThrow().state());
    }

    @Test
    void repairDoesNotClearUpgradeDisabledByADifferentIncident() throws SQLException {
        long other = createIncident(property, IncidentType.PLUMBING, "FACILITIES", DAY.minusDays(1), false);
        execute("UPDATE npc_upgrades SET disabled_incident_id=? WHERE property_id=? AND upgrade_type='FACILITIES'", other, property);
        List<List<String>> unrelated = rows("SELECT * FROM npc_incidents WHERE incident_id=?", other);
        Receipt receipt = purchases.enqueue(quote());
        pay(receipt);
        assertEquals(State.APPLIED, purchases.complete(receipt.eventId()).orElseThrow().state());
        assertApplied(receipt, 85, AT, DAY.plusDays(1));
        assertEquals(Long.toString(other), scalar("SELECT disabled_incident_id FROM npc_upgrades WHERE property_id=? AND upgrade_type='FACILITIES'", property));
        assertEquals(unrelated, rows("SELECT * FROM npc_incidents WHERE incident_id=?", other));
    }

    @ParameterizedTest
    @CsvSource({"2026-09-07T08:59:59.999Z,2026-09-06", "2026-09-07T09:00:00Z,2026-09-07"})
    void completionMemoryUsesChicagoFourAmBoundaryNotQuotedDay(String instant, String day) throws SQLException {
        Instant completedAt = Instant.parse(instant);
        purchases = repositoryAt(Instant.parse("2026-09-07T08:59:00Z"));
        Receipt receipt = purchases.enqueue(quote());
        pay(receipt);
        purchases = repositoryAt(completedAt);
        assertEquals(State.APPLIED, purchases.complete(receipt.eventId()).orElseThrow().state());
        assertApplied(receipt, 85, completedAt, LocalDate.parse(day));
        assertEquals(DAY.toString(), scalar("SELECT economy_day FROM npc_purchase_intents"));
        assertEquals(DAY.toString(), scalar("SELECT economy_day FROM npc_economy_events"));
    }

    @Test
    void completionClockBeforeQuoteDayRollsBackWithoutRewritingPaidLedger() throws SQLException {
        Receipt receipt = purchases.enqueue(quote());
        pay(receipt);
        List<List<String>> before = benefitRows();
        List<List<String>> ledger = ledgerRows();
        purchases = repositoryAt(CREATED.minusSeconds(86_400));
        assertEquals(State.REVIEW_REQUIRED, purchases.complete(receipt.eventId()).orElseThrow().state());
        assertEquals(before, benefitRows());
        assertEquals(ledger, ledgerRows());
        assertPaidLedger(receipt);
    }

    @ParameterizedTest
    @ValueSource(strings = {"created", "incident-day", "quote-day"})
    void futureChronologyCannotQuoteOrQueueACharge(String field) throws SQLException {
        PurchaseRequest original = quote();
        LocalDate proposedDay = DAY;
        switch (field) {
            case "created" -> execute("UPDATE npc_incidents SET created_at=? WHERE incident_id=?",
                    LocalDateTime.ofInstant(AT.plusMillis(1), ZoneOffset.UTC), incident);
            case "incident-day" -> execute("UPDATE npc_incidents SET economy_day=? WHERE incident_id=?",
                    DAY.plusDays(2).toString(), incident);
            case "quote-day" -> proposedDay = DAY.plusDays(2);
            default -> throw new AssertionError(field);
        }
        LocalDate day = proposedDay;
        PurchaseRequest request = new PurchaseRequest(original.requestId(), property, owner, 0,
                Kind.REPAIR, null, null, null, day, original.repair());
        List<List<String>> before = allRows();
        assertThrows(SQLException.class, () -> purchases.quoteRepair(UUID.randomUUID(), property, owner, 0, incident, day));
        assertThrows(SQLException.class, () -> purchases.enqueue(request));
        assertEquals(before, allRows());
        assertEquals(0, count("npc_economy_events"));
    }

    @Test
    void completionBeforeLedgerFinalizationThrowsWithoutAnyBenefitOrMemoryWrite() throws SQLException {
        Receipt receipt = purchases.enqueue(quote());
        pay(receipt);
        execute("UPDATE npc_economy_events SET finalized_at=? WHERE event_id=?",
                LocalDateTime.ofInstant(AT.plusMillis(1), ZoneOffset.UTC), receipt.eventId());
        List<List<String>> before = allRows();
        assertThrows(SQLException.class, () -> purchases.complete(receipt.eventId()));
        assertEquals(before, allRows(), "completion cannot predate the paid ledger or alter any evidence");
        assertEquals(State.WAITING, purchases.findByRequest(receipt.request().requestId()).orElseThrow().state());
        assertPaidLedger(receipt);
    }

    @Test
    void completionExactlyAtLedgerFinalizationIsValid() throws SQLException {
        Receipt receipt = purchases.enqueue(quote());
        pay(receipt);
        execute("UPDATE npc_economy_events SET finalized_at=? WHERE event_id=?",
                LocalDateTime.ofInstant(AT, ZoneOffset.UTC), receipt.eventId());
        assertEquals(State.APPLIED, purchases.complete(receipt.eventId()).orElseThrow().state());
        assertApplied(receipt, 85, AT, DAY.plusDays(1));
    }

    @Test
    void missingFinalizationCannotResolvePaidRepair() throws SQLException {
        Receipt receipt = purchases.enqueue(quote());
        pay(receipt);
        execute("UPDATE npc_economy_events SET finalized_at=NULL WHERE event_id=?", receipt.eventId());
        List<List<String>> before = allRows();
        assertThrows(SQLException.class, () -> purchases.complete(receipt.eventId()));
        assertEquals(before, allRows());
    }

    @Test
    void competingRequestsAndCompletionsWaitOnPropertyLockAndYieldOneEventAndEffect() throws Exception {
        PurchaseRequest request = quote();
        List<Receipt> queued = behindPropertyLock(
                () -> repositoryAt(AT).enqueue(request), () -> repositoryAt(AT).enqueue(retry(request)));
        assertEquals(queued.get(0), queued.get(1));
        assertEquals(1, count("npc_purchase_intents"));
        assertEquals(1, count("npc_economy_events"));
        Receipt receipt = queued.get(0);
        pay(receipt);
        List<List<String>> paid = ledgerRows();
        List<Receipt> completed = behindPropertyLock(
                () -> repositoryAt(AT).complete(receipt.eventId()).orElseThrow(),
                () -> repositoryAt(AT).complete(receipt.eventId()).orElseThrow());
        assertEquals(completed.get(0), completed.get(1));
        assertEquals(State.APPLIED, completed.get(0).state());
        assertApplied(receipt, 85, AT, DAY.plusDays(1));
        assertEquals(paid, ledgerRows());
    }

    @Test
    void quoteItselfWaitsForPropertyLockAndDoesNotWrite() throws Exception {
        List<List<String>> before = allRows();
        List<PurchaseRequest> quotes = behindPropertyLock(this::quote, this::quote);
        assertEquals(quotes.get(0).repair(), quotes.get(1).repair());
        assertEquals(before, allRows());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void v6ResumeAndRerunPreserveHistoricalV3IntentColumnsLedgerAndFingerprints(int completedSteps) throws Exception {
        // Only this isolated fixture schema is rebuilt; create rows using actual V3 SQL before V6 exists.
        execute("DROP TABLE npc_incident_service_receipts");
        execute("DROP TABLE npc_purchase_intents");
        applyResource("/db/migration/V3__purchase_receipts.sql");
        List<PurchaseRequest> historical = new ArrayList<>();
        try {
            for (Kind kind : List.of(Kind.UPGRADE, Kind.MAINTAIN, Kind.EVICT)) {
                PurchaseRequest request = new PurchaseRequest(UUID.randomUUID(), property, owner, 0, kind,
                        kind == Kind.UPGRADE ? UpgradeType.ADVERTISING : null, kind == Kind.UPGRADE ? 1 : null,
                        kind == Kind.EVICT ? occupants.get(0) : null, DAY);
                long event = new EconomyEventRepository(database).insertPending(EconomyEvent.pending(request.eventKey(),
                        request.cents(), request.source(), property, owner, 0, owner, DAY.toString()));
                execute("""
                        INSERT INTO npc_purchase_intents (event_id,request_id,property_id,payer_uuid,ownership_epoch,
                          kind,upgrade_type,target_level,tenant_id,economy_day,quoted_amount_cents,action_key)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                        """, event, request.requestId(), property, owner, 0, kind.name(),
                        request.upgrade() == null ? null : request.upgrade().name(), request.targetLevel(),
                        request.tenantId(), DAY.toString(), request.cents(), request.actionKey());
                historical.add(request);
            }
            List<List<String>> old = rows("SELECT " + OLD_COLUMNS + " FROM npc_purchase_intents ORDER BY event_id");
            List<List<String>> ledger = ledgerRows();
            try (InputStream in = IncidentRepairIT.class.getResourceAsStream(V6)) {
                assertNotNull(in);
                List<String> steps = MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                assertEquals(3, steps.size(), "fixture covers every interruption boundary in this migration");
                for (int i = 0; i < completedSteps; i++) execute(steps.get(i));
            }
            assertEquals(old, rows("SELECT " + OLD_COLUMNS + " FROM npc_purchase_intents ORDER BY event_id"));
            if (completedSteps == 2) {
                long eventId = Long.parseLong(old.getFirst().getFirst());
                assertThrows(SQLException.class, () -> execute("UPDATE npc_purchase_intents SET kind='REPAIR',"
                        + "upgrade_type=NULL,target_level=NULL,tenant_id=NULL,repair_incident_id=?,repair_type='PESTS',"
                        + "repair_snapshot_sha256=REPEAT('a',64) WHERE event_id=?", incident, eventId),
                        "old constraint must keep new repairs disabled until replacement is complete");
            }
            applyResource(V6);
            applyResource(V6);
            assertEquals(old, rows("SELECT " + OLD_COLUMNS + " FROM npc_purchase_intents ORDER BY event_id"));
            assertEquals(ledger, ledgerRows());
            assertEquals("3", scalar("SELECT COUNT(*) FROM npc_purchase_intents WHERE repair_incident_id IS NULL"
                    + " AND repair_type IS NULL AND repair_snapshot_sha256 IS NULL"));
            applyResource("/db/migration/V8__eviction_tenancies.sql");
            PurchaseRepository rebuilt = new PurchaseRepository(database, reserve, Clock.systemUTC(), CHICAGO, 4);
            for (PurchaseRequest request : historical) {
                Receipt receipt = rebuilt.findByRequest(request.requestId()).orElseThrow();
                assertEquals(request, receipt.request());
                assertEquals(State.WAITING, receipt.state());
                assertEquals(request.eventKey(), scalar("SELECT idempotency_key FROM npc_economy_events WHERE event_id=?", receipt.eventId()));
            }
        } finally {
            applyResource(V6);
            applyResource("/db/migration/V8__eviction_tenancies.sql");
            applyResource("/db/migration/V12__incident_service_receipts.sql");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"repair_incident_id=NULL", "repair_incident_id=0", "repair_incident_id=-1",
            "repair_type=NULL", "repair_type='UNKNOWN'", "repair_snapshot_sha256=NULL", "repair_snapshot_sha256='abc'",
            "repair_snapshot_sha256=REPEAT('A',64)", "repair_snapshot_sha256=REPEAT('g',64)",
            "upgrade_type='ADVERTISING'", "target_level=1", "tenant_id=UNHEX(REPEAT('11',16))", "kind='MAINTAIN'"})
    void v6SqlConstraintRejectsMalformedRepairShapesWithoutChangingReceipt(String assignment) throws SQLException {
        Receipt receipt = purchases.enqueue(quote());
        List<List<String>> before = allRows();
        assertThrows(SQLException.class, () -> execute("UPDATE npc_purchase_intents SET " + assignment + " WHERE event_id=?", receipt.eventId()));
        assertEquals(before, allRows());
    }

    @Test
    void v6SqlConstraintRejectsRepairFieldsOnLegacyIntent() throws SQLException {
        Receipt receipt = purchases.enqueue(new PurchaseRequest(UUID.randomUUID(), property, owner, 0,
                Kind.MAINTAIN, null, null, null, DAY));
        List<List<String>> before = allRows();
        for (String set : List.of("repair_incident_id=1", "repair_type='PESTS'", "repair_snapshot_sha256=REPEAT('a',64)")) {
            assertThrows(SQLException.class, () -> execute("UPDATE npc_purchase_intents SET " + set + " WHERE event_id=?", receipt.eventId()));
            assertEquals(before, allRows());
        }
    }

    private PurchaseRepository repositoryAt(Instant at) {
        return new PurchaseRepository(database, reserve, Clock.fixed(at, ZoneOffset.UTC), CHICAGO, 4);
    }

    private PurchaseRequest quote() throws SQLException {
        return purchases.quoteRepair(UUID.randomUUID(), property, owner, 0, incident, DAY);
    }

    private static PurchaseRequest retry(PurchaseRequest request) {
        return new PurchaseRequest(UUID.randomUUID(), request.propertyId(), request.payerId(), request.ownershipEpoch(),
                request.kind(), request.upgrade(), request.targetLevel(), request.tenantId(), request.day(), request.repair());
    }

    private void enroll(UUID id) throws SQLException {
        properties.insertEnrollment(new Property(id, WORLD, "repair-" + id, "downtown", "room",
                4, 1_000, 1_000, owner, 0, 80, true), SEED);
    }

    private void addResident(UUID id, UUID home, int slot, String name) throws SQLException {
        assertTrue(residents.register(ResidentCatalogue.draft(id, name)));
        tenants.insert(new Tenant(id, name, home, slot, "STANDARD", "GENERIC", 50));
    }

    private long createIncident(UUID home, IncidentType type, String upgrade, LocalDate day, boolean prevented)
            throws SQLException {
        new IncidentRepository(database).create(home, type, upgrade, day.toString(), DUE.toEpochMilli(), prevented);
        long id = Long.parseLong(scalar("SELECT incident_id FROM npc_incidents WHERE property_id=? AND economy_day=?", home, day.toString()));
        execute("UPDATE npc_incidents SET created_at=?,resolved_at=? WHERE incident_id=?",
                LocalDateTime.ofInstant(CREATED, ZoneOffset.UTC),
                prevented ? LocalDateTime.ofInstant(CREATED, ZoneOffset.UTC) : null, id);
        return id;
    }

    private void canonicalType(IncidentType type) throws SQLException {
        execute("UPDATE npc_incidents SET incident_type=?,cash_repair_cents=?,condition_damage=?,item_repair_spec=? WHERE incident_id=?",
                type.name(), type.cashRepairCents(), type.conditionDamage(), type.itemRepairSpec(), incident);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sameResidentReturningToSameRoomCannotReviveRepairQuote(boolean paid) throws SQLException {
        PurchaseRequest original = quote();
        Receipt receipt = paid ? purchases.enqueue(original) : null;
        if (paid) pay(receipt);
        UUID resident = occupants.get(0);
        assertTrue(tenants.vacate(resident));
        long revision = Long.parseLong(scalar("SELECT row_version FROM npc_tenants WHERE tenant_id=?", resident));
        tenants.rehouse(resident, revision, property, 0);
        List<List<String>> afterReentry = benefitRows();
        if (paid) assertEquals(State.REVIEW_REQUIRED, purchases.complete(receipt.eventId()).orElseThrow().state());
        else assertThrows(SQLException.class, () -> purchases.enqueue(original));
        assertEquals(afterReentry, benefitRows());
        assertEquals(0, count("npc_resident_memory"));
    }

    private void mutate(String mutation) throws SQLException {
        switch (mutation) {
            case "tenant-reentry" -> {
                UUID resident = occupants.get(0);
                assertTrue(tenants.vacate(resident));
                long revision = Long.parseLong(scalar("SELECT row_version FROM npc_tenants WHERE tenant_id=?", resident));
                tenants.rehouse(resident, revision, property, 0);
            }
            case "wrong-owner" -> execute("UPDATE npc_properties SET owner_uuid=? WHERE property_id=?", UUID.randomUUID(), property);
            case "epoch" -> execute("UPDATE npc_properties SET ownership_epoch=ownership_epoch+1 WHERE property_id=?", property);
            case "disabled" -> properties.setEnabled(property, false);
            case "missing-incident" -> execute("DELETE FROM npc_incidents WHERE incident_id=?", incident);
            case "prevented" -> execute("UPDATE npc_incidents SET prevented=TRUE,condition_damage=0,resolved_at=created_at WHERE incident_id=?", incident);
            case "resolved" -> execute("UPDATE npc_incidents SET resolved_at=created_at,resolver_uuid=? WHERE incident_id=?", owner, incident);
            case "cash" -> execute("UPDATE npc_incidents SET cash_repair_cents=cash_repair_cents+1 WHERE incident_id=?", incident);
            case "type" -> canonicalType(IncidentType.PLUMBING);
            case "damage" -> execute("UPDATE npc_incidents SET condition_damage=condition_damage+1 WHERE incident_id=?", incident);
            case "items" -> execute("UPDATE npc_incidents SET item_repair_spec=? WHERE incident_id=?", IncidentType.PLUMBING.itemRepairSpec(), incident);
            case "due" -> execute("UPDATE npc_incidents SET due_at=TIMESTAMPADD(SECOND,1,due_at) WHERE incident_id=?", incident);
            case "created" -> execute("UPDATE npc_incidents SET created_at=TIMESTAMPADD(SECOND,1,created_at) WHERE incident_id=?", incident);
            case "incident-day" -> execute("UPDATE npc_incidents SET economy_day=? WHERE incident_id=?", DAY.minusDays(1).toString(), incident);
            case "affected-upgrade" -> execute("UPDATE npc_incidents SET affected_upgrade_type='FACILITIES' WHERE incident_id=?", incident);
            case "tenant-move" -> {
                UUID destination = UUID.randomUUID();
                enroll(destination);
                tenants.moveTenant(occupants.get(0), destination, 0, DAY.toString());
            }
            case "tenant-version" -> execute("UPDATE npc_tenants SET row_version=row_version+1 WHERE tenant_id=?", occupants.get(0));
            case "tenant-slot" -> execute("UPDATE npc_tenants SET slot=2 WHERE tenant_id=?", occupants.get(0));
            case "new-tenant" -> addResident(UUID.randomUUID(), property, 2, "Nico Reed");
            case "disabled-upgrade" -> execute("UPDATE npc_upgrades SET disabled_incident_id=NULL WHERE property_id=? AND upgrade_type='ADVERTISING'", property);
            case "upgrade-level" -> execute("UPDATE npc_upgrades SET level=2 WHERE property_id=? AND upgrade_type='ADVERTISING'", property);
            case "upgrade-cost" -> execute("UPDATE npc_upgrades SET purchase_cost_cents=purchase_cost_cents+1 WHERE property_id=? AND upgrade_type='ADVERTISING'", property);
            case "upgrade-installed" -> execute("UPDATE npc_upgrades SET installed_at=TIMESTAMPADD(SECOND,1,installed_at) WHERE property_id=? AND upgrade_type='ADVERTISING'", property);
            case "property-condition" -> properties.setCondition(property, 79);
            case "property-version" -> execute("UPDATE npc_properties SET row_version=row_version+1 WHERE property_id=?", property);
            case "missing-profile" -> execute("DELETE FROM npc_resident_profiles WHERE resident_id=?", occupants.get(0));
            case "five-residents" -> {
                for (int slot = 2; slot < 5; slot++) addResident(UUID.randomUUID(), property, slot, "Nico Reed");
            }
            case "over-capacity" -> execute("UPDATE npc_properties SET capacity=1 WHERE property_id=?", property);
            case "duplicate-slot" -> execute("UPDATE npc_tenants SET slot=0 WHERE tenant_id=?", occupants.get(1));
            case "invalid-slot" -> execute("UPDATE npc_tenants SET slot=-1 WHERE tenant_id=?", occupants.get(0));
            default -> throw new AssertionError(mutation);
        }
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

    private static void pinPaymentTime(long eventId) throws SQLException {
        // Embedded DB uses wall time; align the complete fixture chronology with the injected clock.
        LocalDateTime paid = LocalDateTime.ofInstant(AT.minusSeconds(120), ZoneOffset.UTC);
        execute("UPDATE npc_economy_events SET created_at=?,claimed_at=?,finalized_at=? WHERE event_id=?",
                paid.minusSeconds(5), paid.minusSeconds(4), paid, eventId);
        execute("UPDATE npc_purchase_intents SET created_at=? WHERE event_id=?", paid.minusSeconds(5), eventId);
        execute("UPDATE npc_transfer_attempts SET created_at=?,credit_intent_at=?,updated_at=? WHERE event_id=?",
                paid.minusSeconds(4), paid.minusSeconds(2), paid, eventId);
        execute("UPDATE npc_transfer_observations o JOIN npc_transfer_attempts a ON a.attempt_id=o.attempt_id "
                + "SET o.observed_at=CASE WHEN o.leg='DEBIT' THEN ? ELSE ? END WHERE a.event_id=?",
                paid.minusSeconds(3), paid.minusSeconds(1), eventId);
    }

    private static Observation debit(long cents) {
        double dollars = cents / 100.0;
        return new Observation(true, true, dollars, 1_000.0, 1_000.0 - dollars, false);
    }

    private static Observation credit(long cents) {
        double dollars = cents / 100.0;
        return new Observation(true, true, dollars, 2_000.0, 2_000.0 + dollars, false);
    }

    private void assertApplied(Receipt receipt, int condition, Instant at, LocalDate day) throws SQLException {
        assertEquals(Integer.toString(condition), scalar("SELECT condition_score FROM npc_properties WHERE property_id=?", property));
        assertEquals(HexFormat.of().formatHex(Uuids.toBytes(owner)),
                rows("SELECT resolver_uuid FROM npc_incidents WHERE incident_id=?", incident).getFirst().getFirst());
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT resolved_at FROM npc_incidents WHERE incident_id=?")) {
            ps.setQueryTimeout(3);
            ps.setLong(1, incident);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(LocalDateTime.ofInstant(at, ZoneOffset.UTC), rs.getObject(1, LocalDateTime.class));
                assertFalse(rs.next());
            }
        }
        assertNull(scalar("SELECT disabled_incident_id FROM npc_upgrades WHERE property_id=? AND upgrade_type='ADVERTISING'", property));
        assertEquals(occupants.size(), count("npc_resident_memory"));
        for (UUID resident : occupants) {
            RelationshipMemory.State relationship = residents.readRelationship(resident, owner);
            assertEquals(4, relationship.reliability());
            assertEquals(1, relationship.distinctPositiveDays());
            assertEquals(List.of(new RelationshipMemory.Event(resident, owner,
                    "repair:" + incident + ":event:" + receipt.eventId(), RelationshipMemory.Kind.REPAIR_COMPLETED, at, day)),
                    relationship.recent());
            var profile=residents.find(resident).orElseThrow();
            var history=com.prosperitysmp.npc.resident.ResidentDialogue.reply(profile,relationship,
                    com.prosperitysmp.npc.resident.ResidentDialogue.Topic.HISTORY,6000);
            assertEquals("repair:"+incident+":event:"+receipt.eventId(),history.evidenceRef());
            assertTrue(history.line().contains("repair"));assertTrue(history.line().contains("Service day: "+day+"."));
            var view=com.prosperitysmp.npc.interaction.EncounterView.of(profile,relationship,history);
            assertFalse(view.toString().contains(history.evidenceRef()));
            UUID other=new UUID(0,999_999);assertNotEquals(owner,other);
            var unrelated=residents.readRelationship(resident,other);
            var privateHistory=com.prosperitysmp.npc.resident.ResidentDialogue.reply(profile,unrelated,
                    com.prosperitysmp.npc.resident.ResidentDialogue.Topic.HISTORY,6000);
            assertNull(privateHistory.evidenceRef());assertFalse(privateHistory.line().contains("repair"));
        }
    }

    private void assertPaidLedger(Receipt receipt) throws SQLException {
        assertEquals("PAID", scalar("SELECT state FROM npc_economy_events WHERE event_id=?", receipt.eventId()));
        assertEquals(Long.toString(receipt.quotedCents()), scalar("SELECT paid_amount_cents FROM npc_economy_events WHERE event_id=?", receipt.eventId()));
        assertEquals("PAID", scalar("SELECT state FROM npc_transfer_attempts WHERE event_id=?", receipt.eventId()));
        assertEquals("2", scalar("SELECT COUNT(*) FROM npc_transfer_observations o JOIN npc_transfer_attempts a ON a.attempt_id=o.attempt_id WHERE a.event_id=?", receipt.eventId()));
        assertArrayEquals(new long[]{SEED, receipt.quotedCents(), 0}, properties.fundingOf(property));
    }

    private void seedMemory(UUID resident, int size) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement("""
                INSERT INTO npc_resident_memory (resident_id,player_id,source_ref,kind,occurred_at_millis,economy_day)
                VALUES (?,?,?,'REPAIR_COMPLETED',?,?)
                """)) {
            ps.setQueryTimeout(10);
            for (int i = 0; i < size; i++) {
                bind(ps, resident, owner, "fixture:" + i, CREATED.toEpochMilli() + i, DAY.toString());
                ps.addBatch();
            }
            assertEquals(size, ps.executeBatch().length);
        }
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
                    assertLockingWaiters(2);
                    assertFalse(first.isDone());
                    assertFalse(second.isDone());
                } finally {
                    blocker.rollback();
                }
            }
            return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS), "repair workers terminated");
        }
    }

    private static void assertLockingWaiters(int expected) throws SQLException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        List<List<String>> queries = List.of();
        while (System.nanoTime() < deadline) {
            queries = rows("SELECT INFO FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID() AND DB=? AND INFO LIKE '%npc_properties%' LIMIT 6", schemaName);
            if (queries.size() == expected) {
                for (List<String> row : queries) {
                    String sql = row.getFirst().stripLeading().toLowerCase(Locale.ROOT).replaceFirst(
                            "^set\\s+statement\\s+max_statement_time\\s*=\\s*\\d+(?:\\.\\d+)?\\s+for\\s+", "");
                    assertTrue(sql.matches("(?s)select\\s+.*\\s+for\\s+update\\s*"), "must wait on initial locking read: " + sql);
                }
                return;
            }
            Thread.yield();
        }
        throw new AssertionError("expected " + expected + " initial property locking readers, saw " + queries);
    }

    private static List<List<String>> allRows() throws SQLException {
        return tableRows(TABLES);
    }

    private static List<List<String>> benefitRows() throws SQLException {
        return tableRows(List.of("npc_properties", "npc_incidents", "npc_tenants", "npc_upgrades", "npc_resident_profiles", "npc_resident_memory"));
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
                case "npc_incidents", "npc_incident_service_receipts" -> "incident_id";
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

    private static void applyResource(String path) throws Exception {
        try (InputStream in = IncidentRepairIT.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8))) execute(sql);
        }
    }
}

package com.prosperitysmp.npc.it;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.economy.PurchaseRepository;
import com.prosperitysmp.npc.economy.PurchaseRepository.Receipt;
import com.prosperitysmp.npc.economy.PurchaseRepository.State;
import com.prosperitysmp.npc.economy.PurchaseRequest;
import com.prosperitysmp.npc.economy.TransferJournal;
import com.prosperitysmp.npc.economy.TransferJournal.Handle;
import com.prosperitysmp.npc.economy.TransferProof.Observation;
import com.prosperitysmp.npc.formulas.IncidentType;
import com.prosperitysmp.npc.formulas.UpgradeType;
import com.prosperitysmp.npc.property.IncidentRollRepository;
import com.prosperitysmp.npc.property.IncidentRollRepository.Outcome;
import com.prosperitysmp.npc.property.IncidentServiceAssessment;
import com.prosperitysmp.npc.property.IncidentServiceRepository;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyRepository;
import com.prosperitysmp.npc.property.Tenant;
import com.prosperitysmp.npc.property.TenantRepository;
import com.prosperitysmp.npc.property.UpgradeRepository;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentRepository;
import com.prosperitysmp.npc.sim.DailyRoll;
import com.prosperitysmp.npc.sim.SimulationLogic;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
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

/** Embedded MariaDB only. Real receipt/journal APIs use fixture observations, never Vault. */
@Timeout(45)
class IncidentServiceRepositoryIT {
    private static final Instant NOW = Instant.parse("2026-09-07T12:00:00.001Z");
    private static final Instant FROM = NOW.minus(Duration.ofDays(30));
    private static final UUID WORLD = new UUID(0, 1700);
    private static final long SEED = 5_000;
    private static final String V12 = "/db/migration/V12__incident_service_receipts.sql";
    private static final List<String> TABLES = List.of("npc_item_repair_resolutions", "npc_item_repair_returns",
            "npc_item_repair_evidence", "npc_incident_item_repair_receipts", "npc_item_repair_return_plans",
            "npc_item_repair_lines", "npc_item_repair_collections", "npc_item_custody", "npc_item_custody_players",
            "npc_incident_service_receipts", "npc_incident_rolls",
            "npc_transfer_observations", "npc_transfer_attempts", "npc_purchase_intents", "npc_economy_events",
            "npc_resident_memory", "npc_resident_profiles", "npc_tenants", "npc_upgrades", "npc_incidents",
            "npc_property_ownership", "npc_property_funding", "npc_properties");
    private static DB mariaDb;
    private static Database database;
    private UUID owner;
    private UUID reserve;
    private long candidate;

    private record Incident(UUID property, long id, Instant created, Instant due) { }

    @BeforeAll
    static void bootDatabase() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        try {
            mariaDb.start();
            String schema = "npc_service_" + UUID.randomUUID().toString().replace("-", "");
            mariaDb.createDB(schema);
            database = new Database();
            database.connect(new DatabaseSettings("localhost", cfg.getPort(), schema, "root", "", 6, 10_000L));
            for (String file : List.of("V1__initial_schema.sql", "V2__resident_identity.sql",
                    "V3__purchase_receipts.sql", "V4__transfer_journal.sql", "V5__resident_route_tags.sql",
                    "V6__incident_repairs.sql", "V7__incident_rolls.sql", "V8__eviction_tenancies.sql",
                    "V12__incident_service_receipts.sql", "V21__item_custody.sql", "V23__property_insurance.sql",
                    "V35__item_repairs.sql", "V36__town_contracts_partnerships.sql")) applyResource("/db/migration/" + file);
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
    void freshDatabase() throws SQLException {
        dropFaultTrigger();
        for (String table : TABLES) execute("DELETE FROM " + table);
        owner = new UUID(0, 1701);
        reserve = new UUID(0, 1702);
        candidate = 0;
    }

    @AfterEach
    void dropFaultTrigger() throws SQLException {
        if (database != null && database.isConnected()) execute("DROP TRIGGER IF EXISTS service_receipt_failure");
    }

    @Test
    void emptyOwnerHasUnknownRateAndReadsDoNotCreateEvidence() throws SQLException {
        List<List<String>> before = data();
        IncidentServiceAssessment result = read(owner);
        assertEquals(owner, result.ownerId());
        assertEquals(NOW, result.capturedAt());
        assertEquals(FROM, result.dueFrom());
        assertTrue(result.cases().isEmpty());
        assertTrue(result.onTimeRate().isEmpty());
        assertEquals(result, read(owner));
        assertEquals(before, data());
    }

    @ParameterizedTest
    @CsvSource({"-1,ON_TIME,1.0", "0,ON_TIME,1.0", "1,LATE,0.0"})
    void repairAtExactDeadlineAndEitherAdjacentMillisecond(long offset, String status, double rate) throws SQLException {
        Incident incident = open(owner, NOW.minus(Duration.ofDays(2)));
        Instant repaired = incident.due().plusMillis(offset);
        Receipt applied = repair(incident, owner, 0, repaired);
        assertEquals(State.APPLIED, applied.state());
        IncidentServiceAssessment result = read(owner);
        assertCase(result, incident.id(), status);
        assertEquals(rate, result.onTimeRate().orElseThrow(), 0.0);
        assertEquals(repaired, timestamp("SELECT repaired_at FROM npc_incident_service_receipts WHERE incident_id=?", incident.id()));
        assertEquals(repaired, timestamp("SELECT resolved_at FROM npc_incidents WHERE incident_id=?", incident.id()));
        assertEquals(incident.created(), timestamp("SELECT incident_created_at FROM npc_incident_service_receipts WHERE incident_id=?", incident.id()));
        assertEquals(incident.due(), timestamp("SELECT incident_due_at FROM npc_incident_service_receipts WHERE incident_id=?", incident.id()));
        assertEquals(applied.request().repair().snapshotSha256(), scalar(
                "SELECT repair_snapshot_sha256 FROM npc_incident_service_receipts WHERE incident_id=?", incident.id()));
    }

    @Test
    void quietAndBlockedDaysCannotAddServiceCasesOrImproveRate() throws SQLException {
        Instant created = NOW.minus(Duration.ofDays(2));
        UUID quiet = propertyFor(created, false);
        enroll(quiet, owner);
        assertEquals(Outcome.NONE, rolls(created).roll(quiet, owner, 0, day(created)).outcome());
        assertTrue(read(owner).cases().isEmpty());
        assertTrue(read(owner).onTimeRate().isEmpty());
        Incident incident = open(owner, created);
        assertEquals(Outcome.BLOCKED_OPEN,
                rolls(incident.due()).roll(incident.property(), owner, 0, day(incident.due())).outcome());
        assertEquals(3, count("npc_incident_rolls"));
        IncidentServiceAssessment result = read(owner);
        assertCase(result, incident.id(), "OVERDUE");
        assertEquals(0.0, result.onTimeRate().orElseThrow(), 0.0);
    }

    @Test
    void dueCohortIsInclusiveAtBothEdgesAndUsesDeadlineNotRollTime() throws SQLException {
        Incident first = open(owner, FROM.minus(Duration.ofDays(1)));
        Incident last = open(owner, NOW.minus(Duration.ofDays(1)));
        open(owner, first.created().minusMillis(1));
        open(owner, last.created().plusMillis(1));
        IncidentServiceAssessment result = read(owner);
        assertEquals(Set.of(first.id(), last.id()), ids(result));
        assertTrue(result.cases().stream().allMatch(value -> value.outcome().name().equals("OVERDUE")));
        assertEquals(0.0, result.onTimeRate().orElseThrow(), 0.0);
        assertTrue(new IncidentServiceRepository(database, fixed(NOW.minusMillis(1)))
                .read(owner, FROM).cases().stream().noneMatch(value -> value.incidentId() == last.id()));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 2_592_000_001L})
    void zeroNegativeOrOverThirtyDayWindowRejectsWithoutWrites(long windowMillis) throws SQLException {
        List<List<String>> before = data();
        assertThrows(SQLException.class,
                () -> new IncidentServiceRepository(database, fixed(NOW)).read(owner, NOW.minusMillis(windowMillis)));
        assertEquals(before, data());
    }

    @Test
    void oneMillisecondWindowAndExactlyThirtyDaysAreAccepted() throws SQLException {
        Incident incident = open(owner, NOW.minus(Duration.ofDays(1)));
        assertCase(new IncidentServiceRepository(database, fixed(NOW)).read(owner, NOW.minusMillis(1)), incident.id(), "OVERDUE");
        assertCase(read(owner), incident.id(), "OVERDUE");
    }

    @Test
    void futureDeadlineCannotEarnCreditEvenForAnAlreadyCompletedRepair() throws SQLException {
        Incident incident = open(owner, NOW.minusSeconds(60));
        repair(incident, owner, 0, NOW.minusSeconds(1));
        assertTrue(read(owner).cases().isEmpty());
        assertTrue(read(owner).onTimeRate().isEmpty());
        assertCase(new IncidentServiceRepository(database, fixed(incident.due())).read(owner, NOW), incident.id(), "ON_TIME");
    }

    @Test
    void laterSaleDoesNotEraseCompletedOriginOwnerProofOrTransferCredit() throws SQLException {
        Incident incident = open(owner, NOW.minus(Duration.ofDays(2)));
        Receipt applied = repair(incident, owner, 0, incident.due());
        IncidentServiceAssessment beforeSale = read(owner);
        UUID buyer = new UUID(0, 1801);
        sell(incident, owner, buyer, 1);
        assertEquals(beforeSale, read(owner));
        assertCase(read(owner), incident.id(), "ON_TIME");
        assertTrue(read(buyer).cases().isEmpty());
        assertTrue(read(buyer).onTimeRate().isEmpty());
        assertEquals(applied, purchases(NOW).complete(applied.eventId()).orElseThrow());
        assertEquals(1, count("npc_incident_service_receipts"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void inheritedOpenIncidentAndNewEpochRepairStayHandoverReview(boolean sameOwner) throws SQLException {
        Incident incident = open(owner, NOW.minus(Duration.ofDays(2)));
        UUID nextOwner = sameOwner ? owner : new UUID(0, 1802);
        sell(incident, owner, nextOwner, 1);
        assertCase(read(owner), incident.id(), "HANDOVER_REVIEW");
        assertTrue(read(owner).onTimeRate().isEmpty());
        if (!sameOwner) assertTrue(read(nextOwner).cases().isEmpty());
        repair(incident, nextOwner, 1, incident.due());
        assertCase(read(owner), incident.id(), "HANDOVER_REVIEW");
        assertTrue(read(owner).onTimeRate().isEmpty());
        if (!sameOwner) {
            assertTrue(read(nextOwner).cases().isEmpty());
            assertTrue(read(nextOwner).onTimeRate().isEmpty());
        }
        assertEquals("1", scalar("SELECT ownership_epoch FROM npc_incident_service_receipts WHERE incident_id=?", incident.id()));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oneUnknownCaseSuppressesRateEvenAlongsideEarnedOnTimeProof(boolean handover) throws SQLException {
        Incident earned = open(owner, NOW.minus(Duration.ofDays(3)));
        repair(earned, owner, 0, earned.due());
        Incident unknown = open(owner, NOW.minus(Duration.ofDays(2)));
        if (handover) sell(unknown, owner, UUID.randomUUID(), 1);
        else execute("UPDATE npc_incidents SET resolved_at=?,resolver_uuid=? WHERE incident_id=?", unknown.due(), owner, unknown.id());
        IncidentServiceAssessment result = read(owner);
        assertEquals(2, result.cases().size());
        assertEquals(Set.of("ON_TIME", handover ? "HANDOVER_REVIEW" : "UNVERIFIED_RESOLUTION"),
                result.cases().stream().map(value -> value.outcome().name()).collect(Collectors.toSet()));
        assertTrue(result.onTimeRate().isEmpty());
    }

    @Test
    void legacyResolutionAndAppliedReplayCannotRetroactivelyEarnServiceProof() throws Exception {
        Incident incident = open(owner, NOW.minus(Duration.ofDays(2)));
        Receipt applied = repair(incident, owner, 0, incident.due());
        execute("DELETE FROM npc_incident_service_receipts WHERE incident_id=?", incident.id());
        List<List<String>> legacy = data();
        applyResource(V12);
        applyResource(V12);
        assertEquals(applied, purchases(NOW).complete(applied.eventId()).orElseThrow());
        assertCase(read(owner), incident.id(), "UNVERIFIED_RESOLUTION");
        assertTrue(read(owner).onTimeRate().isEmpty());
        assertEquals(legacy, data(), "migration and APPLIED replay must never backfill service facts");
    }

    @Test
    void actualRepairWithoutHistoricalRollStillWritesAtomicServiceReceipt() throws SQLException {
        Incident incident = open(owner, NOW.minus(Duration.ofDays(2)));
        execute("DELETE FROM npc_incident_rolls WHERE property_id=?", incident.property());
        Receipt applied = repair(incident, owner, 0, incident.due());
        assertEquals(State.APPLIED, applied.state());
        assertEquals(1, count("npc_incident_service_receipts"));
        assertTrue(read(owner).cases().isEmpty(), "a new repair fact cannot invent origin-owner attribution");
        assertTrue(read(owner).onTimeRate().isEmpty());
    }

    @Test
    void repeatedReadsReconstructedReaderAndAppliedReplayDoNotWriteOrDoubleCount() throws Exception {
        Incident incident = open(owner, NOW.minus(Duration.ofDays(2)));
        Receipt applied = repair(incident, owner, 0, incident.due());
        IncidentServiceAssessment expected = read(owner);
        List<List<String>> before = data();
        List<List<String>> index = rows("SELECT SEQ_IN_INDEX,COLUMN_NAME FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='npc_incident_rolls' "
                + "AND INDEX_NAME='idx_roll_service_owner' ORDER BY SEQ_IN_INDEX");
        assertEquals(List.of(List.of("1", "owner_uuid"), List.of("2", "outcome"),
                List.of("3", "recorded_at"), List.of("4", "property_id")), index);
        for (int i = 0; i < 3; i++) {
            assertEquals(expected, new IncidentServiceRepository(database, fixed(NOW)).read(owner, FROM));
            assertEquals(applied, purchases(NOW).complete(applied.eventId()).orElseThrow());
            assertEquals(applied, purchases(NOW).enqueue(applied.request()));
        }
        applyResource(V12);
        applyResource(V12);
        assertEquals(index, rows("SELECT SEQ_IN_INDEX,COLUMN_NAME FROM information_schema.STATISTICS "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='npc_incident_rolls' "
                + "AND INDEX_NAME='idx_roll_service_owner' ORDER BY SEQ_IN_INDEX"));
        assertEquals(before, data());
        assertEquals(1, expected.cases().size());
        assertEquals(1, count("npc_incident_service_receipts"));
        assertEquals(2, count("npc_transfer_observations"));
    }

    @Test
    void failedServiceInsertRollsBackResolutionConditionUpgradeMemoryAndAppliedButKeepsPayment() throws SQLException {
        Incident incident = open(owner, NOW.minus(Duration.ofDays(2)));
        UUID resident = UUID.randomUUID();
        assertTrue(new ResidentRepository(database).register(ResidentCatalogue.draft(resident, "Mara Vale")));
        new TenantRepository(database).insert(new Tenant(resident, "Mara Vale", incident.property(), 0, "STANDARD", "GENERIC", 50));
        new UpgradeRepository(database).setLevel(incident.property(), "ADVERTISING", 1, UpgradeType.ADVERTISING.costToLevelCents(1));
        execute("UPDATE npc_upgrades SET installed_at=?,disabled_incident_id=? WHERE property_id=?", incident.created().minusSeconds(1), incident.id(), incident.property());
        execute("UPDATE npc_incidents SET affected_upgrade_type='ADVERTISING' WHERE incident_id=?", incident.id());
        Receipt paid = pay(incident, owner, 0, incident.due());
        List<List<String>> before = data();
        execute("""
                CREATE TRIGGER service_receipt_failure BEFORE INSERT ON npc_incident_service_receipts
                FOR EACH ROW BEGIN
                  IF (SELECT COUNT(*) FROM npc_incidents WHERE incident_id=NEW.incident_id
                        AND resolved_at=NEW.repaired_at AND resolver_uuid=NEW.payer_uuid)=1
                    AND (SELECT COUNT(*) FROM npc_properties WHERE property_id=NEW.property_id AND condition_score=80)=1
                    AND (SELECT COUNT(*) FROM npc_upgrades WHERE property_id=NEW.property_id AND disabled_incident_id IS NOT NULL)=0
                    AND (SELECT COUNT(*) FROM npc_resident_memory WHERE kind='REPAIR_COMPLETED')=1
                    AND (SELECT COUNT(*) FROM npc_purchase_intents WHERE event_id=NEW.event_id AND state='WAITING')=1
                    AND (SELECT COUNT(*) FROM npc_economy_events WHERE event_id=NEW.event_id AND state='PAID')=1
                  THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture service receipt failure';
                  ELSE SIGNAL SQLSTATE '45001' SET MESSAGE_TEXT='service INSERT reached at wrong effect phase';
                  END IF;
                END
                """);
        SQLException failure = assertThrows(SQLException.class, () -> purchases(incident.due()).complete(paid.eventId()));
        assertEquals("45000", failure.getSQLState());
        assertTrue(failure.getMessage().contains("fixture service receipt failure"), failure.toString());
        assertEquals(before, data(), "all benefit writes and WAITING-to-APPLIED must roll back with the receipt INSERT");
        assertEquals("PAID", scalar("SELECT state FROM npc_economy_events WHERE event_id=?", paid.eventId()));
        assertEquals("PAID", scalar("SELECT state FROM npc_transfer_attempts WHERE event_id=?", paid.eventId()));
        assertEquals("WAITING", scalar("SELECT state FROM npc_purchase_intents WHERE event_id=?", paid.eventId()));
        assertEquals(0, count("npc_incident_service_receipts"));
        assertEquals(0, count("npc_resident_memory"));
        dropFaultTrigger();
        assertEquals(State.APPLIED, purchases(incident.due()).complete(paid.eventId()).orElseThrow().state());
        assertEquals(1, count("npc_incident_service_receipts"));
        assertEquals(1, count("npc_resident_memory"));
        assertEquals("80", scalar("SELECT condition_score FROM npc_properties WHERE property_id=?", incident.property()));
        assertNull(scalar("SELECT disabled_incident_id FROM npc_upgrades WHERE property_id=?", incident.property()));
        assertCase(read(owner), incident.id(), "ON_TIME");
    }

    @Test
    void paidLabelWithoutTransferObservationsCannotCreateServiceFact() throws SQLException {
        Incident incident = open(owner, NOW.minus(Duration.ofDays(2)));
        PurchaseRepository purchases = purchases(incident.due());
        Receipt waiting = purchases.enqueue(quote(purchases, incident, owner, 0, incident.due()));
        execute("UPDATE npc_economy_events SET state='PAID',paid_amount_cents=requested_amount_cents,finalized_at=? WHERE event_id=?",
                incident.due().minusSeconds(1), waiting.eventId());
        assertEquals(State.REVIEW_REQUIRED, purchases.complete(waiting.eventId()).orElseThrow().state());
        assertEquals(0, count("npc_transfer_observations"));
        assertEquals(0, count("npc_incident_service_receipts"));
        assertNull(scalar("SELECT resolved_at FROM npc_incidents WHERE incident_id=?", incident.id()));
        assertEquals("75", scalar("SELECT condition_score FROM npc_properties WHERE property_id=?", incident.property()));
        assertCase(read(owner), incident.id(), "OVERDUE");
    }

    @Test
    void nanosecondLowerBoundExcludesPreviousMillisecondWithoutRoundingDown() throws SQLException {
        Instant due = Instant.parse("2026-09-06T12:00:00.123Z");
        Incident incident = open(owner, due.minus(Duration.ofDays(1)));
        IncidentServiceRepository reader = new IncidentServiceRepository(database, fixed(NOW.plusNanos(999_999)));
        assertCase(reader.read(owner, due), incident.id(), "OVERDUE");
        Instant afterDue = due.plusNanos(1);
        IncidentServiceAssessment excluded = reader.read(owner, afterDue);
        assertTrue(excluded.cases().isEmpty());
        assertTrue(excluded.onTimeRate().isEmpty());
        assertEquals(afterDue, excluded.dueFrom(), "caller boundary remains exact despite SQL millisecond storage");
        assertEquals(NOW, excluded.capturedAt(), "capture clock intentionally truncates to SQL milliseconds");
    }

    @Test
    void authoritativeServiceFactDoesNotReevaluateHistoricalRawObservations() throws SQLException {
        Incident incident = open(owner, NOW.minus(Duration.ofDays(2)));
        repair(incident, owner, 0, incident.due());
        IncidentServiceAssessment earned = read(owner);
        execute("DELETE FROM npc_transfer_observations");
        List<List<String>> before = data();
        assertEquals(earned, read(owner), "atomic benefit receipt is the durable service authority");
        assertEquals(before, data());
    }

    @Test
    void exactly512ValidCasesAreCompleteBut513RejectsTheWholeView() throws SQLException {
        for (int i = 0; i < 512; i++) open(owner, NOW.minus(Duration.ofDays(2)));
        IncidentServiceAssessment full = read(owner);
        assertEquals(512, full.cases().size());
        assertEquals(512, ids(full).size());
        assertEquals(512, full.assessedCount());
        assertEquals(1, full.distinctDueDays());
        assertEquals(0.0, full.onTimeRate().orElseThrow(), 0.0);
        open(owner, NOW.minus(Duration.ofDays(2)));
        List<List<String>> before = data();
        SQLException failure = assertThrows(SQLException.class, () -> read(owner));
        assertTrue(failure.getMessage().contains("bound"), failure.toString());
        assertEquals(before, data(), "overflow must not truncate, repair or persist a partial assessment");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "property_id=UNHEX(REPEAT('ab',16))", "payer_uuid=UNHEX(REPEAT('ab',16))",
            "ownership_epoch=ownership_epoch+1", "incident_type='PLUMBING'", "cash_cents=cash_cents+1",
            "repair_snapshot_sha256=REPEAT('b',64)", "repair_snapshot_sha256='bad'",
            "repair_snapshot_sha256=REPEAT('G',64)",
            "incident_created_at=TIMESTAMPADD(MICROSECOND,1000,incident_created_at)",
            "incident_due_at=TIMESTAMPADD(MICROSECOND,1000,incident_due_at)",
            "repaired_at=TIMESTAMPADD(MICROSECOND,1000,repaired_at)", "event_id=9223372036854775807"
    })
    void corruptServiceBindingRejectsWholeViewRatherThanGivingPartialCredit(String assignment) throws SQLException {
        Incident incident = paidCaseWithHealthySibling();
        unchecked("UPDATE npc_incident_service_receipts SET " + assignment + " WHERE incident_id=?", incident.id());
        assertReadRejectedWithoutWrites();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "property_id=UNHEX(REPEAT('ab',16))", "payer_uuid=UNHEX(REPEAT('ab',16))", "ownership_epoch=1",
            "kind='MAINTAIN',repair_incident_id=NULL,repair_type=NULL,repair_snapshot_sha256=NULL",
            "state='WAITING'", "state='REVIEW_REQUIRED'", "state='DECLINED'", "outcome_code='OTHER'",
            "completed_at=NULL", "action_key='REPAIR|wrong'", "economy_day='2026-09-01'",
            "quoted_amount_cents=quoted_amount_cents+1", "repair_incident_id=repair_incident_id+1",
            "repair_type='PLUMBING'", "repair_snapshot_sha256=REPEAT('b',64)"
    })
    void corruptPurchaseBindingRejectsWholeView(String assignment) throws SQLException {
        Incident incident = paidCaseWithHealthySibling();
        unchecked("UPDATE npc_purchase_intents SET " + assignment + " WHERE repair_incident_id=?", incident.id());
        assertReadRejectedWithoutWrites();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "state='PENDING'", "paid_amount_cents=0", "paid_amount_cents=paid_amount_cents+1",
            "requested_amount_cents=requested_amount_cents+1", "idempotency_key='service-fixture-wrong'",
            "source='MAINTAIN'", "direction='TO_PUBLIC'", "property_id=UNHEX(REPEAT('ab',16))",
            "counterparty_uuid=UNHEX(REPEAT('ab',16))", "benefit_unit_id=UNHEX(REPEAT('ab',16))",
            "ownership_epoch=1", "economy_day='2026-09-01'", "finalized_at=NULL",
            "finalized_at='2026-09-07 12:00:00.002'"
    })
    void corruptLedgerIdentityOrPaymentRejectsWholeView(String assignment) throws SQLException {
        Incident incident = paidCaseWithHealthySibling();
        long event = Long.parseLong(scalar("SELECT event_id FROM npc_incident_service_receipts WHERE incident_id=?", incident.id()));
        unchecked("UPDATE npc_economy_events SET " + assignment + " WHERE event_id=?", event);
        assertReadRejectedWithoutWrites();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-incident", "missing-property", "missing-purchase", "missing-ledger",
            "roll-property", "roll-incident", "roll-day", "roll-type", "roll-damage", "roll-created",
            "incident-property", "incident-created", "incident-due", "incident-items", "incident-damage",
            "incident-prevented", "incident-resolver", "incident-resolved", "incident-unresolved", "epoch-regressed"})
    void missingOrStaleCrossBindingFailsClosed(String mutation) throws SQLException {
        Incident incident = paidCaseWithHealthySibling();
        long event = Long.parseLong(scalar("SELECT event_id FROM npc_incident_service_receipts WHERE incident_id=?", incident.id()));
        switch (mutation) {
            case "missing-incident" -> unchecked("DELETE FROM npc_incidents WHERE incident_id=?", incident.id());
            case "missing-property" -> unchecked("DELETE FROM npc_properties WHERE property_id=?", incident.property());
            case "missing-purchase" -> unchecked("DELETE FROM npc_purchase_intents WHERE event_id=?", event);
            case "missing-ledger" -> unchecked("DELETE FROM npc_economy_events WHERE event_id=?", event);
            case "roll-property" -> unchecked("UPDATE npc_incident_rolls SET property_id=UNHEX(REPEAT('ab',16)) WHERE incident_id=?", incident.id());
            case "roll-incident" -> unchecked("UPDATE npc_incident_rolls SET incident_id=9223372036854775807 WHERE incident_id=?", incident.id());
            case "roll-day" -> unchecked("UPDATE npc_incident_rolls SET economy_day='2026-09-01' WHERE incident_id=?", incident.id());
            case "roll-type" -> unchecked("UPDATE npc_incident_rolls SET incident_type='PLUMBING',condition_after=72 WHERE incident_id=?", incident.id());
            case "roll-damage" -> unchecked("UPDATE npc_incident_rolls SET condition_after=74 WHERE incident_id=?", incident.id());
            case "roll-created" -> unchecked("UPDATE npc_incident_rolls SET recorded_at=TIMESTAMPADD(MICROSECOND,1000,recorded_at) WHERE incident_id=?", incident.id());
            case "incident-property" -> unchecked("UPDATE npc_incidents SET property_id=UNHEX(REPEAT('ab',16)) WHERE incident_id=?", incident.id());
            case "incident-created" -> unchecked("UPDATE npc_incidents SET created_at=TIMESTAMPADD(MICROSECOND,1000,created_at) WHERE incident_id=?", incident.id());
            case "incident-due" -> unchecked("UPDATE npc_incidents SET due_at=TIMESTAMPADD(DAY,100,due_at) WHERE incident_id=?", incident.id());
            case "incident-items" -> unchecked("UPDATE npc_incidents SET item_repair_spec='OTHER' WHERE incident_id=?", incident.id());
            case "incident-damage" -> unchecked("UPDATE npc_incidents SET condition_damage=4 WHERE incident_id=?", incident.id());
            case "incident-prevented" -> unchecked("UPDATE npc_incidents SET prevented=TRUE WHERE incident_id=?", incident.id());
            case "incident-resolver" -> unchecked("UPDATE npc_incidents SET resolver_uuid=UNHEX(REPEAT('ab',16)) WHERE incident_id=?", incident.id());
            case "incident-resolved" -> unchecked("UPDATE npc_incidents SET resolved_at=TIMESTAMPADD(MICROSECOND,1000,resolved_at) WHERE incident_id=?", incident.id());
            case "incident-unresolved" -> unchecked("UPDATE npc_incidents SET resolved_at=NULL,resolver_uuid=NULL WHERE incident_id=?", incident.id());
            case "epoch-regressed" -> unchecked("UPDATE npc_properties SET ownership_epoch=-1 WHERE property_id=?", incident.property());
            default -> throw new AssertionError(mutation);
        }
        assertReadRejectedWithoutWrites();
    }

    @Test
    void serviceFactMovedToAnotherIncidentCannotDowngradeOriginalToLegacyAndCreditTheOther() throws SQLException {
        Incident incident = paidCaseWithHealthySibling();
        long other = Long.parseLong(scalar("SELECT incident_id FROM npc_incidents WHERE incident_id<>?", incident.id()));
        execute("UPDATE npc_incident_service_receipts SET incident_id=? WHERE incident_id=?", other, incident.id());
        assertReadRejectedWithoutWrites();
    }

    @Test
    void consistentSnapshotEstablishedBeforeEvidenceQuerySurvivesConcurrentRepairCommit() throws Exception {
        Incident incident = open(owner, NOW.minus(Duration.ofDays(2)));
        Receipt paid = pay(incident, owner, 0, incident.due());
        IncidentServiceAssessment before = read(owner);
        assertCase(before, incident.id(), "OVERDUE");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ReadGate gate = new ReadGate()) {
            try {
                Future<IncidentServiceAssessment> pending = worker.submit(
                        () -> new IncidentServiceRepository(gate.routed, fixed(NOW)).read(owner, FROM));
                gate.awaitSnapshot();
                assertFalse(pending.isDone());
                assertEquals(State.APPLIED, purchases(incident.due()).complete(paid.eventId()).orElseThrow().state());
                List<List<String>> afterWriter = data();
                gate.release();
                assertEquals(before, pending.get(10, TimeUnit.SECONDS),
                        "START's view must predate the writer even though the joined SELECT executes afterwards");
                assertCase(read(owner), incident.id(), "ON_TIME");
                assertEquals(afterWriter, data(), "both readers remain read-only across the repair commit");
            } finally {
                gate.release();
                worker.shutdownNow();
                assertTrue(worker.awaitTermination(15, TimeUnit.SECONDS), "service snapshot worker terminated");
            }
        }
    }

    private Incident paidCaseWithHealthySibling() throws SQLException {
        open(owner, NOW.minus(Duration.ofDays(3)));
        Incident incident = open(owner, NOW.minus(Duration.ofDays(2)));
        repair(incident, owner, 0, incident.due());
        assertEquals(2, read(owner).cases().size(), "corruption starts from a complete valid cohort");
        assertEquals(0.5, read(owner).onTimeRate().orElseThrow(), 0.0);
        return incident;
    }

    private void assertReadRejectedWithoutWrites() throws SQLException {
        List<List<String>> before = data();
        assertThrows(SQLException.class, () -> read(owner));
        assertEquals(before, data(), "fail closed without repairing the evidence or returning a partial cohort");
    }

    private static void unchecked(String sql, Object... values) throws SQLException {
        // Inject historical corruption on one fixture connection; always restore pooled session settings.
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

    /** All SQL is forwarded to MariaDB. Only the phase between START and the real SELECT is paused. */
    private static final class ReadGate implements AutoCloseable {
        private final CountDownLatch snapshot = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean armed = new AtomicBoolean(true);
        private final Database routed = new Database();

        private ReadGate() throws ReflectiveOperationException {
            HikariDataSource forwarding = new HikariDataSource() {
                @Override public Connection getConnection() throws SQLException {
                    return wrapConnection(database.getConnection());
                }
            };
            // Private facade only; never replace the shared fixture's real pool.
            Field source = Database.class.getDeclaredField("dataSource");
            source.setAccessible(true);
            source.set(routed, forwarding);
        }

        private Connection wrapConnection(Connection actual) {
            return proxy(Connection.class, (ignored, method, args) -> {
                if (method.getName().equals("prepareStatement") && args != null && args[0] instanceof String sql) {
                    String normalized = sql.stripLeading().toUpperCase(Locale.ROOT);
                    if (normalized.startsWith("SELECT") && normalized.contains("FROM NPC_INCIDENT_ROLLS")
                            && armed.compareAndSet(true, false)) {
                        assertTrue(started.get(), "real START must execute before preparing the evidence query");
                        snapshot.countDown();
                        try {
                            if (!resume.await(15, TimeUnit.SECONDS)) throw new AssertionError("service phase gate timed out");
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError("service phase gate interrupted", interrupted);
                        }
                    }
                    Object result = invoke(actual, method, args);
                    if (normalized.startsWith("START TRANSACTION")) {
                        return proxy(PreparedStatement.class, (statement, operation, values) -> {
                            Object value = invoke(result, operation, values);
                            if (operation.getName().equals("execute")) started.set(true);
                            return value;
                        });
                    }
                    return result;
                }
                return invoke(actual, method, args);
            });
        }

        void awaitSnapshot() throws InterruptedException {
            assertTrue(snapshot.await(10, TimeUnit.SECONDS), "fixture did not observe START followed by the evidence SELECT");
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

    private Incident open(UUID originOwner, Instant created) throws SQLException {
        UUID property = propertyFor(created, true);
        enroll(property, originOwner);
        var result = rolls(created).roll(property, originOwner, 0, day(created));
        assertTrue(result.newlyRecorded());
        assertEquals(Outcome.INCIDENT, result.outcome());
        assertNotNull(result.incidentId());
        return new Incident(property, result.incidentId(), created, created.plus(Duration.ofDays(1)));
    }

    private UUID propertyFor(Instant at, boolean incident) {
        SimulationLogic simulation = new SimulationLogic(new DailyRoll(SEED));
        for (int n = 0; n < 100_000; n++) {
            UUID id = new UUID(0x53455256494345L, ++candidate);
            IncidentType outcome = simulation.incidentForDay(id, day(at).toString(), 80).orElse(null);
            if (incident ? outcome == IncidentType.PESTS : outcome == null) return id;
        }
        throw new AssertionError("No deterministic incident fixture found within 100000 candidates");
    }

    private void enroll(UUID property, UUID originOwner) throws SQLException {
        new PropertyRepository(database).insertEnrollment(new Property(property, WORLD, "service-" + property,
                "test", "room", 4, 1000, 1000, originOwner, 0, 80, true), SEED);
    }

    private void sell(Incident incident, UUID previous, UUID next, int epoch) throws SQLException {
        new PropertyRepository(database).applyOwnerChange(incident.property(), previous, next, epoch, "service IT sale");
    }

    private static IncidentRollRepository rolls(Instant at) {
        return new IncidentRollRepository(database, new SimulationLogic(new DailyRoll(SEED)), fixed(at), ZoneOffset.UTC, 0);
    }

    private PurchaseRepository purchases(Instant at) {
        return new PurchaseRepository(database, reserve, fixed(at), ZoneOffset.UTC, 0);
    }

    private PurchaseRequest quote(PurchaseRepository purchases, Incident incident, UUID payer, int epoch, Instant at) throws SQLException {
        return purchases.quoteRepair(UUID.randomUUID(), incident.property(), payer, epoch, incident.id(), day(at));
    }

    private Receipt pay(Incident incident, UUID payer, int epoch, Instant at) throws SQLException {
        PurchaseRepository purchases = purchases(at);
        Receipt waiting = purchases.enqueue(quote(purchases, incident, payer, epoch, at));
        TransferJournal journal = new TransferJournal(database, reserve);
        Handle handle = new Handle(UUID.randomUUID(), waiting.eventId(), UUID.randomUUID(), "a".repeat(64));
        assertTrue(journal.beginDebit(handle).newlyStarted());
        double dollars = waiting.quotedCents() / 100.0;
        assertEquals(TransferJournal.State.CREDIT_READY,
                journal.recordDebit(handle, new Observation(true, true, dollars, 1000.0, 1000.0 - dollars, false)).state());
        assertTrue(journal.beginCredit(handle).newlyStarted());
        assertEquals(TransferJournal.State.PAID,
                journal.recordCredit(handle, new Observation(true, true, dollars, 2000.0, 2000.0 + dollars, false)).state());
        // Journal uses DB wall time; pin only this isolated fixture's chronology, as in IncidentRepairIT.
        LocalDateTime paid = utc(at.minusSeconds(1));
        execute("UPDATE npc_economy_events SET created_at=?,claimed_at=?,finalized_at=? WHERE event_id=?",
                paid.minusSeconds(5), paid.minusSeconds(4), paid, waiting.eventId());
        execute("UPDATE npc_purchase_intents SET created_at=? WHERE event_id=?", paid.minusSeconds(5), waiting.eventId());
        execute("UPDATE npc_transfer_attempts SET created_at=?,credit_intent_at=?,updated_at=? WHERE event_id=?",
                paid.minusSeconds(4), paid.minusSeconds(2), paid, waiting.eventId());
        execute("UPDATE npc_transfer_observations o JOIN npc_transfer_attempts a ON a.attempt_id=o.attempt_id "
                        + "SET o.observed_at=CASE WHEN o.leg='DEBIT' THEN ? ELSE ? END WHERE a.event_id=?",
                paid.minusSeconds(3), paid.minusSeconds(1), waiting.eventId());
        return waiting;
    }

    private Receipt repair(Incident incident, UUID payer, int epoch, Instant at) throws SQLException {
        Receipt paid = pay(incident, payer, epoch, at);
        Receipt applied = purchases(at).complete(paid.eventId()).orElseThrow();
        assertEquals(State.APPLIED, applied.state());
        return applied;
    }

    // ---- item-repairs: an item repair is a verified repair, bound to its APPLIED hand-in ---------------------

    @ParameterizedTest
    @CsvSource({"-1,ON_TIME,1.0", "0,ON_TIME,1.0", "1,LATE,0.0"})
    void itemRepairCountsLikeACashRepairNeverAsUnverified(long offset, String status, double rate) throws SQLException {
        Incident incident = open(owner, NOW.minus(Duration.ofDays(2)));
        UUID collection = itemRepair(incident, owner, 0, incident.due().plusMillis(offset));
        IncidentServiceAssessment result = read(owner);
        assertCase(result, incident.id(), status);
        assertEquals(collection, result.cases().getFirst().itemRepairId());
        assertNull(result.cases().getFirst().repairEventId());
        assertEquals(rate, result.onTimeRate().orElseThrow(), 0.0);
        assertEquals(0, count("npc_incident_service_receipts"));
        assertEquals(0, count("npc_economy_events"));
    }

    @Test
    void itemRepairByTheNextOwnerStaysHandoverReview() throws SQLException {
        Incident incident = open(owner, NOW.minus(Duration.ofDays(2)));
        UUID buyer = new UUID(0, 1803);
        sell(incident, owner, buyer, 1);
        itemRepair(incident, buyer, 1, incident.due());
        assertCase(read(owner), incident.id(), "HANDOVER_REVIEW");
        assertTrue(read(owner).onTimeRate().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"payer", "payer-and-player", "cash", "cash-both", "value", "state", "hash", "repaired", "property", "type"})
    void tamperedItemReceiptCannotRaiseARating(String tamper) throws Exception {
        Incident incident = open(owner, NOW.minus(Duration.ofDays(2)));
        itemRepair(incident, owner, 0, incident.due());
        assertCase(read(owner), incident.id(), "ON_TIME");
        switch (tamper) {
                case "payer" -> unchecked("UPDATE npc_incident_item_repair_receipts SET payer_uuid=?", new UUID(0, 1899));
                case "cash" -> unchecked("UPDATE npc_incident_item_repair_receipts SET cash_equivalent_cents=cash_equivalent_cents+1");
                // A consistent forgery: receipt and hand-in agree, but not with the incident or its price.
                case "payer-and-player" -> {
                    unchecked("UPDATE npc_incident_item_repair_receipts SET payer_uuid=?", new UUID(0, 1899));
                    unchecked("UPDATE npc_item_repair_collections SET player_id=?", new UUID(0, 1899));
                }
                case "cash-both" -> {
                    unchecked("UPDATE npc_incident_item_repair_receipts SET cash_equivalent_cents=cash_equivalent_cents+1");
                    unchecked("UPDATE npc_item_repair_collections SET cash_cents=cash_cents+1");
                }
                case "value" -> unchecked("UPDATE npc_incident_item_repair_receipts SET recipe_value_cents=recipe_value_cents+1");
                case "state" -> unchecked("UPDATE npc_item_repair_collections SET state='RETURN_ISSUED'");
                case "hash" -> unchecked("UPDATE npc_incident_item_repair_receipts SET repair_snapshot_sha256=REPEAT('f',64)");
                case "repaired" -> unchecked("UPDATE npc_incident_item_repair_receipts SET repaired_at=TIMESTAMPADD(SECOND,1,repaired_at)");
                case "property" -> unchecked("UPDATE npc_incident_item_repair_receipts SET property_id=?", new UUID(0, 1898));
                case "type" -> unchecked("UPDATE npc_incident_item_repair_receipts SET incident_type='PLUMBING'");
                default -> throw new AssertionError(tamper);
        }
        assertReadRejectedWithoutWrites();
    }

    @Test
    void cashAndItemReceiptsForOneIncidentThrow() throws SQLException {
        Incident items = open(owner, NOW.minus(Duration.ofDays(3)));
        itemRepair(items, owner, 0, items.due());
        Incident cash = open(owner, NOW.minus(Duration.ofDays(2)));
        repair(cash, owner, 0, cash.due());
        assertEquals(1.0, read(owner).onTimeRate().orElseThrow(), 0.0, "positive control: both repairs count");
        // Move the cash fact onto the item-repaired incident: two proofs for one incident is a broken lifecycle.
        unchecked("UPDATE npc_incident_service_receipts SET incident_id=? WHERE incident_id=?", items.id(), cash.id());
        assertReadRejectedWithoutWrites();
    }

    /** A full item repair through the repository, applied at the given time. Returns the hand-in id. */
    private UUID itemRepair(Incident incident, UUID payer, int epoch, Instant at) throws SQLException {
        var items = new com.prosperitysmp.npc.economy.ItemRepairRepository(database);
        var repair = items.currentRepair(incident.property(), payer, epoch, incident.id(), at, day(at), day(at)).orElseThrow();
        UUID collection = UUID.randomUUID();
        var quote = new com.prosperitysmp.npc.economy.ItemRepairQuote(collection, incident.property(), payer, epoch, day(at), repair,
                repair.cents(), 15_000, 3_000, 3_200, "price-v1",
                List.of(new com.prosperitysmp.npc.economy.ItemRepairQuote.Line("PAPER", 32, 100,
                        new com.prosperitysmp.npc.commerce.CustodyBytes(new byte[]{1}), 40)),
                List.of(new com.prosperitysmp.npc.economy.ItemRepairQuote.ReturnStack(0, 32,
                        new com.prosperitysmp.npc.commerce.CustodyBytes(new byte[]{2}))));
        UUID run = UUID.randomUUID();
        assertTrue(items.begin(quote, run, at, day(at)).started());
        var evidence = new com.prosperitysmp.npc.commerce.ItemCollectionEvidence(payer, collection, "a".repeat(64), "b".repeat(64),
                List.of(40), List.of(8), true, true);
        assertEquals(com.prosperitysmp.npc.economy.ItemRepairRepository.State.APPLIED,
                items.settle(collection, payer, run, evidence, true, at, day(at)).state());
        return collection;
    }

    private IncidentServiceAssessment read(UUID operator) throws SQLException {
        return new IncidentServiceRepository(database, fixed(NOW)).read(operator, FROM);
    }

    private static void assertCase(IncidentServiceAssessment result, long incidentId, String status) {
        assertEquals(1, result.cases().size());
        assertEquals(incidentId, result.cases().getFirst().incidentId());
        assertEquals(status, result.cases().getFirst().outcome().name());
    }

    private static Set<Long> ids(IncidentServiceAssessment result) {
        return result.cases().stream().map(value -> value.incidentId()).collect(Collectors.toSet());
    }

    private static Clock fixed(Instant at) { return Clock.fixed(at, ZoneOffset.UTC); }
    private static LocalDate day(Instant at) { return utc(at).toLocalDate(); }
    private static LocalDateTime utc(Instant at) { return LocalDateTime.ofInstant(at, ZoneOffset.UTC); }

    private static List<List<String>> data() throws SQLException {
        List<List<String>> result = new ArrayList<>();
        for (String table : TABLES) {
            result.add(List.of(table));
            result.addAll(rows("SELECT * FROM " + table + (table.equals("npc_item_custody_players") ? " ORDER BY 1" : " ORDER BY 1,2")));
        }
        return result;
    }

    private static long count(String table) throws SQLException {
        if (!TABLES.contains(table)) throw new IllegalArgumentException(table);
        return Long.parseLong(scalar("SELECT COUNT(*) FROM " + table));
    }

    private static String scalar(String sql, Object... values) throws SQLException {
        List<List<String>> result = rows(sql, values);
        assertEquals(1, result.size());
        return result.getFirst().getFirst();
    }

    private static Instant timestamp(String sql, Object... values) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(5);
            bind(ps, values);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                LocalDateTime at = rs.getObject(1, LocalDateTime.class);
                assertNotNull(at);
                assertFalse(rs.next());
                return at.toInstant(ZoneOffset.UTC);
            }
        }
    }

    private static List<List<String>> rows(String sql, Object... values) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(5);
            bind(ps, values);
            try (ResultSet rs = ps.executeQuery()) {
                List<List<String>> result = new ArrayList<>();
                while (rs.next()) {
                    List<String> row = new ArrayList<>();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                        Object value = rs.getObject(i);
                        row.add(value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes)
                                : value == null ? null : value.toString());
                    }
                    result.add(row);
                }
                return result;
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
            else ps.setObject(i + 1, values[i]);
        }
    }

    private static void applyResource(String path) throws Exception {
        try (InputStream in = IncidentServiceRepositoryIT.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8))) execute(sql);
        }
    }
}

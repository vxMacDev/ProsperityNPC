package com.prosperitysmp.npc.it;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.economy.PurchaseRepository;
import com.prosperitysmp.npc.economy.PurchaseRequest;
import com.prosperitysmp.npc.economy.TransferJournal;
import com.prosperitysmp.npc.economy.TransferProof.Observation;
import com.prosperitysmp.npc.formulas.IncidentType;
import com.prosperitysmp.npc.formulas.UpgradeType;
import com.prosperitysmp.npc.property.IncidentRollRepository;
import com.prosperitysmp.npc.property.IncidentRollRepository.Outcome;
import com.prosperitysmp.npc.property.IncidentRollRepository.Result;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyRepository;
import com.prosperitysmp.npc.property.Tenant;
import com.prosperitysmp.npc.property.TenantRepository;
import com.prosperitysmp.npc.property.UpgradeRepository;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentRepository;
import com.prosperitysmp.npc.sim.DailyRoll;
import com.prosperitysmp.npc.sim.SimulationLogic;
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
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/** Local embedded SQL evidence only; no settlement wiring, payments, or runtime authorization. */
@Timeout(45)
class IncidentRollIT {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 7);
    private static final Instant AT = Instant.parse("2026-09-07T09:00:00.123Z");
    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private static final long SEED = 5_000;
    private static final UUID WORLD = new UUID(0, 700);
    private static final String V7 = "/db/migration/V7__incident_rolls.sql";
    private static final List<String> UNTOUCHED = List.of("npc_transfer_observations",
            "npc_transfer_attempts", "npc_purchase_intents", "npc_economy_events",
            "npc_resident_memory", "npc_resident_profiles", "npc_tenants",
            "npc_property_ownership", "npc_property_funding", "npc_operator_daily",
            "npc_operator_reputation", "npc_audit_log");
    private static final List<String> OUTPUTS = List.of("npc_incident_rolls", "npc_incidents",
            "npc_upgrades", "npc_properties");
    private static DB mariaDb;
    private static Database database;
    private static String schemaName;
    private UUID property;
    private UUID owner;
    private IncidentRollRepository rolls;
    private List<List<String>> untouchedBefore;
    private boolean paidRepairFixture;

    @BeforeAll
    static void bootDatabase() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        try {
            mariaDb.start();
            schemaName = "npc_roll_" + UUID.randomUUID().toString().replace("-", "");
            mariaDb.createDB(schemaName);
            database = new Database();
            database.connect(new DatabaseSettings("localhost", cfg.getPort(), schemaName, "root", "", 6, 10_000L));
            for (String file : List.of("V1__initial_schema.sql", "V2__resident_identity.sql",
                    "V3__purchase_receipts.sql", "V4__transfer_journal.sql", "V5__resident_route_tags.sql",
                    "V6__incident_repairs.sql", "V7__incident_rolls.sql", "V8__eviction_tenancies.sql",
                    "V12__incident_service_receipts.sql", "V23__property_insurance.sql")) applyResource("/db/migration/" + file);
            // V40: the refusal columns the transfer and commerce readers expect, on the tables this schema has.
            try (var v40 = database.getConnection()) { com.prosperitysmp.npc.db.V40Fixture.apply(v40); }
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
    void freshProperty() throws SQLException {
        untouchedBefore = null;
        paidRepairFixture = false;
        dropFaultTriggers();
        execute("DELETE FROM npc_incident_service_receipts");
        for (String table : UNTOUCHED) execute("DELETE FROM " + table);
        for (String table : OUTPUTS) execute("DELETE FROM " + table);
        owner = new UUID(0, 901);
        property = findProperty(id -> simulation(SEED).incidentForDay(id, DAY.toString(), 80)
                .orElse(null) == IncidentType.PESTS);
        enroll(property, 80);
        addUpgrade(UpgradeType.ADVERTISING, 1);
        addUpgrade(UpgradeType.FACILITIES, 1);
        rolls = repository(SEED, AT);
        untouchedBefore = tableRows(UNTOUCHED);
    }

    @AfterEach
    void verifyUnrelatedStateAndCleanup() throws SQLException {
        try {
            if (untouchedBefore != null) {
                assertEquals(untouchedBefore, tableRows(UNTOUCHED),
                        "rolling must not create/edit cash, funding, event, tenant, memory, or audit rows");
                for (String table : List.of("npc_economy_events", "npc_purchase_intents",
                        "npc_transfer_attempts", "npc_transfer_observations", "npc_resident_memory")) {
                    long expected = paidRepairFixture ? (table.equals("npc_transfer_observations") ? 2 : 1) : 0;
                    assertEquals(expected, count(table), table + " count unchanged by rolling");
                }
                assertArrayEquals(new long[]{5_000, paidRepairFixture ? 2_321 : 321, 123},
                        new PropertyRepository(database).fundingOf(property));
            }
        } finally {
            dropFaultTriggers();
        }
    }

    @ParameterizedTest
    @CsvSource({"PESTS,5,2000", "PLUMBING,8,3500", "BREAK_IN,12,5000"})
    void canonicalIncidentAtomicallyDamagesAndDisablesOnlyAdvertising(
            IncidentType type, int damage, long cents) throws SQLException {
        selectProperty(id -> simulation(SEED).incidentForDay(id, DAY.toString(), 80).orElse(null) == type, 80);
        Result result = roll();
        assertTrue(result.newlyRecorded());
        assertEquals(Outcome.INCIDENT, result.outcome());
        assertNotNull(result.incidentId());
        assertEquals(80 - damage, result.conditionAfter());
        assertEquals(Integer.toString(80 - damage), scalar("SELECT condition_score FROM npc_properties"));
        assertEquals(type.name(), scalar("SELECT incident_type FROM npc_incidents"));
        assertEquals(Integer.toString(damage), scalar("SELECT condition_damage FROM npc_incidents"));
        assertEquals(Long.toString(cents), scalar("SELECT cash_repair_cents FROM npc_incidents"));
        assertEquals(type.itemRepairSpec(), scalar("SELECT item_repair_spec FROM npc_incidents"));
        assertEquals("ADVERTISING", scalar("SELECT affected_upgrade_type FROM npc_incidents"));
        assertEquals(result.incidentId().toString(), disabledBy("ADVERTISING"));
        assertNull(disabledBy("FACILITIES"));
        UpgradeRepository upgrades = new UpgradeRepository(database);
        assertEquals(1, upgrades.levelOf(property, "ADVERTISING"));
        assertEquals(0, upgrades.effectiveLevel(property, UpgradeType.ADVERTISING));
        assertEquals(1, upgrades.effectiveLevel(property, UpgradeType.FACILITIES));
        assertEquals("2", scalar("SELECT SUM(level) FROM npc_upgrades"));
        assertEquals("0", scalar("SELECT prevented FROM npc_incidents"));
        assertNull(scalar("SELECT resolved_at FROM npc_incidents"));
        assertNull(scalar("SELECT resolver_uuid FROM npc_incidents"));
        assertEquals(DAY.toString(), scalar("SELECT economy_day FROM npc_incidents"));
        assertReceipt(Outcome.INCIDENT, result.incidentId());
        assertEquals(1, count("npc_incidents"));
    }

    @ParameterizedTest
    @EnumSource(IncidentType.class)
    void damageClampsAtZero(IncidentType type) throws SQLException {
        selectProperty(id -> simulation(SEED).incidentForDay(id, DAY.toString(), 2).orElse(null) == type, 2);
        assertEquals(0, roll().conditionAfter());
        assertEquals("0", scalar("SELECT condition_score FROM npc_properties"));
        assertEquals(Integer.toString(type.conditionDamage()), scalar("SELECT condition_damage FROM npc_incidents"));
    }

    @Test
    void repeatAndReconstructedRepositoryReplayWithoutAnyWrites() throws SQLException {
        Result first = roll();
        List<List<String>> before = tableRows(OUTPUTS);
        rolls = repository(SEED, AT.plusSeconds(600));
        Result replay = roll();
        assertFalse(replay.newlyRecorded());
        assertEquals(first.outcome(), replay.outcome());
        assertEquals(first.incidentId(), replay.incidentId());
        assertEquals(first.conditionAfter(), replay.conditionAfter());
        assertEquals(before, tableRows(OUTPUTS));
    }

    @Test
    void replayReportsCurrentConditionNotHistoricalDamageResult() throws SQLException {
        Result first = roll();
        execute("UPDATE npc_properties SET condition_score=97 WHERE property_id=?", property);
        List<List<String>> before = tableRows(OUTPUTS);
        Result replay = roll();
        assertFalse(replay.newlyRecorded());
        assertEquals(first.incidentId(), replay.incidentId());
        assertEquals(97, replay.conditionAfter());
        assertEquals(before, tableRows(OUTPUTS));
    }

    @Test
    void noIncidentIsDurableAndReplayDoesNotTouchUpgradesOrCondition() throws SQLException {
        selectProperty(id -> simulation(SEED).incidentForDay(id, DAY.toString(), 80).isEmpty(), 80);
        List<List<String>> before = tableRows(List.of("npc_properties", "npc_upgrades"));
        Result first = roll();
        assertTrue(first.newlyRecorded());
        assertEquals(Outcome.NONE, first.outcome());
        assertNull(first.incidentId());
        assertEquals(80, first.conditionAfter());
        assertReceipt(Outcome.NONE, null);
        assertEquals(0, count("npc_incidents"));
        assertEquals(before, tableRows(List.of("npc_properties", "npc_upgrades")));
        List<List<String>> recorded = tableRows(OUTPUTS);
        assertFalse(roll().newlyRecorded());
        assertEquals(recorded, tableRows(OUTPUTS));
    }

    @Test
    void noneReceiptPreventsRerollAfterConditionAndSeedChange() throws SQLException {
        selectProperty(id -> simulation(SEED).incidentForDay(id, DAY.toString(), 100).isEmpty()
                && simulation(SEED + 1).incidentForDay(id, DAY.toString(), 0).isPresent(), 100);
        assertEquals(Outcome.NONE, roll().outcome());
        execute("UPDATE npc_properties SET condition_score=0 WHERE property_id=?", property);
        rolls = repository(SEED + 1, AT);
        List<List<String>> before = tableRows(OUTPUTS);
        Result replay = roll();
        assertFalse(replay.newlyRecorded());
        assertEquals(Outcome.NONE, replay.outcome());
        assertEquals(0, replay.conditionAfter());
        assertNull(replay.incidentId());
        assertEquals(before, tableRows(OUTPUTS));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 1, -30, 30})
    void anyNonCurrentDayRejectsWithoutWrites(int offset) throws SQLException {
        List<List<String>> before = tableRows(OUTPUTS);
        assertThrows(SQLException.class, () -> rolls.roll(property, owner, 0, DAY.plusDays(offset)));
        assertEquals(before, tableRows(OUTPUTS));
    }

    @ParameterizedTest
    @CsvSource({"2026-09-07T08:59:59.999Z,2026-09-06", "2026-09-07T09:00:00Z,2026-09-07",
            "2026-09-08T02:00:00Z,2026-09-07", "2026-03-08T08:59:59.999Z,2026-03-07",
            "2026-03-08T09:00:00Z,2026-03-08", "2026-11-01T09:59:59.999Z,2026-10-31",
            "2026-11-01T10:00:00Z,2026-11-01"})
    void chicagoResetUsesInjectedInstantIncludingDst(String instant, String today) throws SQLException {
        LocalDate expected = LocalDate.parse(today);
        // Deliberately unrelated clock zone: the constructor's Chicago zone controls the economy day.
        rolls = new IncidentRollRepository(database, simulation(SEED),
                Clock.fixed(Instant.parse(instant), ZoneId.of("Asia/Tokyo")), CHICAGO, 4);
        assertEquals(expected, rolls.today());
        assertThrows(SQLException.class, () -> rolls.roll(property, owner, 0, expected.minusDays(1)));
        assertThrows(SQLException.class, () -> rolls.roll(property, owner, 0, expected.plusDays(1)));
        assertTrue(rolls.roll(property, owner, 0, expected).newlyRecorded());
        assertEquals(expected.toString(), scalar("SELECT economy_day FROM npc_incident_rolls"));
    }

    @Test
    void clockPolicyChangesOnlyWithReconstruction() throws SQLException {
        Clock atFive = Clock.fixed(Instant.parse("2026-09-07T10:00:00Z"), ZoneOffset.UTC);
        IncidentRollRepository original = new IncidentRollRepository(database, simulation(SEED), atFive, CHICAGO, 4);
        IncidentRollRepository replacement = new IncidentRollRepository(database, simulation(SEED), atFive, CHICAGO, 6);
        assertEquals(DAY, original.today());
        assertEquals(DAY.minusDays(1), replacement.today());
        assertThrows(SQLException.class, () -> original.roll(property, owner, 0, replacement.today()));
        assertTrue(original.roll(property, owner, 0, original.today()).newlyRecorded());
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "null-owner", "epoch", "disabled", "missing"})
    void invalidPropertyScopeRejectsFreshRoll(String mutation) throws SQLException {
        mutateScope(mutation);
        List<List<String>> before = tableRows(OUTPUTS);
        UUID target = mutation.equals("missing") ? new UUID(0, -1) : property;
        assertThrows(SQLException.class, () -> rolls.roll(target, owner, 0, DAY));
        assertEquals(before, tableRows(OUTPUTS));
    }

    @ParameterizedTest
    @ValueSource(strings = {"owner", "null-owner", "epoch", "disabled"})
    void replayStillRequiresValidCurrentScope(String mutation) throws SQLException {
        roll();
        mutateScope(mutation);
        List<List<String>> before = tableRows(OUTPUTS);
        assertThrows(SQLException.class, this::roll);
        assertEquals(before, tableRows(OUTPUTS));
    }

    @ParameterizedTest
    @CsvSource({"-1,false", "101,false", "-1,true", "101,true"})
    void invalidStoredConditionRejectsFreshAndReplay(int condition, boolean replay) throws SQLException {
        if (replay) roll();
        // Corrupt only this session's fixture, restoring its constraint checks before returning it to the pool.
        try (Connection c = database.getConnection()) {
            try {
                try (PreparedStatement ps = c.prepareStatement("SET SESSION check_constraint_checks=OFF")) { ps.execute(); }
                try (PreparedStatement ps = c.prepareStatement("UPDATE npc_properties SET condition_score=? WHERE property_id=?")) {
                    bind(ps, condition, property);
                    assertEquals(1, ps.executeUpdate());
                }
            } finally {
                try (PreparedStatement ps = c.prepareStatement("SET SESSION check_constraint_checks=ON")) { ps.execute(); }
            }
        }
        List<List<String>> before = tableRows(OUTPUTS);
        assertThrows(SQLException.class, this::roll);
        assertEquals(before, tableRows(OUTPUTS));
    }

    @ParameterizedTest
    @ValueSource(strings = {"open", "resolved", "prevented"})
    void legacySameDayIncidentWithoutReceiptFailsClosed(String state) throws SQLException {
        legacyIncident(DAY, state);
        List<List<String>> before = tableRows(OUTPUTS);
        assertThrows(SQLException.class, this::roll);
        assertEquals(before, tableRows(OUTPUTS), "must neither invent nor adopt a daily receipt");
        assertEquals(0, count("npc_incident_rolls"));
    }

    @Test
    void previousOpenIncidentRecordsDurableBlockedOutcomeEvenAfterRepair() throws SQLException {
        legacyIncident(DAY.minusDays(1), "open");
        List<List<String>> before = tableRows(List.of("npc_properties", "npc_incidents", "npc_upgrades"));
        Result blocked = roll();
        assertTrue(blocked.newlyRecorded());
        assertEquals(Outcome.BLOCKED_OPEN, blocked.outcome());
        assertEquals(80, blocked.conditionAfter());
        assertEquals(Long.valueOf(scalar("SELECT incident_id FROM npc_incidents")), blocked.incidentId());
        assertEquals(before, tableRows(List.of("npc_properties", "npc_incidents", "npc_upgrades")));
        assertEquals("BLOCKED_OPEN", scalar("SELECT outcome FROM npc_incident_rolls"));
        repairFixture(91);
        List<List<String>> repaired = tableRows(OUTPUTS);
        Result replay = roll();
        assertFalse(replay.newlyRecorded());
        assertEquals(Outcome.BLOCKED_OPEN, replay.outcome());
        assertEquals(blocked.incidentId(), replay.incidentId());
        assertEquals(91, replay.conditionAfter());
        assertEquals(repaired, tableRows(OUTPUTS));
        assertEquals(1, count("npc_incidents"));
        assertEquals(1, count("npc_incident_rolls"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"resolved", "prevented"})
    void priorClosedIncidentDoesNotBlockToday(String state) throws SQLException {
        legacyIncident(DAY.minusDays(1), state);
        assertEquals(Outcome.INCIDENT, roll().outcome());
        assertEquals(2, count("npc_incidents"));
        assertEquals("1", scalar("SELECT COUNT(*) FROM npc_incidents WHERE resolved_at IS NULL AND prevented=FALSE"));
    }

    @Test
    void multipleLegacyOpenIncidentsRejectWithoutInventingBlockedReceipt() throws SQLException {
        legacyIncident(DAY.minusDays(2), "open");
        legacyIncident(DAY.minusDays(1), "open");
        List<List<String>> before = tableRows(OUTPUTS);
        assertThrows(SQLException.class, this::roll);
        assertEquals(before, tableRows(OUTPUTS));
        assertEquals(0, count("npc_incident_rolls"));
    }

    @Test
    void sameDayRepairCannotCauseSecondIncidentOrDisableAnotherUpgrade() throws SQLException {
        Result first = roll();
        repairFixture(100);
        List<List<String>> before = tableRows(OUTPUTS);
        Result replay = roll();
        assertFalse(replay.newlyRecorded());
        assertEquals(Outcome.INCIDENT, replay.outcome());
        assertEquals(first.incidentId(), replay.incidentId());
        assertEquals(100, replay.conditionAfter());
        assertEquals(before, tableRows(OUTPUTS));
        assertEquals(1, count("npc_incidents"));
        assertNull(disabledBy("ADVERTISING"));
        assertNull(disabledBy("FACILITIES"));
    }

    @Test
    void generatedIncidentQuotesAndRepairsThroughBothRealJournalProofs() throws SQLException {
        UUID resident = new UUID(0, 777);
        UUID reserve = new UUID(0, 778);
        ResidentRepository residents = new ResidentRepository(database);
        assertTrue(residents.register(ResidentCatalogue.draft(resident, "Mara Vale")));
        new TenantRepository(database).insert(new Tenant(resident, "Mara Vale", property, 0,
                "STANDARD", "GENERIC", 50));
        untouchedBefore = tableRows(UNTOUCHED);
        Result generated = roll();
        assertEquals(Outcome.INCIDENT, generated.outcome());
        assertEquals(untouchedBefore, tableRows(UNTOUCHED), "generation itself creates no financial or memory rows");
        UpgradeRepository upgrades = new UpgradeRepository(database);
        assertEquals(1, upgrades.levelOf(property, "ADVERTISING"));
        assertEquals(0, upgrades.effectiveLevel(property, UpgradeType.ADVERTISING));
        assertEquals(1, upgrades.effectiveLevel(property, UpgradeType.FACILITIES));
        Instant repairAt = AT.plusSeconds(600);
        PurchaseRepository purchases = new PurchaseRepository(database, reserve,
                Clock.fixed(repairAt, ZoneOffset.UTC), CHICAGO, 4);
        List<List<String>> generatedRows = tableRows(OUTPUTS);
        PurchaseRequest quote = purchases.quoteRepair(UUID.randomUUID(), property, owner, 0,
                generated.incidentId(), DAY);
        assertEquals(generated.incidentId().longValue(), quote.repair().incidentId());
        assertEquals(IncidentType.PESTS, quote.repair().type());
        assertEquals(2_000, quote.cents());
        assertEquals(generatedRows, tableRows(OUTPUTS), "quote must not mutate the generated incident");
        assertEquals(untouchedBefore, tableRows(UNTOUCHED));

        PurchaseRepository.Receipt waiting = purchases.enqueue(quote);
        assertEquals(PurchaseRepository.State.WAITING, purchases.complete(waiting.eventId()).orElseThrow().state());
        TransferJournal journal = new TransferJournal(database, reserve);
        TransferJournal.Handle handle = new TransferJournal.Handle(UUID.randomUUID(), waiting.eventId(),
                UUID.randomUUID(), "a".repeat(64));
        assertTrue(journal.beginDebit(handle).newlyStarted());
        assertEquals(TransferJournal.State.CREDIT_READY,
                journal.recordDebit(handle, new Observation(true, true, 20.0, 1_000.0, 980.0, false)).state());
        assertEquals(PurchaseRepository.State.WAITING, purchases.complete(waiting.eventId()).orElseThrow().state());
        assertEquals(generatedRows, tableRows(OUTPUTS), "one transfer leg cannot repair the incident");
        assertTrue(journal.beginCredit(handle).newlyStarted());
        assertEquals(TransferJournal.State.PAID,
                journal.recordCredit(handle, new Observation(true, true, 20.0, 2_000.0, 2_020.0, false)).state());
        pinPaymentTime(waiting.eventId(), repairAt.minusSeconds(120));
        List<List<String>> paidLedger = tableRows(List.of("npc_economy_events", "npc_transfer_attempts",
                "npc_transfer_observations", "npc_property_funding"));
        PurchaseRepository.Receipt applied = purchases.complete(waiting.eventId()).orElseThrow();
        assertEquals(PurchaseRepository.State.APPLIED, applied.state());
        assertEquals(paidLedger, tableRows(List.of("npc_economy_events", "npc_transfer_attempts",
                "npc_transfer_observations", "npc_property_funding")));
        assertEquals("80", scalar("SELECT condition_score FROM npc_properties"));
        assertEquals(HexFormat.of().formatHex(Uuids.toBytes(owner)),
                scalar("SELECT HEX(resolver_uuid) FROM npc_incidents").toLowerCase(Locale.ROOT));
        assertEquals(LocalDateTime.ofInstant(repairAt, ZoneOffset.UTC),
                LocalDateTime.parse(scalar("SELECT CAST(resolved_at AS CHAR) FROM npc_incidents").replace(' ', 'T')));
        assertNull(disabledBy("ADVERTISING"));
        assertEquals(1, upgrades.levelOf(property, "ADVERTISING"));
        assertEquals(1, upgrades.effectiveLevel(property, UpgradeType.ADVERTISING));
        assertEquals(1, upgrades.effectiveLevel(property, UpgradeType.FACILITIES));
        assertEquals("2", scalar("SELECT SUM(level) FROM npc_upgrades"));
        assertEquals(1, count("npc_resident_memory"));
        assertEquals("REPAIR_COMPLETED", scalar("SELECT kind FROM npc_resident_memory"));
        assertEquals("repair:" + generated.incidentId() + ":event:" + waiting.eventId(),
                scalar("SELECT source_ref FROM npc_resident_memory"));
        assertArrayEquals(new long[]{5_000, 2_321, 123}, new PropertyRepository(database).fundingOf(property));

        paidRepairFixture = true;
        untouchedBefore = tableRows(UNTOUCHED);
        List<List<String>> repairedRows = tableRows(OUTPUTS);
        rolls = repository(SEED + 1, repairAt.plusSeconds(60));
        Result replay = roll();
        assertFalse(replay.newlyRecorded());
        assertEquals(Outcome.INCIDENT, replay.outcome());
        assertEquals(generated.incidentId(), replay.incidentId());
        assertEquals(80, replay.conditionAfter());
        assertEquals(applied, purchases.complete(waiting.eventId()).orElseThrow());
        assertEquals(applied, purchases.enqueue(quote));
        assertEquals(repairedRows, tableRows(OUTPUTS));
        assertEquals(untouchedBefore, tableRows(UNTOUCHED), "repair and roll replay cannot pay or remember twice");
        assertEquals(1, count("npc_incidents"));
        assertEquals(1, count("npc_incident_rolls"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "zero"})
    void facilitiesSelectedWhenAdvertisingIsNotActive(String advertising) throws SQLException {
        if (advertising.equals("absent")) execute("DELETE FROM npc_upgrades WHERE upgrade_type='ADVERTISING'");
        else execute("UPDATE npc_upgrades SET level=0 WHERE upgrade_type='ADVERTISING'");
        Result result = roll();
        assertEquals("FACILITIES", scalar("SELECT affected_upgrade_type FROM npc_incidents"));
        assertEquals(result.incidentId().toString(), disabledBy("FACILITIES"));
        assertEquals("1", scalar("SELECT COUNT(*) FROM npc_upgrades WHERE disabled_incident_id IS NOT NULL"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "zero"})
    void noActiveUpgradeStillCreatesIncidentWithNullAffectedUpgrade(String upgrades) throws SQLException {
        if (upgrades.equals("absent")) execute("DELETE FROM npc_upgrades");
        else execute("UPDATE npc_upgrades SET level=0");
        Result result = roll();
        assertEquals(Outcome.INCIDENT, result.outcome());
        assertEquals(75, result.conditionAfter());
        assertNull(scalar("SELECT affected_upgrade_type FROM npc_incidents"));
        assertEquals("0", scalar("SELECT COUNT(*) FROM npc_upgrades WHERE disabled_incident_id IS NOT NULL"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADVERTISING", "FACILITIES"})
    void selectedUpgradeWithStaleDisableReferenceRejectsAtomically(String upgrade) throws SQLException {
        if (upgrade.equals("FACILITIES")) execute("UPDATE npc_upgrades SET level=0 WHERE upgrade_type='ADVERTISING'");
        execute("UPDATE npc_upgrades SET disabled_incident_id=987654321 WHERE upgrade_type=?", upgrade);
        List<List<String>> before = tableRows(OUTPUTS);
        assertThrows(SQLException.class, this::roll);
        assertEquals(before, tableRows(OUTPUTS));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2026-09-07T09:00:00.001Z", "2026-09-07T09:00:00.123Z",
            "2026-03-07T10:00:00.010Z", "2026-10-31T09:00:00.070Z"})
    void createdAndDueAreInjectedUtcInstantsExactlyTwentyFourHoursApart(String timestamp) throws SQLException {
        Instant at = Instant.parse(timestamp);
        LocalDate day = at.atZone(CHICAGO).toLocalDate();
        selectProperty(id -> simulation(SEED).incidentForDay(id, day.toString(), 80).isPresent(), 80);
        Result result = repository(SEED, at).roll(property, owner, 0, day);
        assertEquals(Outcome.INCIDENT, result.outcome());
        assertEquals(LocalDateTime.ofInstant(at, ZoneOffset.UTC),
                LocalDateTime.parse(scalar("SELECT CAST(recorded_at AS CHAR) FROM npc_incident_rolls").replace(' ', 'T')));
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT created_at,due_at FROM npc_incidents WHERE incident_id=?")) {
            ps.setQueryTimeout(3);
            bind(ps, result.incidentId());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(LocalDateTime.ofInstant(at, ZoneOffset.UTC), rs.getObject("created_at", LocalDateTime.class));
                assertEquals(LocalDateTime.ofInstant(at.plusSeconds(86_400), ZoneOffset.UTC),
                        rs.getObject("due_at", LocalDateTime.class));
                assertFalse(rs.next());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"npc_incidents", "npc_incident_rolls"})
    void faultAfterEachInsertRollsBackAllOutputsThenRetryRecordsExactlyOnce(String table) throws SQLException {
        List<List<String>> before = tableRows(OUTPUTS);
        execute("CREATE TRIGGER roll_insert_failure AFTER INSERT ON " + table
                + " FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected roll insert failure'");
        SQLException failure = assertThrows(SQLException.class, this::roll);
        assertTrue(failure.getMessage().contains("injected roll insert failure"), failure.getMessage());
        assertEquals(before, tableRows(OUTPUTS), "incident, damage, upgrade disable and receipt all roll back");
        dropFaultTriggers();
        Result retry = roll();
        assertTrue(retry.newlyRecorded());
        assertEquals(75, retry.conditionAfter());
        assertEquals(retry.incidentId().toString(), disabledBy("ADVERTISING"));
        List<List<String>> committed = tableRows(OUTPUTS);
        assertFalse(roll().newlyRecorded());
        assertEquals(committed, tableRows(OUTPUTS));
        assertEquals(1, count("npc_incident_rolls"));
        assertEquals(1, count("npc_incidents"));
    }

    @Test
    void concurrentSamePropertyWaitsOnRealLockAndRecordsOnce() throws Exception {
        List<Result> results = behindPropertyLock(this::roll,
                () -> repository(SEED, AT).roll(property, owner, 0, DAY));
        assertEquals(1, results.stream().filter(Result::newlyRecorded).count());
        assertEquals(results.get(0).incidentId(), results.get(1).incidentId());
        for (Result result : results) {
            assertEquals(Outcome.INCIDENT, result.outcome());
            assertEquals(75, result.conditionAfter());
        }
        assertEquals(1, count("npc_incidents"));
        assertEquals(1, count("npc_incident_rolls"));
        assertEquals("75", scalar("SELECT condition_score FROM npc_properties"));
        assertEquals("1", scalar("SELECT COUNT(*) FROM npc_upgrades WHERE disabled_incident_id IS NOT NULL"));
    }

    @Test
    void concurrentDifferentDayRequestRejectsWhileTodayWaitsForProperty() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Result> current;
            Future<SQLException> wrongDay;
            try (Connection blocker = lockProperty()) {
                try {
                    current = workers.submit(this::roll);
                    wrongDay = workers.submit(() -> assertThrows(SQLException.class,
                            () -> rolls.roll(property, owner, 0, DAY.plusDays(1))));
                    // The landed contract validates the day after acquiring the property lock.
                    assertLockingWaiters(2);
                    assertFalse(current.isDone());
                    assertFalse(wrongDay.isDone());
                } finally {
                    blocker.rollback();
                }
            }
            assertNotNull(wrongDay.get(15, TimeUnit.SECONDS));
            assertTrue(current.get(15, TimeUnit.SECONDS).newlyRecorded());
            assertEquals(1, count("npc_incident_rolls"));
            assertEquals(DAY.toString(), scalar("SELECT economy_day FROM npc_incident_rolls"));
            assertEquals(1, count("npc_incidents"));
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void repeatMigrationPreservesReceiptsAndPropertyDayUniqueness() throws Exception {
        roll();
        List<List<String>> before = tableRows(OUTPUTS);
        List<List<String>> schema = rows("SHOW CREATE TABLE npc_incident_rolls");
        applyResource(V7);
        applyResource(V7);
        assertEquals(schema, rows("SHOW CREATE TABLE npc_incident_rolls"));
        assertEquals(before, tableRows(OUTPUTS));
        assertFalse(roll().newlyRecorded());
        // Reinsert every persisted column, so this tests the actual migration's uniqueness contract.
        assertThrows(SQLException.class,
                () -> execute("INSERT INTO npc_incident_rolls SELECT * FROM npc_incident_rolls"));
        assertEquals(before, tableRows(OUTPUTS));
    }

    private Result roll() throws SQLException {
        return rolls.roll(property, owner, 0, DAY);
    }

    private static SimulationLogic simulation(long seed) {
        return new SimulationLogic(new DailyRoll(seed));
    }

    private static IncidentRollRepository repository(long seed, Instant at) {
        return new IncidentRollRepository(database, simulation(seed), Clock.fixed(at, ZoneOffset.UTC), CHICAGO, 4);
    }

    private static UUID findProperty(Predicate<UUID> wanted) {
        for (long n = 1; n <= 100_000; n++) {
            UUID id = new UUID(0x524f4c4cL, n);
            if (wanted.test(id)) return id;
        }
        throw new AssertionError("No deterministic fixture UUID found within 100000 candidates");
    }

    private void selectProperty(Predicate<UUID> wanted, int condition) throws SQLException {
        for (String table : OUTPUTS) execute("DELETE FROM " + table);
        property = findProperty(wanted);
        enroll(property, condition);
        addUpgrade(UpgradeType.ADVERTISING, 1);
        addUpgrade(UpgradeType.FACILITIES, 1);
        untouchedBefore = tableRows(UNTOUCHED);
    }

    private void enroll(UUID id, int condition) throws SQLException {
        new PropertyRepository(database).insertEnrollment(new Property(id, WORLD, "roll-" + id,
                "test", "SMALL", 2, 1_000, 1_000, owner, 0, condition, true), 5_000);
        execute("UPDATE npc_property_funding SET successful_cash_sinks_cents=321,successful_cash_payouts_cents=123 WHERE property_id=?", id);
    }

    private void addUpgrade(UpgradeType type, int level) throws SQLException {
        new UpgradeRepository(database).setLevel(property, type.name(), level, type.costToLevelCents(level));
        execute("UPDATE npc_upgrades SET installed_at=? WHERE property_id=? AND upgrade_type=?",
                LocalDateTime.ofInstant(AT.minusSeconds(86_400), ZoneOffset.UTC), property, type.name());
    }

    private static void pinPaymentTime(long eventId, Instant at) throws SQLException {
        // The journal deliberately uses DB time; align only fixture ledger chronology to the injected clock.
        LocalDateTime paid = LocalDateTime.ofInstant(at, ZoneOffset.UTC);
        execute("UPDATE npc_economy_events SET created_at=?,claimed_at=?,finalized_at=? WHERE event_id=?",
                paid.minusSeconds(5), paid.minusSeconds(4), paid, eventId);
        execute("UPDATE npc_purchase_intents SET created_at=? WHERE event_id=?", paid.minusSeconds(5), eventId);
        execute("UPDATE npc_transfer_attempts SET created_at=?,credit_intent_at=?,updated_at=? WHERE event_id=?",
                paid.minusSeconds(4), paid.minusSeconds(2), paid, eventId);
        execute("UPDATE npc_transfer_observations o JOIN npc_transfer_attempts a ON a.attempt_id=o.attempt_id "
                        + "SET o.observed_at=CASE WHEN o.leg='DEBIT' THEN ? ELSE ? END WHERE a.event_id=?",
                paid.minusSeconds(3), paid.minusSeconds(1), eventId);
    }

    private void mutateScope(String mutation) throws SQLException {
        switch (mutation) {
            case "owner" -> execute("UPDATE npc_properties SET owner_uuid=? WHERE property_id=?", new UUID(0, 902), property);
            case "null-owner" -> execute("UPDATE npc_properties SET owner_uuid=NULL WHERE property_id=?", property);
            case "epoch" -> execute("UPDATE npc_properties SET ownership_epoch=1 WHERE property_id=?", property);
            case "disabled" -> execute("UPDATE npc_properties SET enabled=FALSE WHERE property_id=?", property);
            case "missing" -> { }
            default -> throw new AssertionError(mutation);
        }
    }

    private void legacyIncident(LocalDate day, String state) throws SQLException {
        execute("""
                INSERT INTO npc_incidents
                (property_id,incident_type,cash_repair_cents,item_repair_spec,condition_damage,
                 affected_upgrade_type,economy_day,created_at,due_at,resolved_at,prevented)
                VALUES (?,'PESTS',2000,?,5,NULL,?,?,?,?,?)
                """, property, IncidentType.PESTS.itemRepairSpec(), day.toString(),
                LocalDateTime.ofInstant(AT.minusSeconds(86_400), ZoneOffset.UTC),
                LocalDateTime.ofInstant(AT.plusSeconds(86_400), ZoneOffset.UTC),
                state.equals("open") ? null : LocalDateTime.ofInstant(AT.minusSeconds(1), ZoneOffset.UTC),
                state.equals("prevented"));
    }

    private void repairFixture(int condition) throws SQLException {
        // Model a completed repair's nonfinancial state only; paid repair authorization has its own IT suite.
        execute("UPDATE npc_incidents SET resolved_at=?,resolver_uuid=? WHERE property_id=?",
                LocalDateTime.ofInstant(AT.plusSeconds(60), ZoneOffset.UTC), owner, property);
        execute("UPDATE npc_upgrades SET disabled_incident_id=NULL WHERE property_id=?", property);
        execute("UPDATE npc_properties SET condition_score=? WHERE property_id=?", condition, property);
        rolls = repository(SEED, AT.plusSeconds(120));
    }

    private void assertReceipt(Outcome outcome, Long incidentId) throws SQLException {
        assertEquals(1, count("npc_incident_rolls"));
        assertEquals(outcome.name(), scalar("SELECT outcome FROM npc_incident_rolls"));
        assertEquals(DAY.toString(), scalar("SELECT economy_day FROM npc_incident_rolls"));
        assertEquals("0", scalar("SELECT ownership_epoch FROM npc_incident_rolls"));
        assertEquals(HexFormat.of().formatHex(Uuids.toBytes(owner)),
                scalar("SELECT HEX(owner_uuid) FROM npc_incident_rolls").toLowerCase(Locale.ROOT));
        assertEquals(incidentId == null ? null : incidentId.toString(), scalar("SELECT incident_id FROM npc_incident_rolls"));
        assertEquals(HexFormat.of().formatHex(Uuids.toBytes(property)), scalar("SELECT HEX(property_id) FROM npc_incident_rolls").toLowerCase(Locale.ROOT));
    }

    private static String disabledBy(String upgrade) throws SQLException {
        return scalar("SELECT disabled_incident_id FROM npc_upgrades WHERE upgrade_type=?", upgrade);
    }

    private List<Result> behindPropertyLock(Callable<Result> left, Callable<Result> right) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Result> first;
            Future<Result> second;
            try (Connection blocker = lockProperty()) {
                try {
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
            assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS), "roll workers terminated");
        }
    }

    private Connection lockProperty() throws SQLException {
        Connection c = database.getConnection();
        try {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("SELECT property_id FROM npc_properties WHERE property_id=? FOR UPDATE")) {
                ps.setQueryTimeout(3);
                bind(ps, property);
                try (ResultSet rs = ps.executeQuery()) { assertTrue(rs.next()); }
            }
            return c;
        } catch (SQLException | AssertionError failure) {
            try { c.rollback(); } catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
            try { c.close(); } catch (SQLException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
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
                    assertTrue(sql.matches("(?s)select\\s+.*\\s+for\\s+update\\s*"),
                            "must wait on initial property locking read: " + sql);
                }
                return;
            }
            Thread.yield();
        }
        throw new AssertionError("expected " + expected + " property locking readers, saw " + queries);
    }

    private static void dropFaultTriggers() throws SQLException {
        if (database != null && database.isConnected()) execute("DROP TRIGGER IF EXISTS roll_insert_failure");
    }

    private static List<List<String>> tableRows(List<String> tables) throws SQLException {
        List<List<String>> result = new ArrayList<>();
        for (String table : tables) {
            result.add(List.of(table));
            List<List<String>> data = rows("SELECT * FROM " + table);
            data.sort(java.util.Comparator.comparing(Object::toString));
            result.addAll(data);
        }
        return result;
    }

    private static long count(String table) throws SQLException {
        if (!UNTOUCHED.contains(table) && !OUTPUTS.contains(table)) throw new IllegalArgumentException(table);
        return Long.parseLong(scalar("SELECT COUNT(*) FROM " + table));
    }

    private static String scalar(String sql, Object... values) throws SQLException {
        List<List<String>> result = rows(sql, values);
        assertEquals(1, result.size(), sql);
        return result.getFirst().getFirst();
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
        try (InputStream in = IncidentRollIT.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8))) execute(sql);
        }
    }
}

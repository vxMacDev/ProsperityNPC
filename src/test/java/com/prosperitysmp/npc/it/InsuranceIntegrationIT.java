package com.prosperitysmp.npc.it;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.economy.EconomyEventRepository;
import com.prosperitysmp.npc.economy.PurchaseRepository;
import com.prosperitysmp.npc.economy.PurchaseRequest;
import com.prosperitysmp.npc.economy.TransferJournal;
import com.prosperitysmp.npc.economy.TransferProof.Observation;
import com.prosperitysmp.npc.property.IncidentRollRepository;
import com.prosperitysmp.npc.property.InsuranceRepository;
import com.prosperitysmp.npc.property.OwnerDeskRepository;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyRepository;
import com.prosperitysmp.npc.sim.DailyRoll;
import com.prosperitysmp.npc.sim.SimulationLogic;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Real local SQL composition; no Vault calls or live client evidence. */
@Timeout(45)
class InsuranceIntegrationIT {
    private static final Instant AT = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private static final LocalDate DAY = AT.atZone(ZoneOffset.UTC).toLocalDate();
    private static final UUID WORLD = new UUID(0, 2300), RESERVE = new UUID(0, 2301), OWNER = new UUID(0, 2302);
    private static final SimulationLogic SIM = new SimulationLogic(new DailyRoll(5000));
    private static DB maria;
    private static Database database;
    private Property property;
    private PurchaseRepository purchases;
    private final InsuranceRepository insurance = new InsuranceRepository();

    @BeforeAll static void start() throws Exception {
        var cfg = DBConfigurationBuilder.newBuilder().setPort(0);
        maria = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build()); maria.start();
        String schema = "npc_insurance_integration_" + UUID.randomUUID().toString().replace("-", "");
        maria.createDB(schema);
        database = new Database();
        database.connect(new DatabaseSettings("localhost", cfg.getPort(), schema, "root", "", 4, 10000));
        for (String file : List.of("V1__initial_schema.sql", "V2__resident_identity.sql", "V3__purchase_receipts.sql",
                "V4__transfer_journal.sql", "V6__incident_repairs.sql", "V7__incident_rolls.sql",
                "V8__eviction_tenancies.sql", "V17__transfer_resolutions.sql", "V23__property_insurance.sql")) {
            try (var in = InsuranceIntegrationIT.class.getResourceAsStream("/db/migration/" + file)) {
                assertNotNull(in, file);
                for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8)))
                    execute(sql);
            }
        }
        // V40: the refusal columns the transfer and commerce readers expect, on the tables this schema has.
        try (var v40 = database.getConnection()) { com.prosperitysmp.npc.db.V40Fixture.apply(v40); }
    }
    @AfterAll static void stop() throws Exception {
        try { if (database != null) database.close(); } finally { if (maria != null) maria.stop(); }
    }
    @BeforeEach void fixture() throws SQLException {
        execute("DROP TRIGGER IF EXISTS insurance_apply_fault");
        for (String table : List.of("npc_insurance_preventions", "npc_incident_rolls", "npc_property_insurance",
                "npc_transfer_resolutions", "npc_transfer_observations", "npc_transfer_attempts", "npc_purchase_intents",
                "npc_economy_events", "npc_upgrades", "npc_incidents", "npc_property_ownership",
                "npc_property_funding", "npc_properties")) execute("DELETE FROM " + table);
        UUID id = null;
        for (int i = 1; i <= 100000; i++) {
            UUID candidate = new UUID(23, i);
            if (SIM.incidentForDay(candidate, DAY.plusDays(1).toString(), 80).isPresent()
                    && SIM.incidentForDay(candidate, DAY.plusDays(2).toString(), 80).isPresent()) { id = candidate; break; }
        }
        assertNotNull(id, "finite deterministic fixture search");
        property = new Property(id, WORLD, "insurance-fixture", "fixture", "room", 2, 1000, 1000, OWNER, 0, 80, true);
        new PropertyRepository(database).insertEnrollment(property, 5000);
        purchases = new PurchaseRepository(database, RESERVE, Clock.systemUTC(), ZoneOffset.UTC, 0);
    }

    @Test void actualTwoLegProofAppliesImmutableFutureTermWithPurchaseReceipt() throws Exception {
        var quote = quote(); var waiting = purchases.enqueue(quote, 1600, 1600);
        assertEquals(PurchaseRepository.State.WAITING, purchases.complete(waiting.eventId()).orElseThrow().state());
        assertEquals(0, count("npc_property_insurance"));
        var journal = new TransferJournal(database, RESERVE);
        var handle = new TransferJournal.Handle(UUID.randomUUID(), waiting.eventId(), UUID.randomUUID(), "a".repeat(64));
        assertTrue(journal.beginDebit(handle).newlyStarted());
        assertEquals(TransferJournal.State.CREDIT_READY, journal.recordDebit(handle,
                new Observation(true, true, 16.0, 1000.0, 984.0, false)).state());
        assertEquals(PurchaseRepository.State.WAITING, purchases.complete(waiting.eventId()).orElseThrow().state());
        assertEquals(0, count("npc_property_insurance"));
        assertTrue(journal.beginCredit(handle).newlyStarted());
        assertEquals(TransferJournal.State.PAID, journal.recordCredit(handle,
                new Observation(true, true, 16.0, 2000.0, 2016.0, false)).state());
        var applied = purchases.complete(waiting.eventId()).orElseThrow();
        assertEquals(PurchaseRepository.State.APPLIED, applied.state());
        var term = term(waiting.eventId());
        assertEquals(DAY.plusDays(1), term.startDay()); assertEquals(DAY.plusDays(8), term.endDayExclusive());
        assertEquals(1600, term.priceCents()); assertEquals(OWNER, term.ownerUuid());
        assertEquals(applied, purchases(AT.plusSeconds(86400)).complete(waiting.eventId()).orElseThrow());
        assertEquals(applied, purchases.enqueue(quote)); assertEquals(term, term(waiting.eventId()));
        assertEquals(1, count("npc_property_insurance")); assertEquals(1, count("npc_purchase_intents"));
    }

    @Test void paidLabelWithoutConfirmedObservationsCannotGrantCoverage() throws Exception {
        var waiting = purchases.enqueue(quote());
        var events = new EconomyEventRepository(database);
        assertTrue(events.claim(waiting.eventId())); events.finalizePaid(waiting.eventId(), 1600);
        var result = purchases.complete(waiting.eventId()).orElseThrow();
        assertEquals(PurchaseRepository.State.REVIEW_REQUIRED, result.state());
        assertEquals("TRANSFER_PROOF_MISSING", result.outcome()); assertEquals(0, count("npc_property_insurance"));
    }

    @Test void coverageAndAppliedIntentRollbackTogetherBeforeSafeBenefitReplay() throws Exception {
        var waiting = paid();
        execute("CREATE TRIGGER insurance_apply_fault BEFORE UPDATE ON npc_purchase_intents FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture failure'");
        try { assertThrows(SQLException.class, () -> purchases.complete(waiting.eventId())); }
        finally { execute("DROP TRIGGER insurance_apply_fault"); }
        assertEquals(0, count("npc_property_insurance"));
        assertEquals(PurchaseRepository.State.WAITING, purchases.findByRequest(waiting.request().requestId()).orElseThrow().state());
        assertEquals(TransferJournal.State.PAID, new TransferJournal(database, RESERVE).find(waiting.eventId()).orElseThrow().state());
        assertEquals(PurchaseRepository.State.APPLIED, purchases.complete(waiting.eventId()).orElseThrow().state());
        assertEquals(1, count("npc_property_insurance"));
    }

    @Test void firstFutureIncidentPreventsDamageExactlyOnceAndDeskReportsUsedTerm() throws Exception {
        var waiting = paid(); purchases.complete(waiting.eventId()).orElseThrow();
        execute("INSERT INTO npc_upgrades(property_id,upgrade_type,level,purchase_cost_cents) VALUES(?,'FACILITIES',1,15000)", property.propertyId());
        var first = rolls(1).roll(property.propertyId(), OWNER, 0, DAY.plusDays(1));
        assertEquals(IncidentRollRepository.Outcome.PREVENTED, first.outcome()); assertEquals(80, first.conditionAfter());
        assertEquals(1, count("npc_insurance_preventions"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM npc_upgrades WHERE disabled_incident_id IS NOT NULL"));
        assertEquals(1, scalar("SELECT prevented FROM npc_incidents WHERE incident_id=?", first.incidentId()));
        var replay = rolls(1).roll(property.propertyId(), OWNER, 0, DAY.plusDays(1));
        assertFalse(replay.newlyRecorded()); assertEquals(first.incidentId(), replay.incidentId());
        assertEquals(1, count("npc_insurance_preventions"));
        var desk = new OwnerDeskRepository(database).read(WORLD, property.regionId(), OWNER, DAY.plusDays(1)).orElseThrow();
        assertFalse(term(waiting.eventId()).appliedAt().isAfter(desk.capturedAt()), "desk snapshot must follow the real application");
        assertTrue(desk.insurance().used()); assertFalse(desk.insurance().canPurchase()); assertTrue(desk.issues().isEmpty());
        var second = rolls(2).roll(property.propertyId(), OWNER, 0, DAY.plusDays(2));
        assertEquals(IncidentRollRepository.Outcome.INCIDENT, second.outcome()); assertTrue(second.conditionAfter() < 80);
        assertEquals(1, count("npc_insurance_preventions"));
    }

    @Test void existingIncidentAndNewOwnerCannotAcquireRetroactiveOrTransferredCoverage() throws Exception {
        execute("INSERT INTO npc_incidents(property_id,incident_type,cash_repair_cents,item_repair_spec,condition_damage,economy_day,created_at,due_at,prevented) "
                + "VALUES(?,'PESTS',2000,'',5,?,UTC_TIMESTAMP(3),DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY),FALSE)", property.propertyId(), DAY.toString());
        var waiting = paid(); purchases.complete(waiting.eventId()).orElseThrow();
        var blocked = rolls(1).roll(property.propertyId(), OWNER, 0, DAY.plusDays(1));
        assertEquals(IncidentRollRepository.Outcome.BLOCKED_OPEN, blocked.outcome()); assertEquals(0, count("npc_insurance_preventions"));
        UUID nextOwner = new UUID(0, 2399);
        new PropertyRepository(database).applyOwnerChange(property.propertyId(), OWNER, nextOwner, 1, "insurance-test");
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            assertFalse(insurance.isCovered(c, property.withOwner(nextOwner, 1), AT.plusSeconds(86400), DAY.plusDays(1)));
            assertTrue(insurance.canPurchase(c, property.propertyId(), DAY.plusDays(1))); c.rollback();
        }
        var desk = new OwnerDeskRepository(database).read(WORLD, property.regionId(), nextOwner, DAY.plusDays(1)).orElseThrow();
        assertTrue(desk.insurance().currentIncarnation()); assertTrue(desk.insurance().canPurchase());
        assertNull(desk.insurance().startDay()); assertFalse(desk.insurance().used());
        var historical = term(waiting.eventId()); assertEquals(OWNER, historical.ownerUuid());
        var buyerQuote = purchases.quoteInsurance(UUID.randomUUID(), property.propertyId(), nextOwner, 1, DAY);
        var buyerWaiting = paid(buyerQuote);
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            assertFalse(insurance.isCovered(c, property.withOwner(nextOwner, 1), AT.plusSeconds(86400), DAY.plusDays(1)));
            c.rollback();
        }
        assertEquals(PurchaseRepository.State.APPLIED, purchases.complete(buyerWaiting.eventId()).orElseThrow().state());
        var buyerTerm = term(buyerWaiting.eventId());
        assertEquals(nextOwner, buyerTerm.ownerUuid()); assertEquals(1, buyerTerm.ownershipEpoch());
        assertEquals(DAY.plusDays(1), buyerTerm.startDay());
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            assertTrue(insurance.isCovered(c, property.withOwner(nextOwner, 1), AT.plusSeconds(86400), DAY.plusDays(1)));
            c.rollback();
        }
        assertEquals(historical, term(waiting.eventId()));
        assertEquals(0, scalar("SELECT prevented FROM npc_incidents WHERE property_id=?", property.propertyId()));
        assertEquals(0, count("npc_insurance_preventions")); assertEquals(2, count("npc_property_insurance"));
    }

    @Test void staleCapacityOrConsentMaximumRefusesBeforeAnyPaymentIntent() throws Exception {
        var quote = quote();
        assertThrows(PurchaseRepository.MaximumExceededException.class, () -> purchases.enqueue(quote, 1599, 5000));
        execute("UPDATE npc_properties SET capacity=3 WHERE property_id=?", property.propertyId());
        var refused = assertThrows(PurchaseRepository.TargetUnavailableException.class, () -> purchases.enqueue(quote));
        assertEquals(0, refused.getSuppressed().length); assertEquals(0, count("npc_purchase_intents"));
        assertEquals(0, count("npc_economy_events")); assertEquals(0, count("npc_property_insurance"));
    }

    private PurchaseRequest quote() throws SQLException {
        return purchases.quoteInsurance(UUID.randomUUID(), property.propertyId(), OWNER, 0, DAY);
    }
    private PurchaseRepository.Receipt paid() throws SQLException {
        return paid(quote());
    }
    private PurchaseRepository.Receipt paid(PurchaseRequest quote) throws SQLException {
        var waiting = purchases.enqueue(quote); var journal = new TransferJournal(database, RESERVE);
        var handle = new TransferJournal.Handle(UUID.randomUUID(), waiting.eventId(), UUID.randomUUID(), "a".repeat(64));
        assertTrue(journal.beginDebit(handle).newlyStarted());
        assertEquals(TransferJournal.State.CREDIT_READY, journal.recordDebit(handle,
                new Observation(true, true, 16.0, 1000.0, 984.0, false)).state());
        assertTrue(journal.beginCredit(handle).newlyStarted());
        assertEquals(TransferJournal.State.PAID, journal.recordCredit(handle,
                new Observation(true, true, 16.0, 2000.0, 2016.0, false)).state());
        return waiting;
    }
    private static PurchaseRepository purchases(Instant at) {
        return new PurchaseRepository(database, RESERVE, Clock.fixed(at, ZoneOffset.UTC), ZoneOffset.UTC, 0);
    }
    private static IncidentRollRepository rolls(int days) {
        return new IncidentRollRepository(database, SIM, Clock.fixed(AT.plusSeconds(86400L * days), ZoneOffset.UTC), ZoneOffset.UTC, 0);
    }
    private InsuranceRepository.CoverageReceipt term(long event) throws SQLException {
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false); var term = insurance.find(c, event).orElseThrow(); c.rollback(); return term;
        }
    }
    private static long count(String table) throws SQLException { return scalar("SELECT COUNT(*) FROM " + table); }
    private static long scalar(String sql, Object... values) throws SQLException {
        try (Connection c = database.getConnection(); var ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(5); bind(ps, values);
            try (var rs = ps.executeQuery()) { assertTrue(rs.next()); return rs.getLong(1); }
        }
    }
    private static void execute(String sql, Object... values) throws SQLException {
        try (Connection c = database.getConnection(); var ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(5); bind(ps, values); ps.execute();
        }
    }
    private static void bind(PreparedStatement ps, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) {
            if (values[i] instanceof UUID id) ps.setBytes(i + 1, Uuids.toBytes(id)); else ps.setObject(i + 1, values[i]);
        }
    }
}

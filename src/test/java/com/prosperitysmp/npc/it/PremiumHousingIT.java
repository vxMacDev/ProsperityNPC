package com.prosperitysmp.npc.it;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.config.EconomyLimits;
import com.prosperitysmp.npc.db.*;
import com.prosperitysmp.npc.economy.*;
import com.prosperitysmp.npc.formulas.*;
import com.prosperitysmp.npc.market.*;
import com.prosperitysmp.npc.property.*;
import com.prosperitysmp.npc.resident.*;
import com.prosperitysmp.npc.sim.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real isolated MariaDB and real producers; transfer observations are fixture acknowledgments, not live Vault calls. */
class PremiumHousingIT {
    private static DB server;
    private static Database db;
    private static final Instant NOW = Instant.parse("2026-09-30T17:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("America/Chicago");
    private static final UUID OWNER = new UUID(0, 54001), WORLD = new UUID(0, 54002), RESERVE = new UUID(0, 54003);
    private static final HousingMoveRequest.Policy POLICY = new HousingMoveRequest.Policy(128, 128, 4, 7);
    private static final HousingPolicy STANDARD = new HousingPolicy(WORLD, "downtown", "room", HousingPolicy.Tier.STANDARD, 2000, 40);
    private static final HousingPolicy PREMIUM = new HousingPolicy(WORLD, "downtown", "room", HousingPolicy.Tier.PREMIUM, 2000, 80);

    @BeforeAll static void start() throws Exception {
        var config = DBConfigurationBuilder.newBuilder(); config.setPort(0);
        server = DB.newEmbeddedDB(config.addArg("--bind-address=127.0.0.1").build()); server.start();
        String schema = "npc_premium_" + UUID.randomUUID().toString().replace("-", ""); server.createDB(schema);
        db = new Database(); db.connect(new DatabaseSettings("localhost", config.getPort(), schema, "root", "", 6, 10000));
        var migrations = MigrationRunner.class.getDeclaredField("MIGRATIONS"); migrations.setAccessible(true);
        for (Object migration : (List<?>) migrations.get(null)) {
            var resource = migration.getClass().getDeclaredMethod("resource"); resource.setAccessible(true);
            try (var in = PremiumHousingIT.class.getResourceAsStream("/" + resource.invoke(migration))) {
                assertNotNull(in);
                for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8))) execute(sql);
            }
        }
    }
    @AfterAll static void stop() throws Exception { try { if (db != null) db.close(); } finally { if (server != null) server.stop(); } }

    @Test void fullReceiptBackedPremiumLoopRetainsHistoryAndCapsAndCannotReplay() throws Exception {
        Instant original = NOW.minus(Duration.ofDays(24));
        UUID historyHome = UUID.randomUUID(), historyResident = UUID.randomUUID();
        home(historyHome, original.minusSeconds(1), 1000);
        resident(historyResident, STANDARD, original);
        var coldDesk = new OwnerDeskRepository(db, fixed(original), ZONE, 4)
                .read(WORLD, "premium-" + historyHome, OWNER, day(original)).orElseThrow();
        assertEquals(HousingDecisionPlan.RankStatus.UNRATED_HELD, coldDesk.housing().orElseThrow().rating().status());
        assertTrue(coldDesk.housing().orElseThrow().rating().planningOperatorScore().isEmpty());
        var historical = moves(original, false);
        historical.apply(historical.quote(UUID.randomUUID(), historyResident, historyHome, 0, day(original)), POLICY);
        for (int i = 23; i >= 9; i--) {
            Instant issued = NOW.minus(Duration.ofDays(i));
            var bill = new UtilityRepository(db, RESERVE, fixed(issued), ZONE, 4)
                    .preparePayment(UUID.randomUUID(), historyHome, OWNER, 0, day(issued)).receipt();
            pay(bill.eventId(), bill.bill().cents(), issued.plusSeconds(1));
            assertEquals(UtilityRepository.State.PAID, new UtilityRepository(db, RESERVE, fixed(issued.plusSeconds(2)), ZONE, 4)
                    .reconcile(bill.eventId()).orElseThrow().state());
        }
        repairHistory(NOW.minus(Duration.ofDays(20)));
        UUID source = UUID.randomUUID(), target = UUID.randomUUID(), premiumResident = UUID.randomUUID();
        Instant admissionTime = NOW.minus(Duration.ofDays(8));
        home(source, admissionTime.minusSeconds(1), 1000); home(target, admissionTime.minusSeconds(1), 1250);
        resident(premiumResident, PREMIUM, admissionTime);
        var admissionWriter = moves(admissionTime, true);
        var quote = admissionWriter.quote(UUID.randomUUID(), premiumResident, source, 0, day(admissionTime));
        var admission = admissionWriter.apply(quote, POLICY).receipt();
        assertEquals(HousingPolicy.Tier.PREMIUM, admission.request().tier());
        assertEquals(admission, admissionWriter.find(quote.requestId()).orElseThrow());
        assertFalse(admissionWriter.apply(quote, POLICY).newlyApplied());
        assertTrue(scalar("SELECT OCTET_LENGTH(premium_evidence) FROM npc_housing_moves WHERE request_id=?", quote.requestId()) > 0);

        execute("UPDATE npc_properties SET condition_score=60,row_version=row_version+1 WHERE property_id=?", source);
        execute("UPDATE npc_upgrades SET level=0,purchase_cost_cents=0,installed_at=NULL WHERE property_id=? AND upgrade_type='FACILITIES'", source);
        execute("UPDATE npc_properties SET enabled=FALSE WHERE property_id NOT IN (?,?)", source, target);
        var options = new HousingDecisionRepository.Options(NOW.minus(Duration.ofDays(29)), 7, POLICY,
                HousingDecisionPlan.ColdStartPolicy.PROVISIONAL_50_V1, 512, 4096, Duration.ofSeconds(20), true);
        var decisions = new HousingDecisionRepository(db, fixed(NOW), ZONE, 4);
        var plan = decisions.read(day(NOW), options);
        var relocation = plan.requests().stream().filter(r -> r.residentId().equals(premiumResident)).findFirst().orElseThrow();
        assertEquals(source, relocation.origin().propertyId()); assertEquals(target, relocation.propertyId());
        var writer = moves(NOW, true);
        execute("UPDATE npc_properties SET row_version=row_version+1 WHERE property_id=?", source);
        assertThrows(SQLException.class, () -> writer.apply(relocation, POLICY), "stale source authority must refuse the entire move");
        assertEquals(0, scalar("SELECT COUNT(*) FROM npc_tenancy_departures WHERE resident_id=?", premiumResident));
        var refreshed = decisions.read(day(NOW), options).requests().stream()
                .filter(r -> r.residentId().equals(premiumResident)).findFirst().orElseThrow();
        execute("CREATE TRIGGER premium_insert_fault BEFORE INSERT ON npc_housing_moves FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test rollback'");
        assertThrows(SQLException.class, () -> writer.apply(refreshed, POLICY));
        assertEquals(0, scalar("SELECT COUNT(*) FROM npc_tenancy_departures WHERE resident_id=?", premiumResident));
        assertEquals(1, scalar("SELECT COUNT(*) FROM npc_tenants WHERE tenant_id=? AND property_id=?", premiumResident, source));
        execute("DROP TRIGGER premium_insert_fault");
        var moved = writer.apply(refreshed, POLICY);
        assertTrue(moved.newlyApplied()); assertEquals(admission.tenancyRevisionAfter() + 2, moved.receipt().tenancyRevisionAfter());
        assertFalse(writer.apply(refreshed, POLICY).newlyApplied());
        assertEquals(1, scalar("SELECT COUNT(*) FROM npc_tenancy_departures WHERE resident_id=?", premiumResident));
        assertEquals(1, scalar("SELECT COUNT(*) FROM npc_tenants WHERE tenant_id=? AND property_id=?", premiumResident, target));
        var retention = new RetentionRepository(db, fixed(NOW), ZONE, 4).read(OWNER, NOW.minus(Duration.ofDays(29)), 7);
        assertEquals(0, retention.count(RetentionAssessment.Outcome.EVIDENCE_GAP));
        assertEquals(2, retention.count(RetentionAssessment.Outcome.RETAINED));
        assertTrue(decisions.read(day(NOW), options).requests().isEmpty(), "same-day move must not churn again");

        var desk = new OwnerDeskRepository(db, fixed(NOW), ZONE, 4);
        var shown = desk.read(WORLD, "premium-" + target, OWNER, day(NOW)).orElseThrow();
        var view = shown.housing().orElseThrow();
        var services = new ServiceAssessmentRepository(db, fixed(NOW), ZONE, 4)
                .read(OWNER, NOW.minus(Duration.ofDays(29)), 7);
        var targetFacts = decisions.read(day(NOW), options).snapshot().homes().stream()
                .filter(h -> h.propertyId().equals(target)).findFirst().orElseThrow();
        assertEquals(PremiumHousingEligibility.evaluate(targetFacts, services, ZONE, 4), view.premium());
        assertEquals(HousingDecisionPlan.RankStatus.RATED, view.rating().status());
        assertEquals(services.operatorScore(), view.rating().planningOperatorScore());
        // Appreciation reads the same rated score inside its own reservation transaction.
        var ratedHome = new PropertyRepository(db).findById(target).orElseThrow();
        try (var c = db.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); c.setAutoCommit(false);
            try {
                var appreciationScore = new RentReservationRepository(db, RESERVE, fixed(NOW), ZONE, 4, true)
                        .ratedScore().score(c, ratedHome, NOW);
                assertEquals(view.rating().rankingScore(), appreciationScore);
                assertTrue(appreciationScore.orElseThrow() > 70.0);
                assertTrue(com.prosperitysmp.npc.formulas.PropertyFormulas.appreciationCents(appreciationScore.orElseThrow(), 300) > 0);
            } finally { c.rollback(); }
        }
        assertEquals(NOW, shown.capturedAt());
        assertTrue(view.premium().eligible());
        assertTrue(desk.read(WORLD, "premium-" + target, UUID.randomUUID(), day(NOW)).isEmpty(),
                "private service evidence cannot be fetched for a different owner");
        assertEquals(1, scalar("SELECT COUNT(*) FROM npc_tenancy_departures WHERE resident_id=?", premiumResident));

        var utility = new UtilityRepository(db, RESERVE, fixed(NOW), ZONE, 4);
        var currentBill = utility.preparePayment(UUID.randomUUID(), target, OWNER, 0, day(NOW)).receipt();
        pay(currentBill.eventId(), currentBill.bill().cents(), NOW.plusSeconds(1));
        var rentAt = NOW.plusSeconds(2);
        assertEquals(UtilityRepository.State.PAID, new UtilityRepository(db, RESERVE, fixed(rentAt), ZONE, 4)
                .reconcile(currentBill.eventId()).orElseThrow().state());
        var actualHome = new PropertyRepository(db).findById(target).orElseThrow();
        var limits = limits(800, 900, 1000, 1100);
        var heldRent = new RentReservationRepository(db, RESERVE, fixed(rentAt), ZONE, 4, false);
        assertThrows(SQLException.class, () -> heldRent.reserve(day(NOW), List.of(actualHome), limits));
        assertEquals(0, scalar("SELECT COUNT(*) FROM npc_rent_reservations WHERE property_id=?", target));
        var rents = new RentReservationRepository(db, RESERVE, fixed(rentAt), ZONE, 4, true);
        var reserved = rents.reserve(day(NOW), List.of(actualHome), limits);
        assertEquals(1, reserved.size());
        assertEquals(800, reserved.getFirst().cents(), "premium multiplier must remain inside every cash envelope");
        assertEquals(reserved, rents.pending(day(NOW), List.of(actualHome)));
        assertTrue(rents.reserve(day(NOW), List.of(actualHome), limits).isEmpty());
        assertEquals(1, scalar("SELECT COUNT(*) FROM npc_rent_cohort WHERE resident_id=? AND economy_day=?", premiumResident, day(NOW).toString()));
        assertEquals(1, scalar("SELECT COUNT(*) FROM npc_rent_reservations WHERE property_id=?", target));
        // Decoder binding catches altered bytes even when the database CHECK was disabled by a privileged actor.
        try (var c = db.getConnection(); var st = c.createStatement()) {
            st.execute("SET SESSION check_constraint_checks=OFF");
            try (var ps = c.prepareStatement("UPDATE npc_housing_moves SET premium_evidence=CONCAT(premium_evidence,'x') WHERE request_id=?")) {
                ps.setBytes(1, Uuids.toBytes(refreshed.requestId())); ps.executeUpdate();
            }
        }
        assertThrows(SQLException.class, () -> writer.find(refreshed.requestId()));
    }

    @Test void explicitPolicyChangeCannotRewriteOccupiedResidentOrResetCooldown() throws Exception {
        Instant at = NOW.minus(Duration.ofDays(2)); UUID property = UUID.randomUUID(), person = UUID.randomUUID();
        home(property, at.minusSeconds(1), 1000); resident(person, STANDARD, at);
        var writer = moves(at, false); writer.apply(writer.quote(UUID.randomUUID(), person, property, 0, day(at)), POLICY);
        var registry = new HousingRegistry(db);
        assertThrows(SQLException.class, () -> registry.setVacantPolicy(person, 0, PREMIUM));
        assertEquals(STANDARD, registry.find(person).orElseThrow().policy());
        assertThrows(SQLException.class, () -> moves(at, true).quote(UUID.randomUUID(), person, property, 1, day(at)));
    }

    private static void home(UUID property, Instant at, long asking) throws Exception {
        new PropertyRepository(db).insertEnrollment(new Property(property, WORLD, "premium-" + property, "downtown", "room",
                4, 1000, Math.toIntExact(asking), OWNER, 0, 100, true), 5000);
        execute("UPDATE npc_property_ownership SET observed_at=? WHERE property_id=?", at, property);
        execute("INSERT INTO npc_upgrades(property_id,upgrade_type,level,purchase_cost_cents,installed_at) VALUES (?,'FACILITIES',3,70000,?)", property, at);
    }
    private static void resident(UUID person, HousingPolicy policy, Instant at) throws Exception {
        new ResidentRepository(db).register(ResidentCatalogue.draft(person, "Premium fixture"));
        new HousingRegistry(db).admit(person, policy, day(at));
    }
    private static void repairHistory(Instant at) throws Exception {
        var logic = new SimulationLogic(new DailyRoll(5000)); UUID property = null;
        for (int i = 1; i <= 100000; i++) {
            var id = new UUID(54004, i);
            if (logic.incidentForDay(id, day(at).toString(), 100).orElse(null) == IncidentType.PESTS) { property = id; break; }
        }
        assertNotNull(property); home(property, at.minusSeconds(1), 1000);
        var rolled = new IncidentRollRepository(db, logic, fixed(at), ZONE, 4).roll(property, OWNER, 0, day(at));
        var purchases = new PurchaseRepository(db, RESERVE, fixed(at.plusSeconds(5)), ZONE, 4);
        var request = purchases.quoteRepair(UUID.randomUUID(), property, OWNER, 0, rolled.incidentId(), day(at));
        var waiting = purchases.enqueue(request); pay(waiting.eventId(), waiting.quotedCents(), at.plusSeconds(4));
        execute("UPDATE npc_purchase_intents SET created_at=? WHERE event_id=?", at.plusSeconds(1), waiting.eventId());
        assertEquals(PurchaseRepository.State.APPLIED, purchases.complete(waiting.eventId()).orElseThrow().state());
    }
    private static void pay(long event, long cents, Instant at) throws Exception {
        var journal = new TransferJournal(db, RESERVE);
        var handle = new TransferJournal.Handle(UUID.randomUUID(), event, UUID.randomUUID(), "a".repeat(64));
        double amount = cents / 100.0;
        assertTrue(journal.beginDebit(handle).newlyStarted());
        journal.recordDebit(handle, new TransferProof.Observation(true, true, amount, 1000.0, 1000.0 - amount, false));
        assertTrue(journal.beginCredit(handle).newlyStarted());
        assertEquals(TransferJournal.State.PAID, journal.recordCredit(handle,
                new TransferProof.Observation(true, true, amount, 2000.0, 2000.0 + amount, false)).state());
        execute("UPDATE npc_economy_events SET created_at=?,claimed_at=?,finalized_at=? WHERE event_id=?", at.minusMillis(5), at.minusMillis(4), at, event);
        execute("UPDATE npc_transfer_attempts SET created_at=?,credit_intent_at=?,updated_at=? WHERE event_id=?", at.minusMillis(4), at.minusMillis(2), at, event);
        execute("UPDATE npc_transfer_observations o JOIN npc_transfer_attempts a ON a.attempt_id=o.attempt_id SET o.observed_at=CASE WHEN o.leg='DEBIT' THEN ? ELSE ? END WHERE a.event_id=?",
                at.minusMillis(3), at.minusMillis(1), event);
    }
    private static HousingMoveRepository moves(Instant at, boolean enabled) { return new HousingMoveRepository(db, fixed(at), ZONE, 4, enabled); }
    private static EconomyLimits limits(long server, long owner, long property, long tenant) throws Exception {
        var constructor = EconomyLimits.class.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        return (EconomyLimits) constructor.newInstance(server, owner, property, tenant, 0L, 3500000L, 5000L, 50000L, 0.9);
    }
    private static Clock fixed(Instant at) { return Clock.fixed(at, ZoneOffset.UTC); }
    private static LocalDate day(Instant at) { return at.atZone(ZONE).minusHours(4).toLocalDate(); }
    private static long scalar(String sql, Object... args) throws Exception {
        try (var c = db.getConnection(); var ps = prepare(c, sql, args); var rs = ps.executeQuery()) { assertTrue(rs.next()); return rs.getLong(1); }
    }
    private static void execute(String sql, Object... args) throws Exception {
        try (var c = db.getConnection(); var ps = prepare(c, sql, args)) { ps.execute(); }
    }
    private static PreparedStatement prepare(Connection c, String sql, Object... args) throws SQLException {
        var ps = c.prepareStatement(sql); ps.setQueryTimeout(10);
        for (int i = 0; i < args.length; i++) {
            Object value = args[i];
            if (value instanceof UUID id) ps.setBytes(i + 1, Uuids.toBytes(id));
            else if (value instanceof Instant at) ps.setObject(i + 1, LocalDateTime.ofInstant(at, ZoneOffset.UTC));
            else ps.setObject(i + 1, value);
        }
        return ps;
    }
}

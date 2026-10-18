package com.prosperitysmp.npc.it;

import com.prosperitysmp.npc.commerce.CustodyBytes;
import com.prosperitysmp.npc.commerce.ItemCollectionEvidence;
import com.prosperitysmp.npc.economy.ItemRepairQuote;
import com.prosperitysmp.npc.economy.ItemRepairRecipes;
import com.prosperitysmp.npc.economy.ItemRepairRepository;
import com.prosperitysmp.npc.economy.PurchaseRepository;
import com.prosperitysmp.npc.economy.RepairPrices;
import com.prosperitysmp.npc.economy.TransferDispatchFixture;
import com.prosperitysmp.npc.economy.TransferJournal;
import com.prosperitysmp.npc.formulas.IncidentType;
import com.prosperitysmp.npc.property.AutoCareRepository;
import com.prosperitysmp.npc.property.IncidentRollRepository;
import com.prosperitysmp.npc.property.IncidentServiceAssessment;
import com.prosperitysmp.npc.property.IncidentServiceRepository;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyRepository;
import com.prosperitysmp.npc.sim.DailyRoll;
import com.prosperitysmp.npc.sim.SimulationLogic;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

/** Actual incident generation through stored-price readers; payment and item evidence are fixtures. */
@Timeout(45)
class RepairPriceGenerationIT extends ContractItBase {
    private static final UUID OWNER = new UUID(0, 5702), RESERVE = new UUID(0, 5703);
    private static final SimulationLogic SIM = new SimulationLogic(new DailyRoll(5000));
    private static final RepairPrices CUSTOM = new RepairPrices(3500, 2399, 5000);
    private static final RepairPrices CHANGED = new RepairPrices(9000, 10000, 11000);

    @ParameterizedTest
    @EnumSource(IncidentType.class)
    void generatedPriceIsFrozenAcrossReplayWhileNewHomesUseNewSettings(IncidentType type) throws SQLException {
        UUID first = enroll(type, DAY, 1);
        var generated = rolls(CUSTOM, NOW).roll(first, OWNER, 0, DAY);
        assertTrue(generated.newlyRecorded());
        assertEquals(IncidentRollRepository.Outcome.INCIDENT, generated.outcome());
        assertPrice(generated.incidentId(), type, CUSTOM);
        var before = data();
        var replay = rolls(CHANGED, NOW.plusSeconds(60)).roll(first, OWNER, 0, DAY);
        assertFalse(replay.newlyRecorded());
        assertEquals(generated.incidentId(), replay.incidentId());
        assertEquals(before, data());
        UUID second = enroll(type, DAY, 1000001);
        var next = rolls(CHANGED, NOW.plusSeconds(60)).roll(second, OWNER, 0, DAY);
        assertTrue(next.newlyRecorded());
        assertPrice(next.incidentId(), type, CHANGED);
        assertPrice(generated.incidentId(), type, CUSTOM);
    }

    @ParameterizedTest
    @EnumSource(IncidentType.class)
    void generatedCashRepairFlowsThroughConsentPaymentAndHistoricalService(IncidentType type) throws Exception {
        UUID property = enroll(type, DAY, 1);
        var generated = rolls(CUSTOM, NOW).roll(property, OWNER, 0, DAY);
        long cash = CUSTOM.cents(type);
        Instant completed = NOW.plusSeconds(3600);
        var care = new AutoCareRepository(database, RESERVE, Clock.fixed(completed, ZoneOffset.UTC), ZONE, 0);
        care.configure(property, OWNER, 0, true, cash, Set.of(AutoCareRepository.Action.CASH_REPAIR));
        var claim = care.reserve(property, OWNER, 0, DAY, AutoCareRepository.Action.CASH_REPAIR, generated.incidentId(), cash).claim();
        var purchases = purchases(completed);
        var quote = purchases.quoteRepair(claim.claimId(), property, OWNER, 0, generated.incidentId(), DAY);
        assertEquals(cash, quote.cents());
        var before = data();
        assertThrows(PurchaseRepository.MaximumExceededException.class, () -> purchases.enqueue(quote, cash - 1));
        assertEquals(before, data());
        var receipt = purchases.enqueue(quote, cash);
        assertTrue(care.bind(claim.claimId(), receipt.eventId()).newlyBound());
        pay(receipt.eventId(), completed);
        assertEquals(PurchaseRepository.State.APPLIED, purchases.complete(receipt.eventId()).orElseThrow().state());
        assertEquals(AutoCareRepository.State.SPENT, care.reconcile(claim.claimId()).state());
        assertEquals("80", scalar("SELECT condition_score FROM npc_properties WHERE property_id=?", property));
        assertEquals(Long.toString(cash), scalar("SELECT successful_cash_sinks_cents FROM npc_property_funding WHERE property_id=?", property));
        assertEquals(IncidentServiceAssessment.Outcome.ON_TIME, service().cases().getFirst().outcome());
        before = data();
        assertFalse(rolls(CHANGED, completed).roll(property, OWNER, 0, DAY).newlyRecorded());
        assertEquals(before, data());
        assertEquals(receipt.request(), purchases.findByRequest(claim.claimId()).orElseThrow().request());
        assertPrice(generated.incidentId(), type, CUSTOM);
    }
    @ParameterizedTest
    @EnumSource(IncidentType.class)
    void generatedMaterialRepairUsesTheSameCashFloorAndServicePrice(IncidentType type) throws SQLException {
        UUID property = enroll(type, DAY, 1);
        var generated = rolls(CUSTOM, NOW).roll(property, OWNER, 0, DAY);
        var repository = new ItemRepairRepository(database);
        Instant completed = NOW.plusSeconds(3600);
        var repair = repository.currentRepair(property, OWNER, 0, generated.incidentId(), completed, DAY, DAY).orElseThrow();
        assertEquals(CUSTOM.cents(type), repair.cents());
        long required = ItemRepairRecipes.requiredCents(repair.cents(), 15000);
        var quote = new ItemRepairQuote(UUID.randomUUID(), property, OWNER, 0, DAY, repair, repair.cents(), 15000,
                required, required, "v1", List.of(new ItemRepairQuote.Line("PAPER", 1, required,
                        new CustodyBytes(new byte[]{1}), 2)),
                List.of(new ItemRepairQuote.ReturnStack(0, 1, new CustodyBytes(new byte[]{2}))));
        var run = UUID.randomUUID();
        var funding = rows("SELECT * FROM npc_property_funding");
        assertTrue(repository.begin(quote, run, completed, DAY).started());
        var evidence = new ItemCollectionEvidence(OWNER, quote.collectionId(), "a".repeat(64), "b".repeat(64),
                List.of(2), List.of(1), true, true);
        assertEquals(ItemRepairRepository.State.APPLIED,
                repository.settle(quote.collectionId(), OWNER, run, evidence, true, completed, DAY).state());
        assertEquals(quote, new ItemRepairRepository(database).find(quote.collectionId(), OWNER).orElseThrow().quote());
        assertEquals(funding, rows("SELECT * FROM npc_property_funding"));
        assertEquals(0, count("npc_economy_events"));
        assertEquals("80", scalar("SELECT condition_score FROM npc_properties WHERE property_id=?", property));
        assertEquals(IncidentServiceAssessment.Outcome.ON_TIME, service().cases().getFirst().outcome());
    }

    @ParameterizedTest
    @EnumSource(IncidentType.class)
    void customPriceDoesNotChangeInsurancePrevention(IncidentType type) throws Exception {
        LocalDate day = DAY.plusDays(1);
        Instant generatedAt = NOW.plusSeconds(86400), paidAt = NOW.plusSeconds(3600);
        UUID property = enroll(type, day, 1);
        var purchases = purchases(paidAt);
        var receipt = purchases.enqueue(purchases.quoteInsurance(UUID.randomUUID(), property, OWNER, 0, DAY));
        pay(receipt.eventId(), paidAt);
        assertEquals(PurchaseRepository.State.APPLIED, purchases.complete(receipt.eventId()).orElseThrow().state());
        var funding = rows("SELECT * FROM npc_property_funding");
        var generated = rolls(CUSTOM, generatedAt).roll(property, OWNER, 0, day);
        assertEquals(IncidentRollRepository.Outcome.PREVENTED, generated.outcome());
        assertPrice(generated.incidentId(), type, CUSTOM);
        assertEquals("80", scalar("SELECT condition_score FROM npc_properties WHERE property_id=?", property));
        assertEquals("1", scalar("SELECT prevented FROM npc_incidents WHERE incident_id=?", generated.incidentId()));
        assertEquals(1, count("npc_insurance_preventions"));
        assertEquals(funding, rows("SELECT * FROM npc_property_funding"));
        assertTrue(new ItemRepairRepository(database).openIncident(property, generated.incidentId()).isEmpty());
        var care = new AutoCareRepository(database, RESERVE, Clock.fixed(generatedAt, ZoneOffset.UTC), ZONE, 0);
        care.configure(property, OWNER, 0, true, 10000, Set.of(AutoCareRepository.Action.CASH_REPAIR));
        var before = data();
        assertEquals(AutoCareRepository.ReserveStatus.TARGET_UNAVAILABLE,
                care.reserve(property, OWNER, 0, day, AutoCareRepository.Action.CASH_REPAIR,
                        generated.incidentId(), CUSTOM.cents(type)).status());
        assertFalse(rolls(CHANGED, generatedAt).roll(property, OWNER, 0, day).newlyRecorded());
        assertEquals(before, data());
        assertTrue(service().cases().isEmpty());
    }

    @Test void priceSnapshotAndDamageRollBackTogetherWhenRollRecordingFails() throws SQLException {
        UUID property = enroll(IncidentType.PESTS, DAY, 1);
        var before = data();
        execute("CREATE TRIGGER repair_price_roll_failure BEFORE INSERT ON npc_incident_rolls FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='repair price fixture'");
        try {
            assertThrows(SQLException.class, () -> rolls(CUSTOM, NOW).roll(property, OWNER, 0, DAY));
            assertEquals(before, data());
        } finally { execute("DROP TRIGGER repair_price_roll_failure"); }
        var generated = rolls(CUSTOM, NOW).roll(property, OWNER, 0, DAY);
        assertTrue(generated.newlyRecorded());
        assertPrice(generated.incidentId(), IncidentType.PESTS, CUSTOM);
    }
    private static UUID enroll(IncidentType type, LocalDate day, long first) throws SQLException {
        for (long n = first; n < first + 100000; n++) {
            UUID id = new UUID(0x505249434553L, n);
            if (SIM.incidentForDay(id, day.toString(), 80).orElse(null) != type) continue;
            new PropertyRepository(database).insertEnrollment(new Property(id, WORLD, "price-generation-" + id,
                    "downtown", "room", 2, 1000, 1000, OWNER, 0, 80, true), 5000);
            return id;
        }
        throw new AssertionError("No deterministic incident fixture within 100000 candidates");
    }

    private static IncidentRollRepository rolls(RepairPrices prices, Instant at) {
        return new IncidentRollRepository(database, SIM, Clock.fixed(at, ZoneOffset.UTC), ZONE, 0, prices);
    }

    private static PurchaseRepository purchases(Instant at) {
        return new PurchaseRepository(database, RESERVE, Clock.fixed(at, ZoneOffset.UTC), ZONE, 0);
    }

    private static void assertPrice(long incident, IncidentType type, RepairPrices prices) throws SQLException {
        long cash = prices.cents(type);
        assertEquals(Long.toString(cash), scalar("SELECT cash_repair_cents FROM npc_incidents WHERE incident_id=?", incident));
        assertEquals(cash == type.cashRepairCents() ? null : Long.toString(cash),
                scalar("SELECT configured_repair_cents FROM npc_incidents WHERE incident_id=?", incident));
    }

    private static IncidentServiceAssessment service() throws SQLException {
        return new IncidentServiceRepository(database, Clock.fixed(NOW.plusSeconds(172800), ZoneOffset.UTC))
                .read(OWNER, NOW.minusSeconds(86400));
    }

    private static LocalDateTime utc(Instant at) { return LocalDateTime.ofInstant(at, ZoneOffset.UTC); }

    private static void pay(long event, Instant completed) throws SQLException {
        var journal = new TransferJournal(database, RESERVE);
        var handle = new TransferJournal.Handle(UUID.randomUUID(), event, UUID.randomUUID(), "a".repeat(64));
        assertEquals(TransferJournal.State.PAID,
                TransferDispatchFixture.dispatch(journal, handle, new ArrayList<>(), 1000).toCompletableFuture().join().state());
        // Keep the fixture's journal and benefit times within the injected clock.
        execute("UPDATE npc_economy_events SET created_at=?,claimed_at=?,finalized_at=? WHERE event_id=?",
                utc(completed.minusSeconds(125)), utc(completed.minusSeconds(124)), utc(completed.minusSeconds(120)), event);
        execute("UPDATE npc_purchase_intents SET created_at=? WHERE event_id=?", utc(completed.minusSeconds(125)), event);
        execute("UPDATE npc_transfer_attempts SET created_at=?,credit_intent_at=?,updated_at=? WHERE event_id=?",
                utc(completed.minusSeconds(124)), utc(completed.minusSeconds(122)), utc(completed.minusSeconds(120)), event);
        execute("UPDATE npc_transfer_observations o JOIN npc_transfer_attempts a ON a.attempt_id=o.attempt_id "
                + "SET o.observed_at=CASE WHEN o.leg='DEBIT' THEN ? ELSE ? END WHERE a.event_id=?",
                utc(completed.minusSeconds(123)), utc(completed.minusSeconds(121)), event);
    }
}

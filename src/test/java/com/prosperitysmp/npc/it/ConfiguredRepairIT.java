package com.prosperitysmp.npc.it;

import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.commerce.CustodyBytes;
import com.prosperitysmp.npc.commerce.ItemCollectionEvidence;
import com.prosperitysmp.npc.economy.ItemRepairQuote;
import com.prosperitysmp.npc.economy.ItemRepairRecipes;
import com.prosperitysmp.npc.economy.ItemRepairRepository;
import com.prosperitysmp.npc.economy.PurchaseRepository;
import com.prosperitysmp.npc.economy.PurchaseRequest;
import com.prosperitysmp.npc.economy.TransferJournal;
import com.prosperitysmp.npc.economy.TransferProof.Observation;
import com.prosperitysmp.npc.formulas.IncidentType;
import com.prosperitysmp.npc.property.IncidentRepository;
import com.prosperitysmp.npc.property.IncidentServiceRepository;
import com.prosperitysmp.npc.property.IncidentServiceAssessment;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyRepository;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Saved incident prices and real SQL effects; payment observations are fixtures. */
@Timeout(45)
class ConfiguredRepairIT extends ContractItBase {
    private static final UUID OWNER = new UUID(0, 5401), RESERVE = new UUID(0, 5402);
    private UUID property;
    private long incident;
    private PurchaseRepository purchases;

    @BeforeEach void fixture() throws SQLException {
        property = UUID.randomUUID();
        new PropertyRepository(database).insertEnrollment(new Property(property, WORLD, "repair-" + property,
                "downtown", "room", 2, 1000, 1000, OWNER, 0, 80, true), 5000);
        new IncidentRepository(database).create(property, IncidentType.PESTS, null, DAY.toString(),
                NOW.plusSeconds(86400).toEpochMilli(), false);
        incident = Long.parseLong(scalar("SELECT incident_id FROM npc_incidents WHERE property_id=?", property));
        execute("UPDATE npc_incidents SET created_at=? WHERE incident_id=?", utc(-3600), incident);
        purchases = repository();
    }

    @ParameterizedTest
    @EnumSource(IncidentType.class)
    void frozenCustomPriceSurvivesPaymentReadbackAndReplay(IncidentType type) throws SQLException {
        execute("UPDATE npc_incidents SET incident_type=?,condition_damage=?,item_repair_spec=?,"
                + "cash_repair_cents=2375,configured_repair_cents=2375 WHERE incident_id=?",
                type.name(), type.conditionDamage(), type.itemRepairSpec(), incident);
        var quote = quote();
        assertEquals(2375L, quote.repair().configuredPriceCents());
        assertEquals(2375, quote.cents());
        var before = data();
        assertThrows(PurchaseRepository.MaximumExceededException.class, () -> purchases.enqueue(quote, 2374));
        assertEquals(before, data());
        var receipt = purchases.enqueue(quote, 2375);
        pay(receipt);
        purchases = repository();
        assertEquals(receipt, purchases.findByRequest(quote.requestId()).orElseThrow());
        var applied = purchases.complete(receipt.eventId()).orElseThrow();
        assertEquals(PurchaseRepository.State.APPLIED, applied.state());
        assertEquals("2375", scalar("SELECT cash_cents FROM npc_incident_service_receipts WHERE incident_id=?", incident));
        assertEquals(Integer.toString(80 + type.conditionDamage()), scalar(
                "SELECT condition_score FROM npc_properties WHERE property_id=?", property));
        assertEquals("2375", scalar("SELECT successful_cash_sinks_cents FROM npc_property_funding WHERE property_id=?", property));
        var finished = data();
        assertEquals(applied, purchases.complete(receipt.eventId()).orElseThrow());
        assertEquals(applied, purchases.enqueue(quote));
        assertEquals(finished, data());
    }

    @ParameterizedTest
    @ValueSource(strings = {"configured_repair_cents=2000",
            "configured_repair_cents=0", "configured_repair_cents=100000001", "cash_repair_cents=2000"})
    void incidentGuardRejectsLostOrMismatchedPrice(String change) throws SQLException {
        customPrice();
        var before = data();
        assertThrows(SQLException.class, () -> execute("UPDATE npc_incidents SET " + change + " WHERE incident_id=?", incident));
        assertEquals(before, data());
    }

    @Test void missingPurchaseMarkerCannotApplyAnAlreadyPaidRepair() throws SQLException {
        customPrice();
        var receipt = purchases.enqueue(quote());
        pay(receipt);
        execute("UPDATE npc_purchase_intents SET configured_price_cents=NULL WHERE event_id=?", receipt.eventId());
        var events = rows("SELECT * FROM npc_economy_events");
        var funding = rows("SELECT * FROM npc_property_funding");
        var held = purchases.complete(receipt.eventId()).orElseThrow();
        assertEquals(PurchaseRepository.State.REVIEW_REQUIRED, held.state());
        assertEquals("QUOTE_PRICE_MISMATCH", held.outcome());
        assertEquals(events, rows("SELECT * FROM npc_economy_events"));
        assertEquals(funding, rows("SELECT * FROM npc_property_funding"));
        assertNull(scalar("SELECT resolved_at FROM npc_incidents WHERE incident_id=?", incident));
        assertEquals(0, count("npc_incident_service_receipts"));
    }
    @Test void javaStillRefusesInvalidIncidentIfSqlChecksWereBypassed() throws SQLException {
        customPrice();
        try {
            try (var c = database.getConnection()) {
                execute(c, "SET SESSION check_constraint_checks=OFF");
                try { execute(c, "UPDATE npc_incidents SET configured_repair_cents=2001 WHERE incident_id=?", incident); }
                finally { execute(c, "SET SESSION check_constraint_checks=ON"); }
            }
            var before = data();
            assertThrows(SQLException.class, this::quote);
            assertEquals(before, data());
        } finally { customPrice(); }
    }

    @Test void lostIncidentMarkerCannotTurnCustomCashIntoLegacyTerms() throws SQLException {
        customPrice();
        execute("UPDATE npc_incidents SET configured_repair_cents=NULL WHERE incident_id=?", incident);
        var before = data();
        assertThrows(SQLException.class, this::quote);
        assertTrue(new ItemRepairRepository(database).openIncident(property, incident).isEmpty());
        assertEquals(before, data());
    }

    @Test void paidRepairCannotApplyToIncidentWhoseAcceptedPriceChanged() throws SQLException {
        customPrice();
        var receipt = purchases.enqueue(quote());
        pay(receipt);
        execute("UPDATE npc_incidents SET cash_repair_cents=2399,configured_repair_cents=2399 WHERE incident_id=?", incident);
        var events = rows("SELECT * FROM npc_economy_events");
        var funding = rows("SELECT * FROM npc_property_funding");
        var held = purchases.complete(receipt.eventId()).orElseThrow();
        assertEquals(PurchaseRepository.State.REVIEW_REQUIRED, held.state());
        assertEquals("REPAIR_TARGET_CHANGED", held.outcome());
        assertEquals(events, rows("SELECT * FROM npc_economy_events"));
        assertEquals(funding, rows("SELECT * FROM npc_property_funding"));
        assertNull(scalar("SELECT resolved_at FROM npc_incidents WHERE incident_id=?", incident));
        assertEquals(0, count("npc_incident_service_receipts"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void everyMigrationCutCanResumeAndReplayWithoutChangingLegacyReceipts(int completed) throws Exception {
        var receipt = purchases.enqueue(quote());
        var purchasesBefore = rows("SELECT * FROM npc_purchase_intents");
        var eventsBefore = rows("SELECT * FROM npc_economy_events");
        var steps = sql("V54__repair_prices.sql");
        assertEquals(4, steps.size());
        try {
            execute("ALTER TABLE npc_purchase_intents DROP CONSTRAINT ck_purchase_configured_price_v54");
            execute(sql("V51__insurance_prices.sql").get(2));
            execute("ALTER TABLE npc_incidents DROP CONSTRAINT ck_incident_price_v54");
            execute("ALTER TABLE npc_incidents DROP COLUMN configured_repair_cents");
            assertEquals(receipt.request(), quoteWithId(receipt.request().requestId()));
            assertEquals(receipt, purchases.findByRequest(receipt.request().requestId()).orElseThrow());
            for (String step : steps.subList(0, completed)) execute(step);
            for (int replay = 0; replay < 2; replay++) for (String step : steps) execute(step);
            assertEquals(purchasesBefore, rows("SELECT * FROM npc_purchase_intents"));
            assertEquals(eventsBefore, rows("SELECT * FROM npc_economy_events"));
            assertNull(scalar("SELECT configured_repair_cents FROM npc_incidents WHERE incident_id=?", incident));
            assertEquals(receipt, purchases.enqueue(receipt.request()));
        } finally { for (String step : steps) execute(step); }
    }

    private static List<String> sql(String file) throws Exception {
        try (var in = ConfiguredRepairIT.class.getResourceAsStream("/db/migration/" + file)) {
            assertNotNull(in);
            return MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @ParameterizedTest
    @EnumSource(IncidentType.class)
    void customMaterialRepairReadsBackAppliesAndCountsAsServiceWithoutMoney(IncidentType type) throws SQLException {
        serviceOrigin(type, 2375);
        var repository = new ItemRepairRepository(database);
        assertEquals(new ItemRepairRepository.OpenIncident(type, 2375), repository.openIncident(property, incident).orElseThrow());
        assertEquals(type, repository.openIncidentType(property, incident).orElseThrow());
        var quote = itemQuote();
        var run = UUID.randomUUID();
        var events = rows("SELECT * FROM npc_economy_events");
        var funding = rows("SELECT * FROM npc_property_funding");
        assertTrue(repository.begin(quote, run, NOW, DAY).started());
        assertEquals("2375", scalar("SELECT configured_item_repair_cents FROM npc_item_repair_collections WHERE collection_id=?", quote.collectionId()));
        repository = new ItemRepairRepository(database);
        assertEquals(quote, repository.find(quote.collectionId(), OWNER).orElseThrow().quote());
        var result = repository.settle(quote.collectionId(), OWNER, run, confirmed(quote), true, NOW, DAY);
        assertEquals(ItemRepairRepository.State.APPLIED, result.state());
        assertTrue(result.returns().isEmpty());
        assertEquals(events, rows("SELECT * FROM npc_economy_events"));
        assertEquals(funding, rows("SELECT * FROM npc_property_funding"));
        assertEquals("2375", scalar("SELECT cash_equivalent_cents FROM npc_incident_item_repair_receipts WHERE incident_id=?", incident));
        var cases = service().cases();
        assertEquals(1, cases.size());
        assertEquals(IncidentServiceAssessment.Outcome.ON_TIME, cases.getFirst().outcome());
        assertEquals(quote.collectionId(), cases.getFirst().itemRepairId());
        var applied = data();
        assertEquals(result, repository.settle(quote.collectionId(), OWNER, run, confirmed(quote), true, NOW, DAY));
        assertEquals(applied, data());
    }

    @ParameterizedTest
    @ValueSource(longs = {2375, 3500, 5000})
    void serviceHistoryUsesAcceptedCashEvenIfItEqualsAnotherTypesDefault(long cents) throws SQLException {
        serviceOrigin(IncidentType.PESTS, cents);
        var receipt = purchases.enqueue(quote());
        pay(receipt);
        assertEquals(PurchaseRepository.State.APPLIED, purchases.complete(receipt.eventId()).orElseThrow().state());
        var before = data();
        assertEquals(IncidentServiceAssessment.Outcome.ON_TIME, service().cases().getFirst().outcome());
        assertEquals(before, data());
        execute("UPDATE npc_purchase_intents SET configured_price_cents=NULL WHERE event_id=?", receipt.eventId());
        before = data();
        assertThrows(SQLException.class, this::service);
        assertEquals(before, data());
    }

    @Test void lostMaterialMarkerIsRefusedByBothHandInAndServiceReaders() throws SQLException {
        serviceOrigin(IncidentType.PESTS, 2375);
        var repository = new ItemRepairRepository(database);
        var quote = itemQuote();
        var run = UUID.randomUUID();
        assertTrue(repository.begin(quote, run, NOW, DAY).started());
        assertEquals(ItemRepairRepository.State.APPLIED,
                repository.settle(quote.collectionId(), OWNER, run, confirmed(quote), true, NOW, DAY).state());
        execute("UPDATE npc_item_repair_collections SET configured_item_repair_cents=NULL WHERE collection_id=?", quote.collectionId());
        var before = data();
        assertThrows(SQLException.class, () -> repository.find(quote.collectionId(), OWNER));
        assertThrows(SQLException.class, this::service);
        assertEquals(before, data());
    }

    @Test void changedAcceptedCashReturnsTheItemsInsteadOfApplyingTheOldHandIn() throws SQLException {
        customPrice();
        var repository = new ItemRepairRepository(database);
        var quote = itemQuote();
        var run = UUID.randomUUID();
        assertTrue(repository.begin(quote, run, NOW, DAY).started());
        execute("UPDATE npc_incidents SET cash_repair_cents=2399,configured_repair_cents=2399 WHERE incident_id=?", incident);
        var funding = rows("SELECT * FROM npc_property_funding");
        var result = repository.settle(quote.collectionId(), OWNER, run, confirmed(quote), true, NOW, DAY);
        assertEquals(ItemRepairRepository.State.RETURN_ISSUED, result.state());
        assertEquals(1, result.returns().size());
        assertNull(scalar("SELECT resolved_at FROM npc_incidents WHERE incident_id=?", incident));
        assertEquals(0, count("npc_incident_item_repair_receipts"));
        assertEquals(0, count("npc_economy_events"));
        assertEquals(funding, rows("SELECT * FROM npc_property_funding"));
    }

    private void customPrice() throws SQLException {
        execute("UPDATE npc_incidents SET cash_repair_cents=2375,configured_repair_cents=2375 WHERE incident_id=?", incident);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void materialPriceMigrationResumesEveryCutWithoutChangingOldHandIns(int completed) throws Exception {
        var repository = new ItemRepairRepository(database);
        var quote = itemQuote();
        assertTrue(repository.begin(quote, UUID.randomUUID(), NOW, DAY).started());
        var before = data();
        var steps = sql("V55__item_repair_prices.sql");
        assertEquals(2, steps.size());
        try {
            execute("ALTER TABLE npc_item_repair_collections DROP CONSTRAINT ck_item_repair_price_v55");
            execute("ALTER TABLE npc_item_repair_collections DROP COLUMN configured_item_repair_cents");
            assertEquals(quote, repository.find(quote.collectionId(), OWNER).orElseThrow().quote());
            for (String step : steps.subList(0, completed)) execute(step);
            for (int replay = 0; replay < 2; replay++) for (String step : steps) execute(step);
            assertEquals(before, data());
            assertEquals(quote, repository.find(quote.collectionId(), OWNER).orElseThrow().quote());
        } finally { for (String step : steps) execute(step); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"configured_item_repair_cents=2000", "configured_item_repair_cents=0",
            "configured_item_repair_cents=2374", "configured_item_repair_cents=100000001", "cash_cents=2374"})
    void explicitMaterialPriceCannotDifferFromTheSavedCash(String change) throws SQLException {
        customPrice();
        var repository = new ItemRepairRepository(database);
        var quote = itemQuote();
        assertTrue(repository.begin(quote, UUID.randomUUID(), NOW, DAY).started());
        var before = data();
        assertThrows(SQLException.class, () -> execute("UPDATE npc_item_repair_collections SET " + change
                + " WHERE collection_id=?", quote.collectionId()));
        assertEquals(before, data());
    }

    private ItemRepairQuote itemQuote() throws SQLException {
        var repair = quote().repair();
        long required = ItemRepairRecipes.requiredCents(repair.cents(), 15000);
        return new ItemRepairQuote(UUID.randomUUID(), property, OWNER, 0, DAY, repair, repair.cents(), 15000,
                required, required, "v1", List.of(new ItemRepairQuote.Line("PAPER", 1, required,
                        new CustodyBytes(new byte[]{1}), 2)),
                List.of(new ItemRepairQuote.ReturnStack(0, 1, new CustodyBytes(new byte[]{2}))));
    }

    private static ItemCollectionEvidence confirmed(ItemRepairQuote quote) {
        return new ItemCollectionEvidence(OWNER, quote.collectionId(), "a".repeat(64), "b".repeat(64),
                List.of(2), List.of(1), true, true);
    }

    private void serviceOrigin(IncidentType type, long cents) throws SQLException {
        execute("UPDATE npc_incidents SET incident_type=?,condition_damage=?,item_repair_spec=?,cash_repair_cents=?,"
                + "configured_repair_cents=?,due_at=? WHERE incident_id=?", type.name(), type.conditionDamage(),
                type.itemRepairSpec(), cents, cents, utc(82800), incident);
        execute("INSERT INTO npc_incident_rolls(property_id,economy_day,ownership_epoch,owner_uuid,outcome,incident_id,"
                + "incident_type,condition_before,condition_after,recorded_at) VALUES(?,?,0,?,'INCIDENT',?,?,?,80,?)",
                property, DAY.toString(), OWNER, incident, type.name(), 80 + type.conditionDamage(), utc(-3600));
    }

    private IncidentServiceAssessment service() throws SQLException {
        return new IncidentServiceRepository(database, Clock.fixed(NOW.plusSeconds(172800), ZoneOffset.UTC))
                .read(OWNER, NOW.minusSeconds(86400));
    }

    private PurchaseRepository repository() {
        return new PurchaseRepository(database, RESERVE, Clock.fixed(NOW, ZoneOffset.UTC), ZONE, 0);
    }

    private PurchaseRequest quote() throws SQLException { return quoteWithId(UUID.randomUUID()); }

    private PurchaseRequest quoteWithId(UUID id) throws SQLException {
        return purchases.quoteRepair(id, property, OWNER, 0, incident, DAY);
    }

    private static LocalDateTime utc(long seconds) { return LocalDateTime.ofInstant(NOW.plusSeconds(seconds), ZoneOffset.UTC); }

    private void pay(PurchaseRepository.Receipt receipt) throws SQLException {
        var journal = new TransferJournal(database, RESERVE);
        var handle = new TransferJournal.Handle(UUID.randomUUID(), receipt.eventId(), UUID.randomUUID(), "a".repeat(64));
        assertTrue(journal.beginDebit(handle).newlyStarted());
        double dollars = receipt.quotedCents() / 100.0;
        assertEquals(TransferJournal.State.CREDIT_READY, journal.recordDebit(handle,
                new Observation(true, true, dollars, 1000.0, 1000.0 - dollars, false)).state());
        assertTrue(journal.beginCredit(handle).newlyStarted());
        assertEquals(TransferJournal.State.PAID, journal.recordCredit(handle,
                new Observation(true, true, dollars, 2000.0, 2000.0 + dollars, false)).state());
        execute("UPDATE npc_economy_events SET created_at=?,claimed_at=?,finalized_at=? WHERE event_id=?",
                utc(-125), utc(-124), utc(-120), receipt.eventId());
        execute("UPDATE npc_purchase_intents SET created_at=? WHERE event_id=?", utc(-125), receipt.eventId());
        execute("UPDATE npc_transfer_attempts SET created_at=?,credit_intent_at=?,updated_at=? WHERE event_id=?",
                utc(-124), utc(-122), utc(-120), receipt.eventId());
        execute("UPDATE npc_transfer_observations o JOIN npc_transfer_attempts a ON a.attempt_id=o.attempt_id "
                + "SET o.observed_at=CASE WHEN o.leg='DEBIT' THEN ? ELSE ? END WHERE a.event_id=?",
                utc(-123), utc(-121), receipt.eventId());
    }
}

package com.prosperitysmp.npc.it;

import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.economy.PurchaseRepository;
import com.prosperitysmp.npc.economy.TransferDispatchFixture;
import com.prosperitysmp.npc.economy.TransferJournal;
import com.prosperitysmp.npc.formulas.IncidentType;
import com.prosperitysmp.npc.property.AutoCareRepository;
import com.prosperitysmp.npc.property.AutoCareRepository.Action;
import com.prosperitysmp.npc.property.AutoCareRepository.ReserveStatus;
import com.prosperitysmp.npc.property.AutoCareRepository.State;
import com.prosperitysmp.npc.property.IncidentRepository;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyRepository;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQL consent, binding and effects; provider observations are fixtures. */
@Timeout(45)
class ConfiguredRepairAutoCareIT extends ContractItBase {
    private static final UUID OWNER = new UUID(0, 5602), RESERVE = new UUID(0, 5603);
    private UUID property;
    private long incident;
    private AutoCareRepository care;
    private PurchaseRepository purchases;

    @BeforeEach void fixture() throws SQLException {
        property = UUID.randomUUID();
        new PropertyRepository(database).insertEnrollment(new Property(property, WORLD, "care-repair-" + property,
                "downtown", "room", 2, 1000, 1000, OWNER, 0, 80, true), 5000);
        new IncidentRepository(database).create(property, IncidentType.PESTS, null, DAY.toString(),
                NOW.plusSeconds(86400).toEpochMilli(), false);
        incident = Long.parseLong(scalar("SELECT incident_id FROM npc_incidents WHERE property_id=?", property));
        execute("UPDATE npc_incidents SET created_at=? WHERE incident_id=?", utc(-3600), incident);
        care = repository();
        purchases = new PurchaseRepository(database, RESERVE, Clock.fixed(NOW, ZoneOffset.UTC), ZONE, 0);
    }

    @Test void defaultRepairKeepsItsOriginalClaimIdentity() throws SQLException {
        configure(2000, Set.of(Action.CASH_REPAIR));
        var claim = reserve(2000).claim();
        assertNull(claim.configuredPriceCents());
        assertEquals(AutoCareRepository.claimId(property, OWNER, 0, DAY, Action.CASH_REPAIR, incident), claim.claimId());
        assertEquals(claim, repository().find(claim.claimId()).orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2375, 3500, 5000, 100000000})
    void acceptedMarkerIsSavedEvenWhenAmountMatchesAnotherTypesDefault(long cents) throws SQLException {
        customPrice(cents);
        configure(Math.max(1, cents - 1), Set.of(Action.CASH_REPAIR));
        if (cents > 1) {
            var before = data();
            assertEquals(ReserveStatus.LIMIT, reserve(cents).status());
            assertEquals(before, data());
            configure(cents, Set.of(Action.CASH_REPAIR));
        }
        var reservation = reserve(cents);
        assertTrue(reservation.newlyReserved());
        var claim = reservation.claim();
        assertEquals(cents, claim.quotedCents());
        assertEquals(cents, claim.configuredPriceCents());
        assertNotEquals(AutoCareRepository.claimId(property, OWNER, 0, DAY, Action.CASH_REPAIR, incident), claim.claimId());
        assertEquals(claim, repository().find(claim.claimId()).orElseThrow());
        var before = data();
        assertEquals(ReserveStatus.REPLAY, reserve(cents).status());
        assertEquals(before, data());
    }

    @ParameterizedTest
    @ValueSource(longs = {2375, 3500, 5000})
    void customRepairBindsPaysAppliesAndSpendsOnlyOnce(long cents) throws Exception {
        customPrice(cents);
        configure(cents, Set.of(Action.CASH_REPAIR));
        var claim = reserve(cents).claim();
        var receipt = parent(claim);
        assertTrue(care.bind(claim.claimId(), receipt.eventId()).newlyBound());
        var calls = new ArrayList<String>();
        pay(receipt.eventId(), calls);
        assertEquals(PurchaseRepository.State.APPLIED, purchases.complete(receipt.eventId()).orElseThrow().state());
        care = repository();
        var spent = care.reconcile(claim.claimId());
        assertEquals(State.SPENT, spent.state());
        assertEquals(cents, spent.configuredPriceCents());
        assertEquals(Long.toString(cents), scalar("SELECT successful_cash_sinks_cents FROM npc_property_funding WHERE property_id=?", property));
        assertEquals(Long.toString(cents), scalar("SELECT cash_cents FROM npc_incident_service_receipts WHERE incident_id=?", incident));
        assertEquals(2, calls.stream().filter(s -> s.startsWith("money-")).count());
        var before = data();
        assertEquals(spent, care.reconcile(claim.claimId()));
        assertEquals(spent, repository().find(claim.claimId()).orElseThrow());
        assertFalse(care.bind(claim.claimId(), receipt.eventId()).newlyBound());
        assertEquals(ReserveStatus.REPLAY, reserve(cents).status());
        assertEquals(before, data());
        assertEquals(2, calls.stream().filter(s -> s.startsWith("money-")).count());
    }
    @ParameterizedTest
    @ValueSource(strings = {"disabled", "actions", "resolved", "other-owner", "paused"})
    void freshCustomRepairNeedsLiveConsentAndCurrentTarget(String change) throws SQLException {
        customPrice(2375);
        configure(3000, Set.of(Action.CASH_REPAIR));
        ReserveStatus expected = ReserveStatus.TARGET_UNAVAILABLE;
        switch (change) {
            case "disabled" -> {
                care.configure(property, OWNER, 0, false, 0, Set.of());
                expected = ReserveStatus.DISABLED;
            }
            case "actions" -> {
                configure(3000, Set.of(Action.MAINTAIN));
                expected = ReserveStatus.NOT_ALLOWED;
            }
            case "resolved" -> execute("UPDATE npc_incidents SET resolved_at=?,resolver_uuid=? WHERE incident_id=?", utc(-30), OWNER, incident);
            case "other-owner" -> execute("UPDATE npc_properties SET owner_uuid=?,ownership_epoch=1 WHERE property_id=?", new UUID(0, 5699), property);
            case "paused" -> execute("UPDATE npc_properties SET enabled=FALSE WHERE property_id=?", property);
            default -> fail(change);
        }
        var before = data();
        assertEquals(expected, reserve(2375).status());
        assertEquals(before, data());
    }

    @Test void quotedAmountMustMatchTheAcceptedIncident() throws SQLException {
        customPrice(2375);
        configure(3000, Set.of(Action.CASH_REPAIR));
        var before = data();
        assertEquals(ReserveStatus.TARGET_UNAVAILABLE, reserve(2000).status());
        assertEquals(before, data());
        execute("UPDATE npc_incidents SET configured_repair_cents=NULL WHERE incident_id=?", incident);
        before = data();
        assertThrows(SQLException.class, () -> reserve(2375));
        assertEquals(before, data());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 100000001, Long.MAX_VALUE})
    void invalidRepairAmountIsRefusedBeforeAnyClaim(long cents) throws SQLException {
        var before = data();
        assertThrows(IllegalArgumentException.class, () -> reserve(cents));
        assertEquals(before, data());
    }

    @ParameterizedTest
    @ValueSource(longs = {3500, 5000})
    void losingMarkerCannotConvertAPricedClaimIntoAnotherTypesDefault(long cents) throws SQLException {
        customPrice(cents);
        configure(cents, Set.of(Action.CASH_REPAIR));
        var claim = reserve(cents).claim();
        execute("UPDATE npc_auto_care_claims SET configured_price_cents=NULL WHERE claim_id=?", claim.claimId());
        var before = data();
        assertThrows(SQLException.class, () -> care.find(claim.claimId()));
        assertEquals(before, data());
    }

    @Test void lostParentMarkerCannotAuthorizeABinding() throws SQLException {
        customPrice(3500);
        configure(3500, Set.of(Action.CASH_REPAIR));
        var claim = reserve(3500).claim();
        var receipt = parent(claim);
        execute("UPDATE npc_purchase_intents SET configured_price_cents=NULL WHERE event_id=?", receipt.eventId());
        var before = data();
        assertThrows(SQLException.class, () -> care.bind(claim.claimId(), receipt.eventId()));
        assertEquals(before, data());
        assertEquals(State.RESERVED, care.find(claim.claimId()).orElseThrow().state());
        assertEquals(0, count("npc_transfer_attempts"));
    }

    @ParameterizedTest
    @ValueSource(longs = {2300, 2399})
    void changedIncidentCannotAuthorizeNewTermsUnderTheOldClaim(long cents) throws SQLException {
        customPrice(2375);
        configure(3000, Set.of(Action.CASH_REPAIR));
        var claim = reserve(2375).claim();
        customPrice(cents);
        var before = data();
        assertThrows(SQLException.class, () -> reserve(cents));
        assertEquals(before, data());
        if (cents > claim.quotedCents()) {
            assertThrows(PurchaseRepository.MaximumExceededException.class, () -> parent(claim));
            assertEquals(before, data());
        } else {
            var receipt = parent(claim);
            before = data();
            assertThrows(SQLException.class, () -> care.bind(claim.claimId(), receipt.eventId()));
            assertEquals(before, data());
        }
        assertEquals(State.RESERVED, care.find(claim.claimId()).orElseThrow().state());
        assertEquals(0, count("npc_transfer_attempts"));
    }

    @Test void malformedLegacyClaimIsReportedAsSqlFailureIfChecksWereBypassed() throws SQLException {
        customPrice(2375);
        configure(2375, Set.of(Action.CASH_REPAIR));
        var claim = reserve(2375).claim();
        try {
            try (var c = database.getConnection()) {
                execute(c, "SET SESSION check_constraint_checks=OFF");
                try { execute(c, "UPDATE npc_auto_care_claims SET configured_price_cents=NULL WHERE claim_id=?", claim.claimId()); }
                finally { execute(c, "SET SESSION check_constraint_checks=ON"); }
            }
            var before = data();
            assertThrows(SQLException.class, () -> care.find(claim.claimId()));
            assertEquals(before, data());
        } finally { execute("UPDATE npc_auto_care_claims SET configured_price_cents=2375 WHERE claim_id=?", claim.claimId()); }
    }

    @Test void heldCustomClaimRetainsItsWholeBudget() throws SQLException {
        customPrice(2375);
        configure(3000, Set.of(Action.CASH_REPAIR, Action.MAINTAIN));
        var claim = reserve(2375).claim();
        assertEquals(State.HELD, care.hold(claim.claimId()).state());
        var before = data();
        assertEquals(ReserveStatus.LIMIT, care.reserve(property, OWNER, 0, DAY, Action.MAINTAIN, null, 1000).status());
        assertEquals(before, data());
        assertEquals(0, count("npc_economy_events"));
    }
    @Test void confirmedCustomDeclineReleasesBudgetButCannotRepeatTheSameAction() throws SQLException {
        customPrice(2375);
        configure(2375, Set.of(Action.CASH_REPAIR, Action.MAINTAIN));
        var claim = reserve(2375).claim();
        var receipt = parent(claim);
        assertTrue(care.bind(claim.claimId(), receipt.eventId()).newlyBound());
        var journal = new TransferJournal(database, RESERVE);
        var handle = handle(receipt.eventId());
        assertTrue(journal.beginDebit(handle).newlyStarted());
        assertEquals(TransferJournal.State.DECLINED, journal.recordDebit(handle,
                new com.prosperitysmp.npc.economy.TransferProof.Observation(true, false, 23.75, 1000.0, 1000.0, false)).state());
        pinPaymentTime(receipt.eventId());
        assertEquals(PurchaseRepository.State.DECLINED, purchases.complete(receipt.eventId()).orElseThrow().state());
        assertEquals(State.DECLINED, care.reconcile(claim.claimId()).state());
        assertEquals(ReserveStatus.REPLAY, reserve(2375).status());
        assertTrue(care.reserve(property, OWNER, 0, DAY, Action.MAINTAIN, null, 1000).newlyReserved());
        assertEquals("0", scalar("SELECT successful_cash_sinks_cents FROM npc_property_funding WHERE property_id=?", property));
        assertEquals(0, count("npc_incident_service_receipts"));
        assertNull(scalar("SELECT resolved_at FROM npc_incidents WHERE incident_id=?", incident));
    }

    @ParameterizedTest
    @ValueSource(strings = {"configured_price_cents=NULL", "configured_price_cents=0",
            "configured_price_cents=100000001", "incident_id=NULL", "quoted_cents=2374"})
    void sqlGuardRejectsIncompleteOrMismatchedCustomClaims(String change) throws SQLException {
        customPrice(2375);
        configure(2375, Set.of(Action.CASH_REPAIR));
        var claim = reserve(2375).claim();
        var before = data();
        assertThrows(SQLException.class, () -> execute("UPDATE npc_auto_care_claims SET " + change + " WHERE claim_id=?", claim.claimId()));
        assertEquals(before, data());
    }

    @Test void changingBothSavedPricesStillCannotChangeTheAcceptedClaimId() throws SQLException {
        customPrice(2375);
        configure(3000, Set.of(Action.CASH_REPAIR));
        var claim = reserve(2375).claim();
        execute("UPDATE npc_auto_care_claims SET quoted_cents=2399,configured_price_cents=2399 WHERE claim_id=?", claim.claimId());
        var before = data();
        assertThrows(SQLException.class, () -> care.find(claim.claimId()));
        assertEquals(before, data());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void everyMigrationCutReplaysWithoutChangingLegacyClaims(int completed) throws Exception {
        configure(2000, Set.of(Action.CASH_REPAIR));
        var claim = reserve(2000).claim();
        var before = data();
        var steps = sql("V56__auto_care_repair_prices.sql");
        assertEquals(2, steps.size());
        try {
            execute("ALTER TABLE npc_auto_care_claims DROP CONSTRAINT ck_auto_care_claim_action_v56");
            for (String step : sql("V53__auto_care_utility_prices.sql")) execute(step);
            for (String step : steps.subList(0, completed)) execute(step);
            if (completed < 2) assertThrows(SQLException.class, () -> execute(
                    "UPDATE npc_auto_care_claims SET quoted_cents=2375,configured_price_cents=2375 WHERE claim_id=?", claim.claimId()));
            for (int replay = 0; replay < 2; replay++) for (String step : steps) execute(step);
            assertEquals(before, data());
            assertEquals(claim, repository().find(claim.claimId()).orElseThrow());
        } finally { for (String step : steps) execute(step); }
    }

    private void customPrice(long cents) throws SQLException {
        execute("UPDATE npc_incidents SET cash_repair_cents=?,configured_repair_cents=? WHERE incident_id=?", cents, cents, incident);
    }

    private void configure(long ceiling, Set<Action> actions) throws SQLException {
        care.configure(property, OWNER, 0, true, ceiling, actions);
    }

    private AutoCareRepository repository() {
        return new AutoCareRepository(database, RESERVE, Clock.fixed(NOW, ZoneOffset.UTC), ZONE, 0);
    }

    private AutoCareRepository.Reservation reserve(long cents) throws SQLException {
        return care.reserve(property, OWNER, 0, DAY, Action.CASH_REPAIR, incident, cents);
    }

    private PurchaseRepository.Receipt parent(AutoCareRepository.Claim claim) throws SQLException {
        return purchases.enqueue(purchases.quoteRepair(claim.claimId(), property, OWNER, 0, incident, DAY), claim.quotedCents());
    }

    private static TransferJournal.Handle handle(long event) {
        return new TransferJournal.Handle(UUID.randomUUID(), event, UUID.randomUUID(), "a".repeat(64));
    }

    private static LocalDateTime utc(long seconds) { return LocalDateTime.ofInstant(NOW.plusSeconds(seconds), ZoneOffset.UTC); }

    private static void pay(long event, List<String> calls) throws SQLException {
        var journal = new TransferJournal(database, RESERVE);
        assertEquals(TransferJournal.State.PAID,
                TransferDispatchFixture.dispatch(journal, handle(event), calls, 1000).toCompletableFuture().join().state());
        pinPaymentTime(event);
    }

    private static void pinPaymentTime(long event) throws SQLException {
        // Keep the SQL fixture's chronology within the injected clock.
        execute("UPDATE npc_economy_events SET created_at=?,claimed_at=?,finalized_at=? WHERE event_id=?",
                utc(-125), utc(-124), utc(-120), event);
        execute("UPDATE npc_purchase_intents SET created_at=? WHERE event_id=?", utc(-125), event);
        execute("UPDATE npc_transfer_attempts SET created_at=?,credit_intent_at=CASE WHEN credit_intent_at IS NULL THEN NULL ELSE ? END,"
                + "updated_at=? WHERE event_id=?", utc(-124), utc(-122), utc(-120), event);
        execute("UPDATE npc_transfer_observations o JOIN npc_transfer_attempts a ON a.attempt_id=o.attempt_id "
                + "SET o.observed_at=CASE WHEN o.leg='DEBIT' THEN ? ELSE ? END WHERE a.event_id=?", utc(-123), utc(-121), event);
    }

    private static List<String> sql(String file) throws Exception {
        try (var in = ConfiguredRepairAutoCareIT.class.getResourceAsStream("/db/migration/" + file)) {
            assertNotNull(in);
            return MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}

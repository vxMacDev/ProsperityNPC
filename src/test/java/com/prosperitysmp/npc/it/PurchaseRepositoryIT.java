package com.prosperitysmp.npc.it;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.economy.EconomyEvent;
import com.prosperitysmp.npc.economy.EconomyEventRepository;
import com.prosperitysmp.npc.economy.EvictionQuote;
import com.prosperitysmp.npc.economy.EventSource;
import com.prosperitysmp.npc.economy.PurchaseRepository;
import com.prosperitysmp.npc.economy.PurchaseRepository.Receipt;
import com.prosperitysmp.npc.economy.PurchaseRepository.State;
import com.prosperitysmp.npc.economy.PurchaseRequest;
import com.prosperitysmp.npc.economy.PurchaseRequest.Kind;
import com.prosperitysmp.npc.economy.PurchasePrices;
import com.prosperitysmp.npc.economy.TransferDispatchFixture;
import com.prosperitysmp.npc.economy.TransferJournal;
import com.prosperitysmp.npc.economy.TransferJournal.Handle;
import com.prosperitysmp.npc.economy.TransferProof.Observation;
import com.prosperitysmp.npc.formulas.UpgradeType;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyRepository;
import com.prosperitysmp.npc.property.Tenant;
import com.prosperitysmp.npc.property.TenantRepository;
import com.prosperitysmp.npc.property.UpgradeRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real ledger transitions and fault injection, exclusively in a fresh embedded MariaDB schema. */
class PurchaseRepositoryIT {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 6);
    private static final UUID WORLD = new UUID(0, 700);
    private static final UUID RESERVE = new UUID(0, 701);
    private static final long SEED = 5_000;
    private static final String V3 = "/db/migration/V3__purchase_receipts.sql";
    private static final List<String> TRIGGERS = List.of("purchase_insert_failure", "purchase_apply_failure");
    private static DB mariaDb;
    private static Database database;
    private static String schemaName;
    private PurchaseRepository purchases;
    private EconomyEventRepository events;
    private TransferJournal journal;
    private PropertyRepository properties;
    private TenantRepository tenants;
    private UpgradeRepository upgrades;
    private UUID property;
    private UUID owner;
    private UUID tenant;

    @BeforeAll
    static void bootDatabase() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        mariaDb.start();
        schemaName = "npc_purchase_" + UUID.randomUUID().toString().replace("-", "");
        mariaDb.createDB(schemaName);
        database = new Database();
        database.connect(new DatabaseSettings("localhost", cfg.getPort(), schemaName, "root", "", 6, 10_000L));
        applyResource("/db/migration/V1__initial_schema.sql");
        applyResource("/db/migration/V45__property_backend.sql");
        applyResource("/db/migration/V2__resident_identity.sql");
        applyResource(V3);
        applyResource("/db/migration/V4__transfer_journal.sql");
        applyResource("/db/migration/V6__incident_repairs.sql");
        applyResource("/db/migration/V8__eviction_tenancies.sql");
        applyResource("/db/migration/V15__tenancy_departures.sql");
        applyResource("/db/migration/V17__transfer_resolutions.sql");
        applyResource("/db/migration/V47__purchase_prices.sql");
        applyResource("/db/migration/V49__upgrade_cost_width.sql");
        applyResource("/db/migration/V50__eviction_price.sql");
        // V40: the refusal columns the transfer and commerce readers expect, on the tables this schema has.
        try (var v40 = database.getConnection()) { com.prosperitysmp.npc.db.V40Fixture.apply(v40); }
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        try {
            if (database != null) database.close();
        } finally {
            if (mariaDb != null) mariaDb.stop();
        }
    }

    private static PurchaseRepository purchasesOn(Database database) {
        return new PurchaseRepository(database, RESERVE, Clock.systemUTC(), ZoneId.of("America/Chicago"), 4);
    }

    @BeforeEach
    void freshPurchases() throws SQLException {
        dropFixtureTriggers();
        for (String table : List.of("npc_tenancy_departures", "npc_transfer_resolutions", "npc_transfer_observations", "npc_transfer_attempts",
                "npc_purchase_intents", "npc_economy_events", "npc_tenants",
                "npc_upgrades", "npc_property_ownership", "npc_property_funding", "npc_properties")) {
            execute("DELETE FROM " + table);
        }
        purchases = purchasesOn(database);
        events = new EconomyEventRepository(database);
        journal = new TransferJournal(database, RESERVE);
        properties = new PropertyRepository(database);
        tenants = new TenantRepository(database);
        upgrades = new UpgradeRepository(database);
        property = UUID.randomUUID();
        owner = UUID.randomUUID();
        tenant = UUID.randomUUID();
        enroll(property);
        tenants.insert(new Tenant(tenant, "Purchase Tenant", property, 0, "STANDARD", "GENERIC", 50));
    }

    @AfterEach
    void dropFixtureTriggers() throws SQLException {
        if (database != null && database.isConnected()) {
            for (String trigger : TRIGGERS) execute("DROP TRIGGER IF EXISTS " + trigger);
        }
    }

    @ParameterizedTest
    @EnumSource(value = Kind.class, names = {"UPGRADE", "MAINTAIN", "EVICT"})
    void enqueueAtomicallyPersistsOneSourceBoundEventAndOneIntentWithoutEffect(Kind kind) throws SQLException {
        PurchaseRequest request = request(kind);
        Receipt receipt = purchases.enqueue(request);
        assertTrue(receipt.eventId() > 0);
        assertEquals(request, receipt.request());
        assertEquals(request.cents(), receipt.quotedCents());
        assertState(State.WAITING, receipt);
        assertEquals(receipt, purchases.enqueue(request));
        assertEquals(1, count("npc_purchase_intents"));
        assertEquals(1, count("npc_economy_events"));
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM npc_economy_events WHERE event_id=?")) {
            ps.setQueryTimeout(3);
            ps.setLong(1, receipt.eventId());
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(request.eventKey(), rs.getString("idempotency_key"));
                assertEquals(request.cents(), rs.getLong("requested_amount_cents"));
                assertEquals(request.source().key(), rs.getString("source"));
                assertEquals("TRANSFER_FROM_PUBLIC", rs.getString("direction"));
                assertArrayEquals(Uuids.toBytes(property), rs.getBytes("property_id"));
                assertArrayEquals(Uuids.toBytes(owner), rs.getBytes("counterparty_uuid"));
                assertArrayEquals(Uuids.toBytes(owner), rs.getBytes("benefit_unit_id"));
                assertEquals(0, rs.getInt("ownership_epoch"));
                assertFalse(rs.wasNull());
                assertEquals(DAY.toString(), rs.getString("economy_day"));
                assertEquals("PENDING", rs.getString("state"));
            }
        }
        assertNoEffect();
        assertArrayEquals(new long[]{SEED, 0, 0}, properties.fundingOf(property));
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-property", "wrong-owner", "no-owner", "disabled", "stale-epoch",
            "skipped-level", "already-installed", "full-condition", "missing-tenant", "foreign-tenant"})
    void invalidCurrentEvidenceRejectsEnqueueWithoutEitherRow(String fixture) throws SQLException {
        PurchaseRequest request = request(Kind.UPGRADE);
        switch (fixture) {
            case "missing-property" -> request = new PurchaseRequest(UUID.randomUUID(), UUID.randomUUID(), owner,
                    0, Kind.UPGRADE, UpgradeType.ADVERTISING, 1, null, DAY);
            // Deliberately damaged row, written by raw SQL because the ownership producer rejects a zero-epoch change.
            case "wrong-owner" -> execute("UPDATE npc_properties SET owner_uuid=? WHERE property_id=?", UUID.randomUUID(), property);
            case "no-owner" -> properties.applyOwnerChange(property, owner, null, 1, "fixture");
            case "disabled" -> properties.setEnabled(property, false);
            case "stale-epoch" -> properties.applyOwnerChange(property, owner, owner, 1, "fixture");
            case "skipped-level" -> request = new PurchaseRequest(UUID.randomUUID(), property, owner,
                    0, Kind.UPGRADE, UpgradeType.ADVERTISING, 2, null, DAY);
            case "already-installed" -> upgrades.setLevel(property, "ADVERTISING", 1, 10_000);
            case "full-condition" -> {
                properties.setCondition(property, 100);
                request = request(Kind.MAINTAIN);
            }
            case "missing-tenant", "foreign-tenant" -> {
                request = request(Kind.EVICT);
                if (fixture.equals("missing-tenant")) tenants.delete(tenant);
                else {
                    UUID destination = UUID.randomUUID();
                    enroll(destination);
                    tenants.moveTenant(tenant, destination, 0, DAY.toString());
                }
            }
            default -> throw new AssertionError(fixture);
        }
        PurchaseRequest invalid = request;
        var refusal = assertThrows(SQLException.class, () -> purchases.enqueue(invalid));
        if (List.of("skipped-level", "already-installed", "full-condition").contains(fixture)) {
            assertTrue(refusal instanceof PurchaseRepository.TargetUnavailableException);
            assertEquals(0, refusal.getSuppressed().length);
        }
        assertTrue(purchases.findByRequest(invalid.requestId()).isEmpty());
        assertEquals(0, count("npc_economy_events"));
        assertEquals(0, count("npc_purchase_intents"));
        assertArrayEquals(new long[]{SEED, 0, 0}, properties.fundingOf(property));
    }

    @ParameterizedTest
    @ValueSource(strings = {"property", "payer", "epoch", "kind", "upgrade", "target", "tenant", "day"})
    void sameRequestUuidWithAnyDifferentPayloadIsRejected(String field) throws SQLException {
        PurchaseRequest original = request(field.equals("tenant") ? Kind.EVICT : Kind.UPGRADE);
        Receipt receipt = purchases.enqueue(original);
        PurchaseRequest changed = new PurchaseRequest(original.requestId(),
                field.equals("property") ? UUID.randomUUID() : property,
                field.equals("payer") ? UUID.randomUUID() : owner, field.equals("epoch") ? 1 : 0,
                field.equals("kind") ? Kind.MAINTAIN : original.kind(),
                field.equals("kind") ? null : field.equals("upgrade") ? UpgradeType.FACILITIES : original.upgrade(),
                field.equals("kind") ? null : field.equals("target") ? Integer.valueOf(2) : original.targetLevel(),
                field.equals("tenant") ? UUID.randomUUID() : original.tenantId(),
                field.equals("day") ? DAY.plusDays(1) : DAY, original.repair(), original.eviction());
        assertThrows(SQLException.class, () -> purchases.enqueue(changed));
        assertEquals(receipt, purchases.findByRequest(original.requestId()).orElseThrow());
        assertEquals(1, count("npc_economy_events"));
        assertEquals(1, count("npc_purchase_intents"));
        assertNoEffect();
    }

    @ParameterizedTest
    @EnumSource(value = State.class, names = {"WAITING", "APPLIED", "REVIEW_REQUIRED"})
    void sameActionReturnsOriginalReceiptEvenWithFreshRequestUuid(State state) throws SQLException {
        PurchaseRequest request = request(Kind.UPGRADE);
        Receipt receipt = purchases.enqueue(request);
        if (state == State.APPLIED) {
            pay(receipt);
            purchases.complete(receipt.eventId());
        } else if (state == State.REVIEW_REQUIRED) purchases.markReview(receipt.eventId(), "FIXTURE_REVIEW");
        Receipt expected = purchases.findByRequest(request.requestId()).orElseThrow();
        assertEquals(state, expected.state());
        assertEquals(expected, purchases.enqueue(retry(request)));
        assertEquals(expected, purchases.enqueue(request));
        assertEquals(1, count("npc_economy_events"));
        assertEquals(1, count("npc_purchase_intents"));
    }

    @ParameterizedTest
    @EnumSource(value = Kind.class, names = {"UPGRADE", "MAINTAIN", "EVICT"})
    void failedChargeDeclinesWithoutEffectAndOnlyFreshUuidCanRetry(Kind kind) throws SQLException {
        PurchaseRequest request = request(kind);
        Receipt first = purchases.enqueue(request);
        decline(first);
        Receipt declined = purchases.complete(first.eventId()).orElseThrow();
        assertEquals(State.DECLINED, declined.state());
        assertEquals(declined, purchases.enqueue(request));
        assertNoEffect();
        assertArrayEquals(new long[]{SEED, 0, 0}, properties.fundingOf(property));

        Receipt fresh = purchases.enqueue(retry(request));
        assertNotEquals(first.eventId(), fresh.eventId());
        assertState(State.WAITING, fresh);
        pay(fresh);
        assertEquals(State.APPLIED, purchases.complete(fresh.eventId()).orElseThrow().state());
        assertEquals(declined, purchases.enqueue(request), "old declined UUID never aliases its successful retry");
        assertEquals(2, count("npc_economy_events"));
        assertEquals(2, count("npc_purchase_intents"));
        assertArrayEquals(new long[]{SEED, request.cents(), 0}, properties.fundingOf(property));
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 10_000})
    void failedLedgerWithReportedMovementRequiresReviewAndCannotRecharge(long paidCents) throws SQLException {
        Receipt receipt = purchases.enqueue(request(Kind.UPGRADE));
        assertTrue(events.claim(receipt.eventId()));
        events.finalizeFailed(receipt.eventId());
        execute("UPDATE npc_economy_events SET paid_amount_cents=" + paidCents + " WHERE event_id=" + receipt.eventId());

        Receipt review = purchases.complete(receipt.eventId()).orElseThrow();
        assertEquals(State.REVIEW_REQUIRED, review.state());
        assertEquals("FAILED_WITH_MOVEMENT", review.outcome());
        assertEquals(review, purchases.enqueue(retry(receipt.request())));
        assertEquals(review, purchases.complete(receipt.eventId()).orElseThrow());
        assertEquals(1, count("npc_economy_events"));
        assertEquals(1, count("npc_purchase_intents"));
        assertNoEffect();
        assertArrayEquals(new long[]{SEED, 0, 0}, properties.fundingOf(property));
    }

    @Test
    void intentInsertionFailureRollsBackTheAlreadyInsertedMoneyEvent() throws SQLException {
        execute("""
                CREATE TRIGGER purchase_insert_failure AFTER INSERT ON npc_purchase_intents FOR EACH ROW
                BEGIN
                  UPDATE npc_properties SET condition_score=99 WHERE property_id=NEW.property_id;
                  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='purchase enqueue rollback fixture';
                END
                """);
        PurchaseRequest request = request(Kind.UPGRADE);
        SQLException failure = assertThrows(SQLException.class, () -> purchases.enqueue(request));
        assertEquals("45000", failure.getSQLState());
        assertEquals(0, count("npc_economy_events"));
        assertEquals(0, count("npc_purchase_intents"));
        assertNoEffect();
        execute("DROP TRIGGER purchase_insert_failure");
        assertState(State.WAITING, purchases.enqueue(request));
    }

    @ParameterizedTest
    @EnumSource(value = Kind.class, names = {"UPGRADE", "MAINTAIN", "EVICT"})
    void pendingAndClaimedCannotGrantEffectsAndPaidAppliesOnceAcrossRepositoryRecreation(Kind kind)
            throws SQLException {
        Receipt waiting = purchases.enqueue(request(kind));
        purchases.complete(waiting.eventId());
        assertState(State.WAITING, waiting);
        assertNoEffect();
        Handle evictionPayment = null;
        if (kind == Kind.EVICT) evictionPayment = beginEvictionPayment(waiting);
        else assertTrue(events.claim(waiting.eventId()));
        purchases.complete(waiting.eventId());
        assertState(State.WAITING, waiting);
        assertNoEffect();
        if (kind == Kind.EVICT) finishEvictionPayment(evictionPayment, waiting.quotedCents());
        else events.finalizePaid(waiting.eventId(), waiting.quotedCents());
        Receipt applied = purchases.complete(waiting.eventId()).orElseThrow();
        assertEquals(State.APPLIED, applied.state());
        assertNotNull(applied.outcome());
        assertFalse(applied.outcome().isBlank());
        assertAppliedEffect(kind);

        purchases = purchasesOn(database);
        assertEquals(applied, purchases.findByRequest(waiting.request().requestId()).orElseThrow());
        assertEquals(applied, purchases.complete(waiting.eventId()).orElseThrow());
        assertEquals(applied, purchases.enqueue(retry(waiting.request())));
        assertAppliedEffect(kind);
        assertArrayEquals(new long[]{SEED, waiting.quotedCents(), 0}, properties.fundingOf(property));
        assertEquals(1, count("npc_purchase_intents"));
    }

    @Test
    void upgradesRequireSequentialLevelsAndAccumulateOnlyTheQuotedAuditCosts() throws SQLException {
        long total = 0;
        for (int level = 1; level <= 3; level++) {
            PurchaseRequest request = new PurchaseRequest(UUID.randomUUID(), property, owner, 0, Kind.UPGRADE,
                    UpgradeType.ADVERTISING, level, null, DAY);
            Receipt receipt = purchases.enqueue(request);
            assertEquals(level - 1, upgrades.advertisingLevel(property));
            pay(receipt);
            assertEquals(State.APPLIED, purchases.complete(receipt.eventId()).orElseThrow().state());
            purchases.complete(receipt.eventId());
            total += request.cents();
            assertEquals(level, upgrades.advertisingLevel(property));
            assertEquals(total, upgradeCost(UpgradeType.ADVERTISING));
            assertArrayEquals(new long[]{SEED, total, 0}, properties.fundingOf(property));
        }
        assertEquals(85_000, upgradeCost(UpgradeType.ADVERTISING));
        assertEquals(3, count("npc_purchase_intents"));
        assertEquals(1, count("npc_upgrades"));
    }

    @Test
    void levelOneOfDifferentUpgradeTypesCannotCollide() throws SQLException {
        Receipt advertising = purchases.enqueue(request(Kind.UPGRADE));
        Receipt facilities = purchases.enqueue(new PurchaseRequest(UUID.randomUUID(), property, owner, 0,
                Kind.UPGRADE, UpgradeType.FACILITIES, 1, null, DAY));
        assertNotEquals(advertising.eventId(), facilities.eventId());
        pay(advertising);
        pay(facilities);
        purchases.complete(advertising.eventId());
        purchases.complete(facilities.eventId());
        assertEquals(1, upgrades.advertisingLevel(property));
        assertEquals(1, upgrades.facilitiesLevel(property));
        assertEquals(10_000, upgradeCost(UpgradeType.ADVERTISING));
        assertEquals(15_000, upgradeCost(UpgradeType.FACILITIES));
        assertArrayEquals(new long[]{SEED, 25_000, 0}, properties.fundingOf(property));
        assertEquals(2, count("npc_economy_events"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, 3})
    void paidUpgradeMustStillHaveItsExactPreviousLevel(int changedLevel) throws SQLException {
        upgrades.setLevel(property, "ADVERTISING", 1, 10_000);
        Receipt receipt = purchases.enqueue(new PurchaseRequest(UUID.randomUUID(), property, owner, 0,
                Kind.UPGRADE, UpgradeType.ADVERTISING, 2, null, DAY));
        pay(receipt);
        upgrades.setLevel(property, "ADVERTISING", changedLevel, 0);
        assertEquals(State.REVIEW_REQUIRED, purchases.complete(receipt.eventId()).orElseThrow().state());
        assertEquals(changedLevel, upgrades.advertisingLevel(property));
        assertEquals(10_000, upgradeCost(UpgradeType.ADVERTISING));
        assertEquals("PAID", eventState(receipt.eventId()));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 80, 95, 98, 99})
    void maintenanceAddsFiveClampsAtOneHundredAndDeduplicatesByDay(int startingCondition) throws SQLException {
        properties.setCondition(property, startingCondition);
        PurchaseRequest request = request(Kind.MAINTAIN);
        Receipt receipt = purchases.enqueue(request);
        assertEquals(receipt, purchases.enqueue(retry(request)));
        pay(receipt);
        Receipt applied = purchases.complete(receipt.eventId()).orElseThrow();
        assertEquals(State.APPLIED, applied.state());
        assertEquals(Math.min(100, startingCondition + 5), condition());
        assertEquals(applied, purchases.enqueue(retry(request)), "full condition must not invalidate a receipt retry");
        assertEquals(applied, purchases.complete(receipt.eventId()).orElseThrow());
        assertEquals(Math.min(100, startingCondition + 5), condition());

        properties.setCondition(property, 80);
        assertEquals(applied, purchases.enqueue(retry(request)), "later damage cannot reopen today's paid action");
        assertEquals(80, condition());
        Receipt tomorrow = purchases.enqueue(new PurchaseRequest(UUID.randomUUID(), property, owner, 0,
                Kind.MAINTAIN, null, null, null, DAY.plusDays(1)));
        assertNotEquals(receipt.eventId(), tomorrow.eventId());
        pay(tomorrow);
        purchases.complete(tomorrow.eventId());
        assertEquals(85, condition());
        assertArrayEquals(new long[]{SEED, 2_000, 0}, properties.fundingOf(property));
        assertEquals(2, count("npc_purchase_intents"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"moved", "deleted-and-replaced"})
    void evictionNeverTargetsTheReplacementOccupantOrAnOriginalTenantAtAnotherProperty(String fixture)
            throws SQLException {
        Receipt receipt = purchases.enqueue(request(Kind.EVICT));
        pay(receipt);
        UUID destination = UUID.randomUUID();
        enroll(destination);
        if (fixture.equals("moved")) tenants.moveTenant(tenant, destination, 0, DAY.toString());
        else tenants.delete(tenant);
        UUID replacement = UUID.randomUUID();
        tenants.insert(new Tenant(replacement, "Replacement", property, 0, "STANDARD", "GENERIC", 50));
        assertEquals(State.REVIEW_REQUIRED, purchases.complete(receipt.eventId()).orElseThrow().state());
        assertEquals(List.of(replacement), tenants.listByProperty(property).stream().map(Tenant::tenantId).toList());
        assertEquals(fixture.equals("moved") ? List.of(tenant) : List.of(),
                tenants.listByProperty(destination).stream().map(Tenant::tenantId).toList());
        assertState(State.REVIEW_REQUIRED, receipt);
    }

    @Test
    void damagedIntentCannotSubstituteAnotherLegitimateTenantAtTheSamePropertyAndFee() throws SQLException {
        UUID otherTenant = UUID.randomUUID();
        tenants.insert(new Tenant(otherTenant, "Other Tenant", property, 1, "STANDARD", "GENERIC", 50));
        PurchaseRequest request = request(Kind.EVICT);
        Receipt receipt = purchases.enqueue(request);
        pay(receipt);
        PurchaseRequest substitution = purchases.quoteEviction(request.requestId(), property, owner, 0,
                otherTenant, DAY);
        execute("UPDATE npc_purchase_intents SET tenant_id=?,action_key=?,evict_tenancy_revision=?,evict_slot=? "
                        + "WHERE event_id=?", otherTenant, substitution.actionKey(),
                substitution.eviction().tenancyRevision(), substitution.eviction().slot(), receipt.eventId());
        Receipt damaged = purchases.findByRequest(request.requestId()).orElseThrow();
        assertEquals(otherTenant, damaged.request().tenantId());
        assertEquals(substitution, damaged.request());
        assertEquals(request.cents(), damaged.request().cents(), "price checks alone cannot detect substitution");
        assertEquals(request.source(), damaged.request().source());

        Receipt review = purchases.complete(receipt.eventId()).orElseThrow();
        assertEquals(State.REVIEW_REQUIRED, review.state());
        assertEquals(review, purchases.complete(receipt.eventId()).orElseThrow());
        assertEquals(List.of(tenant, otherTenant),
                tenants.listByProperty(property).stream().map(Tenant::tenantId).toList());
        assertEquals(80, condition());
        assertEquals(0, upgrades.totalLevels(property));
        assertEquals(request.eventKey(), scalar(
                "SELECT idempotency_key FROM npc_economy_events WHERE event_id=?", receipt.eventId()));
        assertEquals("PAID", eventState(receipt.eventId()));
        assertArrayEquals(new long[]{SEED, receipt.quotedCents(), 0}, properties.fundingOf(property));
        assertEquals(1, count("npc_economy_events"));
        assertEquals(1, count("npc_purchase_intents"));
    }

    @ParameterizedTest
    @EnumSource(value = Kind.class, names = {"UPGRADE", "MAINTAIN", "EVICT"})
    void failureAfterBenefitButBeforeAppliedReceiptRollsBackTheWholeBenefitTransaction(Kind kind)
            throws SQLException {
        Receipt receipt = purchases.enqueue(request(kind));
        pay(receipt);
        String visibleEffect = switch (kind) {
            case UPGRADE -> "EXISTS (SELECT 1 FROM npc_upgrades WHERE property_id=NEW.property_id "
                    + "AND upgrade_type='ADVERTISING' AND level=1 AND purchase_cost_cents=10000)";
            case MAINTAIN -> "EXISTS (SELECT 1 FROM npc_properties WHERE property_id=NEW.property_id AND condition_score=85)";
            case REPAIR, INSURANCE -> throw new AssertionError("Typed benefit fault injection has separate focused integration tests");
            case EVICT -> "EXISTS (SELECT 1 FROM npc_tenants WHERE tenant_id=NEW.tenant_id "
                    + "AND property_id IS NULL AND slot IS NULL AND row_version=1)";
        };
        execute("CREATE TRIGGER purchase_apply_failure BEFORE UPDATE ON npc_purchase_intents FOR EACH ROW BEGIN "
                + "IF NEW.state='APPLIED' THEN IF " + visibleEffect + " THEN "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='failure after purchase effect'; "
                + "ELSE SIGNAL SQLSTATE '45001' SET MESSAGE_TEXT='receipt written before effect'; END IF; END IF; END");
        SQLException failure = assertThrows(SQLException.class, () -> purchases.complete(receipt.eventId()));
        assertEquals("45000", failure.getSQLState(), "fixture must observe the effect before forcing rollback");
        assertState(State.WAITING, receipt);
        assertNoEffect();
        assertEquals("PAID", eventState(receipt.eventId()), "the money acknowledgement precedes this transaction");
        assertArrayEquals(new long[]{SEED, receipt.quotedCents(), 0}, properties.fundingOf(property));
        execute("DROP TRIGGER purchase_apply_failure");
        assertEquals(State.APPLIED, purchases.complete(receipt.eventId()).orElseThrow().state());
        purchases.complete(receipt.eventId());
        assertAppliedEffect(kind);
        try (Connection c = database.getConnection()) {
            assertTrue(c.getAutoCommit(), "the pool must remain usable after rollback");
        }
    }

    @ParameterizedTest
    @CsvSource({"UPGRADE,owner", "UPGRADE,epoch", "UPGRADE,disabled", "UPGRADE,no-owner",
            "MAINTAIN,owner", "MAINTAIN,epoch", "MAINTAIN,disabled", "MAINTAIN,no-owner",
            "EVICT,owner", "EVICT,epoch", "EVICT,disabled", "EVICT,no-owner"})
    void paidPurchaseWithChangedOwnershipOrEnrollmentRequiresReview(Kind kind, String drift) throws SQLException {
        Receipt receipt = purchases.enqueue(request(kind));
        pay(receipt);
        switch (drift) {
            case "owner" -> execute("UPDATE npc_properties SET owner_uuid=? WHERE property_id=?", UUID.randomUUID(), property);
            case "epoch" -> properties.applyOwnerChange(property, owner, owner, 1, "fixture");
            case "disabled" -> properties.setEnabled(property, false);
            case "no-owner" -> execute("UPDATE npc_properties SET owner_uuid=NULL WHERE property_id=?", property);
            default -> throw new AssertionError(drift);
        }
        Receipt review = purchases.complete(receipt.eventId()).orElseThrow();
        assertEquals(State.REVIEW_REQUIRED, review.state());
        assertEquals(review, purchases.complete(receipt.eventId()).orElseThrow());
        assertNoEffect();
        assertEquals("PAID", eventState(receipt.eventId()));
        assertArrayEquals(new long[]{SEED, receipt.quotedCents(), 0}, properties.fundingOf(property));
    }

    @ParameterizedTest
    @EnumSource(value = Kind.class, names = {"UPGRADE", "MAINTAIN", "EVICT"})
    void ownershipLossAndRestorationCannotResurrectAnOldEpochPurchase(Kind kind) throws SQLException {
        PurchaseRequest request = request(kind);
        Receipt old = purchases.enqueue(request);
        pay(old);
        properties.applyOwnerChange(property, owner, null, 1, "fixture-loss");
        properties.applyOwnerChange(property, null, owner, 2, "fixture-restoration");
        assertEquals(State.REVIEW_REQUIRED, purchases.complete(old.eventId()).orElseThrow().state());
        assertNoEffect();
        PurchaseRequest current = new PurchaseRequest(UUID.randomUUID(), property, owner, 2,
                kind, request.upgrade(), request.targetLevel(), request.tenantId(), DAY,
                request.repair(), request.eviction());
        if (kind == Kind.EVICT) {
            assertThrows(SQLException.class, () -> purchases.enqueue(current),
                    "ownership restoration cannot bypass an unresolved paid eviction for this resident");
            assertState(State.REVIEW_REQUIRED, old);
            assertNoEffect();
            assertEquals(1, count("npc_economy_events"));
            return;
        }
        Receipt fresh = purchases.enqueue(current);
        assertNotEquals(old.eventId(), fresh.eventId(), "action deduplication is scoped to ownership epoch");
        pay(fresh);
        assertEquals(State.APPLIED, purchases.complete(fresh.eventId()).orElseThrow().state());
        assertAppliedEffect(kind);
        assertState(State.REVIEW_REQUIRED, old);
        assertEquals(2, count("npc_economy_events"));
    }

    @ParameterizedTest
    @EnumSource(value = Kind.class, names = {"UPGRADE", "MAINTAIN", "EVICT"})
    void startupAmbiguityRequiresReviewAndCannotBeRechargedOrApplied(Kind kind) throws SQLException {
        Receipt receipt = purchases.enqueue(request(kind));
        assertTrue(events.claim(receipt.eventId()));
        assertEquals(1, events.markClaimedAsAmbiguousOnStartup());
        assertEquals("AMBIGUOUS", eventState(receipt.eventId()));
        Receipt review = purchases.complete(receipt.eventId()).orElseThrow();
        assertEquals(State.REVIEW_REQUIRED, review.state());
        assertEquals(review, purchases.enqueue(retry(receipt.request())));
        assertEquals(review, purchases.complete(receipt.eventId()).orElseThrow());
        assertFalse(events.claim(receipt.eventId()));
        assertNoEffect();
        assertEquals(1, count("npc_economy_events"));
        assertArrayEquals(new long[]{SEED, 0, 0}, properties.fundingOf(property));
    }

    @Test
    void explicitReviewIsDurableAndDoesNotRewriteTheLedgerOrACompletedReceipt() throws SQLException {
        Receipt receipt = purchases.enqueue(request(Kind.MAINTAIN));
        purchases.markReview(receipt.eventId(), "FIXTURE_REVIEW");
        Receipt review = purchases.findByRequest(receipt.request().requestId()).orElseThrow();
        assertEquals(State.REVIEW_REQUIRED, review.state());
        assertEquals("FIXTURE_REVIEW", review.outcome());
        assertEquals("PENDING", eventState(receipt.eventId()));
        pay(receipt);
        purchases = purchasesOn(database);
        assertEquals(review, purchases.complete(receipt.eventId()).orElseThrow());
        assertEquals(review, purchases.enqueue(retry(receipt.request())));
        assertNoEffect();

        Receipt upgrade = purchases.enqueue(request(Kind.UPGRADE));
        pay(upgrade);
        Receipt applied = purchases.complete(upgrade.eventId()).orElseThrow();
        purchases.markReview(upgrade.eventId(), "LATE_CALLBACK");
        assertEquals(applied, purchases.findByRequest(upgrade.request().requestId()).orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"key", "key-case", "key-space", "source", "unknown-source", "direction",
            "unknown-direction", "requested-amount",
            "paid-amount", "null-paid", "property", "null-property", "payer", "null-payer", "epoch",
            "null-epoch", "benefit", "null-benefit", "day", "quoted-amount"})
    void paidEventMustMatchEveryFieldOfItsOriginalIntent(String corruption) throws SQLException {
        Receipt receipt = purchases.enqueue(request(Kind.UPGRADE));
        pay(receipt);
        String assignment = switch (corruption) {
            case "key" -> "idempotency_key='purchase|wrong'";
            case "key-case" -> "idempotency_key=UPPER(idempotency_key)";
            case "key-space" -> "idempotency_key=CONCAT(idempotency_key,' ')";
            case "source" -> "source='npc-property-repair'";
            case "unknown-source" -> "source='UNKNOWN'";
            case "direction" -> "direction='TRANSFER_TO_PUBLIC'";
            case "unknown-direction" -> "direction='UNKNOWN'";
            case "requested-amount" -> "requested_amount_cents=requested_amount_cents+1";
            case "paid-amount" -> "paid_amount_cents=paid_amount_cents-1";
            case "null-paid" -> "paid_amount_cents=NULL";
            case "property" -> "property_id=UNHEX('ffffffffffffffffffffffffffffffff')";
            case "null-property" -> "property_id=NULL";
            case "payer" -> "counterparty_uuid=UNHEX('ffffffffffffffffffffffffffffffff')";
            case "null-payer" -> "counterparty_uuid=NULL";
            case "epoch" -> "ownership_epoch=ownership_epoch+1";
            case "null-epoch" -> "ownership_epoch=NULL";
            case "benefit" -> "benefit_unit_id=UNHEX('ffffffffffffffffffffffffffffffff')";
            case "null-benefit" -> "benefit_unit_id=NULL";
            case "day" -> "economy_day='2026-09-07'";
            case "quoted-amount" -> "quoted_amount_cents=quoted_amount_cents+1";
            default -> throw new AssertionError(corruption);
        };
        String table = corruption.equals("quoted-amount") ? "npc_purchase_intents" : "npc_economy_events";
        // Deliberately damage already acknowledged evidence, never manufacture a successful payment.
        execute("UPDATE " + table + " SET " + assignment + " WHERE event_id=?", receipt.eventId());
        Receipt review = purchases.complete(receipt.eventId()).orElseThrow();
        assertEquals(State.REVIEW_REQUIRED, review.state());
        assertEquals(review, purchases.complete(receipt.eventId()).orElseThrow());
        assertNoEffect();
        assertEquals("PAID", eventState(receipt.eventId()));
        assertArrayEquals(new long[]{SEED, receipt.quotedCents(), 0}, properties.fundingOf(property));
    }

    @ParameterizedTest
    @ValueSource(strings = {"upgrade-type", "upgrade-level", "matching-underquote"})
    void schemaValidDamagedMetadataCannotBuyAnUnquotedBenefit(String corruption) throws SQLException {
        Receipt receipt = purchases.enqueue(request(Kind.UPGRADE));
        pay(receipt);
        switch (corruption) {
            case "upgrade-type" -> execute("UPDATE npc_purchase_intents SET upgrade_type='FACILITIES', "
                    + "action_key='UPGRADE|FACILITIES|1' WHERE event_id=?", receipt.eventId());
            case "upgrade-level" -> {
                upgrades.setLevel(property, "ADVERTISING", 1, 10_000);
                execute("UPDATE npc_purchase_intents SET target_level=2,action_key='UPGRADE|ADVERTISING|2' "
                        + "WHERE event_id=?", receipt.eventId());
            }
            case "matching-underquote" -> {
                execute("UPDATE npc_purchase_intents SET quoted_amount_cents=1 WHERE event_id=?", receipt.eventId());
                execute("UPDATE npc_economy_events SET requested_amount_cents=1,paid_amount_cents=1 WHERE event_id=?",
                        receipt.eventId());
            }
            default -> throw new AssertionError(corruption);
        }
        try {
            assertEquals(State.REVIEW_REQUIRED, purchases.complete(receipt.eventId()).orElseThrow().state(),
                    "event/intent agreement alone must not authorize a different fixed-price benefit");
        } catch (SQLException rejectedMetadata) {
            assertEquals("WAITING", scalar("SELECT state FROM npc_purchase_intents WHERE event_id=?", receipt.eventId()));
        }
        assertEquals(corruption.equals("upgrade-level") ? 1 : 0, upgrades.advertisingLevel(property));
        assertEquals(corruption.equals("upgrade-level") ? 10_000 : 0, upgradeCost(UpgradeType.ADVERTISING));
        assertEquals(0, upgrades.facilitiesLevel(property));
        assertEquals(0, upgradeCost(UpgradeType.FACILITIES));
        assertEquals(80, condition());
        assertArrayEquals(new long[]{SEED, receipt.quotedCents(), 0}, properties.fundingOf(property));
    }

    @Test
    void v1ResourceRemainsByteIdenticalApartFromCheckoutLineEndings() throws Exception {
        String original = resource("/db/migration/V1__initial_schema.sql").replace("\r\n", "\n");
        assertEquals("d78b4f60eadf76f34d69051bc8938cc704d740c3cfafe1299f8c07d8b7422356",
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(original.getBytes(StandardCharsets.UTF_8))),
                "purchase persistence belongs in additive V3, not a rewrite of shipped V1");
    }

    @Test
    void repeatedV3MigrationPreservesReceiptsAndExistingPropertyState() throws Exception {
        Receipt receipt = purchases.enqueue(request(Kind.UPGRADE));
        pay(receipt);
        Receipt applied = purchases.complete(receipt.eventId()).orElseThrow();
        applyResource(V3);
        applyResource(V3);
        purchases = purchasesOn(database);
        assertEquals(applied, purchases.findByRequest(receipt.request().requestId()).orElseThrow());
        assertEquals(applied, purchases.complete(receipt.eventId()).orElseThrow());
        assertAppliedEffect(Kind.UPGRADE);
        assertArrayEquals(new long[]{SEED, 10_000, 0}, properties.fundingOf(property));
        assertEquals(1, count("npc_purchase_intents"));
        assertEquals(1, count("npc_economy_events"));
    }

    @ParameterizedTest
    @EnumSource(value = EventSource.class, names = {"UPGRADE", "REPAIR", "EVICTION"})
    void legacyGenericPaidEventsNeverAcquireInventedPurchaseIntents(EventSource source) throws Exception {
        long legacy = events.insertPending(EconomyEvent.pending("legacy|" + UUID.randomUUID(), 1_000,
                source, property, owner, 0, owner, DAY.toString()));
        assertTrue(events.claim(legacy));
        events.finalizePaid(legacy, 1_000);
        applyResource(V3);
        purchases = purchasesOn(database);
        assertTrue(purchases.complete(legacy).isEmpty());
        assertTrue(purchases.selectReadyIds(100).isEmpty());
        assertEquals(0, count("npc_purchase_intents"));
        assertEquals("PAID", eventState(legacy));
        assertNoEffect();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "PAID"})
    void preexistingPurchaseKeyWithoutIntentCannotBeAdoptedOrChargedAgain(String state) throws SQLException {
        PurchaseRequest request = request(Kind.UPGRADE);
        long id = events.insertPending(EconomyEvent.pending(request.eventKey(), request.cents(), request.source(),
                property, owner, 0, owner, DAY.toString()));
        if (state.equals("PAID")) {
            assertTrue(events.claim(id));
            events.finalizePaid(id, request.cents());
        }
        assertThrows(SQLException.class, () -> purchases.enqueue(request));
        assertTrue(purchases.complete(id).isEmpty());
        assertEquals(0, count("npc_purchase_intents"));
        assertEquals(1, count("npc_economy_events"));
        assertEquals(state, eventState(id));
        assertNoEffect();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ownership_epoch=-1", "quoted_amount_cents=0", "quoted_amount_cents=-1",
            "state='UNKNOWN'", "kind='UNKNOWN'", "upgrade_type='UNKNOWN'", "upgrade_type=NULL",
            "target_level=NULL", "target_level=0", "target_level=4",
            "tenant_id=UNHEX('ffffffffffffffffffffffffffffffff')",
            "kind='MAINTAIN'", "kind='EVICT',upgrade_type=NULL,target_level=NULL,tenant_id=NULL"})
    void schemaRejectsMalformedPurchaseShapesWithoutDamagingOriginalReceipt(String assignment) throws SQLException {
        Receipt receipt = purchases.enqueue(request(Kind.UPGRADE));
        assertThrows(SQLException.class, () -> execute(
                "UPDATE npc_purchase_intents SET " + assignment + " WHERE event_id=?", receipt.eventId()));
        assertEquals(receipt, purchases.findByRequest(receipt.request().requestId()).orElseThrow());
        assertNoEffect();
    }

    @Test
    void schemaRequiresExistingEventUniqueRequestAndRestrictsDeletingEvidence() throws SQLException {
        Receipt first = purchases.enqueue(request(Kind.UPGRADE));
        Receipt second = purchases.enqueue(request(Kind.MAINTAIN));
        assertThrows(SQLException.class, () -> execute(
                "UPDATE npc_purchase_intents SET event_id=? WHERE event_id=?", Long.MAX_VALUE, first.eventId()));
        assertThrows(SQLException.class, () -> execute(
                "UPDATE npc_purchase_intents SET request_id=? WHERE event_id=?", first.request().requestId(), second.eventId()));
        assertThrows(SQLException.class, () -> execute(
                "UPDATE npc_purchase_intents SET event_id=? WHERE event_id=?", first.eventId(), second.eventId()));
        assertThrows(SQLException.class, () -> execute("DELETE FROM npc_economy_events WHERE event_id=?", first.eventId()));
        assertEquals(first, purchases.findByRequest(first.request().requestId()).orElseThrow());
        assertEquals(second, purchases.findByRequest(second.request().requestId()).orElseThrow());
        assertEquals(2, count("npc_economy_events"));
        assertEquals(2, count("npc_purchase_intents"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"kind='UNKNOWN'", "kind='upgrade'", "upgrade_type='UNKNOWN'",
            "upgrade_type='advertising'", "target_level=0", "ownership_epoch=-1", "state='UNKNOWN'",
            "economy_day='2026-02-30'", "economy_day='0000-01-01'", "action_key='UPGRADE|FACILITIES|1'",
            "quoted_amount_cents=0"})
    void corruptStoredReceiptFailsClosedOnReadEnqueueAndCompletion(String assignment) throws SQLException {
        Receipt receipt = purchases.enqueue(request(Kind.UPGRADE));
        pay(receipt);
        // Bypass CHECKs only on this fixture connection, restoring its session setting before pool return.
        try (Connection c = database.getConnection(); Statement st = c.createStatement()) {
            st.setQueryTimeout(3);
            st.execute("SET SESSION check_constraint_checks=OFF");
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE npc_purchase_intents SET " + assignment + " WHERE event_id=?")) {
                ps.setQueryTimeout(3);
                ps.setLong(1, receipt.eventId());
                assertEquals(1, ps.executeUpdate());
            } finally {
                st.execute("SET SESSION check_constraint_checks=ON");
            }
        }
        assertThrows(SQLException.class, () -> purchases.findByRequest(receipt.request().requestId()));
        assertThrows(SQLException.class, () -> purchases.enqueue(receipt.request()));
        assertThrows(SQLException.class, () -> purchases.complete(receipt.eventId()));
        assertNoEffect();
        assertEquals("PAID", eventState(receipt.eventId()));
        assertEquals(1, count("npc_purchase_intents"));
        assertEquals(1, count("npc_economy_events"));
    }

    @Test
    void readyFeedIsOrderedFilteredAndHardBounded() throws SQLException {
        List<Long> ready = new ArrayList<>();
        for (int i = 0; i < 105; i++) {
            Receipt receipt = purchases.enqueue(new PurchaseRequest(UUID.randomUUID(), property, owner, 0,
                    Kind.MAINTAIN, null, null, null, DAY.plusDays(i)));
            pay(receipt);
            ready.add(receipt.eventId());
        }
        purchases.complete(ready.removeLast());
        purchases.markReview(ready.removeLast(), "FIXTURE_REVIEW");
        Receipt pending = purchases.enqueue(request(Kind.UPGRADE));
        Receipt claimed = purchases.enqueue(request(Kind.EVICT));
        assertTrue(events.claim(claimed.eventId()));
        for (int max : new int[]{Integer.MIN_VALUE, -1, 0}) assertTrue(purchases.selectReadyIds(max).isEmpty());
        assertEquals(ready.subList(0, 1), purchases.selectReadyIds(1));
        assertEquals(ready.subList(0, 20), purchases.selectReadyIds(20));
        for (int max : new int[]{100, 101, Integer.MAX_VALUE}) {
            assertEquals(ready.subList(0, 100), purchases.selectReadyIds(max));
        }
        assertState(State.WAITING, pending);
        assertState(State.WAITING, claimed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "AMBIGUOUS"})
    void unsuccessfulAcknowledgementsAreReadyUntilTheirReceiptBecomesTerminal(String state) throws SQLException {
        Receipt receipt = purchases.enqueue(request(Kind.MAINTAIN));
        assertTrue(events.claim(receipt.eventId()));
        if (state.equals("FAILED")) events.finalizeFailed(receipt.eventId());
        else assertEquals(1, events.markClaimedAsAmbiguousOnStartup());
        assertEquals(List.of(receipt.eventId()), purchases.selectReadyIds(10));
        purchases.complete(receipt.eventId());
        assertState(state.equals("FAILED") ? State.DECLINED : State.REVIEW_REQUIRED, receipt);
        assertTrue(purchases.selectReadyIds(10).isEmpty());
        assertNoEffect();
    }

    @Test
    void unknownRequestsAndEventsAreReadOnlyMisses() throws SQLException {
        assertTrue(purchases.findByRequest(UUID.randomUUID()).isEmpty());
        for (long id : new long[]{Long.MIN_VALUE, -1, 0, Long.MAX_VALUE}) {
            assertTrue(purchases.complete(id).isEmpty());
        }
        assertEquals(0, count("npc_economy_events"));
        assertEquals(0, count("npc_purchase_intents"));
        assertNoEffect();
    }

    @ParameterizedTest
    @EnumSource(value = Kind.class, names = {"UPGRADE", "MAINTAIN", "EVICT"})
    void concurrentEnqueueWithFreshUuidsSerializesAtThePropertyAndReturnsOneReceipt(Kind kind) throws Exception {
        PurchaseRequest first = request(kind);
        PurchaseRequest second = retry(first);
        List<Receipt> results = behindPropertyLock(() -> purchases.enqueue(first),
                () -> purchasesOn(database).enqueue(second));
        assertEquals(results.getFirst(), results.getLast());
        Receipt original = results.getFirst();
        assertTrue(original.request().equals(first) || original.request().equals(second));
        assertEquals(1, count("npc_economy_events"));
        assertEquals(1, count("npc_purchase_intents"));
        assertNoEffect();
        assertState(State.WAITING, original);
    }

    @Test
    void concurrentExactUuidRetriesAlsoPersistOneEventAndIntent() throws Exception {
        PurchaseRequest request = request(Kind.MAINTAIN);
        List<Receipt> results = behindPropertyLock(() -> purchases.enqueue(request),
                () -> purchasesOn(database).enqueue(request));
        assertEquals(results.getFirst(), results.getLast());
        assertEquals(1, count("npc_economy_events"));
        assertEquals(1, count("npc_purchase_intents"));
        assertNoEffect();
    }

    @Test
    void concurrentDifferentPayloadsForOneUuidCannotBothBeAccepted() throws Exception {
        PurchaseRequest first = request(Kind.UPGRADE);
        PurchaseRequest second = new PurchaseRequest(first.requestId(), property, owner, 0,
                Kind.UPGRADE, UpgradeType.FACILITIES, 1, null, DAY);
        List<Boolean> inserted = behindPropertyLock(() -> enqueueOrPayloadConflict(first),
                () -> enqueueOrPayloadConflict(second));
        assertEquals(1, inserted.stream().filter(Boolean::booleanValue).count());
        Receipt winner = purchases.findByRequest(first.requestId()).orElseThrow();
        assertTrue(winner.request().equals(first) || winner.request().equals(second));
        assertEquals(1, count("npc_economy_events"));
        assertEquals(1, count("npc_purchase_intents"));
        assertNoEffect();
    }

    @ParameterizedTest
    @EnumSource(value = Kind.class, names = {"UPGRADE", "MAINTAIN", "EVICT"})
    void concurrentPaidCompletionLocksBeforeEffectsAndAppliesExactlyOnce(Kind kind) throws Exception {
        Receipt receipt = purchases.enqueue(request(kind));
        pay(receipt);
        List<Receipt> results = behindPropertyLock(() -> purchases.complete(receipt.eventId()).orElseThrow(),
                () -> purchasesOn(database).complete(receipt.eventId()).orElseThrow());
        assertEquals(results.getFirst(), results.getLast());
        assertEquals(State.APPLIED, results.getFirst().state());
        assertAppliedEffect(kind);
        assertState(State.APPLIED, receipt);
        assertEquals(1, count("npc_purchase_intents"));
        assertArrayEquals(new long[]{SEED, receipt.quotedCents(), 0}, properties.fundingOf(property));
    }

    @Test
    void twoConcurrentUpgradeTypesStillRemainIndependent() throws Exception {
        PurchaseRequest advertising = request(Kind.UPGRADE);
        PurchaseRequest facilities = new PurchaseRequest(UUID.randomUUID(), property, owner, 0,
                Kind.UPGRADE, UpgradeType.FACILITIES, 1, null, DAY);
        List<Receipt> receipts = behindPropertyLock(() -> purchases.enqueue(advertising),
                () -> purchasesOn(database).enqueue(facilities));
        assertNotEquals(receipts.getFirst().eventId(), receipts.getLast().eventId());
        for (Receipt receipt : receipts) pay(receipt);
        List<Receipt> applied = behindPropertyLock(
                () -> purchases.complete(receipts.getFirst().eventId()).orElseThrow(),
                () -> purchasesOn(database).complete(receipts.getLast().eventId()).orElseThrow());
        assertTrue(applied.stream().allMatch(r -> r.state() == State.APPLIED));
        assertEquals(1, upgrades.advertisingLevel(property));
        assertEquals(1, upgrades.facilitiesLevel(property));
        assertEquals(10_000, upgradeCost(UpgradeType.ADVERTISING));
        assertEquals(15_000, upgradeCost(UpgradeType.FACILITIES));
        assertEquals(2, count("npc_purchase_intents"));
    }

    @Test
    void waitingCompletionRechecksOwnershipAfterTheBlockingTransactionCommits() throws Exception {
        Receipt receipt = purchases.enqueue(request(Kind.UPGRADE));
        pay(receipt);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Receipt> result;
            try (Connection blocker = database.getConnection()) {
                blocker.setAutoCommit(false);
                try {
                    try (PreparedStatement ps = blocker.prepareStatement(
                            "UPDATE npc_properties SET owner_uuid=NULL,ownership_epoch=1 WHERE property_id=?")) {
                        ps.setQueryTimeout(3);
                        ps.setBytes(1, Uuids.toBytes(property));
                        assertEquals(1, ps.executeUpdate());
                    }
                    result = worker.submit(() -> purchases.complete(receipt.eventId()).orElseThrow());
                    awaitPropertyLockWaiters(1);
                    assertFalse(result.isDone());
                    assertNoEffect();
                    blocker.commit();
                } finally {
                    blocker.rollback();
                }
            }
            assertEquals(State.REVIEW_REQUIRED, result.get(15, TimeUnit.SECONDS).state());
            assertState(State.REVIEW_REQUIRED, receipt);
            assertNoEffect();
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(15, TimeUnit.SECONDS), "ownership waiter terminated");
        }
    }

    @Test
    void identicalActionsAtDifferentPropertiesDoNotShareReceipts() throws SQLException {
        Receipt first = purchases.enqueue(request(Kind.MAINTAIN));
        UUID otherProperty = UUID.randomUUID();
        enroll(otherProperty);
        Receipt second = purchases.enqueue(new PurchaseRequest(UUID.randomUUID(), otherProperty, owner, 0,
                Kind.MAINTAIN, null, null, null, DAY));
        assertNotEquals(first.eventId(), second.eventId());
        pay(first);
        pay(second);
        purchases.complete(first.eventId());
        purchases.complete(second.eventId());
        assertEquals(85, condition());
        assertEquals("85", scalar("SELECT condition_score FROM npc_properties WHERE property_id=?", otherProperty));
        assertEquals(2, count("npc_purchase_intents"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"event", "intent"})
    void waitingCompletionRechecksMetadataChangedAfterItsInitialRead(String corruption) throws Exception {
        Receipt receipt = purchases.enqueue(request(Kind.UPGRADE));
        pay(receipt);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Receipt> result;
            try (Connection blocker = database.getConnection()) {
                blocker.setAutoCommit(false);
                try {
                    try (PreparedStatement lock = blocker.prepareStatement(
                            "SELECT property_id FROM npc_properties WHERE property_id=? FOR UPDATE")) {
                        lock.setQueryTimeout(3);
                        lock.setBytes(1, Uuids.toBytes(property));
                        try (ResultSet rs = lock.executeQuery()) { assertTrue(rs.next()); }
                    }
                    result = worker.submit(() -> purchases.complete(receipt.eventId()).orElseThrow());
                    awaitPropertyLockWaiters(1);
                    if (corruption.equals("event")) {
                        execute("UPDATE npc_economy_events SET paid_amount_cents=1 WHERE event_id=?", receipt.eventId());
                    } else {
                        execute("UPDATE npc_purchase_intents SET upgrade_type='FACILITIES', "
                                + "action_key='UPGRADE|FACILITIES|1' WHERE event_id=?", receipt.eventId());
                    }
                    assertFalse(result.isDone());
                } finally {
                    blocker.rollback();
                }
            }
            assertEquals(State.REVIEW_REQUIRED, result.get(15, TimeUnit.SECONDS).state());
            assertState(State.REVIEW_REQUIRED, receipt);
            assertNoEffect();
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(15, TimeUnit.SECONDS), "metadata waiter terminated");
        }
    }

    @Test
    void lockObservationRecognizesTimeoutWrappedReadsButStillRejectsWritesAndUnlockedReads() {
        String lockingRead = "SELECT owner_uuid FROM npc_properties WHERE property_id=? FOR UPDATE";
        for (String prefix : List.of("", "SET STATEMENT max_statement_time=5 FOR ",
                "  Set Statement max_statement_time = 5.0 For\n")) {
            assertTrue(isLockingRead(prefix + lockingRead));
            assertFalse(isLockingRead(prefix + "SELECT owner_uuid FROM npc_properties WHERE property_id=?"));
            assertFalse(isLockingRead(prefix + "UPDATE npc_properties SET condition_score=85 WHERE property_id=?"));
            assertFalse(isLockingRead(prefix + "SELECT 'for update' FROM npc_properties"));
        }
        assertTrue(isLockingRead("SET STATEMENT max_statement_time=5 FOR "
                + "SELECT owner_uuid FROM npc_properties WHERE property_id=_binary 'a\nb' FOR UPDATE"));
        assertFalse(isLockingRead("SET STATEMENT other_setting=5 FOR " + lockingRead));
        assertFalse(isLockingRead("SET STATEMENT max_statement_time=invalid FOR " + lockingRead));
    }

    private static boolean isLockingRead(String query) {
        // The MariaDB driver exposes setQueryTimeout's wrapper in PROCESSLIST.INFO.
        String sql = query.stripLeading().toLowerCase(Locale.ROOT).replaceFirst(
                "^set\\s+statement\\s+max_statement_time\\s*=\\s*\\d+(?:\\.\\d+)?\\s+for\\s+", "");
        return sql.matches("(?s)select\\s+.*\\s+for\\s+update\\s*");
    }

    private boolean enqueueOrPayloadConflict(PurchaseRequest request) throws SQLException {
        try {
            purchases.enqueue(request);
            return true;
        } catch (SQLException failure) {
            Receipt winner = purchases.findByRequest(request.requestId()).orElseThrow(() -> failure);
            assertNotEquals(request, winner.request(), "only a proven conflicting UUID may be rejected");
            assertEquals(State.WAITING, winner.state());
            return false;
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
                    try (PreparedStatement lock = blocker.prepareStatement(
                            "SELECT property_id FROM npc_properties WHERE property_id=? FOR UPDATE")) {
                        lock.setQueryTimeout(3);
                        lock.setBytes(1, Uuids.toBytes(property));
                        try (ResultSet rs = lock.executeQuery()) { assertTrue(rs.next()); }
                    }
                    first = workers.submit(left);
                    second = workers.submit(right);
                    for (String query : awaitPropertyLockWaiters(2)) {
                        assertTrue(isLockingRead(query),
                                "workers must block on the initial property lock, not an effect write: " + query);
                    }
                    assertFalse(first.isDone());
                    assertFalse(second.isDone());
                } finally {
                    blocker.rollback();
                }
            }
            return List.of(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS), "purchase workers terminated");
        }
    }

    private static List<String> awaitPropertyLockWaiters(int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        List<String> queries = new ArrayList<>();
        while (System.nanoTime() < deadline) {
            queries.clear();
            try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement("""
                    SELECT INFO FROM information_schema.PROCESSLIST
                    WHERE ID<>CONNECTION_ID() AND DB=? AND INFO LIKE '%npc_properties%' LIMIT 6
                    """)) {
                ps.setQueryTimeout(1);
                ps.setString(1, schemaName);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) queries.add(rs.getString(1));
                }
            }
            if (queries.size() == expected) return List.copyOf(queries);
            // Match the ledger IT: PROCESSLIST sees waiters that INNODB_TRX's cached snapshot can omit.
            Thread.sleep(20);
        }
        throw new AssertionError("expected " + expected + " property lock waiters, saw " + queries);
    }

    private void assertAppliedEffect(Kind kind) throws SQLException {
        assertEquals(kind == Kind.MAINTAIN ? 85 : 80, condition());
        assertEquals(kind == Kind.UPGRADE ? 1 : 0, upgrades.advertisingLevel(property));
        assertEquals(kind == Kind.UPGRADE ? 10_000 : 0, upgradeCost(UpgradeType.ADVERTISING));
        assertEquals(kind == Kind.EVICT ? List.of() : List.of(tenant),
                tenants.listByProperty(property).stream().map(Tenant::tenantId).toList());
        if (kind == Kind.EVICT) {
            assertEquals("1", scalar("SELECT row_version FROM npc_tenants WHERE tenant_id=? AND property_id IS NULL AND slot IS NULL", tenant));
            assertEquals("Purchase Tenant", scalar("SELECT display_name FROM npc_tenants WHERE tenant_id=?", tenant));
        }
    }

    @Test
    void coordinatedRentChecksSnapshotAndBoundsWithoutCreatingMoney() throws Exception {
        // The immediate setter reads pending rent state under the row lock; MigrationRunner always applies V31 first.
        applyResource("/db/migration/V31__rent_change_delay.sql");
        Property before = properties.findById(property).orElseThrow();
        assertThrows(PropertyRepository.ResumeRefused.class, () -> properties.setAskingRentExpected(before, 499));
        assertThrows(PropertyRepository.ResumeRefused.class, () -> properties.setAskingRentExpected(before, 1501));
        assertEquals(before, properties.findById(property).orElseThrow());
        Property updated = properties.setAskingRentExpected(before, 1200);
        assertEquals(1200, updated.askingRentCents());
        assertThrows(PropertyRepository.ResumeRefused.class, () -> properties.setAskingRentExpected(before, 1100));
        properties.applyOwnerChange(property, owner, UUID.randomUUID(), 1, "rent-test");
        assertThrows(PropertyRepository.ResumeRefused.class, () -> properties.setAskingRentExpected(updated, 1300));
        assertEquals(1200, properties.findById(property).orElseThrow().askingRentCents());
        assertEquals(0, count("npc_economy_events"));
        assertEquals(0, count("npc_purchase_intents"));
    }

    @Test
    void unaffordablePurchaseHasNoIntentAndDoesNotPoisonLaterFundedRequest() throws SQLException {
        PurchaseRequest q = request(Kind.UPGRADE);
        var refusal = assertThrows(PurchaseRepository.InsufficientFundsException.class,
                () -> purchases.enqueue(q, 10000, 1850));
        assertEquals(0, refusal.getSuppressed().length);
        assertEquals(0, count("npc_economy_events"));
        assertEquals(0, count("npc_purchase_intents"));
        assertEquals(State.WAITING, purchases.enqueue(q, 10000, 10000).state());
        assertEquals(1, count("npc_purchase_intents"));
    }

    @Test
    void operatorRefundPreservesOverdraftEvidenceAndCannotResolveTwiceDifferently() throws Exception {
        var q = new PurchaseRequest(UUID.randomUUID(), property, owner, 0, Kind.UPGRADE,
                UpgradeType.FACILITIES, 1, null, DAY);
        var intent = purchases.enqueue(q);
        var handle = new Handle(UUID.randomUUID(), intent.eventId(), UUID.randomUUID(), "a".repeat(64));
        journal.beginDebit(handle);
        var debit = new Observation(true,true,150.0,18.5,-131.5,false);
        assertEquals(TransferJournal.State.AMBIGUOUS,journal.recordDebit(handle,debit).state());
        UUID resolution = UUID.randomUUID();
        String file = "c".repeat(64);
        long before = Double.doubleToRawLongBits(-131.5), after = Double.doubleToRawLongBits(18.5);
        assertThrows(SQLException.class, () -> journal.resolveRefundedDebit(handle,resolution,"operator-test","b".repeat(64),file,before,Double.doubleToRawLongBits(19.0)));
        assertEquals(0,count("npc_transfer_resolutions"));
        var resolved = journal.resolveRefundedDebit(handle,resolution,"operator-test","b".repeat(64),file,before,after);
        assertEquals(TransferJournal.State.COMPENSATED,resolved.state());
        applyResource("/db/migration/V17__transfer_resolutions.sql");
        assertEquals(resolved,journal.find(intent.eventId()).orElseThrow());
        assertEquals(resolved,journal.resolveRefundedDebit(handle,resolution,"operator-test","b".repeat(64),file,before,after));
        assertThrows(SQLException.class, () -> journal.resolveRefundedDebit(handle,UUID.randomUUID(),"operator-test","b".repeat(64),"d".repeat(64),before,after));
        assertEquals(1,count("npc_transfer_resolutions"));
        assertEquals(debit,journal.observation(handle.attemptId(),com.prosperitysmp.npc.economy.TransferProof.Leg.DEBIT).orElseThrow());
        assertTrue(journal.observation(handle.attemptId(),com.prosperitysmp.npc.economy.TransferProof.Leg.CREDIT).isEmpty());
        var completed = purchases.complete(intent.eventId()).orElseThrow();
        assertEquals(State.DECLINED,completed.state());
        assertEquals("PAYMENT_COMPENSATED",completed.outcome());
        assertEquals(0,upgrades.facilitiesLevel(property));
    }

    @ParameterizedTest
    @ValueSource(longs = {750_000_000L, 800_000_000L})
    void configuredUpgradeAuditAccumulatesBeyondIntegerRangeWithoutRepeatingMoney(long price) throws Exception {
        assertEquals(price, com.prosperitysmp.npc.economy.TransferProof.toCents(price / 100.0).orElseThrow());
        var prices = new PurchasePrices(1000, Map.of(
                UpgradeType.ADVERTISING, List.of(price, price, price),
                UpgradeType.FACILITIES, PurchasePrices.DEFAULT.upgrades().get(UpgradeType.FACILITIES)));
        var priced = new PurchaseRepository(database, RESERVE, Clock.systemUTC(), ZoneId.of("America/Chicago"), 4,
                () -> false, prices);
        var balances = new java.util.HashMap<UUID, Double>(Map.of(owner, 100_000_000.0, RESERVE, 100_000_000.0));
        var calls = new ArrayList<String>();
        var vault = new TransferDispatchFixture.Vault(journal, balances, ignored -> false, calls, (leg, observation) -> { });
        for (int level = 1; level <= 3; level++) {
            var quote = priced.quotePrice(new PurchaseRequest(UUID.randomUUID(), property, owner, 0,
                    Kind.UPGRADE, UpgradeType.ADVERTISING, level, null, DAY));
            var waiting = priced.enqueue(quote);
            var handle = new Handle(UUID.randomUUID(), waiting.eventId(), UUID.randomUUID(), "a".repeat(64));
            assertEquals(TransferJournal.State.PAID, vault.submit(handle).toCompletableFuture().join().state());
            var beforeReplay = List.copyOf(calls);
            var applied = priced.complete(waiting.eventId()).orElseThrow();
            assertEquals(State.APPLIED, applied.state());
            assertEquals(applied, purchases.complete(waiting.eventId()).orElseThrow());
            assertEquals(quote, purchases.findByRequest(quote.requestId()).orElseThrow().request());
            assertEquals(beforeReplay, calls);
            assertEquals(level, upgrades.advertisingLevel(property));
            assertEquals(price * level, upgradeCost(UpgradeType.ADVERTISING));
            assertArrayEquals(new long[]{SEED, price * level, 0}, properties.fundingOf(property));
        }
        assertEquals(100_000_000.0 - 3 * price / 100.0, balances.get(owner));
        assertEquals(100_000_000.0 + 3 * price / 100.0, balances.get(RESERVE));
        assertEquals(3, count("npc_economy_events"));
        assertEquals(3, count("npc_transfer_attempts"));
    }

    private PurchaseRepository evictionsAt(long cents) {
        return new PurchaseRepository(database, RESERVE, Clock.systemUTC(), ZoneId.of("America/Chicago"), 4,
                () -> false, new PurchasePrices(1000, PurchasePrices.DEFAULT.upgrades(), cents));
    }

    @Test void evictionPriceComesFromTheLockedQuoteAndSurvivesChangedSettings() throws Exception {
        var first = evictionsAt(2375);
        var quote = first.quoteEviction(UUID.randomUUID(), property, owner, 0, tenant, DAY);
        assertEquals(2375, quote.cents());
        assertEquals(new EvictionQuote(0, 0), quote.eviction());
        assertThrows(PurchaseRepository.MaximumExceededException.class, () -> first.enqueue(quote, 2374, 10000));
        assertThrows(PurchaseRepository.InsufficientFundsException.class, () -> first.enqueue(quote, 2375, 2374));
        assertEquals(0, count("npc_economy_events"));
        assertThrows(PurchaseRepository.TargetUnavailableException.class, () -> first.enqueue(quote.withPriceCents(1000)));
        var receipt = first.enqueue(quote, 2375, 2375);
        var changed = evictionsAt(3000);
        assertEquals(3000, changed.quoteEviction(UUID.randomUUID(), property, owner, 0, tenant, DAY).cents());
        assertEquals(receipt, changed.findByRequest(quote.requestId()).orElseThrow());
        assertEquals(receipt, changed.enqueue(quote));
        pay(receipt);
        var applied = changed.complete(receipt.eventId()).orElseThrow();
        assertEquals(State.APPLIED, applied.state());
        assertEquals(quote, applied.request());
        assertEquals(2375, applied.quotedCents());
        assertEquals(applied, changed.complete(receipt.eventId()).orElseThrow());
        assertEquals(applied, changed.enqueue(quote));
        assertTrue(tenants.listByProperty(property).isEmpty());
        assertArrayEquals(new long[]{SEED, 2375, 0}, properties.fundingOf(property));
        assertEquals(1, count("npc_economy_events"));
        assertEquals(1, count("npc_purchase_intents"));
        assertEquals(1, count("npc_transfer_attempts"));
        assertEquals(1, count("npc_tenancy_departures"));
    }

    @Test void configuredEvictionDoesNotRemoveAChangedTenancyAfterPayment() throws Exception {
        var priced = evictionsAt(2375);
        var quote = priced.quoteEviction(UUID.randomUUID(), property, owner, 0, tenant, DAY);
        execute("UPDATE npc_tenants SET row_version=row_version+1 WHERE tenant_id=?", tenant);
        assertThrows(SQLException.class, () -> priced.enqueue(quote));
        assertEquals(0, count("npc_economy_events"));
        var fresh = priced.quoteEviction(UUID.randomUUID(), property, owner, 0, tenant, DAY);
        var receipt = priced.enqueue(fresh);
        pay(receipt);
        execute("UPDATE npc_tenants SET row_version=row_version+1 WHERE tenant_id=?", tenant);
        assertEquals(State.REVIEW_REQUIRED, priced.complete(receipt.eventId()).orElseThrow().state());
        assertEquals(List.of(tenant), tenants.listByProperty(property).stream().map(Tenant::tenantId).toList());
        assertEquals(0, count("npc_tenancy_departures"));
        assertEquals("PAID", eventState(receipt.eventId()));
        assertArrayEquals(new long[]{SEED, 2375, 0}, properties.fundingOf(property));
        assertEquals(State.REVIEW_REQUIRED, priced.complete(receipt.eventId()).orElseThrow().state());
        assertArrayEquals(new long[]{SEED, 2375, 0}, properties.fundingOf(property));
        assertEquals(1, count("npc_transfer_attempts"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void evictionPriceMigrationPreservesOldReceiptsAndRecoversEveryPartialRun(int cut) throws Exception {
        var old = purchases.enqueue(request(Kind.EVICT));
        execute("ALTER TABLE npc_purchase_intents DROP CONSTRAINT IF EXISTS ck_purchase_configured_price_v50");
        applyResource("/db/migration/V47__purchase_prices.sql");
        String path = "/db/migration/V50__eviction_price.sql";
        var statements = MigrationRunner.splitStatements(resource(path));
        assertEquals(2, statements.size());
        var priced = evictionsAt(2375);
        var quote = priced.quoteEviction(UUID.randomUUID(), property, owner, 0, tenant, DAY);
        // A declined old attempt permits a fresh request for the same tenancy.
        decline(old);
        purchases.complete(old.eventId()).orElseThrow();
        var prior = purchases.findByRequest(old.request().requestId()).orElseThrow();
        try {
            for (int i = 0; i < cut; i++) execute(statements.get(i));
            if (cut < 2) assertThrows(SQLException.class, () -> priced.enqueue(quote));
            assertEquals(prior, purchases.findByRequest(old.request().requestId()).orElseThrow());
        } finally {
            applyResource(path);
        }
        applyResource(path);
        assertEquals(prior, purchases.findByRequest(old.request().requestId()).orElseThrow());
        var current = priced.enqueue(quote);
        assertEquals(2375, current.quotedCents());
        assertThrows(SQLException.class, () -> execute(
                "UPDATE npc_purchase_intents SET evict_tenancy_revision=NULL,evict_slot=NULL WHERE event_id=?", current.eventId()));
        assertThrows(SQLException.class, () -> execute(
                "UPDATE npc_purchase_intents SET configured_price_cents=1 WHERE event_id=?", current.eventId()));
        assertEquals(current, priced.findByRequest(quote.requestId()).orElseThrow());
    }

    private PurchaseRepository purchasesAt(long maintenance, long advertising) {
        var prices = new PurchasePrices(maintenance, Map.of(
                UpgradeType.ADVERTISING, List.of(advertising, 25_000L, 50_000L),
                UpgradeType.FACILITIES, PurchasePrices.DEFAULT.upgrades().get(UpgradeType.FACILITIES)));
        return new PurchaseRepository(database, RESERVE, Clock.systemUTC(), ZoneId.of("America/Chicago"), 4,
                () -> false, prices);
    }

    @ParameterizedTest
    @EnumSource(value = Kind.class, names = {"MAINTAIN", "UPGRADE"})
    void configuredQuotesSurviveNewSettingsAndReconcileAtTheirAcceptedPrice(Kind kind) throws Exception {
        var first = purchasesAt(1750, 13700);
        var legacy = request(kind);
        var quote = first.quotePrice(legacy);
        long price = kind == Kind.MAINTAIN ? 1750 : 13700;
        assertEquals(price, quote.cents());
        assertNotEquals(legacy.eventKey(), quote.eventKey());
        var receipt = first.enqueue(quote);
        var changed = purchasesAt(2500, 18700);
        assertNotEquals(price, changed.quotePrice(legacy).cents());
        assertEquals(receipt, changed.findByRequest(quote.requestId()).orElseThrow());
        assertEquals(receipt, changed.enqueue(quote));
        var calls = new ArrayList<String>();
        var handle = new Handle(UUID.randomUUID(), receipt.eventId(), UUID.randomUUID(), "a".repeat(64));
        assertEquals(TransferJournal.State.PAID,
                TransferDispatchFixture.dispatch(journal, handle, calls, 1000.0).toCompletableFuture().join().state());
        var beforeCompletion = List.copyOf(calls);
        var applied = changed.complete(receipt.eventId()).orElseThrow();
        assertEquals(State.APPLIED, applied.state());
        assertEquals(price, applied.quotedCents());
        assertEquals(quote, applied.request());
        assertEquals(applied, changed.complete(receipt.eventId()).orElseThrow());
        assertEquals(beforeCompletion, calls);
        assertEquals(kind == Kind.MAINTAIN ? 85 : 80, condition());
        assertEquals(kind == Kind.UPGRADE ? 1 : 0, upgrades.advertisingLevel(property));
        assertEquals(kind == Kind.UPGRADE ? price : 0, upgradeCost(UpgradeType.ADVERTISING));
        assertArrayEquals(new long[]{SEED, price, 0}, properties.fundingOf(property));
        assertEquals(1, count("npc_economy_events"));
        assertEquals(1, count("npc_purchase_intents"));
    }

    @Test void staleOrInventedConfiguredPricesNeverCreateAPayment() throws Exception {
        var priced = purchasesAt(1750, 13700);
        var request = request(Kind.MAINTAIN);
        assertThrows(PurchaseRepository.TargetUnavailableException.class, () -> priced.enqueue(request));
        assertThrows(PurchaseRepository.TargetUnavailableException.class, () -> priced.enqueue(request.withPriceCents(1)));
        assertEquals(0, count("npc_purchase_intents"));
        assertEquals(0, count("npc_economy_events"));
        var quoted = priced.quotePrice(request);
        assertThrows(PurchaseRepository.MaximumExceededException.class, () -> priced.enqueue(quoted, 1749, 10000));
        assertThrows(PurchaseRepository.InsufficientFundsException.class, () -> priced.enqueue(quoted, 1750, 1749));
        assertEquals(0, count("npc_purchase_intents"));
        assertEquals(State.WAITING, priced.enqueue(quoted, 1750, 1750).state());
    }

    @ParameterizedTest
    @EnumSource(value = Kind.class, names = {"MAINTAIN", "UPGRADE"})
    void aConfiguredBenefitNeedsJournalProofNotJustAPaidLedgerLabel(Kind kind) throws Exception {
        var priced = purchasesAt(1750, 13700);
        var receipt = priced.enqueue(priced.quotePrice(request(kind)));
        pay(receipt);
        var held = priced.complete(receipt.eventId()).orElseThrow();
        assertEquals(State.REVIEW_REQUIRED, held.state());
        assertEquals("TRANSFER_PROOF_MISSING", held.outcome());
        assertNoEffect();
    }

    @Test void changingTheStoredCustomPriceCannotGrantADifferentBenefit() throws Exception {
        var priced = purchasesAt(1750, 13700);
        var receipt = priced.enqueue(priced.quotePrice(request(Kind.UPGRADE)));
        assertThrows(SQLException.class, () -> execute(
                "UPDATE npc_purchase_intents SET configured_price_cents=1 WHERE event_id=?", receipt.eventId()));
        pay(receipt);
        execute("UPDATE npc_purchase_intents SET configured_price_cents=1,quoted_amount_cents=1 WHERE event_id=?", receipt.eventId());
        execute("UPDATE npc_economy_events SET requested_amount_cents=1,paid_amount_cents=1 WHERE event_id=?", receipt.eventId());
        assertEquals(State.REVIEW_REQUIRED, priced.complete(receipt.eventId()).orElseThrow().state());
        assertNoEffect();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void priceMigrationReplaysAfterEveryPartialRunAndKeepsLegacyQuotes(int cut) throws Exception {
        var receipt = purchases.enqueue(request(Kind.MAINTAIN));
        execute("ALTER TABLE npc_purchase_intents DROP CONSTRAINT IF EXISTS ck_purchase_configured_price_v50");
        execute("ALTER TABLE npc_purchase_intents DROP CONSTRAINT IF EXISTS ck_purchase_configured_price");
        execute("ALTER TABLE npc_purchase_intents DROP COLUMN IF EXISTS configured_price_cents");
        assertEquals(receipt, purchases.findByRequest(receipt.request().requestId()).orElseThrow());
        String resource = "/db/migration/V47__purchase_prices.sql";
        try (InputStream in = PurchaseRepositoryIT.class.getResourceAsStream(resource)) {
            assertNotNull(in);
            var statements = MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            for (int i = 0; i < cut; i++) execute(statements.get(i));
        } finally {
            applyResource(resource);
        }
        applyResource(resource);
        applyResource("/db/migration/V50__eviction_price.sql");
        assertEquals(receipt, purchases.findByRequest(receipt.request().requestId()).orElseThrow());
        assertEquals(0, count("npc_transfer_attempts"));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void upgradeAuditMigrationReplaysAndPreservesLegacyValues(int cut) throws Exception {
        var receipt = purchases.enqueue(request(Kind.MAINTAIN));
        upgrades.setLevel(property, "ADVERTISING", 1, Integer.MAX_VALUE);
        String select = "SELECT CONCAT_WS('|',id,upgrade_type,level,purchase_cost_cents,installed_at) FROM npc_upgrades WHERE property_id=? AND upgrade_type='ADVERTISING'";
        String legacy = scalar(select, property);
        execute("ALTER TABLE npc_upgrades MODIFY COLUMN purchase_cost_cents INT NOT NULL DEFAULT 0");
        String path = "/db/migration/V49__upgrade_cost_width.sql";
        var statements = MigrationRunner.splitStatements(resource(path));
        assertEquals(1, statements.size());
        try {
            for (int i = 0; i < cut; i++) execute(statements.get(i));
            assertEquals(legacy, scalar(select, property));
            assertEquals(receipt, purchases.findByRequest(receipt.request().requestId()).orElseThrow());
        } finally {
            applyResource(path);
        }
        applyResource(path);
        assertEquals(legacy, scalar(select, property));
        assertEquals("bigint", scalar("SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='npc_upgrades' AND COLUMN_NAME='purchase_cost_cents'"));
        assertEquals("NO", scalar("SELECT IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='npc_upgrades' AND COLUMN_NAME='purchase_cost_cents'"));
        execute("UPDATE npc_upgrades SET purchase_cost_cents=purchase_cost_cents+750000000 WHERE property_id=? AND upgrade_type='ADVERTISING'", property);
        assertEquals((long) Integer.MAX_VALUE + 750_000_000L, upgradeCost(UpgradeType.ADVERTISING));
        execute("INSERT INTO npc_upgrades(property_id,upgrade_type) VALUES(?,'FACILITIES')", property);
        assertEquals(0, upgradeCost(UpgradeType.FACILITIES));
        assertEquals(receipt, purchases.findByRequest(receipt.request().requestId()).orElseThrow());
        assertEquals(0, count("npc_transfer_attempts"));
    }

    private void enroll(UUID id) throws SQLException {
        properties.insertEnrollment(new Property(id, WORLD, "purchase-" + id, "downtown", "room",
                2, 1_000, 1_000, owner, 0, 80, true), SEED);
    }

    private PurchaseRequest request(Kind kind) {
        // Keep invalid-target fixtures constructible so enqueue performs the evidence rejection.
        return new PurchaseRequest(UUID.randomUUID(), property, owner, 0, kind,
                kind == Kind.UPGRADE ? UpgradeType.ADVERTISING : null, kind == Kind.UPGRADE ? 1 : null,
                kind == Kind.EVICT ? tenant : null, DAY, null,
                kind == Kind.EVICT ? new EvictionQuote(0, 0) : null);
    }

    private static PurchaseRequest retry(PurchaseRequest r) {
        return new PurchaseRequest(UUID.randomUUID(), r.propertyId(), r.payerId(), r.ownershipEpoch(),
                r.kind(), r.upgrade(), r.targetLevel(), r.tenantId(), r.day(), r.repair(), r.eviction());
    }

    private void pay(Receipt receipt) throws SQLException {
        if (receipt.request().kind() == Kind.EVICT) {
            finishEvictionPayment(beginEvictionPayment(receipt), receipt.quotedCents());
        } else {
            assertTrue(events.claim(receipt.eventId()));
            events.finalizePaid(receipt.eventId(), receipt.quotedCents());
        }
        assertEquals("PAID", eventState(receipt.eventId()));
    }

    private Handle beginEvictionPayment(Receipt receipt) throws SQLException {
        Handle handle = new Handle(UUID.randomUUID(), receipt.eventId(), UUID.randomUUID(), "a".repeat(64));
        assertTrue(journal.beginDebit(handle).newlyStarted());
        return handle;
    }

    private void finishEvictionPayment(Handle handle, long cents) throws SQLException {
        double dollars = cents / 100.0;
        assertEquals(TransferJournal.State.CREDIT_READY, journal.recordDebit(handle,
                new Observation(true, true, dollars, 1_000.0, 1_000.0 - dollars, false)).state());
        assertTrue(journal.beginCredit(handle).newlyStarted());
        assertEquals(TransferJournal.State.PAID, journal.recordCredit(handle,
                new Observation(true, true, dollars, 2_000.0, 2_000.0 + dollars, false)).state());
    }

    private void decline(Receipt receipt) throws SQLException {
        if (receipt.request().kind() == Kind.EVICT) {
            assertEquals(TransferJournal.State.DECLINED, journal.recordDebit(beginEvictionPayment(receipt),
                    new Observation(true, false, 0.0, 1_000.0, 1_000.0, false)).state());
        } else {
            assertTrue(events.claim(receipt.eventId()));
            events.finalizeFailed(receipt.eventId());
        }
        assertEquals("FAILED", eventState(receipt.eventId()));
    }

    private void assertState(State state, Receipt receipt) throws SQLException {
        assertEquals(state, purchases.findByRequest(receipt.request().requestId()).orElseThrow().state());
        assertEquals(state.name(), scalar("SELECT state FROM npc_purchase_intents WHERE event_id=?", receipt.eventId()));
    }

    private void assertNoEffect() throws SQLException {
        assertEquals(80, condition());
        assertEquals(0, upgrades.totalLevels(property));
        assertEquals(List.of(tenant), tenants.listByProperty(property).stream().map(Tenant::tenantId).toList());
    }

    private int condition() throws SQLException {
        return Integer.parseInt(scalar("SELECT condition_score FROM npc_properties WHERE property_id=?", property));
    }

    private long upgradeCost(UpgradeType type) throws SQLException {
        return Long.parseLong(scalar("SELECT COALESCE(SUM(purchase_cost_cents),0) FROM npc_upgrades "
                + "WHERE property_id=? AND upgrade_type=?", property, type.name()));
    }

    private String eventState(long id) throws SQLException {
        return scalar("SELECT state FROM npc_economy_events WHERE event_id=?", id);
    }

    private static long count(String table) throws SQLException {
        if (!List.of("npc_purchase_intents", "npc_economy_events", "npc_upgrades", "npc_transfer_resolutions",
                "npc_transfer_attempts", "npc_tenancy_departures").contains(table)) {
            throw new IllegalArgumentException(table);
        }
        return Long.parseLong(scalar("SELECT COUNT(*) FROM " + table));
    }

    private static String scalar(String sql, Object... values) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(3);
            ps.setMaxRows(1);
            bind(ps, values);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), sql);
                return rs.getString(1);
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

    private static String resource(String path) throws Exception {
        try (InputStream in = PurchaseRepositoryIT.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void applyResource(String path) throws Exception {
        for (String sql : MigrationRunner.splitStatements(resource(path))) execute(sql);
    }
}

package com.prosperitysmp.npc.it;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.market.FiniteHousingPlanner.Assignment;
import com.prosperitysmp.npc.market.FiniteHousingPlanner.Market;
import com.prosperitysmp.npc.market.FiniteHousingPlanner.Remaining;
import com.prosperitysmp.npc.market.HousingMarketRepository;
import com.prosperitysmp.npc.market.HousingMarketSnapshot;
import com.prosperitysmp.npc.market.HousingMarketSnapshot.Home;
import com.prosperitysmp.npc.market.HousingMarketSnapshot.Participant;
import com.prosperitysmp.npc.market.HousingMarketSnapshot.Status;
import com.prosperitysmp.npc.market.HousingMoveRepository;
import com.prosperitysmp.npc.market.HousingMoveRepository.Receipt;
import com.prosperitysmp.npc.market.HousingMoveRequest;
import com.prosperitysmp.npc.market.HousingMoveRequest.Policy;
import com.prosperitysmp.npc.market.HousingPolicy;
import com.prosperitysmp.npc.market.HousingRegistry;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;

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
import java.sql.SQLTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Real SQL snapshot boundary; fixture policies do not choose runtime budgets or service scores. */
@Timeout(60)
class HousingMarketRepositoryIT {
    private static final UUID RESIDENT = new UUID(0, 17);
    private static final UUID PROPERTY = new UUID(0, 800);
    private static final UUID OWNER = new UUID(0, 900);
    private static final UUID WORLD = UUID.fromString("fedcba98-7654-3210-89ab-cdef01234567");
    private static final UUID OTHER_WORLD = new UUID(0, 600);
    private static final ZoneId ZONE = ZoneId.of("America/Chicago");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 7);
    private static final Instant NOW = DAY.atTime(12, 0).atZone(ZONE).toInstant();
    private static final Policy POLICY = new Policy(10, 5, 3, 7);
    private static final HousingPolicy STANDARD = housing(WORLD, "downtown", "room");
    private static final Set<String> MOVE_TABLES = Set.of("npc_tenants", "npc_housing_days", "npc_housing_moves");
    private static DB mariaDb;
    private static Database database;
    private static DatabaseSettings settings;
    private static List<String> tables;
    private MutableClock clock;

    @BeforeAll
    static void bootDatabase() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        try {
            mariaDb.start();
            String schema = "npc_hmarket_" + UUID.randomUUID().toString().replace("-", "");
            mariaDb.createDB(schema);
            settings = new DatabaseSettings("localhost", cfg.getPort(), schema, "root", "", 6, 10_000L);
            database = new Database();
            database.connect(settings);
            for (String migration : List.of("V1__initial_schema.sql", "V2__resident_identity.sql",
                    "V9__housing_registry.sql", "V10__housing_moves.sql", "V11__housing_history_order.sql")) {
                applyResource("/db/migration/" + migration);
            }
            tables = rows("SELECT TABLE_NAME AS name FROM information_schema.TABLES WHERE TABLE_SCHEMA=? "
                    + "AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME", schema).stream()
                    .map(row -> row.get("name")).toList();
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
    void freshFixture() throws SQLException {
        try (Connection c = database.getConnection()) {
            execute(c, "SET SESSION foreign_key_checks=OFF");
            try { for (String table : tables) execute(c, "DELETE FROM " + table); }
            finally { execute(c, "SET SESSION foreign_key_checks=ON"); }
        }
        execute("INSERT INTO npc_housing_registry_lock(singleton_id) VALUES (1)");
        clock = new MutableClock(NOW, ZoneOffset.UTC);
        resident(RESIDENT, STANDARD, DAY.minusDays(30));
        property(PROPERTY, WORLD, "downtown", "room");
        new ResidentRepository(database).introduce(RESIDENT, OWNER, NOW.minusSeconds(60), DAY);
        execute("INSERT INTO npc_property_funding(property_id,one_time_seed_cents,successful_cash_sinks_cents,"
                + "successful_cash_payouts_cents) VALUES (?,5000,4321,1234)", PROPERTY);
        execute("""
                INSERT INTO npc_economy_events(idempotency_key,requested_amount_cents,paid_amount_cents,source,
                  direction,property_id,counterparty_uuid,economy_day,state)
                VALUES ('hmarket-preservation',1200,1200,'RENT','PAYOUT',?,?,'2026-09-07','PAID')
                """, PROPERTY, OWNER);
        execute("INSERT INTO npc_audit_log(actor,action,detail) VALUES ('fixture','PRESERVE','Unrelated authored data')");
    }

    @Test
    void readAndQuotePreserveEveryColumnOfEveryTableWithoutPinningAnUnusedDay() throws SQLException {
        var before = data();
        HousingMarketSnapshot snapshot = read();
        assertEquals(DAY, snapshot.day());
        assertEquals(NOW, snapshot.capturedAt());
        assertEquals(POLICY, snapshot.policy());
        assertEquals(List.of(expectedHome(PROPERTY, WORLD, "downtown", "room", List.of(0, 1, 2, 3))), snapshot.homes());
        assertEquals(List.of(new Participant(new HousingRegistry.Member(RESIDENT, STANDARD, DAY.minusDays(30), 0, true),
                -1, DAY, Status.READY)), snapshot.participants());
        assertTrue(snapshot.admissions().isEmpty());
        assertEquals(new Remaining(10, Map.of(new Market(WORLD, "downtown"), 5), Map.of(PROPERTY, 3)), snapshot.remaining());
        UUID requestId = UUID.randomUUID();
        assertEquals(new HousingMoveRequest(requestId, RESIDENT, PROPERTY, OWNER, WORLD, "downtown", "room",
                3, 11, 0, -1, 3, DAY), snapshot.quote(requestId, assignment(snapshot, RESIDENT, PROPERTY, 3)));
        assertEquals(snapshot, read());
        assertEquals(before, data(), "reads, derivations and quotes must not freshen profiles, receipts, money or metadata");
        assertTrue(rows("SELECT * FROM npc_housing_days").isEmpty(), "a proposed policy is not a day pin");
    }

    @Test
    void proposedPolicyCanChangeUntilSuccessfulAdmissionPinsOnlyItsOwnEconomyDay() throws SQLException {
        Policy other = new Policy(8, 4, 2, 2);
        var before = data();
        assertEquals(other, market().read(DAY, other).policy());
        assertEquals(POLICY, read().policy());
        assertEquals(before, data());
        admit(RESIDENT, PROPERTY, 0, DAY, other);
        rejected("Housing day policy conflict", () -> market().read(DAY, POLICY));
        assertEquals(other, market().read(DAY, other).policy());
        clock.set(NOW.plusSeconds(86400));
        var pinned = data();
        HousingMarketSnapshot next = market().read(DAY.plusDays(1), POLICY);
        assertEquals(POLICY, next.policy());
        assertEquals(10, next.remaining().server());
        assertTrue(next.admissions().isEmpty());
        assertEquals(Status.OCCUPIED, participant(next, RESIDENT).status());
        assertEquals(pinned, data(), "historical policy must neither override nor pin the new day");
    }

    @Test
    void durableRemainingUsesRecordedWorldMarketAndPropertyDespiteDeparturesDisablingAndReclassification() throws SQLException {
        Policy quotas = new Policy(8, 4, 3, 2);
        UUID apartment = new UUID(0, 801), otherWorldHome = new UUID(0, 802), untouched = new UUID(0, 803);
        UUID second = new UUID(0, 18), third = new UUID(0, 19), fourth = new UUID(0, 20);
        property(apartment, WORLD, "downtown", "apartment");
        property(otherWorldHome, OTHER_WORLD, "downtown", "room");
        property(untouched, WORLD, "downtown", "room");
        resident(second, STANDARD, DAY);
        resident(third, housing(WORLD, "downtown", "apartment"), DAY);
        resident(fourth, housing(OTHER_WORLD, "downtown", "room"), DAY);
        List<Receipt> receipts = List.of(admit(RESIDENT, PROPERTY, 0, DAY, quotas),
                admit(second, PROPERTY, 2, DAY, quotas), admit(third, apartment, 1, DAY, quotas),
                admit(fourth, otherWorldHome, 3, DAY, quotas));
        for (UUID id : List.of(RESIDENT, second, third, fourth)) vacate(id);
        new HousingRegistry(database).setEnabled(second, 0, false);
        new HousingRegistry(database).setEnabled(third, 0, false);
        execute("UPDATE npc_properties SET enabled=FALSE WHERE property_id=?", apartment);
        execute("UPDATE npc_properties SET market_id='uptown',class_key='loft',row_version=12 WHERE property_id=?", PROPERTY);
        var before = data();
        HousingMarketSnapshot snapshot;
        try (Database restarted = new Database()) {
            restarted.connect(settings);
            snapshot = new HousingMarketRepository(restarted, clock, ZONE, 4).read(DAY, quotas);
        }
        assertEquals(Set.copyOf(receipts), Set.copyOf(snapshot.admissions()));
        assertEquals(new Remaining(4, Map.of(new Market(WORLD, "downtown"), 1,
                new Market(WORLD, "uptown"), 4, new Market(OTHER_WORLD, "downtown"), 3),
                Map.of(PROPERTY, 1, otherWorldHome, 2, untouched, 3)), snapshot.remaining());
        assertEquals(List.of(0, 1, 2, 3), home(snapshot, PROPERTY).freeSlots());
        assertEquals("loft", home(snapshot, PROPERTY).classKey());
        assertEquals(Status.ADMITTED_TODAY, participant(snapshot, RESIDENT).status());
        assertEquals(Status.DISABLED, participant(snapshot, second).status());
        assertEquals(before, data());

        execute("UPDATE npc_properties SET enabled=TRUE WHERE property_id=?", apartment);
        HousingMarketSnapshot reenabled = market().read(DAY, quotas);
        assertEquals(2, reenabled.remaining().properties().get(apartment));
        assertEquals(1, reenabled.remaining().markets().get(new Market(WORLD, "downtown")));
        clock.set(NOW.plusSeconds(86400));
        var previousDays = data();
        HousingMarketSnapshot next = market().read(DAY.plusDays(1), quotas);
        assertEquals(new Remaining(8, Map.of(new Market(WORLD, "downtown"), 4,
                new Market(WORLD, "uptown"), 4, new Market(OTHER_WORLD, "downtown"), 4),
                Map.of(PROPERTY, 3, apartment, 3, otherWorldHome, 3, untouched, 3)), next.remaining());
        assertTrue(next.admissions().isEmpty());
        assertEquals(previousDays, data(), "new-day allowance must not delete or reset the previous receipts");
    }

    @Test
    void participantStatusesSeparateAbsentVacantDisabledOccupiedFutureCooldownPremiumAndAdmittedToday() throws SQLException {
        UUID vacant = new UUID(0, 18), disabled = new UUID(0, 19), occupied = new UUID(0, 20);
        UUID future = new UUID(0, 21), cooling = new UUID(0, 22), premium = new UUID(0, 23), departed = new UUID(0, 24);
        for (UUID id : List.of(vacant, disabled, occupied, cooling, departed)) resident(id, STANDARD, DAY.minusDays(30));
        resident(future, STANDARD, DAY.plusDays(1));
        resident(premium, new HousingPolicy(WORLD, "downtown", "room", HousingPolicy.Tier.PREMIUM, 1200, 40), DAY);
        vacant(vacant, 19, DAY);
        new HousingRegistry(database).setEnabled(disabled, 0, false);
        clock.set(NOW.minusSeconds(86400));
        admit(cooling, PROPERTY, 0, DAY.minusDays(1), POLICY);
        vacate(cooling);
        clock.set(NOW);
        admit(occupied, PROPERTY, 2, DAY, POLICY);
        admit(departed, PROPERTY, 0, DAY, POLICY);
        vacate(departed);
        var before = data();
        HousingMarketSnapshot snapshot = read();
        assertEquals(Map.of(RESIDENT, Status.READY, vacant, Status.READY, disabled, Status.DISABLED,
                occupied, Status.OCCUPIED, future, Status.NOT_YET_AVAILABLE, cooling, Status.COOLDOWN,
                premium, Status.PREMIUM_HELD, departed, Status.ADMITTED_TODAY), snapshot.participants().stream()
                .collect(Collectors.toMap(p -> p.member().residentId(), Participant::status)));
        assertEquals(-1, participant(snapshot, RESIDENT).tenancyRevision());
        assertEquals(19, participant(snapshot, vacant).tenancyRevision());
        assertEquals(DAY, participant(snapshot, vacant).cooldownUntil(), "cooldown boundary is inclusive");
        assertEquals(DAY.plusDays(6), participant(snapshot, cooling).cooldownUntil());
        assertEquals(Set.of(RESIDENT, vacant), snapshot.seekers().stream().map(s -> s.residentId()).collect(Collectors.toSet()));
        assertEquals(19, snapshot.seekers().stream().filter(s -> s.residentId().equals(vacant)).findFirst().orElseThrow().tenancyRevision());
        assertEquals(List.of(0, 1, 3), home(snapshot, PROPERTY).freeSlots());
        assertEquals(before, data());
    }

    @Test
    void facilitiesReportInstalledVersusEffectiveWithoutInventingPremiumServiceEvidence() throws SQLException {
        UUID other = new UUID(0, 801), premium = new UUID(0, 18);
        property(other, WORLD, "downtown", "room");
        resident(premium, new HousingPolicy(WORLD, "downtown", "room", HousingPolicy.Tier.PREMIUM, 1200, 40), DAY);
        facilities(PROPERTY, 3);
        facilities(other, 2);
        execute("INSERT INTO npc_upgrades(property_id,upgrade_type,level) VALUES (?,'ADVERTISING',3)", other);
        execute("""
                INSERT INTO npc_incidents(incident_id,property_id,incident_type,cash_repair_cents,item_repair_spec,
                  condition_damage,affected_upgrade_type,economy_day,due_at)
                VALUES (77,?,'LEAK',100,'fixture',1,'FACILITIES','2026-09-07','2026-09-08 12:00:00')
                """, other);
        execute("UPDATE npc_upgrades SET disabled_incident_id=77 WHERE property_id=? AND upgrade_type='FACILITIES'", other);
        var before = data();
        HousingMarketSnapshot snapshot = read();
        assertEquals(3, home(snapshot, PROPERTY).installedFacilities());
        assertEquals(3, home(snapshot, PROPERTY).effectiveFacilities());
        assertEquals(2, home(snapshot, other).installedFacilities());
        assertEquals(0, home(snapshot, other).effectiveFacilities());
        assertEquals(Status.PREMIUM_HELD, participant(snapshot, premium).status());
        assertThrows(IllegalArgumentException.class, () -> snapshot.quote(UUID.randomUUID(), assignment(snapshot, premium, PROPERTY, 0)));
        assertEquals(before, data());
    }

    @Test
    void disabledUnownedHomesAreExcludedButDisabledAndNonmemberOccupantsKeepTheirActualSlots() throws SQLException {
        UUID disabledHome = new UUID(0, 801), unownedHome = new UUID(0, 802);
        UUID disabledResident = new UUID(0, 18), legacy = new UUID(0, 19), elsewhere = new UUID(0, 20);
        property(disabledHome, WORLD, "downtown", "room");
        property(unownedHome, WORLD, "downtown", "room");
        resident(disabledResident, STANDARD, DAY);
        resident(elsewhere, STANDARD, DAY);
        admit(disabledResident, PROPERTY, 0, DAY, POLICY);
        admit(elsewhere, disabledHome, 1, DAY, POLICY);
        new HousingRegistry(database).setEnabled(disabledResident, 0, false);
        profile(legacy);
        occupant(legacy, PROPERTY, 2);
        UUID unownedOccupant = new UUID(0, 21);
        profile(unownedOccupant);
        occupant(unownedOccupant, unownedHome, 3);
        execute("UPDATE npc_properties SET enabled=FALSE WHERE property_id=?", disabledHome);
        execute("UPDATE npc_properties SET owner_uuid=NULL WHERE property_id=?", unownedHome);
        var before = data();
        HousingMarketSnapshot snapshot = read();
        assertEquals(List.of(expectedHome(PROPERTY, WORLD, "downtown", "room", List.of(1, 3))), snapshot.homes());
        assertEquals(3, snapshot.participants().size(), "legacy occupancy must not generate cohort membership");
        assertEquals(8, snapshot.remaining().server(), "excluded homes do not refund successful intake");
        UUID requestId = UUID.randomUUID();
        HousingMoveRequest request = snapshot.quote(requestId, assignment(snapshot, RESIDENT, PROPERTY, 3));
        assertEquals(new HousingMoveRequest(requestId, RESIDENT, PROPERTY, OWNER, WORLD, "downtown", "room",
                3, 11, 0, -1, 3, DAY), request);
        assertEquals(before, data());
        var sides = sideData();
        assertTrue(moves().apply(request, POLICY).newlyApplied());
        assertEquals("3", rows("SELECT slot FROM npc_tenants WHERE tenant_id=?", RESIDENT).getFirst().get("slot"));
        assertEquals(List.of(1), home(read(), PROPERTY).freeSlots());
        assertEquals(sides, sideData());
    }

    @Test
    void snapshotQuotesRejectMismatchedAssignmentsAndRealIneligibleHomes() throws SQLException {
        UUID occupant = new UUID(0, 18);
        resident(occupant, STANDARD, DAY);
        admit(occupant, PROPERTY, 2, DAY, POLICY);
        UUID wrongWorld = new UUID(0, 801), wrongMarket = new UUID(0, 802), wrongClass = new UUID(0, 803);
        UUID expensive = new UUID(0, 804), damaged = new UUID(0, 805);
        property(wrongWorld, OTHER_WORLD, "downtown", "room");
        property(wrongMarket, WORLD, "uptown", "room");
        property(wrongClass, WORLD, "downtown", "apartment");
        property(expensive, WORLD, "downtown", "room");
        property(damaged, WORLD, "downtown", "room");
        execute("UPDATE npc_properties SET asking_rent_cents=1201 WHERE property_id=?", expensive);
        execute("UPDATE npc_properties SET condition_score=39 WHERE property_id=?", damaged);
        var before = data();
        HousingMarketSnapshot snapshot = read();
        List<Assignment> invalid = new ArrayList<>(List.of(
                new Assignment(RESIDENT, PROPERTY, 0, 1, -1), new Assignment(RESIDENT, PROPERTY, 0, 0, 0),
                new Assignment(RESIDENT, PROPERTY, 2, 0, -1), new Assignment(new UUID(0, 999), PROPERTY, 0, 0, -1),
                new Assignment(RESIDENT, new UUID(0, 999), 0, 0, -1), assignment(snapshot, occupant, PROPERTY, 0)));
        for (UUID id : List.of(wrongWorld, wrongMarket, wrongClass, expensive, damaged))
            invalid.add(assignment(snapshot, RESIDENT, id, 0));
        for (Assignment assignment : invalid)
            assertThrows(IllegalArgumentException.class, () -> snapshot.quote(UUID.randomUUID(), assignment), assignment.toString());
        assertNotNull(snapshot.quote(UUID.randomUUID(), assignment(snapshot, RESIDENT, PROPERTY, 3)));
        assertEquals(before, data());
    }

    @Test
    void exhaustedDurableServerMarketOrPropertyAllowanceCannotProduceAnotherSnapshotQuote() throws SQLException {
        UUID second = new UUID(0, 18), third = new UUID(0, 19), other = new UUID(0, 801);
        resident(second, STANDARD, DAY.minusDays(30));
        resident(third, housing(WORLD, "downtown", "apartment"), DAY.minusDays(30));
        property(other, WORLD, "downtown", "apartment");
        List<Policy> limits = List.of(new Policy(1, 5, 3, 1), new Policy(10, 1, 3, 1), new Policy(10, 5, 1, 1));
        for (int i = 0; i < limits.size(); i++) {
            LocalDate day = DAY.plusDays(i);
            clock.set(NOW.plusSeconds(86400L * i));
            Policy policy = limits.get(i);
            admit(RESIDENT, PROPERTY, 0, day, policy);
            vacate(RESIDENT);
            HousingMarketSnapshot snapshot = market().read(day, policy);
            var before = data();
            assertEquals(List.of(0, 1, 2, 3), home(snapshot, PROPERTY).freeSlots());
            assertThrows(IllegalArgumentException.class, () -> snapshot.quote(UUID.randomUUID(), assignment(snapshot, second, PROPERTY, 0)));
            if (i < 2) {
                assertThrows(IllegalArgumentException.class, () -> snapshot.quote(UUID.randomUUID(), assignment(snapshot, third, other, 0)),
                        "market allowance crosses classes; server allowance crosses properties");
            } else {
                assertNotNull(snapshot.quote(UUID.randomUUID(), assignment(snapshot, third, other, 0)),
                        "property exhaustion does not consume an unrelated property's allowance");
            }
            assertEquals(before, data());
        }
    }

    @Test
    void snapshotBoundRequestRejectsSubsequentOwnerEpochAndPropertyRevisionChanges() throws SQLException {
        HousingMarketSnapshot snapshot = read();
        Assignment assignment = assignment(snapshot, RESIDENT, PROPERTY, 1);
        UUID requestId = UUID.randomUUID();
        HousingMoveRequest request = snapshot.quote(requestId, assignment);
        for (String change : List.of("owner_uuid=UNHEX('00000000000000000000000000000385')",
                "ownership_epoch=4", "row_version=12")) {
            execute("UPDATE npc_properties SET " + change + " WHERE property_id=?", PROPERTY);
            assertEquals(request, snapshot.quote(requestId, assignment), "snapshot quoting must not silently re-read current terms");
            rejected("Housing property changed", () -> moves().apply(request, POLICY));
            execute("UPDATE npc_properties SET owner_uuid=?,ownership_epoch=3,row_version=11 WHERE property_id=?", OWNER, PROPERTY);
        }
        var sides = sideData();
        assertTrue(moves().apply(request, POLICY).newlyApplied());
        assertEquals(sides, sideData());
    }

    @Test
    void snapshotBoundRequestRejectsMembershipAbaAndAppearedOrAdvancedVacantTenancy() throws SQLException {
        HousingMarketSnapshot snapshot = read();
        HousingMoveRequest request = snapshot.quote(UUID.randomUUID(), assignment(snapshot, RESIDENT, PROPERTY, 1));
        HousingRegistry registry = new HousingRegistry(database);
        registry.setEnabled(RESIDENT, 0, false);
        rejected("Housing membership changed", () -> moves().apply(request, POLICY));
        registry.setEnabled(RESIDENT, 1, true);
        rejected("Housing membership changed", () -> moves().apply(request, POLICY));
        HousingMarketSnapshot current = read();
        HousingMoveRequest noTenancy = current.quote(UUID.randomUUID(), assignment(current, RESIDENT, PROPERTY, 1));
        vacant(RESIDENT, 19, DAY);
        rejected("Housing tenancy changed", () -> moves().apply(noTenancy, POLICY));
        HousingMarketSnapshot returning = read();
        HousingMoveRequest oldRevision = returning.quote(UUID.randomUUID(), assignment(returning, RESIDENT, PROPERTY, 1));
        assertEquals(2, oldRevision.membershipRevision());
        assertEquals(19, oldRevision.tenancyRevision());
        execute("UPDATE npc_tenants SET row_version=20 WHERE tenant_id=?", RESIDENT);
        rejected("Housing tenancy changed", () -> moves().apply(oldRevision, POLICY));
    }

    @Test
    void anotherActualAdmissionCanTakeASnapshotSlotAndApplyMustRejectTheStaleRequest() throws SQLException {
        UUID other = new UUID(0, 18);
        resident(other, STANDARD, DAY);
        HousingMarketSnapshot snapshot = read();
        HousingMoveRequest stale = snapshot.quote(UUID.randomUUID(), assignment(snapshot, RESIDENT, PROPERTY, 1));
        HousingMoveRequest winner = snapshot.quote(UUID.randomUUID(), assignment(snapshot, other, PROPERTY, 1));
        var sides = sideData();
        assertTrue(moves().apply(winner, POLICY).newlyApplied());
        rejected("Housing slot occupied", () -> moves().apply(stale, POLICY));
        HousingMarketSnapshot current = read();
        assertEquals(List.of(0, 2, 3), home(current, PROPERTY).freeSlots());
        assertEquals(9, current.remaining().server());
        assertEquals(List.of(winner), current.admissions().stream().map(Receipt::request).toList());
        assertEquals(sides, sideData());
    }

    @Test
    void exactSnapshotRequestReplayAfterDepartureAndNewDayNeverRehousesOrConsumesAnotherAllowance() throws SQLException {
        HousingMarketSnapshot snapshot = read();
        HousingMoveRequest request = snapshot.quote(UUID.randomUUID(), assignment(snapshot, RESIDENT, PROPERTY, 3));
        Receipt receipt = moves().apply(request, POLICY).receipt();
        vacate(RESIDENT);
        new HousingRegistry(database).setEnabled(RESIDENT, 0, false);
        execute("UPDATE npc_properties SET owner_uuid=NULL,enabled=FALSE,row_version=12 WHERE property_id=?", PROPERTY);
        clock.set(NOW.plusSeconds(10 * 86400L));
        var before = data();
        for (int i = 0; i < 2; i++) {
            assertEquals(new HousingMoveRepository.Result(false, receipt), moves().apply(request, POLICY));
            assertEquals(receipt, moves().find(request.requestId()).orElseThrow());
        }
        assertNull(rows("SELECT property_id FROM npc_tenants WHERE tenant_id=?", RESIDENT).getFirst().get("property_id"));
        HousingMarketSnapshot current = market().read(DAY.plusDays(10), POLICY);
        assertEquals(10, current.remaining().server());
        assertTrue(current.homes().isEmpty());
        assertTrue(current.admissions().isEmpty());
        assertEquals(before, data());
    }

    @Test
    void missingRegistryLockOrRequiredProfileFailsClosedWithoutRepairingAnything() throws SQLException {
        execute("DELETE FROM npc_housing_registry_lock");
        rejected("Housing admission lock missing or invalid", this::read);
        assertTrue(rows("SELECT * FROM npc_housing_registry_lock").isEmpty());
        execute("INSERT INTO npc_housing_registry_lock(singleton_id) VALUES (1)");
        corrupt("DELETE FROM npc_resident_profiles WHERE resident_id=?", RESIDENT);
        rejected("Invalid housing market snapshot", this::read);
        assertTrue(rows("SELECT * FROM npc_resident_profiles WHERE resident_id=?", RESIDENT).isEmpty());
    }

    @Test
    void partialDuplicateOutOfCapacityAndOrphanSlotsFailClosedEvenOnExcludedHomes() throws SQLException {
        UUID first = new UUID(0, 18), second = new UUID(0, 19);
        profile(first);
        profile(second);
        occupant(first, PROPERTY, 0);
        execute("UPDATE npc_tenants SET slot=NULL WHERE tenant_id=?", first);
        rejected("Invalid housing snapshot tenancy", this::read);
        execute("UPDATE npc_tenants SET property_id=NULL,slot=1 WHERE tenant_id=?", first);
        rejected("Invalid housing snapshot tenancy", this::read);
        execute("UPDATE npc_tenants SET property_id=?,slot=0 WHERE tenant_id=?", PROPERTY, first);
        occupant(second, PROPERTY, 0);
        rejected("Duplicate housing slot", this::read);
        execute("DELETE FROM npc_tenants WHERE tenant_id=?", second);
        execute("UPDATE npc_properties SET capacity=2,enabled=FALSE WHERE property_id=?", PROPERTY);
        execute("UPDATE npc_tenants SET slot=3 WHERE tenant_id=?", first);
        rejected("Housing occupancy outside property", this::read);
        execute("UPDATE npc_properties SET enabled=TRUE,owner_uuid=NULL WHERE property_id=?", PROPERTY);
        rejected("Housing occupancy outside property", this::read);
        corrupt("UPDATE npc_tenants SET property_id=?,slot=0 WHERE tenant_id=?", new UUID(0, 999), first);
        rejected("Housing occupancy outside property", this::read);
    }

    @Test
    @Timeout(120)
    void full512CohortAndPropertyBoundReturnAllRowsBut513thFailsInsteadOfTruncating() throws SQLException {
        for (int i = 1; i < 512; i++) resident(new UUID(0, 1000 + i), STANDARD, DAY);
        UUID last = new UUID(0, 1511);
        new HousingRegistry(database).setEnabled(last, 0, false);
        HousingMarketSnapshot full = read();
        assertEquals(512, full.participants().size());
        assertEquals(Status.DISABLED, participant(full, last).status());
        assertEquals(511, full.seekers().size());
        UUID overflow = new UUID(0, 2000);
        profile(overflow);
        // Out-of-band corruption deliberately bypasses the API's 512-member admission ceiling.
        execute("""
                INSERT INTO npc_housing_members(resident_id,world_uuid,market_id,class_key,tier,
                  max_daily_rent_cents,minimum_condition,available_since,row_version,enabled)
                SELECT ?,world_uuid,market_id,class_key,tier,max_daily_rent_cents,minimum_condition,
                  available_since,row_version,enabled FROM npc_housing_members WHERE resident_id=?
                """, overflow, RESIDENT);
        rejected("Housing snapshot exceeds query bound", this::read);
        execute("DELETE FROM npc_housing_members WHERE resident_id=?", overflow);
        for (int i = 1; i < 512; i++) property(new UUID(0, 3000 + i), WORLD, "downtown", "room");
        HousingMarketSnapshot allHomes = read();
        assertEquals(512, allHomes.homes().size());
        assertEquals(512, allHomes.remaining().properties().size());
        UUID hiddenOverflow = new UUID(0, 4000);
        property(hiddenOverflow, WORLD, "downtown", "room");
        execute("UPDATE npc_properties SET enabled=FALSE,owner_uuid=NULL WHERE property_id=?", hiddenOverflow);
        rejected("Housing snapshot exceeds query bound", this::read);
    }

    @Test
    void invalidCohortAndExhaustedRevisionsFailClosedInsteadOfNarrowingOrWrapping() throws SQLException {
        for (String change : List.of("market_id='Downtown'", "max_daily_rent_cents=2147483648",
                "enabled=2", "available_since='2026-02-30'")) {
            corrupt("UPDATE npc_housing_members SET " + change + " WHERE resident_id=?", RESIDENT);
            rejected("Invalid housing membership", this::read);
            execute("UPDATE npc_housing_members SET market_id='downtown',max_daily_rent_cents=1200,enabled=TRUE,"
                    + "available_since=? WHERE resident_id=?", DAY.minusDays(30).toString(), RESIDENT);
        }
        execute("UPDATE npc_properties SET row_version=? WHERE property_id=?", Long.MAX_VALUE, PROPERTY);
        rejected("Invalid housing snapshot property", this::read);
        execute("UPDATE npc_properties SET row_version=11 WHERE property_id=?", PROPERTY);
        vacant(RESIDENT, Long.MAX_VALUE, DAY);
        rejected("Invalid housing snapshot tenancy", this::read);
        execute("UPDATE npc_tenants SET row_version=0,tier='PREMIUM' WHERE tenant_id=?", RESIDENT);
        rejected("Housing tier requires reconciliation", this::read);
    }

    @Test
    void historicalAdmissionDetectsDeletedVacantResetAndOccupiedRegressedTenancy() throws SQLException {
        clock.set(NOW.minusSeconds(10 * 86400L));
        vacant(RESIDENT, 19, DAY.minusDays(10));
        Receipt prior = admit(RESIDENT, PROPERTY, 1, DAY.minusDays(10), POLICY);
        assertEquals(20, prior.tenancyRevisionAfter());
        vacate(RESIDENT);
        clock.set(NOW);
        HousingMarketSnapshot valid = read();
        assertEquals(Status.READY, participant(valid, RESIDENT).status());
        HousingMoveRequest request = valid.quote(UUID.randomUUID(), assignment(valid, RESIDENT, PROPERTY, 0));
        for (String shortened : new String[]{null, prior.cooldownUntil().minusDays(1).toString()}) {
            execute("UPDATE npc_tenants SET move_cooldown_until_day=? WHERE tenant_id=?", shortened, RESIDENT);
            rejected("Housing move cooldown regressed", this::read);
            rejected("Housing move cooldown regressed", () -> moves().apply(request, POLICY));
        }
        execute("UPDATE npc_tenants SET move_cooldown_until_day=? WHERE tenant_id=?", prior.cooldownUntil().toString(), RESIDENT);
        execute("DELETE FROM npc_tenants WHERE tenant_id=?", RESIDENT);
        rejected("Housing tenancy history requires reconciliation", this::read);
        vacant(RESIDENT, 20, DAY);
        rejected("Housing tenancy history requires reconciliation", this::read);
        execute("UPDATE npc_tenants SET row_version=21 WHERE tenant_id=?", RESIDENT);
        assertEquals(Status.READY, participant(read(), RESIDENT).status());
        execute("UPDATE npc_tenants SET property_id=?,slot=1,row_version=19 WHERE tenant_id=?", PROPERTY, RESIDENT);
        rejected("Housing tenancy history requires reconciliation", this::read);
        execute("UPDATE npc_tenants SET row_version=20 WHERE tenant_id=?", RESIDENT);
        assertEquals(Status.OCCUPIED, participant(read(), RESIDENT).status());
        execute("UPDATE npc_tenants SET slot=2 WHERE tenant_id=?", RESIDENT);
        rejected("Housing occupancy contradicts admission receipt", this::read);
        execute("UPDATE npc_tenants SET row_version=21 WHERE tenant_id=?", RESIDENT);
        assertEquals(Status.OCCUPIED, participant(read(), RESIDENT).status(), "a later custody revision may differ from the admission");
    }

    @Test
    void priorDayReceiptHashOutcomeAndHistoricalPolicyAreValidatedBeforeTrustingItsRevision() throws SQLException {
        Policy oldPolicy = new Policy(6, 3, 2, 2);
        LocalDate oldDay = DAY.minusDays(10);
        clock.set(NOW.minusSeconds(10 * 86400L));
        Receipt prior = admit(RESIDENT, PROPERTY, 0, oldDay, oldPolicy);
        vacate(RESIDENT);
        clock.set(NOW);
        HousingMarketSnapshot baseline = read();
        assertEquals(Status.READY, participant(baseline, RESIDENT).status());
        assertTrue(baseline.admissions().isEmpty(), "history is validated but does not consume today's allowance");
        execute("UPDATE npc_housing_moves SET payload_hash=? WHERE request_id=?", "0".repeat(64), prior.request().requestId());
        rejected("Housing receipt binding mismatch", this::read);
        execute("UPDATE npc_housing_moves SET payload_hash=? WHERE request_id=?", prior.request().fingerprint(oldPolicy), prior.request().requestId());
        execute("UPDATE npc_housing_moves SET cooldown_until=? WHERE request_id=?", oldDay.plusDays(3).toString(), prior.request().requestId());
        rejected("Invalid housing receipt outcome", this::read);
        execute("UPDATE npc_housing_moves SET cooldown_until=? WHERE request_id=?", prior.cooldownUntil().toString(), prior.request().requestId());
        execute("UPDATE npc_housing_days SET server_limit=5 WHERE economy_day=?", oldDay.toString());
        rejected("Housing receipt binding mismatch", this::read);
        execute("UPDATE npc_housing_days SET server_limit=6 WHERE economy_day=?", oldDay.toString());
        assertEquals(baseline, read(), "the old receipt must bind its old policy, not today's proposed policy");
        corrupt("DELETE FROM npc_housing_days WHERE economy_day=?", oldDay.toString());
        rejected("Missing housing snapshot number", this::read);
    }

    @Test
    void newerHistoricalReceiptCannotHideBehindAnOlderHigherRevision() throws SQLException {
        Policy oldPolicy = new Policy(6, 3, 2, 1);
        clock.set(NOW.minusSeconds(10 * 86400L));
        vacant(RESIDENT, 19, DAY.minusDays(10));
        Receipt older = admit(RESIDENT, PROPERTY, 0, DAY.minusDays(10), oldPolicy);
        vacate(RESIDENT);
        clock.set(NOW.minusSeconds(8 * 86400L));
        Receipt newer = admit(RESIDENT, PROPERTY, 1, DAY.minusDays(8), oldPolicy);
        vacate(RESIDENT);
        clock.set(NOW);
        HousingMarketSnapshot snapshot = read();
        assertEquals(23, participant(snapshot, RESIDENT).tenancyRevision());
        HousingMoveRequest next = snapshot.quote(UUID.randomUUID(), assignment(snapshot, RESIDENT, PROPERTY, 0));
        assertEquals(20, older.tenancyRevisionAfter());
        assertEquals(22, newer.tenancyRevisionAfter());
        corrupt("UPDATE npc_housing_moves SET tenancy_revision_after=18 WHERE request_id=?", newer.request().requestId());
        rejected("Invalid housing receipt outcome", this::read);
        rejected("Housing receipt chronology disagrees with revision", () -> moves().apply(next, POLICY));
        execute("UPDATE npc_housing_moves SET tenancy_revision_after=22 WHERE request_id=?", newer.request().requestId());
        assertEquals(snapshot, read());
    }

    @Test
    void concurrentCommittedAdmissionAndPropertyChangesCannotTearTheCrossQuerySnapshot() throws Exception {
        facilities(PROPERTY, 1);
        HousingMarketSnapshot before = read();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ReadGate gate = new ReadGate()) {
            try {
                Future<HousingMarketSnapshot> pending = worker.submit(() -> gate.market(clock).read(DAY, POLICY));
                gate.awaitPropertyRead();
                assertFalse(pending.isDone(), "reader is paused after consuming real property rows");
                try (Connection c = database.getConnection()) {
                    c.setAutoCommit(false);
                    try {
                        execute(c, "UPDATE npc_properties SET row_version=12,asking_rent_cents=1000,condition_score=60 WHERE property_id=?", PROPERTY);
                        execute(c, "UPDATE npc_upgrades SET level=3 WHERE property_id=? AND upgrade_type='FACILITIES'", PROPERTY);
                        c.commit();
                    } catch (Exception | AssertionError failure) { c.rollback(); throw failure; }
                }
                Receipt concurrent = admit(RESIDENT, PROPERTY, 1, DAY, POLICY);
                resident(new UUID(0, 18), STANDARD, DAY);
                var afterWriter = data();
                gate.release();
                assertEquals(before, pending.get(10, TimeUnit.SECONDS),
                        "properties, cohort, custody, receipts and facilities must all describe the pre-writer view");
                HousingMarketSnapshot after = read();
                assertEquals(12, home(after, PROPERTY).revision());
                assertEquals(1000, home(after, PROPERTY).askingRentCents());
                assertEquals(60, home(after, PROPERTY).condition());
                assertEquals(3, home(after, PROPERTY).effectiveFacilities());
                assertEquals(List.of(0, 2, 3), home(after, PROPERTY).freeSlots());
                assertEquals(Status.OCCUPIED, participant(after, RESIDENT).status());
                assertEquals(2, after.participants().size());
                assertEquals(List.of(concurrent), after.admissions());
                assertEquals(9, after.remaining().server());
                assertEquals(afterWriter, data(), "neither reader may undo or add to the concurrent writer's commit");
            } finally {
                gate.release();
                worker.shutdownNow();
                assertTrue(worker.awaitTermination(15, TimeUnit.SECONDS), "snapshot worker terminated");
            }
        }
    }

    @Test
    void economyDayRolloverAfterAnObservedSqlPhaseRejectsTheWholeReadWithoutPinningEitherDay() throws Exception {
        Instant reset = DAY.plusDays(1).atTime(4, 0).atZone(ZONE).toInstant();
        clock.set(reset.minusMillis(1));
        var before = data();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ReadGate gate = new ReadGate()) {
            try {
                Future<SQLException> pending = worker.submit(() -> assertThrows(SQLException.class,
                        () -> gate.market(clock).read(DAY, POLICY)));
                gate.awaitPropertyRead();
                assertFalse(pending.isDone());
                clock.set(reset);
                gate.release();
                assertDomainSql(pending.get(10, TimeUnit.SECONDS), "Housing snapshot crossed economy day");
                assertEquals(before, data());
                HousingMarketSnapshot next = market().read(DAY.plusDays(1), POLICY);
                assertEquals(reset, next.capturedAt());
                assertEquals(DAY.plusDays(1), next.day());
                assertEquals(10, next.remaining().server());
                rejected("Housing snapshot requires current economy day", this::read);
                assertEquals(before, data());
            } finally {
                gate.release();
                worker.shutdownNow();
                assertTrue(worker.awaitTermination(15, TimeUnit.SECONDS), "rollover worker terminated");
            }
        }
    }

    @Test
    void v11RepeatKeepsAllRowsAndTheChronologicalIndexInItsRequiredColumnOrder() throws Exception {
        admit(RESIDENT, PROPERTY, 0, DAY, POLICY);
        var before = data();
        applyResource("/db/migration/V11__housing_history_order.sql");
        applyResource("/db/migration/V11__housing_history_order.sql");
        assertEquals(before, data());
        assertEquals(List.of(Map.of("index_position", "1", "column_name", "resident_id"),
                Map.of("index_position", "2", "column_name", "economy_day"),
                Map.of("index_position", "3", "column_name", "applied_at_millis"),
                Map.of("index_position", "4", "column_name", "request_id")), rows(
                "SELECT SEQ_IN_INDEX AS index_position,COLUMN_NAME AS column_name FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='npc_housing_moves' "
                        + "AND INDEX_NAME='idx_hmove_resident_chronology' ORDER BY SEQ_IN_INDEX"));
        assertEquals(1, read().admissions().size());
    }

    private HousingMarketRepository market() { return new HousingMarketRepository(database, clock, ZONE, 4); }
    private HousingMarketSnapshot read() throws SQLException { return market().read(DAY, POLICY); }
    private HousingMoveRepository moves() { return new HousingMoveRepository(database, clock, ZONE, 4); }

    private Receipt admit(UUID resident, UUID property, int slot, LocalDate day, Policy policy) throws SQLException {
        HousingMoveRepository repository = moves();
        HousingMoveRequest request = repository.quote(UUID.randomUUID(), resident, property, slot, day);
        HousingMoveRepository.Result result = repository.apply(request, policy);
        assertTrue(result.newlyApplied());
        assertEquals(request, result.receipt().request());
        return result.receipt();
    }

    private static HousingPolicy housing(UUID world, String market, String kind) {
        return new HousingPolicy(world, market, kind, HousingPolicy.Tier.STANDARD, 1200, 40);
    }

    private static void profile(UUID resident) throws SQLException {
        assertTrue(new ResidentRepository(database).register(ResidentCatalogue.draft(resident, "Resident " + resident)));
    }

    private static void resident(UUID resident, HousingPolicy policy, LocalDate available) throws SQLException {
        profile(resident);
        assertTrue(new HousingRegistry(database).admit(resident, policy, available));
    }

    private static void property(UUID id, UUID world, String market, String kind) throws SQLException {
        execute("""
                INSERT INTO npc_properties(property_id,world_uuid,region_id,market_id,class_key,capacity,
                  reference_rent_cents,asking_rent_cents,owner_uuid,ownership_epoch,condition_score,enabled,row_version)
                VALUES (?,?,?,?,?,4,1000,1200,?,3,40,TRUE,11)
                """, id, world, "fixture-" + id, market, kind, OWNER);
    }

    private static void facilities(UUID property, int level) throws SQLException {
        execute("INSERT INTO npc_upgrades(property_id,upgrade_type,level) VALUES (?,'FACILITIES',?)", property, level);
    }

    private static Home expectedHome(UUID id, UUID world, String market, String kind, List<Integer> slots) {
        return new Home(id, OWNER, world, "fixture-" + id, market, kind, 3, 11, 4, 1000, 1200, 40, 0, 0, slots);
    }

    private static Home home(HousingMarketSnapshot snapshot, UUID id) {
        return snapshot.homes().stream().filter(h -> h.propertyId().equals(id)).findFirst().orElseThrow();
    }

    private static Participant participant(HousingMarketSnapshot snapshot, UUID id) {
        return snapshot.participants().stream().filter(p -> p.member().residentId().equals(id)).findFirst().orElseThrow();
    }

    private static Assignment assignment(HousingMarketSnapshot snapshot, UUID resident, UUID property, int slot) {
        Participant p = participant(snapshot, resident);
        return new Assignment(resident, property, slot, p.member().revision(), p.tenancyRevision());
    }

    // Explicit pre-V10/legacy custody fixtures: no receipt is invented for unrecorded history.
    private static void vacant(UUID resident, long revision, LocalDate cooldown) throws SQLException {
        execute("""
                INSERT INTO npc_tenants(tenant_id,display_name,property_id,slot,tier,archetype,mood,satisfaction,
                  move_cooldown_until_day,schedule_template,state,row_version)
                VALUES (?,'Preserved Tenant',NULL,NULL,'STANDARD','RESTORER','HAPPY',73,?,'NIGHT_SHIFT','WORKING',?)
                """, resident, cooldown.toString(), revision);
    }

    private static void occupant(UUID resident, UUID property, int slot) throws SQLException {
        execute("INSERT INTO npc_tenants(tenant_id,display_name,property_id,slot,archetype) VALUES (?,'Legacy occupant',?,?,'COOK')",
                resident, property, slot);
    }

    private static void vacate(UUID resident) throws SQLException {
        execute("UPDATE npc_tenants SET property_id=NULL,slot=NULL,row_version=row_version+1 WHERE tenant_id=?", resident);
    }

    private static void rejected(String message, Executable operation) throws SQLException {
        var before = data();
        assertDomainSql(assertThrows(SQLException.class, operation), message);
        assertEquals(before, data(), "a rejected operation must leave every table and column unchanged");
    }

    private static void assertDomainSql(SQLException failure, String message) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                assertFalse(sql instanceof SQLTimeoutException, "query timeout is not a domain pass");
                assertNotEquals(1205, sql.getErrorCode(), "lock wait timeout is not a domain pass");
                assertNotEquals(1213, sql.getErrorCode(), "deadlock is not a domain pass");
            }
        }
        assertEquals(message, failure.getMessage());
    }

    private static Map<String, List<Map<String, String>>> data() throws SQLException {
        Map<String, List<Map<String, String>>> result = new LinkedHashMap<>();
        for (String table : tables) result.put(table, rows("SELECT * FROM " + table));
        return result;
    }

    private static Map<String, List<Map<String, String>>> sideData() throws SQLException {
        var result = data();
        MOVE_TABLES.forEach(result::remove);
        return result;
    }

    private static List<Map<String, String>> rows(String sql, Object... values) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(5);
            bind(ps, values);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, String>> result = new ArrayList<>();
                while (rs.next()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                        Object value = rs.getObject(i);
                        row.put(rs.getMetaData().getColumnLabel(i), value instanceof byte[] bytes
                                ? HexFormat.of().formatHex(bytes) : rs.getString(i));
                    }
                    result.add(row);
                }
                result.sort(Comparator.comparing(Map::toString));
                return result;
            }
        }
    }

    private static void corrupt(String sql, Object... values) throws SQLException {
        try (Connection c = database.getConnection()) {
            execute(c, "SET SESSION check_constraint_checks=OFF,foreign_key_checks=OFF");
            try { execute(c, sql, values); }
            finally { execute(c, "SET SESSION check_constraint_checks=ON,foreign_key_checks=ON"); }
        }
    }

    private static void applyResource(String path) throws Exception {
        try (InputStream in = HousingMarketRepositoryIT.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8))) execute(sql);
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
            else ps.setObject(i + 1, values[i]);
        }
    }

    /** Forwards every SQL call/result to MariaDB; only pauses after a fully consumed property query. */
    private static final class ReadGate implements AutoCloseable {
        private final CountDownLatch propertyRead = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);
        private final AtomicBoolean armed = new AtomicBoolean(true);
        private final Database routed = new Database();

        private ReadGate() throws ReflectiveOperationException {
            // Database is final and owns its pool. Replace the pool only on this private test facade,
            // never on the shared fixture; production needs no injection constructor or test hook.
            HikariDataSource forwarding = new HikariDataSource() {
                @Override public Connection getConnection() throws SQLException {
                    return wrapConnection(database.getConnection());
                }
            };
            Field source = Database.class.getDeclaredField("dataSource");
            source.setAccessible(true);
            source.set(routed, forwarding);
        }

        HousingMarketRepository market(Clock clock) { return new HousingMarketRepository(routed, clock, ZONE, 4); }

        void awaitPropertyRead() throws InterruptedException {
            assertTrue(propertyRead.await(10, TimeUnit.SECONDS),
                    "fixture did not observe completion of the repository's real property SELECT");
        }

        void release() { resume.countDown(); }

        private Connection wrapConnection(Connection actual) {
            return proxy(Connection.class, (ignored, method, args) -> {
                Object result = invoke(actual, method, args);
                if (method.getName().equals("prepareStatement") && args != null && args[0] instanceof String sql
                        && sql.stripLeading().startsWith("SELECT * FROM npc_properties ORDER BY property_id")) {
                    return wrapStatement((PreparedStatement) result);
                }
                return result;
            });
        }

        private PreparedStatement wrapStatement(PreparedStatement actual) {
            return proxy(PreparedStatement.class, (ignored, method, args) -> {
                Object result = invoke(actual, method, args);
                if (method.getName().equals("executeQuery")) return wrapRows((ResultSet) result);
                return result;
            });
        }

        private ResultSet wrapRows(ResultSet actual) {
            AtomicBoolean exhausted = new AtomicBoolean();
            return proxy(ResultSet.class, (ignored, method, args) -> {
                Object result = invoke(actual, method, args);
                if (method.getName().equals("next") && Boolean.FALSE.equals(result)) exhausted.set(true);
                if (method.getName().equals("close") && armed.compareAndSet(true, false)) {
                    assertTrue(exhausted.get(), "property rows must be consumed before the cross-query phase gate");
                    propertyRead.countDown();
                    try {
                        if (!resume.await(10, TimeUnit.SECONDS)) throw new AssertionError("fixture phase gate timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("fixture phase gate interrupted", interrupted);
                    }
                }
                return result;
            });
        }

        @Override public void close() { release(); routed.close(); }
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        private final ZoneId zone;

        private MutableClock(Instant now, ZoneId zone) { this(new AtomicReference<>(now), zone); }
        private MutableClock(AtomicReference<Instant> now, ZoneId zone) { this.now = now; this.zone = zone; }
        void set(Instant instant) { now.set(instant); }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) { return new MutableClock(now, zone); }
        @Override public Instant instant() { return now.get(); }
    }
}

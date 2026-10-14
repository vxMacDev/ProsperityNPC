package com.prosperitysmp.npc.it;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.clock.EconomyClock;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.ReadSnapshot;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.economy.UtilityBill;
import com.prosperitysmp.npc.economy.UtilityRepository;
import com.prosperitysmp.npc.economy.UtilityServiceRepository;
import com.prosperitysmp.npc.formulas.IncidentType;
import com.prosperitysmp.npc.market.HousingMoveRepository;
import com.prosperitysmp.npc.market.HousingMoveRepository.Receipt;
import com.prosperitysmp.npc.market.HousingMoveRequest;
import com.prosperitysmp.npc.market.HousingMoveRequest.Policy;
import com.prosperitysmp.npc.market.HousingPolicy;
import com.prosperitysmp.npc.market.HousingRegistry;
import com.prosperitysmp.npc.property.IncidentRollRepository;
import com.prosperitysmp.npc.property.IncidentServiceAssessment;
import com.prosperitysmp.npc.property.IncidentServiceRepository;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyRepository;
import com.prosperitysmp.npc.property.RetentionAssessment;
import com.prosperitysmp.npc.property.RetentionRepository;
import com.prosperitysmp.npc.property.ServiceAssessment;
import com.prosperitysmp.npc.property.ServiceAssessment.Component;
import com.prosperitysmp.npc.property.ServiceAssessmentRepository;
import com.prosperitysmp.npc.property.TenantRepository;
import com.prosperitysmp.npc.property.UtilityServiceAssessment;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentRepository;
import com.prosperitysmp.npc.sim.DailyRoll;
import com.prosperitysmp.npc.sim.SimulationLogic;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real V1..V15 MariaDB composition; JDBC instrumentation never substitutes query results. */
@Timeout(45)
class ServiceAssessmentRepositoryIT {
    private static final Instant NOW = Instant.parse("2026-09-27T09:00:00Z");
    private static final Instant FROM = NOW.minus(Duration.ofDays(29));
    private static final Instant ADMITTED = NOW.minus(Duration.ofDays(10));
    private static final int HORIZON = 7;
    private static final ZoneId ZONE = ZoneId.of("America/Chicago");
    private static final int RESET = 4;
    private static final long SEED = 5000;
    private static final UUID WORLD = new UUID(0, 3200);
    private static final UUID OWNER = new UUID(0, 3201);
    private static final UUID RESERVE = new UUID(0, 3202);
    private static final Policy POLICY = new Policy(512, 512, 4, 1);
    private static final HousingPolicy HOUSING = new HousingPolicy(
            WORLD, "downtown", "room", HousingPolicy.Tier.STANDARD, 1200, 40);
    private static DB mariaDb;
    private static Database database;
    private static HikariDataSource pool;
    private static List<String> tables;

    @BeforeAll
    static void bootDatabase() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        try {
            mariaDb.start();
            String schema = "npc_combined_service_" + UUID.randomUUID().toString().replace("-", "");
            mariaDb.createDB(schema);
            database = new Database();
            database.connect(new DatabaseSettings("localhost", cfg.getPort(), schema, "root", "", 6, 10_000L));
            pool = (HikariDataSource) sourceField().get(database);
            Field migrations = MigrationRunner.class.getDeclaredField("MIGRATIONS");
            migrations.setAccessible(true);
            List<Integer> applied = new ArrayList<>();
            for (Object migration : (List<?>) migrations.get(null)) {
                Method version = migration.getClass().getDeclaredMethod("version");
                Method resource = migration.getClass().getDeclaredMethod("resource");
                version.setAccessible(true);
                resource.setAccessible(true);
                int number = (Integer) version.invoke(migration);
                // V21 and V35: the incident service read also checks item-repair receipts (item-repairs).
                if (number > 15 && number != 21 && number != 23 && number != 35) continue;
                try (InputStream in = ServiceAssessmentRepositoryIT.class.getResourceAsStream("/" + resource.invoke(migration))) {
                    assertNotNull(in);
                    for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8)))
                        execute(sql);
                }
                applied.add(number);
            }
            var expectedMigrations = new ArrayList<>(java.util.stream.IntStream.rangeClosed(1, 15).boxed().toList());
            expectedMigrations.addAll(List.of(21, 23, 35));
            assertEquals(expectedMigrations, applied,
                    "Use registered service migrations plus the incident insurance integration");
            tables = rows("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() "
                    + "AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME").stream().map(List::getFirst).toList();
            assertTrue(tables.containsAll(List.of("npc_utility_bills", "npc_incident_rolls", "npc_housing_moves",
                    "npc_tenancy_departures")));
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
    void cleanTables() throws SQLException {
        try (Connection c = database.getConnection()) {
            execute(c, "SET SESSION foreign_key_checks=0");
            try { for (String table : tables) execute(c, "DELETE FROM " + table); }
            finally { execute(c, "SET SESSION foreign_key_checks=1"); }
        }
        execute("INSERT INTO npc_housing_registry_lock(singleton_id) VALUES (1)");
    }

    @Test
    void emptyComponentsShareOneMillisecondCaptureAndRemainUnknownWithoutWrites() throws Exception {
        List<List<String>> before = data();
        try (ReadProbe probe = new ReadProbe()) {
            ServiceAssessment result = read(probe.routed, fixed(NOW.plusNanos(987654)));
            coherent(result, NOW);
            assertTrue(result.utilities().cases().isEmpty());
            assertTrue(result.incidents().cases().isEmpty());
            assertTrue(result.retention().cases().isEmpty());
            assertEquals(Set.of(Component.values()), result.unknownComponents());
            assertTrue(result.operatorScore().isEmpty());
            assertEquals(new ServiceAssessment.ScoreBounds(25, 75), result.scoreBounds());
            probe.assertSnapshot(3);
            probe.assertReturned();
            assertEquals(result, read(database, fixed(NOW)));
            assertEquals(before, data());
        }
    }

    @Test
    void realObligationAdmissionAndIncidentUseTheSameExistingTransaction() throws Exception {
        Receipt admission = admit(ADMITTED);
        Instant issued = NOW.minus(Duration.ofDays(2));
        UUID property = incidentProperty(issued);
        UtilityRepository.Receipt utility = obligation(property, issued);
        long incident = roll(property, issued);
        List<List<String>> before = data();
        try (ReadProbe probe = new ReadProbe()) {
            ServiceAssessment result = read(probe.routed, fixed(NOW));
            coherent(result, NOW);
            assertEquals(utility.bill(), result.utilities().cases().getFirst().bill());
            assertEquals(utility.eventId(), result.utilities().cases().getFirst().paymentEventId());
            assertEquals(incident, result.incidents().cases().getFirst().incidentId());
            assertEquals(admission, result.retention().cases().getFirst().admission());
            knownOverdueAndRetained(result);
            probe.assertSnapshot(8);
            probe.assertReturned();
            assertEquals(before, data());
        }
    }

    @Test
    void writerBetweenComponentsCannotMixNewIncidentOrDepartureIntoOldSnapshot() throws Exception {
        Receipt admission = admit(ADMITTED);
        Instant issued = NOW.minus(Duration.ofDays(2));
        UUID property = incidentProperty(issued);
        obligation(property, issued);
        ServiceAssessment before = read(database, fixed(NOW));
        assertTrue(before.incidents().cases().isEmpty());
        assertEquals(1, before.retention().count(RetentionAssessment.Outcome.RETAINED));
        CountDownLatch reached = new CountDownLatch(1), resume = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ReadProbe probe = new ReadProbe()) {
            probe.afterSql = sql -> {
                if (utilityQuery(sql)) {
                    reached.countDown();
                    await(resume, "reader was not released");
                }
            };
            Future<ServiceAssessment> pending = worker.submit(() -> read(probe.routed, fixed(NOW)));
            try {
                assertTrue(reached.await(10, TimeUnit.SECONDS), "utility query did not reach MariaDB");
                long incident = roll(property, issued);
                // Logical event clocks are fixed; these real producer transactions commit after START.
                assertTrue(new TenantRepository(database, fixed(ADMITTED.plus(Duration.ofDays(1))))
                        .vacate(admission.request().residentId()));
                List<List<String>> afterWriter = data();
                resume.countDown();
                ServiceAssessment original = pending.get(15, TimeUnit.SECONDS);
                assertEquals(before, original, "reopening even one component's transaction tears the assessment");
                assertNull(original.retention().cases().getFirst().departure());
                probe.assertSnapshot(8);
                probe.assertReturned();
                ServiceAssessment fresh = read(database, fixed(NOW));
                coherent(fresh, NOW);
                assertEquals(original.utilities(), fresh.utilities());
                assertEquals(incident, fresh.incidents().cases().getFirst().incidentId());
                assertEquals(1, fresh.retention().count(RetentionAssessment.Outcome.DEPARTED));
                assertNotNull(fresh.retention().cases().getFirst().departure());
                assertEquals(25, fresh.operatorScore().orElseThrow());
                assertEquals(afterWriter, data(), "only the two real writer transactions may change stored data");
            } finally {
                resume.countDown();
                worker.shutdownNow();
                assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
            }
        } finally { resume.countDown(); worker.shutdownNow(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"BEGIN", "UTILITY"})
    void capturePrecedesBeginAndDoesNotAdvanceDuringComponentSql(String phase) throws Exception {
        admit(NOW.minus(Duration.ofDays(HORIZON)));
        Instant created = NOW.minus(Duration.ofDays(1));
        UUID property = incidentProperty(created);
        UtilityRepository.Receipt utility = obligation(property, NOW.minusSeconds(3600));
        assertEquals(NOW, utility.bill().dueAt());
        roll(property, created);
        MutableClock clock = new MutableClock(NOW.minusMillis(1));
        List<List<String>> before = data();
        try (ReadProbe probe = new ReadProbe()) {
            probe.afterSql = sql -> {
                if (phase.equals("BEGIN") ? sql.startsWith("START TRANSACTION") : utilityQuery(sql)) clock.at.set(NOW);
            };
            ServiceAssessment original = read(probe.routed, clock);
            assertEquals(NOW, clock.instant(), "the real JDBC phase must have advanced the clock");
            coherent(original, NOW.minusMillis(1));
            assertTrue(original.utilities().cases().isEmpty());
            assertTrue(original.incidents().cases().isEmpty());
            assertEquals(1, original.retention().count(RetentionAssessment.Outcome.NOT_MATURED));
            assertEquals(Set.of(Component.values()), original.unknownComponents());
            assertTrue(original.operatorScore().isEmpty());
            probe.assertSnapshot(8);
            probe.assertReturned();
            knownOverdueAndRetained(read(database, clock));
            assertEquals(before, data());
        }
    }

    @ParameterizedTest
    @EnumSource(Component.class)
    void corruptionInAnyComponentRejectsWholeResultAndReturnsConnection(Component component) throws Exception {
        Receipt admission = admit(ADMITTED);
        Instant issued = NOW.minus(Duration.ofDays(2));
        UUID property = incidentProperty(issued);
        obligation(property, issued);
        long incident = roll(property, issued);
        knownOverdueAndRetained(read(database, fixed(NOW)));
        String diagnostic = switch (component) {
            case UTILITIES -> {
                execute("UPDATE npc_utility_bills SET payload_sha256=? WHERE property_id=?", "0".repeat(64), property);
                yield "fingerprint";
            }
            case INCIDENTS -> {
                execute("UPDATE npc_incidents SET cash_repair_cents=cash_repair_cents+1 WHERE incident_id=?", incident);
                yield "recorded service obligation";
            }
            case RETENTION -> {
                execute("UPDATE npc_housing_moves SET payload_hash=? WHERE request_id=?",
                        "0".repeat(64), admission.request().requestId());
                yield "Housing receipt binding mismatch";
            }
        };
        List<List<String>> corrupted = data();
        try (ReadProbe probe = new ReadProbe()) {
            SQLException failure = assertThrows(SQLException.class, () -> read(probe.routed, fixed(NOW)),
                    "corrupt " + component + " must not return a partial ServiceAssessment");
            assertTrue(causes(failure).contains(diagnostic), () -> causes(failure));
            probe.assertSnapshot(component.ordinal() + 1);
            probe.assertReturned();
            assertEquals(corrupted, data());
        }
    }

    @Test
    void wrongDatabaseAndClosedSnapshotRejectAllBorrowingReadersBeforeSql() throws Exception {
        try (ReadProbe owner = new ReadProbe(); ReadProbe other = new ReadProbe()) {
            ReadSnapshot snapshot = ReadSnapshot.open(owner.routed, fixed(NOW));
            try (snapshot) {
                assertThrows(SQLException.class, () -> snapshot.connection(other.routed));
                assertBorrowersReject(snapshot, other.routed);
                assertEquals(0, other.borrowed.size(), "even another facade over the same pool is the wrong Database");
                assertEquals(1, owner.sqls.size(), "only START, no component SQL");
                assertSame(owner.exposed, snapshot.connection(owner.routed));
            }
            assertThrows(SQLException.class, () -> snapshot.connection(owner.routed));
            assertBorrowersReject(snapshot, owner.routed);
            assertEquals(1, owner.sqls.size());
            owner.assertReturned();
        }
    }

    @Test
    void crossThreadConnectionAccessRejectsWithoutEndingOwnersTransaction() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ReadProbe probe = new ReadProbe()) {
            try (ReadSnapshot snapshot = ReadSnapshot.open(probe.routed, fixed(NOW))) {
                Future<SQLException> rejected = worker.submit(() ->
                        assertThrows(SQLException.class, () -> snapshot.connection(probe.routed)));
                assertNotNull(rejected.get(10, TimeUnit.SECONDS));
                assertSame(probe.exposed, snapshot.connection(probe.routed));
                assertEquals(0, probe.rollbacks);
                assertEquals(0, probe.closes);
                assertEquals(1, probe.sqls.size());
            } finally {
                worker.shutdownNow();
                assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
            }
            probe.assertReturned();
        } finally { worker.shutdownNow(); }
    }

    @Test
    void mariaDbActuallyRejectsWritesInsideReadOnlySnapshot() throws SQLException {
        List<List<String>> before = data();
        try (ReadSnapshot snapshot = ReadSnapshot.open(database, fixed(NOW))) {
            SQLException failure = assertThrows(SQLException.class, () -> execute(snapshot.connection(database),
                    "DELETE FROM npc_housing_registry_lock WHERE singleton_id=1"));
            assertEquals(1792, failure.getErrorCode(), "MariaDB read-only transaction error, not an unrelated SQL failure");
            assertEquals("1", rows(snapshot.connection(database),
                    "SELECT COUNT(*) FROM npc_housing_registry_lock").getFirst().getFirst());
        }
        assertEquals(before, data());
        assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
    }

    @Test
    void failedBorrowingReaderDoesNotRollbackOrCloseCallersSnapshot() throws Exception {
        Instant issued = NOW.minus(Duration.ofDays(2));
        UUID property = incidentProperty(issued);
        obligation(property, issued);
        execute("UPDATE npc_utility_bills SET payload_sha256=? WHERE property_id=?", "0".repeat(64), property);
        List<List<String>> before = data();
        try (ReadProbe probe = new ReadProbe()) {
            try (ReadSnapshot snapshot = ReadSnapshot.open(probe.routed, fixed(NOW))) {
                assertThrows(SQLException.class, () -> new UtilityServiceRepository(probe.routed, fixed(NOW))
                        .read(snapshot, OWNER, FROM));
                assertEquals(0, probe.rollbacks);
                assertEquals(0, probe.closes);
                assertTrue(new IncidentServiceRepository(probe.routed, fixed(NOW)).read(snapshot, OWNER, FROM).cases().isEmpty());
                assertTrue(new RetentionRepository(probe.routed, fixed(NOW), ZONE, RESET)
                        .read(snapshot, OWNER, FROM, HORIZON).cases().isEmpty());
            }
            probe.assertSnapshot(3);
            probe.assertReturned();
            assertEquals(before, data());
        }
    }

    @Test
    void emptyRetentionReadFirstLeavesSharedSnapshotOpenForOtherComponents() throws Exception {
        Instant issued = NOW.minus(Duration.ofDays(2));
        UUID property = incidentProperty(issued);
        UtilityRepository.Receipt utility = obligation(property, issued);
        long incident = roll(property, issued);
        List<List<String>> before = data();
        try (ReadProbe probe = new ReadProbe()) {
            try (ReadSnapshot snapshot = ReadSnapshot.open(probe.routed, fixed(NOW))) {
                var retention = new RetentionRepository(probe.routed, fixed(NOW), ZONE, RESET)
                        .read(snapshot, OWNER, FROM, HORIZON);
                assertTrue(retention.cases().isEmpty());
                assertEquals(0, probe.rollbacks, "the empty retention fast path must not end a borrowed transaction");
                assertEquals(0, probe.closes);
                var utilities = new UtilityServiceRepository(probe.routed, fixed(NOW)).read(snapshot, OWNER, FROM);
                var incidents = new IncidentServiceRepository(probe.routed, fixed(NOW)).read(snapshot, OWNER, FROM);
                coherent(new ServiceAssessment(utilities, incidents, retention), NOW);
                assertEquals(utility.bill(), utilities.cases().getFirst().bill());
                assertEquals(incident, incidents.cases().getFirst().incidentId());
                assertEquals(0, probe.rollbacks);
                assertEquals(0, probe.closes);
            }
            probe.assertSnapshot(3);
            probe.assertReturned();
            assertEquals(before, data());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SQL", "RUNTIME", "ERROR"})
    void failedRollbackStillClosesReturnsConnectionAndPreservesSuppression(String kind) throws Exception {
        try (ReadProbe probe = new ReadProbe()) {
            ReadSnapshot snapshot = ReadSnapshot.open(probe.routed, fixed(NOW));
            Throwable rollback = cleanupFailure(kind);
            SQLException close = new SQLException("injected close failure after returning real lease");
            probe.rollbackFailure = rollback;
            probe.closeFailure = close;
            assertSame(rollback, assertThrows(rollback.getClass(), snapshot::close));
            probe.assertReturned();
            assertArrayEquals(new Throwable[]{close}, rollback.getSuppressed());
            assertThrows(SQLException.class, () -> snapshot.connection(probe.routed));
            snapshot.close();
            probe.assertReturned();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SQL", "RUNTIME", "ERROR"})
    void failedOpenReturnsConnectionEvenWhenRollbackAndCloseAlsoFail(String kind) throws Exception {
        try (ReadProbe probe = new ReadProbe()) {
            SQLException begin = new SQLException("injected failure after MariaDB establishes snapshot");
            Throwable rollback = cleanupFailure(kind);
            SQLException close = new SQLException("injected close failure after returning real lease");
            probe.beginFailure = begin;
            probe.rollbackFailure = rollback;
            probe.closeFailure = close;
            assertSame(begin, assertThrows(SQLException.class, () -> ReadSnapshot.open(probe.routed, fixed(NOW))));
            probe.assertReturned();
            assertArrayEquals(new Throwable[]{rollback}, begin.getSuppressed());
            assertArrayEquals(new Throwable[]{close}, rollback.getSuppressed());
            probe.assertReturned();
        }
    }

    @ParameterizedTest
    @EnumSource(Component.class)
    void pureMissingComponentCannotBecomeANeutralScoreOrDenominatorDiscount(Component missing) {
        // Pure model control only: no manufactured rows are used by the composition tests.
        UUID property = new UUID(1, 3200), resident = new UUID(2, 3200);
        UtilityBill bill = new UtilityBill(property, OWNER, 0, 0, day(ADMITTED), 4, 1, 0,
                ADMITTED, ZONE.getId(), RESET);
        var utility = new UtilityServiceAssessment(OWNER, NOW, FROM, missing == Component.UTILITIES ? List.of()
                : List.of(new UtilityServiceAssessment.Case(bill, 1L, bill.dueAt(), UtilityServiceAssessment.Outcome.ON_TIME)));
        var incident = new IncidentServiceAssessment(OWNER, NOW, FROM, missing == Component.INCIDENTS ? List.of()
                : List.of(new IncidentServiceAssessment.Case(1, property, 0, ADMITTED, ADMITTED.plusSeconds(86400),
                        ADMITTED, 2L, IncidentServiceAssessment.Outcome.ON_TIME)));
        var request = new HousingMoveRequest(new UUID(3, 3200), resident, property, OWNER, WORLD,
                "downtown", "room", 0, 0, 0, -1, 0, day(ADMITTED));
        Receipt admission = new Receipt(request, POLICY, 0, day(ADMITTED).plusDays(1), ADMITTED);
        var retention = new RetentionAssessment(OWNER, NOW, FROM, HORIZON, missing == Component.RETENTION ? List.of()
                : List.of(new RetentionAssessment.Case(admission, RetentionAssessment.Outcome.RETAINED, null, null,
                        new RetentionAssessment.Occupancy(property, 0, 0))));
        ServiceAssessment result = new ServiceAssessment(utility, incident, retention);
        assertEquals(Set.of(missing), result.unknownComponents());
        assertTrue(result.operatorScore().isEmpty(), "unknown is neither zero, neutral nor assumed success");
        assertEquals(new ServiceAssessment.ScoreBounds(missing == Component.RETENTION ? 65 : 55, 75), result.scoreBounds());
    }

    private static Receipt admit(Instant at) throws SQLException {
        UUID property = UUID.randomUUID(), resident = UUID.randomUUID();
        enroll(property, at.minusSeconds(1));
        assertTrue(new ResidentRepository(database).register(ResidentCatalogue.draft(resident, "Service " + resident)));
        assertTrue(new HousingRegistry(database).admit(resident, HOUSING, day(at)));
        HousingMoveRepository moves = new HousingMoveRepository(database, fixed(at), ZONE, RESET);
        var result = moves.apply(moves.quote(UUID.randomUUID(), resident, property, 0, day(at)), POLICY);
        assertTrue(result.newlyApplied());
        assertEquals(at, result.receipt().appliedAt());
        return result.receipt();
    }

    private static void enroll(UUID property, Instant at) throws SQLException {
        new PropertyRepository(database).insertEnrollment(new Property(property, WORLD, "combined-" + property,
                "downtown", "room", 4, 1000, 1200, OWNER, 0, 80, true), SEED);
        // Enrollment uses SQL wall time; align only the real producer's observation to the fixture clock.
        execute("UPDATE npc_property_ownership SET observed_at=? WHERE property_id=? AND ownership_epoch=0", at, property);
    }

    private static UUID incidentProperty(Instant at) throws SQLException {
        SimulationLogic simulation = new SimulationLogic(new DailyRoll(SEED));
        for (int candidate = 1; candidate <= 100_000; candidate++) {
            UUID id = new UUID(0x434f4d504f5345L, candidate);
            if (simulation.incidentForDay(id, day(at).toString(), 80).orElse(null) == IncidentType.PESTS) {
                enroll(id, at.minusSeconds(1));
                return id;
            }
        }
        throw new AssertionError("No deterministic PESTS fixture within 100000 candidates");
    }

    private static UtilityRepository.Receipt obligation(UUID property, Instant issued) throws SQLException {
        var result = new UtilityRepository(database, RESERVE, fixed(issued), ZONE, RESET)
                .preparePayment(UUID.randomUUID(), property, OWNER, 0, day(issued)).receipt();
        assertEquals(UtilityRepository.State.WAITING, result.state());
        // The real event producer defaults to DB wall time; pin chronology, never fabricate payment proof.
        execute("UPDATE npc_economy_events SET created_at=? WHERE event_id=?", issued, result.eventId());
        return result;
    }

    private static long roll(UUID property, Instant at) throws SQLException {
        var result = new IncidentRollRepository(database, new SimulationLogic(new DailyRoll(SEED)), fixed(at), ZONE, RESET)
                .roll(property, OWNER, 0, day(at));
        assertTrue(result.newlyRecorded());
        assertEquals(IncidentRollRepository.Outcome.INCIDENT, result.outcome());
        assertNotNull(result.incidentId());
        return result.incidentId();
    }

    private static ServiceAssessment read(Database db, Clock clock) throws SQLException {
        return new ServiceAssessmentRepository(db, clock, ZONE, RESET).read(OWNER, FROM, HORIZON);
    }

    private static void coherent(ServiceAssessment result, Instant captured) {
        assertEquals(OWNER, result.ownerId());
        assertEquals(OWNER, result.utilities().ownerId());
        assertEquals(OWNER, result.incidents().ownerId());
        assertEquals(OWNER, result.retention().ownerId());
        assertEquals(captured, result.capturedAt());
        assertEquals(captured, result.utilities().capturedAt());
        assertEquals(captured, result.incidents().capturedAt());
        assertEquals(captured, result.retention().capturedAt());
        assertEquals(FROM, result.windowFrom());
        assertEquals(FROM, result.utilities().dueFrom());
        assertEquals(FROM, result.incidents().dueFrom());
        assertEquals(FROM, result.retention().admittedFrom());
        assertEquals(HORIZON, result.retention().horizonDays());
    }

    private static void knownOverdueAndRetained(ServiceAssessment result) {
        coherent(result, NOW);
        assertEquals(1, result.utilities().cases().size());
        assertEquals(1, result.incidents().cases().size());
        assertEquals(1, result.retention().cases().size());
        assertEquals(1, result.utilities().count(UtilityServiceAssessment.Outcome.OVERDUE));
        assertEquals(1, result.incidents().count(IncidentServiceAssessment.Outcome.OVERDUE));
        assertEquals(1, result.retention().count(RetentionAssessment.Outcome.RETAINED));
        assertTrue(result.unknownComponents().isEmpty());
        assertEquals(35, result.operatorScore().orElseThrow());
        assertEquals(new ServiceAssessment.ScoreBounds(35, 35), result.scoreBounds());
    }

    private static void assertBorrowersReject(ReadSnapshot snapshot, Database db) {
        assertThrows(SQLException.class, () -> new UtilityServiceRepository(db, fixed(NOW)).read(snapshot, OWNER, FROM));
        assertThrows(SQLException.class, () -> new IncidentServiceRepository(db, fixed(NOW)).read(snapshot, OWNER, FROM));
        assertThrows(SQLException.class, () -> new RetentionRepository(db, fixed(NOW), ZONE, RESET)
                .read(snapshot, OWNER, FROM, HORIZON));
    }

    private static Throwable cleanupFailure(String kind) {
        return switch (kind) {
            case "SQL" -> new SQLException("injected rollback SQL failure");
            case "RUNTIME" -> new IllegalStateException("injected rollback runtime failure");
            case "ERROR" -> new AssertionError("injected rollback error");
            default -> throw new IllegalArgumentException(kind);
        };
    }

    private static String causes(Throwable failure) {
        StringBuilder result = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) result.append(cause.getMessage()).append('\n');
        return result.toString();
    }

    @FunctionalInterface
    private interface SqlHook { void after(String sql) throws SQLException; }

    /** Timing and failure injection around real JDBC; the fixture pool is never replaced or closed. */
    private static final class ReadProbe implements AutoCloseable {
        private final Database routed = new Database();
        private final List<Connection> borrowed = new ArrayList<>();
        private final List<String> sqls = new ArrayList<>();
        private final List<String> connectionIds = new ArrayList<>();
        private final List<String> transactionFaults = new ArrayList<>();
        private Connection exposed;
        private int rollbacks, closes, commits;
        private SqlHook afterSql = sql -> { };
        private Throwable rollbackFailure;
        private SQLException closeFailure, beginFailure;

        private ReadProbe() throws ReflectiveOperationException {
            HikariDataSource forwarding = new HikariDataSource() {
                @Override public Connection getConnection() throws SQLException {
                    Connection actual = database.getConnection();
                    borrowed.add(actual);
                    exposed = proxy(Connection.class, (ignored, method, args) -> {
                        switch (method.getName()) {
                            case "rollback" -> {
                                rollbacks++;
                                if (rollbackFailure != null) throw rollbackFailure;
                            }
                            case "commit" -> commits++;
                            case "close" -> {
                                closes++;
                                Object result = invoke(actual, method, args);
                                if (closeFailure != null) throw closeFailure;
                                return result;
                            }
                            case "prepareStatement" -> {
                                String sql = ((String) args[0]).strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
                                PreparedStatement statement = (PreparedStatement) invoke(actual, method, args);
                                return proxy(PreparedStatement.class, (unused, operation, values) -> {
                                    boolean executing = operation.getName().startsWith("execute");
                                    if (executing) {
                                        sqls.add(sql);
                                        assertTrue(statement.getQueryTimeout() > 0 && statement.getQueryTimeout() <= 5,
                                                "component SQL must have a bounded JDBC deadline");
                                        if (actual.getAutoCommit() || actual.getTransactionIsolation() != Connection.TRANSACTION_REPEATABLE_READ)
                                            transactionFaults.add("Wrong transaction mode: " + sql);
                                        if (sql.startsWith("SELECT")) {
                                            if (rollbacks != 0 || commits != 0)
                                                transactionFaults.add("Component ended the shared transaction: " + sql);
                                            // Observe the real session before each component query, outside the SQL trace.
                                            List<String> session = rows(actual, "SELECT CONNECTION_ID(),@@in_transaction").getFirst();
                                            if (!"1".equals(session.get(1))) transactionFaults.add("Inactive server transaction: " + sql);
                                            connectionIds.add(session.getFirst());
                                        }
                                    }
                                    Object result = invoke(statement, operation, values);
                                    if (executing) {
                                        if (sql.startsWith("START TRANSACTION") && beginFailure != null) throw beginFailure;
                                        afterSql.after(sql);
                                    }
                                    return result;
                                });
                            }
                            default -> { }
                        }
                        return invoke(actual, method, args);
                    });
                    return exposed;
                }
            };
            sourceField().set(routed, forwarding);
        }

        private void assertSnapshot(int selects) {
            // Assert after outcome checks so concurrency mutants must reach their semantic consequence.
            assertTrue(transactionFaults.isEmpty(), transactionFaults::toString);
            assertEquals(1, borrowed.size(), "all components must share one connection, never reopen independent snapshots");
            List<String> begins = sqls.stream().filter(sql -> sql.startsWith("START TRANSACTION")).toList();
            assertEquals(1, begins.size());
            assertTrue(begins.getFirst().contains("WITH CONSISTENT SNAPSHOT"));
            assertTrue(begins.getFirst().contains("READ ONLY"));
            assertEquals(selects, sqls.stream().filter(sql -> sql.startsWith("SELECT")).count());
            assertEquals(selects, connectionIds.size());
            assertEquals(1, connectionIds.stream().distinct().count(), "each component reads the same MariaDB session");
            assertEquals(0, commits);
            assertTrue(sqls.stream().allMatch(sql -> sql.startsWith("SELECT") || sql.startsWith("START TRANSACTION")), sqls::toString);
            assertTrue(sqls.stream().noneMatch(sql -> sql.contains("FOR UPDATE") || sql.contains("LOCK IN SHARE MODE")));
            assertTrue(sqls.stream().anyMatch(ServiceAssessmentRepositoryIT::utilityQuery));
            if (selects >= 2) assertTrue(sqls.stream().anyMatch(sql -> sql.contains("FROM NPC_INCIDENT_ROLLS")));
            if (selects >= 3) assertTrue(sqls.stream().anyMatch(sql -> sql.contains("FROM NPC_HOUSING_MOVES")));
        }

        private void assertReturned() throws SQLException {
            assertEquals(1, rollbacks, "the owning snapshot rolls back exactly once");
            assertEquals(1, closes, "a failed rollback must still close its connection");
            for (Connection c : borrowed) assertTrue(c.isClosed(), "real pooled lease was not returned");
            assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
            List<Connection> leases = new ArrayList<>();
            try {
                for (int i = 0; i < pool.getMaximumPoolSize(); i++) leases.add(database.getConnection());
                for (Connection c : leases) assertTrue(c.isValid(3));
            } finally { for (Connection c : leases) c.close(); }
        }

        @Override public void close() throws SQLException {
            // Only after assertions: release leaked mutant/failing-test handles so later cases stay isolated.
            try { for (Connection actual : borrowed) if (!actual.isClosed()) actual.close(); }
            finally { routed.close(); }
        }
    }

    private static Field sourceField() throws ReflectiveOperationException {
        Field field = Database.class.getDeclaredField("dataSource");
        field.setAccessible(true);
        return field;
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
    }

    private static boolean utilityQuery(String sql) { return sql.startsWith("SELECT") && sql.contains("FROM NPC_UTILITY_BILLS"); }
    private static Clock fixed(Instant at) { return Clock.fixed(at, ZoneOffset.UTC); }
    private static LocalDate day(Instant at) { return new EconomyClock(fixed(at), ZONE, RESET).dayAt(at); }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> at;
        private MutableClock(Instant at) { this.at = new AtomicReference<>(at); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return ServiceAssessmentRepositoryIT.fixed(instant()).withZone(zone); }
        @Override public Instant instant() { return at.get(); }
    }

    private static void await(CountDownLatch latch, String message) {
        try { assertTrue(latch.await(15, TimeUnit.SECONDS), message); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(message, interrupted);
        }
    }

    private static List<List<String>> data() throws SQLException {
        List<List<String>> result = new ArrayList<>();
        try (Connection c = database.getConnection()) {
            for (String table : tables) {
                result.add(List.of(table));
                List<List<String>> contents = rows(c, "SELECT * FROM " + table);
                contents.sort(Comparator.comparing(Object::toString));
                result.addAll(contents);
            }
        }
        return result;
    }

    private static List<List<String>> rows(String sql, Object... values) throws SQLException {
        try (Connection c = database.getConnection()) { return rows(c, sql, values); }
    }

    private static List<List<String>> rows(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(5);
            bind(ps, values);
            try (ResultSet rs = ps.executeQuery()) {
                List<List<String>> result = new ArrayList<>();
                while (rs.next()) {
                    List<String> row = new ArrayList<>();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                        Object value = rs.getMetaData().getColumnType(i) == java.sql.Types.TIMESTAMP
                                ? rs.getObject(i, LocalDateTime.class) : rs.getObject(i);
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
            else if (values[i] instanceof Instant at) ps.setObject(i + 1, LocalDateTime.ofInstant(at, ZoneOffset.UTC));
            else if (values[i] instanceof LocalDate date) ps.setString(i + 1, date.toString());
            else ps.setObject(i + 1, values[i]);
        }
    }
}

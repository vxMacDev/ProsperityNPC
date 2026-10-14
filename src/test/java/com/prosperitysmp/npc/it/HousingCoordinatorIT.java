package com.prosperitysmp.npc.it;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.economy.UtilityRepository;
import com.prosperitysmp.npc.market.*;
import com.prosperitysmp.npc.market.HousingDecisionPlan.ColdStartPolicy;
import com.prosperitysmp.npc.market.HousingDecisionRepository.Options;
import com.prosperitysmp.npc.market.HousingMoveRepository.Result;
import com.prosperitysmp.npc.market.HousingMoveRequest.Policy;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;

import java.io.InputStream;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real V1..V15 SQL; proxies forward results and inject faults only at observed boundaries. */
@Timeout(60)
class HousingCoordinatorIT {
    private static final UUID WORLD = new UUID(0, 6000), OWNER = new UUID(0, 6001);
    private static final UUID HOME = new UUID(0, 6010), OTHER = new UUID(0, 6011);
    private static final UUID A = new UUID(0, 6020), B = new UUID(0, 6021), C = new UUID(0, 6022);
    private static final ZoneId ZONE = ZoneId.of("America/Chicago");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 7);
    private static final Instant NOW = DAY.atTime(12, 0).atZone(ZONE).toInstant();
    private static final Instant FROM = NOW.minus(Duration.ofDays(20));
    private static final Policy POLICY = new Policy(10, 5, 4, 7);
    private static DB maria;
    private static Database database;
    private static List<String> tables;
    private MutableClock clock;

    @BeforeAll static void boot() throws Exception {
        var cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        maria = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        try {
            maria.start();
            String schema = "npc_coordinator_" + UUID.randomUUID().toString().replace("-", "");
            maria.createDB(schema);
            database = new Database();
            database.connect(new DatabaseSettings("localhost", cfg.getPort(), schema, "root", "", 6, 10_000L));
            Field registered = MigrationRunner.class.getDeclaredField("MIGRATIONS");
            registered.setAccessible(true);
            List<Integer> versions = new ArrayList<>();
            for (Object migration : (List<?>) registered.get(null)) {
                Method version = migration.getClass().getDeclaredMethod("version");
                Method resource = migration.getClass().getDeclaredMethod("resource");
                version.setAccessible(true); resource.setAccessible(true);
                int number = (Integer) version.invoke(migration);
                // V21 and V35: the incident service read also checks item-repair receipts (item-repairs).
                if (number > 15 && number != 21 && number != 35) continue;
                try (InputStream in = HousingCoordinatorIT.class.getResourceAsStream("/" + resource.invoke(migration))) {
                    assertNotNull(in);
                    for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8)))
                        execute(sql);
                }
                versions.add(number);
            }
            var expected = new java.util.ArrayList<>(java.util.stream.IntStream.rangeClosed(1, 15).boxed().toList());
            expected.addAll(List.of(21, 35));
            assertEquals(expected, versions);
            tables = rows("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() "
                    + "AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME").stream().map(List::getFirst).toList();
        } catch (Exception | AssertionError failure) {
            try { shutdown(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @AfterAll static void shutdown() throws Exception {
        try { if (database != null) database.close(); }
        finally { if (maria != null) { DB stopping = maria; maria = null; stopping.stop(); } }
    }

    @BeforeEach void seed() throws SQLException {
        try (Connection c = database.getConnection()) {
            execute(c, "SET SESSION foreign_key_checks=0");
            try { for (String table : tables) execute(c, "DELETE FROM " + table); }
            finally { execute(c, "SET SESSION foreign_key_checks=1"); }
        }
        execute("INSERT INTO npc_housing_registry_lock(singleton_id) VALUES (1)");
        clock = new MutableClock(NOW);
        home(HOME, OWNER);
        resident(A);
        execute("INSERT INTO npc_audit_log(actor,action,detail) VALUES ('fixture','PRESERVE','Do not rewrite')");
    }

    @Test void readSharesOneSqlSnapshotAndPreBeginMillisecondCutoffWithoutAnyWrites() throws Exception {
        var before = data();
        clock.set(NOW.plusNanos(987654));
        try (Probe probe = new Probe()) {
            probe.afterBegin = () -> clock.set(NOW.plusSeconds(2));
            var plan = read(probe.routed, options(1));
            assertEquals(NOW, plan.snapshot().capturedAt());
            assertEquals(NOW, plan.evidence().get(OWNER).capturedAt());
            assertEquals(NOW, plan.evidence().get(OWNER).utilities().capturedAt());
            assertEquals(NOW, plan.evidence().get(OWNER).incidents().capturedAt());
            assertEquals(NOW, plan.evidence().get(OWNER).retention().capturedAt());
            assertEquals(1, probe.connections);
            assertEquals(1, probe.begins);
            assertEquals(0, probe.commits);
            assertEquals(1, probe.rollbacks);
            assertEquals(1, probe.closes);
            assertEquals(before, data());
        }
    }

    @Test void committedAdmissionBetweenMarketAndServiceReadsCannotTearTheDecision() throws Exception {
        try (Probe probe = new Probe()) {
            AtomicBoolean fired = new AtomicBoolean();
            probe.beforePrepare = sql -> {
                if (sql.contains("FROM npc_upgrades ORDER BY") && fired.compareAndSet(false, true)) {
                    var move = moves(database);
                    move.apply(move.quote(UUID.randomUUID(), A, HOME, 0, DAY), POLICY);
                }
            };
            var plan = read(probe.routed, options(1));
            assertTrue(fired.get(), "writer must run before the housing read finishes, before any second snapshot could start");
            assertEquals(1, plan.requests().size(), "market remains pre-admission");
            assertTrue(plan.evidence().get(OWNER).retention().cases().isEmpty(), "service must share that same pre-admission view");
            assertEquals(1, probe.connections);
            var committed = data();
            var fresh = read();
            assertTrue(fresh.requests().isEmpty());
            assertEquals(1, fresh.evidence().get(OWNER).retention().cases().size());
            assertEquals(committed, data());
        }
    }

    @Test void ownerBudgetRejectsWholeReadInsteadOfReturningPrefix() throws Exception {
        home(OTHER, new UUID(0, 6002));
        var before = data();
        try (Probe probe = new Probe()) {
            SQLException error = assertThrows(SQLException.class, () -> read(probe.routed, options(1)));
            assertEquals("Housing service owner budget exceeded", error.getMessage());
            assertEquals(0, probe.serviceQueries, "budget must reject before partial owner enrichment");
        }
        assertEquals(before, data());
        assertEquals(2, read(database, options(2)).evidence().size());
    }

    @Test void multipleHomesForSameOwnerConsumeOneOwnerBudget() throws Exception {
        home(OTHER, OWNER);
        var plan = read(database, options(1));
        assertEquals(2, plan.snapshot().homes().size());
        assertEquals(Set.of(OWNER), plan.evidence().keySet());
    }

    @Test void invalidEvidenceWindowRejectsEvenWhenThereAreNoHomes() throws Exception {
        execute("UPDATE npc_properties SET enabled=FALSE");
        var before = data();
        for (Instant from : List.of(NOW, NOW.plusMillis(1), NOW.minus(Duration.ofDays(30)).minusMillis(1))) {
            var opt = new Options(from, 7, POLICY, ColdStartPolicy.PROVISIONAL_50_V1, 1, 10000, Duration.ofSeconds(30));
            var error = assertThrows(SQLException.class, () -> read(database, opt));
            assertEquals("Housing evidence requires a positive <=30-day window", error.getMessage());
        }
        assertEquals(before, data());
    }

    @Test void coldStartAdmitsMultipleSlotsInOneHomeWithoutInventingPublicRating() throws Exception {
        resident(B); resident(C);
        var plan = read();
        assertEquals(3, plan.requests().size());
        assertEquals(List.of(0, 1, 2), plan.requests().stream().map(HousingMoveRequest::slot).toList());
        assertTrue(plan.evidence().get(OWNER).operatorScore().isEmpty());
        assertEquals(50, plan.ranks().get(HOME).planningOperatorScore().orElseThrow());
        var before = sideData();
        var results = coordinator(database).execute(plan);
        assertEquals(3, results.size());
        assertTrue(results.stream().allMatch(Result::newlyApplied));
        assertEquals(plan.requests(), results.stream().map(r -> r.receipt().request()).toList());
        assertEquals(3, count("npc_tenants"));
        assertEquals(3, count("npc_housing_moves"));
        assertEquals(7, read().snapshot().remaining().server());
        assertEquals(before, sideData(), "admission must not alter balances, profiles, service evidence or property revisions");
    }

    @Test void exactRetryReturnsSameReceiptsAndConsumesNoMoreQuota() throws Exception {
        resident(B);
        var plan = read();
        var first = coordinator(database).execute(plan);
        var before = data();
        var retry = coordinator(database).execute(plan);
        assertTrue(retry.stream().noneMatch(Result::newlyApplied));
        assertEquals(first.stream().map(Result::receipt).toList(), retry.stream().map(Result::receipt).toList());
        assertEquals(before, data());
        assertThrows(UnsupportedOperationException.class, () -> retry.clear());
    }

    @Test void differentTargetForSameResidentDayConflictsWithFirstPlanReceipt() throws Exception {
        home(OTHER, OWNER);
        execute("UPDATE npc_properties SET condition_score=60 WHERE property_id=?", HOME);
        var first = read();
        execute("UPDATE npc_properties SET condition_score=100,row_version=row_version+1 WHERE property_id=?", OTHER);
        var second = read();
        assertEquals(HOME, first.requests().getFirst().propertyId());
        assertEquals(OTHER, second.requests().getFirst().propertyId());
        assertEquals(first.requests().getFirst().requestId(), second.requests().getFirst().requestId());
        coordinator(database).execute(first);
        var before = data();
        var error = assertThrows(HousingCoordinator.Interrupted.class, () -> coordinator(database).execute(second));
        assertEquals("Housing request payload conflict", error.getCause().getMessage());
        assertTrue(error.completed().isEmpty());
        assertEquals(second.requests().getFirst(), error.unresolved());
        assertEquals(before, data());
    }

    @Test void staleRevisionAfterFirstCommitStopsAndFullRetryStopsAtSameUnresolvedRequest() throws Exception {
        resident(B); resident(C);
        var plan = read();
        try (Probe probe = new Probe()) {
            probe.afterCommit = number -> {
                if (number == 1) execute("UPDATE npc_properties SET row_version=row_version+1 WHERE property_id=?", HOME);
            };
            var error = assertThrows(HousingCoordinator.Interrupted.class, () -> coordinator(probe.routed).execute(plan));
            assertSame(plan, error.plan());
            assertEquals(1, error.completed().size());
            assertTrue(error.completed().getFirst().newlyApplied());
            assertEquals(plan.requests().get(1), error.unresolved());
            assertEquals("Housing property changed", error.getCause().getMessage());
            assertTrue(moves(database).find(plan.requests().get(2).requestId()).isEmpty());
            assertThrows(UnsupportedOperationException.class, () -> error.completed().clear());
        }
        var before = data();
        var retry = assertThrows(HousingCoordinator.Interrupted.class, () -> coordinator(database).execute(plan));
        assertEquals(1, retry.completed().size());
        assertFalse(retry.completed().getFirst().newlyApplied());
        assertEquals(plan.requests().get(1), retry.unresolved());
        assertEquals(before, data());
        assertEquals(1, count("npc_housing_moves"));
        assertEquals(9, read().snapshot().remaining().server());
    }

    @Test void ownershipRoundTripAfterFirstCommitStillInvalidatesRemainingRequests() throws Exception {
        resident(B); resident(C);
        var plan = read();
        try (Probe probe = new Probe()) {
            probe.afterCommit = number -> {
                if (number == 1) {
                    execute("UPDATE npc_properties SET owner_uuid=?,ownership_epoch=ownership_epoch+1,row_version=row_version+1 WHERE property_id=?", new UUID(0, 6999), HOME);
                    execute("UPDATE npc_properties SET owner_uuid=?,ownership_epoch=ownership_epoch+1,row_version=row_version+1 WHERE property_id=?", OWNER, HOME);
                }
            };
            var error = assertThrows(HousingCoordinator.Interrupted.class, () -> coordinator(probe.routed).execute(plan));
            assertEquals(1, error.completed().size());
            assertEquals(plan.requests().get(1), error.unresolved());
            assertEquals("Housing property changed", error.getCause().getMessage());
            assertEquals(1, count("npc_housing_moves"));
            assertTrue(moves(database).find(plan.requests().get(2).requestId()).isEmpty());
        }
    }

    @Test void commitAcknowledgementLossIsUnresolvedNotRolledBackAndExactRetryRecovers() throws Exception {
        resident(B); resident(C);
        var plan = read();
        try (Probe probe = new Probe()) {
            probe.loseAcknowledgementAt = 2;
            var error = assertThrows(HousingCoordinator.Interrupted.class, () -> coordinator(probe.routed).execute(plan));
            assertEquals(1, error.completed().size());
            assertEquals(plan.requests().get(1), error.unresolved());
            assertEquals("injected lost commit acknowledgement", error.getCause().getMessage());
            assertEquals(2, count("npc_housing_moves"), "second commit really happened despite exception");
            assertTrue(moves(database).find(error.unresolved().requestId()).isPresent());
            assertTrue(moves(database).find(plan.requests().get(2).requestId()).isEmpty());
        }
        var retry = coordinator(database).execute(plan);
        assertEquals(List.of(false, false, true), retry.stream().map(Result::newlyApplied).toList());
        assertEquals(3, count("npc_housing_moves"));
        assertEquals(3, count("npc_tenants"));
        assertEquals(7, read().snapshot().remaining().server());
    }

    @Test void priorDayUncommittedPlanFailsWithoutPinningOrCustody() throws Exception {
        var plan = read();
        clock.set(NOW.plus(Duration.ofDays(1)));
        var before = data();
        var error = assertThrows(HousingCoordinator.Interrupted.class, () -> coordinator(database).execute(plan));
        assertTrue(error.completed().isEmpty());
        assertEquals(plan.requests().getFirst(), error.unresolved());
        assertEquals(before, data());
    }

    @Test void rolloverAfterFirstCommitReplaysReceiptButNeverAppliesOldDayRemainder() throws Exception {
        resident(B); resident(C);
        var plan = read();
        try (Probe probe = new Probe()) {
            probe.afterCommit = number -> { if (number == 1) clock.set(NOW.plus(Duration.ofDays(1))); };
            var error = assertThrows(HousingCoordinator.Interrupted.class, () -> coordinator(probe.routed).execute(plan));
            assertEquals(1, error.completed().size());
            assertEquals(plan.requests().get(1), error.unresolved());
        }
        var before = data();
        var retry = assertThrows(HousingCoordinator.Interrupted.class, () -> coordinator(database).execute(plan));
        assertFalse(retry.completed().getFirst().newlyApplied());
        assertEquals(plan.requests().get(1), retry.unresolved());
        assertEquals(1, count("npc_housing_moves"));
        assertEquals(before, data());
    }

    @Test void rolloverDuringReadRejectsWholePlanWithoutWrites() throws Exception {
        var before = data();
        try (Probe probe = new Probe()) {
            AtomicBoolean fired = new AtomicBoolean();
            probe.beforePrepare = sql -> {
                if (sql.contains("npc_utility_bills") && fired.compareAndSet(false, true))
                    clock.set(DAY.plusDays(1).atTime(4, 0).atZone(ZONE).toInstant());
            };
            assertThrows(SQLException.class, () -> read(probe.routed, options(1)));
            assertTrue(fired.get());
        }
        assertEquals(before, data());
    }

    @Test void holdUnratedProducesEmptyExecutionWithoutDayPin() throws Exception {
        var before = data();
        var plan = read(database, new Options(FROM, 7, POLICY, ColdStartPolicy.HOLD_UNRATED, 1, 10000, Duration.ofSeconds(30)));
        assertTrue(plan.requests().isEmpty());
        assertTrue(coordinator(database).execute(plan).isEmpty());
        assertEquals(before, data());
    }

    @Test void aggregateEvidenceBudgetRejectsTwoValidOwnerCohortsRatherThanReturningOne() throws Exception {
        UUID secondOwner = new UUID(0, 6002);
        home(OTHER, secondOwner); resident(B);
        var moves = moves(database);
        moves.apply(moves.quote(UUID.randomUUID(), A, HOME, 0, DAY), POLICY);
        moves.apply(moves.quote(UUID.randomUUID(), B, OTHER, 0, DAY), POLICY);
        var before = data();
        var allowed = new Options(FROM, 7, POLICY, ColdStartPolicy.PROVISIONAL_50_V1, 2, 2, Duration.ofSeconds(30));
        var complete = read(database, allowed);
        assertEquals(1, complete.evidence().get(OWNER).retention().cases().size());
        assertEquals(1, complete.evidence().get(secondOwner).retention().cases().size());
        try (Probe probe = new Probe()) {
            var denied = new Options(FROM, 7, POLICY, ColdStartPolicy.PROVISIONAL_50_V1, 2, 1, Duration.ofSeconds(30));
            var error = assertThrows(SQLException.class, () -> read(probe.routed, denied));
            assertEquals("Housing aggregate evidence budget exceeded", error.getMessage());
            assertEquals(2, probe.serviceQueries, "both individually valid cohorts must be read before aggregate rejection");
            assertEquals(1, probe.rollbacks);
            assertEquals(1, probe.closes);
            assertEquals(0, probe.commits);
        }
        assertEquals(before, data());
    }

    @Test void utilityProducerCommitAfterHousingReadIsInvisibleUntilNextDecision() throws Exception {
        try (Probe probe = new Probe()) {
            AtomicBoolean fired = new AtomicBoolean();
            probe.beforePrepare = sql -> {
                if (sql.contains("FROM npc_upgrades ORDER BY") && fired.compareAndSet(false, true)) {
                    var producer = new UtilityRepository(database, new UUID(0, 6998),
                            Clock.fixed(NOW.minus(Duration.ofDays(2)), ZoneOffset.UTC), ZONE, 4);
                    var preparation = producer.preparePayment(UUID.randomUUID(), HOME, OWNER, 3, DAY.minusDays(2));
                    assertTrue(preparation.newlyQueued());
                    assertEquals(UtilityRepository.State.WAITING, preparation.receipt().state());
                    // Producer uses DB wall time; align the fixture's event chronology, not payment evidence.
                    execute("UPDATE npc_economy_events SET created_at=? WHERE event_id=?",
                            LocalDateTime.ofInstant(NOW.minus(Duration.ofDays(2)), ZoneOffset.UTC), preparation.receipt().eventId());
                }
            };
            var plan = read(probe.routed, options(1));
            assertTrue(fired.get());
            assertTrue(plan.evidence().get(OWNER).utilities().cases().isEmpty());
            assertEquals(1, plan.requests().size());
            assertEquals(1, probe.connections);
            var afterWriter = data();
            var fresh = read();
            assertEquals(1, fresh.evidence().get(OWNER).utilities().cases().size());
            assertEquals(0.0, fresh.evidence().get(OWNER).utilities().onTimeRate().orElseThrow());
            assertEquals(50, plan.ranks().get(HOME).planningOperatorScore().orElseThrow());
            assertEquals(40, fresh.ranks().get(HOME).planningOperatorScore().orElseThrow(),
                    "known unpaid matured bill must affect the next plan, not be replaced by a prior");
            assertEquals(afterWriter, data());
        }
    }

    @Test void elapsedBudgetAfterRealBeginClosesAndRollsBackWithoutReturningPlan() throws Exception {
        var before = data();
        try (Probe probe = new Probe()) {
            // A real monotonic delay at a real SQL boundary, not the injectable economy clock.
            probe.afterBegin = () -> {
                long until = System.nanoTime() + Duration.ofMillis(300).toNanos();
                while (System.nanoTime() < until) java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
            };
            var limited = new Options(FROM, 7, POLICY, ColdStartPolicy.PROVISIONAL_50_V1, 1, 10000, Duration.ofMillis(200));
            var error = assertThrows(SQLException.class, () -> read(probe.routed, limited));
            assertEquals("Housing decision elapsed budget exceeded", error.getMessage());
            assertEquals(1, probe.begins, "must reach injected boundary rather than pass from slow fixture startup");
            assertEquals(1, probe.rollbacks);
            assertEquals(1, probe.closes);
            assertEquals(0, probe.commits);
        }
        assertEquals(before, data());
    }

    @Test void preInterruptedExecutionDoesNoSqlAndRetainsFirstUnresolvedRequest() throws Exception {
        var plan = read();
        var before = data();
        try (Probe probe = new Probe()) {
            HousingCoordinator.Interrupted error;
            try {
                Thread.currentThread().interrupt();
                error = assertThrows(HousingCoordinator.Interrupted.class, () -> coordinator(probe.routed).execute(plan));
                assertTrue(Thread.currentThread().isInterrupted(), "coordinator must preserve cancellation status");
            } finally { Thread.interrupted(); }
            assertSame(plan, error.plan());
            assertTrue(error.completed().isEmpty());
            assertEquals(plan.requests().getFirst(), error.unresolved());
            assertEquals(0, probe.connections);
        }
        assertEquals(before, data());
    }

    private Options options(int owners) { return new Options(FROM, 7, POLICY, ColdStartPolicy.PROVISIONAL_50_V1, owners, 10000, Duration.ofSeconds(30)); }
    private HousingDecisionPlan read() throws SQLException { return read(database, options(4)); }
    private HousingDecisionPlan read(Database db, Options options) throws SQLException {
        return new HousingDecisionRepository(db, clock, ZONE, 4).read(DAY, options);
    }
    private HousingMoveRepository moves(Database db) { return new HousingMoveRepository(db, clock, ZONE, 4); }
    private HousingCoordinator coordinator(Database db) { return new HousingCoordinator(moves(db)); }
    private static void resident(UUID id) throws SQLException {
        assertTrue(new ResidentRepository(database).register(ResidentCatalogue.draft(id, "Resident " + id)));
        assertTrue(new HousingRegistry(database).admit(id, new HousingPolicy(WORLD, "downtown", "room",
                HousingPolicy.Tier.STANDARD, 1200, 40), DAY.minusDays(30)));
    }
    private static void home(UUID id, UUID owner) throws SQLException {
        execute("""
                INSERT INTO npc_properties(property_id,world_uuid,region_id,market_id,class_key,capacity,
                  reference_rent_cents,asking_rent_cents,owner_uuid,ownership_epoch,condition_score,enabled,row_version)
                VALUES (?,? ,?,'downtown','room',4,1000,1200,?,3,40,TRUE,11)
                """, id, WORLD, "fixture-" + id, owner);
    }
    private static int count(String table) throws SQLException { return Integer.parseInt(rows("SELECT COUNT(*) FROM " + table).getFirst().getFirst()); }
    private static Map<String, List<List<String>>> data() throws SQLException {
        var result = new LinkedHashMap<String, List<List<String>>>();
        for (String table : tables) result.put(table, rows("SELECT * FROM " + table));
        return result;
    }
    private static Map<String, List<List<String>>> sideData() throws SQLException {
        var result = data();
        for (String table : List.of("npc_tenants", "npc_housing_days", "npc_housing_moves")) result.remove(table);
        return result;
    }
    private static List<List<String>> rows(String sql) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(5);
            try (ResultSet rs = ps.executeQuery()) {
                var result = new ArrayList<List<String>>();
                while (rs.next()) {
                    var row = new ArrayList<String>();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                        Object value = rs.getObject(i);
                        row.add(value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : rs.getString(i));
                    }
                    result.add(row);
                }
                result.sort(Comparator.comparing(List::toString));
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
            for (int i = 0; i < values.length; i++) {
                if (values[i] instanceof UUID id) ps.setBytes(i + 1, Uuids.toBytes(id));
                else ps.setObject(i + 1, values[i]);
            }
            ps.execute();
        }
    }

    @FunctionalInterface private interface Action { void run() throws SQLException; }
    @FunctionalInterface private interface SqlAction { void run(String sql) throws SQLException; }
    @FunctionalInterface private interface CommitAction { void run(int number) throws SQLException; }

    /** Private forwarding pool; never replace the shared fixture's pool or synthesize SQL results. */
    private static final class Probe implements AutoCloseable {
        final Database routed = new Database();
        int connections, begins, commits, rollbacks, closes, serviceQueries;
        int loseAcknowledgementAt = -1;
        Throwable hookFailure;
        Action afterBegin = () -> {};
        SqlAction beforePrepare = sql -> {};
        CommitAction afterCommit = number -> {};
        Probe() throws ReflectiveOperationException {
            var forwarding = new HikariDataSource() {
                @Override public Connection getConnection() throws SQLException {
                    connections++;
                    return wrap(database.getConnection());
                }
            };
            Field field = Database.class.getDeclaredField("dataSource");
            field.setAccessible(true); field.set(routed, forwarding);
        }
        private Connection wrap(Connection actual) {
            return proxy(Connection.class, (ignored, method, args) -> {
                if (method.getName().equals("prepareStatement") && args[0] instanceof String sql) {
                    if (sql.contains("npc_utility_bills")) serviceQueries++;
                    runHook(() -> beforePrepare.run(sql));
                    PreparedStatement prepared = (PreparedStatement) invoke(actual, method, args);
                    if (sql.startsWith("START TRANSACTION")) return proxy(PreparedStatement.class, (p, call, parameters) -> {
                        Object result = invoke(prepared, call, parameters);
                        if (call.getName().equals("execute")) { begins++; runHook(afterBegin); }
                        return result;
                    });
                    return prepared;
                }
                Object result = invoke(actual, method, args);
                if (method.getName().equals("commit")) {
                    commits++;
                    runHook(() -> afterCommit.run(commits));
                    if (commits == loseAcknowledgementAt) {
                        loseAcknowledgementAt = -1;
                        throw new SQLException("injected lost commit acknowledgement");
                    }
                }
                if (method.getName().equals("rollback")) rollbacks++;
                if (method.getName().equals("close")) closes++;
                return result;
            });
        }
        private void runHook(Action action) throws SQLException {
            try { action.run(); }
            catch (SQLException | RuntimeException | Error failure) { hookFailure = failure; throw failure; }
        }
        @Override public void close() {
            try { routed.close(); }
            finally { assertNull(hookFailure, "fixture hook failed; this cannot count as an expected domain rejection"); }
        }
    }
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException error) { throw error.getCause(); }
    }
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        private final ZoneId zone;
        MutableClock(Instant instant) { this(new AtomicReference<>(instant), ZoneOffset.UTC); }
        private MutableClock(AtomicReference<Instant> now, ZoneId zone) { this.now = now; this.zone = zone; }
        void set(Instant instant) { now.set(instant); }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) { return new MutableClock(now, zone); }
        @Override public Instant instant() { return now.get(); }
    }
}

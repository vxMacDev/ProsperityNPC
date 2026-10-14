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

/** Evicted-slot lock (housing.evicted-slot-lock-days) 0 versus 2 against real V1..V15 SQL. */
@Timeout(60)
class EvictedSlotLockIT {
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
            String schema = "npc_evict_lock_" + UUID.randomUUID().toString().replace("-", "");
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
                try (InputStream in = EvictedSlotLockIT.class.getResourceAsStream("/" + resource.invoke(migration))) {
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
        singleRoom(HOME, OWNER);
        resident(A);
        execute("INSERT INTO npc_audit_log(actor,action,detail) VALUES ('fixture','PRESERVE','Do not rewrite')");
    }

    private static final UUID EVICTED = new UUID(0, 6030);

    @Test void lockTwoSkipsTheSlotOnDayPlusOneAndFillsItOnDayPlusTwo() throws Exception {
        evictAtNow();
        var plus1 = read(DAY.plusDays(1), 2);
        assertTrue(plus1.requests().isEmpty(), "evicted room is closed on day+1");
        var plus2 = read(DAY.plusDays(2), 2);
        assertEquals(1, plus2.requests().size());
        assertEquals(HOME, plus2.requests().getFirst().propertyId());
        assertEquals(0, plus2.requests().getFirst().slot());
    }

    @Test void lockZeroLetsTheSlotRefillOnDayPlusOne() throws Exception {
        evictAtNow();
        var plus1 = read(DAY.plusDays(1), 0);
        assertEquals(1, plus1.requests().size());
        assertEquals(0, plus1.requests().getFirst().slot());
    }

    @Test void lockTwoAlsoClosesTheSlotOnTheEvictionDayItself() throws Exception {
        evictAtNow();
        assertTrue(read(DAY, 2).requests().isEmpty());
    }

    @Test void applyAndQuoteRefuseALockedSlotButAcceptItWhenOpen() throws Exception {
        evictAtNow();
        clock.set(NOW.plus(Duration.ofDays(1)));
        var locked = moves(2);
        assertThrows(SQLException.class, () -> locked.quote(UUID.randomUUID(), A, HOME, 0, DAY.plusDays(1)));
        assertNotNull(moves(0).quote(UUID.randomUUID(), A, HOME, 0, DAY.plusDays(1)), "with the lock days at 0 the slot is not locked");
        clock.set(NOW.plus(Duration.ofDays(2)));
        assertNotNull(moves(2).quote(UUID.randomUUID(), A, HOME, 0, DAY.plusDays(2)));
    }

    @Test void ordinaryDepartureDoesNotLock() throws Exception {
        evictAtNowAs("DEPARTURE");
        assertEquals(1, read(DAY.plusDays(1), 2).requests().size());
    }

    private void evictAtNow() throws Exception { evictAtNowAs("PAID_EVICTION"); }

    private void evictAtNowAs(String cause) throws Exception {
        boolean paid = cause.equals("PAID_EVICTION");
        try (Connection c = database.getConnection()) {
            execute(c, "SET SESSION foreign_key_checks=0");
            try {
                if (paid) execute(c, "INSERT INTO npc_economy_events(event_id,idempotency_key,requested_amount_cents,paid_amount_cents,source,direction,property_id,economy_day,state) "
                        + "VALUES (9001,'evict-lock-fixture',1000,1000,'npc-property-eviction','SINK',?,?,'SUCCESS')", HOME, DAY.toString());
                var d = new com.prosperitysmp.npc.property.TenancyDeparture(EVICTED, HOME, OWNER, 3, 0, 0, NOW,
                        paid ? com.prosperitysmp.npc.property.TenancyDeparture.Cause.PAID_EVICTION
                             : com.prosperitysmp.npc.property.TenancyDeparture.Cause.DEPARTURE, paid ? 9001L : null);
                execute(c, "INSERT INTO npc_tenancy_departures(resident_id,occupancy_revision_before,occupancy_revision_after,property_id,"
                        + "owner_at_departure,ownership_epoch,slot,departed_at,cause,purchase_event_id,payload_sha256) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                        EVICTED, 0L, 1L, HOME, OWNER, 3, 0, LocalDateTime.ofInstant(NOW, ZoneOffset.UTC), cause,
                        paid ? 9001L : null, d.fingerprint());
            } finally { execute(c, "SET SESSION foreign_key_checks=1"); }
        }
    }

    private HousingDecisionPlan read(LocalDate day, int lockDays) throws SQLException {
        clock.set(day.atTime(12, 0).atZone(ZONE).toInstant());
        return new HousingDecisionRepository(database, clock, ZONE, 4, lockDays).read(day,
                new Options(FROM, 7, POLICY, ColdStartPolicy.PROVISIONAL_50_V1, 4, 10000, Duration.ofSeconds(30)));
    }
    private HousingMoveRepository moves(int lockDays) { return new HousingMoveRepository(database, clock, ZONE, 4, false, lockDays); }
    private static void singleRoom(UUID id, UUID owner) throws SQLException {
        execute("""
                INSERT INTO npc_properties(property_id,world_uuid,region_id,market_id,class_key,capacity,
                  reference_rent_cents,asking_rent_cents,owner_uuid,ownership_epoch,condition_score,enabled,row_version)
                VALUES (?,? ,?,'downtown','room',1,1000,1200,?,3,40,TRUE,11)
                """, id, WORLD, "fixture-" + id, owner);
    }
    private static void resident(UUID id) throws SQLException {
        assertTrue(new ResidentRepository(database).register(ResidentCatalogue.draft(id, "Resident " + id)));
        assertTrue(new HousingRegistry(database).admit(id, new HousingPolicy(WORLD, "downtown", "room",
                HousingPolicy.Tier.STANDARD, 1200, 40), DAY.minusDays(30)));
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

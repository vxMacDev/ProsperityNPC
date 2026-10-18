package com.prosperitysmp.npc.it;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyRepository;
import com.prosperitysmp.npc.property.Tenant;
import com.prosperitysmp.npc.property.TenantRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Embedded SQL contract tests; rehousing is trusted storage, not cooldown eligibility policy. */
@Timeout(45)
class TenantContinuityIT {
    private static DB mariaDb;
    private static Database database;
    private static String schemaName;
    private TenantRepository tenants;
    private UUID source;
    private UUID target;
    private UUID tenant;

    @BeforeAll
    static void bootDatabase() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        try {
            mariaDb.start();
            schemaName = "npc_continuity_" + UUID.randomUUID().toString().replace("-", "");
            mariaDb.createDB(schemaName);
            database = new Database();
            database.connect(new DatabaseSettings("localhost", cfg.getPort(), schemaName, "root", "", 6, 10_000L));
            for (String path : List.of("/db/migration/V1__initial_schema.sql", "/db/migration/V15__tenancy_departures.sql", "/db/migration/V45__property_backend.sql")) {
                try (InputStream in = TenantContinuityIT.class.getResourceAsStream(path)) {
                    assertNotNull(in, path);
                    for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8))) {
                        execute(sql);
                    }
                }
            }
        } catch (Exception | AssertionError failure) {
            try { stopDatabase(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        try {
            if (database != null) database.close();
        } finally {
            if (mariaDb != null) {
                DB stopping = mariaDb;
                mariaDb = null;
                stopping.stop();
            }
        }
    }

    @BeforeEach
    void freshTables() throws SQLException {
        for (String table : List.of("npc_tenancy_departures", "npc_tenants", "npc_property_ownership", "npc_property_funding", "npc_properties")) {
            execute("DELETE FROM " + table);
        }
        tenants = new TenantRepository(database);
        source = enroll("source", 2);
        target = enroll("target", 2);
        tenant = insertTenant(source, 0);
    }

    @Test
    void vacateRetainsIdentityAndOnlyClearsHousingAndAdvancesRevision() throws SQLException {
        Map<String, String> before = row(tenant);
        UUID neighbor = insertTenant(source, 1);
        Map<String, String> neighborBefore = row(neighbor);
        assertTrue(tenants.vacate(tenant));
        assertHousingChange(before, null, null, 8);
        assertEquals(neighborBefore, row(neighbor));
        assertEquals(1, tenants.countByProperty(source));
        assertEquals(0, tenants.firstFreeSlot(source, 2));
        assertEquals(List.of(neighbor), tenants.listByProperty(source).stream().map(Tenant::tenantId).toList());
    }

    @Test
    void repeatedVacateIsAnExactNoOpEvenWithANewRepository() throws SQLException {
        assertTrue(tenants.vacate(tenant));
        List<Map<String, String>> before = tenantRows();
        assertFalse(new TenantRepository(database).vacate(tenant));
        assertEquals(before, tenantRows());
    }

    @Test
    void missingVacateReturnsFalseWithoutCreatingAnything() throws SQLException {
        List<Map<String, String>> before = tenantRows();
        assertFalse(tenants.vacate(UUID.randomUUID()));
        assertEquals(before, tenantRows());
    }

    @Test
    @SuppressWarnings("deprecation")
    void legacyDeleteVacatesAndRemainsIdempotent() throws SQLException {
        Map<String, String> before = row(tenant);
        tenants.delete(tenant);
        assertHousingChange(before, null, null, 8);
        List<Map<String, String>> vacant = tenantRows();
        tenants.delete(tenant);
        tenants.delete(UUID.randomUUID());
        assertEquals(vacant, tenantRows());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void duplicateFreshInsertNeverResetsAnExistingIdentity(boolean vacant) throws SQLException {
        if (vacant) assertTrue(tenants.vacate(tenant));
        List<Map<String, String>> before = tenantRows();
        assertThrows(SQLException.class, () -> tenants.insert(
                new Tenant(tenant, "Replacement", target, 1, "STANDARD", "GENERIC", 50)));
        assertEquals(before, tenantRows());
        assertEquals(0, tenants.countByProperty(target));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"9999-12-31"})
    void rehousePreservesEveryOtherFieldAndDoesNotEnforceCooldown(String cooldown) throws SQLException {
        execute("UPDATE npc_tenants SET move_cooldown_until_day=? WHERE tenant_id=?", cooldown, tenant);
        assertTrue(tenants.vacate(tenant));
        Map<String, String> before = row(tenant);
        tenants.rehouse(tenant, 8, target, 1);
        assertHousingChange(before, target, 1, 9);
        assertEquals(0, tenants.countByProperty(source));
        assertEquals(1, tenants.countByProperty(target));
        assertEquals(tenant, tenants.listByProperty(target).getFirst().tenantId());
        assertRejectedUnchanged(() -> tenants.rehouse(tenant, 8, source, 0));
    }

    @ParameterizedTest
    @ValueSource(longs = {6, 8})
    void staleOrFutureExpectedRevisionRejectsVacantTenant(long expected) throws SQLException {
        makeVacant(tenant);
        assertRejectedUnchanged(() -> tenants.rehouse(tenant, expected, target, 0));
    }

    @Test
    void occupiedTenantRejectsEvenWithMatchingRevision() throws SQLException {
        assertRejectedUnchanged(() -> tenants.rehouse(tenant, 7, target, 0));
        assertRejectedUnchanged(() -> tenants.rehouse(tenant, 7, source, 0));
    }

    @Test
    void missingTenantCannotBeRecreatedByRehouse() throws SQLException {
        assertRejectedUnchanged(() -> tenants.rehouse(UUID.randomUUID(), 0, target, 0));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingPropertyOrOwnerRejectsWithoutChangingVacantTenant(boolean ownerless) throws SQLException {
        makeVacant(tenant);
        UUID destination = ownerless ? target : UUID.randomUUID();
        if (ownerless) execute("UPDATE npc_properties SET owner_uuid=NULL WHERE property_id=?", target);
        assertRejectedUnchanged(() -> tenants.rehouse(tenant, 7, destination, 0));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 2, 127, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void invalidSlotRejectsWithoutChangingAnything(int slot) throws SQLException {
        makeVacant(tenant);
        assertRejectedUnchanged(() -> tenants.rehouse(tenant, 7, target, slot));
    }

    @Test
    void disabledTargetRejects() throws SQLException {
        makeVacant(tenant);
        execute("UPDATE npc_properties SET enabled=FALSE WHERE property_id=?", target);
        assertRejectedUnchanged(() -> tenants.rehouse(tenant, 7, target, 0));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, Long.MIN_VALUE, Long.MAX_VALUE})
    void vacateRejectsCorruptOrExhaustedRevisionAtomically(long revision) throws SQLException {
        execute("UPDATE npc_tenants SET row_version=? WHERE tenant_id=?", revision, tenant);
        assertRejectedUnchanged(() -> tenants.vacate(tenant));
    }

    @ParameterizedTest
    @ValueSource(longs = {-1, Long.MIN_VALUE, Long.MAX_VALUE})
    void rehouseRejectsCorruptOrExhaustedRevisionAtomically(long revision) throws SQLException {
        makeVacant(tenant);
        execute("UPDATE npc_tenants SET row_version=? WHERE tenant_id=?", revision, tenant);
        assertRejectedUnchanged(() -> tenants.rehouse(tenant, revision, target, 0));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 5, 100, 127})
    void corruptCapacityRejectsEvenWhenChosenSlotWouldOtherwiseFit(int capacity) throws SQLException {
        makeVacant(tenant);
        // Bypass V1's CHECK only on this fixture connection; restore before returning it to the pool.
        try (Connection c = database.getConnection()) {
            execute(c, "SET SESSION check_constraint_checks=OFF");
            try {
                execute(c, "UPDATE npc_properties SET capacity=? WHERE property_id=?", capacity, target);
            } finally {
                execute(c, "SET SESSION check_constraint_checks=ON");
            }
        }
        assertRejectedUnchanged(() -> tenants.rehouse(tenant, 7, target, 0));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4})
    void capacityEndpointsAndLastSlotAreAccepted(int capacity) throws SQLException {
        makeVacant(tenant);
        execute("UPDATE npc_properties SET capacity=? WHERE property_id=?", capacity, target);
        Map<String, String> before = row(tenant);
        tenants.rehouse(tenant, 7, target, capacity - 1);
        assertHousingChange(before, target, capacity - 1, 8);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void occupiedChosenSlotRejectsWhetherFullOrAnotherSlotIsFree(int capacity) throws SQLException {
        makeVacant(tenant);
        execute("UPDATE npc_properties SET capacity=? WHERE property_id=?", capacity, target);
        insertTenant(target, 0);
        assertEquals(capacity == 1 ? -1 : 1, tenants.firstFreeSlot(target, capacity));
        assertRejectedUnchanged(() -> tenants.rehouse(tenant, 7, target, 0));
    }

    @Test
    void propertyLockSerializesSameSlotContendersWithExactlyOneWinner() throws Exception {
        makeVacant(tenant);
        UUID other = insertTenant(source, 1);
        makeVacant(other);
        Map<String, String> leftBefore = row(tenant);
        Map<String, String> rightBefore = row(other);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> left;
            Future<Boolean> right;
            try (Connection blocker = database.getConnection()) {
                blocker.setAutoCommit(false);
                try {
                    try (PreparedStatement ps = blocker.prepareStatement(
                            "SELECT property_id FROM npc_properties WHERE property_id=? FOR UPDATE")) {
                        ps.setQueryTimeout(3);
                        bind(ps, target);
                        try (ResultSet rs = ps.executeQuery()) { assertTrue(rs.next()); }
                    }
                    left = workers.submit(() -> contend(tenant));
                    right = workers.submit(() -> contend(other));
                    assertPropertyLockWaiters();
                    assertFalse(left.isDone());
                    assertFalse(right.isDone());
                } finally {
                    blocker.rollback();
                }
            }
            boolean leftWon = left.get(15, TimeUnit.SECONDS);
            boolean rightWon = right.get(15, TimeUnit.SECONDS);
            assertNotEquals(leftWon, rightWon, "exactly one contender must commit");
            assertHousingChange(leftWon ? leftBefore : rightBefore, target, 0, 8);
            assertEquals(leftWon ? rightBefore : leftBefore, row(leftWon ? other : tenant));
            assertEquals(1, tenants.countByProperty(target));
            assertEquals(2, tenantRows().size(), "loser remains retained and vacant");
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS), "rehouse workers terminated");
        }
    }

    private boolean contend(UUID id) throws SQLException {
        try {
            new TenantRepository(database).rehouse(id, 7, target, 0);
            return true;
        } catch (SQLException rejection) {
            // Lock timeouts/deadlocks are not evidence of correct slot conflict handling.
            assertNotEquals(1205, rejection.getErrorCode(), "lock wait timed out");
            assertNotEquals(1213, rejection.getErrorCode(), "deadlock instead of serialized rejection");
            assertEquals("Target slot occupied", rejection.getMessage());
            return false;
        }
    }

    private static void assertPropertyLockWaiters() throws SQLException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        List<Map<String, String>> queries = List.of();
        while (System.nanoTime() < deadline) {
            queries = rows("SELECT INFO FROM information_schema.PROCESSLIST "
                    + "WHERE ID<>CONNECTION_ID() AND DB=? AND INFO LIKE '%npc_properties%'", schemaName);
            if (queries.size() == 2) {
                for (Map<String, String> query : queries) {
                    String sql = query.get("INFO").stripLeading().toLowerCase(Locale.ROOT).replaceFirst(
                            "^set\\s+statement\\s+max_statement_time\\s*=\\s*\\d+(?:\\.\\d+)?\\s+for\\s+", "");
                    assertTrue(sql.matches("(?s)select\\s+.*\\s+for\\s+update\\s*"),
                            "both contenders must wait on the property locking read: " + sql);
                }
                return;
            }
            Thread.yield();
        }
        fail("expected two property locking readers, saw " + queries);
    }

    @Test
    void callerOwnedVacateRollsBackWithALaterSqlFault() throws SQLException {
        List<Map<String, String>> before = tenantRows();
        List<Map<String, String>> fundingBefore = rows("SELECT * FROM npc_property_funding ORDER BY property_id");
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            try {
                execute(c, "UPDATE npc_property_funding SET successful_cash_sinks_cents=321 WHERE property_id=?", source);
                assertTrue(TenantRepository.vacate(c, tenant, source));
                assertFalse(c.getAutoCommit(), "helper must leave transaction ownership with caller");
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT property_id,slot,row_version FROM npc_tenants WHERE tenant_id=?")) {
                    ps.setQueryTimeout(3);
                    bind(ps, tenant);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next());
                        assertNull(rs.getBytes("property_id"));
                        assertNull(rs.getObject("slot"));
                        assertEquals(8, rs.getLong("row_version"));
                    }
                }
                SQLException fault = assertThrows(SQLException.class,
                        () -> execute(c, "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='continuity rollback fault'"));
                assertEquals("45000", fault.getSQLState());
            } finally {
                c.rollback();
            }
        }
        assertEquals(before, tenantRows(), "rollback restores housing, revision, and every identity field");
        assertEquals(fundingBefore, rows("SELECT * FROM npc_property_funding ORDER BY property_id"),
                "static vacate must not commit earlier caller writes");
    }

    @ParameterizedTest
    @ValueSource(strings = {"property_id=NULL", "slot=NULL", "slot=-1", "slot=4"})
    void malformedHousingCannotBeSilentlyVacated(String assignment) throws SQLException {
        execute("UPDATE npc_tenants SET " + assignment + " WHERE tenant_id=?", tenant);
        assertRejectedUnchanged(() -> tenants.vacate(tenant));
    }

    private UUID enroll(String region, int capacity) throws SQLException {
        UUID id = UUID.randomUUID();
        new PropertyRepository(database).insertEnrollment(new Property(id, new UUID(0, 700), region,
                "downtown", "room", capacity, 1000, 1200, new UUID(0, 901), 0, 80, true), 5000);
        return id;
    }

    @Test
    void displayReadIsOrderedScopedImmutableAndDoesNotMutate() throws SQLException {
        UUID second = insertTenant(source, 1);
        insertTenant(target, 0);
        var before = tenantRows();
        var display = tenants.listForDisplay(source, 2);
        assertEquals(List.of(tenant, second), display.stream().map(Tenant::tenantId).toList());
        assertTrue(display.stream().allMatch(t -> t.propertyId().equals(source)));
        assertThrows(UnsupportedOperationException.class, () -> display.clear());
        assertEquals(before, tenantRows());
    }

    @ParameterizedTest
    @ValueSource(strings = {"slot=NULL", "slot=-1", "slot=2", "tier='UNKNOWN'", "display_name=''", "archetype=''"})
    void displayReadRejectsCorruptionWithoutPartialResult(String assignment) throws SQLException {
        insertTenant(source, 1);
        execute("UPDATE npc_tenants SET " + assignment + " WHERE tenant_id=?", tenant);
        assertRejectedUnchanged(() -> tenants.listForDisplay(source, 2));
    }

    @Test
    void displayReadRejectsDuplicateSlotsAndOverCapacity() throws SQLException {
        insertTenant(source, 0);
        assertRejectedUnchanged(() -> tenants.listForDisplay(source, 2));
        assertRejectedUnchanged(() -> tenants.listForDisplay(source, 1));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 5, Integer.MAX_VALUE})
    void displayReadRejectsInvalidCapacity(int capacity) {
        assertThrows(IllegalArgumentException.class, () -> tenants.listForDisplay(source, capacity));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void displaySelectBindsCapacityPlusOneAndFiveSecondTimeoutBeforeExecution(int capacity) throws Exception {
        var before = tenantRows();
        try (DisplayProbe probe = new DisplayProbe(source, capacity, null)) {
            var display = new TenantRepository(probe.routed).listForDisplay(source, capacity);
            assertEquals(List.of(tenant), display.stream().map(Tenant::tenantId).toList());
            assertEquals(1, probe.executions, "the inspected SELECT must execute against MariaDB");
            assertEquals(1, probe.rowsRead, "results must come from the real driver");
            assertEquals(0, probe.injectedWarnings);
        }
        assertEquals(before, tenantRows());
    }

    @ParameterizedTest
    @ValueSource(strings = {"connection", "statement", "resultSet"})
    void displayReadRejectsEachJdbcWarningAfterReadingValidRealRows(String warningSource) throws Exception {
        var before = tenantRows();
        try (DisplayProbe probe = new DisplayProbe(source, 2, warningSource)) {
            SQLException error = assertThrows(SQLException.class,
                    () -> new TenantRepository(probe.routed).listForDisplay(source, 2));
            assertEquals("Display occupancy read warning", error.getMessage(),
                    "another occupancy guard or driver failure must not satisfy this test");
            assertEquals(1, probe.executions);
            assertEquals(1, probe.rowsRead, "valid SQL rows must be consumed before warning rejection");
            assertEquals(1, probe.injectedWarnings, "the selected JDBC warning surface must be checked");
        }
        assertEquals(before, tenantRows());
    }

    /** Private forwarding pool: only warnings are injected; SQL, bindings and rows use MariaDB. */
    private static final class DisplayProbe implements AutoCloseable {
        final Database routed = new Database();
        final UUID property;
        final int capacity;
        final String warningSource;
        int executions, rowsRead, injectedWarnings;

        DisplayProbe(UUID property, int capacity, String warningSource) throws ReflectiveOperationException {
            this.property = property;
            this.capacity = capacity;
            this.warningSource = warningSource;
            var forwarding = new HikariDataSource() {
                @Override public Connection getConnection() throws SQLException {
                    Connection actual = database.getConnection();
                    return proxy(Connection.class, (ignored, method, args) -> {
                        Object result = invoke(actual, method, args);
                        if (method.getName().equals("prepareStatement") && args[0] instanceof String sql)
                            return wrap((PreparedStatement) result, sql);
                        return warning("connection", method, result);
                    });
                }
            };
            var field = Database.class.getDeclaredField("dataSource");
            field.setAccessible(true);
            field.set(routed, forwarding);
        }

        private PreparedStatement wrap(PreparedStatement actual, String sql) {
            Map<Integer, Object> bindings = new LinkedHashMap<>();
            List<Integer> timeouts = new ArrayList<>();
            return proxy(PreparedStatement.class, (ignored, method, args) -> {
                if (method.getName().equals("executeQuery")) {
                    String normalized = sql.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
                    assertTrue(normalized.matches("select .+ from npc_tenants where property_id\\s*=\\s*\\? "
                                    + "order by slot\\s*,\\s*tenant_id limit \\?"),
                            "the executed SELECT itself must have a parameterized LIMIT: " + sql);
                    assertEquals(2, actual.getParameterMetaData().getParameterCount());
                    assertEquals(2, bindings.size());
                    assertArrayEquals(Uuids.toBytes(property), (byte[]) bindings.get(1));
                    assertEquals(capacity + 1, bindings.get(2), "LIMIT must bind cap+1, not cap or a constant");
                    assertEquals(List.of(5), timeouts, "setQueryTimeout(5) must precede execution");
                    assertEquals(5, actual.getQueryTimeout(), "timeout must reach the real prepared statement");
                }
                Object result = invoke(actual, method, args);
                if (method.getName().equals("setBytes")) bindings.put((Integer) args[0], ((byte[]) args[1]).clone());
                if (method.getName().equals("setInt")) bindings.put((Integer) args[0], args[1]);
                if (method.getName().equals("setQueryTimeout")) timeouts.add((Integer) args[0]);
                if (method.getName().equals("executeQuery")) {
                    executions++;
                    ResultSet actualRows = (ResultSet) result;
                    return proxy(ResultSet.class, (p, call, parameters) -> {
                        Object value = invoke(actualRows, call, parameters);
                        if (call.getName().equals("next") && Boolean.TRUE.equals(value)) rowsRead++;
                        return warning("resultSet", call, value);
                    });
                }
                return warning("statement", method, result);
            });
        }

        private Object warning(String surface, Method method, Object result) {
            if (!method.getName().equals("getWarnings")) return result;
            assertNull(result, "unexpected real JDBC warning must not count as the injected warning");
            if (!surface.equals(warningSource)) return null;
            injectedWarnings++;
            return new SQLWarning("display probe " + surface, "01000");
        }

        @Override public void close() { routed.close(); }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException error) { throw error.getCause(); }
    }

    private UUID insertTenant(UUID property, int slot) throws SQLException {
        UUID id = UUID.randomUUID();
        tenants.insert(new Tenant(id, "Ava Continuity", property, slot, "PREMIUM", "ARTISAN", 73));
        execute("UPDATE npc_tenants SET mood='HAPPY',schedule_template='NIGHT_SHIFT',state='WORKING',"
                + "move_cooldown_until_day='9999-12-31',row_version=7 WHERE tenant_id=?", id);
        return id;
    }

    private static void makeVacant(UUID id) throws SQLException {
        // Isolate rehouse tests from vacate so one broken primitive cannot mask the other.
        execute("UPDATE npc_tenants SET property_id=NULL,slot=NULL WHERE tenant_id=?", id);
    }

    private static void assertHousingChange(Map<String, String> before, UUID property, Integer slot, long revision)
            throws SQLException {
        Map<String, String> expected = new LinkedHashMap<>(before);
        expected.put("property_id", property == null ? null : HexFormat.of().formatHex(Uuids.toBytes(property)));
        expected.put("slot", slot == null ? null : slot.toString());
        expected.put("row_version", Long.toString(revision));
        UUID id = Uuids.fromBytes(HexFormat.of().parseHex(before.get("tenant_id")));
        assertEquals(expected, row(id), "only property_id, slot and row_version may change");
    }

    private static void assertRejectedUnchanged(Executable operation) throws SQLException {
        List<Map<String, String>> before = tenantRows();
        List<Map<String, String>> propertiesBefore = rows("SELECT * FROM npc_properties ORDER BY property_id");
        Exception rejection = assertThrows(Exception.class, operation);
        assertTrue(rejection instanceof SQLException || rejection instanceof IllegalArgumentException,
                "expected an explicit SQL/domain rejection, got " + rejection);
        assertEquals(before, tenantRows(), "rejection must leave every tenant unchanged");
        assertEquals(propertiesBefore, rows("SELECT * FROM npc_properties ORDER BY property_id"));
    }

    private static Map<String, String> row(UUID id) throws SQLException {
        List<Map<String, String>> found = rows("SELECT * FROM npc_tenants WHERE tenant_id=?", id);
        assertEquals(1, found.size(), "tenant identity must remain stored");
        return found.getFirst();
    }

    private static List<Map<String, String>> tenantRows() throws SQLException {
        return rows("SELECT * FROM npc_tenants ORDER BY tenant_id");
    }

    private static List<Map<String, String>> rows(String sql, Object... values) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(3);
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
                return result;
            }
        }
    }

    private static void execute(String sql, Object... values) throws SQLException {
        try (Connection c = database.getConnection()) { execute(c, sql, values); }
    }

    private static void execute(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
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
}

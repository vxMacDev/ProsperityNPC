package com.prosperitysmp.npc.publication;

import com.google.gson.JsonParser;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.ReadSnapshot;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.publication.PublicOccupancySnapshot.Approval;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Executes real repository/ReadSnapshot logic against strict JDBC doubles, not a SQL engine. */
class PublicOccupancyRepositoryTest {
    private static final UUID WORLD = new UUID(17, 1);
    private static final Instant NOW = Instant.parse("2026-09-08T07:00:00.123456Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static Approval approval(int id) {
        return new Approval(new UUID(0, id), WORLD, "c" + id, "main");
    }

    private static Map<String, Object> property(int id, int capacity, boolean enabled) {
        var row = new HashMap<String, Object>();
        row.put("property_id", Uuids.toBytes(new UUID(0, id)));
        row.put("world_uuid", Uuids.toBytes(WORLD));
        row.put("region_id", "C" + id);
        row.put("market_id", "market"); row.put("class_key", "TEST_ONLY");
        row.put("capacity", capacity); row.put("enabled", enabled ? 1 : 0);
        row.put("reference_rent_cents", 100); row.put("asking_rent_cents", 100);
        row.put("owner_uuid", null); row.put("ownership_epoch", 0);
        row.put("condition_score", 80); row.put("row_version", 0L);
        return row;
    }

    private static Map<String, Object> tenant(int id, int property, Integer slot) {
        var row = new HashMap<String, Object>();
        row.put("tenant_id", Uuids.toBytes(new UUID(1, id)));
        row.put("property_id", Uuids.toBytes(new UUID(0, property)));
        row.put("slot", slot);
        row.put("row_version", 0L);
        return row;
    }

    @Test void oneReadOnlySnapshotTwoBoundQueriesAndAllOccupancyStates() throws Exception {
        var h = new Jdbc();
        h.properties.addAll(List.of(property(1, 2, true), property(2, 2, true), property(3, 1, true), property(4, 1, false)));
        h.tenants.addAll(List.of(tenant(1, 2, 1), tenant(2, 3, 0)));
        String json = h.repository.read(List.of(approval(1), approval(2), approval(3), approval(4))).json();
        var root = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("2026-09-08T07:00:00.123Z", root.get("capturedAt").getAsString());
        var entries = root.getAsJsonArray("properties");
        assertEquals(3, entries.size());
        for (int i = 0; i < 3; i++) assertEquals(List.of("EMPTY", "PARTIAL", "FULL").get(i),
                entries.get(i).getAsJsonObject().get("occupancy").getAsString());
        assertEquals(1, h.connections);
        assertEquals(1, h.begins);
        assertEquals(2, h.selects);
        assertEquals(1, h.rollbacks);
        assertTrue(h.closed);
        assertEquals(3, h.statementCloses);
        assertEquals(2, h.resultCloses);
        assertEquals(List.of(new UUID(0, 1), new UUID(0, 2), new UUID(0, 3), new UUID(0, 4)), h.bindings.get(0));
        assertEquals(h.bindings.get(0), h.bindings.get(1));
    }

    @Test void borrowedSnapshotIsNotClosedAndWrongDatabaseIsRejected() throws Exception {
        var h = new Jdbc();
        h.properties.add(property(1, 1, true));
        try (var snapshot = ReadSnapshot.open(h.database, CLOCK)) {
            assertNotNull(h.repository.read(snapshot, List.of(approval(1))));
            assertEquals(0, h.rollbacks);
            assertFalse(h.closed);
            assertThrows(SQLException.class, () -> new PublicOccupancyRepository(new Database(), CLOCK)
                    .read(snapshot, List.of()));
        }
        assertEquals(1, h.rollbacks);
    }

    @Test void emptyApprovalDoesNotScanAndInvalidScopeDoesNotOpenConnection() throws Exception {
        var h = new Jdbc();
        assertTrue(h.repository.read(List.of()).json().contains("\"properties\":[]"));
        assertEquals(0, h.selects);
        var invalid = new Jdbc();
        assertThrows(SQLException.class, () -> invalid.repository.read(List.of(approval(1), approval(1))));
        assertThrows(SQLException.class, () -> invalid.repository.read(java.util.Collections.nCopies(513, approval(1))));
        assertThrows(SQLException.class, () -> invalid.repository.read(java.util.Collections.singletonList(null)));
        assertEquals(0, invalid.connections);
    }

    @Test void nullDuplicateNegativeAndOutOfCapacitySlotsReject() throws Exception {
        for (Integer slot : new Integer[]{null, -1, 2}) {
            var h = new Jdbc(); h.properties.add(property(1, 2, true)); h.tenants.add(tenant(1, 1, slot));
            assertThrows(SQLException.class, () -> h.repository.read(List.of(approval(1))));
            assertTrue(h.closed); assertEquals(1, h.rollbacks);
        }
        var duplicate = new Jdbc(); duplicate.properties.add(property(1, 2, true));
        duplicate.tenants.addAll(List.of(tenant(1, 1, 0), tenant(2, 1, 0)));
        assertThrows(SQLException.class, () -> duplicate.repository.read(List.of(approval(1))));
    }

    @Test void duplicateTenantIdentityAndForeignPropertyReject() throws Exception {
        var duplicate = new Jdbc(); duplicate.properties.add(property(1, 2, true));
        duplicate.tenants.addAll(List.of(tenant(1, 1, 0), tenant(1, 1, 1)));
        assertThrows(SQLException.class, () -> duplicate.repository.read(List.of(approval(1))));
        var foreign = new Jdbc(); foreign.properties.add(property(1, 1, true)); foreign.tenants.add(tenant(1, 2, 0));
        assertThrows(SQLException.class, () -> foreign.repository.read(List.of(approval(1))));
    }

    @Test void evidenceRetainsPrivateMembershipAndBothRevisionsImmutably() throws Exception {
        var h = new Jdbc();
        var p = property(1, 2, true); p.put("row_version", 7L); h.properties.add(p);
        var t = tenant(99, 1, 1); t.put("row_version", 19L); h.tenants.add(t);
        var evidence = h.repository.readEvidence(List.of(approval(1)));
        assertEquals(Instant.parse("2026-09-08T07:00:00.123Z"), evidence.capturedAt());
        assertEquals(Map.of(new UUID(0, 1), 7L), evidence.propertyRevisions());
        assertEquals(Map.of(new UUID(1, 99), new PublicOccupancyRepository.TenantRevision(new UUID(0, 1), 1, 19L)),
                evidence.tenantRevisions());
        assertThrows(UnsupportedOperationException.class, () -> evidence.propertyRevisions().clear());
        assertThrows(UnsupportedOperationException.class, () -> evidence.tenantRevisions().clear());
        p.put("row_version", 8L); t.put("row_version", 20L);
        assertEquals(7L, evidence.propertyRevisions().get(new UUID(0, 1)));
        assertEquals(19L, evidence.tenantRevisions().get(new UUID(1, 99)).revision());
        String json = evidence.snapshot().json();
        assertFalse(json.contains(new UUID(1, 99).toString()));
        assertFalse(json.contains(new UUID(0, 1).toString()));
        assertFalse(json.contains("revision"));
        assertEquals(java.util.Set.of("world", "region", "occupancy"), JsonParser.parseString(json).getAsJsonObject()
                .getAsJsonArray("properties").get(0).getAsJsonObject().keySet());
    }

    @Test void nullAndNegativeTenantRevisionsReject() throws Exception {
        for (Long revision : new Long[]{null, -1L}) {
            var h = new Jdbc(); h.properties.add(property(1, 1, true));
            var row = tenant(1, 1, 0); row.put("row_version", revision); h.tenants.add(row);
            assertThrows(SQLException.class, () -> h.repository.readEvidence(List.of(approval(1))));
            assertTrue(h.closed); assertEquals(1, h.rollbacks);
        }
    }

    @Test void missingMismatchedDuplicateAndUnexpectedPropertiesReject() throws Exception {
        var missing = new Jdbc(); missing.properties.add(property(1, 1, true));
        assertThrows(SQLException.class, () -> missing.repository.read(List.of(approval(1), approval(2))));
        for (String column : List.of("world_uuid", "region_id", "property_id")) {
            var h = new Jdbc(); var row = property(1, 1, true);
            row.put(column, column.equals("region_id") ? "other" : Uuids.toBytes(new UUID(99, 99)));
            h.properties.add(row);
            assertThrows(SQLException.class, () -> h.repository.read(List.of(approval(1))));
        }
        var duplicate = new Jdbc(); duplicate.properties.addAll(List.of(property(1, 1, true), property(1, 1, true)));
        assertThrows(SQLException.class, () -> duplicate.repository.read(List.of(approval(1), approval(2))));
    }

    @Test void corruptPropertyNumbersMetadataAndDisabledTenantsStillReject() throws Exception {
        var mutations = new HashMap<String, Object>();
        mutations.put("capacity", 5); mutations.put("enabled", 2); mutations.put("condition_score", 101);
        mutations.put("ownership_epoch", -1); mutations.put("row_version", -1L);
        mutations.put("reference_rent_cents", -1); mutations.put("asking_rent_cents", Long.MAX_VALUE);
        mutations.put("owner_uuid", new byte[15]); mutations.put("region_id", "\u212A1");
        mutations.put("market_id", ""); mutations.put("class_key", null);
        for (var mutation : mutations.entrySet()) {
            var h = new Jdbc(); var row = property(1, 1, true); row.put(mutation.getKey(), mutation.getValue());
            h.properties.add(row);
            assertThrows(SQLException.class, () -> h.repository.read(List.of(approval(1))), mutation.getKey());
        }
        for (String column : List.of("capacity", "enabled", "row_version", "condition_score", "ownership_epoch")) {
            var h = new Jdbc(); var row = property(1, 1, true); row.put(column, null); h.properties.add(row);
            assertThrows(SQLException.class, () -> h.repository.read(List.of(approval(1))), column);
        }
        var disabled = new Jdbc(); disabled.properties.add(property(1, 1, false)); disabled.tenants.add(tenant(1, 1, null));
        assertThrows(SQLException.class, () -> disabled.repository.read(List.of(approval(1))));
    }

    @Test void maxScopeIsTwoQueriesAndExcessResultsNeverTruncated() throws Exception {
        var h = new Jdbc(); var approvals = new ArrayList<Approval>();
        for (int i = 1; i <= 512; i++) {
            approvals.add(approval(i)); h.properties.add(property(i, 4, true));
            for (int slot = 0; slot < 4; slot++) h.tenants.add(tenant(i * 4 + slot, i, slot));
        }
        assertEquals(512, JsonParser.parseString(h.repository.read(approvals).json()).getAsJsonObject()
                .getAsJsonArray("properties").size());
        assertEquals(2, h.selects);
        var overflow = new Jdbc(); overflow.properties.add(property(1, 4, true));
        for (int slot = 0; slot < 5; slot++) overflow.tenants.add(tenant(slot, 1, slot));
        assertThrows(SQLException.class, () -> overflow.repository.read(List.of(approval(1))));
        var extra = new Jdbc(); extra.properties.addAll(List.of(property(1, 1, true), property(2, 1, true)));
        assertThrows(SQLException.class, () -> extra.repository.read(List.of(approval(1))));
    }

    @Test void warningsAtEveryJdbcLayerAndQueryFailureRollbackAndClose() throws Exception {
        for (String layer : List.of("connection", "statement", "result", "late-result", "query", "second-query", "tenant-warning")) {
            var h = new Jdbc(); h.failure = layer; h.properties.add(property(1, 1, true));
            assertThrows(SQLException.class, () -> h.repository.read(List.of(approval(1))), layer);
            assertEquals(1, h.rollbacks); assertTrue(h.closed);
            if (layer.equals("result") || layer.equals("late-result")) assertEquals(1, h.resultCloses);
            if (layer.equals("second-query")) { assertEquals(2, h.selects); assertEquals(1, h.resultCloses); }
            if (layer.equals("tenant-warning")) { assertEquals(2, h.selects); assertEquals(2, h.resultCloses); }
        }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static final class Jdbc {
        final List<Map<String, Object>> properties = new ArrayList<>();
        final List<Map<String, Object>> tenants = new ArrayList<>();
        final List<List<UUID>> bindings = new ArrayList<>();
        final Database database = new Database();
        final PublicOccupancyRepository repository = new PublicOccupancyRepository(database, CLOCK);
        boolean autoCommit = true, closed;
        int isolation, connections, begins, selects, rollbacks, statementCloses, resultCloses;
        String failure = "none";

        Jdbc() throws Exception {
            Connection connection = proxy(Connection.class, (p, m, a) -> switch (m.getName()) {
                case "setTransactionIsolation" -> { isolation = (int) a[0]; yield null; }
                case "getTransactionIsolation" -> isolation;
                case "setAutoCommit" -> { autoCommit = (boolean) a[0]; yield null; }
                case "getAutoCommit" -> autoCommit;
                case "isClosed" -> closed;
                case "prepareStatement" -> statement((String) a[0]);
                case "getWarnings" -> failure.equals("connection") ? new SQLWarning("test") : null;
                case "rollback" -> { rollbacks++; yield null; }
                case "close" -> { closed = true; yield null; }
                default -> throw new AssertionError("Unexpected connection operation: " + m);
            });
            var dataSource = new HikariDataSource() {
                @Override public boolean isClosed() { return false; }
                @Override public Connection getConnection() { connections++; return connection; }
            };
            var field = Database.class.getDeclaredField("dataSource");
            field.setAccessible(true); field.set(database, dataSource);
        }

        PreparedStatement statement(String sql) {
            boolean begin = sql.equals("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY");
            boolean property = sql.contains("FROM npc_properties");
            if (!begin) {
                assertTrue(sql.startsWith("SELECT "));
                assertTrue(property || sql.contains("FROM npc_tenants"));
                assertTrue(sql.contains("WHERE property_id IN ("));
                assertTrue(sql.endsWith(property ? "LIMIT 513" : "LIMIT 2049"));
                assertFalse(sql.contains("COUNT("));
            }
            var params = new HashMap<Integer, UUID>();
            int[] timeout = {0};
            return proxy(PreparedStatement.class, (p, m, a) -> switch (m.getName()) {
                case "setQueryTimeout" -> { timeout[0] = (int) a[0]; assertEquals(5, timeout[0]); yield null; }
                case "setBytes" -> { params.put((int) a[0], Uuids.fromBytes((byte[]) a[1])); yield null; }
                case "execute" -> {
                    assertTrue(begin); assertEquals(5, timeout[0]); assertFalse(autoCommit);
                    assertEquals(Connection.TRANSACTION_REPEATABLE_READ, isolation); begins++; yield false;
                }
                case "executeQuery" -> {
                    assertFalse(begin); assertEquals(5, timeout[0]); assertEquals(1, begins);
                    assertFalse(autoCommit); assertEquals(Connection.TRANSACTION_REPEATABLE_READ, isolation);
                    assertEquals(sql.chars().filter(c -> c == '?').count(), params.size());
                    var bound = new ArrayList<UUID>();
                    for (int i = 1; i <= params.size(); i++) bound.add(params.get(i));
                    bindings.add(List.copyOf(bound)); selects++;
                    if (failure.equals("query")) throw new SQLException("test query failure");
                    if (failure.equals("second-query") && selects == 2)
                        throw new java.sql.SQLTimeoutException("test tenant timeout");
                    yield result(property ? properties : tenants);
                }
                case "getWarnings" -> !begin && (failure.equals("statement")
                        || (!property && failure.equals("tenant-warning"))) ? new SQLWarning("test") : null;
                case "close" -> { statementCloses++; yield null; }
                default -> throw new AssertionError("Unexpected statement operation: " + m);
            });
        }

        ResultSet result(List<Map<String, Object>> rows) {
            int[] index = {-1}; boolean[] wasNull = {false};
            return proxy(ResultSet.class, (p, m, a) -> {
                switch (m.getName()) {
                    case "next": return ++index[0] < rows.size();
                    case "wasNull": return wasNull[0];
                    case "getWarnings": return failure.equals("result")
                            || (failure.equals("late-result") && index[0] >= rows.size()) ? new SQLWarning("test") : null;
                    case "close": resultCloses++; return null;
                    case "getString", "getBytes", "getLong":
                        assertTrue(rows.get(index[0]).containsKey((String) a[0]), "Unexpected column " + a[0]);
                        Object value = rows.get(index[0]).get((String) a[0]); wasNull[0] = value == null;
                        if (m.getName().equals("getLong")) return value == null ? 0L : ((Number) value).longValue();
                        return value;
                    default: throw new AssertionError("Unexpected result operation: " + m);
                }
            });
        }
    }
}

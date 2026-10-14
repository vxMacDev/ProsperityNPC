package com.prosperitysmp.npc.it;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.*;
import com.prosperitysmp.npc.publication.PublicOccupancyRepository;
import com.prosperitysmp.npc.publication.PublicOccupancySnapshot.Approval;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import java.io.InputStream;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** Real migrated MariaDB, including a committed write between the two SELECTs. */
@Timeout(45)
class PublicOccupancyRepositoryIT {
    private static DB maria;
    private static Database database;
    private static List<String> tables;
    private static final UUID WORLD = new UUID(0, 501);
    private static final UUID PROPERTY = new UUID(0, 502);
    private static final UUID TENANT = new UUID(0, 503);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-08T12:00:00Z"), ZoneOffset.UTC);
    private static final List<Approval> SCOPE = List.of(new Approval(PROPERTY, WORLD, "c1", "main"));

    @BeforeAll static void start() throws Exception {
        var config = DBConfigurationBuilder.newBuilder(); config.setPort(0);
        maria = DB.newEmbeddedDB(config.addArg("--bind-address=127.0.0.1").build());
        try {
            maria.start();
            String schema = "npc_publication_" + UUID.randomUUID().toString().replace("-", "");
            maria.createDB(schema);
            database = new Database();
            database.connect(new DatabaseSettings("localhost", config.getPort(), schema, "root", "", 4, 10000));
            Field field = MigrationRunner.class.getDeclaredField("MIGRATIONS"); field.setAccessible(true);
            var versions = new ArrayList<Integer>();
            for (Object migration : (List<?>) field.get(null)) {
                Method version = migration.getClass().getDeclaredMethod("version"); version.setAccessible(true);
                Method resource = migration.getClass().getDeclaredMethod("resource"); resource.setAccessible(true);
                int number = (Integer) version.invoke(migration);
                if (number > 15) continue;
                try (InputStream in = PublicOccupancyRepositoryIT.class.getResourceAsStream("/" + resource.invoke(migration))) {
                    assertNotNull(in);
                    for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8)))
                        execute(sql);
                }
                versions.add(number);
            }
            assertEquals(java.util.stream.IntStream.rangeClosed(1, 15).boxed().toList(), versions);
            tables = new ArrayList<>();
            try (Connection c = database.getConnection(); Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SHOW TABLES")) {
                while (rs.next()) tables.add(rs.getString(1));
            }
        } catch (Exception | AssertionError failure) {
            try { stop(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @AfterAll static void stop() throws Exception {
        try { if (database != null) database.close(); }
        finally { if (maria != null) { DB old = maria; maria = null; old.stop(); } }
    }

    @BeforeEach void seed() throws SQLException {
        try (Connection c = database.getConnection(); Statement s = c.createStatement()) {
            s.execute("SET SESSION foreign_key_checks=0");
            try { for (String table : tables) s.executeUpdate("DELETE FROM `" + table + "`"); }
            finally { s.execute("SET SESSION foreign_key_checks=1"); }
        }
        execute("INSERT INTO npc_properties(property_id,world_uuid,region_id,market_id,class_key,capacity,"
                + "reference_rent_cents,asking_rent_cents,enabled) VALUES (?,?,'c1','PRIVATE_MARKET','PRIVATE_CLASS',2,827364,918273,1)",
                PROPERTY, WORLD);
        execute("INSERT INTO npc_tenants(tenant_id,display_name,property_id,slot,archetype) VALUES (?,'PRIVATE_NAME',?,0,'GENERIC')",
                TENANT, PROPERTY);
    }

    @Test void successAndCorruptSlotFailureLeaveEverySourceRowUnchanged() throws Exception {
        var reader = new PublicOccupancyRepository(database, CLOCK);
        List<String> before = data();
        String json = reader.read(SCOPE).json();
        assertTrue(json.contains("PARTIAL"));
        for (String secret : List.of("PRIVATE_MARKET", "PRIVATE_CLASS", "PRIVATE_NAME", "827364", "918273",
                PROPERTY.toString(), WORLD.toString(), TENANT.toString())) assertFalse(json.contains(secret));
        assertEquals(before, data());
        for (String badSlot : List.of("NULL", "-1", "2")) {
            execute("UPDATE npc_tenants SET slot=" + badSlot);
            before = data();
            assertThrows(SQLException.class, () -> reader.read(SCOPE));
            assertEquals(before, data());
        }
    }

    @Test void duplicateSlotsAndMissingApprovedPropertyFailWithoutSourceRepair() throws Exception {
        execute("INSERT INTO npc_tenants(tenant_id,display_name,property_id,slot,archetype) VALUES (?,'SECOND',?,0,'GENERIC')",
                new UUID(0, 504), PROPERTY);
        List<String> before = data();
        assertThrows(SQLException.class, () -> new PublicOccupancyRepository(database, CLOCK).read(SCOPE));
        assertEquals(before, data());
        assertThrows(SQLException.class, () -> new PublicOccupancyRepository(database, CLOCK).read(
                List.of(new Approval(new UUID(0, 999), WORLD, "missing", "main"))));
        assertEquals(before, data());
    }

    @Test void secondConnectionCommitBetweenQueriesCannotMixCapacityAndTenantState() throws Exception {
        AtomicBoolean moved = new AtomicBoolean();
        Database routed = new Database();
        var source = new HikariDataSource() {
            @Override public boolean isClosed() { return false; }
            @Override public Connection getConnection() throws SQLException {
                Connection actual = database.getConnection();
                return proxy(Connection.class, (p, method, args) -> {
                    Object result = invoke(actual, method, args);
                    if (!method.getName().equals("prepareStatement") || !((String) args[0]).contains("FROM npc_properties")) return result;
                    PreparedStatement statement = (PreparedStatement) result;
                    return proxy(PreparedStatement.class, (sp, sm, sa) -> {
                        Object value = invoke(statement, sm, sa);
                        if (!sm.getName().equals("executeQuery")) return value;
                        ResultSet rows = (ResultSet) value;
                        return proxy(ResultSet.class, (rp, rm, ra) -> {
                            Object answer = invoke(rows, rm, ra);
                            if (rm.getName().equals("close") && moved.compareAndSet(false, true)) {
                                try (Connection writer = database.getConnection(); Statement update = writer.createStatement()) {
                                    writer.setAutoCommit(false);
                                    update.executeUpdate("UPDATE npc_properties SET capacity=1,row_version=row_version+1");
                                    update.executeUpdate("UPDATE npc_tenants SET property_id=NULL,slot=NULL,row_version=row_version+1");
                                    writer.commit();
                                }
                            }
                            return answer;
                        });
                    });
                });
            }
        };
        Field ds = Database.class.getDeclaredField("dataSource"); ds.setAccessible(true); ds.set(routed, source);
        var originalEvidence = new PublicOccupancyRepository(routed, CLOCK).readEvidence(SCOPE);
        String original = originalEvidence.snapshot().json();
        assertTrue(moved.get(), "the writer must commit between SELECTs");
        assertTrue(original.contains("PARTIAL"), "original capacity=2, one tenant; not mixed EMPTY");
        assertEquals(CLOCK.instant(), originalEvidence.capturedAt());
        assertEquals(0L, originalEvidence.propertyRevisions().get(PROPERTY));
        assertEquals(PROPERTY, originalEvidence.tenantRevisions().get(TENANT).propertyId());
        assertEquals(0, originalEvidence.tenantRevisions().get(TENANT).slot());
        assertEquals(0L, originalEvidence.tenantRevisions().get(TENANT).revision());
        List<String> afterWriter = data();
        var laterEvidence = new PublicOccupancyRepository(database, CLOCK).readEvidence(SCOPE);
        assertTrue(laterEvidence.snapshot().json().contains("EMPTY"));
        assertEquals(1L, laterEvidence.propertyRevisions().get(PROPERTY));
        assertTrue(laterEvidence.tenantRevisions().isEmpty());
        assertEquals(afterWriter, data());
    }

    @Test void serverRejectsWriteInBorrowedReadOnlySnapshot() throws Exception {
        List<String> before = data();
        try (ReadSnapshot snapshot = ReadSnapshot.open(database, CLOCK)) {
            assertTrue(new PublicOccupancyRepository(database, CLOCK).read(snapshot, SCOPE).json().contains("PARTIAL"));
            try (Statement s = snapshot.connection(database).createStatement()) {
                SQLException error = assertThrows(SQLException.class, () -> s.executeUpdate("DELETE FROM npc_tenants"));
                assertEquals(1792, error.getErrorCode());
            }
        }
        assertEquals(before, data());
    }

    @Test void realDatabaseThroughWorkerCommitAndPumpPreservesSourcesAndPrivateFields(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        var task=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<?>>();
        var session=new com.prosperitysmp.npc.publication.OccupancyPublicationSession(directory,SCOPE,
                new PublicOccupancyRepository(database,CLOCK)::readEvidence,CLOCK,System::nanoTime,
                Duration.ofSeconds(30),Duration.ofHours(1),Duration.ofDays(1));
        var pump=new com.prosperitysmp.npc.publication.OccupancyRefreshPump(
                work->task.set(executor.submit(work)),session,CLOCK,System::nanoTime,Duration.ofSeconds(30));
        try {
            List<String> before=data();
            pump.configure(SCOPE);
            assertEquals(com.prosperitysmp.npc.publication.OccupancyRefreshPump.Acceptance.ACCEPTED,pump.request());
            task.get().get(10,java.util.concurrent.TimeUnit.SECONDS);pump.tick();
            String json=pump.current().orElseThrow().json();
            assertTrue(json.contains("PARTIAL"));
            String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8)));
            assertEquals(json,java.nio.file.Files.readString(directory.resolve(hash+".json")));
            assertTrue(java.nio.file.Files.exists(directory.resolve(".selection.json")));
            for(String secret:List.of("PRIVATE_NAME","PRIVATE_MARKET","PRIVATE_CLASS","827364","918273",
                    PROPERTY.toString(),WORLD.toString(),TENANT.toString())) assertFalse(json.contains(secret));
            assertEquals(before,data());

            byte[] committed=java.nio.file.Files.readAllBytes(directory.resolve(".selection.json"));
            execute("UPDATE npc_tenants SET slot=NULL");
            List<String> corruptBefore=data();
            assertEquals(com.prosperitysmp.npc.publication.OccupancyRefreshPump.Acceptance.ACCEPTED,pump.request());
            task.get().get(10,java.util.concurrent.TimeUnit.SECONDS);pump.tick();
            assertEquals(com.prosperitysmp.npc.publication.OccupancyRefreshPump.Disposition.FAILED,pump.disposition());
            assertEquals(json,pump.current().orElseThrow().json());
            assertArrayEquals(committed,java.nio.file.Files.readAllBytes(directory.resolve(".selection.json")));
            assertEquals(corruptBefore,data());
        } finally {
            pump.close();session.cancel();
            try {executor.submit(()->{session.close();return null;}).get(10,java.util.concurrent.TimeUnit.SECONDS);}
            finally {executor.shutdownNow();assertTrue(executor.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));}
        }
        try(var reopened=new com.prosperitysmp.npc.publication.AtomicOccupancyArtifacts(directory)) {
            assertTrue(reopened.beginAuthorization(UUID.randomUUID()).entries().isEmpty());
        }
    }

    private static void execute(String sql, Object... values) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) ps.setBytes(i + 1, Uuids.toBytes((UUID) values[i]));
            ps.execute();
        }
    }

    private static List<String> data() throws SQLException {
        var output = new ArrayList<String>();
        try (Connection c = database.getConnection(); Statement s = c.createStatement()) {
            for (String table : tables) try (ResultSet rs = s.executeQuery("SELECT * FROM `" + table + "`")) {
                int count = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    var row = new StringBuilder(table);
                    for (int i = 1; i <= count; i++) {
                        int type = rs.getMetaData().getColumnType(i);
                        byte[] bytes;
                        if (type == Types.BINARY || type == Types.VARBINARY || type == Types.LONGVARBINARY || type == Types.BLOB) {
                            bytes = rs.getBytes(i);
                        } else {
                            String value = rs.getString(i);
                            bytes = value == null ? null : value.getBytes(StandardCharsets.UTF_8);
                        }
                        row.append('|').append(bytes == null ? "NULL" : HexFormat.of().formatHex(bytes));
                    }
                    output.add(row.toString());
                }
            }
        }
        output.sort(String::compareTo); return List.copyOf(output);
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}

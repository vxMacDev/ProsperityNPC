package com.prosperitysmp.npc.it;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.economy.EconomyEvent;
import com.prosperitysmp.npc.economy.EconomyEventRepository;
import com.prosperitysmp.npc.economy.EventSource;
import com.prosperitysmp.npc.property.OwnerDeskData;
import com.prosperitysmp.npc.property.OwnerDeskRepository;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyRepository;
import com.prosperitysmp.npc.property.Tenant;
import com.prosperitysmp.npc.property.TenantRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Isolated real MariaDB fixtures only. No Bukkit, live ARM, Vault, or production connection. */
class OwnerDeskRepositoryIT {
    private static final LocalDate END = LocalDate.of(2026, 9, 7);
    private static final UUID WORLD = new UUID(0, 302);
    private static final String REGION = "Desk-Fixture";
    private static final String AT = "2026-09-01 12:00:00.123";
    private static final List<String> READ_TABLES = List.of("npc_properties", "npc_tenants", "npc_incidents",
            "npc_upgrades", "npc_economy_events", "npc_property_insurance", "npc_insurance_preventions");
    private static DB mariaDb;
    private static Database database;
    private static Database readOnlyDatabase;
    private static String schema;
    private OwnerDeskRepository desk;
    private Property property;
    private UUID owner;

    @BeforeAll
    static void bootDatabase() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        cfg.setSecurityDisabled(false);
        cfg.addArg("--bind-address=127.0.0.1");
        mariaDb = DB.newEmbeddedDB(cfg.build());
        mariaDb.start();
        schema = "npc_owner_desk_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection bootstrap = java.sql.DriverManager.getConnection(
                "jdbc:mariadb://127.0.0.1:" + cfg.getPort() + "/", "root", "")) {
            execute(bootstrap, "CREATE DATABASE " + schema);
        }
        database = new Database();
        database.connect(new DatabaseSettings("localhost", cfg.getPort(), schema, "root", "", 6, 10_000L));
        for (String migration : List.of("V1__initial_schema.sql", "V2__resident_identity.sql",
                "V3__purchase_receipts.sql", "V4__transfer_journal.sql", "V5__resident_route_tags.sql",
                "V6__incident_repairs.sql", "V7__incident_rolls.sql", "V8__eviction_tenancies.sql",
                "V23__property_insurance.sql", "V45__property_backend.sql")) {
            try (InputStream in = OwnerDeskRepositoryIT.class.getResourceAsStream("/db/migration/" + migration)) {
                assertNotNull(in, migration);
                for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8)))
                    execute(sql);
            }
        }
        execute("CREATE USER 'desk_reader'@'localhost' IDENTIFIED BY ''");
        for (String table : READ_TABLES) execute("GRANT SELECT ON " + schema + "." + table + " TO 'desk_reader'@'localhost'");
        readOnlyDatabase = new Database();
        readOnlyDatabase.connect(new DatabaseSettings("localhost", cfg.getPort(), schema,
                "desk_reader", "", 2, 10_000L));
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        try {
            if (readOnlyDatabase != null) readOnlyDatabase.close();
            if (database != null) database.close();
        } finally {
            if (mariaDb != null) mariaDb.stop();
        }
    }

    @BeforeEach
    void freshFixture() throws SQLException {
        for (String table : List.of("npc_economy_events", "npc_tenants", "npc_incidents", "npc_upgrades",
                "npc_property_ownership", "npc_property_funding", "npc_properties")) execute("DELETE FROM " + table);
        owner = UUID.randomUUID();
        property = new Property(UUID.randomUUID(), WORLD, REGION, "fixture", "room", 2,
                1000, 1200, owner, 3, 65, true);
        new PropertyRepository(database).insertEnrollment(property, 5000);
        desk = new OwnerDeskRepository(readOnlyDatabase);
    }

    @Test
    void occupiedDamagedSnapshotHasOrderedResidentsActiveIssuesAndDisabledUpgrade() throws SQLException {
        UUID second = tenant(1), first = tenant(0);
        execute("UPDATE npc_tenants SET mood='HAPPY', satisfaction=79 WHERE tenant_id=?", first);
        incident(END, false, false);
        incident(END.minusDays(1), true, false);
        incident(END.minusDays(2), false, true);
        execute("INSERT INTO npc_upgrades (property_id,upgrade_type,level,disabled_incident_id) "
                + "SELECT property_id,'FACILITIES',2,incident_id FROM npc_incidents WHERE economy_day=?", END.toString());
        execute("UPDATE npc_properties SET row_version=42 WHERE property_id=?", property.propertyId());
        OwnerDeskData data = read();
        assertEquals(property, data.property());
        assertEquals(42, data.revision());
        assertTrue(data.capturedAt().isAfter(Instant.parse("2026-09-01T00:00:00Z")));
        assertEquals(List.of(first, second), data.residents().stream().map(OwnerDeskData.Resident::id).toList());
        assertEquals("Fixture Resident", data.residents().getFirst().name());
        assertEquals("STANDARD", data.residents().getFirst().tier());
        assertEquals("HAPPY", data.residents().getFirst().mood());
        assertEquals(79, data.residents().getFirst().satisfaction());
        assertEquals(1, data.issues().size());
        assertEquals("PLUMBING", data.issues().getFirst().type());
        assertEquals(350, data.issues().getFirst().repairCents());
        assertEquals(Instant.parse("2026-09-08T04:00:00.123Z"), data.issues().getFirst().dueAt());
        assertEquals(List.of(new OwnerDeskData.Upgrade("FACILITIES", 2, true)), data.upgrades());
    }

    @Test
    void emptyHistoryIsARealZeroWithoutReadingOrInventingFunding() throws SQLException {
        execute("DELETE FROM npc_property_funding");
        OwnerDeskData data = read();
        assertTrue(data.residents().isEmpty());
        assertTrue(data.issues().isEmpty());
        assertTrue(data.upgrades().isEmpty());
        assertEquals(new OwnerDeskData.Statement(END.minusDays(6), END, 0, 0, 0, 0), data.statement());
        assertTrue(desk.read(UUID.randomUUID(), REGION, owner, END).isEmpty());
        assertTrue(desk.read(WORLD, "missing", owner, END).isEmpty());
        assertTrue(desk.read(WORLD, "desk-fixture", owner, END).isEmpty(), "region comparison is case-exact");
        assertTrue(desk.read(WORLD, REGION + " ", owner, END).isEmpty(), "region comparison preserves trailing spaces");
        assertTrue(desk.read(WORLD, REGION, UUID.randomUUID(), END).isEmpty());
        new PropertyRepository(database).setEnabled(property.propertyId(), false);
        assertTrue(desk.read(WORLD, REGION, owner, END).isEmpty());
        execute("UPDATE npc_properties SET enabled=TRUE,owner_uuid=NULL");
        assertTrue(desk.read(WORLD, REGION, owner, END).isEmpty());
        try (Database disconnected = new Database()) {
            assertThrows(SQLException.class,
                    () -> new OwnerDeskRepository(disconnected).read(WORLD, REGION, owner, END));
        }
    }

    @Test void billsOffNeedsNoInvoiceTablesOrInvoiceReadPermission() throws SQLException {
        assertTrue(desk.read(WORLD, REGION, owner, END).orElseThrow().bills().isEmpty());
        assertTrue(desk.read(WORLD, REGION, owner, END, false).orElseThrow().bills().isEmpty());
        assertThrows(SQLException.class, () -> desk.read(WORLD, REGION, owner, END, true));
    }

    @Test
    void sevenInclusiveDaysExcludeOtherPropertyOwnerEpochAndOutOfWindowPoison() throws SQLException {
        for (int i = 0; i < 7; i++) event(END.minusDays(i), EventSource.RENT, "PAID", 1000, 100L);
        event(END, EventSource.REPAIR, "PAID", 1000, 250L);
        for (String scope : List.of("owner", "epoch", "null-owner", "null-epoch", "property", "before", "after")) {
            long id = event(scope.equals("before") ? END.minusDays(7) : scope.equals("after") ? END.plusDays(1) : END,
                    EventSource.RENT, "PAID", 1000, null);
            switch (scope) {
                case "owner" -> execute("UPDATE npc_economy_events SET counterparty_uuid=? WHERE event_id=?", UUID.randomUUID(), id);
                case "epoch" -> execute("UPDATE npc_economy_events SET ownership_epoch=2 WHERE event_id=?", id);
                case "null-owner" -> execute("UPDATE npc_economy_events SET counterparty_uuid=NULL WHERE event_id=?", id);
                case "null-epoch" -> execute("UPDATE npc_economy_events SET ownership_epoch=NULL WHERE event_id=?", id);
                case "property" -> execute("UPDATE npc_economy_events SET property_id=? WHERE event_id=?", UUID.randomUUID(), id);
                default -> { }
            }
        }
        assertEquals(new OwnerDeskData.Statement(END.minusDays(6), END, 700, 250, 0, 0), read().statement());
        assertEquals(450, read().statement().netCents());
    }

    @ParameterizedTest
    @EnumSource(EventSource.class)
    void everyKnownSourceUsesActualCentsAndItsValidatedDirection(EventSource source) throws SQLException {
        event(END, source, "PAID", 1234, 321L);
        OwnerDeskData.Statement result = read().statement();
        assertEquals(source.isPayout() ? 321 : 0, result.grossCents());
        assertEquals(source.isPayout() ? 0 : 321, result.expenseCents());
    }

    @Test
    void unconfirmedEventsAreCountsAndBothExistingFailureAndAmbiguityFormsAreSupported() throws SQLException {
        event(END, EventSource.RENT, "PENDING", Long.MAX_VALUE, null);
        event(END, EventSource.RENT, "CLAIMED", Long.MAX_VALUE, null);
        event(END, EventSource.RENT, "AMBIGUOUS", Long.MAX_VALUE, null);
        long terminal = event(END, EventSource.REPAIR, "AMBIGUOUS", Long.MAX_VALUE, null);
        execute("UPDATE npc_economy_events SET finalized_at=? WHERE event_id=?", AT, terminal);
        event(END, EventSource.REPAIR, "FAILED", 1000, null);
        event(END, EventSource.REPAIR, "FAILED", 1000, 0L);
        assertEquals(new OwnerDeskData.Statement(END.minusDays(6), END, 0, 0, 1, 3), read().statement());
    }

    @ParameterizedTest
    @ValueSource(strings = {"paid_amount_cents=NULL", "paid_amount_cents=0", "paid_amount_cents=-1",
            "paid_amount_cents=1001", "requested_amount_cents=0", "requested_amount_cents=-1",
            "finalized_at=NULL", "claimed_at=NULL", "claimed_at='2026-08-31 00:00:00'",
            "finalized_at='2026-08-31 00:00:00'", "finalized_at='9999-01-01 00:00:00'",
            "created_at='9999-01-01 00:00:00'", "state='UNKNOWN'", "state='paid'", "source='unknown'",
            "source='NPC-PROPERTY-RENT'", "direction='TRANSFER_FROM_PUBLIC'", "direction='transfer_to_public'",
            "state='FAILED'", "state='PENDING'", "state='CLAIMED'", "state='AMBIGUOUS'"})
    void poisonFinancialRowFailsClosedWithoutChangingAnyRows(String poison) throws SQLException {
        long id = event(END, EventSource.RENT, "PAID", 1000, 500L);
        execute("UPDATE npc_economy_events SET " + poison + " WHERE event_id=?", id);
        Map<String, List<String>> before = fingerprint();
        assertThrows(SQLException.class, this::read);
        assertEquals(before, fingerprint());
    }

    @ParameterizedTest
    @ValueSource(strings = {"state='PENDING',claimed_at=NULL,paid_amount_cents=NULL",
            "state='CLAIMED',paid_amount_cents=NULL", "state='FAILED',paid_amount_cents=0,finalized_at=NULL",
            "state='AMBIGUOUS',paid_amount_cents=NULL,claimed_at=NULL,finalized_at=NULL",
            "state='PENDING',paid_amount_cents=NULL,finalized_at=NULL",
            "state='PENDING',claimed_at=NULL,finalized_at=NULL", "state='CLAIMED',finalized_at=NULL"})
    void contradictoryNonPaidEvidenceCannotBecomePlausibleCounts(String poison) throws SQLException {
        long id = event(END, EventSource.RENT, "PAID", 1000, 500L);
        execute("UPDATE npc_economy_events SET " + poison + " WHERE event_id=?", id);
        assertThrows(SQLException.class, this::read);
    }

    @Test
    void invalidCalendarDayWithinLexicalWindowFailsInsteadOfBeingCounted() throws SQLException {
        long id = event(END, EventSource.RENT, "PAID", 1000, 100L);
        execute("UPDATE npc_economy_events SET economy_day='2026-02-30' WHERE event_id=?", id);
        assertThrows(SQLException.class, () -> desk.read(WORLD, REGION, owner, LocalDate.of(2026, 3, 3)));
    }

    @ParameterizedTest
    @EnumSource(value = EventSource.class, names = {"RENT", "REPAIR"})
    void totalOverflowIsUnavailableNotWrappedOrClamped(EventSource source) throws SQLException {
        event(END, source, "PAID", Long.MAX_VALUE, Long.MAX_VALUE);
        assertEquals(source.isPayout() ? Long.MAX_VALUE : -Long.MAX_VALUE, read().statement().netCents());
        event(END, source, "PAID", 1, 1L);
        assertThrows(SQLException.class, this::read);
    }

    @Test
    void idempotentReceiptReplayAndRepeatedReadsNeverMutateOrDoubleCount() throws SQLException {
        EconomyEventRepository events = new EconomyEventRepository(database);
        EconomyEvent request = EconomyEvent.pending("desk/replay", 200, EventSource.RENT,
                property.propertyId(), owner, 3, owner, END.toString());
        long id = events.insertPending(request);
        assertTrue(events.claim(id));
        events.finalizePaid(id, 200);
        assertEquals(-1, events.insertPending(request));
        events.finalizePaid(id, 200);
        Map<String, List<String>> before = fingerprint();
        for (int i = 0; i < 3; i++) {
            assertEquals(200, new OwnerDeskRepository(readOnlyDatabase)
                    .read(WORLD, REGION, owner, END).orElseThrow().statement().grossCents());
        }
        assertEquals(200, new OwnerDeskRepository(database)
                .read(WORLD, REGION, owner, END).orElseThrow().statement().grossCents());
        assertEquals(before, fingerprint());
        try (Connection c = readOnlyDatabase.getConnection()) {
            assertThrows(SQLException.class, () -> execute(c, "UPDATE npc_properties SET asking_rent_cents=1"));
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM npc_property_funding LIMIT 1")) {
                ps.setQueryTimeout(2);
                assertThrows(SQLException.class, ps::executeQuery);
            }
        }
        try (Connection c = database.getConnection()) {
            assertTrue(c.getAutoCommit(), "pool remains usable for writers");
            execute(c, "UPDATE npc_properties SET asking_rent_cents=1300 WHERE property_id=?", property.propertyId());
        }
        assertEquals(1300, read().property().askingRentCents());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "negative", "outside", "duplicate", "capacity-zero", "capacity-five"})
    void invalidTenantCapacityOrSlotFailsClosed(String poison) throws SQLException {
        UUID tenant = tenant(0);
        switch (poison) {
            case "null" -> execute("UPDATE npc_tenants SET slot=NULL WHERE tenant_id=?", tenant);
            case "negative" -> execute("UPDATE npc_tenants SET slot=-1 WHERE tenant_id=?", tenant);
            case "outside" -> execute("UPDATE npc_tenants SET slot=2 WHERE tenant_id=?", tenant);
            case "duplicate" -> tenant(0);
            default -> {
                try (Connection c = database.getConnection()) {
                    execute(c, "SET SESSION check_constraint_checks=OFF");
                    try { execute(c, "UPDATE npc_properties SET capacity=?", poison.equals("capacity-zero") ? 0 : 5); }
                    finally { execute(c, "SET SESSION check_constraint_checks=ON"); }
                }
            }
        }
        assertThrows(SQLException.class, this::read);
    }

    @ParameterizedTest
    @ValueSource(strings = {"events", "issues", "residents", "upgrades"})
    void exactRowLimitWorksButOneMoreFailsInsteadOfTruncating(String kind) throws SQLException {
        int max = switch (kind) { case "events" -> 1024; case "issues" -> 16; case "residents" -> 4; default -> 2; };
        if (kind.equals("residents")) execute("UPDATE npc_properties SET capacity=4");
        for (int i = 0; i < max; i++) boundedRow(kind, i);
        OwnerDeskData atLimit = read();
        assertEquals(max, switch (kind) {
            case "events" -> atLimit.statement().pendingCount(); case "issues" -> atLimit.issues().size();
            case "residents" -> atLimit.residents().size(); default -> atLimit.upgrades().size();
        });
        boundedRow(kind, max);
        assertThrows(SQLException.class, this::read);
    }

    @Test
    void repeatableReadKeepsPropertyResidentsAndReceiptsFromOneSnapshotAcrossConcurrentSale() throws Exception {
        UUID originalTenant = tenant(0);
        event(END, EventSource.RENT, "PAID", 1000, 100L);
        UUID newOwner = UUID.randomUUID();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<OwnerDeskData> result;
            try (Connection blocker = database.getConnection()) {
                execute(blocker, "LOCK TABLES npc_economy_events WRITE");
                try {
                    result = worker.submit(this::read);
                    awaitLedgerRead();
                    execute("UPDATE npc_properties SET owner_uuid=?,ownership_epoch=4,row_version=1,asking_rent_cents=2000", newOwner);
                    execute(blocker, "UPDATE npc_economy_events SET paid_amount_cents=900");
                    execute("UPDATE npc_tenants SET display_name='After Sale'");
                } finally { execute(blocker, "UNLOCK TABLES"); }
            }
            OwnerDeskData captured = result.get(8, TimeUnit.SECONDS);
            assertEquals(property, captured.property());
            assertEquals(0, captured.revision());
            assertEquals(originalTenant, captured.residents().getFirst().id());
            assertEquals("Fixture Resident", captured.residents().getFirst().name());
            assertEquals(100, captured.statement().grossCents());
            assertTrue(desk.read(WORLD, REGION, owner, END).isEmpty());
            OwnerDeskData after = desk.read(WORLD, REGION, newOwner, END).orElseThrow();
            assertEquals(2000, after.property().askingRentCents());
            assertEquals("After Sale", after.residents().getFirst().name());
            assertEquals(0, after.statement().grossCents(), "seller receipt is private even after mutation");
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void blockedSelectHasADeadlineAndFailureDoesNotPoisonNextRead() throws Exception {
        tenant(0);
        event(END, EventSource.RENT, "PENDING", 100, null);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            try (Connection blocker = database.getConnection()) {
                execute(blocker, "LOCK TABLES npc_economy_events WRITE");
                try {
                    Future<OwnerDeskData> result = worker.submit(this::read);
                    awaitLedgerRead();
                    ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(8, TimeUnit.SECONDS));
                    assertTrue(failure.getCause() instanceof SQLException, "SQL deadline must fail closed");
                } finally { execute(blocker, "UNLOCK TABLES"); }
            }
            assertEquals(1, read().residents().size());
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private OwnerDeskData read() throws SQLException {
        return desk.read(WORLD, REGION, owner, END).orElseThrow();
    }

    @Test
    void excludedHistoryCannotExhaustTheWorkerOrProduceAFalseEmptyStatement() throws SQLException {
        event(END, EventSource.RENT, "PAID", 1000, 100L);
        execute("UPDATE npc_economy_events SET ownership_epoch=2");
        // All rows share this property/day; the owner+epoch filter excludes all of them.
        for (int i=0;i<14;i++) execute("""
                INSERT INTO npc_economy_events (idempotency_key,property_id,counterparty_uuid,ownership_epoch,
                    economy_day,source,direction,state,requested_amount_cents,paid_amount_cents,created_at,claimed_at,finalized_at)
                SELECT CONCAT(idempotency_key,?),property_id,counterparty_uuid,ownership_epoch,
                    economy_day,source,direction,state,requested_amount_cents,paid_amount_cents,created_at,claimed_at,finalized_at
                FROM npc_economy_events
                """, "-"+i);
        var failure = assertThrows(SQLException.class, this::read,
                "examined-row warning must not become an empty statement");
        assertEquals("statement query incomplete", failure.getMessage(),
                "fixture must exercise warning rejection, not fail for an unrelated SQL error");
    }

    private UUID tenant(int slot) throws SQLException {
        UUID id = UUID.randomUUID();
        new TenantRepository(database).insert(new Tenant(id, "Fixture Resident", property.propertyId(),
                slot, "STANDARD", "GENERIC", 50));
        return id;
    }

    private void incident(LocalDate day, boolean prevented, boolean resolved) throws SQLException {
        execute("INSERT INTO npc_incidents (property_id,incident_type,cash_repair_cents,item_repair_spec,"
                + "condition_damage,economy_day,due_at,prevented,resolved_at) VALUES (?,'PLUMBING',350,'',5,?,?,?,?)",
                property.propertyId(), day.toString(), "2026-09-08 04:00:00.123", prevented, resolved ? AT : null);
    }

    private long event(LocalDate day, EventSource source, String state, long requested, Long paid) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement("""
                INSERT INTO npc_economy_events (idempotency_key,property_id,counterparty_uuid,ownership_epoch,
                    economy_day,source,direction,state,requested_amount_cents,paid_amount_cents,created_at,claimed_at,finalized_at)
                VALUES (?,?,?,3,?,?,?,?,?,?,?,?,?)
                """, java.sql.Statement.RETURN_GENERATED_KEYS)) {
            ps.setQueryTimeout(3);
            bind(ps, UUID.randomUUID().toString(), property.propertyId(), owner, day.toString(), source.key(),
                    source.direction().moneyFlowKey(), state, requested, paid, AT,
                    state.equals("PENDING") ? null : AT, state.equals("PAID") || state.equals("FAILED") ? AT : null);
            assertEquals(1, ps.executeUpdate());
            try (ResultSet keys = ps.getGeneratedKeys()) { assertTrue(keys.next()); return keys.getLong(1); }
        }
    }

    private void boundedRow(String kind, int index) throws SQLException {
        switch (kind) {
            case "events" -> event(END, EventSource.RENT, "PENDING", 1, null);
            case "issues" -> incident(END.minusDays(index), false, false);
            case "residents" -> tenant(index);
            default -> execute("INSERT INTO npc_upgrades (property_id,upgrade_type,level) VALUES (?,?,1)",
                    property.propertyId(), index == 0 ? "ADVERTISING" : index == 1 ? "FACILITIES" : "UNKNOWN");
        }
    }

    private static void awaitLedgerRead() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement("""
                    SELECT ID FROM information_schema.PROCESSLIST WHERE DB=? AND USER='desk_reader'
                      AND INFO LIKE '%FROM npc_economy_events%' LIMIT 2
                    """)) {
                ps.setQueryTimeout(1);
                ps.setString(1, schema);
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return; }
            }
            Thread.sleep(10);
        }
        throw new AssertionError("owner desk never reached its ledger SELECT");
    }

    private static Map<String, List<String>> fingerprint() throws SQLException {
        Map<String, List<String>> result = new LinkedHashMap<>();
        try (Connection c = database.getConnection(); PreparedStatement tables = c.prepareStatement(
                "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=? ORDER BY TABLE_NAME LIMIT 32")) {
            tables.setQueryTimeout(3);
            tables.setString(1, schema);
            try (ResultSet names = tables.executeQuery()) {
                while (names.next()) {
                    String table = names.getString(1);
                    assertTrue(table.matches("npc_[a-z_]+"));
                    List<String> rows = new ArrayList<>();
                    try (PreparedStatement ps = c.prepareStatement("SELECT * FROM " + table + " LIMIT 2048")) {
                        ps.setQueryTimeout(3);
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                List<String> cells = new ArrayList<>();
                                for (int col = 1; col <= rs.getMetaData().getColumnCount(); col++) {
                                    Object value = rs.getObject(col);
                                    if (value == null) cells.add("NULL");
                                    else if (value instanceof byte[] bytes) cells.add("B:"+HexFormat.of().formatHex(bytes));
                                    else cells.add("V:"+HexFormat.of().formatHex(value.toString().getBytes(StandardCharsets.UTF_8)));
                                }
                                rows.add(String.join(":", cells));
                            }
                        }
                    }
                    rows.sort(String::compareTo);
                    result.put(table, rows);
                }
            }
        }
        assertFalse(result.isEmpty());
        return result;
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

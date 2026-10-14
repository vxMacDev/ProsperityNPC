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
import com.prosperitysmp.npc.formulas.IncidentType;
import com.prosperitysmp.npc.property.IncidentRepository;
import com.prosperitysmp.npc.property.OwnerDeskData;
import com.prosperitysmp.npc.property.OwnerDeskRepository;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Isolated MariaDB fixtures, not a migration or evidence about existing timestamp provenance. */
@Isolated("Changes the JVM default timezone before creating JDBC pools")
@Execution(ExecutionMode.SAME_THREAD)
class PropertyTimestampIT {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 7);
    private static DB mariaDb;
    private static int port;
    private static String schema;
    private TimeZone originalZone;

    @BeforeAll
    static void bootDatabase() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        cfg.setSecurityDisabled(false);
        cfg.addArg("--bind-address=127.0.0.1");
        cfg.addArg("--default-time-zone=+09:00");
        mariaDb = DB.newEmbeddedDB(cfg.build());
        mariaDb.start();
        port = cfg.getPort();
        schema = "npc_timestamp_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = DriverManager.getConnection("jdbc:mariadb://127.0.0.1:" + port + "/", "root", "")) {
            execute(c, "CREATE DATABASE " + schema);
            execute(c, "USE " + schema);
            for (String migration : List.of("V1__initial_schema.sql", "V2__resident_identity.sql",
                    "V3__purchase_receipts.sql", "V4__transfer_journal.sql", "V5__resident_route_tags.sql",
                    "V6__incident_repairs.sql", "V7__incident_rolls.sql", "V8__eviction_tenancies.sql",
                    "V23__property_insurance.sql")) {
                try (InputStream in = PropertyTimestampIT.class.getResourceAsStream("/db/migration/" + migration)) {
                    assertNotNull(in, migration);
                    for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8)))
                        execute(c, sql);
                }
            }
        }
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        if (mariaDb != null) mariaDb.stop();
    }

    @BeforeEach
    void rememberJvmZone() {
        originalZone = TimeZone.getDefault();
    }

    @AfterEach
    void restoreJvmZone() {
        TimeZone.setDefault(originalZone);
    }

    @ParameterizedTest
    @ValueSource(strings = {"America/Chicago", "Asia/Tokyo"})
    void everyPhysicalPoolConnectionUsesUtcDespiteServerAndJvmDefaults(String zone) throws SQLException {
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of(zone)));
        try (Connection raw = DriverManager.getConnection(url(), "root", "");
             PreparedStatement ps = prepare(raw, "SELECT @@session.time_zone, "
                     + "TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(3), CURRENT_TIMESTAMP(3))");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals("+09:00", rs.getString(1), "uninitialized sessions must expose the regression");
            assertEquals(9 * 60 * 60, rs.getLong(2));
        }
        try (Database database = new Database()) {
            database.connect(settings());
            try (Connection first = database.getConnection();
                 Connection second = database.getConnection();
                 Connection third = database.getConnection()) {
                var physicalIds = new HashSet<Long>();
                physicalIds.add(assertUtcSession(first));
                physicalIds.add(assertUtcSession(second));
                physicalIds.add(assertUtcSession(third));
                assertEquals(3, physicalIds.size(), "hold connections concurrently to force pool expansion");
            }
            try (Connection reused = database.getConnection()) {
                assertUtcSession(reused);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
            "America/Chicago, 2026-03-08T07:59:59.123Z",
            "America/Chicago, 2026-03-08T08:00:00.123Z",
            "America/Chicago, 2026-11-01T06:30:00.123Z",
            "America/Chicago, 2026-11-01T07:30:00.123Z",
            "Asia/Tokyo, 2026-09-08T09:00:00.123Z"
    })
    void realIncidentWriterRoundTripsExactUtcDeadlineThroughOwnerDesk(String zone, String deadline) throws SQLException {
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of(zone)));
        Instant due = Instant.parse(deadline);
        try (Database database = new Database()) {
            database.connect(settings());
            Property property = enroll(database);
            IncidentRepository incidents = new IncidentRepository(database);
            Instant before = utcNow(database);
            incidents.create(property.propertyId(), IncidentType.PLUMBING, null,
                    DAY.toString(), due.toEpochMilli(), false);
            Instant beforeResolution = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            incidents.create(property.propertyId(), IncidentType.PESTS, null,
                    DAY.minusDays(1).toString(), due.toEpochMilli(), true);
            Instant afterResolution = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            Instant after = utcNow(database);

            OwnerDeskData data = new OwnerDeskRepository(database)
                    .read(property.worldUuid(), property.regionId(), property.ownerUuid(), DAY).orElseThrow();
            assertEquals(1, data.issues().size(), "prevented incident stays outside the active desk");
            assertEquals(due, data.issues().getFirst().dueAt(), "deadline must not shift with the JVM zone or DST");
            assertEquals(IncidentType.PLUMBING.cashRepairCents(), data.issues().getFirst().repairCents());

            try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c,
                    "SELECT created_at,due_at,resolved_at,prevented FROM npc_incidents "
                            + "WHERE property_id=? ORDER BY economy_day", property.propertyId());
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertTrue(rs.getBoolean("prevented"));
                assertEquals(LocalDateTime.ofInstant(due, ZoneOffset.UTC), rs.getObject("due_at", LocalDateTime.class));
                assertBetween(instant(rs, "created_at"), before, after);
                assertBetween(instant(rs, "resolved_at"), beforeResolution, afterResolution);
                assertTrue(rs.next());
                assertFalse(rs.getBoolean("prevented"));
                assertEquals(LocalDateTime.ofInstant(due, ZoneOffset.UTC), rs.getObject("due_at", LocalDateTime.class));
                assertBetween(instant(rs, "created_at"), before, after);
                assertNull(rs.getObject("resolved_at"));
                assertFalse(rs.next());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"America/Chicago", "Asia/Tokyo"})
    void realReceiptDefaultsClaimAndFinalizationStayUtcAndRemainReadable(String zone) throws SQLException {
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of(zone)));
        try (Database database = new Database()) {
            database.connect(settings());
            Instant before = utcNow(database);
            Property property = enroll(database);
            EconomyEventRepository events = new EconomyEventRepository(database);
            long eventId = events.insertPending(EconomyEvent.pending("timestamp/" + property.propertyId(),
                    1234, EventSource.RENT, property.propertyId(), property.ownerUuid(), property.ownershipEpoch(),
                    property.ownerUuid(), DAY.toString()));
            assertTrue(eventId > 0);
            assertTrue(events.claim(eventId));
            events.finalizePaid(eventId, 1234);
            Instant after = utcNow(database);
            OwnerDeskData data = new OwnerDeskRepository(database)
                    .read(property.worldUuid(), property.regionId(), property.ownerUuid(), DAY).orElseThrow();
            assertEquals(new OwnerDeskData.Statement(DAY.minusDays(6), DAY, 1234, 0, 0, 0), data.statement());
            assertBetween(data.capturedAt(), before, utcNow(database));
            try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c, """
                    SELECT e.created_at,e.claimed_at,e.finalized_at,p.created_at AS enrolled_at
                    FROM npc_economy_events e JOIN npc_properties p ON p.property_id=e.property_id
                    WHERE e.event_id=?
                    """, eventId); ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                for (String column : List.of("enrolled_at", "created_at", "claimed_at", "finalized_at"))
                    assertBetween(instant(rs, column), before, after);
                assertFalse(rs.next());
            }
        }
    }

    private static String url() {
        return "jdbc:mariadb://127.0.0.1:" + port + "/" + schema;
    }

    private static DatabaseSettings settings() {
        return new DatabaseSettings("127.0.0.1", port, schema, "root", "", 3, 10_000L);
    }

    private static Property enroll(Database database) throws SQLException {
        Property property = new Property(UUID.randomUUID(), UUID.randomUUID(), "Timestamp-Fixture",
                "fixture", "room", 1, 1000, 1000, UUID.randomUUID(), 1, 80, true);
        new PropertyRepository(database).insertEnrollment(property, 5000);
        return property;
    }

    private static long assertUtcSession(Connection c) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT CONNECTION_ID(),@@session.time_zone,"
                + "CURRENT_TIMESTAMP(3),UTC_TIMESTAMP(3),@@global.time_zone"); ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            assertEquals("+00:00", rs.getString(2));
            assertEquals(rs.getObject(4, LocalDateTime.class), rs.getObject(3, LocalDateTime.class));
            assertEquals("+09:00", rs.getString(5), "session init must not change the server global timezone");
            return rs.getLong(1);
        }
    }

    private static Instant utcNow(Database database) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c, "SELECT UTC_TIMESTAMP(3) AS utc_now");
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            return instant(rs, "utc_now");
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        assertNotNull(value, column);
        return value.toInstant(ZoneOffset.UTC);
    }

    private static void assertBetween(Instant value, Instant before, Instant after) {
        assertFalse(value.isBefore(before), () -> value + " precedes UTC lower bound " + before);
        assertFalse(value.isAfter(after), () -> value + " exceeds UTC upper bound " + after);
    }

    private static void execute(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = prepare(c, sql)) { ps.execute(); }
    }

    private static PreparedStatement prepare(Connection c, String sql, Object... values) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        try {
            ps.setQueryTimeout(3);
            for (int i = 0; i < values.length; i++) {
                if (values[i] instanceof UUID uuid) ps.setBytes(i + 1, Uuids.toBytes(uuid));
                else ps.setObject(i + 1, values[i]);
            }
            return ps;
        } catch (SQLException failure) {
            try { ps.close(); } catch (SQLException close) { failure.addSuppressed(close); }
            throw failure;
        }
    }
}

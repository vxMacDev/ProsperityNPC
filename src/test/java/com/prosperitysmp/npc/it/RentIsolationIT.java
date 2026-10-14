package com.prosperitysmp.npc.it;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.config.EconomyLimits;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.economy.RentReservationRepository;
import com.prosperitysmp.npc.economy.TransferJournal;
import com.prosperitysmp.npc.economy.TransferJournal.Handle;
import com.prosperitysmp.npc.economy.TransferProof.Observation;
import com.prosperitysmp.npc.economy.UtilityPaymentRuntime;
import com.prosperitysmp.npc.economy.UtilityRepository;
import com.prosperitysmp.npc.economy.UtilityRepository.Receipt;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyRepository;
import com.prosperitysmp.npc.property.Tenant;
import com.prosperitysmp.npc.property.TenantRepository;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rent isolation on a real MariaDB: the production owner plan, the real rent reservation SQL and the
 * real transfer journal. Vault is replaced by fixture observations; nothing here can move money.
 */
@Timeout(60)
class RentIsolationIT {
    private static final Instant NOW = Instant.parse("2026-09-07T12:00:00.001Z");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 7);
    private static final ZoneId ZONE = ZoneId.of("America/Chicago");
    private static final int RESET = 4;
    private static final UUID WORLD = new UUID(0, 2900);
    private static final UUID RESERVE = new UUID(0, 2902);
    private static final String RENT_ATTEMPTS = "npc_transfer_attempts a JOIN npc_economy_events e ON e.event_id=a.event_id "
            + "WHERE e.source='npc-property-rent'";
    private static DB mariaDb;
    private static Database database;
    private static List<String> tables;
    private MutableClock clock;
    private List<UUID> properties;
    private UUID unreadable;

    @BeforeAll
    static void bootDatabase() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        try {
            mariaDb.start();
            String schema = "npc_rent_iso_" + UUID.randomUUID().toString().replace("-", "");
            mariaDb.createDB(schema);
            database = new Database();
            database.connect(new DatabaseSettings("localhost", cfg.getPort(), schema, "root", "", 6, 10_000L));
            for (String file : List.of("V1__initial_schema.sql", "V2__resident_identity.sql",
                    "V3__purchase_receipts.sql", "V4__transfer_journal.sql", "V5__resident_route_tags.sql",
                    "V6__incident_repairs.sql", "V7__incident_rolls.sql", "V8__eviction_tenancies.sql",
                    "V9__housing_registry.sql", "V10__housing_moves.sql", "V11__housing_history_order.sql",
                    "V12__incident_service_receipts.sql", "V13__utility_obligations.sql",
                    "V16__rent_reservations.sql", "V33__payout_retirements.sql")) {
                applyResource("/db/migration/" + file);
            }
            tables = rows("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() "
                    + "AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME").stream().map(List::getFirst).toList();
            assertTrue(tables.containsAll(List.of("npc_rent_reservations", "npc_transfer_attempts", "npc_economy_events")));
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
            execute(c, "SET SESSION foreign_key_checks=0");
            try { for (String table : tables) execute(c, "DELETE FROM " + table); }
            finally { execute(c, "SET SESSION foreign_key_checks=1"); }
        }
        clock = new MutableClock(NOW);
        execute("INSERT INTO npc_rent_mutex(singleton) VALUES (1)");
        var ids = new ArrayList<UUID>();
        // Four properties, four owners, one tenant each, each with today's utility bill verified paid.
        for (int i = 0; i < 4; i++) {
            UUID owner = new UUID(0, 2910 + i);
            UUID id = enroll(owner);
            occupy(id);
            Receipt utility = utilities().preparePayment(UUID.randomUUID(), id, owner, 0, DAY).receipt();
            payUtility(utility, clock.instant().plusSeconds(1));
            assertEquals(UtilityRepository.State.PAID, utilities().reconcile(utility.eventId()).orElseThrow().state());
            ids.add(id);
        }
        properties = ids.stream().sorted(Comparator.comparing(UUID::toString)).toList();
        // Not the first in sorted order, so an abort would visibly stop properties both before and after it.
        unreadable = properties.get(1);
        assertEquals(0, count("npc_rent_reservations"));
    }

    @Test
    void oneUnreadableOwnerIsHeldWhileEveryOtherPropertyIsPaidExactlyOnceAndReplayPaysNothing() throws Exception {
        Function<Property, Optional<String>> armDown = p -> p.propertyId().equals(unreadable)
                ? Optional.empty() : Optional.of("owner-read:" + p.ownerUuid());

        // Isolation off keeps the original behavior: the whole batch is refused before any SQL.
        assertThrows(IllegalStateException.class, () -> batch(false, armDown));
        assertEquals(0, count("npc_rent_reservations"));
        assertEquals(0, count("npc_economy_events WHERE source='npc-property-rent'"));
        assertEquals(0, count(RENT_ATTEMPTS));

        var plan = UtilityPaymentRuntime.planRent(enabled(), RESERVE, true, armDown);
        assertEquals(List.of(unreadable), plan.held().stream().map(Property::propertyId).toList());
        assertEquals(3, plan.eligible().size());

        assertEquals(3, batch(true, armDown), "every readable property is paid");
        for (UUID id : properties) {
            boolean held = id.equals(unreadable);
            assertEquals(held ? 0 : 1, count("npc_rent_reservations WHERE property_id=?", id));
            assertEquals(held ? 0 : 1, count("npc_economy_events WHERE source='npc-property-rent' AND property_id=?", id));
            assertEquals(held ? 0 : 1, count("npc_economy_events WHERE source='npc-property-rent' AND state='PAID' AND property_id=?", id));
        }
        assertEquals(3, count(RENT_ATTEMPTS + " AND a.state='PAID'"));
        assertEquals(1800, sumPaidRent(), "three tenants at the $6 tenant cap");

        // Replay with the owner still unreadable: nothing new is reserved, attempted or paid.
        var before = snapshot();
        assertEquals(0, batch(true, armDown));
        assertEquals(before, snapshot(), "replay must not write");
        assertEquals(0, count("npc_rent_reservations WHERE property_id=?", unreadable), "held property is not marked paid");

        // A thrown owner read is also held, never a batch abort.
        Function<Property, Optional<String>> armThrows = p -> {
            if (p.propertyId().equals(unreadable)) throw new IllegalStateException("ARM read failed");
            return Optional.of("owner-read:" + p.ownerUuid());
        };
        assertEquals(0, batch(true, armThrows));
        assertEquals(before, snapshot());

        // Once the owner is readable again the held property is paid once, later the same day, and only once.
        Function<Property, Optional<String>> armUp = p -> Optional.of("owner-read:" + p.ownerUuid());
        assertEquals(1, batch(true, armUp));
        assertEquals(1, count("npc_economy_events WHERE source='npc-property-rent' AND state='PAID' AND property_id=?", unreadable));
        var settled = snapshot();
        assertEquals(0, batch(true, armUp));
        assertEquals(settled, snapshot());
        assertEquals(4, count("npc_rent_reservations"));
        assertEquals(4, count(RENT_ATTEMPTS + " AND a.state='PAID'"));
        assertEquals(2400, sumPaidRent());
    }

    @Test
    void reserveOwnedAndMissingOwnersAreHeldOnlyWithIsolation() {
        var p = enabled();
        var reserveOwned = new Property(p.get(0).propertyId(), WORLD, "r0", "test", "room", 4, 1000, 1000, RESERVE, 0, 80, true);
        var ownerless = new Property(p.get(1).propertyId(), WORLD, "r1", "test", "room", 4, 1000, 1000, null, 0, 80, true);
        var input = List.of(reserveOwned, ownerless, p.get(2), p.get(3));
        Function<Property, Optional<String>> read = x -> Optional.of("read");
        var plan = UtilityPaymentRuntime.planRent(input, RESERVE, true, read);
        assertEquals(List.of(reserveOwned, ownerless), plan.held());
        assertEquals(List.of(p.get(2), p.get(3)), plan.eligible());
        assertEquals(List.of("read", "read"), plan.owners());
        assertThrows(IllegalStateException.class, () -> UtilityPaymentRuntime.planRent(input, RESERVE, false, read));
        // Isolation off rethrows the original read failure unchanged.
        var original = new IllegalArgumentException("read");
        assertSame(original, assertThrows(IllegalArgumentException.class,
                () -> UtilityPaymentRuntime.planRent(List.of(p.get(2)), RESERVE, false, x -> { throw original; })));
    }

    /** Mirrors requestRentBatch: plan owners, reserve and read pending for eligible only, then one journaled transfer each. */
    private int batch(boolean isolate, Function<Property, Optional<String>> read) throws SQLException {
        var plan = UtilityPaymentRuntime.planRent(enabled(), RESERVE, isolate, read);
        var rents = new RentReservationRepository(database, RESERVE, clock, ZONE, RESET, false);
        rents.reserve(DAY, plan.eligible(), limits());
        int paid = 0;
        for (var row : rents.pending(DAY, plan.eligible())) {
            assertTrue(plan.held().stream().noneMatch(h -> h.propertyId().equals(row.property().propertyId())),
                    "a held property must never reach dispatch");
            Handle handle = new Handle(UUID.randomUUID(), row.eventId(), UUID.randomUUID(), "b".repeat(64));
            var start = journal().beginDebit(handle);
            if (!start.newlyStarted()) continue;
            assertEquals(RESERVE, start.receipt().payer());
            assertEquals(row.property().ownerUuid(), start.receipt().payee());
            double dollars = row.cents() / 100.0;
            assertEquals(TransferJournal.State.CREDIT_READY, journal().recordDebit(handle,
                    new Observation(true, true, dollars, 100000.0, 100000.0 - dollars, false)).state());
            assertTrue(journal().beginCredit(handle).newlyStarted());
            assertEquals(TransferJournal.State.PAID, journal().recordCredit(handle,
                    new Observation(true, true, dollars, 50.0, 50.0 + dollars, false)).state());
            paid++;
        }
        return paid;
    }

    private List<Property> enabled() {
        var repo = new PropertyRepository(database);
        return properties.stream().map(id -> {
            try { return repo.findById(id).orElseThrow(); }
            catch (SQLException failure) { throw new IllegalStateException(failure); }
        }).toList();
    }

    private static EconomyLimits limits() throws SQLException {
        try {
            var constructor = EconomyLimits.class.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            // Shipped defaults: server $150, benefit unit $45, property $15, tenant $6, floor $35,000.
            return (EconomyLimits) constructor.newInstance(15000L, 4500L, 1500L, 600L, 300L, 3500000L, 5000L, 50000L, 0.9);
        } catch (ReflectiveOperationException failure) { throw new SQLException(failure); }
    }

    private UtilityRepository utilities() { return new UtilityRepository(database, RESERVE, clock, ZONE, RESET); }
    private static TransferJournal journal() { return new TransferJournal(database, RESERVE); }

    private static UUID enroll(UUID owner) throws SQLException {
        UUID id = UUID.randomUUID();
        new PropertyRepository(database).insertEnrollment(new Property(id, WORLD, "rent-" + id, "test", "room",
                4, 1000, 1000, owner, 0, 80, true), 5000);
        return id;
    }

    private static void occupy(UUID property) throws SQLException {
        UUID tenant = UUID.randomUUID();
        assertTrue(new ResidentRepository(database).register(ResidentCatalogue.draft(tenant, "Mara Vale")));
        new TenantRepository(database).insert(new Tenant(tenant, "Mara Vale", property, 0, "STANDARD", "COOK", 50));
    }

    /** Real journal proofs, then fixture chronology pinned the same way UtilityRepositoryIT does. */
    private void payUtility(Receipt receipt, Instant at) throws SQLException {
        Handle handle = new Handle(UUID.randomUUID(), receipt.eventId(), UUID.randomUUID(), "a".repeat(64));
        double dollars = receipt.bill().cents() / 100.0;
        assertTrue(journal().beginDebit(handle).newlyStarted());
        assertEquals(TransferJournal.State.CREDIT_READY,
                journal().recordDebit(handle, new Observation(true, true, dollars, 1000.0, 1000.0 - dollars, false)).state());
        assertTrue(journal().beginCredit(handle).newlyStarted());
        assertEquals(TransferJournal.State.PAID,
                journal().recordCredit(handle, new Observation(true, true, dollars, 2000.0, 2000.0 + dollars, false)).state());
        execute("UPDATE npc_economy_events SET created_at=?,claimed_at=?,finalized_at=? WHERE event_id=?",
                receipt.bill().issuedAt(), at.minusMillis(4), at, receipt.eventId());
        execute("UPDATE npc_transfer_attempts SET created_at=?,credit_intent_at=?,updated_at=? WHERE event_id=?",
                at.minusMillis(4), at.minusMillis(2), at, receipt.eventId());
        execute("UPDATE npc_transfer_observations o JOIN npc_transfer_attempts a ON a.attempt_id=o.attempt_id "
                        + "SET o.observed_at=CASE WHEN o.leg='DEBIT' THEN ? ELSE ? END WHERE a.event_id=?",
                at.minusMillis(3), at.minusMillis(1), receipt.eventId());
        clock.set(at.plusMillis(1));
    }

    private static long sumPaidRent() throws SQLException {
        String total = rows("SELECT COALESCE(SUM(requested_amount_cents),0) FROM npc_economy_events "
                + "WHERE source='npc-property-rent' AND state='PAID'").getFirst().getFirst();
        return Long.parseLong(total);
    }

    private static List<List<String>> snapshot() throws SQLException {
        var result = new ArrayList<List<String>>();
        for (String table : List.of("npc_rent_reservations", "npc_rent_cohort", "npc_economy_events",
                "npc_transfer_attempts", "npc_transfer_observations", "npc_property_funding")) {
            result.add(List.of(table));
            var contents = rows("SELECT * FROM " + table);
            contents.sort(Comparator.comparing(Object::toString));
            result.addAll(contents);
        }
        return result;
    }

    private static long count(String tableAndWhere, Object... args) throws SQLException {
        return Long.parseLong(rows("SELECT COUNT(*) FROM " + tableAndWhere, args).getFirst().getFirst());
    }

    private static List<List<String>> rows(String sql, Object... args) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(5);
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<List<String>> result = new ArrayList<>();
                while (rs.next()) {
                    List<String> row = new ArrayList<>();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                        int type = rs.getMetaData().getColumnType(i);
                        Object value = type == Types.TIMESTAMP ? rs.getObject(i, LocalDateTime.class) : rs.getObject(i);
                        row.add(value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : value == null ? null : value.toString());
                    }
                    result.add(row);
                }
                return result;
            }
        }
    }

    private static void execute(String sql, Object... args) throws SQLException {
        try (Connection c = database.getConnection()) { execute(c, sql, args); }
    }

    private static void execute(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(5);
            bind(ps, args);
            ps.execute();
        }
    }

    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof UUID id) ps.setBytes(i + 1, Uuids.toBytes(id));
            else if (args[i] instanceof Instant at) ps.setObject(i + 1, LocalDateTime.ofInstant(at, ZoneOffset.UTC));
            else if (args[i] instanceof LocalDate day) ps.setString(i + 1, day.toString());
            else ps.setObject(i + 1, args[i]);
        }
    }

    private static void applyResource(String path) throws Exception {
        try (InputStream in = RentIsolationIT.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8))) execute(sql);
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;
        private MutableClock(Instant now) { this.now = now; }
        void set(Instant at) { now = at; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) {
            return new Clock() {
                @Override public ZoneId getZone() { return zone; }
                @Override public Clock withZone(ZoneId next) { return MutableClock.this.withZone(next); }
                @Override public Instant instant() { return now; }
            };
        }
        @Override public Instant instant() { return now; }
    }
}

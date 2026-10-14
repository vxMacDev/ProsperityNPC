package com.prosperitysmp.npc.economy;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.config.EconomyLimits;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.economy.RentReservationRepository.Reserved;
import com.prosperitysmp.npc.economy.TransferJournal.Handle;
import com.prosperitysmp.npc.economy.TransferProof.Observation;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rent never pays the old rate on the day a delayed asking-rent change takes effect, on a real MariaDB with the
 * real rent reservation SQL and the real rent-change repository. Nothing here can move money.
 * Positive control: without the hold, a decrease effective today is reserved at the old rate and the daily
 * (property, day) uniqueness then prevents any same-day correction, which is the defect the hold closes.
 */
@Timeout(300)
class DueRentChangeRentHoldIT {
    private static final Instant NOW = Instant.parse("2026-09-07T12:00:00.001Z");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 7);
    private static final ZoneId ZONE = ZoneId.of("America/Chicago");
    private static final int RESET = 4;
    private static final UUID WORLD = new UUID(0, 3400);
    private static final UUID RESERVE = new UUID(0, 3402);
    private static DB mariaDb;
    private static Database database;
    private static List<String> tables;
    private MutableClock clock;

    @BeforeAll
    static void bootDatabase() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        try {
            mariaDb.start();
            String schema = "npc_rent_hold_" + UUID.randomUUID().toString().replace("-", "");
            mariaDb.createDB(schema);
            database = new Database();
            database.connect(new DatabaseSettings("localhost", cfg.getPort(), schema, "root", "", 6, 10_000L));
            var migrations = MigrationRunner.class.getDeclaredField("MIGRATIONS");
            migrations.setAccessible(true);
            for (Object migration : (List<?>) migrations.get(null)) {
                var resource = migration.getClass().getDeclaredMethod("resource");
                resource.setAccessible(true);
                applyResource("/" + resource.invoke(migration));
            }
            tables = rows("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() "
                    + "AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME");
            assertTrue(tables.containsAll(List.of("npc_rent_changes", "npc_rent_reservations", "npc_properties")));
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
        execute("INSERT INTO npc_rent_mutex(singleton) VALUES (1)");
        execute("INSERT INTO npc_housing_registry_lock(singleton_id) VALUES (1)");
        execute("INSERT INTO npc_chronicle_recovery_lock(singleton_id) VALUES (1)");
        clock = new MutableClock(NOW);
    }

    @Test
    void decreaseDueTodayButUnappliedIsHeldThenPaidAtTheNewRate() throws Exception {
        var limits = limits();
        UUID id = operating(new UUID(0, 3410));
        scheduleDueToday(id, 500);
        assertEquals(1000, property(id).askingRentCents(), "the rent-change step has not run: SQL still holds the old rate");

        // Staff rent before the rent-change step: the home is held, nothing is reserved, no event is written.
        assertTrue(rents().reserve(DAY, List.of(property(id)), limits, true).isEmpty());
        assertEquals(0, count("npc_rent_reservations WHERE property_id=? AND economy_day=?", id, DAY));
        assertEquals(0, count("npc_economy_events WHERE source='npc-property-rent'"));
        assertEquals(0, count("npc_rent_cohort"));
        // The same through the batch's worker step.
        assertTrue(UtilityPaymentRuntime.reserveRentBatch(rents(), false, true, DAY, List.of(property(id)), limits,
                warning -> fail("no warning expected: " + warning)).isEmpty());
        assertEquals(0, count("npc_rent_reservations"));

        // The rent-change step applies the decrease; rent then reserves today at the new rate.
        var due = new PropertyRepository(database).applyDueRentChanges(DAY, PropertyRepository.MAX_DUE_RENT_CHANGES);
        assertEquals(1, due.changes().size());
        assertTrue(due.changes().getFirst().applied());
        assertEquals(500, property(id).askingRentCents());
        var reserved = UtilityPaymentRuntime.reserveRentBatch(rents(), false, true, DAY, List.of(property(id)), limits,
                warning -> fail("no warning expected: " + warning));
        assertEquals(List.of(500L), reserved.stream().map(Reserved::cents).toList());
        assertEquals(1, count("npc_rent_reservations WHERE property_id=? AND economy_day=?", id, DAY));
    }

    @Test
    void withoutTheHoldTheOldRateIsReservedAndCannotBeCorrectedTheSameDay() throws Exception {
        // Positive control for the defect: the plain batch (flag off path) pays the old rate.
        var limits = limits();
        UUID id = operating(new UUID(0, 3411));
        scheduleDueToday(id, 500);
        var stale = rents().reserve(DAY, List.of(property(id)), limits);
        assertEquals(List.of(1000L), stale.stream().map(Reserved::cents).toList());
        new PropertyRepository(database).applyDueRentChanges(DAY, PropertyRepository.MAX_DUE_RENT_CHANGES);
        assertEquals(500, property(id).askingRentCents());
        assertTrue(rents().reserve(DAY, List.of(property(id)), limits, true).isEmpty(),
                "a reservation already exists for today: no correction is possible");
        assertEquals(List.of(1000L), rents().pending(DAY, List.of(property(id))).stream().map(Reserved::cents).toList());
    }

    @Test
    void holdTouchesOnlyChangesDueOnOrBeforeTheRentDay() throws Exception {
        var limits = limits();
        UUID future = operating(new UUID(0, 3412));
        UUID plain = operating(new UUID(0, 3413));
        var change = new PropertyRepository(database).changeAskingRent(property(future), 500, DAY, 1);
        assertEquals(DAY.plusDays(1), change.effectiveDay());
        // Not due until tomorrow: today's rent is the current rate, with or without the hold.
        var reserved = rents().reserve(DAY, List.of(property(future), property(plain)), limits, true);
        assertEquals(List.of(1000L, 1000L), reserved.stream().map(Reserved::cents).toList());
        assertEquals(2, count("npc_rent_reservations WHERE economy_day=?", DAY));
    }

    @Test
    void noPendingChangeReservesExactlyAsWithoutTheHold() throws Exception {
        var limits = limits();
        UUID held = operating(new UUID(0, 3414));
        var withHold = rents().reserve(DAY, List.of(property(held)), limits, true);
        UUID plain = operating(new UUID(0, 3415));
        var without = rents().reserve(DAY, List.of(property(plain)), limits, false);
        assertEquals(without.stream().map(Reserved::cents).toList(), withHold.stream().map(Reserved::cents).toList());
        assertEquals(List.of(1000L), withHold.stream().map(Reserved::cents).toList());
    }

    /** A delay-1 change requested yesterday, so it takes effect today; asking rent itself is untouched. */
    private void scheduleDueToday(UUID id, long cents) throws SQLException {
        var change = new PropertyRepository(database).changeAskingRent(property(id), cents, DAY.minusDays(1), 1);
        assertEquals(PropertyRepository.RentChange.Outcome.SCHEDULED, change.outcome());
        assertEquals(DAY, change.effectiveDay());
        assertEquals(1, count("npc_properties WHERE property_id=? AND pending_rent_effective_day=?", id, DAY));
    }

    private static Property property(UUID id) throws SQLException {
        return new PropertyRepository(database).findById(id).orElseThrow();
    }

    private static EconomyLimits limits() throws SQLException {
        try {
            var constructor = EconomyLimits.class.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            // Tenant cap above the asking rent, so the reserved amount is the asking rent itself.
            return (EconomyLimits) constructor.newInstance(15000L, 4500L, 1500L, 5000L, 300L, 3_500_000L, 5000L, 50000L, 0.9);
        } catch (ReflectiveOperationException failure) { throw new SQLException(failure); }
    }

    private RentReservationRepository rents() { return new RentReservationRepository(database, RESERVE, clock, ZONE, RESET, false); }
    private UtilityRepository utilities() { return new UtilityRepository(database, RESERVE, clock, ZONE, RESET); }
    private static TransferJournal journal() { return new TransferJournal(database, RESERVE); }

    /** Enrolled at $10.00 asking and reference, one standard tenant, today's utility verified paid. */
    private UUID operating(UUID owner) throws SQLException {
        UUID id = UUID.randomUUID();
        new PropertyRepository(database).insertEnrollment(new Property(id, WORLD, "hold-" + id, "test", "room",
                4, 1000, 1000, owner, 0, 80, true), 5000);
        UUID tenant = UUID.randomUUID();
        assertTrue(new ResidentRepository(database).register(ResidentCatalogue.draft(tenant, "Mara Vale")));
        new TenantRepository(database).insert(new Tenant(tenant, "Mara Vale", id, 0, "STANDARD", "COOK", 50));
        payUtilityFor(id, owner, DAY);
        return id;
    }

    private void payUtilityFor(UUID id, UUID owner, LocalDate day) throws SQLException {
        UtilityRepository.Receipt receipt = utilities().preparePayment(UUID.randomUUID(), id, owner, 0, day).receipt();
        Instant at = clock.instant().plusSeconds(1);
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
        assertEquals(UtilityRepository.State.PAID, utilities().reconcile(receipt.eventId()).orElseThrow().state());
    }

    private static long count(String tableAndWhere, Object... args) throws SQLException {
        return Long.parseLong(rows("SELECT COUNT(*) FROM " + tableAndWhere, args).getFirst());
    }

    private static List<String> rows(String sql, Object... args) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(30);
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> result = new ArrayList<>();
                while (rs.next()) result.add(String.valueOf(rs.getObject(1)));
                return result;
            }
        }
    }

    private static void execute(String sql, Object... args) throws SQLException {
        try (Connection c = database.getConnection()) { execute(c, sql, args); }
    }

    private static void execute(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(60);
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
        try (InputStream in = DueRentChangeRentHoldIT.class.getResourceAsStream(path)) {
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

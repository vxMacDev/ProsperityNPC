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
import com.prosperitysmp.npc.property.AutoCareRuntime;
import com.prosperitysmp.npc.property.OwnerAway;
import com.prosperitysmp.npc.property.OwnerAwayDecisionRepository;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyRepository;
import com.prosperitysmp.npc.property.Tenant;
import com.prosperitysmp.npc.property.TenantMoodRepository;
import com.prosperitysmp.npc.property.TenantRepository;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Vacation protection on a real MariaDB: the production owner-away decision feeds the production phase selections,
 * then the real utility-bill, rent-reservation and mood SQL. Vault is replaced by fixture observations.
 */
@Timeout(90)
class VacationIT {
    private static final Instant NOW = Instant.parse("2026-09-07T12:00:00.001Z");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 7);
    private static final ZoneId ZONE = ZoneId.of("America/Chicago");
    private static final int RESET = 4;
    private static final long MS_DAY = 86_400_000L;
    private static final UUID WORLD = new UUID(0, 3100);
    private static final UUID RESERVE = new UUID(0, 3102);
    private static DB mariaDb;
    private static Database database;

    @BeforeAll
    static void bootDatabase() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        try {
            mariaDb.start();
            String schema = "npc_vacation_" + UUID.randomUUID().toString().replace("-", "");
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
            execute("INSERT IGNORE INTO npc_rent_mutex(singleton) VALUES (1)");
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

    @Test
    void awayHomeGetsNoBillRentOrMoodChangeAndResumesTheDayAfterTheOwnerIsSeen() throws Exception {
        var clock = new MutableClock(NOW);
        UUID awayOwner = new UUID(0, 3110), homeOwner = new UUID(0, 3111);
        UUID awayHome = enroll(awayOwner), presentHome = enroll(homeOwner);
        UUID awayTenant = occupy(awayHome), presentTenant = occupy(presentHome);

        // Production decision: away owner last seen 10 days ago, the other online. vacation-days = 7.
        var today = new AtomicReference<>(DAY);
        Map<UUID, OwnerAway.Seen> seen = new HashMap<>();
        seen.put(awayOwner, new OwnerAway.Seen(false, NOW.toEpochMilli() - 10 * MS_DAY, 0));
        seen.put(homeOwner, new OwnerAway.Seen(true, NOW.toEpochMilli(), NOW.toEpochMilli()));
        var decisions = new OwnerAway(() -> 7, today::get, () -> clock.instant().toEpochMilli(), seen::get);

        // ---- Day 1: one owner away.
        var away = decisions.awayAmong(enrolled(awayHome, presentHome));
        assertEquals(Set.of(awayHome), away);
        assertEquals(1, cycle(clock, DAY, away, awayHome, presentHome), "only the present owner's rent is paid");
        assertEquals(0, count("npc_utility_bills WHERE property_id=?", awayHome), "no utility bill accrues while away");
        assertEquals(0, count("npc_rent_reservations WHERE property_id=?", awayHome), "no rent while away");
        assertEquals(0, count("npc_economy_events WHERE property_id=?", awayHome), "no money event of any kind while away");
        assertEquals(1, count("npc_rent_reservations WHERE property_id=?", presentHome));
        assertEquals(50, satisfaction(awayTenant), "away home's resident mood is untouched");
        assertNull(scalar("SELECT mood_day FROM npc_tenants WHERE tenant_id=?", awayTenant));
        assertEquals(DAY.toString(), String.valueOf(scalar("SELECT mood_day FROM npc_tenants WHERE tenant_id=?", presentTenant)));

        // The owner logs in later the same day: today's decision holds, so a replayed cycle changes nothing.
        seen.put(awayOwner, new OwnerAway.Seen(true, clock.instant().toEpochMilli(), clock.instant().toEpochMilli()));
        assertEquals(Set.of(awayHome), decisions.awayAmong(enrolled(awayHome, presentHome)));
        assertEquals(0, cycle(clock, DAY, decisions.awayAmong(enrolled(awayHome, presentHome)), awayHome, presentHome));
        assertEquals(0, count("npc_rent_reservations WHERE property_id=?", awayHome));
        assertEquals(50, satisfaction(awayTenant));

        // ---- Day 2: seen yesterday, so the home resumes automatically with no catch-up.
        clock.set(NOW.plusMillis(MS_DAY));
        today.set(DAY.plusDays(1));
        seen.put(awayOwner, new OwnerAway.Seen(false, NOW.toEpochMilli() + 3_600_000L, 0));
        away = decisions.awayAmong(enrolled(awayHome, presentHome));
        assertEquals(Set.of(), away);
        assertEquals(2, cycle(clock, DAY.plusDays(1), away, awayHome, presentHome));
        assertEquals(1, count("npc_utility_bills WHERE property_id=?", awayHome), "one bill for the resumed day only");
        assertEquals(1, count("npc_rent_reservations WHERE property_id=?", awayHome), "one rent for the resumed day only");
        assertEquals(0, count("npc_rent_reservations WHERE property_id=? AND economy_day=?", awayHome, DAY));
        int resumed = satisfaction(awayTenant);
        assertTrue(Math.abs(resumed - 50) <= 5, "one ordinary mood step, no catch-up: " + resumed);
        assertEquals(DAY.plusDays(1).toString(), String.valueOf(scalar("SELECT mood_day FROM npc_tenants WHERE tenant_id=?", awayTenant)));
    }

    @Test
    void flagOffChangesNothing() throws Exception {
        var decisions = new OwnerAway(() -> 0, () -> DAY, () -> NOW.toEpochMilli(),
                owner -> new OwnerAway.Seen(false, 1, 0));
        var p = new Property(UUID.randomUUID(), WORLD, "x", "test", "room", 4, 1000, 1000, new UUID(0, 1), 0, 80, true);
        assertEquals(Set.of(), decisions.awayAmong(List.of(p)));
        assertEquals(List.of(p), UtilityPaymentRuntime.rentCandidates(List.of(p), Set.of()));
    }

    @Test
    void savedAwayDecisionSurvivesASameDayRestartSoNoRentOrMoodRunsOnTheFrozenDay() throws Exception {
        var clock = new MutableClock(NOW);
        UUID awayOwner = new UUID(0, 3120), homeOwner = new UUID(0, 3121);
        UUID awayHome = enroll(awayOwner), presentHome = enroll(homeOwner);
        UUID awayTenant = occupy(awayHome);
        occupy(presentHome);
        Map<UUID, OwnerAway.Seen> seen = new HashMap<>();
        seen.put(awayOwner, new OwnerAway.Seen(false, NOW.toEpochMilli() - 10 * MS_DAY, 0));
        seen.put(homeOwner, new OwnerAway.Seen(true, NOW.toEpochMilli(), NOW.toEpochMilli()));

        // Process before the restart: decides, saves both rows, then runs the day with the away home skipped.
        var before = durable(seen);
        assertTrue(save(before, DAY, enrolled(awayHome, presentHome)));
        var away = before.held(enrolled(awayHome, presentHome)).orElseThrow();
        assertEquals(Set.of(awayHome), away);
        assertEquals(1, cycle(clock, DAY, away, awayHome, presentHome));
        assertEquals(1L, count("npc_owner_away_decisions WHERE economy_day=? AND property_id=? AND owner_uuid=? AND away=TRUE",
                DAY, awayHome, awayOwner));
        assertEquals(1L, count("npc_owner_away_decisions WHERE economy_day=? AND property_id=? AND owner_uuid=? AND away=FALSE",
                DAY, presentHome, homeOwner), "present decisions are saved too");

        // The owner logs in, then the server restarts the same economy day. The new process starts empty.
        seen.put(awayOwner, new OwnerAway.Seen(true, clock.instant().toEpochMilli(), clock.instant().toEpochMilli()));
        // In memory alone, the returning owner counts as present, so nobody is away. The saved decisions keep them away.
        assertEquals(Set.of(), new OwnerAway(() -> 7, () -> DAY, () -> NOW.toEpochMilli(), seen::get)
                .awayAmong(enrolled(awayHome, presentHome)));
        var after = durable(seen);
        assertTrue(after.held(enrolled(awayHome, presentHome)).isEmpty(), "before the save no phase may run");
        assertTrue(save(after, DAY, enrolled(awayHome, presentHome)));
        var stillAway = after.held(enrolled(awayHome, presentHome)).orElseThrow();
        assertEquals(Set.of(awayHome), stillAway, "the saved away decision holds across the restart");
        assertTrue(after.isAway(enrolled(awayHome).getFirst()));
        assertEquals(0, cycle(clock, DAY, stillAway, awayHome, presentHome), "a replayed cycle after restart pays nothing");
        assertEquals(0, count("npc_rent_reservations WHERE property_id=?", awayHome), "no rent on the frozen day");
        assertEquals(0, count("npc_utility_bills WHERE property_id=?", awayHome), "no utility bill on the frozen day");
        assertEquals(0, count("npc_economy_events WHERE property_id=?", awayHome), "no money event on the frozen day");
        assertEquals(50, satisfaction(awayTenant));
        assertNull(scalar("SELECT mood_day FROM npc_tenants WHERE tenant_id=?", awayTenant));
        assertEquals(1L, count("npc_owner_away_decisions WHERE economy_day=? AND property_id=?", DAY, awayHome),
                "the second process added no row of its own");

        // Next economy day: the returning owner resumes.
        var nextDay = new AtomicReference<>(DAY.plusDays(1));
        var tomorrow = new OwnerAway(() -> 7, nextDay::get, () -> NOW.toEpochMilli() + MS_DAY, seen::get);
        tomorrow.requireDurable();
        assertTrue(save(tomorrow, DAY.plusDays(1), enrolled(awayHome, presentHome)));
        assertEquals(Set.of(), tomorrow.held(enrolled(awayHome, presentHome)).orElseThrow());
    }

    @Test
    void flagOffWritesNoDecisionRows() throws Exception {
        UUID owner = new UUID(0, 3130);
        UUID home = enroll(owner);
        Map<UUID, OwnerAway.Seen> seen = Map.of(owner, new OwnerAway.Seen(false, NOW.toEpochMilli() - 10 * MS_DAY, 0));
        var off = new OwnerAway(() -> 0, () -> DAY, () -> NOW.toEpochMilli(), seen::get);
        off.requireDurable();
        assertTrue(off.settled());
        assertEquals(List.of(), off.unsaved(enrolled(home)));
        assertEquals(Optional.of(Set.of()), off.held(enrolled(home)));
        assertEquals(0L, count("npc_owner_away_decisions WHERE property_id=?", home));
        // With the flag on, the same home is saved, so the empty result above comes from the flag.
        assertTrue(save(durable(seen), DAY, enrolled(home)));
        assertEquals(1L, count("npc_owner_away_decisions WHERE property_id=?", home));
    }

    @Test
    void repositoryKeepsTheFirstDecisionAndPrunesOldDaysInBoundedBatches() throws Exception {
        var repo = new OwnerAwayDecisionRepository(database);
        UUID property = UUID.randomUUID(), owner = UUID.randomUUID(), buyer = UUID.randomUUID();
        LocalDate day = LocalDate.of(2026, 9, 20);
        var away = new OwnerAway.Choice(property, owner, true);
        assertEquals(List.of(away), repo.recordFirst(day, List.of(away)));
        assertEquals(List.of(away), repo.recordFirst(day, List.of(new OwnerAway.Choice(property, owner, false))),
                "a later present decision never flips the stored away row");
        assertEquals(1L, count("npc_owner_away_decisions WHERE economy_day=? AND property_id=?", day, property));
        assertEquals(List.of(new OwnerAway.Choice(property, buyer, false)),
                repo.recordFirst(day, List.of(new OwnerAway.Choice(property, buyer, false))), "a new owner is decided on their own");
        assertEquals(List.of(new OwnerAway.Choice(property, owner, false)),
                repo.recordFirst(day.plusDays(1), List.of(new OwnerAway.Choice(property, owner, false))), "the next day starts fresh");
        assertEquals(List.of(), repo.recordFirst(day, List.of()));

        // More than one insert chunk round-trips exactly.
        LocalDate later = LocalDate.of(2026, 9, 25);
        var many = new java.util.ArrayList<OwnerAway.Choice>();
        for (int i = 0; i < 450; i++) many.add(new OwnerAway.Choice(UUID.randomUUID(), owner, i % 3 == 0));
        assertEquals(many, repo.recordFirst(later, many));
        assertEquals(450L, count("npc_owner_away_decisions WHERE economy_day=?", later));
        assertThrows(IllegalArgumentException.class, () -> repo.recordFirst(later,
                java.util.Collections.nCopies(OwnerAwayDecisionRepository.MAX_ROWS + 1, away)));

        // Rows exactly RETAIN_DAYS old are kept; older ones go, at most `limit` per call.
        LocalDate keepToday = day.plusDays(OwnerAwayDecisionRepository.RETAIN_DAYS);
        while (repo.prune(keepToday, OwnerAwayDecisionRepository.MAX_PRUNE) > 0) { /* drain anything older */ }
        assertEquals(2L, count("npc_owner_away_decisions WHERE economy_day=?", day), "exactly RETAIN_DAYS old is kept");
        LocalDate cutoffToday = day.plusDays(OwnerAwayDecisionRepository.RETAIN_DAYS + 1);
        assertEquals(1, repo.prune(cutoffToday, 1), "bounded");
        while (repo.prune(cutoffToday, OwnerAwayDecisionRepository.MAX_PRUNE) > 0) { /* drain */ }
        assertEquals(0L, count("npc_owner_away_decisions WHERE economy_day<=?", day));
        assertEquals(1L, count("npc_owner_away_decisions WHERE economy_day=?", day.plusDays(1)));
        assertEquals(450L, count("npc_owner_away_decisions WHERE economy_day=?", later));
        assertThrows(IllegalArgumentException.class, () -> repo.prune(cutoffToday, OwnerAwayDecisionRepository.MAX_PRUNE + 1));
    }

    @Test
    void aSaveThatCannotBeConfirmedThrowsSoTheCycleNeverStarts() throws Exception {
        String schema = "npc_vacation_empty_" + UUID.randomUUID().toString().replace("-", "");
        mariaDb.createDB(schema);
        var empty = new Database();
        empty.connect(new DatabaseSettings("localhost", mariaDb.getConfiguration().getPort(), schema, "root", "", 2, 10_000L));
        try {
            var decisions = durable(Map.of(new UUID(0, 3140), new OwnerAway.Seen(false, NOW.toEpochMilli() - 10 * MS_DAY, 0)));
            var home = new Property(UUID.randomUUID(), WORLD, "x", "test", "room", 4, 1000, 1000, new UUID(0, 3140), 0, 80, true);
            var choices = decisions.unsaved(List.of(home));
            assertEquals(1, choices.size());
            assertThrows(SQLException.class, () -> new OwnerAwayDecisionRepository(empty).recordFirst(DAY, choices));
            // The plugin runs the failure path and never confirms: every phase and recurring charge keeps refusing.
            assertTrue(decisions.held(List.of(home)).isEmpty());
            assertTrue(decisions.holds(home));
        } finally {
            empty.close();
        }
    }

    private static OwnerAway durable(Map<UUID, OwnerAway.Seen> seen) {
        var decisions = new OwnerAway(() -> 7, () -> DAY, () -> NOW.toEpochMilli(), seen::get);
        decisions.requireDurable();
        return decisions;
    }

    /** One save round exactly as the plugin runs it: decide on main, save on the worker, adopt what is stored. */
    private static boolean save(OwnerAway decisions, LocalDate day, List<Property> enrolled) throws SQLException {
        return decisions.confirm(day, new OwnerAwayDecisionRepository(database).recordFirst(day, decisions.unsaved(enrolled)));
    }

    /**
     * Mirrors one daily cycle's money and mood steps for the given away set: moods, then the recurring utility
     * bill (auto-care selection), then rent (rent selection, owner plan, reservation, one journaled transfer each).
     */
    private int cycle(MutableClock clock, LocalDate day, Set<UUID> away, UUID... ids) throws Exception {
        new TenantMoodRepository(database).updateDay(day, TenantMoodRepository.MAX_BATCH, away);
        var utilities = new UtilityRepository(database, RESERVE, clock, ZONE, RESET);
        for (Property p : AutoCareRuntime.careTargets(enrolled(ids), away)) {
            if (!utilities.history(p.propertyId(), day).isEmpty()) continue;
            Receipt bill = utilities.preparePayment(UUID.randomUUID(), p.propertyId(), p.ownerUuid(), 0, day).receipt();
            payUtility(clock, bill, clock.instant().plusSeconds(1));
        }
        var candidates = UtilityPaymentRuntime.rentCandidates(enrolled(ids), away);
        var plan = UtilityPaymentRuntime.planRent(candidates, RESERVE, false, p -> Optional.of("owner:" + p.ownerUuid()));
        var rents = new RentReservationRepository(database, RESERVE, clock, ZONE, RESET, false);
        rents.reserve(day, plan.eligible(), limits());
        int paid = 0;
        for (var row : rents.pending(day, plan.eligible())) {
            assertFalse(away.contains(row.property().propertyId()), "an away home must never reach dispatch");
            Handle handle = new Handle(UUID.randomUUID(), row.eventId(), UUID.randomUUID(), "b".repeat(64));
            if (!journal().beginDebit(handle).newlyStarted()) continue;
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

    private static List<Property> enrolled(UUID... ids) throws SQLException {
        var repo = new PropertyRepository(database);
        var out = new java.util.ArrayList<Property>();
        for (UUID id : ids) out.add(repo.findById(id).orElseThrow());
        return out;
    }

    private static EconomyLimits limits() throws SQLException {
        try {
            var constructor = EconomyLimits.class.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            return (EconomyLimits) constructor.newInstance(15000L, 4500L, 1500L, 600L, 300L, 3500000L, 5000L, 50000L, 0.9);
        } catch (ReflectiveOperationException failure) { throw new SQLException(failure); }
    }

    private static TransferJournal journal() { return new TransferJournal(database, RESERVE); }

    private static UUID enroll(UUID owner) throws SQLException {
        UUID id = UUID.randomUUID();
        new PropertyRepository(database).insertEnrollment(new Property(id, WORLD, "vac-" + id, "test", "room",
                4, 1000, 1000, owner, 0, 80, true), 5000);
        return id;
    }

    private static UUID occupy(UUID property) throws SQLException {
        UUID tenant = UUID.randomUUID();
        assertTrue(new ResidentRepository(database).register(ResidentCatalogue.draft(tenant, "Mara Vale")));
        new TenantRepository(database).insert(new Tenant(tenant, "Mara Vale", property, 0, "STANDARD", "COOK", 50));
        return tenant;
    }

    private static void payUtility(MutableClock clock, Receipt receipt, Instant at) throws SQLException {
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
        assertEquals(UtilityRepository.State.PAID, new UtilityRepository(database, RESERVE, clock, ZONE, RESET)
                .reconcile(receipt.eventId()).orElseThrow().state());
    }

    private static int satisfaction(UUID tenant) throws SQLException {
        return ((Number) scalar("SELECT satisfaction FROM npc_tenants WHERE tenant_id=?", tenant)).intValue();
    }

    private static long count(String tableAndWhere, Object... args) throws SQLException {
        return ((Number) scalar("SELECT COUNT(*) FROM " + tableAndWhere, args)).longValue();
    }

    private static Object scalar(String sql, Object... args) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(5);
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) { assertTrue(rs.next()); return rs.getObject(1); }
        }
    }

    private static void execute(String sql, Object... args) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
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
        try (InputStream in = VacationIT.class.getResourceAsStream(path)) {
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

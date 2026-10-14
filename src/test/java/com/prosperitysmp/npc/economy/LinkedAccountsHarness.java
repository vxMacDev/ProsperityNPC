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
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real MariaDB for the linked-accounts ITs: every registered migration, the real rent reservation SQL and the
 * real transfer journal. Vault is replaced by fixture observations, as in AppreciationIT; nothing moves money.
 */
final class LinkedAccountsHarness implements AutoCloseable {
    static final Instant NOW = Instant.parse("2026-09-07T12:00:00.001Z");
    static final LocalDate DAY = LocalDate.of(2026, 9, 7);
    static final ZoneId ZONE = ZoneId.of("America/Chicago");
    static final int RESET = 4;
    static final UUID WORLD = new UUID(0x51L << 56, 1);
    static final UUID RESERVE = UUID.fromString("5eeeeeee-0000-4000-8000-000000000001");
    /** A Java account and a Floodgate (Bedrock) account. The Bedrock UUID is the smaller, so it is the group key. */
    static final UUID JAVA = UUID.fromString("7a1b2c3d-0000-4000-8000-0000000000a1");
    static final UUID BEDROCK = new UUID(0, 0x0009_0001_00b2L);
    static final UUID STRANGER = UUID.fromString("6c000000-0000-4000-8000-0000000000c3");

    private final DB mariaDb;
    final Database database;
    private final List<String> tables;
    final MutableClock clock = new MutableClock(NOW);

    private LinkedAccountsHarness(DB mariaDb, Database database, List<String> tables) {
        this.mariaDb = mariaDb; this.database = database; this.tables = tables;
    }

    static LinkedAccountsHarness boot(String name) throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        DB db = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        Database database = null;
        try {
            db.start();
            String schema = name + "_" + UUID.randomUUID().toString().replace("-", "");
            db.createDB(schema);
            database = new Database();
            database.connect(new DatabaseSettings("localhost", cfg.getPort(), schema, "root", "", 6, 10_000L));
            var migrations = MigrationRunner.class.getDeclaredField("MIGRATIONS");
            migrations.setAccessible(true);
            for (Object migration : (List<?>) migrations.get(null)) {
                var resource = migration.getClass().getDeclaredMethod("resource");
                resource.setAccessible(true);
                String path = "/" + resource.invoke(migration);
                try (InputStream in = LinkedAccountsHarness.class.getResourceAsStream(path)) {
                    assertNotNull(in, path);
                    for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8)))
                        try (Connection c = database.getConnection()) { execute(c, sql); }
                }
            }
            var harness = new LinkedAccountsHarness(db, database, List.of());
            var tables = harness.rows("SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() "
                    + "AND TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME").stream().map(List::getFirst).toList();
            assertTrue(tables.containsAll(List.of("npc_account_links", "npc_linked_account_days", "npc_rent_reservations")));
            return new LinkedAccountsHarness(db, database, tables);
        } catch (Exception | AssertionError failure) {
            try { if (database != null) database.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            try { db.stop(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @Override public void close() throws Exception {
        try { database.close(); } finally { mariaDb.stop(); }
    }

    /** Empties every table and puts back the singleton rows, clock at NOW. */
    void reset() throws SQLException {
        try (Connection c = database.getConnection()) {
            execute(c, "SET SESSION foreign_key_checks=0");
            try { for (String table : tables) execute(c, "DELETE FROM " + table); }
            finally { execute(c, "SET SESSION foreign_key_checks=1"); }
        }
        execute("INSERT INTO npc_rent_mutex(singleton) VALUES (1)");
        execute("INSERT INTO npc_housing_registry_lock(singleton_id) VALUES (1)");
        execute("INSERT INTO npc_chronicle_recovery_lock(singleton_id) VALUES (1)");
        clock.set(NOW);
    }

    AccountLinkRepository links() { return new AccountLinkRepository(database); }
    /** The economy clock the runtime builds, over this harness's clock. */
    com.prosperitysmp.npc.clock.EconomyClock days() { return new com.prosperitysmp.npc.clock.EconomyClock(clock, ZONE, RESET); }

    /** With links: the new constructor. linked is linked-accounts.enabled for every batch. */
    RentReservationRepository rents(BooleanSupplier linked) {
        return new RentReservationRepository(database, RESERVE, clock, ZONE, RESET, false, links(), linked);
    }
    /** The constructor every pre-linked-accounts caller uses: no link table is read. */
    RentReservationRepository legacyRents() { return new RentReservationRepository(database, RESERVE, clock, ZONE, RESET, false); }
    UtilityRepository utilities() { return new UtilityRepository(database, RESERVE, clock, ZONE, RESET); }
    TransferJournal journal() { return new TransferJournal(database, RESERVE); }

    /** Saves the Floodgate pair the way a linked login does. */
    AccountLinkRepository.Observed link(UUID java, UUID bedrock, LocalDate day) throws SQLException {
        return links().observe(java, bedrock, AccountLinkRepository.Source.FLOODGATE_LOGIN, clock.instant(), day, RESERVE);
    }

    static EconomyLimits limits(long server, long owner, long property, long tenant) throws SQLException {
        try {
            var constructor = EconomyLimits.class.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            // Appreciation $3, floor $35,000, seed $50, pool $500, ratio 0.9 as shipped.
            return (EconomyLimits) constructor.newInstance(server, owner, property, tenant, 300L, 3_500_000L, 5000L, 50000L, 0.9);
        } catch (ReflectiveOperationException failure) { throw new SQLException(failure); }
    }

    /** Enrolled, one tenant, asking and reference rent both rentCents, today's utility verified paid. */
    UUID operating(UUID owner, int rentCents, String region) throws SQLException {
        UUID id = UUID.nameUUIDFromBytes(("linked-" + region).getBytes(StandardCharsets.UTF_8));
        new PropertyRepository(database).insertEnrollment(new Property(id, WORLD, region, "test", "room",
                4, rentCents, rentCents, owner, 0, 80, true), 5000);
        UUID tenant = UUID.nameUUIDFromBytes(("tenant-" + region).getBytes(StandardCharsets.UTF_8));
        assertTrue(new ResidentRepository(database).register(ResidentCatalogue.draft(tenant, "Mara Vale")));
        new TenantRepository(database).insert(new Tenant(tenant, "Mara Vale", id, 0, "STANDARD", "COOK", 50));
        payUtility(owner, id, DAY);
        return id;
    }

    void payUtility(UUID owner, UUID property, LocalDate day) throws SQLException {
        UtilityRepository.Receipt receipt = utilities().preparePayment(UUID.randomUUID(), property, owner, 0, day).receipt();
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

    /** One complete reserve-to-owner journaled transfer, as the runtime records it. */
    boolean pay(Reserved row) throws SQLException {
        Handle handle = new Handle(UUID.randomUUID(), row.eventId(), UUID.randomUUID(), "b".repeat(64));
        var start = journal().beginDebit(handle);
        if (!start.newlyStarted()) return false;
        assertEquals(RESERVE, start.receipt().payer());
        assertEquals(row.property().ownerUuid(), start.receipt().payee());
        double dollars = row.cents() / 100.0;
        assertEquals(TransferJournal.State.CREDIT_READY, journal().recordDebit(handle,
                new Observation(true, true, dollars, 100000.0, 100000.0 - dollars, false)).state());
        assertTrue(journal().beginCredit(handle).newlyStarted());
        assertEquals(TransferJournal.State.PAID, journal().recordCredit(handle,
                new Observation(true, true, dollars, 50.0, 50.0 + dollars, false)).state());
        return true;
    }

    /**
     * Waits until some transaction is blocked on the npc_rent_mutex row lock. Fails if work finished first or
     * nothing waited within 30 s, so a test built on this cannot pass without the interleaving it describes.
     */
    void awaitMutexWait(java.util.concurrent.Future<?> work) throws Exception {
        awaitLockWait(work, "%FROM npc_rent_mutex WHERE singleton=1 FOR UPDATE%", "the rent mutex");
    }

    /** Same wait, for another session blocked on a statement whose text matches like (a SQL LIKE pattern). */
    void awaitLockWait(java.util.concurrent.Future<?> work, String like, String what) throws Exception {
        for (int i = 0; i < 600; i++) {
            // The holder's own locking SELECT has finished, so another session still running it is waiting for the lock.
            if (count("information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID() AND INFO LIKE ?", like) > 0) return;
            if (work.isDone()) {
                Object outcome;
                try { outcome = work.get(); } catch (java.util.concurrent.ExecutionException failure) { outcome = failure.getCause(); }
                fail("the work finished without waiting for " + what + ": " + outcome);
            }
            Thread.sleep(50);
        }
        fail("nothing waited for " + what + ": " + rows("SELECT ID,STATE,INFO FROM information_schema.PROCESSLIST"));
    }

    Property property(UUID id) throws SQLException { return new PropertyRepository(database).findById(id).orElseThrow(); }

    List<Property> properties(List<UUID> ids) throws SQLException {
        var out = new ArrayList<Property>();
        for (UUID id : ids) out.add(property(id));
        return out;
    }

    long count(String tableAndWhere, Object... args) throws SQLException {
        return Long.parseLong(rows("SELECT COUNT(*) FROM " + tableAndWhere, args).getFirst().getFirst());
    }

    List<List<String>> rows(String sql, Object... args) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(15);
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

    void execute(String sql, Object... args) throws SQLException {
        try (Connection c = database.getConnection()) { execute(c, sql, args); }
    }

    static void execute(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setQueryTimeout(30);
            bind(ps, args);
            ps.execute();
        }
    }

    static String hex(UUID id) { return HexFormat.of().formatHex(Uuids.toBytes(id)); }

    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof UUID id) ps.setBytes(i + 1, Uuids.toBytes(id));
            else if (args[i] instanceof Instant at) ps.setObject(i + 1, LocalDateTime.ofInstant(at, ZoneOffset.UTC));
            else if (args[i] instanceof LocalDate day) ps.setString(i + 1, day.toString());
            else ps.setObject(i + 1, args[i]);
        }
    }

    static final class MutableClock extends Clock {
        private volatile Instant now;
        MutableClock(Instant now) { this.now = now; }
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

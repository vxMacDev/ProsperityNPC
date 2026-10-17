package com.prosperitysmp.npc.economy;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.arm.ArmAdapter;
import com.prosperitysmp.npc.arm.OwnershipFence;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.property.OwnershipReconcilePump;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyRepository;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import org.bukkit.World;
import org.junit.jupiter.api.*;
import static com.prosperitysmp.npc.economy.TransferJournal.*;
import static com.prosperitysmp.npc.economy.TransferProof.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The ownership reconcile pump must never replace the cached record of a home whose ownership lease is held. Real MariaDB behind the pump's writer (PropertyRepository.reconcileOwner, with the findById fallback
 * PropertyService uses), a real OwnershipFence, the real OwnershipReconcilePump with PropertyService's cache rebase,
 * the real JournaledTransfer with a fake journal, and a CREDIT authorization doing what UtilityPaymentRuntime.validate
 * does: the cached Property must still equal the one the payment was admitted against. The SQL row differs from the
 * cache before the payment starts (the daily incident batch lowered condition_score in SQL only).
 */
@Timeout(120)
class ReconcilePumpLeaseIT {
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-00000000b201");
    private static final UUID RESERVE = new UUID(0, 3), RUN = new UUID(0, 2);
    private static DB maria;
    private static Database db;
    private PropertyRepository repo;
    private UUID owner;

    @BeforeAll static void boot() throws Exception {
        var cfg = DBConfigurationBuilder.newBuilder(); cfg.setPort(0);
        cfg.setBaseDir(java.nio.file.Files.createTempDirectory("npc-pump-lease-maria-").toFile());
        maria = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        try {
            maria.start();
            String schema = "npc_pump_lease_" + UUID.randomUUID().toString().replace("-", "");
            maria.createDB(schema);
            db = new Database(); db.connect(new DatabaseSettings("localhost", cfg.getPort(), schema, "root", "", 6, 10_000));
            var migrations = MigrationRunner.class.getDeclaredField("MIGRATIONS");
            migrations.setAccessible(true);
            for (Object migration : (List<?>) migrations.get(null)) {
                var resource = migration.getClass().getDeclaredMethod("resource");
                resource.setAccessible(true);
                applyResource("/" + resource.invoke(migration));
            }
        } catch (Exception | AssertionError failure) {
            try { stop(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    @AfterAll static void stop() throws Exception {
        try { if (db != null) db.close(); }
        finally { if (maria != null) { DB old = maria; maria = null; old.stop(); } }
    }
    @BeforeEach void reset() throws SQLException {
        try (Connection c = db.getConnection()) {
            exec(c, "SET FOREIGN_KEY_CHECKS=0");
            for (String t : List.of("npc_rent_change_notices", "npc_rent_changes", "npc_tenants", "npc_properties"))
                exec(c, "DELETE FROM " + t);
            exec(c, "SET FOREIGN_KEY_CHECKS=1");
        }
        repo = new PropertyRepository(db);
        owner = UUID.randomUUID();
    }

    // ---- fixtures

    private Property property() throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = db.getConnection()) {
            exec(c, "INSERT INTO npc_properties(property_id,world_uuid,region_id,market_id,class_key,capacity,reference_rent_cents,"
                    + "asking_rent_cents,owner_uuid,ownership_epoch,condition_score,enabled) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, WORLD, "p-" + id, "test", "small", 3, 1000, 1000, owner, 0, 80, true);
        }
        return repo.findById(id).orElseThrow();
    }
    /** The daily incident batch: condition_score changes in SQL only, the cache is not told. */
    private void incident(Property p, int condition) throws SQLException {
        try (Connection c = db.getConnection()) {
            exec(c, "UPDATE npc_properties SET condition_score=? WHERE property_id=?", condition, p.propertyId());
        }
    }
    private ArmAdapter.OwnerRead ownerRead(Property p) {
        Object identity = new Object();
        World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, m, a) -> { throw new AssertionError("No Bukkit access expected"); });
        return new ArmAdapter.OwnerRead(p.worldUuid(), p.regionId(), p.ownerUuid(), identity, identity, identity, identity, identity, world);
    }

    /** The reconcile pump as PropertyService wires it, against the shared cache; ARM reports the cached owner. */
    private final class Pump implements OwnershipReconcilePump.Host, Executor {
        final Map<UUID, Property> cache;
        final OwnershipFence fence;
        final List<OwnershipReconcilePump.Result> results = new ArrayList<>();
        Runnable hop;
        int writes;
        final OwnershipReconcilePump pump;
        Pump(Map<UUID, Property> cache, OwnershipFence fence) {
            this.cache = cache; this.fence = fence;
            pump = new OwnershipReconcilePump(this, (expected, observed) -> {
                writes++;
                try { return new OwnershipReconcilePump.Write(repo.reconcileOwner(expected, observed), true); }
                catch (SQLException | RuntimeException conflict) {
                    return new OwnershipReconcilePump.Write(repo.findById(expected.propertyId()).orElse(null), false);
                }
            }, this);
        }
        public Property current(UUID id) { return cache.get(id); }
        public OwnershipReconcilePump.Observation observe(Property p) {
            return new OwnershipReconcilePump.Observation(OwnershipReconcilePump.Presence.PRESENT, p.ownerUuid());
        }
        public void uncertain(UUID id) { }
        public void rebase(Property expected, Property authoritative, boolean verified) {
            // PropertyService's Host.rebase: identity compare-and-set on the cached record.
            if (!expected.propertyId().equals(authoritative.propertyId())) return;
            if (!authoritative.enabled()) cache.remove(expected.propertyId(), expected);
            else cache.replace(expected.propertyId(), expected, authoritative);
        }
        public OwnershipFence fence() { return fence; }
        public void execute(Runnable task) { assertNull(hop, "one SQL hop at a time"); hop = task; }
        void request() { assertEquals(OwnershipReconcilePump.Acceptance.ACCEPTED, pump.request(cache.values(), results::add)); }
        /** The worker finishes its SQL hop, then main processes the completion. */
        void finishHop() { Runnable r = hop; hop = null; assertNotNull(r, "a SQL hop was dispatched"); r.run(); pump.tick(); }
        /** Ticks until a SQL hop is dispatched; -1 when none within the limit. */
        int ticksUntilHop(int limit) {
            for (int i = 1; i <= limit; i++) { pump.tick(); if (hop != null) return i; }
            return -1;
        }
    }

    /** One utility-style payment: owner pays the reserve, CREDIT authorized against the admitted Property. */
    private static final class Payment implements JournaledTransfer.Journal {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        String thread = "main";
        final Executor main = r -> queue.add(() -> { thread = "main"; r.run(); });
        final Executor worker = r -> queue.add(() -> { thread = "worker"; r.run(); });
        final List<String> calls = new ArrayList<>();
        State journal;
        Runnable onDebitAck = () -> { };

        void drain() { while (!queue.isEmpty()) queue.remove().run(); }
        private static Handle handle() { return new Handle(new UUID(9, 1), 1, RUN, "a".repeat(64)); }
        private Receipt receipt(Handle h, UUID payer, State s) {
            return new Receipt(h, "b".repeat(64), payer, RESERVE, 250, s, null, s == State.CREDIT_INTENT || s == State.PAID);
        }
        UUID payer;
        public Start beginDebit(Handle h) { journal = State.DEBIT_INTENT; return new Start(true, receipt(h, payer, journal)); }
        public Receipt recordDebit(Handle h, Observation o) {
            journal = State.CREDIT_READY; calls.add("debit-acknowledged"); onDebitAck.run(); return receipt(h, payer, journal);
        }
        public Start beginCredit(Handle h) { journal = State.CREDIT_INTENT; return new Start(true, receipt(h, payer, journal)); }
        Observation credit;
        public Receipt recordCredit(Handle h, Observation o) {
            // As the real journal: a credit refused before its money call leaves the transfer uncertain.
            credit = o;
            journal = o.refusedBeforeCall() != null ? State.AMBIGUOUS : State.PAID;
            return receipt(h, payer, journal);
        }

        CompletableFuture<Receipt> start(OwnershipFence fence, ArmAdapter.OwnerRead read, Property admitted,
                                         Map<UUID, Property> cache) {
            payer = admitted.ownerUuid();
            var lease = fence.acquire(read); // requestCore: the ownership lease before any SQL or money.
            var transfer = new JournaledTransfer(this, (r, leg) -> {
                calls.add("money-" + leg);
                return new Observation(true, true, 2.5, 10.0, leg == Leg.DEBIT ? 7.5 : 12.5, false);
            }, worker, main);
            return transfer.submit(handle(), new JournaledTransfer.Lease() {
                public void authorize(Receipt r, Leg leg) {
                    assertEquals("main", thread);
                    // UtilityPaymentRuntime.validate: full-record equality, then the ownership lease itself.
                    var current = cache.get(admitted.propertyId());
                    if (current == null || !current.equals(admitted)) throw new IllegalStateException("Utility property changed");
                    lease.validate(read);
                    calls.add("authorized-" + leg);
                }
                public void releaseVerified(Receipt r) { lease.releaseReconciled(); calls.add("released"); }
            }).toCompletableFuture();
        }
    }

    private static final int RETRY = (int) OwnershipReconcilePump.DEFERRED_RETRY_TICKS + 2;

    // ---- the pump against a held lease

    /** The pump dispatched before the payment; its SQL hop and completion land between DEBIT and CREDIT. */
    @Test void pumpCompletingBetweenDebitAndCreditLeavesTheRecordAndTheCreditCompletes() throws Exception {
        Property p = property();
        Map<UUID, Property> cache = new ConcurrentHashMap<>(Map.of(p.propertyId(), p));
        incident(p, 55);
        var fence = new OwnershipFence(() -> true);
        var pump = new Pump(cache, fence);
        pump.request(); pump.pump.tick(); // dispatched while nothing held the home
        var pay = new Payment();
        pay.onDebitAck = () -> pay.main.execute(() -> { pump.finishHop(); pump.pump.tick(); });
        var done = pay.start(fence, ownerRead(p), p, cache);
        pay.drain();

        assertEquals(State.PAID, done.join().state(), "the CREDIT completed");
        assertEquals(List.of("authorized-DEBIT", "money-DEBIT", "debit-acknowledged", "authorized-CREDIT", "money-CREDIT", "released"),
                pay.calls);
        assertSame(p, cache.get(p.propertyId()), "the record was not swapped under the payment");
        assertEquals(1, pump.results.size());
        assertEquals(1, pump.results.getFirst().deferred());

        // The payment released its lease: the deferred retry installs the SQL row.
        assertTrue(pump.ticksUntilHop(RETRY) > 0);
        pump.finishHop();
        Property row = repo.findById(p.propertyId()).orElseThrow();
        assertEquals(55, row.conditionScore());
        assertEquals(row, cache.get(p.propertyId()));
        assertDoesNotThrow(() -> fence.acquire(ownerRead(p)).releaseReconciled(), "no lease left held");
    }

    /** The pump reaches the home while the payment holds it: no SQL hop at all, and the CREDIT completes. */
    @Test void pumpReachingAHeldHomeDefersWithoutSql() throws Exception {
        Property p = property();
        Map<UUID, Property> cache = new ConcurrentHashMap<>(Map.of(p.propertyId(), p));
        incident(p, 60);
        var fence = new OwnershipFence(() -> true);
        var pump = new Pump(cache, fence);
        var pay = new Payment();
        pay.onDebitAck = () -> pay.main.execute(() -> {
            pump.request();
            pump.pump.tick(); pump.pump.tick();
            assertNull(pump.hop, "no SQL hop for a held home");
        });
        var done = pay.start(fence, ownerRead(p), p, cache);
        pay.drain();

        assertEquals(State.PAID, done.join().state());
        assertEquals(0, pump.writes);
        assertSame(p, cache.get(p.propertyId()));
        assertEquals(1, pump.results.getFirst().deferred());
        assertTrue(pump.ticksUntilHop(RETRY) > 0);
        pump.finishHop();
        assertEquals(60, cache.get(p.propertyId()).conditionScore());
        assertEquals(repo.findById(p.propertyId()).orElseThrow(), cache.get(p.propertyId()));
    }

    /** With no payment in flight, the same pass installs the SQL row at once. */
    @Test void withoutAPaymentThePassRebasesImmediately() throws Exception {
        Property p = property();
        Map<UUID, Property> cache = new ConcurrentHashMap<>(Map.of(p.propertyId(), p));
        incident(p, 55);
        var pump = new Pump(cache, new OwnershipFence(() -> true));
        pump.request(); pump.pump.tick(); pump.finishHop(); pump.pump.tick();
        assertEquals(0, pump.results.getFirst().deferred());
        assertEquals(1, pump.results.getFirst().checked());
        assertEquals(repo.findById(p.propertyId()).orElseThrow(), cache.get(p.propertyId()));
        assertEquals(-1, pump.ticksUntilHop(3 * RETRY), "nothing to retry");
    }

    /** A pump with no fence swaps the record and strands the debit, which is what the fence prevents. */
    @Test void unfencedPumpStrandsTheDebitAtCreditIntent() throws Exception {
        Property p = property();
        Map<UUID, Property> cache = new ConcurrentHashMap<>(Map.of(p.propertyId(), p));
        incident(p, 55);
        var fence = new OwnershipFence(() -> true);
        var pump = new Pump(cache, null);
        pump.request(); pump.pump.tick();
        var pay = new Payment();
        pay.onDebitAck = () -> pay.main.execute(() -> { pump.finishHop(); pump.pump.tick(); });
        var done = pay.start(fence, ownerRead(p), p, cache);
        pay.drain();

        assertNotEquals(p, cache.get(p.propertyId()), "the record was swapped between DEBIT and CREDIT");
        // The credit is refused before its money call and journaled as such: still held for staff, never retried.
        assertEquals(State.AMBIGUOUS, done.join().state());
        assertEquals(TransferProof.Refusal.LEASE_REFUSED, pay.credit.refusedBeforeCall());
        assertEquals("java.lang.IllegalStateException", pay.credit.refusalCause());
        assertEquals(State.AMBIGUOUS, pay.journal, "debited, credit refused, never credited");
        assertEquals(List.of("authorized-DEBIT", "money-DEBIT", "debit-acknowledged"), pay.calls);
        assertThrows(IllegalStateException.class, () -> fence.acquire(ownerRead(p)), "the payment's lease stays held");
    }

    /** The production pump is wired to the live fence, and the guarded rent step installs full rows for held homes. */
    @Test void productionWiring() throws Exception {
        String service = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/prosperitysmp/npc/property/PropertyService.java"));
        assertTrue(service.contains("public com.prosperitysmp.npc.arm.OwnershipFence fence() { return plugin.ownershipFence(); }"),
                "PropertyService's pump host hands the pump the live ownership fence");
        String step = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/prosperitysmp/npc/property/GuardedRentStep.java"));
        assertFalse(step.contains("PropertyService.rebaseDueRent("), "the guarded step does not release over drifted held entries");
        assertEquals(2, step.split("installHeld\\(host\\.cache\\(\\)", -1).length - 1, "apply and settle both install");
    }

    // ---- SQL helpers

    private static void exec(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) { ps.setQueryTimeout(5); bind(ps, args); ps.execute(); }
    }
    private static void bind(PreparedStatement ps, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            if (args[i] instanceof UUID id) ps.setBytes(i + 1, Uuids.toBytes(id));
            else ps.setObject(i + 1, args[i]);
        }
    }
    private static void applyResource(String path) throws Exception {
        try (InputStream in = ReconcilePumpLeaseIT.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            try (Connection c = db.getConnection()) {
                for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8))) exec(c, sql);
            }
        }
    }
}

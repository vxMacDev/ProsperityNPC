package com.prosperitysmp.npc.economy;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.arm.ArmAdapter;
import com.prosperitysmp.npc.arm.OwnershipFence;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.property.DueRentLeases;
import com.prosperitysmp.npc.property.GuardedRentHarness;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyRepository;
import com.prosperitysmp.npc.property.PropertyService;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * housing.rent-change-lease-guard-enabled. The daily asking-rent change step against a payment that is between
 * its acknowledged DEBIT and its CREDIT on the same home. Real MariaDB for the rent step's SQL, a real
 * OwnershipFence, the real JournaledTransfer with a fake journal, and the payment's CREDIT authorization doing
 * what UtilityPaymentRuntime.validate does: the cached Property must still equal the one the payment was admitted
 * against ("Utility property changed"). Main and worker are queued separately and drained in order.
 */
@Timeout(120)
class DueRentLeaseGuardIT {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 13);
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-00000000b001");
    private static final UUID RESERVE = new UUID(0, 3), RUN = new UUID(0, 2);
    private static DB maria;
    private static Database db;
    private PropertyRepository repo;
    private UUID owner;

    @BeforeAll static void boot() throws Exception {
        var cfg = DBConfigurationBuilder.newBuilder(); cfg.setPort(0);
        cfg.setBaseDir(java.nio.file.Files.createTempDirectory("npc-rent-lease-maria-").toFile());
        maria = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        try {
            maria.start();
            String schema = "npc_rent_lease_" + UUID.randomUUID().toString().replace("-", "");
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

    private Property property(int asking) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = db.getConnection()) {
            exec(c, "INSERT INTO npc_properties(property_id,world_uuid,region_id,market_id,class_key,capacity,reference_rent_cents,"
                    + "asking_rent_cents,owner_uuid,ownership_epoch,condition_score,enabled) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, WORLD, "r-" + id, "test", "small", 3, 1000, asking, owner, 0, 80, true);
        }
        return repo.findById(id).orElseThrow();
    }
    /** Schedules a change due on DAY (asked on DAY-1 with a one-day delay). */
    private long dueChange(Property p, int cents) throws SQLException {
        var change = repo.changeAskingRent(p, cents, DAY.minusDays(1), 1);
        assertEquals(PropertyRepository.RentChange.Outcome.SCHEDULED, change.outcome());
        assertEquals(DAY, change.effectiveDay());
        return change.changeId();
    }
    private int asking(Property p) throws SQLException {
        return ((Number) scalar("SELECT asking_rent_cents FROM npc_properties WHERE property_id=?", p.propertyId())).intValue();
    }
    private long rowVersion(Property p) throws SQLException {
        return ((Number) scalar("SELECT row_version FROM npc_properties WHERE property_id=?", p.propertyId())).longValue();
    }
    private String state(long changeId) throws SQLException {
        return String.valueOf(scalar("SELECT state FROM npc_rent_changes WHERE change_id=?", changeId));
    }
    private ArmAdapter.OwnerRead ownerRead(Property p) {
        Object identity = new Object();
        World world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, m, a) -> { throw new AssertionError("No Bukkit access expected"); });
        return new ArmAdapter.OwnerRead(p.worldUuid(), p.regionId(), p.ownerUuid(), identity, identity, identity, identity, identity, world);
    }

    /**
     * The service's rent step on main. Guard on: the production GuardedRentStep (what PropertyService runs), with
     * its main and worker hops drained to completion inside this main task, against the shared cache. It takes
     * DueRentLeases before the SQL, passes their exclusions to SQL, rebases only held entries, then releases; a
     * deferred change leaves its retry scheduled (not fired here). Returns the SQL step's result. Off: the step without the guard.
     */
    private PropertyRepository.DueRentResult step(boolean guard, OwnershipFence fence, Map<UUID, Property> cache)
            throws SQLException {
        if (!guard) {
            var result = repo.applyDueRentChanges(DAY, PropertyRepository.MAX_DUE_RENT_CHANGES);
            assertFalse(PropertyService.rebaseDueRent(cache, result, List.of(), id -> true).drift());
            return result;
        }
        var host = new GuardedRentHarness(cache);
        var sql = new GuardedRentHarness.RepoSql(repo);
        assertTrue(host.start(sql, fence, DAY));
        host.drain();
        assertFalse(host.suspect, "no drift");
        assertEquals(1, sql.applied.size(), "a due change reached the SQL step");
        return sql.applied.getFirst();
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
        public Receipt recordCredit(Handle h, Observation o) { journal = State.PAID; return receipt(h, payer, journal); }

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

    // ---- repository level

    @Test void excludedDueChangeStaysPendingUntouchedAndAppliesOnTheNextCall() throws Exception {
        Property held = property(1000), free = property(1000);
        long heldChange = dueChange(held, 1200), freeChange = dueChange(free, 1100);
        long heldVersion = rowVersion(held);

        var first = repo.applyDueRentChanges(DAY, 512, Set.of(held.propertyId()));
        assertEquals(List.of(held.propertyId()), first.deferred());
        assertEquals(1, first.changes().size(), "the other home still applies");
        assertEquals(free.propertyId(), first.changes().getFirst().before().propertyId());
        assertEquals(1100, asking(free));
        assertEquals("APPLIED", state(freeChange));
        assertEquals(1000, asking(held), "excluded: asking rent unchanged");
        assertEquals(heldVersion, rowVersion(held), "excluded: row not written at all");
        assertEquals("PENDING", state(heldChange));
        assertEquals(1, first.stillPending());
        assertEquals(1, repo.countPendingRentChanges());

        var next = repo.applyDueRentChanges(DAY, 512, Set.of());
        assertTrue(next.deferred().isEmpty());
        assertEquals(1, next.changes().size());
        assertTrue(next.changes().getFirst().applied());
        assertEquals(1200, asking(held));
        assertEquals("APPLIED", state(heldChange));
        assertEquals(0, next.stillPending());
    }

    @Test void emptyExclusionIsTheOriginalStep() throws Exception {
        Property p = property(1000);
        dueChange(p, 1300);
        var due = repo.applyDueRentChanges(DAY, 512, Set.of());
        assertTrue(due.deferred().isEmpty());
        assertEquals(1, due.changes().size());
        assertEquals(p.withAskingRent(1300), due.changes().getFirst().after());
        assertTrue(new PropertyRepository.DueRentResult(List.of(), 0).deferred().isEmpty());
    }

    // ---- a payment between DEBIT and CREDIT while the day's rent step runs

    @Test void guardedStepWaitsForTheInFlightPaymentWhichCompletes() throws Exception {
        Property p = property(1000);
        long change = dueChange(p, 1200);
        long version = rowVersion(p);
        var fence = new OwnershipFence(() -> true);
        Map<UUID, Property> cache = new ConcurrentHashMap<>(Map.of(p.propertyId(), p));
        var pay = new Payment();
        List<PropertyRepository.DueRentResult> steps = new ArrayList<>();
        // The daily cycle's step lands on main right after the DEBIT is acknowledged, before the CREDIT.
        pay.onDebitAck = () -> pay.main.execute(() -> {
            try { steps.add(step(true, fence, cache)); } catch (SQLException e) { throw new AssertionError(e); }
        });
        var done = pay.start(fence, ownerRead(p), p, cache);
        pay.drain();

        assertEquals(1, steps.size(), "the step ran between DEBIT and CREDIT");
        assertEquals(List.of(p.propertyId()), steps.getFirst().deferred(), "the leased home was excluded");
        assertTrue(steps.getFirst().changes().isEmpty());
        assertEquals(State.PAID, done.join().state(), "the CREDIT completed");
        assertEquals(List.of("authorized-DEBIT", "money-DEBIT", "debit-acknowledged", "authorized-CREDIT", "money-CREDIT", "released"),
                pay.calls);
        assertEquals(1000, asking(p), "the change waited");
        assertEquals(version, rowVersion(p));
        assertEquals("PENDING", state(change));
        assertEquals(p, cache.get(p.propertyId()));

        // The payment released its lease: the next step applies the change to SQL and the cache.
        var later = step(true, fence, cache);
        assertTrue(later.deferred().isEmpty());
        assertEquals(1, later.changes().size());
        assertEquals(1200, asking(p));
        assertEquals("APPLIED", state(change));
        assertEquals(p.withAskingRent(1200), cache.get(p.propertyId()));
    }

    /** Without the guard, the same interleaving strands the debit at CREDIT_INTENT. */
    @Test void unguardedStepBreaksTheCreditAuthorization() throws Exception {
        Property p = property(1000);
        long change = dueChange(p, 1200);
        var fence = new OwnershipFence(() -> true);
        Map<UUID, Property> cache = new ConcurrentHashMap<>(Map.of(p.propertyId(), p));
        var pay = new Payment();
        pay.onDebitAck = () -> pay.main.execute(() -> {
            try { step(false, fence, cache); } catch (SQLException e) { throw new AssertionError(e); }
        });
        var done = pay.start(fence, ownerRead(p), p, cache);
        pay.drain();

        assertEquals("APPLIED", state(change), "the unguarded step changed the home mid-transfer");
        assertEquals(1200, asking(p));
        var failure = assertThrows(java.util.concurrent.CompletionException.class, done::join);
        assertEquals("Utility property changed", failure.getCause().getMessage());
        assertEquals(State.CREDIT_INTENT, pay.journal, "debited, CREDIT_INTENT durable, never credited");
        assertEquals(List.of("authorized-DEBIT", "money-DEBIT", "debit-acknowledged"), pay.calls);
        assertThrows(IllegalStateException.class, () -> fence.acquire(ownerRead(p)), "the payment's lease stays held");
    }

    @Test void stepHoldingAHomeMakesAPaymentFailBeforeAnyDebit() throws Exception {
        Property p = property(1000);
        var fence = new OwnershipFence(() -> true);
        Map<UUID, Property> cache = new ConcurrentHashMap<>(Map.of(p.propertyId(), p));
        var leases = DueRentLeases.acquire(fence, cache.values());
        assertTrue(leases.holds(p.propertyId()));
        assertTrue(leases.excluded().isEmpty());
        var pay = new Payment();
        assertThrows(IllegalStateException.class, () -> pay.start(fence, ownerRead(p), p, cache));
        assertTrue(pay.queue.isEmpty(), "no journal row and no money call was submitted");
        assertNull(pay.journal);
        leases.releaseAll();
        leases.releaseAll(); // idempotent
        var after = new Payment();
        after.start(fence, ownerRead(p), p, cache);
        after.drain();
        assertEquals(State.PAID, after.journal, "released: payments start again");
    }

    // ---- cache side and retry bound

    @Test void rebaseLeavesUnheldEntriesAndReportsTheirDrift() {
        UUID id = UUID.randomUUID();
        Property cached = new Property(id, WORLD, "r", "m", "small", 3, 1000, 1000, UUID.randomUUID(), 0, 80, true);
        Property fresh = cached.withAskingRent(1200);
        Map<UUID, Property> cache = new ConcurrentHashMap<>(Map.of(id, cached));
        var empty = new PropertyRepository.DueRentResult(List.of(), 0);
        var held = PropertyService.rebaseDueRent(cache, empty, List.of(fresh), x -> false);
        assertTrue(held.drift(), "a held entry that differs from SQL is drift, not silently replaced");
        assertSame(cached, cache.get(id));
        var free = PropertyService.rebaseDueRent(cache, empty, List.of(fresh), x -> true);
        assertFalse(free.drift());
        assertEquals(fresh, cache.get(id));
        // A non-rent difference is drift either way.
        Map<UUID, Property> other = new ConcurrentHashMap<>(Map.of(id, cached));
        assertTrue(PropertyService.rebaseDueRent(other, empty, List.of(fresh.withOwner(UUID.randomUUID(), 1)), x -> true).drift());
        assertSame(cached, other.get(id));
        // A change result for a held entry is never applied to the cache.
        var change = new PropertyRepository.DueRent(cached, fresh, 7, PropertyRepository.DueRent.Resolution.APPLIED);
        Map<UUID, Property> guarded = new ConcurrentHashMap<>(Map.of(id, cached));
        var outcome = PropertyService.rebaseDueRent(guarded, new PropertyRepository.DueRentResult(List.of(change), 0), List.of(), x -> false);
        assertTrue(outcome.drift());
        assertEquals(1, outcome.applied());
        assertSame(cached, guarded.get(id));
    }

    @Test void retryIsBoundedAndOnlyForDeferredChanges() {
        assertFalse(DueRentLeases.retry(0, 1), "nothing deferred: no wait");
        assertTrue(DueRentLeases.retry(1, 1));
        assertTrue(DueRentLeases.retry(3, DueRentLeases.MAX_ATTEMPTS - 1));
        assertFalse(DueRentLeases.retry(1, DueRentLeases.MAX_ATTEMPTS), "never waits without a limit");
        assertTrue(DueRentLeases.MAX_ATTEMPTS * DueRentLeases.RETRY_TICKS <= 20L * 60, "bounded to a minute at most");
    }

    @Test void flagIsOnlyARealBooleanTrue() {
        assertTrue(DueRentLeases.enabled(Boolean.TRUE));
        for (Object off : new Object[]{null, Boolean.FALSE, "true", 1})
            assertFalse(DueRentLeases.enabled(off), String.valueOf(off));
        assertEquals("housing.rent-change-lease-guard-enabled", PropertyService.RENT_CHANGE_LEASE_GUARD);
    }

    // ---- SQL helpers

    private static Object scalar(String sql, Object... args) throws SQLException {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, args);
            try (ResultSet rs = ps.executeQuery()) { assertTrue(rs.next()); return rs.getObject(1); }
        }
    }
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
        try (InputStream in = DueRentLeaseGuardIT.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            try (Connection c = db.getConnection()) {
                for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8))) exec(c, sql);
            }
        }
    }
}

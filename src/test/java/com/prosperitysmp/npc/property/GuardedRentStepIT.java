package com.prosperitysmp.npc.property;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.arm.OwnershipFence;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * housing.rent-change-lease-guard-enabled through the PRODUCTION GuardedRentStep (the code PropertyService runs):
 * real MariaDB, the real PropertyRepository, a real OwnershipFence, and PropertyService's state as plain fields.
 * Covers: nothing due takes no lease and writes nothing; acquire, SQL with exclusions, rebase of held entries,
 * release and retry; a coordinator failure aborts; and an unconfirmed step during a database outage keeps its
 * leases, backs off, and releases on the first successful re-read.
 */
@Timeout(120)
class GuardedRentStepIT {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 13);
    private static final UUID WORLD = UUID.fromString("00000000-0000-0000-0000-00000000b101");
    private static DB maria;
    private static Database db;
    private PropertyRepository repo;
    private UUID owner;
    private GuardedRentHarness host;
    private GuardedRentHarness.RepoSql sql;
    private OwnershipFence fence;

    @BeforeAll static void boot() throws Exception {
        var cfg = DBConfigurationBuilder.newBuilder(); cfg.setPort(0);
        cfg.setBaseDir(java.nio.file.Files.createTempDirectory("npc-guarded-rent-maria-").toFile());
        maria = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        try {
            maria.start();
            String schema = "npc_guarded_rent_" + UUID.randomUUID().toString().replace("-", "");
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
        host = new GuardedRentHarness();
        sql = new GuardedRentHarness.RepoSql(repo);
        fence = new OwnershipFence(() -> true);
    }

    // ---- inert unless a change is due

    @Test void nothingDueTakesNoLeaseWritesNothingAndRepliesLikeTheUnguardedStep() throws Exception {
        Property later = property(1000), none = property(1000);
        long change = changeEffective(later, 1200, DAY.plusDays(1)); // pending, not due today
        cache(later, none);
        var rows = rowState();
        int[] busy = {0};
        host.probe = () -> busy[0] += busyCount();

        assertTrue(host.start(sql, fence, DAY));
        host.drain();

        assertEquals(0, busy[0], "no home was held at any point");
        assertEquals(List.of(Map.entry(true, "No rent changes due.")), host.done);
        assertEquals(1, sql.statusCalls);
        assertEquals(0, sql.applyCalls, "the write step never ran");
        assertEquals(0, sql.findCalls);
        assertEquals(rows, rowState(), "nothing written");
        assertEquals("PENDING", state(change));
        assertTrue(host.finished);
        assertTrue(host.pending, "the later change is still remembered as pending");
        assertFalse(host.suspect);
        assertEquals(0, host.cacheChanges);
        assertEquals(Map.of(later.propertyId(), later, none.propertyId(), none), host.cache);
        assertNull(host.scheduled);

        // The unguarded step on the same state: same reply, same pending state, also no write.
        var unguarded = repo.applyDueRentChanges(DAY, PropertyRepository.MAX_DUE_RENT_CHANGES);
        var copy = new HashMap<>(host.cache);
        var outcome = PropertyService.rebaseDueRent(copy, unguarded, List.of(), id -> true);
        assertEquals(host.done.getFirst().getValue(),
                PropertyService.dueRentSummary(outcome.applied(), outcome.cancelled(), outcome.replaced()));
        assertEquals(host.pending, unguarded.stillPending() > 0);
        assertEquals(rows, rowState());

        // On its day the same change is due, the homes are held during the step, and it applies, so the probe can see a hold.
        var due = new GuardedRentHarness();
        due.cache.putAll(host.cache);
        int[] heldSeen = {0};
        due.probe = () -> heldSeen[0] += busyCount();
        assertTrue(due.start(sql, fence, DAY.plusDays(1)));
        due.drain();
        assertTrue(heldSeen[0] > 0, "the probe can see a held home");
        assertEquals(List.of(Map.entry(true, "Rent changes applied: 1.")), due.done);
        assertEquals(later.withAskingRent(1200), due.cache.get(later.propertyId()));
        assertEquals("APPLIED", state(change));
        assertEquals(0, busyCount(), "released");
    }

    @Test void unreadableStatusChangesNothingAndHoldsNothing() throws Exception {
        Property p = property(1000);
        dueChange(p, 1200);
        cache(p);
        var rows = rowState();
        sql.outage = true;
        assertTrue(host.start(sql, fence, DAY));
        host.drain();
        assertEquals(List.of(Map.entry(false, GuardedRentStep.UNREADABLE)), host.done);
        assertEquals(0, sql.applyCalls);
        assertTrue(host.finished);
        assertFalse(host.suspect, "nothing was written, so the cache is not suspect");
        assertEquals(0, busyCount());
        assertEquals(rows, rowState());
    }

    // ---- the DB-outage hold ends on the first successful re-read

    @Test void outageHoldKeepsLeasesBacksOffAndReleasesOnTheFirstGoodReread() throws Exception {
        Property p = property(1000), q = property(1000);
        long change = dueChange(p, 1200);
        cache(p, q);
        // The status read succeeds; then the database goes away after the step's commit, before its acknowledgement.
        sql.onApply = () -> { sql.outage = true; sql.loseApplyAcknowledgement = true; };

        assertTrue(host.start(sql, fence, DAY));
        host.drain();

        assertEquals(List.of(Map.entry(false, GuardedRentStep.HELD)), host.done);
        assertFalse(host.finished, "admissionPending stays set while the outcome is unknown");
        assertTrue(host.suspect);
        assertEquals(1, host.alerts.size());
        assertTrue(host.alerts.getFirst().startsWith("2:"), host.alerts.toString());
        assertEquals(GuardedRentStep.REREAD_TICKS, host.scheduledTicks);
        assertEquals(2, busyCount(), "both homes stay held: no payment may start");
        assertThrows(OwnershipFence.Busy.class, () -> fence.acquireExclusive(p.worldUuid(), p.regionId()));
        assertEquals("APPLIED", state(change), "the step did commit");
        assertEquals(p, host.cache.get(p.propertyId()), "the cache was not guessed");

        // Still down: the re-read fails, the leases stay, and the wait doubles.
        host.fireScheduled();
        assertEquals(1, host.done.size(), "done is reported once");
        assertFalse(host.finished);
        assertEquals(2, busyCount());
        assertEquals(GuardedRentStep.rereadDelay(2), host.scheduledTicks);
        assertEquals(200L, host.scheduledTicks);
        assertEquals(2, host.alerts.size(), "alerted while held (StaffAlerts dedupes per day)");

        // The database is back: the next re-read rebases the held entries, releases and ends the step.
        sql.onApply = () -> { };
        sql.outage = false; sql.loseApplyAcknowledgement = false;
        host.fireScheduled();
        assertTrue(host.finished);
        assertNull(host.scheduled, "no further retry");
        assertEquals(0, busyCount(), "every lease released");
        assertEquals(p.withAskingRent(1200), host.cache.get(p.propertyId()), "cache matches what SQL committed");
        assertEquals(q, host.cache.get(q.propertyId()));
        assertEquals(1, host.cacheChanges);
        assertTrue(host.suspect, "the next step still rebases the whole cache");
        assertEquals(1, host.done.size());

        // The next daily cycle runs normally again.
        var next = new GuardedRentHarness();
        next.cache.putAll(host.cache); next.suspect = true;
        assertTrue(next.start(sql, fence, DAY));
        next.drain();
        assertEquals(List.of(Map.entry(true, "No rent changes due.")), next.done);
        assertFalse(next.suspect);
        assertEquals(0, busyCount());
    }

    @Test void unconfirmedButReadableReleasesAtOnce() throws Exception {
        Property p = property(1000);
        dueChange(p, 1300);
        cache(p);
        sql.onApply = () -> sql.loseApplyAcknowledgement = true;
        assertTrue(host.start(sql, fence, DAY));
        host.drain();
        assertEquals(List.of(Map.entry(false, GuardedRentStep.NOT_CONFIRMED)), host.done);
        assertTrue(host.finished);
        assertTrue(host.suspect);
        assertNull(host.scheduled);
        assertEquals(0, busyCount());
        assertEquals(p.withAskingRent(1300), host.cache.get(p.propertyId()));
        assertTrue(host.alerts.isEmpty());
    }

    @Test void rereadWaitIsBounded() {
        assertEquals(List.of(100L, 200L, 400L, 800L, 1200L, 1200L, 1200L),
                java.util.stream.IntStream.rangeClosed(1, 7).mapToObj(GuardedRentStep::rereadDelay).toList());
        assertEquals(1200L, GuardedRentStep.rereadDelay(Integer.MAX_VALUE));
        assertEquals(100L, GuardedRentStep.rereadDelay(0));
    }

    @Test void workerBusyDuringTheHoldKeepsTheLeasesAndBacksOff() throws Exception {
        Property p = property(1000);
        dueChange(p, 1200);
        cache(p);
        sql.onApply = () -> { sql.outage = true; sql.loseApplyAcknowledgement = true; };
        assertTrue(host.start(sql, fence, DAY));
        host.drain();
        host.rejectWorker = true;
        host.fireScheduled();
        assertFalse(host.finished);
        assertEquals(1, busyCount());
        assertEquals(200L, host.scheduledTicks);
        host.rejectWorker = false; sql.outage = false; sql.loseApplyAcknowledgement = false;
        host.fireScheduled();
        assertTrue(host.finished);
        assertEquals(0, busyCount());
    }

    // ---- acquire -> SQL with exclusions -> rebase held -> release -> retry

    @Test void busyHomeIsExcludedThenAppliedOnTheRetry() throws Exception {
        Property paying = property(1000), free = property(1000);
        long payingChange = dueChange(paying, 1200), freeChange = dueChange(free, 1100);
        cache(paying, free);
        var payment = fence.acquireExclusive(paying.worldUuid(), paying.regionId()); // a payment between DEBIT and CREDIT

        assertTrue(host.start(sql, fence, DAY));
        host.drain();
        assertEquals(List.of(paying.propertyId()), sql.applied.getFirst().deferred());
        assertEquals("PENDING", state(payingChange));
        assertEquals("APPLIED", state(freeChange));
        assertEquals(paying, host.cache.get(paying.propertyId()), "the payment's admitted record is untouched");
        assertEquals(free.withAskingRent(1100), host.cache.get(free.propertyId()));
        assertTrue(host.done.isEmpty());
        assertFalse(host.finished);
        assertEquals(DueRentLeases.RETRY_TICKS, host.scheduledTicks);
        assertThrows(OwnershipFence.Busy.class, () -> fence.acquireExclusive(paying.worldUuid(), paying.regionId()),
                "still the payment's lease only");
        assertDoesNotThrow(() -> fence.acquireExclusive(free.worldUuid(), free.regionId()).releaseReconciled(),
                "the step released its own leases while it waits");

        payment.releaseReconciled();
        host.fireScheduled();
        assertEquals(List.of(Map.entry(true, "Rent changes applied: 2.")), host.done);
        assertEquals("APPLIED", state(payingChange));
        assertEquals(paying.withAskingRent(1200), host.cache.get(paying.propertyId()));
        assertTrue(host.finished);
        assertEquals(0, busyCount());
    }

    // ---- coordinator failure aborts instead of excluding everything

    @Test void stoppedCoordinatorAbortsTheAttemptWithoutRetrying() throws Exception {
        Property p = property(1000);
        long change = dueChange(p, 1200);
        cache(p);
        fence.stop();
        assertTrue(host.start(sql, fence, DAY));
        host.drain();
        assertEquals(List.of(Map.entry(false, GuardedRentStep.NOT_STARTED)), host.done);
        assertEquals(0, sql.applyCalls, "never marked everything excluded and ran");
        assertNull(host.scheduled, "no 30-attempt retry loop");
        assertTrue(host.finished);
        assertEquals("PENDING", state(change));
        assertTrue(host.logs.stream().anyMatch(l -> l.contains("Ownership coordinator stopped")), host.logs.toString());

        // Suspect cache goes straight to the attempt: refused at once, nothing submitted, done not called.
        var suspect = new GuardedRentHarness();
        suspect.cache.putAll(host.cache); suspect.suspect = true;
        assertFalse(suspect.start(sql, fence, DAY));
        assertTrue(suspect.done.isEmpty());
        assertTrue(suspect.finished);
    }

    @Test void onlyBusyMeansExcluded() throws Exception {
        Property a = property(1000), b = property(1000);
        var held = fence.acquireExclusive(a.worldUuid(), a.regionId());
        var leases = DueRentLeases.acquire(fence, List.of(a, b));
        assertEquals(java.util.Set.of(a.propertyId()), leases.excluded());
        assertEquals(List.of(b.propertyId()), leases.heldIds());
        leases.releaseAll();
        assertEquals(List.of(), leases.heldIds());
        held.releaseReconciled();

        var wrongThread = new OwnershipFence(() -> false);
        var failure = assertThrows(IllegalStateException.class, () -> DueRentLeases.acquire(wrongThread, List.of(a, b)));
        assertFalse(failure instanceof OwnershipFence.Busy);
        assertEquals("Ownership fence requires main thread", failure.getMessage());

        var stopped = new OwnershipFence(() -> true);
        stopped.stop();
        assertEquals("Ownership coordinator stopped",
                assertThrows(IllegalStateException.class, () -> DueRentLeases.acquire(stopped, List.of(a, b))).getMessage());
    }

    @Test void productionServiceRunsThisStepAndFallsBackWithoutAFence() throws Exception {
        String code = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/prosperitysmp/npc/property/PropertyService.java"));
        int flag = code.indexOf("if (DueRentLeases.enabled(plugin.getConfig().get(RENT_CHANGE_LEASE_GUARD))) {");
        int guarded = code.indexOf("if (fence != null) return applyDueRentChangesLeased(fence, today, done);");
        int once = code.indexOf("if (!unguardedFallbackLogged) {");
        int unguarded = code.indexOf("final boolean rebase = rentCacheSuspect;");
        assertTrue(flag > 0 && guarded > flag && once > guarded && unguarded > once, "fence null falls through to the unguarded step");
        assertTrue(code.contains("started = GuardedRentStep.start(rentStepHost, new GuardedRentStep.Sql() {"));
        assertTrue(code.contains("public void schedule(long ticks, Runnable next) { rentRetryTicks = ticks; rentRetry = next; }"),
                "the hold's re-read uses the ticker slot that close() clears");
        assertTrue(code.contains("visibility.clear(); refreshRequested = false; rentRetry = null;"));
        assertFalse(code.contains("private boolean attemptLeased("), "no second copy of the guarded step");
    }

    // ---- never release a held home over a record SQL contradicts

    /**
     * Records a violation whenever a home the step held is free again while its cached record differs from its SQL
     * row. Before the step takes the lease the cache may be stale (that is the premise); after release it must not be.
     */
    private List<String> releaseInvariant(Property... watched) {
        var violations = new ArrayList<String>();
        var seenHeld = new java.util.HashSet<UUID>();
        host.probe = () -> {
            for (Property w : watched) {
                UUID id = w.propertyId();
                boolean held;
                try { fence.acquireExclusive(w.worldUuid(), w.regionId()).releaseReconciled(); held = false; }
                catch (OwnershipFence.Busy busy) { held = true; }
                if (held) { seenHeld.add(id); continue; }
                if (!seenHeld.contains(id)) continue;
                try {
                    var row = repo.findById(id).orElseThrow();
                    if (!row.equals(host.cache.get(id))) violations.add(id + " released with " + host.cache.get(id) + " but SQL has " + row);
                } catch (SQLException e) { throw new AssertionError(e); }
            }
        };
        return violations;
    }
    /** A SQL-side change the cache never saw, as the daily incident batch makes (condition_score only). */
    private void incident(Property p, int condition) throws SQLException {
        try (Connection c = db.getConnection()) {
            exec(c, "UPDATE npc_properties SET condition_score=? WHERE property_id=?", condition, p.propertyId());
        }
    }

    @Test void heldEntryWithNonRentDriftGetsTheFullSqlRowBeforeRelease() throws Exception {
        Property p = property(1000);
        long change = dueChange(p, 1200);
        cache(p);
        incident(p, 50);
        var violations = releaseInvariant(p);

        assertTrue(host.start(sql, fence, DAY));
        host.drain();

        assertEquals(List.of(), violations);
        assertEquals(List.of(Map.entry(true, "Rent changes applied: 1.")), host.done);
        assertEquals("APPLIED", state(change));
        Property row = repo.findById(p.propertyId()).orElseThrow();
        assertEquals(50, row.conditionScore()); assertEquals(1200, row.askingRentCents());
        assertEquals(row, host.cache.get(p.propertyId()), "the full SQL row, condition included");
        assertFalse(host.suspect);
        assertTrue(host.finished);
        assertEquals(0, busyCount());

        // rebaseDueRent on the same SQL result leaves the entry stale and reports drift,
        // which is why the guarded step installs the full row before it releases.
        var old = new HashMap<UUID, Property>(Map.of(p.propertyId(), p));
        var outcome = PropertyService.rebaseDueRent(old, sql.applied.getFirst(), List.of(), id -> true);
        assertTrue(outcome.drift());
        assertSame(p, old.get(p.propertyId()));
        assertNotEquals(row, old.get(p.propertyId()));
    }

    @Test void excludedHomeKeepsItsRecordEvenWithNonRentDrift() throws Exception {
        Property paying = property(1000), free = property(1000);
        dueChange(paying, 1200); dueChange(free, 1100);
        cache(paying, free);
        incident(paying, 40); incident(free, 45);
        var payment = fence.acquireExclusive(paying.worldUuid(), paying.regionId());
        var violations = releaseInvariant(free);

        assertTrue(host.start(sql, fence, DAY));
        host.drain();

        assertEquals(List.of(), violations);
        assertSame(paying, host.cache.get(paying.propertyId()), "the payment's admitted record is untouched");
        assertEquals(repo.findById(free.propertyId()).orElseThrow(), host.cache.get(free.propertyId()));
        payment.releaseReconciled();
        host.fireScheduled();
        assertEquals(repo.findById(paying.propertyId()).orElseThrow(), host.cache.get(paying.propertyId()),
                "once the payment released it, the step held it and installed the full row");
        assertEquals(40, host.cache.get(paying.propertyId()).conditionScore());
        assertEquals(List.of(Map.entry(true, "Rent changes applied: 2.")), host.done);
        assertEquals(0, busyCount());
    }

    @Test void rereadAfterAnOutageInstallsNonRentDriftBeforeRelease() throws Exception {
        Property p = property(1000), q = property(1000);
        dueChange(p, 1200);
        cache(p, q);
        incident(q, 40);
        var violations = releaseInvariant(p, q);
        sql.onApply = () -> { sql.outage = true; sql.loseApplyAcknowledgement = true; };

        assertTrue(host.start(sql, fence, DAY));
        host.drain();
        assertEquals(List.of(Map.entry(false, GuardedRentStep.HELD)), host.done);
        assertEquals(2, busyCount());
        assertSame(q, host.cache.get(q.propertyId()), "nothing guessed while SQL is unreadable");

        sql.onApply = () -> { };
        sql.outage = false; sql.loseApplyAcknowledgement = false;
        host.fireScheduled();
        assertEquals(List.of(), violations);
        assertTrue(host.finished);
        assertEquals(0, busyCount());
        assertEquals(repo.findById(q.propertyId()).orElseThrow(), host.cache.get(q.propertyId()));
        assertEquals(40, host.cache.get(q.propertyId()).conditionScore());
        assertEquals(repo.findById(p.propertyId()).orElseThrow(), host.cache.get(p.propertyId()));
    }

    @Test void installHeldLeavesUnheldEntriesAndCountsTheirDrift() {
        UUID id = UUID.randomUUID();
        Property cached = new Property(id, WORLD, "r", "m", "small", 3, 1000, 1000, UUID.randomUUID(), 0, 80, true);
        Property condition = new Property(id, WORLD, "r", "m", "small", 3, 1000, 1000, cached.ownerUuid(), 0, 50, true);
        var empty = new PropertyRepository.DueRentResult(List.of(), 0);
        // Held: any difference is installed.
        Map<UUID, Property> held = new HashMap<>(Map.of(id, cached));
        var installed = GuardedRentStep.installHeld(held, empty, List.of(condition), x -> true);
        assertTrue(installed.heldMatch()); assertFalse(installed.outcome().drift());
        assertSame(condition, held.get(id));
        // Not held: untouched; a non-rent difference is not rent drift, a rent difference is.
        Map<UUID, Property> busy = new HashMap<>(Map.of(id, cached));
        assertFalse(GuardedRentStep.installHeld(busy, empty, List.of(condition), x -> false).outcome().drift());
        assertTrue(GuardedRentStep.installHeld(busy, empty, List.of(cached.withAskingRent(1200)), x -> false).outcome().drift());
        assertSame(cached, busy.get(id));
        // A change on an unheld entry is never applied, and differs: drift.
        var change = new PropertyRepository.DueRent(cached, cached.withAskingRent(1200), 7, PropertyRepository.DueRent.Resolution.APPLIED);
        var outcome = GuardedRentStep.installHeld(busy, new PropertyRepository.DueRentResult(List.of(change), 0), List.of(), x -> false);
        assertTrue(outcome.outcome().drift()); assertEquals(1, outcome.outcome().applied());
        assertSame(cached, busy.get(id));
    }

    // ---- fixtures

    private void cache(Property... properties) { for (Property p : properties) host.cache.put(p.propertyId(), p); }

    /** Homes in the cache the fence reports busy right now (each probe lease is released at once). */
    private int busyCount() {
        int busy = 0;
        for (Property p : List.copyOf(host.cache.values())) {
            try { fence.acquireExclusive(p.worldUuid(), p.regionId()).releaseReconciled(); }
            catch (OwnershipFence.Busy held) { busy++; }
        }
        return busy;
    }

    private Property property(int asking) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = db.getConnection()) {
            exec(c, "INSERT INTO npc_properties(property_id,world_uuid,region_id,market_id,class_key,capacity,reference_rent_cents,"
                    + "asking_rent_cents,owner_uuid,ownership_epoch,condition_score,enabled) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, WORLD, "g-" + id, "test", "small", 3, 1000, asking, owner, 0, 80, true);
        }
        return repo.findById(id).orElseThrow();
    }
    private long dueChange(Property p, int cents) throws SQLException { return changeEffective(p, cents, DAY); }
    private long changeEffective(Property p, int cents, LocalDate effective) throws SQLException {
        var change = repo.changeAskingRent(p, cents, effective.minusDays(1), 1);
        assertEquals(PropertyRepository.RentChange.Outcome.SCHEDULED, change.outcome());
        assertEquals(effective, change.effectiveDay());
        return change.changeId();
    }
    private String state(long changeId) throws SQLException {
        return String.valueOf(scalar("SELECT state FROM npc_rent_changes WHERE change_id=?", changeId));
    }
    /** Every property row's version and rent, and every rent change's state: a write changes this. */
    private static List<String> rowState() throws SQLException {
        var out = new ArrayList<String>();
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT HEX(property_id), row_version, asking_rent_cents, pending_rent_change_id "
                     + "FROM npc_properties ORDER BY property_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getString(1) + "/" + rs.getLong(2) + "/" + rs.getInt(3) + "/" + rs.getObject(4));
        }
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT change_id, state FROM npc_rent_changes ORDER BY change_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getLong(1) + ":" + rs.getString(2));
        }
        return out;
    }
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
        try (InputStream in = GuardedRentStepIT.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            try (Connection c = db.getConnection()) {
                for (String s : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8))) exec(c, s);
            }
        }
    }
}

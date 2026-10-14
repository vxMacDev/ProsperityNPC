package com.prosperitysmp.npc.training;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.clock.EconomyClock;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentRepository;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static com.prosperitysmp.npc.training.TrainingRepository.Code;
import static org.junit.jupiter.api.Assertions.*;

/** Real isolated MariaDB plus synthetic Bukkit/Jobs host boundary; no installed Jobs execution. */
@Timeout(45)
class TrainingRepositoryIT {
    private static final String SCHEMA = "prosperity_npc_training";
    private static final Instant AT = TrainingEventsTest.AT;
    private static final LocalDate DAY = LocalDate.of(2026, 9, 29);
    private static DB maria;
    private static Database db;
    private static DatabaseSettings settings;
    private TrainingRepository repository;
    private ResidentRepository residents;
    private UUID player;
    private UUID trainer;

    @BeforeAll static void boot() throws Exception {
        var cfg = DBConfigurationBuilder.newBuilder(); cfg.setPort(0);
        maria = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        try {
            maria.start(); maria.createDB(SCHEMA);
            settings = new DatabaseSettings("localhost", cfg.getPort(), SCHEMA, "root", "", 8, 10_000);
            db = new Database(); db.connect(settings);
            apply("/db/migration/V2__resident_identity.sql");
            apply("/db/migration/V20__training_grants.sql");
            apply("/db/migration/V20__training_grants.sql");
        } catch (Exception | AssertionError failure) {
            try { stop(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @AfterAll static void stop() throws Exception {
        try { if (db != null) db.close(); }
        finally { if (maria != null) { DB closing = maria; maria = null; closing.stop(); } }
    }

    @BeforeEach void reset() throws Exception {
        execute("DROP TRIGGER IF EXISTS training_guard_failure");
        execute("DELETE FROM npc_training_grants"); execute("DELETE FROM npc_training_players");
        execute("DELETE FROM npc_resident_memory"); execute("DELETE FROM npc_resident_profiles");
        repository = new TrainingRepository(db); residents = new ResidentRepository(db);
        player = UUID.randomUUID(); trainer = UUID.randomUUID();
        residents.register(ResidentCatalogue.draft(trainer, "Mara Vale"));
    }

    @AfterEach void cleanup() throws SQLException { execute("DROP TRIGGER IF EXISTS training_guard_failure"); }

    @Test void persistentTrainerAndAcquaintanceAreRequiredWithoutTrustAward() throws Exception {
        assertEquals(Code.UNKNOWN_TRAINER, request(repository, player, UUID.randomUUID(), "miner", AT, DAY).code());
        assertEquals(Code.RELATIONSHIP_TOO_LOW, request(repository, player, trainer, "miner", AT, DAY).code());
        introduce();
        assertEquals(Code.GRANTED, request(repository, player, trainer, "Miner", AT, DAY).code());
        assertEquals(1, scalar("SELECT COUNT(*) FROM npc_resident_memory"));
        assertEquals(0, residents.readRelationship(trainer, player).reliability());
    }

    @Test void exactReplayRetainsSessionTimeAndOnlyOneJobCanBeActive() throws Exception {
        TrainingGrant original = grant();
        var replay = request(repository, player, trainer, "MINER", AT.plusSeconds(10), DAY);
        assertEquals(Code.ALREADY_ACTIVE, replay.code()); assertEquals(Optional.of(original), replay.grant());
        assertEquals(Code.ACTIVE_CONFLICT, request(repository, player, trainer, "farmer", AT.plusSeconds(10), DAY).code());
        UUID other = UUID.randomUUID(); residents.register(ResidentCatalogue.draft(other, "Ellis Brook"));
        assertEquals(Code.ACTIVE_CONFLICT, request(repository, player, other, "miner", AT.plusSeconds(10), DAY).code());
        assertEquals(1, scalar("SELECT COUNT(*) FROM npc_training_grants"));
    }

    @Test void expiryConsumesDayAndRegressedTimeOrDayCannotRefresh() throws Exception {
        TrainingGrant original = grant();
        assertTrue(original.activeAt(original.expiresAt().minusMillis(1)));
        assertFalse(original.activeAt(original.expiresAt()));
        assertEquals(Code.DAILY_LIMIT, request(repository, player, trainer, "miner", original.expiresAt(), DAY).code());
        assertEquals(Code.TIME_REGRESSION, request(repository, player, trainer, "miner", AT.minusMillis(1), DAY).code());
        assertEquals(Code.TIME_REGRESSION, request(repository, player, trainer, "miner", AT, DAY.minusDays(1)).code());
        assertEquals(Optional.of(original), repository.currentForPlayer(player));
        assertEquals(Code.GRANTED, request(repository, player, trainer, "miner", AT.plusSeconds(86400), DAY.plusDays(1)).code());
    }

    @Test void actualPoolRestartRetainsGrantExpiryAndDailyLimit() throws Exception {
        TrainingGrant original = grant(); db.close(); db.connect(settings); repository = new TrainingRepository(db);
        assertEquals(Optional.of(original), repository.currentForPlayer(player));
        assertEquals(Optional.of(original), request(repository, player, trainer, "miner", AT.plusSeconds(1), DAY).grant());
        assertEquals(Code.DAILY_LIMIT, request(repository, player, trainer, "miner", original.expiresAt(), DAY).code());
        assertTrue(repository.currentForPlayer(UUID.randomUUID()).isEmpty());
        assertEquals(1, scalar("SELECT COUNT(*) FROM npc_training_players"));
    }

    @Test void competingSameAndDifferentJobRequestsHaveOneWinner() throws Exception {
        introduce();
        List<Code> results = concurrently(
                () -> request(repository, player, trainer, "miner", AT, DAY).code(),
                () -> request(repository, player, trainer, "miner", AT, DAY).code());
        assertTrue(results.contains(Code.GRANTED)); assertTrue(results.contains(Code.ALREADY_ACTIVE));
        UUID other = UUID.randomUUID(); residents.introduce(trainer, other, AT.minusSeconds(1), DAY);
        List<Code> different = concurrently(
                () -> request(repository, other, trainer, "miner", AT, DAY).code(),
                () -> request(repository, other, trainer, "farmer", AT, DAY).code());
        assertTrue(different.contains(Code.GRANTED)); assertTrue(different.contains(Code.ACTIVE_CONFLICT));
        assertEquals(2, scalar("SELECT COUNT(*) FROM npc_training_grants"));
    }

    @Test void issuanceActuallyWaitsOnPlayerGuardBeforeGrantOrMemory() throws Exception {
        grant();
        var worker = Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<Code> pending;
            try (Connection blocker = db.getConnection()) {
                blocker.setAutoCommit(false);
                try (PreparedStatement ps = blocker.prepareStatement("SELECT player_id FROM npc_training_players WHERE player_id=? FOR UPDATE")) {
                    ps.setBytes(1, Uuids.toBytes(player));
                    try (ResultSet rs = ps.executeQuery()) { assertTrue(rs.next()); }
                }
                try {
                    pending = worker.submit(() -> request(repository, player, trainer, "miner", AT, DAY).code());
                    awaitGuardInsert(); assertFalse(pending.isDone());
                    assertEquals(1, scalar("SELECT COUNT(*) FROM npc_training_grants"));
                } finally { blocker.rollback(); }
            }
            assertEquals(Code.ALREADY_ACTIVE, pending.get(10, TimeUnit.SECONDS));
        } finally { worker.shutdownNow(); assertTrue(worker.awaitTermination(15, TimeUnit.SECONDS)); }
    }

    @Test void writeFailureRollsBackGrantAndDayReservation() throws Exception {
        introduce();
        execute("""
                CREATE TRIGGER training_guard_failure BEFORE UPDATE ON npc_training_players FOR EACH ROW
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='training guard test failure'
                """);
        SQLException failure = assertThrows(SQLException.class, () -> request(repository, player, trainer, "miner", AT, DAY));
        assertEquals("45000", failure.getSQLState());
        assertEquals(0, scalar("SELECT COUNT(*) FROM npc_training_grants"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM npc_training_players WHERE last_accepted_day IS NOT NULL"));
        execute("DROP TRIGGER training_guard_failure");
        assertEquals(Code.GRANTED, request(repository, player, trainer, "miner", AT, DAY).code());
    }

    @Test void lostRealCommitAcknowledgmentHasOneDurableGrantAndSafeReplay() throws Exception {
        introduce(); AtomicBoolean lose = new AtomicBoolean(true);
        TrainingRepository losing = new TrainingRepository(() -> lostCommit(db.getConnection(), lose));
        SQLException failure = assertThrows(SQLException.class, () -> request(losing, player, trainer, "miner", AT, DAY));
        assertTrue(failure.getMessage().contains("lost training commit acknowledgment"));
        TrainingGrant committed = repository.currentForPlayer(player).orElseThrow();
        var replay = request(repository, player, trainer, "miner", AT.plusSeconds(1), DAY);
        assertEquals(Code.ALREADY_ACTIVE, replay.code()); assertEquals(Optional.of(committed), replay.grant());
        assertEquals(1, scalar("SELECT COUNT(*) FROM npc_training_grants"));
    }

    @Test void sqlDefendsPolicyAndDailySlotWithoutCascadeLoss() throws Exception {
        grant();
        assertThrows(SQLException.class, () -> execute("UPDATE npc_training_grants SET money_basis_points=501"));
        assertThrows(SQLException.class, () -> execute("UPDATE npc_training_grants SET xp_basis_points=1001"));
        assertThrows(SQLException.class, () -> execute("UPDATE npc_training_grants SET expires_at_millis=accepted_at_millis+1800001"));
        assertThrows(SQLException.class, () -> execute("UPDATE npc_training_grants SET job_name='Miner'"));
        assertThrows(SQLException.class, () -> execute("DELETE FROM npc_resident_profiles"));
        assertThrows(SQLException.class, () -> execute("""
                INSERT INTO npc_training_grants SELECT UNHEX(REPLACE(UUID(),'-','')), player_id, trainer_id,
                job_name, UNHEX(REPLACE(UUID(),'-','')), accepted_at_millis, economy_day, expires_at_millis,
                money_basis_points, xp_basis_points FROM npc_training_grants LIMIT 1
                """));
    }

    @SuppressWarnings("removal")
    @Test void hostSqlRunsOffMainEventsUseCacheAndEveryCurrentGateRevokes() throws Exception {
        introduce(); AtomicInteger sqlCalls = new AtomicInteger(); Thread main = Thread.currentThread();
        TrainingRepository tracked = new TrainingRepository(() -> {
            assertNotSame(main, Thread.currentThread(), "runtime SQL on main"); sqlCalls.incrementAndGet();
            return db.getConnection();
        });
        try (Host host = new Host(tracked)) {
            assertEquals(2, host.executors.size());
            AtomicReference<TrainingRepository.Result> reply = new AtomicReference<>();
            host.runtime.request(host.login, trainer, "Miner", reply::set);
            host.pump(() -> reply.get() != null); assertEquals(Code.GRANTED, reply.get().code());
            int before = sqlCalls.get();
            assertEquals(105, host.money(100)); assertEquals(110, host.xp(100), .000001);
            assertEquals(before, sqlCalls.get(), "payout seams must not query SQL");
            host.config.set("town.training-enabled", false); assertEquals(100, host.money(100));
            host.config.set("town.training-enabled", true);
            host.config.set("switches.economy-enabled", false); assertEquals(100, host.xp(100));
            host.config.set("switches.economy-enabled", true);
            host.config.set("switches.system-enabled", false); assertEquals(100, host.money(100));
            host.config.set("switches.system-enabled", true);
            host.gates.set(false); assertEquals(100, host.money(100)); host.gates.set(true);
            host.permission.set(false); assertEquals(100, host.money(100)); host.permission.set(true);
            host.employment.set(false); assertEquals(100, host.xp(100)); host.employment.set(true);
            host.online.set(false); assertEquals(100, host.money(100)); host.online.set(true);
            host.runtime.quit(new PlayerQuitEvent(host.login, "test"));
            assertEquals(100, host.money(100));
            AtomicReference<TrainingRuntime.View> view = new AtomicReference<>();
            host.runtime.view(host.login, view::set); host.pump(() -> view.get() != null);
            assertEquals(TrainingRuntime.Status.ACTIVE, view.get().status()); assertEquals(105, host.money(100));
            assertEquals(reply.get().grant(), view.get().grant(), "recovery must not extend the grant");
        }
    }

    @Test void disabledDefaultAndMissingEmploymentNeverIssueGrant() throws Exception {
        introduce();
        try (Host host = new Host(repository)) {
            AtomicReference<TrainingRepository.Result> reply = new AtomicReference<>();
            host.config.set("town.training-enabled", null);
            assertTrue(host.runtime.employed(player, "Miner"), "offer inspection is independent of training enablement");
            assertFalse(host.runtime.enabled());
            host.runtime.request(host.login, trainer, "miner", reply::set); assertEquals(Code.DISABLED, reply.get().code());
            host.config.set("town.training-enabled", true); host.employment.set(false);
            host.runtime.request(host.login, trainer, "miner", reply::set); assertEquals(Code.NOT_EMPLOYED, reply.get().code());
            host.employment.set(true); host.nearby.set(false);
            host.runtime.request(host.login, trainer, "miner", reply::set); assertEquals(Code.UNAUTHORIZED, reply.get().code());
            assertEquals(0, scalar("SELECT COUNT(*) FROM npc_training_grants"));
        }
    }

    @Test void uncertainCommitPublishesNoBenefitAndExplicitRecoveryUsesOriginalGrant() throws Exception {
        introduce(); AtomicBoolean lose = new AtomicBoolean(true);
        try (Host host = new Host(new TrainingRepository(() -> lostCommit(db.getConnection(), lose)))) {
            AtomicReference<TrainingRepository.Result> reply = new AtomicReference<>();
            host.runtime.request(host.login, trainer, "miner", reply::set); host.pump(() -> reply.get() != null);
            assertEquals(Code.UNCERTAIN, reply.get().code());
            assertEquals(TrainingRuntime.Status.UNCERTAIN, host.runtime.current(player).status());
            assertEquals(100, host.money(100));
            TrainingGrant persisted = repository.currentForPlayer(player).orElseThrow();
            AtomicReference<TrainingRuntime.View> recovered = new AtomicReference<>();
            host.runtime.view(host.login, recovered::set); host.pump(() -> recovered.get() != null);
            assertEquals(Optional.of(persisted), recovered.get().grant()); assertEquals(105, host.money(100));
        }
    }

    @Test void closeRevokesImmediatelyAndDrainWaitsForInflightSql() throws Exception {
        introduce(); CountDownLatch entered = new CountDownLatch(1); CountDownLatch release = new CountDownLatch(1);
        Thread main = Thread.currentThread();
        TrainingRepository stalled = new TrainingRepository(() -> {
            assertNotSame(main, Thread.currentThread()); entered.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new SQLException("fixture stalled"); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new SQLException(failure); }
            return db.getConnection();
        });
        try (Host host = new Host(stalled)) {
            AtomicInteger callbacks = new AtomicInteger();
            host.runtime.request(host.login, trainer, "miner", ignored -> callbacks.incrementAndGet());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var drain = host.runtime.closeAsync().toCompletableFuture();
            assertFalse(drain.isDone()); assertEquals(100, host.money(100));
            release.countDown(); drain.get(10, TimeUnit.SECONDS);
            host.tick.run(); assertEquals(0, callbacks.get());
            assertEquals(1, scalar("SELECT COUNT(*) FROM npc_training_grants"));
            assertEquals(100, host.money(100));
        } finally { release.countDown(); }
    }

    private void introduce() throws SQLException { residents.introduce(trainer, player, AT.minusSeconds(1), DAY); }
    private TrainingGrant grant() throws SQLException { introduce(); return request(repository, player, trainer, "miner", AT, DAY).grant().orElseThrow(); }
    private static TrainingRepository.Result request(TrainingRepository repo, UUID player, UUID trainer,
                                                     String job, Instant at, LocalDate day) throws SQLException {
        return repo.request(player, trainer, job, UUID.randomUUID(), at, day);
    }

    private static Connection lostCommit(Connection physical, AtomicBoolean lose) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] { Connection.class },
                (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(physical, args);
                        if (method.getName().equals("commit") && lose.compareAndSet(true, false)) {
                            throw new SQLException("lost training commit acknowledgment", "08006");
                        }
                        return result;
                    } catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
    }

    private static <T> List<T> concurrently(Callable<T> left, Callable<T> right) throws Exception {
        var workers = Executors.newFixedThreadPool(2); CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
        try {
            var a = workers.submit(() -> { ready.countDown(); assertTrue(go.await(10, TimeUnit.SECONDS)); return left.call(); });
            var b = workers.submit(() -> { ready.countDown(); assertTrue(go.await(10, TimeUnit.SECONDS)); return right.call(); });
            assertTrue(ready.await(10, TimeUnit.SECONDS)); go.countDown();
            return List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        } finally { go.countDown(); workers.shutdownNow(); assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS)); }
    }

    private static void awaitGuardInsert() throws Exception {
        // ensurePlayer's duplicate INSERT waits on this row before request can reach SELECT FOR UPDATE.
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < until) {
            if (scalar("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE ID<>CONNECTION_ID() AND DB='" + SCHEMA
                    + "' AND INFO LIKE '%INSERT INTO npc_training_players%'") > 0) return;
            Thread.sleep(20);
        }
        fail("writer never reached player guard INSERT");
    }

    private static void apply(String resource) throws Exception {
        try (var in = TrainingRepositoryIT.class.getResourceAsStream(resource)) {
            assertNotNull(in);
            for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8))) execute(sql);
        }
    }
    private static void execute(String sql) throws SQLException {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) { ps.execute(); }
    }
    private static long scalar(String sql) throws SQLException {
        try (Connection c = db.getConnection(); PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next()); return rs.getLong(1);
        }
    }

    private final class Host implements AutoCloseable {
        final AtomicBoolean online = new AtomicBoolean(true), permission = new AtomicBoolean(true),
                employment = new AtomicBoolean(true), gates = new AtomicBoolean(true), nearby = new AtomicBoolean(true);
        final YamlConfiguration config = new YamlConfiguration();
        final Map<Class<?>, EventExecutor> executors = new HashMap<>();
        final Player login;
        final TrainingRuntime runtime;
        final Server server;
        final Object oldServer;
        Runnable tick;

        Host(TrainingRepository repo) throws Exception {
            Thread owner = Thread.currentThread();
            config.set("town.training-enabled", true); config.set("switches.system-enabled", true); config.set("switches.economy-enabled", true);
            login = proxy(Player.class, (p, m, a) -> switch (m.getName()) {
                case "getUniqueId" -> player;
                case "isOnline" -> online.get();
                case "hasPermission" -> permission.get();
                default -> defaultValue(m.getReturnType());
            });
            AtomicReference<Plugin> jobsPlugin = new AtomicReference<>();
            var manager = proxy(PluginManager.class, (p, m, a) -> {
                if (m.getName().equals("getPlugin")) return jobsPlugin.get();
                if (m.getName().equals("registerEvent")) {
                    assertEquals(EventPriority.NORMAL, a[2]); assertEquals(true, a[5]);
                    assertNull(executors.put((Class<?>) a[0], (EventExecutor) a[3]));
                }
                return defaultValue(m.getReturnType());
            });
            BukkitTask task = proxy(BukkitTask.class, (p, m, a) -> defaultValue(m.getReturnType()));
            var scheduler = proxy(BukkitScheduler.class, (p, m, a) -> {
                if (m.getName().equals("runTaskTimer")) { assertNull(tick); tick = (Runnable) a[1]; return task; }
                return defaultValue(m.getReturnType());
            });
            server = proxy(Server.class, (p, m, a) -> switch (m.getName()) {
                case "isPrimaryThread" -> Thread.currentThread() == owner;
                case "getPluginManager" -> manager;
                case "getScheduler" -> scheduler;
                case "getPlayer" -> a[0].equals(player) && online.get() ? login : null;
                case "getOnlinePlayers" -> List.of();
                default -> defaultValue(m.getReturnType());
            });
            Plugin plugin = proxy(Plugin.class, (p, m, a) -> switch (m.getName()) {
                case "getServer" -> server;
                case "getConfig" -> config;
                case "isEnabled" -> true;
                default -> defaultValue(m.getReturnType());
            });
            jobsPlugin.set(plugin);
            var serverField = Bukkit.class.getDeclaredField("server"); serverField.setAccessible(true);
            oldServer = serverField.get(null); serverField.set(null, server);
            try {
                runtime = new TrainingRuntime(plugin, repo, new EconomyClock(Clock.fixed(AT, ZoneId.of("UTC")),
                        ZoneId.of("America/Chicago"), 4), gates::get, (p, t) -> nearby.get() && trainer.equals(t),
                        Clock.fixed(AT, ZoneId.of("UTC")), new TrainingJobsAccess() {
                    public Plugin plugin() { return plugin; }
                    public TrainingJobsAdapter.EventBinding money() { try { return TrainingEventsTest.binding(false); } catch (Exception e) { throw new AssertionError(e); } }
                    public TrainingJobsAdapter.EventBinding xp() { try { return TrainingEventsTest.binding(true); } catch (Exception e) { throw new AssertionError(e); } }
                    public Optional<String> employed(UUID id, String job) { return employment.get() && id.equals(player)
                            && TrainingGrant.canonicalJob(job).equals("miner") ? Optional.of("miner") : Optional.empty(); }
                });
            } catch (Exception | Error failure) { serverField.set(null, oldServer); throw failure; }
        }

        double money(double amount) throws Exception {
            var event = new TrainingEventsTest.Payment(player, "Miner", amount, 10);
            executors.get(event.getClass()).execute(runtime, event); return event.amount;
        }
        double xp(double amount) throws Exception {
            var event = new TrainingEventsTest.Experience(player, "Miner", amount);
            executors.get(event.getClass()).execute(runtime, event); return event.exp;
        }
        void pump(BooleanSupplier done) throws Exception {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!done.getAsBoolean() && System.nanoTime() < until) { tick.run(); Thread.sleep(10); }
            assertTrue(done.getAsBoolean(), "main-thread delivery did not arrive");
        }
        @Override public void close() {
            try { runtime.closeAsync().toCompletableFuture().get(15, TimeUnit.SECONDS); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            catch (Exception failure) { throw new AssertionError(failure); }
            finally {
                try {
                    var field = Bukkit.class.getDeclaredField("server"); field.setAccessible(true); field.set(null, oldServer);
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            }
        }
    }

    @FunctionalInterface private interface Invocation {
        Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable;
    }
    private static <T> T proxy(Class<T> type, Invocation action) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (p, m, a) -> {
            if (m.getDeclaringClass() == Object.class) return switch (m.getName()) {
                case "hashCode" -> System.identityHashCode(p);
                case "equals" -> p == a[0];
                default -> type.getSimpleName();
            };
            return action.invoke(p, m, a);
        }));
    }
    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == float.class) return 0f;
        return null;
    }
}

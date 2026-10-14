package com.prosperitysmp.npc.it;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.db.MigrationRunner;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.resident.RelationshipMemory;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentRepository;
import com.prosperitysmp.npc.town.TownTask;
import com.prosperitysmp.npc.town.TownTaskDefinition;
import com.prosperitysmp.npc.town.TownTaskRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.prosperitysmp.npc.town.TownTaskRepository.Code;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Isolated real MariaDB V2/V19 tests. No runtime wiring, proximity adapter, or production database. */
@Timeout(45)
class TownTaskRepositoryIT {
    private static final String SCHEMA = "prosperity_npc_town_tasks";
    private static final Instant AT = Instant.parse("2026-09-29T12:00:00.123456789Z");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 29);
    private static DB mariaDb;
    private static Database database;
    private static DatabaseSettings settings;
    private TownTaskRepository tasks;
    private ResidentRepository residents;
    private UUID player;
    private UUID giver;
    private UUID recipient;
    private TownTaskDefinition definition;

    @BeforeAll
    static void bootDatabase() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        mariaDb = DB.newEmbeddedDB(cfg.addArg("--bind-address=127.0.0.1").build());
        try {
            mariaDb.start();
            mariaDb.createDB(SCHEMA);
            settings = new DatabaseSettings("localhost", cfg.getPort(), SCHEMA, "root", "", 8, 10_000L);
            database = new Database();
            database.connect(settings);
            apply("/db/migration/V2__resident_identity.sql");
            apply("/db/migration/V19__town_tasks.sql");
            apply("/db/migration/V19__town_tasks.sql");
        } catch (Exception | AssertionError failure) {
            try { stopDatabase(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @AfterAll
    static void stopDatabase() throws Exception {
        try {
            if (database != null) database.close();
        } finally {
            if (mariaDb != null) {
                DB stopping = mariaDb;
                mariaDb = null;
                stopping.stop();
            }
        }
    }

    @BeforeEach
    void freshTables() throws SQLException {
        dropFaultTriggers();
        execute("DELETE FROM npc_town_tasks");
        execute("DELETE FROM npc_town_task_players");
        execute("DELETE FROM npc_resident_memory");
        execute("DELETE FROM npc_resident_profiles");
        tasks = new TownTaskRepository(database);
        residents = new ResidentRepository(database);
        player = UUID.randomUUID();
        // Signed UUID order intentionally differs from unsigned BINARY(16) ordering.
        giver = UUID.fromString("80000000-0000-0000-0000-000000000001");
        recipient = UUID.fromString("00000000-0000-0000-0000-000000000002");
        residents.register(ResidentCatalogue.draft(giver, "Mara Vale"));
        residents.register(ResidentCatalogue.draft(recipient, "Ellis Brook"));
        definition = definition("sealed-note", giver, recipient, RelationshipMemory.Stage.STRANGER);
    }

    @AfterEach
    void dropFaultTriggers() throws SQLException {
        if (database != null && database.isConnected()) {
            execute("DROP TRIGGER IF EXISTS town_memory_failure");
            execute("DROP TRIGGER IF EXISTS town_guard_failure");
        }
    }

    @Test
    void validatesBothPersistentIdentitiesAndCurrentGiverStage() throws SQLException {
        assertEquals(Code.UNKNOWN_GIVER, tasks.accept(definition("missing", UUID.randomUUID(), recipient,
                RelationshipMemory.Stage.STRANGER), player, AT, DAY).code());
        assertEquals(Code.UNKNOWN_RECIPIENT, tasks.accept(definition("missing", giver, UUID.randomUUID(),
                RelationshipMemory.Stage.STRANGER), player, AT, DAY).code());
        TownTaskDefinition gated = definition("gated", giver, recipient, RelationshipMemory.Stage.ACQUAINTANCE);
        assertEquals(Code.RELATIONSHIP_TOO_LOW, tasks.accept(gated, player, AT, DAY).code());
        assertEquals(0, scalar("SELECT COUNT(*) FROM npc_town_tasks"));
        residents.introduce(giver, player, AT.minusSeconds(1), DAY);
        assertEquals(Code.ACCEPTED, tasks.accept(gated, player, AT, DAY).code());
        assertEquals(1, scalar("SELECT COUNT(*) FROM npc_resident_memory"), "acceptance awards nothing");
    }

    @Test
    void higherMinimumStageUsesExistingRelationshipReplayNotRecipientTrust() throws SQLException {
        TownTaskDefinition gated = definition("trusted", giver, recipient, RelationshipMemory.Stage.TRUSTED);
        residents.introduce(recipient, player, AT.minusSeconds(1), DAY);
        assertEquals(Code.RELATIONSHIP_TOO_LOW, tasks.accept(gated, player, AT, DAY).code());
        residents.introduce(giver, player, AT.minusSeconds(4 * 86400L), DAY.minusDays(4));
        for (int i = 3; i >= 1; i--) {
            residents.appendVerified(new RelationshipMemory.Event(giver, player, "repair/" + i,
                    RelationshipMemory.Kind.REPAIR_COMPLETED, AT.minusSeconds(i * 86400L), DAY.minusDays(i)));
        }
        assertEquals(RelationshipMemory.Stage.TRUSTED, residents.readRelationship(giver, player).stage());
        assertEquals(Code.ACCEPTED, tasks.accept(gated, player, AT, DAY).code());
    }

    @Test
    void activeReplayKeepsEverySnapshotFieldAndDifferentDefinitionDenies() throws SQLException {
        TownTask task = accept();
        TownTaskDefinition rewritten = new TownTaskDefinition(definition.id(), "Changed title", "Changed note",
                recipient, giver, RelationshipMemory.Stage.ESTABLISHED, 1440);
        var replay = tasks.accept(rewritten, player, AT.plusSeconds(1), DAY.plusDays(1));
        assertEquals(Code.ALREADY_ACTIVE, replay.code());
        assertEquals(Optional.of(task), replay.task());
        assertEquals(Code.ACTIVE_CONFLICT, tasks.accept(definition("other", giver, recipient,
                RelationshipMemory.Stage.STRANGER), player, AT, DAY).code());
        assertEquals(List.of(task), tasks.list(player, AT, 10));
        assertTrue(tasks.history(player, AT, 10).isEmpty());
        assertEquals(1, scalar("SELECT COUNT(*) FROM npc_town_tasks"));
    }

    @Test
    void completesWithTwoExactMemoriesAndReplayNeverAwardsAgain() throws SQLException {
        TownTask task = accept();
        var done = tasks.complete(task.taskId(), player, recipient, AT.plusSeconds(1), DAY);
        assertEquals(Code.COMPLETED, done.code());
        assertEquals(TownTask.Status.COMPLETED, done.task().orElseThrow().status());
        for (UUID resident : List.of(giver, recipient)) {
            var state = residents.readRelationship(resident, player);
            assertEquals(3, state.reliability());
            assertEquals(List.of(new RelationshipMemory.Event(resident, player, "town-task:" + task.taskId(),
                    RelationshipMemory.Kind.OBLIGATION_KEPT, Instant.ofEpochMilli(AT.plusSeconds(1).toEpochMilli()), DAY)),
                    state.recent());
        }
        assertEquals(done.task(), tasks.complete(task.taskId(), player, recipient,
                AT.plusSeconds(2000), DAY.plusDays(1)).task());
        assertEquals(Code.ALREADY_COMPLETED, tasks.complete(task.taskId(), player, recipient,
                AT.plusSeconds(2000), DAY.plusDays(1)).code());
        assertEquals(2, scalar("SELECT COUNT(*) FROM npc_resident_memory"));
        assertEquals(Code.DAILY_LIMIT, tasks.accept(definition, player, AT.plusSeconds(2), DAY).code());
        assertTrue(tasks.current(player, AT.plusSeconds(2)).isEmpty());
        assertEquals(Code.NOT_ACTIVE, tasks.cancel(task.taskId(), player, AT.plusSeconds(2)).code());
    }

    @Test
    void foreignAccountAndWrongRecipientCannotMutateOrReplay() throws SQLException {
        TownTask task = accept();
        UUID foreign = UUID.randomUUID();
        assertEquals(Code.NOT_FOUND, tasks.complete(task.taskId(), foreign, recipient, AT, DAY).code());
        assertEquals(Code.NOT_FOUND, tasks.cancel(task.taskId(), foreign, AT).code());
        tasks.accept(definition, foreign, AT, DAY); // A real, separate guard must not authorize the task either.
        assertEquals(Code.NOT_FOUND, tasks.complete(task.taskId(), foreign, recipient, AT, DAY).code());
        assertEquals(Code.WRONG_RECIPIENT, tasks.complete(task.taskId(), player, giver, AT, DAY).code());
        assertEquals(Optional.of(task), tasks.current(player, AT));
        assertEquals(0, scalar("SELECT COUNT(*) FROM npc_resident_memory"));
        assertEquals(Code.NOT_FOUND, tasks.complete(UUID.randomUUID(), player, recipient, AT, DAY).code());
        assertEquals(Code.COMPLETED, tasks.complete(task.taskId(), player, recipient, AT, DAY).code());
        assertEquals(Code.WRONG_RECIPIENT, tasks.complete(task.taskId(), player, giver, AT, DAY).code());
        assertEquals(2, scalar("SELECT COUNT(*) FROM npc_resident_memory"));
    }

    @Test
    void cancellationConsumesDayWithoutTrustPenaltyAndNextDayCanAccept() throws SQLException {
        TownTask task = accept();
        assertEquals(Code.CANCELLED, tasks.cancel(task.taskId(), player, AT.plusSeconds(1)).code());
        assertEquals(Code.ALREADY_CANCELLED, tasks.cancel(task.taskId(), player, AT.plusSeconds(2)).code());
        assertEquals(Code.NOT_ACTIVE, tasks.complete(task.taskId(), player, recipient, AT.plusSeconds(2), DAY).code());
        assertEquals(Code.DAILY_LIMIT, tasks.accept(definition, player, AT.plusSeconds(3), DAY).code());
        assertEquals(Code.DAILY_LIMIT, tasks.accept(definition("other", recipient, giver,
                RelationshipMemory.Stage.STRANGER), player, AT.plusSeconds(3), DAY).code());
        assertEquals(0, scalar("SELECT COUNT(*) FROM npc_resident_memory"));
        assertEquals(Code.ACCEPTED, tasks.accept(definition, player, AT.plusSeconds(86400), DAY.plusDays(1)).code());
    }

    @Test
    void expiryIsInclusiveDurableAndNeverPunishesOfflinePlayer() throws SQLException {
        TownTask task = accept();
        assertEquals(Optional.of(task), tasks.current(player, task.expiresAt().minusMillis(1)));
        assertEquals(Code.EXPIRED, tasks.complete(task.taskId(), player, recipient, task.expiresAt(), DAY).code());
        assertEquals(Code.EXPIRED, tasks.cancel(task.taskId(), player, task.expiresAt().plusSeconds(1)).code());
        assertTrue(tasks.current(player, task.expiresAt()).isEmpty());
        TownTask expired = tasks.history(player, task.expiresAt(), 10).getFirst();
        assertEquals(TownTask.Status.EXPIRED, expired.status());
        assertEquals(task.expiresAt(), expired.closedAt());
        assertEquals(Code.DAILY_LIMIT, tasks.accept(definition, player, task.expiresAt(), DAY).code());
        assertEquals(0, scalar("SELECT COUNT(*) FROM npc_resident_memory"));
    }

    @Test
    void lazyExpiryOnListAndAcceptRetainsOriginalDeadline() throws SQLException {
        TownTask task = accept();
        TownTask expired = tasks.list(player, AT.plusSeconds(86400), 10).getFirst();
        assertEquals(TownTask.Status.EXPIRED, expired.status());
        assertEquals(task.expiresAt(), expired.closedAt());
        TownTask next = tasks.accept(definition, player, AT.plusSeconds(86400), DAY.plusDays(1)).task().orElseThrow();
        assertEquals(Code.ACCEPTED, tasks.accept(definition, UUID.randomUUID(), AT, DAY).code());
        assertTrue(tasks.current(player, next.expiresAt()).isEmpty());
        assertEquals(2, tasks.history(player, next.expiresAt(), 100).size());
        assertEquals(0, scalar("SELECT COUNT(*) FROM npc_resident_memory"));
    }

    @Test
    void expiryDuringAcceptanceAllowsNextDayButNotSameDay() throws SQLException {
        TownTask task = accept();
        assertEquals(Code.DAILY_LIMIT, tasks.accept(definition, player, task.expiresAt(), DAY).code());
        assertEquals(TownTask.Status.EXPIRED, tasks.history(player, task.expiresAt(), 1).getFirst().status());
        tasks.accept(definition, player, AT.plusSeconds(86400), DAY.plusDays(1));
        assertEquals(Code.ACCEPTED, tasks.accept(definition, player, AT.plusSeconds(172800), DAY.plusDays(2)).code());
    }

    @Test
    void regressedClockOrEconomyDayCannotCreateOrCompleteEvidence() throws SQLException {
        TownTask task = accept();
        assertEquals(Code.TIME_REGRESSION, tasks.complete(task.taskId(), player, recipient, AT.minusMillis(1), DAY).code());
        assertEquals(Code.TIME_REGRESSION, tasks.complete(task.taskId(), player, recipient, AT, DAY.minusDays(1)).code());
        assertEquals(Code.TIME_REGRESSION, tasks.cancel(task.taskId(), player, AT.minusMillis(1)).code());
        tasks.cancel(task.taskId(), player, AT);
        assertEquals(Code.TIME_REGRESSION, tasks.accept(definition, player, AT, DAY.minusDays(1)).code());
        assertEquals(Code.TIME_REGRESSION, tasks.accept(definition, player, AT.minusMillis(1), DAY.plusDays(1)).code());
        assertEquals(0, scalar("SELECT COUNT(*) FROM npc_resident_memory"));
    }

    @Test
    void poolAndRepositoryRestartRetainActiveSnapshotAndDailyLimit() throws SQLException {
        TownTask task = accept();
        database.close();
        database.connect(settings);
        tasks = new TownTaskRepository(database);
        residents = new ResidentRepository(database);
        assertEquals(Optional.of(task), tasks.current(player, AT.plusSeconds(1)));
        assertEquals(Code.ALREADY_ACTIVE, tasks.accept(definition, player, AT.plusSeconds(1), DAY).code());
        var done = tasks.complete(task.taskId(), player, recipient, AT.plusSeconds(1), DAY);
        database.close();
        database.connect(settings);
        tasks = new TownTaskRepository(database);
        assertEquals(done.task(), tasks.complete(task.taskId(), player, recipient, AT.plusSeconds(2), DAY).task());
        assertEquals(Code.DAILY_LIMIT, tasks.accept(definition, player, AT.plusSeconds(2), DAY).code());
        assertEquals(2, scalar("SELECT COUNT(*) FROM npc_resident_memory"));
    }

    @Test
    void parentLookupApiScopesTasksAndPersistsExpiry() throws SQLException {
        TownTask task = accept();
        assertEquals(Optional.of(task), tasks.currentForPlayer(player, AT));
        assertEquals(Optional.of(task), tasks.find(task.taskId(), player, AT));
        assertTrue(tasks.find(task.taskId(), UUID.randomUUID(), AT).isEmpty());
        assertTrue(tasks.find(UUID.randomUUID(), player, AT).isEmpty());
        TownTask expired = tasks.find(task.taskId(), player, task.expiresAt()).orElseThrow();
        assertEquals(TownTask.Status.EXPIRED, expired.status());
        assertEquals(task.expiresAt(), expired.closedAt());
        assertTrue(tasks.currentForPlayer(player, task.expiresAt()).isEmpty());
        assertEquals(Optional.of(expired), tasks.find(task.taskId(), player, AT.plusSeconds(86400)));
        assertEquals(0, scalar("SELECT COUNT(*) FROM npc_resident_memory"));
    }

    @Test
    void secondMemoryFailureRollsBackCompletionAndFirstMemory() throws SQLException {
        TownTask task = accept();
        String second = HexFormat.of().formatHex(Uuids.toBytes(recipient));
        execute("""
                CREATE TRIGGER town_memory_failure BEFORE INSERT ON npc_resident_memory FOR EACH ROW
                BEGIN
                  IF NEW.source_ref LIKE 'town-task:%%' AND NEW.resident_id=X'%s' THEN
                    IF (SELECT COUNT(*) FROM npc_resident_memory
                        WHERE source_ref=NEW.source_ref AND player_id=NEW.player_id) <> 1 THEN
                      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='first town memory was not present';
                    END IF;
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='second town memory fixture failure';
                  END IF;
                END
                """.formatted(second));
        SQLException failure = assertThrows(SQLException.class,
                () -> tasks.complete(task.taskId(), player, recipient, AT.plusSeconds(1), DAY));
        assertEquals("45000", failure.getSQLState());
        assertTrue(failure.getMessage().contains("second town memory fixture failure"));
        assertEquals(Optional.of(task), tasks.current(player, AT.plusSeconds(1)));
        assertEquals(0, scalar("SELECT COUNT(*) FROM npc_resident_memory"));
        execute("DROP TRIGGER town_memory_failure");
        assertEquals(Code.COMPLETED, tasks.complete(task.taskId(), player, recipient, AT.plusSeconds(1), DAY).code());
        assertEquals(2, scalar("SELECT COUNT(*) FROM npc_resident_memory"));
    }

    @Test
    void acceptanceFailureRollsBackTaskAndDailyReservation() throws SQLException {
        execute("""
                CREATE TRIGGER town_guard_failure BEFORE UPDATE ON npc_town_task_players FOR EACH ROW
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='town guard fixture failure'
                """);
        assertThrows(SQLException.class, this::accept);
        assertEquals(0, scalar("SELECT COUNT(*) FROM npc_town_tasks"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM npc_town_task_players WHERE last_accepted_day IS NOT NULL"));
        execute("DROP TRIGGER town_guard_failure");
        assertEquals(Code.ACCEPTED, tasks.accept(definition, player, AT, DAY).code());
    }

    @Test
    void conflictingMemoryPayloadRollsBackTaskAndOtherResidentsMemory() throws SQLException {
        TownTask task = accept();
        residents.appendVerified(new RelationshipMemory.Event(recipient, player, "town-task:" + task.taskId(),
                RelationshipMemory.Kind.OBLIGATION_KEPT, AT, DAY));
        assertThrows(SQLException.class, () -> tasks.complete(task.taskId(), player, recipient, AT.plusSeconds(1), DAY));
        assertEquals(Optional.of(task), tasks.current(player, AT));
        assertEquals(1, scalar("SELECT COUNT(*) FROM npc_resident_memory"));
        assertTrue(residents.readRelationship(giver, player).recent().isEmpty());
    }

    @Test
    void listAndHistoryArePlayerScopedImmutableAndHardBounded() throws SQLException {
        for (int i = 0; i < 105; i++) {
            Instant at = AT.plusSeconds(i * 86400L);
            TownTask task = tasks.accept(definition, player, at, DAY.plusDays(i)).task().orElseThrow();
            tasks.cancel(task.taskId(), player, at);
        }
        List<TownTask> history = tasks.history(player, AT.plusSeconds(105 * 86400L), Integer.MAX_VALUE);
        assertEquals(100, history.size());
        assertEquals(DAY.plusDays(104), history.getFirst().acceptedEconomyDay());
        assertEquals(DAY.plusDays(5), history.getLast().acceptedEconomyDay());
        assertEquals(history, tasks.list(player, AT.plusSeconds(105 * 86400L), 200));
        assertThrows(UnsupportedOperationException.class, () -> history.add(history.getFirst()));
        assertEquals(1, tasks.history(player, AT, 1).size());
        assertTrue(tasks.list(player, AT, 0).isEmpty());
        assertTrue(tasks.history(player, AT, -1).isEmpty());
        assertTrue(tasks.list(UUID.randomUUID(), AT, 100).isEmpty());
        assertTrue(tasks.current(UUID.randomUUID(), AT).isEmpty());
        assertEquals(1, scalar("SELECT COUNT(*) FROM npc_town_task_players"), "reads never fabricate accounts");
    }

    @Test
    void competingAcceptsAndCompletionsOnlyAwardOnce() throws Exception {
        List<Code> accepts = concurrently(() -> tasks.accept(definition, player, AT, DAY).code(),
                () -> tasks.accept(definition, player, AT, DAY).code());
        assertTrue(accepts.contains(Code.ACCEPTED));
        assertTrue(accepts.contains(Code.ALREADY_ACTIVE));
        TownTask task = tasks.current(player, AT).orElseThrow();
        List<Code> completions = concurrently(() -> tasks.complete(task.taskId(), player, recipient, AT, DAY).code(),
                () -> tasks.complete(task.taskId(), player, recipient, AT, DAY).code());
        assertTrue(completions.contains(Code.COMPLETED));
        assertTrue(completions.contains(Code.ALREADY_COMPLETED));
        assertEquals(1, scalar("SELECT COUNT(*) FROM npc_town_tasks"));
        assertEquals(2, scalar("SELECT COUNT(*) FROM npc_resident_memory"));
    }

    @Test
    void differentAcceptsAndCancelCompletionRaceHaveTruthfulSingleWinner() throws Exception {
        TownTaskDefinition other = definition("other", recipient, giver, RelationshipMemory.Stage.STRANGER);
        List<Code> accepts = concurrently(() -> tasks.accept(definition, player, AT, DAY).code(),
                () -> tasks.accept(other, player, AT, DAY).code());
        assertTrue(accepts.contains(Code.ACCEPTED));
        assertTrue(accepts.contains(Code.ACTIVE_CONFLICT));
        TownTask task = tasks.current(player, AT).orElseThrow();
        UUID target = task.definition().recipientId();
        List<Code> outcomes = concurrently(() -> tasks.complete(task.taskId(), player, target, AT, DAY).code(),
                () -> tasks.cancel(task.taskId(), player, AT).code());
        assertTrue(outcomes.equals(List.of(Code.COMPLETED, Code.NOT_ACTIVE))
                || outcomes.equals(List.of(Code.NOT_ACTIVE, Code.CANCELLED)));
        assertEquals(outcomes.contains(Code.COMPLETED) ? 2 : 0, scalar("SELECT COUNT(*) FROM npc_resident_memory"));
        assertEquals(Code.DAILY_LIMIT, tasks.accept(definition, player, AT, DAY).code());
    }

    @Test
    void completionActuallyWaitsOnDedicatedPlayerGuardBeforeTaskOrMemory() throws Exception {
        TownTask task = accept();
        var worker = Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<Code> pending;
            try (Connection blocker = database.getConnection()) {
                blocker.setAutoCommit(false);
                try {
                    lock(blocker, "SELECT player_id FROM npc_town_task_players WHERE player_id=? FOR UPDATE", player);
                    pending = worker.submit(() -> tasks.complete(task.taskId(), player, recipient, AT, DAY).code());
                    awaitQueries("%FROM npc_town_task_players WHERE player_id=%FOR UPDATE%", 1);
                    assertFalse(pending.isDone());
                    assertEquals(0, scalar("SELECT COUNT(*) FROM npc_resident_memory"));
                    assertEquals(1, scalar("SELECT COUNT(*) FROM npc_town_tasks WHERE status='ACTIVE'"));
                } finally {
                    blocker.rollback();
                }
            }
            assertEquals(Code.COMPLETED, pending.get(10, TimeUnit.SECONDS));
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void reversedErrandsForTwoPlayersTakeSameFirstResidentLockWithoutDeadlock() throws Exception {
        TownTask first = accept();
        UUID secondPlayer = UUID.randomUUID();
        TownTask second = tasks.accept(definition("reverse", recipient, giver, RelationshipMemory.Stage.STRANGER),
                secondPlayer, AT, DAY).task().orElseThrow();
        var workers = Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<Code> left;
            java.util.concurrent.Future<Code> right;
            try (Connection blocker = database.getConnection()) {
                blocker.setAutoCommit(false);
                try {
                    lock(blocker, "SELECT resident_id FROM npc_resident_profiles WHERE resident_id=? FOR UPDATE", giver);
                    left = workers.submit(() -> tasks.complete(first.taskId(), player, recipient, AT, DAY).code());
                    right = workers.submit(() -> tasks.complete(second.taskId(), secondPlayer, giver, AT, DAY).code());
                    awaitQueries("%SELECT resident_id FROM npc_resident_profiles%FOR UPDATE%", 2);
                    assertFalse(left.isDone());
                    assertFalse(right.isDone());
                    assertEquals(0, scalar("SELECT COUNT(*) FROM npc_resident_memory"));
                } finally {
                    blocker.rollback();
                }
            }
            assertEquals(Code.COMPLETED, left.get(10, TimeUnit.SECONDS));
            assertEquals(Code.COMPLETED, right.get(10, TimeUnit.SECONDS));
            assertEquals(4, scalar("SELECT COUNT(*) FROM npc_resident_memory"));
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void sqlConstraintsDefendActiveDailySlotsAndResidentIndependence() throws SQLException {
        TownTask task = accept();
        String columns = "task_id, player_id, definition_id, title, description, giver_id, recipient_id, minimum_stage, "
                + "duration_minutes, accepted_at_millis, accepted_economy_day, expires_at_millis, status";
        String copy = "INSERT INTO npc_town_tasks (" + columns + ") SELECT UNHEX(REPLACE(UUID(),'-','')), "
                + "player_id, definition_id, title, description, giver_id, recipient_id, minimum_stage, "
                + "duration_minutes, accepted_at_millis, %s, expires_at_millis, 'ACTIVE' FROM npc_town_tasks LIMIT 1";
        assertThrows(SQLException.class, () -> execute(copy.formatted("'2026-09-30'")), "one active task enforced by SQL");
        tasks.cancel(task.taskId(), player, AT);
        assertThrows(SQLException.class, () -> execute(copy.formatted("accepted_economy_day")), "cancel retains the SQL daily slot");
        assertThrows(SQLException.class, () -> execute("DELETE FROM npc_resident_profiles"));
        assertThrows(SQLException.class, () -> execute("UPDATE npc_town_tasks SET recipient_id=giver_id"));
        assertThrows(SQLException.class, () -> execute("UPDATE npc_town_tasks SET definition_id='UPPER'"));
        assertThrows(SQLException.class, () -> execute("UPDATE npc_town_tasks SET duration_minutes=4"));
        assertThrows(SQLException.class, () -> execute("UPDATE npc_town_tasks SET status='COMPLETED'"));
        assertEquals(2, residents.list(10).size());
    }

    private TownTask accept() throws SQLException {
        var accepted = tasks.accept(definition, player, AT, DAY);
        assertEquals(Code.ACCEPTED, accepted.code());
        return accepted.task().orElseThrow();
    }

    private static TownTaskDefinition definition(String id, UUID giver, UUID recipient, RelationshipMemory.Stage stage) {
        return new TownTaskDefinition(id, "A sealed note", "Carry this sealed note to my neighbor.", giver, recipient, stage, 5);
    }

    private static <T> List<T> concurrently(Callable<T> left, Callable<T> right) throws Exception {
        var workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var a = workers.submit(() -> { ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS)); return left.call(); });
            var b = workers.submit(() -> { ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS)); return right.call(); });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    private static void lock(Connection c, String sql, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setBytes(1, Uuids.toBytes(id));
            try (ResultSet rs = ps.executeQuery()) { assertTrue(rs.next()); }
        }
    }

    private static void awaitQueries(String pattern, int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement("""
                    SELECT COUNT(*) FROM information_schema.PROCESSLIST
                    WHERE ID<>CONNECTION_ID() AND DB=? AND INFO LIKE ?
                    """)) {
                ps.setString(1, SCHEMA);
                ps.setString(2, pattern);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    if (rs.getInt(1) >= count) return;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("writers did not reach expected SQL lock: " + pattern);
    }

    private static void apply(String path) throws Exception {
        try (InputStream in = TownTaskRepositoryIT.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            for (String sql : MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8))) execute(sql);
        }
    }

    private static long scalar(String sql) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private static void execute(String sql) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.execute();
        }
    }
}

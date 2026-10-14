package com.prosperitysmp.npc.town;

import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.resident.RelationshipMemory;
import com.prosperitysmp.npc.resident.ResidentRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable nonfinancial errands, with no Bukkit or financial integration.
 * Lock order: dedicated player guard -> task -> sorted resident profiles -> resident memory.
 * Never invoke while holding a resident lock; this sidecar never acquires property/economy locks.
 * All calls do bounded synchronous JDBC work and belong on the existing database worker.
 */
public final class TownTaskRepository {
    public static final int MAX_LIST = 100;
    public enum Code {
        ACCEPTED, ALREADY_ACTIVE, ACTIVE_CONFLICT, DAILY_LIMIT, RELATIONSHIP_TOO_LOW,
        UNKNOWN_GIVER, UNKNOWN_RECIPIENT, COMPLETED, ALREADY_COMPLETED, CANCELLED,
        ALREADY_CANCELLED, EXPIRED, NOT_ACTIVE, NOT_FOUND, WRONG_RECIPIENT, TIME_REGRESSION
    }
    /** Empty task for missing/foreign tasks or acceptance denials without a current task. */
    public record Result(Code code, Optional<TownTask> task) {
        public Result {
            Objects.requireNonNull(code);
            Objects.requireNonNull(task);
        }
    }

    private static final String COLUMNS = "task_id, player_id, definition_id, title, description, giver_id, "
            + "recipient_id, minimum_stage, duration_minutes, accepted_at_millis, accepted_economy_day, "
            + "expires_at_millis, status, closed_at_millis, completed_economy_day";
    private final Database database;

    public TownTaskRepository(Database database) {
        this.database = Objects.requireNonNull(database);
    }

    /**
     * Trusted authored definition and server time/day only. An active same-ID retry retains its original
     * snapshot; cancelled/completed/expired tasks still consume the accepted economy day's slot.
     */
    public Result accept(TownTaskDefinition definition, UUID player, Instant at, LocalDate economyDay)
            throws SQLException {
        Objects.requireNonNull(definition);
        Objects.requireNonNull(player);
        Objects.requireNonNull(economyDay);
        Instant now = TownTask.normalize(at);
        Instant expires = TownTask.normalize(now.plusSeconds(definition.durationMinutes() * 60L));
        ensurePlayer(player);
        return inTransaction(c -> {
            PlayerGuard guard = lockPlayer(c, player);
            if (guard == null) throw new SQLException("town task player guard disappeared");
            Optional<TownTask> active = expireActive(c, player, now);
            if (active.isPresent()) {
                TownTask existing = active.orElseThrow();
                return result(existing.definition().id().equals(definition.id())
                        ? Code.ALREADY_ACTIVE : Code.ACTIVE_CONFLICT, existing);
            }
            if (economyDay.equals(guard.lastDay())) return result(Code.DAILY_LIMIT);
            if (guard.lastDay() != null && (economyDay.isBefore(guard.lastDay()) || now.isBefore(guard.lastAt()))) {
                return result(Code.TIME_REGRESSION);
            }
            Code missing = lockResidents(c, definition);
            if (missing != null) return result(missing);
            if (readGiverStage(c, definition.giverId(), player).ordinal() < definition.minimumStage().ordinal()) {
                return result(Code.RELATIONSHIP_TOO_LOW);
            }
            TownTask task = new TownTask(UUID.randomUUID(), player, definition, now, economyDay, expires,
                    TownTask.Status.ACTIVE, null, null);
            insertTask(c, task);
            try (PreparedStatement ps = prepare(c, """
                    UPDATE npc_town_task_players SET last_accepted_day=?, last_accepted_at_millis=? WHERE player_id=?
                    """)) {
                ps.setString(1, economyDay.toString());
                ps.setLong(2, now.toEpochMilli());
                ps.setBytes(3, Uuids.toBytes(player));
                requireOne(ps.executeUpdate());
            }
            return result(Code.ACCEPTED, task);
        });
    }

    /**
     * INTERNAL trusted nearby-avatar adapter only. The main-thread adapter MUST validate a live session,
     * real player proximity to the recipient avatar, persistent resident identity and server time/day
     * BEFORE dispatching this JDBC work. UUID equality here is not proximity/session authorization.
     * Never expose directly to a player command, client endpoint, or untrusted dialogue input.
     * A successful completion and both OBLIGATION_KEPT memories commit atomically. Owned, correct-recipient
     * terminal replays retain their original time/day and never append memory again.
     */
    public Result complete(UUID taskId, UUID player, UUID actualRecipient, Instant at, LocalDate economyDay)
            throws SQLException {
        Objects.requireNonNull(taskId);
        Objects.requireNonNull(player);
        Objects.requireNonNull(actualRecipient);
        Objects.requireNonNull(economyDay);
        Instant now = TownTask.normalize(at);
        return inTransaction(c -> {
            if (lockPlayer(c, player) == null) return result(Code.NOT_FOUND);
            Optional<TownTask> found = findLocked(c, taskId, player);
            if (found.isEmpty()) return result(Code.NOT_FOUND);
            TownTask task = found.orElseThrow();
            if (!task.definition().recipientId().equals(actualRecipient)) return result(Code.WRONG_RECIPIENT, task);
            if (task.status() == TownTask.Status.COMPLETED) return result(Code.ALREADY_COMPLETED, task);
            if (task.status() == TownTask.Status.EXPIRED) return result(Code.EXPIRED, task);
            if (task.status() != TownTask.Status.ACTIVE) return result(Code.NOT_ACTIVE, task);
            if (!now.isBefore(task.expiresAt())) {
                return result(Code.EXPIRED, closeTask(c, task, TownTask.Status.EXPIRED, task.expiresAt(), null));
            }
            if (now.isBefore(task.acceptedAt()) || economyDay.isBefore(task.acceptedEconomyDay())) {
                return result(Code.TIME_REGRESSION, task);
            }
            Code missing = lockResidents(c, task.definition());
            if (missing != null) return result(missing, task);
            TownTask completed = closeTask(c, task, TownTask.Status.COMPLETED, now, economyDay);
            String source = "town-task:" + task.taskId();
            for (UUID resident : sortedResidents(task.definition())) {
                ResidentRepository.appendVerified(c, new RelationshipMemory.Event(resident, player, source,
                        RelationshipMemory.Kind.OBLIGATION_KEPT, now, economyDay));
            }
            return result(Code.COMPLETED, completed);
        });
    }

    /** Cancellation and logical expiry never write negative (or positive) relationship evidence. */
    public Result cancel(UUID taskId, UUID player, Instant at) throws SQLException {
        Objects.requireNonNull(taskId);
        Objects.requireNonNull(player);
        Instant now = TownTask.normalize(at);
        return inTransaction(c -> {
            if (lockPlayer(c, player) == null) return result(Code.NOT_FOUND);
            Optional<TownTask> found = findLocked(c, taskId, player);
            if (found.isEmpty()) return result(Code.NOT_FOUND);
            TownTask task = found.orElseThrow();
            if (task.status() == TownTask.Status.CANCELLED) return result(Code.ALREADY_CANCELLED, task);
            if (task.status() == TownTask.Status.EXPIRED) return result(Code.EXPIRED, task);
            if (task.status() != TownTask.Status.ACTIVE) return result(Code.NOT_ACTIVE, task);
            if (!now.isBefore(task.expiresAt())) {
                return result(Code.EXPIRED, closeTask(c, task, TownTask.Status.EXPIRED, task.expiresAt(), null));
            }
            if (now.isBefore(task.acceptedAt())) return result(Code.TIME_REGRESSION, task);
            return result(Code.CANCELLED, closeTask(c, task, TownTask.Status.CANCELLED, now, null));
        });
    }

    /** Lazily persists expiry; returns at most one live task, including after a restart. */
    public Optional<TownTask> current(UUID player, Instant at) throws SQLException {
        Objects.requireNonNull(player);
        Instant now = TownTask.normalize(at);
        return inTransaction(c -> lockPlayer(c, player) == null ? Optional.empty() : expireActive(c, player, now));
    }

    public Optional<TownTask> currentForPlayer(UUID player, Instant at) throws SQLException {
        return current(player, at);
    }

    /** Player-scoped lookup; missing/foreign tasks return empty. Persists this task's logical expiry. */
    public Optional<TownTask> find(UUID taskId, UUID player, Instant at) throws SQLException {
        Objects.requireNonNull(taskId);
        Objects.requireNonNull(player);
        Instant now = TownTask.normalize(at);
        return inTransaction(c -> {
            if (lockPlayer(c, player) == null) return Optional.empty();
            Optional<TownTask> found = findLocked(c, taskId, player);
            if (found.isPresent()) {
                TownTask task = found.orElseThrow();
                if (task.status() == TownTask.Status.ACTIVE && !now.isBefore(task.expiresAt())) {
                    return Optional.of(closeTask(c, task, TownTask.Status.EXPIRED, task.expiresAt(), null));
                }
            }
            return found;
        });
    }

    /** Newest first, stable task-ID tiebreak, immutable result, hard maximum 100. Persists any active expiry. */
    public List<TownTask> list(UUID player, Instant at, int max) throws SQLException {
        return list(player, at, max, false);
    }

    /** Same bound/order as list, but only terminal tasks. Nothing is deleted or rerolled. */
    public List<TownTask> history(UUID player, Instant at, int max) throws SQLException {
        return list(player, at, max, true);
    }

    private List<TownTask> list(UUID player, Instant at, int max, boolean terminalOnly) throws SQLException {
        Objects.requireNonNull(player);
        Instant now = TownTask.normalize(at);
        int limit = Math.clamp(max, 0, MAX_LIST);
        if (limit == 0) return List.of();
        return inTransaction(c -> {
            if (lockPlayer(c, player) == null) return List.of();
            expireActive(c, player, now);
            List<TownTask> tasks = new ArrayList<>();
            try (PreparedStatement ps = prepare(c, "SELECT " + COLUMNS + " FROM npc_town_tasks WHERE player_id=?"
                    + (terminalOnly ? " AND status<>'ACTIVE'" : "")
                    + " ORDER BY accepted_at_millis DESC, task_id DESC LIMIT ?")) {
                ps.setBytes(1, Uuids.toBytes(player));
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) tasks.add(readTask(rs));
                }
            }
            return List.copyOf(tasks);
        });
    }

    private static Optional<TownTask> expireActive(Connection c, UUID player, Instant now) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT " + COLUMNS
                + " FROM npc_town_tasks WHERE active_player_id=? FOR UPDATE")) {
            ps.setBytes(1, Uuids.toBytes(player));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                TownTask task = readTask(rs);
                if (!now.isBefore(task.expiresAt())) {
                    closeTask(c, task, TownTask.Status.EXPIRED, task.expiresAt(), null);
                    return Optional.empty();
                }
                return Optional.of(task);
            }
        }
    }

    private static Optional<TownTask> findLocked(Connection c, UUID taskId, UUID player) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT " + COLUMNS
                + " FROM npc_town_tasks WHERE task_id=? AND player_id=? FOR UPDATE")) {
            ps.setBytes(1, Uuids.toBytes(taskId));
            ps.setBytes(2, Uuids.toBytes(player));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readTask(rs)) : Optional.empty();
            }
        }
    }

    private static List<UUID> sortedResidents(TownTaskDefinition definition) {
        return List.of(definition.giverId(), definition.recipientId()).stream().sorted().toList();
    }

    private static Code lockResidents(Connection c, TownTaskDefinition definition) throws SQLException {
        for (UUID resident : sortedResidents(definition)) {
            try (PreparedStatement ps = prepare(c,
                    "SELECT resident_id FROM npc_resident_profiles WHERE resident_id=? FOR UPDATE")) {
                ps.setBytes(1, Uuids.toBytes(resident));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return resident.equals(definition.giverId()) ? Code.UNKNOWN_GIVER : Code.UNKNOWN_RECIPIENT;
                }
            }
        }
        return null;
    }

    private static RelationshipMemory.Stage readGiverStage(Connection c, UUID giver, UUID player) throws SQLException {
        // ResidentRepository has no caller-connection read API. Use its exact V2 evidence columns and
        // replay model, with a current locking read under the profile lock (never a second connection).
        List<RelationshipMemory.Event> events = new ArrayList<>();
        try (PreparedStatement ps = prepare(c, """
                SELECT source_ref, kind, occurred_at_millis, economy_day FROM npc_resident_memory
                WHERE resident_id=? AND player_id=? ORDER BY occurred_at_millis, source_ref LIMIT ? FOR UPDATE
                """)) {
            ps.setBytes(1, Uuids.toBytes(giver));
            ps.setBytes(2, Uuids.toBytes(player));
            ps.setInt(3, RelationshipMemory.MAX_EVENTS + 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (events.size() == RelationshipMemory.MAX_EVENTS) throw new SQLException("resident memory exceeds replay bound");
                    try {
                        events.add(new RelationshipMemory.Event(giver, player, rs.getString("source_ref"),
                                RelationshipMemory.Kind.valueOf(rs.getString("kind")),
                                Instant.ofEpochMilli(rs.getLong("occurred_at_millis")),
                                LocalDate.parse(rs.getString("economy_day"))));
                    } catch (IllegalArgumentException | DateTimeException | NullPointerException e) {
                        throw new SQLException("invalid stored resident memory", e);
                    }
                }
            }
        }
        try {
            return RelationshipMemory.replay(giver, player, events).stage();
        } catch (IllegalArgumentException e) {
            throw new SQLException("invalid resident memory chronology or payload", e);
        }
    }

    private void ensurePlayer(UUID player) throws SQLException {
        // Release the autocommit duplicate-key shared lock before taking the exclusive guard lock.
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c,
                "INSERT INTO npc_town_task_players (player_id) VALUES (?)")) {
            ps.setBytes(1, Uuids.toBytes(player));
            try {
                requireOne(ps.executeUpdate());
            } catch (SQLException e) {
                if (e.getErrorCode() != 1062 || !"23000".equals(e.getSQLState())) throw e;
            }
        }
    }

    private record PlayerGuard(LocalDate lastDay, Instant lastAt) { }

    private static PlayerGuard lockPlayer(Connection c, UUID player) throws SQLException {
        try (PreparedStatement ps = prepare(c, """
                SELECT last_accepted_day, last_accepted_at_millis FROM npc_town_task_players WHERE player_id=? FOR UPDATE
                """)) {
            ps.setBytes(1, Uuids.toBytes(player));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                String day = rs.getString("last_accepted_day");
                long at = rs.getLong("last_accepted_at_millis");
                boolean atMissing = rs.wasNull();
                if ((day == null) != atMissing) throw new SQLException("invalid town task player guard");
                try {
                    return new PlayerGuard(day == null ? null : LocalDate.parse(day),
                            atMissing ? null : Instant.ofEpochMilli(at));
                } catch (DateTimeException e) {
                    throw new SQLException("invalid town task player acceptance day", e);
                }
            }
        }
    }

    private static void insertTask(Connection c, TownTask task) throws SQLException {
        try (PreparedStatement ps = prepare(c, "INSERT INTO npc_town_tasks (" + COLUMNS
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            TownTaskDefinition d = task.definition();
            ps.setBytes(1, Uuids.toBytes(task.taskId()));
            ps.setBytes(2, Uuids.toBytes(task.playerId()));
            ps.setString(3, d.id());
            ps.setString(4, d.title());
            ps.setString(5, d.description());
            ps.setBytes(6, Uuids.toBytes(d.giverId()));
            ps.setBytes(7, Uuids.toBytes(d.recipientId()));
            ps.setString(8, d.minimumStage().name());
            ps.setInt(9, d.durationMinutes());
            ps.setLong(10, task.acceptedAt().toEpochMilli());
            ps.setString(11, task.acceptedEconomyDay().toString());
            ps.setLong(12, task.expiresAt().toEpochMilli());
            ps.setString(13, task.status().name());
            ps.setNull(14, Types.BIGINT);
            ps.setNull(15, Types.VARCHAR);
            requireOne(ps.executeUpdate());
        }
    }

    private static TownTask closeTask(Connection c, TownTask task, TownTask.Status status, Instant at, LocalDate day)
            throws SQLException {
        TownTask closed = new TownTask(task.taskId(), task.playerId(), task.definition(), task.acceptedAt(),
                task.acceptedEconomyDay(), task.expiresAt(), status, at, day);
        try (PreparedStatement ps = prepare(c, """
                UPDATE npc_town_tasks SET status=?, closed_at_millis=?, completed_economy_day=?
                WHERE task_id=? AND player_id=? AND status='ACTIVE'
                """)) {
            ps.setString(1, status.name());
            ps.setLong(2, closed.closedAt().toEpochMilli());
            ps.setString(3, day == null ? null : day.toString());
            ps.setBytes(4, Uuids.toBytes(task.taskId()));
            ps.setBytes(5, Uuids.toBytes(task.playerId()));
            requireOne(ps.executeUpdate());
        }
        return closed;
    }

    private static TownTask readTask(ResultSet rs) throws SQLException {
        try {
            TownTaskDefinition d = new TownTaskDefinition(rs.getString("definition_id"), rs.getString("title"),
                    rs.getString("description"), Uuids.fromBytes(rs.getBytes("giver_id")),
                    Uuids.fromBytes(rs.getBytes("recipient_id")),
                    RelationshipMemory.Stage.valueOf(rs.getString("minimum_stage")), rs.getInt("duration_minutes"));
            long closed = rs.getLong("closed_at_millis");
            Instant closedAt = rs.wasNull() ? null : Instant.ofEpochMilli(closed);
            String completedDay = rs.getString("completed_economy_day");
            return new TownTask(Uuids.fromBytes(rs.getBytes("task_id")), Uuids.fromBytes(rs.getBytes("player_id")), d,
                    Instant.ofEpochMilli(rs.getLong("accepted_at_millis")), LocalDate.parse(rs.getString("accepted_economy_day")),
                    Instant.ofEpochMilli(rs.getLong("expires_at_millis")), TownTask.Status.valueOf(rs.getString("status")),
                    closedAt, completedDay == null ? null : LocalDate.parse(completedDay));
        } catch (IllegalArgumentException | DateTimeException | NullPointerException e) {
            throw new SQLException("invalid stored town task", e);
        }
    }

    private static Result result(Code code) { return new Result(code, Optional.empty()); }
    private static Result result(Code code, TownTask task) { return new Result(code, Optional.of(task)); }

    @FunctionalInterface
    private interface Transaction<T> { T run(Connection connection) throws SQLException; }

    private <T> T inTransaction(Transaction<T> work) throws SQLException {
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    c.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            }
            // Hikari resets transaction settings on close without risking a commit after failed rollback.
        }
    }

    private static void requireOne(int rows) throws SQLException {
        if (rows != 1) throw new SQLException("town task write did not affect exactly one row");
    }

    private static PreparedStatement prepare(Connection c, String sql) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        try {
            ps.setQueryTimeout(5);
            return ps;
        } catch (SQLException e) {
            try {
                ps.close();
            } catch (SQLException cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }
}

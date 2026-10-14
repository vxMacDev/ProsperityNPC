package com.prosperitysmp.npc.training;

import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.resident.RelationshipMemory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Off-main-thread JDBC only. Lock order: dedicated player guard -> grant -> trainer -> memory.
 * Employment/proximity/session authorization belongs to the trusted main-thread runtime.
 * Grant issuance writes neither money, XP, permissions nor relationship evidence.
 */
public final class TrainingRepository {
    public enum Code {
        GRANTED, ALREADY_ACTIVE, ACTIVE_CONFLICT, DAILY_LIMIT, UNKNOWN_TRAINER,
        RELATIONSHIP_TOO_LOW, TIME_REGRESSION, DISABLED, UNAVAILABLE, NOT_EMPLOYED,
        UNAUTHORIZED, BUSY, UNCERTAIN
    }
    public record Result(Code code, Optional<TrainingGrant> grant) {
        public Result {
            Objects.requireNonNull(code);
            Objects.requireNonNull(grant);
        }
        public static Result denied(Code code) { return new Result(code, Optional.empty()); }
    }

    private static final String COLUMNS = "grant_id, player_id, trainer_id, job_name, session_id, "
            + "accepted_at_millis, economy_day, expires_at_millis, money_basis_points, xp_basis_points";
    @FunctionalInterface interface Connections { Connection open() throws SQLException; }
    private final Connections connections;

    public TrainingRepository(Database database) { this(Objects.requireNonNull(database)::getConnection); }
    TrainingRepository(Connections connections) { this.connections = Objects.requireNonNull(connections); }

    /** Internal trusted adapter only: current employment is captured before dispatch and rechecked on delivery. */
    public Result request(UUID player, UUID trainer, String jobName, UUID session, Instant at, LocalDate day)
            throws SQLException {
        Objects.requireNonNull(player);
        Objects.requireNonNull(trainer);
        Objects.requireNonNull(session);
        Objects.requireNonNull(day);
        String job = TrainingGrant.canonicalJob(jobName);
        Instant now = TrainingGrant.normalize(at);
        ensurePlayer(player);
        try (Connection c = connections.open()) {
            c.setAutoCommit(false);
            try {
                Instant lastAt;
                LocalDate lastDay;
                try (PreparedStatement ps = prepare(c, """
                        SELECT last_accepted_day, last_accepted_at_millis FROM npc_training_players
                        WHERE player_id=? FOR UPDATE
                        """)) {
                    ps.setBytes(1, Uuids.toBytes(player));
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) throw new SQLException("training player guard missing");
                        String label = rs.getString(1);
                        long millis = rs.getLong(2);
                        boolean missingAt = rs.wasNull();
                        if ((label == null) != missingAt) throw new SQLException("invalid training player guard");
                        lastDay = label == null ? null : LocalDate.parse(label);
                        lastAt = missingAt ? null : Instant.ofEpochMilli(millis);
                    }
                }
                Optional<TrainingGrant> existing = latest(c, player, true);
                Result result;
                if (lastAt != null && (now.isBefore(lastAt) || day.isBefore(lastDay))) {
                    result = Result.denied(Code.TIME_REGRESSION);
                } else if (existing.isPresent() && existing.orElseThrow().activeAt(now)) {
                    TrainingGrant old = existing.orElseThrow();
                    result = new Result(old.trainerId().equals(trainer) && old.jobName().equals(job)
                            ? Code.ALREADY_ACTIVE : Code.ACTIVE_CONFLICT, existing);
                } else if (day.equals(lastDay)) {
                    result = Result.denied(Code.DAILY_LIMIT);
                } else if (!lockTrainer(c, trainer)) {
                    result = Result.denied(Code.UNKNOWN_TRAINER);
                } else if (stage(c, trainer, player).ordinal() < RelationshipMemory.Stage.ACQUAINTANCE.ordinal()) {
                    result = Result.denied(Code.RELATIONSHIP_TOO_LOW);
                } else {
                    TrainingGrant grant = new TrainingGrant(UUID.randomUUID(), player, trainer, job, session,
                            now, day, now.plusSeconds(TrainingGrant.DURATION_MINUTES * 60L),
                            TrainingGrant.MONEY_BASIS_POINTS, TrainingGrant.XP_BASIS_POINTS);
                    insert(c, grant);
                    try (PreparedStatement ps = prepare(c, """
                            UPDATE npc_training_players SET last_accepted_day=?, last_accepted_at_millis=?
                            WHERE player_id=?
                            """)) {
                        ps.setString(1, day.toString());
                        ps.setLong(2, now.toEpochMilli());
                        ps.setBytes(3, Uuids.toBytes(player));
                        requireOne(ps.executeUpdate());
                    }
                    result = new Result(Code.GRANTED, Optional.of(grant));
                }
                c.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                try {
                    c.rollback();
                } catch (SQLException cleanup) {
                    failure.addSuppressed(cleanup);
                }
                if (failure instanceof SQLException sql) throw sql;
                throw new SQLException("invalid training evidence", failure);
            }
        }
    }

    /** Latest immutable authorization, including expired grants. Never extends or restarts a session. */
    public Optional<TrainingGrant> currentForPlayer(UUID player) throws SQLException {
        Objects.requireNonNull(player);
        try (Connection c = connections.open()) { return latest(c, player, false); }
    }

    private static Optional<TrainingGrant> latest(Connection c, UUID player, boolean lock) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT " + COLUMNS + " FROM npc_training_grants WHERE player_id=?"
                + " ORDER BY accepted_at_millis DESC, grant_id DESC LIMIT 1" + (lock ? " FOR UPDATE" : ""))) {
            ps.setBytes(1, Uuids.toBytes(player));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                try {
                    return Optional.of(new TrainingGrant(Uuids.fromBytes(rs.getBytes("grant_id")),
                            Uuids.fromBytes(rs.getBytes("player_id")), Uuids.fromBytes(rs.getBytes("trainer_id")),
                            rs.getString("job_name"), Uuids.fromBytes(rs.getBytes("session_id")),
                            Instant.ofEpochMilli(rs.getLong("accepted_at_millis")),
                            LocalDate.parse(rs.getString("economy_day")),
                            Instant.ofEpochMilli(rs.getLong("expires_at_millis")),
                            rs.getInt("money_basis_points"), rs.getInt("xp_basis_points")));
                } catch (RuntimeException failure) { throw new SQLException("invalid stored training grant", failure); }
            }
        }
    }

    private static boolean lockTrainer(Connection c, UUID trainer) throws SQLException {
        try (PreparedStatement ps = prepare(c,
                "SELECT resident_id FROM npc_resident_profiles WHERE resident_id=? FOR UPDATE")) {
            ps.setBytes(1, Uuids.toBytes(trainer));
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private static RelationshipMemory.Stage stage(Connection c, UUID trainer, UUID player) throws SQLException {
        // Exact V2 columns and current locking replay under the same profile lock used by ResidentRepository.
        var events = new ArrayList<RelationshipMemory.Event>();
        try (PreparedStatement ps = prepare(c, """
                SELECT source_ref, kind, occurred_at_millis, economy_day FROM npc_resident_memory
                WHERE resident_id=? AND player_id=? ORDER BY occurred_at_millis, source_ref LIMIT ? FOR UPDATE
                """)) {
            ps.setBytes(1, Uuids.toBytes(trainer));
            ps.setBytes(2, Uuids.toBytes(player));
            ps.setInt(3, RelationshipMemory.MAX_EVENTS + 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (events.size() == RelationshipMemory.MAX_EVENTS) throw new SQLException("training memory replay bound");
                    events.add(new RelationshipMemory.Event(trainer, player, rs.getString(1),
                            RelationshipMemory.Kind.valueOf(rs.getString(2)), Instant.ofEpochMilli(rs.getLong(3)),
                            LocalDate.parse(rs.getString(4))));
                }
            }
        }
        return RelationshipMemory.replay(trainer, player, events).stage();
    }

    private void ensurePlayer(UUID player) throws SQLException {
        // Autocommit duplicate INSERT releases its shared lock before taking the exclusive guard lock.
        try (Connection c = connections.open(); PreparedStatement ps = prepare(c,
                "INSERT INTO npc_training_players (player_id) VALUES (?)")) {
            ps.setBytes(1, Uuids.toBytes(player));
            try { requireOne(ps.executeUpdate()); }
            catch (SQLException failure) {
                if (failure.getErrorCode() != 1062 || !"23000".equals(failure.getSQLState())) throw failure;
            }
        }
    }

    private static void insert(Connection c, TrainingGrant grant) throws SQLException {
        try (PreparedStatement ps = prepare(c, "INSERT INTO npc_training_grants (" + COLUMNS
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setBytes(1, Uuids.toBytes(grant.grantId()));
            ps.setBytes(2, Uuids.toBytes(grant.playerId()));
            ps.setBytes(3, Uuids.toBytes(grant.trainerId()));
            ps.setString(4, grant.jobName());
            ps.setBytes(5, Uuids.toBytes(grant.sessionId()));
            ps.setLong(6, grant.acceptedAt().toEpochMilli());
            ps.setString(7, grant.economyDay().toString());
            ps.setLong(8, grant.expiresAt().toEpochMilli());
            ps.setInt(9, grant.moneyBasisPoints());
            ps.setInt(10, grant.xpBasisPoints());
            requireOne(ps.executeUpdate());
        }
    }

    private static void requireOne(int count) throws SQLException {
        if (count != 1) throw new SQLException("training write affected unexpected rows");
    }

    private static PreparedStatement prepare(Connection c, String sql) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        try {
            ps.setQueryTimeout(5);
            return ps;
        }
        catch (SQLException failure) {
            try {
                ps.close();
            } catch (SQLException cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }
}

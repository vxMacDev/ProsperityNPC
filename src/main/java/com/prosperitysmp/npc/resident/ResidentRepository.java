package com.prosperitysmp.npc.resident;

import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.Uuids;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;

/** Persistent nonfinancial identity and evidence. Neither occupancy nor a tenant deletion owns this data. */
public final class ResidentRepository {
    private static final int MAX_LIST = 100;
    private static final String INTRODUCTION_SOURCE = "introduction";
    private static final String PROFILE_COLUMNS = "resident_id, catalogue_version, name, household, generation, "
            + "occupation, means, life_stage, temperament, preference, wardrobe_everyday, wardrobe_work, wardrobe_leisure";

    private final Database database;

    /** The UUID is already saved with other details. The saved row is kept unchanged. */
    public static final class ProfileMismatchException extends SQLException {
        public ProfileMismatchException() { super("resident UUID already has a different profile or catalogue version"); }
    }

    public ResidentRepository(Database database) {
        this.database = Objects.requireNonNull(database);
    }

    /** Registers the full profile at the current catalogue version. Existing identity is never rerolled. */
    public boolean register(ResidentProfile profile) throws SQLException {
        validateProfile(profile);
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c,
                "INSERT INTO npc_resident_profiles (" + PROFILE_COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setBytes(1, Uuids.toBytes(profile.id()));
            ps.setInt(2, ResidentCatalogue.VERSION);
            ps.setString(3, profile.name());
            ps.setString(4, profile.household());
            ps.setInt(5, profile.generation());
            ps.setString(6, profile.occupation().name());
            ps.setString(7, profile.means().name());
            ps.setString(8, profile.lifeStage().name());
            ps.setString(9, profile.temperament().name());
            ps.setString(10, profile.preference().name());
            ps.setString(11, profile.wardrobe().everyday());
            ps.setString(12, profile.wardrobe().work());
            ps.setString(13, profile.wardrobe().leisure());
            try {
                requireOne(ps.executeUpdate(), "profile insert");
                return true;
            } catch (SQLException failure) {
                if (failure.getErrorCode() != 1062 || !"23000".equals(failure.getSQLState())) {
                    throw failure;
                }
            }
        }
        // The failed autocommit INSERT has released its duplicate-key shared lock before taking an exclusive lock.
        return withResidentLock(profile.id(), c -> {
            try (PreparedStatement ps = prepare(c,
                    "SELECT " + PROFILE_COLUMNS + " FROM npc_resident_profiles WHERE resident_id=?")) {
                ps.setBytes(1, Uuids.toBytes(profile.id()));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || rs.getInt("catalogue_version") != ResidentCatalogue.VERSION
                            || !profile.equals(readProfile(rs))) {
                        throw new ProfileMismatchException();
                    }
                }
            }
            return false;
        });
    }

    public Optional<ResidentProfile> find(UUID resident) throws SQLException {
        Objects.requireNonNull(resident);
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c,
                "SELECT " + PROFILE_COLUMNS + " FROM npc_resident_profiles WHERE resident_id=?")) {
            ps.setBytes(1, Uuids.toBytes(resident));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readProfile(rs)) : Optional.empty();
            }
        }
    }

    /** Bounded read for render snapshots; missing profiles stay missing rather than being rerolled. */
    public Map<UUID, ResidentProfile> findMany(Set<UUID> ids) throws SQLException {
        Objects.requireNonNull(ids);
        if (ids.size() > 2048 || ids.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Invalid resident batch");
        List<UUID> sorted = ids.stream().sorted().toList();
        Map<UUID, ResidentProfile> result = new HashMap<>();
        for (int offset = 0; offset < sorted.size(); offset += 128) {
            if (Thread.currentThread().isInterrupted()) throw new SQLException("Resident snapshot interrupted");
            List<UUID> batch = sorted.subList(offset, Math.min(offset + 128, sorted.size()));
            String placeholders = String.join(",", java.util.Collections.nCopies(batch.size(), "?"));
            try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c,
                    "SELECT " + PROFILE_COLUMNS + " FROM npc_resident_profiles WHERE resident_id IN (" + placeholders + ")")) {
                for (int i = 0; i < batch.size(); i++) ps.setBytes(i + 1, Uuids.toBytes(batch.get(i)));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) { ResidentProfile profile = readProfile(rs); result.put(profile.id(), profile); }
                }
            }
        }
        return Map.copyOf(result);
    }

    /** Stable UUID order; nonpositive limits return nothing and no request returns more than 100 profiles. */
    public List<ResidentProfile> list(int max) throws SQLException {
        List<ResidentProfile> profiles = new ArrayList<>();
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c,
                "SELECT " + PROFILE_COLUMNS + " FROM npc_resident_profiles ORDER BY resident_id LIMIT ?")) {
            ps.setInt(1, Math.clamp(max, 0, MAX_LIST));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    profiles.add(readProfile(rs));
                }
            }
        }
        return List.copyOf(profiles);
    }

    /** An unknown profile is an error, not a fabricated stranger. Oversized/corrupt history fails closed. */
    public RelationshipMemory.State readRelationship(UUID resident, UUID player) throws SQLException {
        Objects.requireNonNull(player);
        try (Connection c = database.getConnection()) {
            requireResident(c, resident, false);
            return replay(resident, player, readEvents(c, resident, player, false));
        }
    }

    /**
     * Records only the first introduction. Later calls ignore their proposed time/day and retain the original
     * evidence while returning the current relationship. New timestamps are normalized down to epoch milliseconds.
     */
    public RelationshipMemory.State introduce(UUID resident, UUID player, Instant at, LocalDate day) throws SQLException {
        Objects.requireNonNull(player);
        Objects.requireNonNull(at);
        Objects.requireNonNull(day);
        return withResidentLock(resident, c -> {
            List<RelationshipMemory.Event> history = readEvents(c, resident, player, true);
            if (history.stream().noneMatch(e -> e.sourceRef().equals(INTRODUCTION_SOURCE))) {
                RelationshipMemory.Event event = normalize(new RelationshipMemory.Event(resident, player,
                        INTRODUCTION_SOURCE, RelationshipMemory.Kind.INTRODUCTION, at, day));
                addToHistory(history, event);
                RelationshipMemory.State state = replay(resident, player, history);
                insertEvent(c, event);
                return state;
            }
            return replay(resident, player, history);
        });
    }

    /**
     * INTERNAL trusted source adapters only; never expose this as a player/console command or client endpoint.
     * Event validates shape, NOT whether a repair/obligation happened. The adapter MUST verify source identity,
     * completion/outcome, resident/player attribution, time and economy day before calling this method.
     * Full payload equality is checked after millisecond normalization. Evidence cannot be overwritten.
     * INTRODUCTION uses the reserved source "introduction"; normal playable interactions call introduce instead.
     */
    public boolean appendVerified(RelationshipMemory.Event input) throws SQLException {
        return inTransaction(c -> appendVerified(c, input));
    }

    /**
     * Same trusted-source contract as {@link #appendVerified(RelationshipMemory.Event)}, inside a caller-owned
     * SQL transaction. Auto-commit must already be disabled. This method never commits, rolls back, closes,
     * or changes transaction settings on the supplied connection, including on failure. The caller MUST abort
     * its entire transaction on any exception; false means an exact normalized duplicate, not a failure.
     *
     * Lock order: the caller may already hold property -> event -> intent -> incident/tenant locks, then this
     * method takes the resident profile followed by its memory rows. Resident code never acquires those
     * upstream locks in reverse. Callers appending for multiple residents must lock them in stable UUID order.
     */
    public static boolean appendVerified(Connection c, RelationshipMemory.Event input) throws SQLException {
        Objects.requireNonNull(c);
        if (c.getAutoCommit()) {
            throw new SQLException("resident memory append requires a caller-owned transaction");
        }
        RelationshipMemory.Event event = normalize(Objects.requireNonNull(input));
        if ((event.kind() == RelationshipMemory.Kind.INTRODUCTION) != event.sourceRef().equals(INTRODUCTION_SOURCE)) {
            throw new IllegalArgumentException("introduction kind and source are reserved together");
        }
        requireResident(c, event.residentId(), true);
        // A caller can already have an older REPEATABLE READ snapshot. Replay must use a current locking read.
        List<RelationshipMemory.Event> history = readEvents(c, event.residentId(), event.playerId(), true);
        for (RelationshipMemory.Event old : history) {
            if (old.sourceRef().equals(event.sourceRef())) {
                if (!old.equals(event)) {
                    throw new SQLException("resident memory source payload conflict");
                }
                return false;
            }
        }
        addToHistory(history, event);
        replay(event.residentId(), event.playerId(), history);
        insertEvent(c, event);
        return true;
    }

    private static void addToHistory(List<RelationshipMemory.Event> history, RelationshipMemory.Event event)
            throws SQLException {
        if (history.size() >= RelationshipMemory.MAX_EVENTS) {
            throw new SQLException("resident memory is full; a reviewed checkpoint is required");
        }
        history.add(event);
    }

    private static List<RelationshipMemory.Event> readEvents(Connection c, UUID resident, UUID player, boolean lock)
            throws SQLException {
        List<RelationshipMemory.Event> events = new ArrayList<>();
        try (PreparedStatement ps = prepare(c, """
                SELECT source_ref, kind, occurred_at_millis, economy_day FROM npc_resident_memory
                WHERE resident_id=? AND player_id=? ORDER BY occurred_at_millis, source_ref LIMIT ?
                """ + (lock ? " FOR UPDATE" : ""))) {
            ps.setBytes(1, Uuids.toBytes(resident));
            ps.setBytes(2, Uuids.toBytes(player));
            // One bounded sentinel detects out-of-band overflow; it is never passed to replay.
            ps.setInt(3, RelationshipMemory.MAX_EVENTS + 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (events.size() == RelationshipMemory.MAX_EVENTS) {
                        throw new SQLException("stored resident memory exceeds replay bound");
                    }
                    try {
                        events.add(new RelationshipMemory.Event(resident, player, rs.getString("source_ref"),
                                RelationshipMemory.Kind.valueOf(rs.getString("kind")),
                                Instant.ofEpochMilli(rs.getLong("occurred_at_millis")),
                                LocalDate.parse(rs.getString("economy_day"))));
                    } catch (IllegalArgumentException | java.time.DateTimeException e) {
                        throw new SQLException("invalid stored resident memory", e);
                    }
                }
            }
        }
        return events;
    }

    private static RelationshipMemory.State replay(UUID resident, UUID player, List<RelationshipMemory.Event> history)
            throws SQLException {
        try {
            return RelationshipMemory.replay(resident, player, history);
        } catch (IllegalArgumentException e) {
            throw new SQLException("invalid resident memory chronology or payload", e);
        }
    }

    private static RelationshipMemory.Event normalize(RelationshipMemory.Event event) {
        Instant at = event.occurredAt().truncatedTo(ChronoUnit.MILLIS);
        try {
            at.toEpochMilli();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("memory timestamp is outside epoch-millisecond range", e);
        }
        return new RelationshipMemory.Event(event.residentId(), event.playerId(), event.sourceRef(), event.kind(),
                at, event.economyDay());
    }

    private static void insertEvent(Connection c, RelationshipMemory.Event event) throws SQLException {
        try (PreparedStatement ps = prepare(c, """
                INSERT INTO npc_resident_memory (resident_id, player_id, source_ref, kind, occurred_at_millis, economy_day)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            ps.setBytes(1, Uuids.toBytes(event.residentId()));
            ps.setBytes(2, Uuids.toBytes(event.playerId()));
            ps.setString(3, event.sourceRef());
            ps.setString(4, event.kind().name());
            ps.setLong(5, event.occurredAt().toEpochMilli());
            ps.setString(6, event.economyDay().toString());
            requireOne(ps.executeUpdate(), "memory insert");
        }
    }

    private <T> T withResidentLock(UUID resident, Transaction<T> work) throws SQLException {
        Objects.requireNonNull(resident);
        return inTransaction(c -> {
            requireResident(c, resident, true);
            return work.run(c);
        });
    }

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
            // Hikari resets auto-commit on close, without risking a commit after a failed rollback.
        }
    }

    @FunctionalInterface
    private interface Transaction<T> {
        T run(Connection connection) throws SQLException;
    }

    private static void requireResident(Connection c, UUID resident, boolean lock) throws SQLException {
        Objects.requireNonNull(resident);
        try (PreparedStatement ps = prepare(c,
                "SELECT resident_id FROM npc_resident_profiles WHERE resident_id=?" + (lock ? " FOR UPDATE" : ""))) {
            ps.setBytes(1, Uuids.toBytes(resident));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("resident profile does not exist: " + resident);
                }
            }
        }
    }

    private static ResidentProfile readProfile(ResultSet rs) throws SQLException {
        try {
            if (rs.getInt("catalogue_version") < 1) {
                throw new IllegalArgumentException("invalid catalogue version");
            }
            ResidentProfile profile = new ResidentProfile(Uuids.fromBytes(rs.getBytes("resident_id")),
                    rs.getString("name"), rs.getString("household"), rs.getInt("generation"),
                    ResidentProfile.Occupation.valueOf(rs.getString("occupation")),
                    ResidentProfile.Means.valueOf(rs.getString("means")),
                    ResidentProfile.LifeStage.valueOf(rs.getString("life_stage")),
                    ResidentProfile.Temperament.valueOf(rs.getString("temperament")),
                    ResidentProfile.Preference.valueOf(rs.getString("preference")),
                    new ResidentProfile.Wardrobe(rs.getString("wardrobe_everyday"), rs.getString("wardrobe_work"),
                            rs.getString("wardrobe_leisure")));
            validateProfile(profile);
            return profile;
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new SQLException("invalid stored resident profile", e);
        }
    }

    private static void validateProfile(ResidentProfile profile) {
        Objects.requireNonNull(profile);
        for (String key : List.of(profile.wardrobe().everyday(), profile.wardrobe().work(), profile.wardrobe().leisure())) {
            if (key.length() > 64 || !key.matches("[a-z0-9][a-z0-9_-]*(/[a-z0-9][a-z0-9_-]*)*")) {
                throw new IllegalArgumentException("wardrobe key must be a canonical relative asset path");
            }
        }
    }

    private static void requireOne(int rows, String action) throws SQLException {
        if (rows != 1) {
            throw new SQLException(action + " did not affect exactly one row");
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            statement.setQueryTimeout(5);
            return statement;
        } catch (SQLException error) {
            statement.close();
            throw error;
        }
    }
}

package com.prosperitysmp.npc.town;

import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.Uuids;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL for partnerships. No Bukkit, Vault, transfer journal, cap or economy event. A partnership starts or renews only
 * inside {@link TownContractRepository}'s hand-over transaction, after the materials are proven taken. Ending never
 * refunds anything. A partnership past its last day lapses lazily (status ENDED, reason LAPSED) the next time its
 * owner or its resident's seats are read.
 * Lock order (shared with TownContractRepository): contract player guard, contract row, sorted resident profiles,
 * partnership rows, resident memory. Standalone methods here take only partnership rows. No property, economy or
 * commerce locks. {@link #activeForDebit} runs inside a commerce transaction, after its commerce locks. All calls are
 * bounded synchronous JDBC work for a database worker.
 */
public final class PartnershipRepository {
    public static final int MAX_LIST = 200;
    private static final String COLUMNS = "partnership_id, owner_id, resident_id, started_economy_day, last_active_day, "
            + "fee_discount_pct, rank_bonus_points, status, end_reason, ended_at_millis, start_contract_id, revision";
    private final Database database;

    public PartnershipRepository(Database database) { this.database = Objects.requireNonNull(database); }

    /** The owner's partnership that is active today, after lazily lapsing an old one. */
    public Optional<Partnership> current(UUID owner, LocalDate today, Instant now) throws SQLException {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(today);
        Objects.requireNonNull(now);
        return transaction(c -> currentLocked(c, owner, today, now));
    }

    /** Ends the owner's active partnership now. No refund. Empty when there was none. */
    public Optional<Partnership> end(UUID owner, Partnership.EndReason reason, Instant now, LocalDate today) throws SQLException {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(reason);
        Objects.requireNonNull(now);
        Objects.requireNonNull(today);
        if (reason == Partnership.EndReason.LAPSED) throw new IllegalArgumentException("Lapses are written by the lazy read");
        return transaction(c -> {
            var current = currentLocked(c, owner, today, now);
            if (current.isEmpty()) return Optional.empty();
            return Optional.of(close(c, current.get(), reason, now));
        });
    }

    /** Plain read for staff and health: active partnerships today, newest first, optionally for one resident. */
    public List<Partnership> listActive(UUID resident, LocalDate today, int max) throws SQLException {
        Objects.requireNonNull(today);
        int limit = Math.clamp(max, 0, MAX_LIST);
        if (limit == 0) return List.of();
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c, "SELECT " + COLUMNS
                + " FROM npc_partnerships WHERE status='ACTIVE' AND last_active_day>=?"
                + (resident == null ? "" : " AND resident_id=?") + " ORDER BY started_economy_day DESC, partnership_id LIMIT ?")) {
            ps.setString(1, today.toString());
            int i = 2;
            if (resident != null) ps.setBytes(i++, Uuids.toBytes(resident));
            ps.setInt(i, limit);
            var out = new ArrayList<Partnership>();
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(read(rs)); }
            return List.copyOf(out);
        }
    }

    /** Plain count of partnerships active today, for health. */
    public int countActive(LocalDate today) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c,
                "SELECT COUNT(*) FROM npc_partnerships WHERE status='ACTIVE' AND last_active_day>=?")) {
            ps.setString(1, today.toString());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /**
     * Housing snapshot: rank points per owner active on day, as min(row, caps). caps holds the current config points
     * per resident; a resident missing from caps gives nothing. A plain consistent read in the caller's transaction,
     * so it writes nothing (no lazy lapse) and takes no lock.
     */
    public static Map<UUID, Integer> rankBonusByOwner(Connection c, LocalDate day, Map<UUID, Integer> caps) throws SQLException {
        Objects.requireNonNull(c);
        Objects.requireNonNull(day);
        Objects.requireNonNull(caps);
        var out = new HashMap<UUID, Integer>();
        if (caps.isEmpty()) return Map.of();
        try (PreparedStatement ps = prepare(c, """
                SELECT owner_id, resident_id, rank_bonus_points FROM npc_partnerships
                WHERE status='ACTIVE' AND last_active_day>=? AND started_economy_day<=? AND rank_bonus_points>0
                ORDER BY owner_id LIMIT 4097
                """)) {
            ps.setString(1, day.toString());
            ps.setString(2, day.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (out.size() >= 4096) throw new SQLException("Partnership rank read exceeds bound");
                    int stored = rs.getInt("rank_bonus_points");
                    if (stored < 0 || stored > PartnerOffer.MAX_RANK_POINTS) throw new SQLException("Invalid stored rank points");
                    int points = Math.min(stored, caps.getOrDefault(Uuids.fromBytes(rs.getBytes("resident_id")), 0));
                    if (points > 0) out.merge(Uuids.fromBytes(rs.getBytes("owner_id")), points, Math::max);
                }
            }
        }
        return Map.copyOf(out);
    }

    /**
     * Inside a commerce payment transaction, just before its debit intent: the owner's ACTIVE row under a shared
     * lock, so an end or a lapse of it waits until that transaction commits. Writes nothing (no lazy lapse); the
     * caller checks the day. This is the only place a commerce transaction takes a partnership lock, and partnership
     * transactions never take commerce locks, so the two cannot wait on each other in a cycle.
     */
    public static Optional<Partnership> activeForDebit(Connection c, UUID owner) throws SQLException {
        Objects.requireNonNull(c);
        Objects.requireNonNull(owner);
        try (PreparedStatement ps = prepare(c, "SELECT " + COLUMNS + " FROM npc_partnerships WHERE active_owner_id=? LOCK IN SHARE MODE")) {
            ps.setBytes(1, Uuids.toBytes(owner));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                Partnership found = read(rs);
                if (rs.next()) throw new SQLException("Two active partnerships for one owner");
                return Optional.of(found);
            }
        }
    }

    // ---- inside the hand-over transaction (caller holds the contract guard, the contract row and the resident) ----

    /** The owner's partnership, locked. A row past its last day is lapsed here and not returned. */
    static Optional<Partnership> currentLocked(Connection c, UUID owner, LocalDate today, Instant now) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT " + COLUMNS + " FROM npc_partnerships WHERE active_owner_id=? FOR UPDATE")) {
            ps.setBytes(1, Uuids.toBytes(owner));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                Partnership found = read(rs);
                if (rs.next()) throw new SQLException("Two active partnerships for one owner");
                if (found.activeOn(today)) return Optional.of(found);
                close(c, found, Partnership.EndReason.LAPSED, now);
                return Optional.empty();
            }
        }
    }

    /** Seats used at this resident today, after lapsing old rows. Call with the resident profile locked. */
    static int seatsLocked(Connection c, UUID resident, LocalDate today, Instant now) throws SQLException {
        var stale = new ArrayList<Partnership>();
        int active = 0;
        try (PreparedStatement ps = prepare(c, "SELECT " + COLUMNS
                + " FROM npc_partnerships WHERE resident_id=? AND status='ACTIVE' ORDER BY partnership_id LIMIT 4097 FOR UPDATE")) {
            ps.setBytes(1, Uuids.toBytes(resident));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Partnership row = read(rs);
                    if (row.activeOn(today)) active++;
                    else stale.add(row);
                }
            }
        }
        for (Partnership row : stale) close(c, row, Partnership.EndReason.LAPSED, now);
        return active;
    }

    static Partnership start(Connection c, UUID contractId, UUID owner, UUID resident, PartnerPerks perks,
                             LocalDate today, int termDays) throws SQLException {
        if (termDays < 1 || termDays > 30) throw new SQLException("Invalid partnership term");
        var row = new Partnership(UUID.randomUUID(), owner, resident, today, today.plusDays(termDays - 1L), perks,
                Partnership.Status.ACTIVE, null, null, contractId, 1);
        try (PreparedStatement ps = prepare(c, "INSERT INTO npc_partnerships (" + COLUMNS
                + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setBytes(1, Uuids.toBytes(row.partnershipId()));
            ps.setBytes(2, Uuids.toBytes(owner));
            ps.setBytes(3, Uuids.toBytes(resident));
            ps.setString(4, row.startedDay().toString());
            ps.setString(5, row.lastActiveDay().toString());
            ps.setInt(6, perks.feeDiscountPct());
            ps.setInt(7, perks.rankBonusPoints());
            ps.setString(8, "ACTIVE");
            ps.setNull(9, Types.VARCHAR);
            ps.setNull(10, Types.BIGINT);
            ps.setBytes(11, Uuids.toBytes(contractId));
            ps.setLong(12, 1);
            one(ps.executeUpdate());
        }
        return row;
    }

    /** Extends from the current last day, never from today, and records the renewal. */
    static Partnership renew(Connection c, UUID contractId, Partnership current, int termDays) throws SQLException {
        if (termDays < 1 || termDays > 30) throw new SQLException("Invalid partnership term");
        LocalDate next = current.lastActiveDay().plusDays(termDays);
        try (PreparedStatement ps = prepare(c, """
                UPDATE npc_partnerships SET last_active_day=?, revision=revision+1, updated_at=CURRENT_TIMESTAMP(3)
                WHERE partnership_id=? AND status='ACTIVE' AND last_active_day=? AND revision=?
                """)) {
            ps.setString(1, next.toString());
            ps.setBytes(2, Uuids.toBytes(current.partnershipId()));
            ps.setString(3, current.lastActiveDay().toString());
            ps.setLong(4, current.revision());
            one(ps.executeUpdate());
        }
        try (PreparedStatement ps = prepare(c, """
                INSERT INTO npc_partnership_renewals (contract_id, partnership_id, previous_last_day, new_last_day)
                VALUES (?,?,?,?)
                """)) {
            ps.setBytes(1, Uuids.toBytes(contractId));
            ps.setBytes(2, Uuids.toBytes(current.partnershipId()));
            ps.setString(3, current.lastActiveDay().toString());
            ps.setString(4, next.toString());
            one(ps.executeUpdate());
        }
        return new Partnership(current.partnershipId(), current.ownerId(), current.residentId(), current.startedDay(), next,
                current.perks(), Partnership.Status.ACTIVE, null, null, current.startContractId(), current.revision() + 1);
    }

    private static Partnership close(Connection c, Partnership row, Partnership.EndReason reason, Instant now) throws SQLException {
        long at = now.toEpochMilli();
        try (PreparedStatement ps = prepare(c, """
                UPDATE npc_partnerships SET status='ENDED', end_reason=?, ended_at_millis=?, revision=revision+1,
                updated_at=CURRENT_TIMESTAMP(3) WHERE partnership_id=? AND status='ACTIVE' AND revision=?
                """)) {
            ps.setString(1, reason.name());
            ps.setLong(2, at);
            ps.setBytes(3, Uuids.toBytes(row.partnershipId()));
            ps.setLong(4, row.revision());
            one(ps.executeUpdate());
        }
        return new Partnership(row.partnershipId(), row.ownerId(), row.residentId(), row.startedDay(), row.lastActiveDay(),
                row.perks(), Partnership.Status.ENDED, reason, Instant.ofEpochMilli(at), row.startContractId(), row.revision() + 1);
    }

    static Partnership read(ResultSet rs) throws SQLException {
        try {
            String reason = rs.getString("end_reason");
            long ended = rs.getLong("ended_at_millis");
            Instant endedAt = rs.wasNull() ? null : Instant.ofEpochMilli(ended);
            return new Partnership(Uuids.fromBytes(rs.getBytes("partnership_id")), Uuids.fromBytes(rs.getBytes("owner_id")),
                    Uuids.fromBytes(rs.getBytes("resident_id")), LocalDate.parse(rs.getString("started_economy_day")),
                    LocalDate.parse(rs.getString("last_active_day")),
                    new PartnerPerks(rs.getInt("fee_discount_pct"), rs.getInt("rank_bonus_points")),
                    Partnership.Status.valueOf(rs.getString("status")), reason == null ? null : Partnership.EndReason.valueOf(reason),
                    endedAt, Uuids.fromBytes(rs.getBytes("start_contract_id")), rs.getLong("revision"));
        } catch (IllegalArgumentException | DateTimeException | NullPointerException invalid) {
            throw new SQLException("Invalid stored partnership", invalid);
        }
    }

    @FunctionalInterface private interface Work<T> { T run(Connection c) throws SQLException; }

    private <T> T transaction(Work<T> work) throws SQLException {
        try (Connection c = database.getConnection()) {
            // READ COMMITTED: locking reads take record locks only, never gap locks, so two players' inserts never
            // wait on each other's gaps while one holds a resident lock (no cross-player deadlock).
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                try {
                    c.rollback();
                } catch (SQLException rollback) {
                    failure.addSuppressed(rollback);
                }
                throw failure;
            }
        }
    }

    static void one(int rows) throws SQLException {
        if (rows != 1) throw new SQLException("Partnership write did not affect exactly one row");
    }

    static PreparedStatement prepare(Connection c, String sql) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        try {
            ps.setQueryTimeout(5);
            return ps;
        }
        catch (SQLException failure) {
            ps.close();
            throw failure;
        }
    }
}

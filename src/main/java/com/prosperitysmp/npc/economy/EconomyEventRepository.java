package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.Uuids;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The economy-event ledger. Enforces idempotency (unique key), the
 * PENDING→CLAIMED→PAID/FAILED lifecycle with an atomic claim, and startup
 * crash-recovery of orphaned CLAIMED rows to AMBIGUOUS (never auto-retried).
 */
public final class EconomyEventRepository {

    private static final int MAX_PENDING_BATCH = 100;

    private final Database database;

    public EconomyEventRepository(Database database) {
        this.database = database;
    }

    /**
     * Inserts a PENDING event. Returns the new event id, or -1 if an event with
     * the same key and complete request payload already exist (safe no-op retry).
     * Conflicting payloads and other database errors are not retries.
     */
    public long insertPending(EconomyEvent e) throws SQLException {
        try (Connection c = database.getConnection()) {
            return insertPending(c, e);
        }
    }

    /** Caller owns the transaction: used to enqueue an event and its structured benefit intent atomically. */
    static long insertPending(Connection c, EconomyEvent e) throws SQLException {
        if (e == null || e.idempotencyKey() == null || e.idempotencyKey().isBlank()
                || e.source() == null || e.requestedAmountCents() <= 0) {
            throw new SQLException("event requires a nonblank idempotency key, source and positive amount");
        }
        try (PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO npc_economy_events
                       (idempotency_key, requested_amount_cents, source, direction, property_id,
                        counterparty_uuid, ownership_epoch, benefit_unit_id, economy_day, state)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
                     """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, e.idempotencyKey());
            ps.setLong(2, e.requestedAmountCents());
            ps.setString(3, e.source().key());
            ps.setString(4, e.source().direction().moneyFlowKey());
            setNullableUuid(ps, 5, e.propertyId());
            setNullableUuid(ps, 6, e.counterpartyUuid());
            if (e.ownershipEpoch() == null) {
                ps.setNull(7, Types.INTEGER);
            } else {
                ps.setInt(7, e.ownershipEpoch());
            }
            setNullableUuid(ps, 8, e.benefitUnitId());
            ps.setString(9, e.economyDay());
            try {
                if (ps.executeUpdate() != 1) {
                    throw new SQLException("pending event insert did not affect exactly one row");
                }
            } catch (SQLException failure) {
                if (!isIdempotencyViolation(failure) || !sameRequest(c, e)) {
                    throw failure;
                }
                return -1L;
            }
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("pending event insert returned no generated id");
                }
                return keys.getLong(1);
            }
        }
    }

    private static boolean isIdempotencyViolation(SQLException failure) {
        // JDBC exposes no constraint-name field. Accept only MariaDB's named unique-key error.
        String message = failure.getMessage();
        return failure.getErrorCode() == 1062 && "23000".equals(failure.getSQLState())
                && message != null && (message.endsWith("for key 'uq_idempotency'")
                || message.endsWith("for key 'npc_economy_events.uq_idempotency'"));
    }

    private static boolean sameRequest(Connection c, EconomyEvent e) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT idempotency_key, requested_amount_cents, source, direction, property_id,
                       counterparty_uuid, ownership_epoch, benefit_unit_id, economy_day
                FROM npc_economy_events WHERE idempotency_key=?
                """)) {
            ps.setString(1, e.idempotencyKey());
            try (ResultSet rs = ps.executeQuery()) {
                // Compare strings exactly even though the database's unique key is case-insensitive.
                return rs.next()
                        && e.idempotencyKey().equals(rs.getString("idempotency_key"))
                        && e.requestedAmountCents() == rs.getLong("requested_amount_cents")
                        && e.source().key().equals(rs.getString("source"))
                        && e.source().direction().moneyFlowKey().equals(rs.getString("direction"))
                        && sameUuid(e.propertyId(), rs.getBytes("property_id"))
                        && sameUuid(e.counterpartyUuid(), rs.getBytes("counterparty_uuid"))
                        && Objects.equals(e.ownershipEpoch(), rs.getObject("ownership_epoch"))
                        && sameUuid(e.benefitUnitId(), rs.getBytes("benefit_unit_id"))
                        && Objects.equals(e.economyDay(), rs.getString("economy_day"));
            }
        }
    }

    private static boolean sameUuid(UUID uuid, byte[] bytes) {
        return Arrays.equals(uuid == null ? null : Uuids.toBytes(uuid), bytes);
    }

    /** Atomically PENDING -> CLAIMED. Returns true if this call performed the claim. */
    public boolean claim(long eventId) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE npc_economy_events SET state='CLAIMED', claimed_at=CURRENT_TIMESTAMP(3) "
                             + "WHERE event_id=? AND state='PENDING'")) {
            ps.setLong(1, eventId);
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Finalizes a CLAIMED event as PAID and updates the property's lifetime funding
     * in the same transaction: a TO_PUBLIC payout increments payouts, a FROM_PUBLIC
     * sink increments sinks. Both must move together to keep the invariant honest.
     */
    public void finalizePaid(long eventId, long paidCents) throws SQLException {
        try (Connection c = database.getConnection()) {
            // Pool close resets auto-commit; enabling it after a failed rollback could commit partial work.
            c.setAutoCommit(false);
            try {
                EventSource source;
                byte[] propertyBytes;
                try (PreparedStatement q = c.prepareStatement(
                        "SELECT source, direction, property_id, requested_amount_cents FROM npc_economy_events "
                                + "WHERE event_id=? AND state='CLAIMED' FOR UPDATE")) {
                    q.setLong(1, eventId);
                    try (ResultSet r = q.executeQuery()) {
                        if (!r.next()) {
                            c.rollback();
                            return; // missing or no longer CLAIMED: nothing to book
                        }
                        source = validatedSource(r.getString("source"), r.getString("direction"));
                        long requestedCents = r.getLong("requested_amount_cents");
                        if (requestedCents <= 0 || paidCents != requestedCents) {
                            throw new SQLException("paid amount must equal the positive requested amount for event " + eventId);
                        }
                        propertyBytes = r.getBytes("property_id");
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE npc_economy_events SET state='PAID', paid_amount_cents=?, "
                                + "finalized_at=CURRENT_TIMESTAMP(3) WHERE event_id=? AND state='CLAIMED'")) {
                    ps.setLong(1, paidCents);
                    ps.setLong(2, eventId);
                    if (ps.executeUpdate() != 1) {
                        throw new SQLException("paid event update did not affect exactly one row: " + eventId);
                    }
                }
                if (propertyBytes != null) {
                    String col = source.isPayout()
                            ? "successful_cash_payouts_cents" : "successful_cash_sinks_cents";
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE npc_property_funding SET " + col + " = " + col + " + ?, "
                                    + "updated_at=CURRENT_TIMESTAMP(3) WHERE property_id=?")) {
                        ps.setLong(1, paidCents);
                        ps.setBytes(2, propertyBytes);
                        if (ps.executeUpdate() != 1) {
                            throw new SQLException("funding update did not affect exactly one row for event " + eventId);
                        }
                    }
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                try {
                    c.rollback();
                } catch (SQLException rollbackFailure) {
                    e.addSuppressed(rollbackFailure);
                }
                throw e;
            }
        }
    }

    public void finalizeFailed(long eventId) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE npc_economy_events SET state='FAILED', finalized_at=CURRENT_TIMESTAMP(3) "
                             + "WHERE event_id=? AND state='CLAIMED'")) {
            ps.setLong(1, eventId);
            ps.executeUpdate();
        }
    }

    /** Details the Skript bridge needs to settle one claimed event. */
    public record ClaimedDetail(long eventId, EventSource source, UUID counterparty, long amountCents) {
    }

    /** Returns up to {@code max} PENDING event ids, oldest first, hard-capped at 100. */
    public List<Long> selectPendingIds(int max) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT event_id FROM npc_economy_events WHERE state='PENDING' ORDER BY event_id LIMIT ?")) {
            ps.setInt(1, Math.min(MAX_PENDING_BATCH, Math.max(0, max)));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getLong(1));
                }
            }
        }
        return ids;
    }

    /** Loads the fields the bridge needs for a (already CLAIMED) event. */
    public Optional<ClaimedDetail> loadDetail(long eventId) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT source, direction, counterparty_uuid, requested_amount_cents FROM npc_economy_events "
                             + "WHERE event_id=? AND state='CLAIMED'")) {
            ps.setLong(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                EventSource source = validatedSource(rs.getString("source"), rs.getString("direction"));
                long requestedCents = rs.getLong("requested_amount_cents");
                if (requestedCents <= 0) {
                    throw new SQLException("claimed event has a nonpositive requested amount: " + eventId);
                }
                byte[] cp = rs.getBytes("counterparty_uuid");
                UUID counterparty = cp == null ? null : Uuids.fromBytes(cp);
                return Optional.of(new ClaimedDetail(eventId, source, counterparty,
                        requestedCents));
            }
        }
    }

    private static EventSource validatedSource(String key, String direction) throws SQLException {
        for (EventSource s : EventSource.values()) {
            if (s.key().equals(key)) {
                if (!s.direction().moneyFlowKey().equals(direction)) {
                    throw new SQLException("invalid direction for source: " + key);
                }
                return s;
            }
        }
        throw new SQLException("unknown source key: " + key);
    }

    /** Startup recovery: any CLAIMED row is a crash between move and ack -> AMBIGUOUS, never retried. */
    public int markClaimedAsAmbiguousOnStartup() throws SQLException {
        try (Connection c = database.getConnection();
             Statement st = c.createStatement()) {
            return st.executeUpdate("UPDATE npc_economy_events SET state='AMBIGUOUS' WHERE state='CLAIMED'");
        }
    }

    public boolean existsByIdempotencyKey(String key) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM npc_economy_events WHERE idempotency_key=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // ---- Daily PAID sums for cap headroom ----------------------------------

    public long sumPaidToPublicForDay(String economyDay) throws SQLException {
        return sum("economy_day=? AND direction='TRANSFER_TO_PUBLIC' AND state='PAID'", economyDay);
    }

    public long sumPaidToPublicForProperty(String economyDay, UUID propertyId) throws SQLException {
        return sumWithUuid("economy_day=? AND property_id=? AND direction='TRANSFER_TO_PUBLIC' AND state='PAID'",
                economyDay, propertyId);
    }

    public long sumPaidToPublicForBenefitUnit(String economyDay, UUID benefitUnitId) throws SQLException {
        return sumWithUuid("economy_day=? AND benefit_unit_id=? AND direction='TRANSFER_TO_PUBLIC' AND state='PAID'",
                economyDay, benefitUnitId);
    }

    public long sumPaidAppreciationForProperty(String economyDay, UUID propertyId) throws SQLException {
        return sumWithUuid("economy_day=? AND property_id=? AND source='" + EventSource.APPRECIATION.key()
                + "' AND state='PAID'", economyDay, propertyId);
    }

    private long sum(String where, String economyDay) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COALESCE(SUM(paid_amount_cents),0) FROM npc_economy_events WHERE " + where)) {
            ps.setString(1, economyDay);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private long sumWithUuid(String where, String economyDay, UUID uuid) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COALESCE(SUM(paid_amount_cents),0) FROM npc_economy_events WHERE " + where)) {
            ps.setString(1, economyDay);
            ps.setBytes(2, Uuids.toBytes(uuid));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private static void setNullableUuid(PreparedStatement ps, int idx, UUID uuid) throws SQLException {
        if (uuid == null) {
            ps.setNull(idx, Types.BINARY);
        } else {
            ps.setBytes(idx, Uuids.toBytes(uuid));
        }
    }
}

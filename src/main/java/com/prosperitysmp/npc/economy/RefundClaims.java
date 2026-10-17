package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.Uuids;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * One staff refund closes one held payment. A refund is known by its payer and its before and after balances, and
 * the transfer journal and commerce both claim them here, inside the transaction that records the resolution.
 *
 * A refund can only reverse a debit made before it. So a claim already recorded for this payer, with the same
 * before or the same after balance, after this payment's debit began may be this payment's own refund, used once
 * already: the new resolution is refused. A claim recorded before the debit began is a different refund, and a
 * later payment that happens to repeat its balances is still resolved by its own refund.
 *
 * That rule cannot tell two refunds apart when both payments were already held before the first was refunded. So
 * staff may assert, for one resolution, that its refund is a second, separate one: only the matching balances rule is
 * skipped, and the claim records that staff said so.
 *
 * Claims for one payer take turns on that payer's row in npc_refund_claim_payers, so two resolutions racing for one
 * refund cannot both see no earlier claim.
 *
 * Each claim also records the SHA-256 of the refund-observed.json it was resolved with, and that file closes only one
 * payment, whoever paid and in either table. And a refund must leave the payer somewhere other than where the debit
 * left them: a payer still exactly at the debit's after balance looks the same as one who was never refunded.
 */
public final class RefundClaims {
    /** The table that holds the payment a refund resolves. Resolution ids are unique only within one of them. */
    public enum Source {
        TRANSFER("SELECT created_at FROM npc_transfer_attempts WHERE attempt_id=?"),
        // An order has no separate debit time. It is issued just before its debit and never after it, so timing the
        // debit from the issue can only refuse more, never less.
        COMMERCE("SELECT created_at FROM npc_commerce_orders WHERE order_id=?");

        private final String debitBegan;

        Source(String debitBegan) { this.debitBegan = debitBegan; }
    }

    private static final int DUPLICATE_KEY = 1062;
    private static final String REUSED = "This refund was already used to resolve another held payment. If staff made a"
            + " second, separate refund for this held payment, run the tool again with --apply --distinct-refund";
    private static final String EVIDENCE_REUSED = "This refund evidence file was already used to resolve another held payment";
    private static final String NOT_MOVED = "The refund ends at the balance this payment's debit left, so it shows no refund";

    private RefundClaims() { }

    /**
     * Caller owns the transaction; a refusal leaves the claim unwritten and is rolled back with everything else.
     * paymentId is the transfer attempt or the commerce order the resolution closes. debitAfterBits is the payer's
     * balance the debit observed after its call, and evidenceSha256 hashes the refund-observed.json the staff refund
     * was recorded in. distinctRefund is staff asserting that this refund is a second, separate refund for this
     * payment, so an earlier claim with the same balances is not this one. It skips only that rule, and is recorded
     * with the claim.
     */
    public static void claim(Connection c, Source source, UUID paymentId, UUID resolutionId, UUID payer,
                             long refundBeforeBits, long refundAfterBits, long debitAfterBits,
                             String evidenceSha256, boolean distinctRefund) throws SQLException {
        Objects.requireNonNull(source);
        Objects.requireNonNull(paymentId);
        Objects.requireNonNull(resolutionId);
        Objects.requireNonNull(payer);
        if (evidenceSha256 == null || !evidenceSha256.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid refund evidence hash");
        if (c.getAutoCommit()) throw new SQLException("Refund claim requires caller-owned transaction");
        // Compared as balances, so a refund ending at -0.0 cannot pass for one that left the payer at 0.0.
        if (Double.longBitsToDouble(refundAfterBits) == Double.longBitsToDouble(debitAfterBits)) throw new SQLException(NOT_MOVED);
        // Plain reads cannot block an insert at READ COMMITTED, so the payer's row is written to take its lock.
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO npc_refund_claim_payers(payer_id) VALUES (?) ON DUPLICATE KEY UPDATE payer_id=payer_id")) {
            ps.setQueryTimeout(5);
            ps.setBytes(1, Uuids.toBytes(payer));
            ps.executeUpdate();
        }
        // Any payer, either table. The unique key on the insert below also stops two payers racing with one file.
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM npc_refund_claims WHERE evidence_sha256=?")) {
            ps.setQueryTimeout(5);
            ps.setString(1, evidenceSha256);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) throw new SQLException(EVIDENCE_REUSED);
            }
        }
        // Times are compared in the database, where both were written by the same clock. The debit time is still
        // required when staff assert a distinct refund; only the matching balances are let through.
        try (PreparedStatement ps = c.prepareStatement("SELECT d.created_at, EXISTS (SELECT 1 FROM npc_refund_claims k"
                + " WHERE k.payer_id=? AND (k.refund_before_bits=? OR k.refund_after_bits=?) AND k.recorded_at>d.created_at)"
                + " FROM (" + source.debitBegan + ") d")) {
            ps.setQueryTimeout(5);
            ps.setBytes(1, Uuids.toBytes(payer));
            ps.setLong(2, refundBeforeBits);
            ps.setLong(3, refundAfterBits);
            ps.setBytes(4, Uuids.toBytes(paymentId));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getTimestamp(1) == null) throw new SQLException("Refund claim has no debit time");
                if (rs.getBoolean(2) && !distinctRefund) throw new SQLException(REUSED);
            }
        }
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO npc_refund_claims(source,resolution_id,payer_id,refund_before_bits,refund_after_bits,evidence_sha256,
                distinct_override) VALUES (?,?,?,?,?,?,?)
                """)) {
            ps.setQueryTimeout(5);
            ps.setString(1, source.name());
            ps.setBytes(2, Uuids.toBytes(resolutionId));
            ps.setBytes(3, Uuids.toBytes(payer));
            ps.setLong(4, refundBeforeBits);
            ps.setLong(5, refundAfterBits);
            ps.setString(6, evidenceSha256);
            ps.setBoolean(7, distinctRefund);
            if (ps.executeUpdate() != 1) throw new SQLException("Refund claim did not affect exactly one row");
        } catch (SQLException failure) {
            if (failure.getErrorCode() == DUPLICATE_KEY && "23000".equals(failure.getSQLState())) {
                if (String.valueOf(failure.getMessage()).contains("uq_refund_claim_evidence"))
                    throw new SQLException(EVIDENCE_REUSED, failure);
                throw new SQLException("This resolution id already claimed a refund", failure);
            }
            throw failure;
        }
    }

    /** The line a recovery tool prints and logs when staff assert a distinct refund. */
    public static String distinctRefundNotice(UUID paymentId, String operator) {
        return "Staff asserted a distinct refund: payment " + paymentId + " was resolved by a second, separate refund"
                + " with the same balances as one already used (operator " + operator + ").";
    }

    /**
     * Prints and logs what an applied run with --distinct-refund recorded, read back from the resolution's claim: the
     * notice and the other claims for the payer with the same balances, so a refund told apart from the wrong one can
     * be found later. A replay of a resolution recorded without the flag says the flag changed nothing. The resolution
     * is already committed and its domain side settled before this runs; runtime and linkage errors here are caught.
     */
    public static void reportDistinctRefund(Database db, Source source, UUID paymentId, UUID resolutionId, String operator,
                                            Logger logger) {
        String report;
        try {
            report = distinctRefundReport(db, source, paymentId, resolutionId, operator);
        } catch (Exception | LinkageError failed) {
            report = "--distinct-refund was given for payment " + paymentId + ", but its claim could not be read back ("
                    + failed.getClass().getName() + "); check npc_refund_claims for resolution " + resolutionId + ".";
        }
        try { System.out.println(report); } catch (RuntimeException | LinkageError ignored) { }
        try { logger.warning(report); } catch (RuntimeException | LinkageError ignored) { }
    }

    static String distinctRefundReport(Database db, Source source, UUID paymentId, UUID resolutionId, String operator)
            throws SQLException {
        try (Connection c = db.getConnection()) {
            byte[] payer;
            long before, after;
            try (PreparedStatement ps = c.prepareStatement("SELECT distinct_override,payer_id,refund_before_bits,"
                    + "refund_after_bits FROM npc_refund_claims WHERE source=? AND resolution_id=?")) {
                ps.setQueryTimeout(5);
                ps.setString(1, source.name());
                ps.setBytes(2, Uuids.toBytes(resolutionId));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return "--distinct-refund was given for payment " + paymentId + ", but resolution "
                            + resolutionId + " has no refund claim.";
                    if (!rs.getBoolean(1)) return "--distinct-refund was given for payment " + paymentId + ", but resolution "
                            + resolutionId + " was recorded earlier without it, so the flag changed nothing.";
                    payer = rs.getBytes(2);
                    before = rs.getLong(3);
                    after = rs.getLong(4);
                }
            }
            List<String> same = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT source,resolution_id,recorded_at FROM npc_refund_claims"
                    + " WHERE payer_id=? AND (refund_before_bits=? OR refund_after_bits=?) AND NOT (source=? AND resolution_id=?)"
                    + " ORDER BY recorded_at,source,resolution_id LIMIT 5")) {
                ps.setQueryTimeout(5);
                ps.setBytes(1, payer);
                ps.setLong(2, before);
                ps.setLong(3, after);
                ps.setString(4, source.name());
                ps.setBytes(5, Uuids.toBytes(resolutionId));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next())
                        same.add(rs.getString(1) + " resolution " + Uuids.fromBytes(rs.getBytes(2)) + " at " + rs.getString(3));
                }
            }
            return distinctRefundNotice(paymentId, operator) + " Claims with the same balances: "
                    + (same.isEmpty() ? "none" : String.join("; ", same)) + ".";
        }
    }
}

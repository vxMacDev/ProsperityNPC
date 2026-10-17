package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.Uuids;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import static com.prosperitysmp.npc.economy.TransferProof.*;

/**
 * SQL journal only: no Vault calls, Bukkit objects, automatic retry, refund or
 * runtime wiring. A fresh, acknowledged Start is necessary but NOT sufficient
 * authority to move money. The coordinator must separately enforce live ARM,
 * permissions, provider/account identity, budgets, lifecycle and single-flight.
 * An uncertain commit is read back, never converted into another money call.
 */
public final class TransferJournal {
    public enum State { DEBIT_INTENT, CREDIT_READY, CREDIT_INTENT, PAID, DECLINED, AMBIGUOUS, COMPENSATED }
    public record Handle(UUID attemptId, long eventId, UUID runId, String providerSha256) {
        public Handle {
            Objects.requireNonNull(attemptId);
            Objects.requireNonNull(runId);
            if (eventId <= 0 || providerSha256 == null || !providerSha256.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Invalid transfer handle");
        }
    }
    public record Receipt(Handle handle, String requestSha256, UUID payer, UUID payee,
                          long cents, State state, String outcome, boolean creditStarted) {
        public Receipt {
            Objects.requireNonNull(handle);
            Objects.requireNonNull(payer);
            Objects.requireNonNull(payee);
            Objects.requireNonNull(state);
            if (cents <= 0 || payer.equals(payee) || requestSha256 == null || !requestSha256.matches("[a-f0-9]{64}")
                    || ((state == State.CREDIT_INTENT || state == State.PAID) && !creditStarted)
                    || ((state == State.DEBIT_INTENT || state == State.CREDIT_READY || state == State.DECLINED) && creditStarted))
                throw new IllegalArgumentException("Invalid transfer receipt");
        }
    }
    /** A replay returns newlyStarted=false even when the stored intent matches exactly. */
    public record Start(boolean newlyStarted, Receipt receipt) { }
    /** One stored operator resolution. Refund bits are null only for a legacy refusal. */
    private record Resolution(UUID resolutionId, String operator, String evidenceSha, String kind, String creditProof,
                              Long beforeBits, Long afterBits) { }
    private record Event(long id, String key, EventSource source, UUID property, UUID counterparty,
                         int epoch, UUID benefitUnit, String day, long cents, String state, Long paid) {
        String fingerprint() {
            // All fields preceding key have delimiter-free typed encodings; key is last.
            return sha("transfer-v1|" + id + "|" + source + "|" + property + "|" + counterparty
                    + "|" + epoch + "|" + benefitUnit + "|" + day + "|" + cents + "|" + key);
        }
    }
    private final Database database;
    private final UUID reserve;
    public TransferJournal(Database database, UUID reserve) {
        this.database = Objects.requireNonNull(database);
        this.reserve = Objects.requireNonNull(reserve);
    }

    /** Stores DEBIT_INTENT with the ledger claim in one commit. Never returns a second dispatch. */
    public Start beginDebit(Handle handle) throws SQLException {
        Objects.requireNonNull(handle);
        return transaction(c -> {
            Event event = lockEvent(c, handle.eventId());
            Optional<Receipt> old = byEvent(c, event.id(), true);
            if (old.isPresent()) {
                Receipt receipt = old.get();
                binding(c, event, receipt, handle);
                return new Start(false, receipt);
            }
            if (!"PENDING".equals(event.state()) || event.paid() != null)
                throw new SQLException("Transfer requires an untouched pending event");
            validateMirror(c, event);
            UUID payer = event.source().isPayout() ? reserve : event.counterparty();
            UUID payee = event.source().isPayout() ? event.counterparty() : reserve;
            if (payer.equals(payee)) throw new SQLException("Transfer payer equals payee");
            Receipt receipt = new Receipt(handle, event.fingerprint(), payer, payee, event.cents(), State.DEBIT_INTENT, null, false);
            try (PreparedStatement ps = prepare(c, """
                    INSERT INTO npc_transfer_attempts(attempt_id,event_id,run_id,provider_sha256,request_sha256,
                      payer_uuid,payee_uuid,amount_cents,state) VALUES(?,?,?,?,?,?,?,?,'DEBIT_INTENT')
                    """)) {
                id(ps, 1, handle.attemptId());
                ps.setLong(2, event.id());
                id(ps, 3, handle.runId());
                ps.setString(4, handle.providerSha256());
                ps.setString(5, receipt.requestSha256());
                id(ps, 6, payer);
                id(ps, 7, payee);
                ps.setLong(8, event.cents());
                one(ps.executeUpdate());
            }
            ledger(c, event.id(), "CLAIMED", null);
            return new Start(true, receipt);
        });
    }

    public Optional<Receipt> find(long eventId) throws SQLException {
        if (eventId <= 0) return Optional.empty();
        return transaction(c -> {
            if (byEvent(c, eventId, false).isEmpty()) return Optional.empty();
            Event event = lockEvent(c, eventId);
            Receipt receipt = byEvent(c, eventId, true).orElseThrow(() -> new SQLException("Readback intent vanished"));
            binding(c, event, receipt, receipt.handle());
            return Optional.of(receipt);
        });
    }
    public Optional<Observation> observation(UUID attempt, Leg leg) throws SQLException {
        Objects.requireNonNull(attempt);
        Objects.requireNonNull(leg);
        try (Connection c = database.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            return observation(c, attempt, leg, false);
        }
    }

    /** Caller already holds property/event locks. Proof is rechecked in the benefit transaction. */
    boolean confirmedPaid(Connection c, long eventId) throws SQLException {
        return confirmedState(c, eventId, State.PAID);
    }

    /** A failed ledger label alone cannot authorize another charge. */
    boolean confirmedDeclined(Connection c, long eventId) throws SQLException {
        return confirmedState(c, eventId, State.DECLINED) || confirmedState(c, eventId, State.COMPENSATED);
    }

    boolean confirmedCompensated(Connection c, long eventId) throws SQLException {
        return confirmedState(c, eventId, State.COMPENSATED);
    }

    /**
     * Offline/operator boundary only: records an independently completed refund, never calls Vault.
     * Caller must verify evidence bytes/hash, exclusive wallet custody and the actual refund.
     * Only a fully observed debit can use this resolution, and only when the credit either never started
     * or was refused before its money call. refundEvidenceSha is the SHA-256 of the refund-observed.json the
     * refund was recorded in; one such file resolves one payment.
     */
    public Receipt resolveRefundedDebit(Handle expected, UUID resolutionId, String operator, String evidenceSha,
                                       String refundEvidenceSha, long refundBeforeBits, long refundAfterBits) throws SQLException {
        return resolveRefundedDebit(expected, resolutionId, operator, evidenceSha, refundEvidenceSha, refundBeforeBits,
                refundAfterBits, false, true);
    }

    /**
     * As above. legacyCreditVerified says the caller has already proved, from outside the database, which build
     * wrote this row and that its credit gate ran before the money call. Only then may a credit stored the old
     * way (a throw with no response and an unchanged balance) count as refused.
     */
    public Receipt resolveRefundedDebit(Handle expected, UUID resolutionId, String operator, String evidenceSha,
                                       String refundEvidenceSha, long refundBeforeBits, long refundAfterBits,
                                       boolean legacyCreditVerified, boolean apply) throws SQLException {
        return resolveRefundedDebit(expected, resolutionId, operator, evidenceSha, refundEvidenceSha, refundBeforeBits,
                refundAfterBits, legacyCreditVerified, apply, false);
    }

    /**
     * As above. distinctRefund is staff asserting that this refund is a second, separate refund for this payment;
     * see {@link RefundClaims#claim}.
     */
    public Receipt resolveRefundedDebit(Handle expected, UUID resolutionId, String operator, String evidenceSha,
                                       String refundEvidenceSha, long refundBeforeBits, long refundAfterBits,
                                       boolean legacyCreditVerified, boolean apply, boolean distinctRefund) throws SQLException {
        Objects.requireNonNull(expected);
        Objects.requireNonNull(resolutionId);
        operatorEvidence(operator, evidenceSha);
        return transaction(apply, c -> {
            Event event = lockEvent(c, expected.eventId());
            Receipt receipt = required(c, expected);
            binding(c, event, receipt, expected);
            if (receipt.state() == State.COMPENSATED) {
                Resolution r = resolution(c, expected.attemptId());
                if (r == null || !"REFUND".equals(r.kind()) || !resolutionId.equals(r.resolutionId())
                        || !operator.equals(r.operator()) || !evidenceSha.equals(r.evidenceSha())
                        || !Objects.equals(refundBeforeBits, r.beforeBits()) || !Objects.equals(refundAfterBits, r.afterBits()))
                    throw new SQLException("Recovery replay conflicts with completed resolution");
                return receipt;
            }
            if (receipt.state() != State.AMBIGUOUS) throw new SQLException("Not an isolated ambiguous debit");
            Observation debit = observation(c, expected.attemptId(), Leg.DEBIT, true)
                    .orElseThrow(() -> new SQLException("Not an isolated ambiguous debit"));
            Observation credit = observation(c, expected.attemptId(), Leg.CREDIT, true).orElse(null);
            String creditProof;
            if (!receipt.creditStarted() && credit == null) creditProof = null;
            else if (receipt.creditStarted() && credit != null && credit.refusedBeforeCall() != null) creditProof = "REFUSED";
            else if (receipt.creditStarted() && legacyCreditVerified && legacyGateRefused(receipt.cents(), Leg.CREDIT, credit))
                creditProof = "LEGACY";
            else throw new SQLException("Not an isolated ambiguous debit");
            if (!refundRestoresDebit(receipt.cents(), debit, refundBeforeBits, refundAfterBits))
                throw new SQLException("Refund must exactly restore the fully observed debit");
            // The refund is judged only by its own change, so this payer's other held rows of the same amount
            // would accept it too. It may close only one of them.
            RefundClaims.claim(c, RefundClaims.Source.TRANSFER, expected.attemptId(), resolutionId, receipt.payer(),
                    refundBeforeBits, refundAfterBits, Double.doubleToRawLongBits(debit.afterDollars()), refundEvidenceSha,
                    distinctRefund);
            try (PreparedStatement ps = prepare(c, """
                    INSERT INTO npc_transfer_resolutions(attempt_id,resolution_id,operator_ref,evidence_sha256,
                    original_outcome,refund_before_bits,refund_after_bits,kind,credit_proof) VALUES (?,?,?,?,?,?,?,'REFUND',?)
                    """)) {
                id(ps, 1, expected.attemptId());
                id(ps, 2, resolutionId);
                ps.setString(3, operator);
                ps.setString(4, evidenceSha);
                ps.setString(5, receipt.outcome());
                ps.setLong(6, refundBeforeBits);
                ps.setLong(7, refundAfterBits);
                ps.setString(8, creditProof);
                one(ps.executeUpdate());
            }
            ledger(c, event.id(), "FAILED", 0L);
            return finish(c, receipt, State.COMPENSATED, "OPERATOR_REFUND_VERIFIED");
        });
    }

    /**
     * Offline/operator boundary only. A debit stored by an older build as a throw with no response and an
     * unchanged balance, where that build's gate would have refused before the money call. The caller must
     * already have proved, from the install record and the jar bytes, that the row was written by such a build.
     * No money moved, so this finishes DECLINED, never COMPENSATED. With apply false every check and write runs
     * and is then rolled back, so a dry run tests exactly what an applied run would do.
     */
    public Receipt resolveLegacyRefusal(Handle expected, UUID resolutionId, String operator, String evidenceSha,
                                        boolean apply) throws SQLException {
        Objects.requireNonNull(expected);
        Objects.requireNonNull(resolutionId);
        operatorEvidence(operator, evidenceSha);
        return transaction(apply, c -> {
            Event event = lockEvent(c, expected.eventId());
            Receipt receipt = required(c, expected);
            binding(c, event, receipt, expected);
            if (receipt.state() == State.DECLINED) {
                Resolution r = resolution(c, expected.attemptId());
                if (r == null || !"LEGACY_REFUSAL".equals(r.kind()) || !resolutionId.equals(r.resolutionId())
                        || !operator.equals(r.operator()) || !evidenceSha.equals(r.evidenceSha()))
                    throw new SQLException("Recovery replay conflicts with completed resolution");
                return receipt;
            }
            if (receipt.state() != State.AMBIGUOUS || receipt.creditStarted()
                    || observation(c, expected.attemptId(), Leg.CREDIT, true).isPresent())
                throw new SQLException("Not an isolated ambiguous debit");
            Observation debit = observation(c, expected.attemptId(), Leg.DEBIT, true).orElse(null);
            if (!legacyGateRefused(receipt.cents(), Leg.DEBIT, debit))
                throw new SQLException("Stored debit is not one the old gate refused");
            try (PreparedStatement ps = prepare(c, """
                    INSERT INTO npc_transfer_resolutions(attempt_id,resolution_id,operator_ref,evidence_sha256,
                    original_outcome,refund_before_bits,refund_after_bits,kind,credit_proof)
                    VALUES (?,?,?,?,?,NULL,NULL,'LEGACY_REFUSAL',NULL)
                    """)) {
                id(ps, 1, expected.attemptId());
                id(ps, 2, resolutionId);
                ps.setString(3, operator);
                ps.setString(4, evidenceSha);
                ps.setString(5, receipt.outcome());
                one(ps.executeUpdate());
            }
            ledger(c, event.id(), "FAILED", 0L);
            return finish(c, receipt, State.DECLINED, "LEGACY_REFUSAL_VERIFIED");
        });
    }

    private static void operatorEvidence(String operator, String evidenceSha) {
        if (operator == null || !operator.matches("[A-Za-z0-9_.:@-]{1,96}")
                || evidenceSha == null || !evidenceSha.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Invalid operator recovery evidence");
    }

    /**
     * A DECLINED row is proved by a debit that made no movement and no credit, or, for an older build, by a
     * legacy refusal resolution over a debit that build's gate would have refused before its money call.
     */
    public static boolean declinedProof(Connection c, Receipt receipt, Observation debit, Observation credit) throws SQLException {
        return declinedProof(c, receipt.handle().attemptId(), receipt.cents(), receipt.creditStarted(), debit, credit);
    }

    /** The same proof for a reader that holds the attempt row's fields rather than a Receipt. */
    public static boolean declinedProof(Connection c, UUID attempt, long cents, boolean creditStarted,
                                        Observation debit, Observation credit) throws SQLException {
        if (credit != null) return false;
        if (evaluate(cents, Leg.DEBIT, debit) == LegOutcome.NO_MOVEMENT) return true;
        if (creditStarted) return false;
        Resolution r = resolution(c, attempt);
        return r != null && "LEGACY_REFUSAL".equals(r.kind()) && legacyGateRefused(cents, Leg.DEBIT, debit);
    }

    private boolean confirmedState(Connection c, long eventId, State expected) throws SQLException {
        if (c.getAutoCommit()) throw new SQLException("Payment proof requires caller-owned transaction");
        Optional<Receipt> found = byEvent(c, eventId, true);
        if (found.isEmpty() || found.get().state() != expected) return false;
        Receipt receipt = found.get();
        binding(c, event(c, eventId, true), receipt, receipt.handle());
        return true;
    }

    /** Duplicate debit acknowledgement must match bit-for-bit; it cannot restart either leg. */
    public Receipt recordDebit(Handle handle, Observation evidence) throws SQLException {
        return record(handle, Leg.DEBIT, evidence);
    }
    /** Separate durable credit intent: persisting a debit response does not dispatch credit. */
    public Start beginCredit(Handle handle) throws SQLException {
        return transaction(c -> {
            Event event = lockEvent(c, handle.eventId());
            Receipt receipt = required(c, handle);
            binding(c, event, receipt, handle);
            if (receipt.state() != State.CREDIT_READY) return new Start(false, receipt);
            Observation debit = observation(c, handle.attemptId(), Leg.DEBIT, true)
                    .orElseThrow(() -> new SQLException("Debit evidence missing"));
            if (evaluate(receipt.cents(), Leg.DEBIT, debit) != LegOutcome.CONFIRMED)
                throw new SQLException("Credit intent lacks confirmed debit");
            return new Start(true, finish(c, receipt, State.CREDIT_INTENT, null));
        });
    }
    /** Credit evidence, PAID and lifetime funding are one transaction, never a boolean ack. */
    public Receipt recordCredit(Handle handle, Observation evidence) throws SQLException {
        return record(handle, Leg.CREDIT, evidence);
    }

    private Receipt record(Handle handle, Leg leg, Observation evidence) throws SQLException {
        Objects.requireNonNull(handle);
        Objects.requireNonNull(evidence);
        return transaction(c -> {
            Event event = lockEvent(c, handle.eventId());
            Receipt receipt = required(c, handle);
            binding(c, event, receipt, handle);
            Optional<Observation> old = observation(c, handle.attemptId(), leg, true);
            if (old.isPresent()) {
                if (!same(old.get(), evidence)) throw new SQLException("Conflicting transfer acknowledgement");
                return receipt;
            }
            State expected = leg == Leg.DEBIT ? State.DEBIT_INTENT : State.CREDIT_INTENT;
            if (receipt.state() != expected && receipt.state() != State.AMBIGUOUS)
                throw new SQLException("Observation without matching leg intent");
            // Credit without a confirmed debit is never legitimate, even in late recovery.
            Observation debit = observation(c, handle.attemptId(), Leg.DEBIT, true).orElse(null);
            if (leg == Leg.CREDIT && (!receipt.creditStarted() || evaluate(receipt.cents(), Leg.DEBIT, debit) != LegOutcome.CONFIRMED))
                throw new SQLException("Credit observation without confirmed debit");
            insertObservation(c, handle.attemptId(), leg, evidence);
            // Preserve late evidence without turning a recovered attempt into new authority.
            if (receipt.state() == State.AMBIGUOUS) return receipt;
            if (leg == Leg.DEBIT) {
                return switch (evaluate(receipt.cents(), leg, evidence)) {
                    case CONFIRMED -> finish(c, receipt, State.CREDIT_READY, null);
                    case NO_MOVEMENT -> {
                        ledger(c, event.id(), "FAILED", 0L);
                        yield finish(c, receipt, State.DECLINED,
                                evidence.refusedBeforeCall() != null ? "DEBIT_REFUSED_BEFORE_CALL" : "DEBIT_DECLINED");
                    }
                    case AMBIGUOUS -> ambiguous(c, receipt, "DEBIT_UNCERTAIN");
                };
            }
            // A credit refused before its call leaves the debit to be refunded by staff; it is never retried.
            if (evaluateTransfer(receipt.cents(), debit, evidence) != TransferOutcome.PAID)
                return ambiguous(c, receipt, evidence.refusedBeforeCall() != null ? "CREDIT_REFUSED_BEFORE_CALL" : "CREDIT_UNCERTAIN");
            String column = event.source().isPayout() ? "successful_cash_payouts_cents" : "successful_cash_sinks_cents";
            try (PreparedStatement ps = prepare(c, "UPDATE npc_property_funding SET " + column + "=" + column
                    + "+?,updated_at=CURRENT_TIMESTAMP(3) WHERE property_id=?")) {
                ps.setLong(1, receipt.cents());
                id(ps, 2, event.property());
                if (ps.executeUpdate() != 1) return ambiguous(c, receipt, "FUNDING_MISSING");
            }
            ledger(c, event.id(), "PAID", receipt.cents());
            return finish(c, receipt, State.PAID, "TRANSFER_CONFIRMED");
        });
    }

    /** One payment that holds the runtimes, as stored. Read-only; nothing here is a decision. */
    public record Held(long eventId, State state, long cents, boolean creditStarted, Observation debit, Observation credit,
                       String createdAt) {
        public HeldClass heldClass() {
            return HeldClass.of(state == State.AMBIGUOUS, cents, creditStarted, debit, credit);
        }
    }

    /** Every row the startup hold counts, oldest first, up to limit. Reads only. */
    public List<Held> held(int limit) throws SQLException {
        List<Held> result = new ArrayList<>();
        try (Connection c = database.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setReadOnly(true);
            c.setAutoCommit(false);
            try {
                List<long[]> rows = new ArrayList<>();
                try (PreparedStatement ps = prepare(c, """
                        SELECT event_id FROM npc_transfer_attempts
                        WHERE state IN ('DEBIT_INTENT','CREDIT_READY','CREDIT_INTENT','AMBIGUOUS') ORDER BY event_id LIMIT ?
                        """)) {
                    ps.setInt(1, Math.clamp(limit, 0, 100_000));
                    try (ResultSet rs = ps.executeQuery()) { while (rs.next()) rows.add(new long[]{rs.getLong(1)}); }
                }
                for (long[] row : rows) {
                    Receipt receipt = byEvent(c, row[0], false).orElseThrow(() -> new SQLException("Held intent vanished"));
                    String created;
                    try (PreparedStatement ps = prepare(c, "SELECT created_at FROM npc_transfer_attempts WHERE event_id=?")) {
                        ps.setLong(1, row[0]);
                        try (ResultSet rs = ps.executeQuery()) { rs.next(); created = rs.getString(1); }
                    }
                    UUID attempt = receipt.handle().attemptId();
                    result.add(new Held(row[0], receipt.state(), receipt.cents(), receipt.creditStarted(),
                            observation(c, attempt, Leg.DEBIT, false).orElse(null),
                            observation(c, attempt, Leg.CREDIT, false).orElse(null), created));
                }
            } finally {
                c.rollback();
            }
        }
        return result;
    }

    /** Rows the startup hold sees in this table. The classify total must equal it. */
    public long heldCount() throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c, """
                SELECT COUNT(*) FROM npc_transfer_attempts WHERE state IN ('DEBIT_INTENT','CREDIT_READY','CREDIT_INTENT','AMBIGUOUS')
                """); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Bounded startup recovery. No interrupted intent, including CREDIT_READY, gets another dispatch. */
    public int recoverOtherRuns(UUID currentRun, int limit) throws SQLException {
        Objects.requireNonNull(currentRun);
        List<Long> ids = new ArrayList<>();
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c, """
                SELECT event_id FROM npc_transfer_attempts WHERE run_id<>?
                AND state IN ('DEBIT_INTENT','CREDIT_READY','CREDIT_INTENT') ORDER BY event_id LIMIT ?
                """)) {
            id(ps, 1, currentRun);
            ps.setInt(2, Math.clamp(limit, 0, 100));
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) ids.add(rs.getLong(1)); }
        }
        int changed = 0;
        for (long eventId : ids) {
            changed += transaction(c -> {
                Event event = lockEvent(c, eventId);
                Receipt receipt = byEvent(c, eventId, true).orElseThrow(() -> new SQLException("Recovery intent missing"));
                binding(c, event, receipt, receipt.handle());
                if (receipt.handle().runId().equals(currentRun) || terminal(receipt.state())) return 0;
                ambiguous(c, receipt, "INTERRUPTED_RUN");
                return 1;
            });
        }
        return changed;
    }

    private Receipt ambiguous(Connection c, Receipt receipt, String reason) throws SQLException {
        ledger(c, receipt.handle().eventId(), "AMBIGUOUS", null);
        return finish(c, receipt, State.AMBIGUOUS, reason);
    }
    private static boolean terminal(State s) {
        return s == State.PAID || s == State.DECLINED || s == State.AMBIGUOUS || s == State.COMPENSATED;
    }
    private static Receipt finish(Connection c, Receipt r, State state, String outcome) throws SQLException {
        try (PreparedStatement ps = prepare(c, "UPDATE npc_transfer_attempts SET state=?,outcome_code=?,updated_at=CURRENT_TIMESTAMP(3),"
                + "credit_intent_at=" + (state == State.CREDIT_INTENT ? "CURRENT_TIMESTAMP(3)" : "credit_intent_at")
                + " WHERE attempt_id=? AND state=?")) {
            ps.setString(1, state.name());
            ps.setString(2, outcome);
            id(ps, 3, r.handle().attemptId());
            ps.setString(4, r.state().name());
            one(ps.executeUpdate());
        }
        return new Receipt(r.handle(), r.requestSha256(), r.payer(), r.payee(), r.cents(), state, outcome,
                r.creditStarted() || state == State.CREDIT_INTENT);
    }
    private static void ledger(Connection c, long eventId, String state, Long paid) throws SQLException {
        try (PreparedStatement ps = prepare(c, "UPDATE npc_economy_events SET state=?,paid_amount_cents=?,"
                + "claimed_at=COALESCE(claimed_at,CURRENT_TIMESTAMP(3)),finalized_at="
                + (state.equals("CLAIMED") ? "NULL" : "CURRENT_TIMESTAMP(3)") + " WHERE event_id=?")) {
            ps.setString(1, state);
            nullableLong(ps, 2, paid);
            ps.setLong(3, eventId);
            one(ps.executeUpdate());
        }
    }
    private static Receipt required(Connection c, Handle handle) throws SQLException {
        return byEvent(c, handle.eventId(), true).orElseThrow(() -> new SQLException("Transfer intent missing"));
    }
    private void binding(Connection c, Event e, Receipt r, Handle expected) throws SQLException {
        if (!r.handle().equals(expected) || !r.requestSha256().equals(e.fingerprint()) || r.cents() != e.cents()
                || !r.payer().equals(e.source().isPayout() ? reserve : e.counterparty())
                || !r.payee().equals(e.source().isPayout() ? e.counterparty() : reserve))
            throw new SQLException("Transfer identity mismatch");
        String expectedState = switch (r.state()) {
            case PAID -> "PAID";
            case DECLINED, COMPENSATED -> "FAILED";
            case AMBIGUOUS -> "AMBIGUOUS";
            default -> "CLAIMED";
        };
        if (!expectedState.equals(e.state()) || (r.state() == State.PAID && !Objects.equals(e.paid(), r.cents()))
                || ((r.state() == State.DECLINED || r.state() == State.COMPENSATED) && !Objects.equals(e.paid(), 0L))
                || (!terminal(r.state()) && e.paid() != null))
            throw new SQLException("Transfer ledger state mismatch");
        proofs(c, r);
    }
    /** Cross-table state is evidence only when the required durable observations agree. */
    private static void proofs(Connection c, Receipt receipt) throws SQLException {
        Observation debit = observation(c, receipt.handle().attemptId(), Leg.DEBIT, true).orElse(null);
        Observation credit = observation(c, receipt.handle().attemptId(), Leg.CREDIT, true).orElse(null);
        boolean confirmedDebit = evaluate(receipt.cents(), Leg.DEBIT, debit) == LegOutcome.CONFIRMED;
        boolean valid = switch (receipt.state()) {
            case DEBIT_INTENT -> debit == null && credit == null;
            case CREDIT_READY, CREDIT_INTENT -> confirmedDebit && credit == null;
            case PAID -> evaluateTransfer(receipt.cents(), debit, credit) == TransferOutcome.PAID;
            case DECLINED -> declinedProof(c, receipt, debit, credit);
            case COMPENSATED -> {
                if (debit == null) yield false;
                Resolution r = resolution(c, receipt.handle().attemptId());
                if (r == null || !"REFUND".equals(r.kind()) || r.beforeBits() == null || r.afterBits() == null
                        || r.evidenceSha() == null || !r.evidenceSha().matches("[a-f0-9]{64}")) yield false;
                // The credit either never started, or it stopped before its money call.
                boolean creditProved = !receipt.creditStarted()
                        ? credit == null && r.creditProof() == null
                        : credit != null && (("REFUSED".equals(r.creditProof()) && credit.refusedBeforeCall() != null)
                                || ("LEGACY".equals(r.creditProof()) && legacyGateRefused(receipt.cents(), Leg.CREDIT, credit)));
                yield creditProved && refundRestoresDebit(receipt.cents(), debit, r.beforeBits(), r.afterBits());
            }
            case AMBIGUOUS -> (!receipt.creditStarted() || confirmedDebit)
                    && (credit == null || (receipt.creditStarted() && confirmedDebit));
        };
        if (!valid) throw new SQLException("Transfer phase and durable evidence disagree");
    }
    private static Resolution resolution(Connection c, UUID attempt) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_transfer_resolutions WHERE attempt_id=? FOR UPDATE")) {
            id(ps, 1, attempt);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Resolution(uuid(rs, "resolution_id"), rs.getString("operator_ref"), rs.getString("evidence_sha256"),
                        rs.getString("kind"), rs.getString("credit_proof"),
                        number(rs, "refund_before_bits"), number(rs, "refund_after_bits"));
            }
        }
    }
    private static void validateMirror(Connection c, Event event) throws SQLException {
        try (PreparedStatement ps = prepare(c,
                "SELECT owner_uuid,ownership_epoch,enabled FROM npc_properties WHERE property_id=? FOR UPDATE")) {
            id(ps, 1, event.property());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || !rs.getBoolean("enabled") || !event.counterparty().equals(uuid(rs, "owner_uuid"))
                        || rs.getInt("ownership_epoch") != event.epoch()) throw new SQLException("Transfer property mirror changed");
            }
        }
    }
    private static Event lockEvent(Connection c, long eventId) throws SQLException {
        Event observed = event(c, eventId, false);
        // Same lock order as the purchase effect repository; no cross-thread Bukkit calls.
        try (PreparedStatement ps = prepare(c, "SELECT property_id FROM npc_properties WHERE property_id=? FOR UPDATE")) {
            id(ps, 1, observed.property());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) { /* row lock */ }
            }
        }
        Event locked = event(c, eventId, true);
        if (!observed.property().equals(locked.property())) throw new SQLException("Transfer property changed while locking");
        return locked;
    }
    private static Event event(Connection c, long eventId, boolean lock) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_economy_events WHERE event_id=?" + (lock ? " FOR UPDATE" : ""))) {
            ps.setLong(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("Transfer event missing");
                EventSource source = null;
                for (EventSource s : EventSource.values()) if (s.key().equals(rs.getString("source"))) source = s;
                UUID property = uuid(rs, "property_id"), counterparty = uuid(rs, "counterparty_uuid"),
                        benefit = uuid(rs, "benefit_unit_id");
                String day = rs.getString("economy_day"), key = rs.getString("idempotency_key");
                boolean validDay;
                try { validDay = day != null && day.matches("\\d{4}-\\d{2}-\\d{2}") && LocalDate.parse(day).getYear() > 0; }
                catch (DateTimeException invalid) { validDay = false; }
                if (source == null || !source.direction().moneyFlowKey().equals(rs.getString("direction"))
                        || property == null || counterparty == null || benefit == null || !validDay || key == null || key.isBlank()
                        || rs.getObject("ownership_epoch") == null || rs.getInt("ownership_epoch") < 0
                        || rs.getLong("requested_amount_cents") <= 0)
                    throw new SQLException("Invalid transfer event metadata");
                return new Event(eventId, key, source, property, counterparty, rs.getInt("ownership_epoch"), benefit, day,
                        rs.getLong("requested_amount_cents"), rs.getString("state"), number(rs, "paid_amount_cents"));
            }
        }
    }
    private static Optional<Receipt> byEvent(Connection c, long eventId, boolean lock) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_transfer_attempts WHERE event_id=?" + (lock ? " FOR UPDATE" : ""))) {
            ps.setLong(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                try {
                    return Optional.of(new Receipt(new Handle(uuid(rs, "attempt_id"), eventId, uuid(rs, "run_id"),
                            rs.getString("provider_sha256")),
                            rs.getString("request_sha256"), uuid(rs, "payer_uuid"), uuid(rs, "payee_uuid"), rs.getLong("amount_cents"),
                            State.valueOf(rs.getString("state")), rs.getString("outcome_code"),
                            rs.getTimestamp("credit_intent_at") != null));
                } catch (IllegalArgumentException | NullPointerException malformed) {
                    throw new SQLException("Invalid transfer receipt", malformed);
                }
            }
        }
    }
    private static Optional<Observation> observation(Connection c, UUID attempt, Leg leg, boolean lock) throws SQLException {
        try (PreparedStatement ps = prepare(c,
                "SELECT * FROM npc_transfer_observations WHERE attempt_id=? AND leg=?" + (lock ? " FOR UPDATE" : ""))) {
            id(ps, 1, attempt);
            ps.setString(2, leg.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                try {
                    return Optional.of(new Observation(rs.getBoolean("response_present"), rs.getBoolean("response_success"),
                            value(rs, "reported_bits"), value(rs, "before_bits"), value(rs, "after_bits"),
                            rs.getBoolean("threw"), refusal(rs.getString("refused_before_call")), rs.getString("refusal_cause")));
                } catch (IllegalArgumentException malformed) {
                    throw new SQLException("Invalid transfer observation", malformed);
                }
            }
        }
    }
    private static void insertObservation(Connection c, UUID attempt, Leg leg, Observation o) throws SQLException {
        try (PreparedStatement ps = prepare(c, """
                INSERT INTO npc_transfer_observations(attempt_id,leg,response_present,response_success,reported_bits,before_bits,after_bits,threw,
                  refused_before_call,refusal_cause)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """)) {
            id(ps, 1, attempt);
            ps.setString(2, leg.name());
            ps.setBoolean(3, o.responsePresent());
            ps.setBoolean(4, o.responseSuccess());
            nullableLong(ps, 5, bits(o.reportedDollars()));
            nullableLong(ps, 6, bits(o.beforeDollars()));
            nullableLong(ps, 7, bits(o.afterDollars()));
            ps.setBoolean(8, o.threw());
            ps.setString(9, o.refusedBeforeCall() == null ? null : o.refusedBeforeCall().name());
            ps.setString(10, o.refusalCause());
            one(ps.executeUpdate());
        }
    }
    private static boolean same(Observation a, Observation b) {
        return a.responsePresent() == b.responsePresent() && a.responseSuccess() == b.responseSuccess() && a.threw() == b.threw()
                && Objects.equals(bits(a.reportedDollars()), bits(b.reportedDollars()))
                && Objects.equals(bits(a.beforeDollars()), bits(b.beforeDollars()))
                && Objects.equals(bits(a.afterDollars()), bits(b.afterDollars()))
                && a.refusedBeforeCall() == b.refusedBeforeCall() && Objects.equals(a.refusalCause(), b.refusalCause());
    }
    private static Long bits(Double d) { return d == null ? null : Double.doubleToRawLongBits(d); }
    /** A stored refusal reason, or null for a leg that was called or is unknown. */
    public static Refusal refusal(String stored) {
        return stored == null ? null : Refusal.valueOf(stored);
    }
    private static Double value(ResultSet rs, String column) throws SQLException {
        Long n = number(rs, column);
        return n == null ? null : Double.longBitsToDouble(n);
    }
    private static Long number(ResultSet rs, String column) throws SQLException {
        long n = rs.getLong(column);
        return rs.wasNull() ? null : n;
    }
    private static void nullableLong(PreparedStatement ps, int i, Long n) throws SQLException {
        if (n == null) ps.setNull(i, Types.BIGINT);
        else ps.setLong(i, n);
    }
    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        byte[] b = rs.getBytes(column);
        return b == null ? null : Uuids.fromBytes(b);
    }
    private static void id(PreparedStatement ps, int i, UUID id) throws SQLException { ps.setBytes(i, Uuids.toBytes(id)); }
    private static void one(int rows) throws SQLException {
        if (rows != 1) throw new SQLException("Transfer write did not affect exactly one row");
    }
    private static PreparedStatement prepare(Connection c, String sql) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        try {
            ps.setQueryTimeout(5);
            return ps;
        } catch (SQLException e) {
            ps.close();
            throw e;
        }
    }
    private static String sha(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    @FunctionalInterface private interface Work<T> { T run(Connection c) throws SQLException; }
    private <T> T transaction(Work<T> work) throws SQLException {
        return transaction(true, work);
    }
    /** commit false runs the same work and rolls it back: an offline dry run. */
    private <T> T transaction(boolean commit, Work<T> work) throws SQLException {
        try (Connection c = database.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                if (commit) c.commit();
                else c.rollback();
                return result;
            } catch (SQLException | RuntimeException error) {
                try {
                    c.rollback();
                } catch (SQLException rollback) {
                    error.addSuppressed(rollback);
                }
                throw error;
            }
        }
    }
}

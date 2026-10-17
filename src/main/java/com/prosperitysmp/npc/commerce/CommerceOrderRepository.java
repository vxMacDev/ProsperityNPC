package com.prosperitysmp.npc.commerce;

import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.economy.RefundClaims;
import com.prosperitysmp.npc.economy.TransferProof;
import com.prosperitysmp.npc.economy.TransferProof.Leg;
import com.prosperitysmp.npc.economy.TransferProof.Observation;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Finite issuance and external-payment journal, not a Vault dispatcher or an accounted ledger.
 * Trusted callers supply native catalogue/full-price snapshots and per-day budgets. The runtime
 * must authorize on main, exclusively lease payer AND reserve wallets across all payment paths,
 * capture adjacent account evidence, and invoke Vault only for newly acknowledged Starts.
 * No refund/retry follows uncertainty. SQL never implies money/inventory provider atomicity.
 * Lock order: day -> material -> commerce player -> order -> custody player -> custody rows. beginDebit's DebitCheck
 * may then share-lock one partnership row; partnership transactions never take commerce locks.
 */
public final class CommerceOrderRepository {
    public static final int MAX_SWEEP = 100;
    public static final int MAX_RECENT = 8;
    public static final int MAX_OUTSTANDING = 8;
    public static final int MAX_PAID_PROOFS = 10_000;
    /** Normal issuance refusal, published only after rollback succeeds. Suppressed failures remain uncertain. */
    public static final class IssuanceRefused extends SQLException {
        private static final long serialVersionUID = 1L;
        private IssuanceRefused(String message, SQLException cause) { super(message, cause); }
    }
    private static final class IssuanceLimit extends SQLException {
        private static final long serialVersionUID = 1L;
        private IssuanceLimit(String message) { super(message); }
    }
    private static final String UNFULFILLED = """
            NOT EXISTS (SELECT 1 FROM npc_item_custody i WHERE i.request_nonce=o.custody_nonce
              AND i.state='DELIVERED' AND i.player_id=o.player_id AND i.offer_id=o.order_id
              AND i.source=o.source AND i.quantity=o.quantity AND i.reserved_at_millis>=o.output_ready_at_millis
              AND BINARY i.payload_sha256=BINARY SHA2(i.payload_bytes,256))
            """;
    public enum State { RESERVED, DEBIT_INTENT, CREDIT_READY, CREDIT_INTENT, FUNDED, DECLINED, AMBIGUOUS, CANCELLED, COMPENSATED }
    public enum PaymentDecision { VERIFIED_PAID, VERIFIED_NO_MOVEMENT, VERIFIED_REFUNDED_DEBIT, VERIFIED_LEGACY_REFUSAL }
    /**
     * Trusted offline adjudication, not authorization to issue a refund. Case bytes must document independent verification.
     * refundEvidenceSha256 is the SHA-256 of the refund-observed.json a refund decision was recorded in; one such
     * file resolves one payment. Other decisions have no refund and leave it null.
     */
    public record PaymentResolution(UUID resolutionId, String operatorRef, String caseRef, String notes,
                                    String requestSha256, PaymentDecision decision, CustodyBytes caseEvidence,
                                    Long refundBeforeBits, Long refundAfterBits, String refundEvidenceSha256,
                                    Instant resolvedAt) {
        public PaymentResolution {
            Objects.requireNonNull(resolutionId);
            Objects.requireNonNull(decision);
            Objects.requireNonNull(caseEvidence);
            reference(operatorRef);
            reference(caseRef);
            ItemDeliveryEvidence.requireHash(requestSha256);
            if (notes == null || notes.isBlank() || notes.length() > 2048)
                throw new IllegalArgumentException("Recovery notes exceed bounds");
            boolean refund = decision == PaymentDecision.VERIFIED_REFUNDED_DEBIT;
            if (refund != (refundBeforeBits != null && refundAfterBits != null && refundEvidenceSha256 != null)
                    || (!refund && (refundBeforeBits != null || refundAfterBits != null || refundEvidenceSha256 != null)))
                throw new IllegalArgumentException("Refund evidence does not match decision");
            if (refund) ItemDeliveryEvidence.requireHash(refundEvidenceSha256);
            resolvedAt = time(resolvedAt);
        }
    }
    public record Budgets(int globalOrdersPerDay, int globalUnitsPerDay, int materialUnitsPerDay, long globalCostCentsPerDay) {
        public Budgets {
            if (globalOrdersPerDay < 1 || globalOrdersPerDay > 4096 || globalUnitsPerDay < 1 || globalUnitsPerDay > 262_144
                    || materialUnitsPerDay < 1 || materialUnitsPerDay > 262_144
                    || globalCostCentsPerDay < 1 || globalCostCentsPerDay > 409_600_000_000L)
                throw new IllegalArgumentException("Commerce budget exceeds bounds");
        }
    }
    public record Handle(UUID orderId, UUID runId, UUID payerId, UUID reserveId, String providerSha256) {
        public Handle {
            Objects.requireNonNull(orderId);
            Objects.requireNonNull(runId);
            Objects.requireNonNull(payerId);
            Objects.requireNonNull(reserveId);
            ItemDeliveryEvidence.requireHash(providerSha256);
            if (payerId.equals(reserveId)) throw new IllegalArgumentException("Commerce payer equals reserve");
        }
    }
    public record Receipt(CommerceOrder order, State state, Handle handle, boolean creditStarted,
                          Instant fundedAt, Instant outputReadyAt, UUID custodyNonce, String outcome) {
        public Receipt {
            Objects.requireNonNull(order);
            Objects.requireNonNull(state);
            if ((state == State.RESERVED || state == State.CANCELLED) != (handle == null)
                    || (handle != null && (!handle.orderId().equals(order.orderId()) || !handle.payerId().equals(order.playerId())))
                    || ((state == State.CREDIT_INTENT || state == State.FUNDED) && !creditStarted)
                    || ((state == State.RESERVED || state == State.CANCELLED || state == State.DEBIT_INTENT
                         || state == State.CREDIT_READY || state == State.DECLINED) && creditStarted)
                    || (state == State.FUNDED && (fundedAt == null || outputReadyAt == null
                         || fundedAt.isBefore(order.issuedAt()) || !outputReadyAt.equals(fundedAt.plusSeconds(order.outputDelaySeconds()))))
                    || (state != State.FUNDED && (fundedAt != null || outputReadyAt != null || custodyNonce != null))
                    || (custodyNonce != null && !custodyNonce.equals(order.orderId())))
                throw new IllegalArgumentException("Invalid commerce receipt");
        }
    }
    public record Reservation(boolean newlyReserved, Receipt receipt) { }
    public record Start(boolean newlyStarted, Receipt receipt) { }
    public record ItemClaim(boolean newlyReserved, ItemCustodyRepository.Receipt custody) { }
    /** Proof for a financial projection adapter; this is NOT evidence of inventory delivery. */
    public record PaidProof(CommerceOrder order, Handle handle, Observation debit, Observation credit,
                            Instant fundedAt, Instant outputReadyAt) { }
    private record Key(UUID player, UUID order) { }
    private final Database database;
    private final UUID reserve;

    public CommerceOrderRepository(Database database, UUID reserve) {
        this.database = Objects.requireNonNull(database);
        this.reserve = Objects.requireNonNull(reserve);
    }

    /** Limits pin on first use per day/material. No terminal state returns a consumed issuance slot. */
    public Reservation reserve(CommerceOrder order, Budgets budgets) throws SQLException {
        Objects.requireNonNull(order);
        Objects.requireNonNull(budgets);
        if (order.playerId().equals(reserve)) throw new IllegalArgumentException("Reserve cannot buy its own goods");
        ensureGuards(order, budgets);
        return transaction(c -> {
            lockBudgets(c, order, budgets);
            lockPlayer(c, order.playerId());
            Optional<Receipt> old = read(c, order.playerId(), order.orderId());
            if (old.isPresent()) {
                orderBinding(old.get(), order);
                return new Reservation(false, old.get());
            }
            if (unfulfilled(c, order.playerId(), false).size() >= MAX_OUTSTANDING)
                throw new IssuanceLimit("Player commerce backlog is full");
            if (order.source() == ItemCustody.Source.WORKER_OUTPUT) {
                try (PreparedStatement ps = prepare(c, "SELECT order_id FROM npc_commerce_orders"
                        + " WHERE worker_player_id=? AND economy_day=? LIMIT 1 FOR UPDATE")) {
                    id(ps, 1, order.playerId());
                    ps.setString(2, order.economyDay().toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) throw new IssuanceLimit("Worker issuance day is already consumed");
                    }
                }
            }
            try (PreparedStatement ps = prepare(c, """
                    INSERT INTO npc_commerce_orders(order_id,issuance_nonce,player_id,resident_id,source,catalogue_id,material,
                    quantity,cost_cents,economy_day,issued_at_millis,output_delay_seconds,request_sha256,state)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,'RESERVED')
                    """)) {
                id(ps, 1, order.orderId());
                id(ps, 2, order.issuanceNonce());
                id(ps, 3, order.playerId());
                id(ps, 4, order.residentId());
                ps.setString(5, order.source().name());
                ps.setString(6, order.catalogueId());
                ps.setString(7, order.material());
                ps.setInt(8, order.quantity());
                ps.setLong(9, order.costCents());
                ps.setString(10, order.economyDay().toString());
                ps.setLong(11, order.issuedAt().toEpochMilli());
                ps.setInt(12, order.outputDelaySeconds());
                ps.setString(13, order.requestSha256());
                one(ps.executeUpdate());
            }
            try (PreparedStatement ps = prepare(c, """
                    UPDATE npc_commerce_days SET issued_orders=issued_orders+1,issued_units=issued_units+?,
                    issued_cost_cents=issued_cost_cents+? WHERE economy_day=? AND issued_orders<max_orders
                    AND issued_units+?<=max_units AND issued_cost_cents+?<=max_cost_cents
                    """)) {
                ps.setInt(1, order.quantity());
                ps.setLong(2, order.costCents());
                ps.setString(3, order.economyDay().toString());
                ps.setInt(4, order.quantity());
                ps.setLong(5, order.costCents());
                budgetWrite(ps.executeUpdate());
            }
            try (PreparedStatement ps = prepare(c, """
                    UPDATE npc_commerce_material_days SET issued_units=issued_units+? WHERE economy_day=?
                    AND material=? AND issued_units+?<=max_units
                    """)) {
                ps.setInt(1, order.quantity());
                ps.setString(2, order.economyDay().toString());
                ps.setString(3, order.material());
                ps.setInt(4, order.quantity());
                budgetWrite(ps.executeUpdate());
            }
            return new Reservation(true, new Receipt(order, State.RESERVED, null, false, null, null, null, null));
        });
    }

    /** outcome_code of an order refused at its debit intent because the quote's terms no longer hold. */
    public static final String QUOTE_EXPIRED = "QUOTE_EXPIRED";
    /**
     * A last check of a quote's terms (the partner rate), run inside beginDebit's transaction after the player and
     * order are locked and just before DEBIT_INTENT. False cancels the RESERVED order as QUOTE_EXPIRED in that same
     * transaction, so no debit can start. It may lock rows outside commerce only if their own transactions never
     * take commerce locks.
     */
    @FunctionalInterface public interface DebitCheck { boolean holds(Connection c) throws SQLException; }
    public Start beginDebit(CommerceOrder expected, Handle handle) throws SQLException { return beginDebit(expected, handle, null); }
    /** A null check skips the quote test and starts the debit intent directly. */
    public Start beginDebit(CommerceOrder expected, Handle handle, DebitCheck check) throws SQLException {
        Objects.requireNonNull(expected);
        Objects.requireNonNull(handle);
        if (!expected.orderId().equals(handle.orderId()) || !expected.playerId().equals(handle.payerId())
                || !reserve.equals(handle.reserveId())) throw new IllegalArgumentException("Commerce payment binding conflicts");
        return transaction(c -> {
            lockPlayer(c, expected.playerId());
            Receipt receipt = required(c, expected.playerId(), expected.orderId());
            orderBinding(receipt, expected);
            if (receipt.handle() != null) handleBinding(receipt, handle);
            if (receipt.state() != State.RESERVED) return new Start(false, receipt);
            try (PreparedStatement ps = prepare(c, """
                    SELECT order_id FROM npc_commerce_orders WHERE player_id=?
                    AND state IN ('DEBIT_INTENT','CREDIT_READY','CREDIT_INTENT','AMBIGUOUS') LIMIT 1 FOR UPDATE
                    """)) {
                id(ps, 1, expected.playerId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return new Start(false, receipt);
                }
            }
            if (check != null && !check.holds(c))
                return new Start(false, transition(c, receipt, State.CANCELLED, null, false, null, QUOTE_EXPIRED));
            return new Start(true, transition(c, receipt, State.DEBIT_INTENT, handle, false, null, null));
        });
    }

    public Receipt recordDebit(Handle handle, Observation observation) throws SQLException {
        return record(handle, Leg.DEBIT, observation, null);
    }
    public Start beginCredit(Handle handle) throws SQLException {
        Objects.requireNonNull(handle);
        return transaction(c -> {
            lockPlayer(c, handle.payerId());
            Receipt receipt = required(c, handle.payerId(), handle.orderId());
            handleBinding(receipt, handle);
            if (receipt.state() != State.CREDIT_READY) return new Start(false, receipt);
            Observation debit = observation(c, handle.orderId(), Leg.DEBIT)
                    .orElseThrow(() -> new SQLException("Commerce debit proof missing"));
            if (TransferProof.evaluate(receipt.order().costCents(), Leg.DEBIT, debit) != TransferProof.LegOutcome.CONFIRMED)
                throw new SQLException("Commerce credit requires confirmed debit");
            return new Start(true, transition(c, receipt, State.CREDIT_INTENT, handle, true, null, null));
        });
    }
    public Receipt recordCredit(Handle handle, Observation observation, Instant now) throws SQLException {
        return record(handle, Leg.CREDIT, observation, time(now));
    }

    private Receipt record(Handle handle, Leg leg, Observation evidence, Instant now) throws SQLException {
        Objects.requireNonNull(handle);
        Objects.requireNonNull(evidence);
        return transaction(c -> {
            lockPlayer(c, handle.payerId());
            Receipt receipt = required(c, handle.payerId(), handle.orderId());
            handleBinding(receipt, handle);
            Optional<Observation> old = observation(c, handle.orderId(), leg);
            if (old.isPresent()) {
                if (!same(old.get(), evidence)) throw new SQLException("Conflicting commerce money acknowledgement");
                return receipt;
            }
            State expected = leg == Leg.DEBIT ? State.DEBIT_INTENT : State.CREDIT_INTENT;
            if (receipt.state() != expected && receipt.state() != State.AMBIGUOUS)
                throw new SQLException("Commerce observation without matching money intent");
            Observation debit = observation(c, handle.orderId(), Leg.DEBIT).orElse(null);
            if (leg == Leg.CREDIT && (!receipt.creditStarted()
                    || TransferProof.evaluate(receipt.order().costCents(), Leg.DEBIT, debit) != TransferProof.LegOutcome.CONFIRMED))
                throw new SQLException("Commerce credit lacks observed debit");
            if (leg == Leg.CREDIT && now.isBefore(receipt.order().issuedAt()))
                throw new IllegalArgumentException("Funding precedes issuance");
            insertObservation(c, handle.orderId(), leg, evidence);
            if (receipt.state() == State.AMBIGUOUS) return receipt;
            boolean refused = evidence.refusedBeforeCall() != null;
            if (leg == Leg.DEBIT) return switch (TransferProof.evaluate(receipt.order().costCents(), leg, evidence)) {
                case CONFIRMED -> transition(c, receipt, State.CREDIT_READY, handle, false, null, null);
                case NO_MOVEMENT -> transition(c, receipt, State.DECLINED, handle, false, null,
                        refused ? "DEBIT_REFUSED_BEFORE_CALL" : "DEBIT_DECLINED");
                case AMBIGUOUS -> transition(c, receipt, State.AMBIGUOUS, handle, false, null, "DEBIT_UNCERTAIN");
            };
            // A credit refused before its call leaves the debit to be refunded by staff; it is never retried.
            return TransferProof.evaluateTransfer(receipt.order().costCents(), debit, evidence) == TransferProof.TransferOutcome.PAID
                    ? transition(c, receipt, State.FUNDED, handle, true, now, "TRANSFER_CONFIRMED")
                    : transition(c, receipt, State.AMBIGUOUS, handle, true, null,
                            refused ? "CREDIT_REFUSED_BEFORE_CALL" : "CREDIT_UNCERTAIN");
        });
    }

    /** One order that holds the runtimes, as stored. Read-only; nothing here is a decision. */
    public record Held(UUID orderId, UUID playerId, State state, long cents, boolean creditStarted,
                       Observation debit, Observation credit, String createdAt) {
        public com.prosperitysmp.npc.economy.HeldClass heldClass() {
            return com.prosperitysmp.npc.economy.HeldClass.of(state == State.AMBIGUOUS, cents, creditStarted, debit, credit);
        }
    }

    /** Every order the startup hold counts, up to limit. Reads only. */
    public List<Held> held(int limit) throws SQLException {
        List<Held> result = new ArrayList<>();
        try (Connection c = database.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setReadOnly(true);
            c.setAutoCommit(false);
            try (PreparedStatement ps = prepare(c, """
                    SELECT * FROM npc_commerce_orders WHERE state IN ('DEBIT_INTENT','CREDIT_READY','CREDIT_INTENT','AMBIGUOUS')
                    ORDER BY order_id LIMIT ?
                    """)) {
                ps.setInt(1, Math.clamp(limit, 0, 100_000));
                List<Receipt> receipts = new ArrayList<>();
                List<String> created = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) { receipts.add(receipt(rs)); created.add(rs.getString("created_at")); }
                }
                for (int i = 0; i < receipts.size(); i++) {
                    Receipt r = receipts.get(i);
                    UUID order = r.order().orderId();
                    result.add(new Held(order, r.order().playerId(), r.state(), r.order().costCents(), r.creditStarted(),
                            observation(c, order, Leg.DEBIT, false).orElse(null),
                            observation(c, order, Leg.CREDIT, false).orElse(null), created.get(i)));
                }
            } finally {
                c.rollback();
            }
        }
        return result;
    }

    /** When the order was written (UTC, as the column prints it), whatever its state now. Reads only. */
    public Optional<String> createdAt(UUID player, UUID order) throws SQLException {
        Objects.requireNonNull(player);
        Objects.requireNonNull(order);
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c,
                "SELECT created_at FROM npc_commerce_orders WHERE player_id=? AND order_id=?")) {
            id(ps, 1, player);
            id(ps, 2, order);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty(); }
        }
    }

    /** Orders the startup hold sees. The classify total must equal it. */
    public long heldCount() throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c, """
                SELECT COUNT(*) FROM npc_commerce_orders WHERE state IN ('DEBIT_INTENT','CREDIT_READY','CREDIT_INTENT','AMBIGUOUS')
                """); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    public Receipt cancel(CommerceOrder expected) throws SQLException {
        Objects.requireNonNull(expected);
        return transaction(c -> {
            lockPlayer(c, expected.playerId());
            Receipt receipt = required(c, expected.playerId(), expected.orderId());
            orderBinding(receipt, expected);
            return switch (receipt.state()) {
                case RESERVED -> transition(c, receipt, State.CANCELLED, null, false, null, "ORDER_CANCELLED");
                case DEBIT_INTENT, CREDIT_READY, CREDIT_INTENT -> transition(c, receipt, State.AMBIGUOUS, receipt.handle(),
                        receipt.creditStarted(), null, "CANCELLED_AFTER_INTENT");
                default -> receipt;
            };
        });
    }

    public Optional<Receipt> find(UUID playerId, UUID orderId) throws SQLException {
        Objects.requireNonNull(playerId);
        Objects.requireNonNull(orderId);
        return transaction(c -> {
            if (!player(c, playerId)) return Optional.empty();
            return read(c, playerId, orderId);
        });
    }
    /** True when this player's one hired-worker issuance for the service day is already consumed. */
    public boolean workerDayUsed(UUID playerId, LocalDate day) throws SQLException {
        Objects.requireNonNull(playerId);
        Objects.requireNonNull(day);
        return transaction(c -> {
            try (PreparedStatement ps = prepare(c,
                    "SELECT 1 FROM npc_commerce_orders WHERE worker_player_id=? AND economy_day=? LIMIT 1")) {
                id(ps, 1, playerId);
                ps.setString(2, day.toString());
                try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
            }
        });
    }
    /** Newest first across all states. Caller applies resident/world presentation filters, never another player's identity. */
    public List<Receipt> recent(UUID playerId, int limit) throws SQLException {
        Objects.requireNonNull(playerId);
        if (limit < 0 || limit > MAX_RECENT) throw new IllegalArgumentException("Commerce recent limit must be between zero and eight");
        if (limit == 0) return List.of();
        return transaction(c -> {
            if (!player(c, playerId)) return List.of();
            List<Receipt> receipts = new ArrayList<>();
            try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_commerce_orders WHERE player_id=?"
                    + " ORDER BY issued_at_millis DESC,order_id DESC LIMIT ? FOR UPDATE")) {
                id(ps, 1, playerId);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) receipts.add(receipt(rs)); }
            }
            return List.copyOf(receipts);
        });
    }
    /** Oldest paid obligations first, including held/expired custody. Caller scopes resident presentation. */
    public List<Receipt> outstanding(UUID playerId, int limit) throws SQLException {
        Objects.requireNonNull(playerId);
        if (limit < 0 || limit > MAX_OUTSTANDING)
            throw new IllegalArgumentException("Commerce outstanding limit must be between zero and eight");
        if (limit == 0) return List.of();
        return transaction(c -> {
            if (!player(c, playerId)) return List.of();
            List<Receipt> receipts = unfulfilled(c, playerId, true);
            return List.copyOf(receipts.subList(0, Math.min(limit, receipts.size())));
        });
    }

    // The commerce-player guard serializes issuance and order binding. Delivered custody is terminal;
    // reading it without a custody lock avoids reversing the shared commerce -> custody lock order.
    private static List<Receipt> unfulfilled(Connection c, UUID playerId, boolean fundedOnly) throws SQLException {
        List<Receipt> receipts = new ArrayList<>();
        String states = fundedOnly ? "o.state='FUNDED' AND " + UNFULFILLED
                : "(o.state IN ('RESERVED','DEBIT_INTENT','CREDIT_READY','CREDIT_INTENT','AMBIGUOUS')"
                  + " OR (o.state='FUNDED' AND " + UNFULFILLED + "))";
        try (PreparedStatement ps = prepare(c, "SELECT o.* FROM npc_commerce_orders o WHERE o.player_id=? AND "
                + states + " ORDER BY o.issued_at_millis,o.order_id LIMIT ?")) {
            id(ps, 1, playerId);
            ps.setInt(2, MAX_OUTSTANDING + 1);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) receipts.add(receipt(rs)); }
        }
        for (Receipt receipt : receipts) {
            if (receipt.custodyNonce() != null) {
                ItemCustodyRepository.Receipt item = custody(c, receipt.custodyNonce(), false)
                        .orElseThrow(() -> new SQLException("Linked commerce custody missing"));
                custodyBinding(receipt, item.request());
            }
        }
        if (receipts.size() > MAX_OUTSTANDING) throw new SQLException("Commerce backlog exceeds bounded capacity");
        return receipts;
    }
    public Optional<PaidProof> paidProof(UUID playerId, UUID orderId) throws SQLException {
        Objects.requireNonNull(playerId);
        Objects.requireNonNull(orderId);
        return transaction(c -> {
            if (!player(c, playerId)) return Optional.empty();
            Optional<Receipt> receipt = read(c, playerId, orderId);
            return receipt.isEmpty() || receipt.get().state() != State.FUNDED ? Optional.empty() : Optional.of(proof(c, receipt.get()));
        });
    }

    /**
     * Closed [from,through] accounting window on the caller's rollback-only REPEATABLE_READ
     * transaction. No new connection, writes, locks, commit, rollback, or connection-setting changes.
     * Over-cap or unverifiable results fail the entire projection; never publish clipped totals.
     */
    public List<PaidProof> paidProofs(Connection supplied, Instant from, Instant through, int limit) throws SQLException {
        Objects.requireNonNull(supplied);
        Objects.requireNonNull(from);
        Objects.requireNonNull(through);
        if (limit < 1 || limit > MAX_PAID_PROOFS || from.isAfter(through)
                || from.toEpochMilli() < 0 || through.toEpochMilli() < 0)
            throw new IllegalArgumentException("Invalid commerce accounting window or limit");
        if (supplied.getAutoCommit() || supplied.getTransactionIsolation() != Connection.TRANSACTION_REPEATABLE_READ)
            throw new SQLException("Commerce accounting requires caller-owned repeatable-read transaction");
        long started = System.nanoTime();
        long lower = from.toEpochMilli();
        if (from.getNano() % 1_000_000 != 0) lower = Math.incrementExact(lower);
        List<Receipt> receipts = new ArrayList<>();
        try (PreparedStatement ps = prepare(supplied, """
                SELECT * FROM npc_commerce_orders WHERE state='FUNDED' AND
                (funded_at_millis IS NULL OR (funded_at_millis>=? AND funded_at_millis<=?))
                ORDER BY funded_at_millis,order_id LIMIT ?
                """)) {
            ps.setLong(1, lower);
            ps.setLong(2, through.toEpochMilli());
            ps.setInt(3, limit + 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    accountingBudget(started);
                    if (receipts.size() >= limit) throw new SQLException("Commerce accounting row bound exceeded");
                    receipts.add(receipt(rs));
                }
                if (rs.getWarnings() != null || ps.getWarnings() != null) throw new SQLException("Commerce accounting SQL warnings");
            }
        }
        List<PaidProof> proofs = new ArrayList<>();
        for (Receipt receipt : receipts) {
            accountingBudget(started);
            proofs.add(proof(supplied, receipt));
        }
        accountingBudget(started);
        if (supplied.getWarnings() != null) throw new SQLException("Commerce accounting SQL warnings");
        return List.copyOf(proofs);
    }

    /**
     * FUNDING is not delivery. The deterministic custody insert and order link commit together.
     * Caller must verify serialized native material/metadata and batch quantity on main before this call.
     */
    public ItemClaim claimItems(CommerceOrder expected, CustodyBytes payload, Instant now) throws SQLException {
        Objects.requireNonNull(expected);
        Objects.requireNonNull(payload);
        Instant at = time(now);
        ensure("INSERT INTO npc_item_custody_players(player_id) VALUES(?)", ps -> id(ps, 1, expected.playerId()));
        return transaction(c -> {
            lockPlayer(c, expected.playerId());
            Receipt receipt = required(c, expected.playerId(), expected.orderId());
            orderBinding(receipt, expected);
            proof(c, receipt);
            if (at.isBefore(receipt.outputReadyAt())) throw new SQLException("Paid commerce output is not ready");
            try (PreparedStatement ps = prepare(c, "SELECT player_id FROM npc_item_custody_players WHERE player_id=? FOR UPDATE")) {
                id(ps, 1, expected.playerId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new SQLException("Custody player guard missing");
                }
            }
            Optional<ItemCustodyRepository.Receipt> old = custody(c, expected.orderId());
            if (receipt.custodyNonce() != null) {
                ItemCustodyRepository.Receipt stored = old.orElseThrow(() -> new SQLException("Commerce custody link is missing"));
                ItemCustody request = stored.request();
                if (!request.requestNonce().equals(expected.orderId()) || !request.offerId().equals(expected.orderId())
                        || !request.playerId().equals(expected.playerId()) || request.source() != expected.source()
                        || request.quantity() != expected.quantity() || !request.payload().equals(payload)
                        || request.reservedAt().isBefore(receipt.outputReadyAt()))
                    throw new SQLException("Commerce custody replay conflicts");
                return new ItemClaim(false, expireCustody(c, stored, at));
            }
            if (old.isPresent()) throw new SQLException("Unbound order custody already exists");
            custodyBudget(c, expected.playerId(), expected.quantity(), at);
            ItemCustody request = new ItemCustody(expected.orderId(), expected.playerId(), expected.orderId(), expected.source(),
                    expected.quantity(), payload, at, at.plusSeconds(ItemCustody.MAX_RESERVATION_SECONDS));
            try (PreparedStatement ps = prepare(c, """
                    INSERT INTO npc_item_custody(request_nonce,player_id,offer_id,source,quantity,payload_bytes,payload_sha256,
                    reserved_at_millis,expires_at_millis,state) VALUES(?,?,?,?,?,?,?,?,?,'RESERVED')
                    """)) {
                id(ps, 1, request.requestNonce());
                id(ps, 2, request.playerId());
                id(ps, 3, request.offerId());
                ps.setString(4, request.source().name());
                ps.setInt(5, request.quantity());
                ps.setBytes(6, payload.bytes());
                ps.setString(7, payload.sha256());
                ps.setLong(8, at.toEpochMilli());
                ps.setLong(9, request.expiresAt().toEpochMilli());
                one(ps.executeUpdate());
            }
            try (PreparedStatement ps = prepare(c, "UPDATE npc_commerce_orders SET custody_nonce=?,updated_at=CURRENT_TIMESTAMP(3)"
                    + " WHERE order_id=? AND state='FUNDED' AND custody_nonce IS NULL")) {
                id(ps, 1, expected.orderId());
                id(ps, 2, expected.orderId());
                one(ps.executeUpdate());
            }
            return new ItemClaim(true, new ItemCustodyRepository.Receipt(request, ItemCustodyRepository.State.RESERVED, null, null));
        });
    }

    /** Call to zero with a fresh boot UUID before money dispatch. CREDIT_READY is frozen too. */
    public int recoverOtherRuns(UUID currentRun, int limit) throws SQLException {
        Objects.requireNonNull(currentRun);
        if (limit < 0) throw new IllegalArgumentException("Negative recovery limit");
        List<Key> keys = new ArrayList<>();
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c, """
                SELECT player_id,order_id FROM npc_commerce_orders WHERE run_id<>?
                AND state IN ('DEBIT_INTENT','CREDIT_READY','CREDIT_INTENT') ORDER BY order_id LIMIT ?
                """)) {
            id(ps, 1, currentRun);
            ps.setInt(2, Math.min(limit, MAX_SWEEP));
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) keys.add(new Key(uuid(rs, "player_id"), uuid(rs, "order_id"))); }
        }
        int changed = 0;
        for (Key key : keys) changed += transaction(c -> {
            lockPlayer(c, key.player());
            Receipt receipt = required(c, key.player(), key.order());
            if ((receipt.state() != State.DEBIT_INTENT && receipt.state() != State.CREDIT_READY && receipt.state() != State.CREDIT_INTENT)
                    || currentRun.equals(receipt.handle().runId())) return 0;
            transition(c, receipt, State.AMBIGUOUS, receipt.handle(), receipt.creditStarted(), null, "INTERRUPTED_RUN");
            return 1;
        });
        return changed;
    }

    /** Strictly older, untouched reservations only. Never returns issuance budgets or worker-day slots. */
    public int cancelUnstartedBefore(Instant cutoff, int limit) throws SQLException {
        Instant before = time(cutoff);
        if (limit < 0) throw new IllegalArgumentException("Negative cleanup limit");
        List<Key> keys = new ArrayList<>();
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c, """
                SELECT o.player_id,o.order_id FROM npc_commerce_orders o
                WHERE o.state='RESERVED' AND o.issued_at_millis<? AND o.run_id IS NULL
                  AND o.provider_sha256 IS NULL AND o.payer_id IS NULL AND o.reserve_id IS NULL
                  AND o.credit_started=0 AND o.custody_nonce IS NULL
                  AND NOT EXISTS (SELECT 1 FROM npc_commerce_money_evidence e WHERE e.order_id=o.order_id)
                  AND NOT EXISTS (SELECT 1 FROM npc_item_custody i WHERE i.request_nonce=o.order_id OR i.offer_id=o.order_id)
                ORDER BY o.issued_at_millis,o.order_id LIMIT ?
                """)) {
            ps.setLong(1, before.toEpochMilli());
            ps.setInt(2, Math.min(limit, MAX_SWEEP));
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) keys.add(new Key(uuid(rs, "player_id"), uuid(rs, "order_id"))); }
        }
        int changed = 0;
        for (Key key : keys) {
            ensure("INSERT INTO npc_item_custody_players(player_id) VALUES(?)", ps -> id(ps, 1, key.player()));
            changed += transaction(c -> {
                lockPlayer(c, key.player());
                Receipt receipt = required(c, key.player(), key.order());
                if (receipt.state() != State.RESERVED || receipt.handle() != null || receipt.creditStarted()
                        || receipt.custodyNonce() != null || !receipt.order().issuedAt().isBefore(before)) return 0;
                lockCustodyPlayer(c, key.player());
                if (hasCustody(c, key.order()) || hasMoneyEvidence(c, key.order())) return 0;
                transition(c, receipt, State.CANCELLED, null, false, null, "RESERVATION_EXPIRED");
                return 1;
            });
        }
        return changed;
    }

    /**
     * Requires stopped-server/exclusive wallet custody and independently verified case evidence.
     * Uses existing adjacent observations only; never dispatches money, retries, or deletes funded goods.
     * Exact replay returns the current receipt without changing the original resolution or funding time.
     */
    public Receipt reconcilePayment(CommerceOrder expected, Handle handle, PaymentResolution resolution) throws SQLException {
        return reconcilePayment(expected, handle, resolution, false, true);
    }

    /**
     * As above. legacyProvenanceVerified says the caller has already proved, from the install record and the jar
     * bytes, that an older build whose gate ran before the money call wrote this row. Only then may a leg stored
     * the old way (a throw with no response and an unchanged balance) count as refused. With apply false every
     * check and write runs and is then rolled back, so a dry run tests exactly what an applied run would do.
     */
    public Receipt reconcilePayment(CommerceOrder expected, Handle handle, PaymentResolution resolution,
                                    boolean legacyProvenanceVerified, boolean apply) throws SQLException {
        return reconcilePayment(expected, handle, resolution, legacyProvenanceVerified, apply, false);
    }

    /**
     * As above. distinctRefund is staff asserting that a refund decision's refund is a second, separate refund for
     * this order; see {@link RefundClaims#claim}. It applies only to a refund.
     */
    public Receipt reconcilePayment(CommerceOrder expected, Handle handle, PaymentResolution resolution,
                                    boolean legacyProvenanceVerified, boolean apply, boolean distinctRefund) throws SQLException {
        Objects.requireNonNull(expected);
        Objects.requireNonNull(handle);
        Objects.requireNonNull(resolution);
        if (distinctRefund && resolution.decision() != PaymentDecision.VERIFIED_REFUNDED_DEBIT)
            throw new IllegalArgumentException("A distinct refund applies only to a refund decision");
        if (!expected.requestSha256().equals(resolution.requestSha256()))
            throw new IllegalArgumentException("Recovery request hash conflicts");
        // The custody guard row is written inside this transaction, so a dry run leaves no trace of it either.
        return transaction(apply, c -> {
            lockPlayer(c, expected.playerId());
            Receipt receipt = required(c, expected.playerId(), expected.orderId());
            orderBinding(receipt, expected);
            handleBinding(receipt, handle);
            if (existingResolution(c, receipt, resolution)) {
                State terminal = resolvedState(resolution.decision());
                if (receipt.state() != terminal || (terminal == State.FUNDED && !receipt.fundedAt().equals(resolution.resolvedAt())))
                    throw new SQLException("Recovery terminal receipt conflicts");
                if (terminal == State.FUNDED) proof(c, receipt);
                return receipt;
            }
            if (receipt.state() != State.AMBIGUOUS || resolution.resolvedAt().isBefore(expected.issuedAt()))
                throw new SQLException("Recovery requires an unresolved held order");
            ensure(c, "INSERT INTO npc_item_custody_players(player_id) VALUES(?)", ps -> id(ps, 1, expected.playerId()));
            lockCustodyPlayer(c, expected.playerId());
            if (hasCustody(c, expected.orderId())) throw new SQLException("Recovery cannot alter item custody");
            Observation debit = observation(c, expected.orderId(), Leg.DEBIT).orElse(null);
            Observation credit = observation(c, expected.orderId(), Leg.CREDIT).orElse(null);
            switch (resolution.decision()) {
                case VERIFIED_PAID -> {
                    if (!receipt.creditStarted() || TransferProof.evaluateTransfer(expected.costCents(), debit,
                            credit) != TransferProof.TransferOutcome.PAID)
                        throw new SQLException("Recovery lacks exact paid transfer proof");
                }
                case VERIFIED_NO_MOVEMENT -> {
                    if (receipt.creditStarted() || credit != null
                            || TransferProof.evaluateTransfer(expected.costCents(), debit,
                            credit) != TransferProof.TransferOutcome.DECLINED)
                        throw new SQLException("Recovery lacks exact no-movement proof");
                }
                case VERIFIED_REFUNDED_DEBIT -> {
                    // The reserve credit either never started, or it stopped before its money call.
                    boolean creditClear = !receipt.creditStarted()
                            ? credit == null
                            : credit != null && (credit.refusedBeforeCall() != null || (legacyProvenanceVerified
                                    && TransferProof.legacyGateRefused(expected.costCents(), Leg.CREDIT, credit)));
                    if (!creditClear) throw new SQLException("Refund recovery cannot follow reserve credit intent");
                    if (!TransferProof.refundRestoresDebit(expected.costCents(), debit,
                            resolution.refundBeforeBits(), resolution.refundAfterBits()))
                        throw new SQLException("Existing external refund does not exactly restore observed debit");
                    // Shared with the transfer journal: one refund closes one held payment in either table.
                    RefundClaims.claim(c, RefundClaims.Source.COMMERCE, expected.orderId(), resolution.resolutionId(),
                            handle.payerId(), resolution.refundBeforeBits(), resolution.refundAfterBits(),
                            Double.doubleToRawLongBits(debit.afterDollars()), resolution.refundEvidenceSha256(), distinctRefund);
                }
                case VERIFIED_LEGACY_REFUSAL -> {
                    if (!legacyProvenanceVerified || receipt.creditStarted() || credit != null
                            || !TransferProof.legacyGateRefused(expected.costCents(), Leg.DEBIT, debit))
                        throw new SQLException("Recovery lacks a verified legacy refusal");
                }
            }
            insertResolution(c, receipt, resolution);
            State terminal = resolvedState(resolution.decision());
            boolean creditStarted = terminal == State.FUNDED || (terminal == State.COMPENSATED && receipt.creditStarted());
            return transition(c, receipt, terminal, handle, creditStarted,
                    terminal == State.FUNDED ? resolution.resolvedAt() : null, resolution.decision().name());
        });
    }

    private static State resolvedState(PaymentDecision decision) {
        return switch (decision) {
            case VERIFIED_PAID -> State.FUNDED;
            case VERIFIED_NO_MOVEMENT, VERIFIED_LEGACY_REFUSAL -> State.DECLINED;
            case VERIFIED_REFUNDED_DEBIT -> State.COMPENSATED;
        };
    }
    private static void reference(String ref) {
        if (ref == null || !ref.matches("[A-Za-z0-9_.:@-]{1,96}")) throw new IllegalArgumentException("Invalid recovery reference");
    }
    private static void lockCustodyPlayer(Connection c, UUID player) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT player_id FROM npc_item_custody_players WHERE player_id=? FOR UPDATE")) {
            id(ps, 1, player);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("Custody player guard missing");
            }
        }
    }
    private static boolean hasCustody(Connection c, UUID order) throws SQLException {
        try (PreparedStatement ps = prepare(c,
                "SELECT request_nonce FROM npc_item_custody WHERE request_nonce=? OR offer_id=? LIMIT 1 FOR UPDATE")) {
            id(ps, 1, order);
            id(ps, 2, order);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
    private static boolean hasMoneyEvidence(Connection c, UUID order) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT leg FROM npc_commerce_money_evidence WHERE order_id=? LIMIT 1 FOR UPDATE")) {
            id(ps, 1, order);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
    private static boolean existingResolution(Connection c, Receipt receipt, PaymentResolution resolution) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_commerce_payment_resolutions WHERE order_id=? FOR UPDATE")) {
            id(ps, 1, receipt.order().orderId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                CustodyBytes evidence = new CustodyBytes(rs.getBytes("evidence_bytes"));
                Handle handle = receipt.handle();
                if (!resolution.resolutionId().equals(uuid(rs, "resolution_id"))
                        || !resolution.operatorRef().equals(rs.getString("operator_ref")) || !resolution.caseRef()
                        .equals(rs.getString("case_ref"))
                        || !resolution.notes().equals(rs.getString("notes")) || !resolution.decision().name()
                        .equals(rs.getString("decision"))
                        || !resolution.requestSha256().equals(rs.getString("request_sha256"))
                        || !resolution.caseEvidence().equals(evidence) || !evidence.sha256().equals(rs.getString("evidence_sha256"))
                        || !Objects.equals(resolution.refundBeforeBits(), number(rs, "refund_before_bits"))
                        || !Objects.equals(resolution.refundAfterBits(), number(rs, "refund_after_bits"))
                        || resolution.resolvedAt().toEpochMilli() != rs.getLong("resolved_at_millis")
                        || !handle.runId().equals(uuid(rs, "run_id")) || !handle.payerId().equals(uuid(rs, "payer_id"))
                        || !handle.reserveId().equals(uuid(rs, "reserve_id")) || !handle.providerSha256()
                        .equals(rs.getString("provider_sha256"))
                        || receipt.order().costCents() != rs.getLong("cost_cents"))
                    throw new SQLException("Conflicting commerce payment resolution");
                return true;
            }
        }
    }
    private static void insertResolution(Connection c, Receipt receipt, PaymentResolution resolution) throws SQLException {
        try (PreparedStatement ps = prepare(c, """
                INSERT INTO npc_commerce_payment_resolutions(order_id,resolution_id,run_id,payer_id,reserve_id,provider_sha256,
                request_sha256,cost_cents,operator_ref,case_ref,notes,decision,evidence_bytes,evidence_sha256,
                refund_before_bits,refund_after_bits,resolved_at_millis,previous_outcome)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            Handle handle = receipt.handle();
            id(ps, 1, receipt.order().orderId());
            id(ps, 2, resolution.resolutionId());
            id(ps, 3, handle.runId());
            id(ps, 4, handle.payerId());
            id(ps, 5, handle.reserveId());
            ps.setString(6, handle.providerSha256());
            ps.setString(7, resolution.requestSha256());
            ps.setLong(8, receipt.order().costCents());
            ps.setString(9, resolution.operatorRef());
            ps.setString(10, resolution.caseRef());
            ps.setString(11, resolution.notes());
            ps.setString(12, resolution.decision().name());
            ps.setBytes(13, resolution.caseEvidence().bytes());
            ps.setString(14, resolution.caseEvidence().sha256());
            number(ps, 15, resolution.refundBeforeBits());
            number(ps, 16, resolution.refundAfterBits());
            ps.setLong(17, resolution.resolvedAt().toEpochMilli());
            ps.setString(18, receipt.outcome());
            one(ps.executeUpdate());
        }
    }

    private PaidProof proof(Connection c, Receipt receipt) throws SQLException {
        if (receipt.state() != State.FUNDED) throw new SQLException("Commerce order is not paid");
        handleBinding(receipt, receipt.handle());
        // Accounting owns a consistent RR snapshot; repository writers use RC and locked current reads.
        boolean lock = c.getTransactionIsolation() != Connection.TRANSACTION_REPEATABLE_READ;
        Observation debit = observation(c, receipt.order().orderId(), Leg.DEBIT, lock)
                .orElseThrow(() -> new SQLException("Paid debit proof missing"));
        Observation credit = observation(c, receipt.order().orderId(), Leg.CREDIT, lock)
                .orElseThrow(() -> new SQLException("Paid credit proof missing"));
        if (TransferProof.evaluateTransfer(receipt.order().costCents(), debit, credit) != TransferProof.TransferOutcome.PAID)
            throw new SQLException("Commerce paid proof conflicts");
        return new PaidProof(receipt.order(), receipt.handle(), debit, credit, receipt.fundedAt(), receipt.outputReadyAt());
    }

    private void ensureGuards(CommerceOrder order, Budgets budgets) throws SQLException {
        ensure("INSERT INTO npc_commerce_days(economy_day,max_orders,max_units,max_cost_cents) VALUES(?,?,?,?)", ps -> {
            ps.setString(1, order.economyDay().toString());
            ps.setInt(2, budgets.globalOrdersPerDay());
            ps.setInt(3, budgets.globalUnitsPerDay());
            ps.setLong(4, budgets.globalCostCentsPerDay());
        });
        ensure("INSERT INTO npc_commerce_material_days(economy_day,material,max_units) VALUES(?,?,?)", ps -> {
            ps.setString(1, order.economyDay().toString());
            ps.setString(2, order.material());
            ps.setInt(3, budgets.materialUnitsPerDay());
        });
        ensure("INSERT INTO npc_commerce_players(player_id) VALUES(?)", ps -> id(ps, 1, order.playerId()));
    }
    private static void lockBudgets(Connection c, CommerceOrder order, Budgets budgets) throws SQLException {
        try (PreparedStatement ps = prepare(c,
                "SELECT max_orders,max_units,max_cost_cents FROM npc_commerce_days WHERE economy_day=? FOR UPDATE")) {
            ps.setString(1, order.economyDay().toString()); try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getInt(1) != budgets.globalOrdersPerDay() || rs.getInt(2) != budgets.globalUnitsPerDay()
                        || rs.getLong(3) != budgets.globalCostCentsPerDay()) throw new SQLException("Pinned commerce day budget conflicts");
            }
        }
        try (PreparedStatement ps = prepare(c,
                "SELECT max_units FROM npc_commerce_material_days WHERE economy_day=? AND material=? FOR UPDATE")) {
            ps.setString(1, order.economyDay().toString());
            ps.setString(2, order.material());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getInt(1) != budgets.materialUnitsPerDay())
                    throw new SQLException("Pinned commerce material budget conflicts");
            }
        }
    }
    private static boolean player(Connection c, UUID player) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT player_id FROM npc_commerce_players WHERE player_id=? FOR UPDATE")) {
            id(ps, 1, player);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
    private static void lockPlayer(Connection c, UUID player) throws SQLException {
        if (!player(c, player)) throw new SQLException("Commerce player guard missing");
    }
    private static Optional<Receipt> read(Connection c, UUID player, UUID order) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_commerce_orders WHERE order_id=? AND player_id=? FOR UPDATE")) {
            id(ps, 1, order);
            id(ps, 2, player);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(receipt(rs)) : Optional.empty();
            }
        }
    }
    private static Receipt required(Connection c, UUID player, UUID order) throws SQLException {
        return read(c, player, order).orElseThrow(() -> new SQLException("Unknown commerce order for player"));
    }
    private static Receipt receipt(ResultSet rs) throws SQLException {
        try {
            CommerceOrder order = new CommerceOrder(uuid(rs, "order_id"), uuid(rs, "issuance_nonce"), uuid(rs, "player_id"),
                    uuid(rs, "resident_id"),
                    ItemCustody.Source.valueOf(rs.getString("source")), rs.getString("catalogue_id"), rs.getString("material"),
                    rs.getInt("quantity"),
                    rs.getLong("cost_cents"), LocalDate.parse(rs.getString("economy_day")),
                    Instant.ofEpochMilli(rs.getLong("issued_at_millis")), rs.getInt("output_delay_seconds"));
            if (!order.requestSha256().equals(rs.getString("request_sha256"))) throw new SQLException("Commerce request hash conflicts");
            UUID run = uuid(rs, "run_id");
            Handle handle = run == null ? null : new Handle(order.orderId(), run, uuid(rs, "payer_id"), uuid(rs, "reserve_id"),
                    rs.getString("provider_sha256"));
            return new Receipt(order, State.valueOf(rs.getString("state")), handle, rs.getBoolean("credit_started"),
                    instant(rs, "funded_at_millis"),
                    instant(rs, "output_ready_at_millis"), uuid(rs, "custody_nonce"), rs.getString("outcome_code"));
        } catch (IllegalArgumentException | NullPointerException invalid) { throw new SQLException("Invalid stored commerce order",
                invalid); }
    }
    private static void orderBinding(Receipt receipt, CommerceOrder order) throws SQLException {
        if (!receipt.order().equals(order)) throw new SQLException("Commerce order replay conflicts");
    }
    private void handleBinding(Receipt receipt, Handle handle) throws SQLException {
        if (!Objects.equals(receipt.handle(), handle) || handle == null || !reserve.equals(handle.reserveId()))
            throw new SQLException("Commerce provider/account/run binding conflicts");
    }
    private static Receipt transition(Connection c, Receipt old, State state, Handle handle, boolean credit, Instant funded,
            String outcome) throws SQLException {
        Instant ready = funded == null ? null : funded.plusSeconds(old.order().outputDelaySeconds());
        try (PreparedStatement ps = prepare(c, """
                UPDATE npc_commerce_orders SET state=?,run_id=?,provider_sha256=?,payer_id=?,reserve_id=?,credit_started=?,
                funded_at_millis=?,output_ready_at_millis=?,outcome_code=?,updated_at=CURRENT_TIMESTAMP(3)
                WHERE order_id=? AND state=? AND run_id<=>?
                """)) {
            ps.setString(1, state.name());
            nullableId(ps, 2, handle == null ? null : handle.runId());
            ps.setString(3, handle == null ? null : handle.providerSha256());
            nullableId(ps, 4, handle == null ? null : handle.payerId());
            nullableId(ps, 5, handle == null ? null : handle.reserveId());
            ps.setBoolean(6, credit);
            number(ps, 7, funded == null ? null : funded.toEpochMilli());
            number(ps, 8, ready == null ? null : ready.toEpochMilli());
            ps.setString(9, outcome);
            id(ps, 10, old.order().orderId());
            ps.setString(11, old.state().name());
            nullableId(ps, 12, old.handle() == null ? null : old.handle().runId());
            one(ps.executeUpdate());
        }
        return new Receipt(old.order(), state, handle, credit, funded, ready, null, outcome);
    }
    private static Optional<Observation> observation(Connection c, UUID order, Leg leg) throws SQLException {
        return observation(c, order, leg, true);
    }
    private static Optional<Observation> observation(Connection c, UUID order, Leg leg, boolean lock) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_commerce_money_evidence WHERE order_id=? AND leg=?"
                + (lock ? " FOR UPDATE" : ""))) {
            id(ps, 1, order);
            ps.setString(2, leg.name());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                String refused = rs.getString("refused_before_call");
                try {
                    return Optional.of(new Observation(rs.getBoolean("response_present"), rs.getBoolean("response_success"),
                            value(rs, "reported_bits"), value(rs, "before_bits"), value(rs, "after_bits"), rs.getBoolean("threw"),
                            refused == null ? null : TransferProof.Refusal.valueOf(refused), rs.getString("refusal_cause")));
                } catch (IllegalArgumentException malformed) {
                    throw new SQLException("Invalid commerce money evidence", malformed);
                }
            }
        }
    }
    private static void insertObservation(Connection c, UUID order, Leg leg, Observation observation) throws SQLException {
        try (PreparedStatement ps = prepare(c, """
                INSERT INTO npc_commerce_money_evidence(order_id,leg,response_present,response_success,reported_bits,before_bits,after_bits,threw,
                  refused_before_call,refusal_cause)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """)) {
            id(ps, 1, order);
            ps.setString(2, leg.name());
            ps.setBoolean(3, observation.responsePresent());
            ps.setBoolean(4, observation.responseSuccess());
            number(ps, 5, bits(observation.reportedDollars()));
            number(ps, 6, bits(observation.beforeDollars()));
            number(ps, 7, bits(observation.afterDollars()));
            ps.setBoolean(8, observation.threw());
            ps.setString(9, observation.refusedBeforeCall() == null ? null : observation.refusedBeforeCall().name());
            ps.setString(10, observation.refusalCause());
            one(ps.executeUpdate());
        }
    }

    // Mirrors the bounded custody guard contract without editing/exposing its private connection helpers.
    // Like ItemCustodyRepository.open(): item-repair returns are outside the commerce budget and never expire here.
    private static void custodyBudget(Connection c, UUID player, int quantity, Instant at) throws SQLException {
        List<ItemCustodyRepository.Receipt> held = new ArrayList<>();
        try (PreparedStatement ps = prepare(c, """
                SELECT * FROM npc_item_custody WHERE player_id=? AND state IN ('RESERVED','DELIVERY_INTENT','AMBIGUOUS')
                AND source<>'REPAIR_RETURN' ORDER BY request_nonce LIMIT ? FOR UPDATE
                """)) {
            id(ps, 1, player);
            ps.setInt(2, ItemCustodyRepository.MAX_OPEN_REQUESTS_PER_PLAYER + 1);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) held.add(custodyReceipt(rs)); }
        }
        if (held.size() > ItemCustodyRepository.MAX_OPEN_REQUESTS_PER_PLAYER) throw new SQLException("Corrupt custody count");
        int count = 0;
        long units = 0;
        for (ItemCustodyRepository.Receipt r : held) {
            if (expireCustody(c, r, at).state() != ItemCustodyRepository.State.EXPIRED) {
                count++;
                units += r.request().quantity();
            }
        }
        if (count >= ItemCustodyRepository.MAX_OPEN_REQUESTS_PER_PLAYER || units
                + quantity > ItemCustodyRepository.MAX_HELD_QUANTITY_PER_PLAYER)
            throw new SQLException("Player item custody budget is held");
    }
    private static ItemCustodyRepository.Receipt expireCustody(Connection c, ItemCustodyRepository.Receipt receipt,
            Instant at) throws SQLException {
        if (receipt.state() != ItemCustodyRepository.State.RESERVED || at.isBefore(receipt.request().expiresAt())) return receipt;
        if (isPaidCustody(c, receipt.request())) return receipt;
        try (PreparedStatement ps = prepare(c, "UPDATE npc_item_custody SET state='EXPIRED',outcome_code='RESERVATION_EXPIRED',"
                + "updated_at=CURRENT_TIMESTAMP(3) WHERE request_nonce=? AND state='RESERVED' AND delivery_run_id IS NULL")) {
            id(ps, 1, receipt.request().requestNonce());
            one(ps.executeUpdate());
        }
        return new ItemCustodyRepository.Receipt(receipt.request(), ItemCustodyRepository.State.EXPIRED, null, "RESERVATION_EXPIRED");
    }
    private static Optional<ItemCustodyRepository.Receipt> custody(Connection c, UUID nonce) throws SQLException {
        return custody(c, nonce, true);
    }
    private static Optional<ItemCustodyRepository.Receipt> custody(Connection c, UUID nonce, boolean lock) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_item_custody WHERE request_nonce=?" + (lock ? " FOR UPDATE" : ""))) {
            id(ps, 1, nonce);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(custodyReceipt(rs)) : Optional.empty();
            }
        }
    }
    private static void custodyBinding(Receipt receipt, ItemCustody request) throws SQLException {
        CommerceOrder order = receipt.order();
        if (receipt.state() != State.FUNDED || !Objects.equals(receipt.custodyNonce(), request.requestNonce())
                || !order.orderId().equals(request.requestNonce()) || !order.orderId().equals(request.offerId())
                || !order.playerId().equals(request.playerId()) || order.source() != request.source()
                || order.quantity() != request.quantity() || request.reservedAt().isBefore(receipt.outputReadyAt()))
            throw new SQLException("Paid commerce custody binding conflicts");
    }
    // FUNDED and its one-shot link are irreversible. A nonlocking RC read under the custody guard
    // avoids custody -> commerce lock inversion; the stored byte payload is the canonical grant.
    static boolean isPaidCustody(Connection c, ItemCustody request) throws SQLException {
        Receipt linked;
        try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_commerce_orders WHERE custody_nonce=?")) {
            id(ps, 1, request.requestNonce());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                linked = receipt(rs);
            }
        }
        custodyBinding(linked, request);
        ItemCustody stored = custody(c, request.requestNonce(), false)
                .orElseThrow(() -> new SQLException("Paid custody bytes missing")).request();
        if (!stored.equals(request)) throw new SQLException("Paid custody payload binding conflicts");
        return true;
    }
    private static ItemCustodyRepository.Receipt custodyReceipt(ResultSet rs) throws SQLException {
        try {
            CustodyBytes bytes = new CustodyBytes(rs.getBytes("payload_bytes"));
            if (!bytes.sha256().equals(rs.getString("payload_sha256"))) throw new SQLException("Custody payload hash conflicts");
            ItemCustody request = new ItemCustody(uuid(rs, "request_nonce"), uuid(rs, "player_id"), uuid(rs, "offer_id"),
                    ItemCustody.Source.valueOf(rs.getString("source")),
                    rs.getInt("quantity"), bytes, Instant.ofEpochMilli(rs.getLong("reserved_at_millis")),
                    Instant.ofEpochMilli(rs.getLong("expires_at_millis")));
            return new ItemCustodyRepository.Receipt(request, ItemCustodyRepository.State.valueOf(rs.getString("state")),
                    uuid(rs, "delivery_run_id"), rs.getString("outcome_code"));
        } catch (IllegalArgumentException | NullPointerException invalid) { throw new SQLException("Invalid linked custody", invalid); }
    }
    @FunctionalInterface private interface Parameters { void set(PreparedStatement ps) throws SQLException; }
    private void ensure(String sql, Parameters parameters) throws SQLException {
        try (Connection c = database.getConnection()) { ensure(c, sql, parameters); }
    }
    /** The same insert on the caller's connection, inside its transaction. A row that already exists is fine. */
    private static void ensure(Connection c, String sql, Parameters parameters) throws SQLException {
        try (PreparedStatement ps = prepare(c, sql)) {
            parameters.set(ps);
            try {
                one(ps.executeUpdate());
            }
            catch (SQLException duplicate) { if (duplicate.getErrorCode() != 1062 || !"23000".equals(duplicate.getSQLState()))
                throw duplicate; }
        }
    }
    private static boolean same(Observation a, Observation b) {
        return a.responsePresent() == b.responsePresent() && a.responseSuccess() == b.responseSuccess() && a.threw() == b.threw()
                && Objects.equals(bits(a.reportedDollars()), bits(b.reportedDollars()))
                && Objects.equals(bits(a.beforeDollars()), bits(b.beforeDollars()))
                && Objects.equals(bits(a.afterDollars()), bits(b.afterDollars()))
                && a.refusedBeforeCall() == b.refusedBeforeCall() && Objects.equals(a.refusalCause(), b.refusalCause());
    }
    private static Long bits(Double value) { return value == null ? null : Double.doubleToRawLongBits(value); }
    private static Double value(ResultSet rs, String name) throws SQLException {
        Long n = number(rs, name);
        return n == null ? null : Double.longBitsToDouble(n);
    }
    private static Long number(ResultSet rs, String name) throws SQLException {
        long n = rs.getLong(name);
        return rs.wasNull() ? null : n;
    }
    private static Instant instant(ResultSet rs, String name) throws SQLException {
        Long n = number(rs, name);
        return n == null ? null : Instant.ofEpochMilli(n);
    }
    private static void number(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) ps.setNull(index, Types.BIGINT);
        else ps.setLong(index, value);
    }
    private static UUID uuid(ResultSet rs, String name) throws SQLException {
        byte[] b = rs.getBytes(name);
        return b == null ? null : Uuids.fromBytes(b);
    }
    private static void id(PreparedStatement ps, int index, UUID value) throws SQLException { ps.setBytes(index, Uuids.toBytes(value)); }
    private static void nullableId(PreparedStatement ps, int index, UUID value) throws SQLException {
        if (value == null) ps.setNull(index, Types.BINARY);
        else id(ps, index, value);
    }
    private static Instant time(Instant now) {
        Instant at = ItemCustody.normalize(now);
        if (at.toEpochMilli() < 0) throw new IllegalArgumentException("Invalid commerce time");
        return at;
    }
    private static void one(int rows) throws SQLException {
        if (rows != 1) throw new SQLException("Commerce write did not affect exactly one row");
    }
    private static void budgetWrite(int rows) throws SQLException {
        if (rows == 0) throw new IssuanceLimit("Commerce issuance budget exhausted");
        one(rows);
    }
    private static void accountingBudget(long started) throws SQLException {
        long elapsed = System.nanoTime() - started;
        if (Thread.currentThread().isInterrupted() || elapsed < 0 || elapsed >= 20_000_000_000L)
            throw new SQLException("Commerce accounting read deadline");
    }
    private static PreparedStatement prepare(Connection c, String sql) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        try {
            ps.setQueryTimeout(5);
            return ps;
        }
        catch (SQLException error) {
            ps.close();
            throw error;
        }
    }
    @FunctionalInterface private interface Work<T> { T run(Connection c) throws SQLException; }
    private <T>T transaction(Work<T> work) throws SQLException {
        return transaction(true, work);
    }
    /** commit false runs the same work and rolls it back: an offline dry run. */
    private <T>T transaction(boolean commit, Work<T> work) throws SQLException {
        try (Connection c = database.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                if (commit) c.commit();
                else c.rollback();
                return result;
            }
            catch (SQLException | RuntimeException error) {
                try { c.rollback(); }
                catch (SQLException rollback) {
                    error.addSuppressed(rollback);
                    throw error;
                }
                if (error instanceof IssuanceLimit limit) throw new IssuanceRefused(limit.getMessage(), limit);
                throw error;
            }
        }
    }
}

package com.prosperitysmp.npc.commerce;

import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.Uuids;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL custody only, with no Bukkit, wallet or item creation calls. The issuer must own finite
 * stock/output and issue a unique one-shot offer; this journal is not funding authorization.
 * The shared runtime coordinator must serialize ALL inventory delivery paths for a player,
 * verify the serialized batch/quantity on the main thread, and dispatch only a freshly
 * acknowledged Start. An uncertain commit is read back, never retried as an inventory call.
 * SQL intent and Bukkit inventory mutation are deliberately not described as atomic.
 */
public final class ItemCustodyRepository {
    public static final int MAX_OPEN_REQUESTS_PER_PLAYER = 8;
    public static final int MAX_HELD_QUANTITY_PER_PLAYER = 512;
    public static final int MAX_SWEEP = 100;
    /** One undelivered item-repair return set per player, at most this many stacks. Counted apart from the commerce budget. */
    public static final int MAX_RETURN_STACKS = 4;
    public enum State { RESERVED, DELIVERY_INTENT, DELIVERED, AMBIGUOUS, CANCELLED, EXPIRED, RECONCILED_NOT_DELIVERED }
    public enum Uncertainty { DELIVERY_UNCERTAIN, INTERRUPTED_RUN, CANCELLED_AFTER_INTENT }
    /**
     * RETURNED_BY_STAFF is for item repair returns only: it closes a return held as {@link #PAYLOAD_UNREADABLE} after staff
     * gave the items back by hand. It issues nothing again. Commerce reconciliation refuses it.
     */
    public enum Decision { VERIFIED_DELIVERED, VERIFIED_NOT_DELIVERED, RETURNED_BY_STAFF }
    /**
     * The outcome of a REPAIR_RETURN held because its stored items could not be read back into an item. Nothing was
     * started and nothing was given, so it never counts as a delivery that might have happened.
     */
    public static final String PAYLOAD_UNREADABLE = "PAYLOAD_UNREADABLE";
    public record Receipt(ItemCustody request, State state, UUID deliveryRunId, String outcome) {
        public Receipt {
            Objects.requireNonNull(request); Objects.requireNonNull(state);
            boolean started = state == State.DELIVERY_INTENT || state == State.DELIVERED
                    || state == State.AMBIGUOUS || state == State.RECONCILED_NOT_DELIVERED;
            if (started != (deliveryRunId != null)) throw new IllegalArgumentException("Invalid custody run binding");
        }
    }
    public record Reservation(boolean newlyReserved, Receipt receipt) { }
    /** False includes replay, expired/cancelled/terminal custody, and another held player delivery. */
    public record Start(boolean newlyStarted, Receipt receipt) { }
    public record OperatorResolution(UUID resolutionId, String operatorRef, Decision decision, CustodyBytes evidence) {
        public OperatorResolution {
            Objects.requireNonNull(resolutionId); Objects.requireNonNull(decision); Objects.requireNonNull(evidence);
            if (operatorRef == null || !operatorRef.matches("[A-Za-z0-9_.:@-]{1,96}"))
                throw new IllegalArgumentException("Invalid operator reference");
        }
    }
    private record Key(UUID playerId, UUID nonce) { }
    private final Database database;

    public ItemCustodyRepository(Database database) { this.database = Objects.requireNonNull(database); }

    /** Reservation replay must match every field and the serialized bytes, not only their hash. */
    public Reservation reserve(ItemCustody request, Instant now) throws SQLException {
        Objects.requireNonNull(request); now = checkedTime(now);
        // Returns are created only inside an item-repair settle transaction, never as a budgeted offer.
        if (request.source() == ItemCustody.Source.REPAIR_RETURN) throw new IllegalArgumentException("Repair returns use reserveReturn");
        Instant at = now;
        ensurePlayer(request.playerId());
        return transaction(c -> {
            requirePlayer(c, request.playerId());
            Optional<Receipt> old = read(c, request.playerId(), request.requestNonce());
            if (old.isPresent()) {
                binding(old.get(), request);
                return new Reservation(false, expire(c, old.get(), at));
            }
            if (at.isBefore(request.reservedAt()) || !at.isBefore(request.expiresAt()))
                throw new IllegalArgumentException("Reservation is outside its issued interval");
            List<Receipt> held = open(c, request.playerId(), at);
            long units = held.stream().mapToLong(r -> r.request().quantity()).sum();
            if (held.size() >= MAX_OPEN_REQUESTS_PER_PLAYER || units + request.quantity() > MAX_HELD_QUANTITY_PER_PLAYER)
                throw new SQLException("Player item custody budget is held");
            try (PreparedStatement ps = prepare(c, """
                    INSERT INTO npc_item_custody(request_nonce,player_id,offer_id,source,quantity,payload_bytes,
                    payload_sha256,reserved_at_millis,expires_at_millis,state) VALUES(?,?,?,?,?,?,?,?,?,'RESERVED')
                    """)) {
                id(ps, 1, request.requestNonce()); id(ps, 2, request.playerId()); id(ps, 3, request.offerId());
                ps.setString(4, request.source().name()); ps.setInt(5, request.quantity());
                ps.setBytes(6, request.payload().bytes()); ps.setString(7, request.payload().sha256());
                ps.setLong(8, request.reservedAt().toEpochMilli()); ps.setLong(9, request.expiresAt().toEpochMilli());
                one(ps.executeUpdate());
            }
            return new Reservation(true, new Receipt(request, State.RESERVED, null, null));
        });
    }

    /** Fresh per-boot run UUID. Replay of ANY intent never returns dispatch authority. */
    public Start beginDelivery(ItemCustody expected, UUID runId, Instant now) throws SQLException {
        Objects.requireNonNull(expected); Objects.requireNonNull(runId);
        Instant at = checkedTime(now);
        return transaction(c -> {
            requirePlayer(c, expected.playerId());
            Receipt receipt = expire(c, required(c, expected), at);
            if (receipt.state() != State.RESERVED) return new Start(false, receipt);
            if (at.isBefore(expected.reservedAt())) throw new IllegalArgumentException("Delivery precedes issuance");
            try (PreparedStatement ps = prepare(c, """
                    SELECT request_nonce FROM npc_item_custody WHERE player_id=?
                    AND state IN ('DELIVERY_INTENT','AMBIGUOUS') LIMIT 1 FOR UPDATE
                    """)) {
                id(ps, 1, expected.playerId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return new Start(false, receipt);
                }
            }
            // An item hand-in that is running or held for staff owns this inventory too. Inert while that table is empty.
            try (PreparedStatement ps = prepare(c, """
                    SELECT collection_id FROM npc_item_repair_collections WHERE player_id=?
                    AND state IN ('INTENT','AMBIGUOUS') LIMIT 1 FOR UPDATE
                    """)) {
                id(ps, 1, expected.playerId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return new Start(false, receipt);
                }
            }
            // A contract hand-over that is running or held for staff owns this inventory too. Inert while that table is empty.
            try (PreparedStatement ps = prepare(c, """
                    SELECT contract_id FROM npc_town_contracts WHERE open_player_id=?
                    AND status IN ('HANDOVER_INTENT','AMBIGUOUS') LIMIT 1 FOR UPDATE
                    """)) {
                id(ps, 1, expected.playerId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return new Start(false, receipt);
                }
            }
            return new Start(true, transition(c, receipt, State.DELIVERY_INTENT, runId, null));
        });
    }

    /** Exact immutable evidence replay; late evidence is retained but cannot release recovered ambiguity. */
    public Receipt recordDelivery(ItemCustody expected, UUID runId, ItemDeliveryEvidence evidence) throws SQLException {
        Objects.requireNonNull(expected); Objects.requireNonNull(evidence); Objects.requireNonNull(runId);
        return transaction(c -> {
            requirePlayer(c, expected.playerId());
            Receipt receipt = required(c, expected); runBinding(receipt, runId);
            if (!expected.playerId().equals(evidence.playerId()) || !expected.payload().sha256().equals(evidence.payloadSha256()))
                throw new SQLException("Inventory evidence does not bind the custody recipient and payload");
            Optional<ItemDeliveryEvidence> old = evidence(c, expected.requestNonce());
            if (old.isPresent()) {
                if (!old.get().equals(evidence)) throw new SQLException("Conflicting inventory acknowledgement");
                return receipt;
            }
            if (receipt.state() != State.DELIVERY_INTENT && receipt.state() != State.AMBIGUOUS)
                throw new SQLException("Inventory acknowledgement without an unresolved intent");
            try (PreparedStatement ps = prepare(c, """
                    INSERT INTO npc_item_delivery_evidence(request_nonce,player_id,payload_sha256,
                    before_inventory_sha256,after_inventory_sha256,before_units,after_units,leftover_units,
                    returned_normally) VALUES(?,?,?,?,?,?,?,?,?)
                    """)) {
                id(ps, 1, expected.requestNonce()); id(ps, 2, evidence.playerId());
                ps.setString(3, evidence.payloadSha256()); ps.setString(4, evidence.beforeInventorySha256());
                ps.setString(5, evidence.afterInventorySha256()); ps.setInt(6, evidence.beforeUnits());
                ps.setInt(7, evidence.afterUnits()); ps.setInt(8, evidence.leftoverUnits());
                ps.setBoolean(9, evidence.returnedNormally()); one(ps.executeUpdate());
            }
            if (receipt.state() == State.AMBIGUOUS) return receipt;
            return evidence.confirms(expected)
                    ? transition(c, receipt, State.DELIVERED, runId, "INVENTORY_CONFIRMED")
                    : transition(c, receipt, State.AMBIGUOUS, runId, "EVIDENCE_UNCERTAIN");
        });
    }

    public Receipt markAmbiguous(ItemCustody expected, UUID runId, Uncertainty reason) throws SQLException {
        Objects.requireNonNull(expected); Objects.requireNonNull(runId); Objects.requireNonNull(reason);
        return transaction(c -> {
            requirePlayer(c, expected.playerId());
            Receipt receipt = required(c, expected); runBinding(receipt, runId);
            return receipt.state() == State.DELIVERY_INTENT
                    ? transition(c, receipt, State.AMBIGUOUS, runId, reason.name()) : receipt;
        });
    }

    /**
     * A REPAIR_RETURN whose stored items cannot be read back into an item. Nothing started and nothing was given. The row
     * moves from RESERVED to AMBIGUOUS {@link #PAYLOAD_UNREADABLE} for this run, so it is held like any other held
     * delivery: no delivery of any kind starts for the player until staff settle it with the offline tool. Any other state
     * comes back unchanged. So does a RESERVED row while another delivery for the player is running or held: that one
     * already holds the player, and only one row per player may be. Commerce rows are refused.
     */
    public Receipt holdUnreadableReturn(ItemCustody expected, UUID runId) throws SQLException {
        Objects.requireNonNull(expected); Objects.requireNonNull(runId);
        if (!isRepairReturn(expected)) throw new IllegalArgumentException("Only repair returns are held as unreadable");
        return transaction(c -> {
            requirePlayer(c, expected.playerId());
            Receipt receipt = required(c, expected);
            if (receipt.state() != State.RESERVED) return receipt;
            try (PreparedStatement ps = prepare(c, """
                    SELECT request_nonce FROM npc_item_custody WHERE player_id=?
                    AND state IN ('DELIVERY_INTENT','AMBIGUOUS') LIMIT 1 FOR UPDATE
                    """)) {
                id(ps, 1, expected.playerId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return receipt;
                }
            }
            return transition(c, receipt, State.AMBIGUOUS, runId, PAYLOAD_UNREADABLE);
        });
    }

    /** Cancellation before intent releases custody; after intent it holds ambiguity, never stock for a retry. */
    public Receipt cancel(ItemCustody expected, Instant now) throws SQLException {
        Objects.requireNonNull(expected); Instant at = checkedTime(now);
        return transaction(c -> {
            requirePlayer(c, expected.playerId());
            Receipt receipt = expire(c, required(c, expected), at);
            return switch (receipt.state()) {
                case RESERVED -> transition(c, receipt, State.CANCELLED, null, "RESERVATION_CANCELLED");
                case DELIVERY_INTENT -> transition(c, receipt, State.AMBIGUOUS,
                        receipt.deliveryRunId(), Uncertainty.CANCELLED_AFTER_INTENT.name());
                default -> receipt;
            };
        });
    }

    public Optional<Receipt> find(UUID playerId, UUID requestNonce, Instant now) throws SQLException {
        Objects.requireNonNull(playerId); Objects.requireNonNull(requestNonce); Instant at = checkedTime(now);
        return transaction(c -> {
            if (!lockPlayer(c, playerId)) return Optional.empty();
            Optional<Receipt> receipt = read(c, playerId, requestNonce);
            return receipt.isEmpty() ? receipt : Optional.of(expire(c, receipt.get(), at));
        });
    }

    /** Bounded startup barrier: call until zero before enabling deliveries; old intents already hold the player. */
    public int recoverOtherRuns(UUID currentRun, int limit) throws SQLException {
        Objects.requireNonNull(currentRun);
        List<Key> keys = selectKeys("""
                SELECT player_id,request_nonce FROM npc_item_custody WHERE state='DELIVERY_INTENT'
                AND delivery_run_id<>? ORDER BY request_nonce LIMIT ?
                """, ps -> id(ps, 1, currentRun), limit);
        int changed = 0;
        for (Key key : keys) changed += transaction(c -> {
            requirePlayer(c, key.playerId());
            Receipt receipt = read(c, key.playerId(), key.nonce()).orElseThrow(() -> new SQLException("Recovery custody vanished"));
            if (receipt.state() != State.DELIVERY_INTENT || currentRun.equals(receipt.deliveryRunId())) return 0;
            transition(c, receipt, State.AMBIGUOUS, receipt.deliveryRunId(), Uncertainty.INTERRUPTED_RUN.name());
            return 1;
        });
        return changed;
    }

    public int expireReservations(Instant now, int limit) throws SQLException {
        Instant at = checkedTime(now);
        List<Key> keys = selectKeys("""
                SELECT i.player_id,i.request_nonce FROM npc_item_custody i WHERE i.state='RESERVED'
                AND i.expires_at_millis<=? AND i.source<>'REPAIR_RETURN' AND NOT EXISTS (
                    SELECT 1 FROM npc_commerce_orders o WHERE o.custody_nonce=i.request_nonce AND o.state='FUNDED'
                    AND o.order_id=i.offer_id AND o.player_id=i.player_id AND o.source=i.source AND o.quantity=i.quantity
                    AND i.reserved_at_millis>=o.output_ready_at_millis AND BINARY i.payload_sha256=BINARY SHA2(i.payload_bytes,256))
                ORDER BY i.expires_at_millis,i.request_nonce LIMIT ?
                """, ps -> ps.setLong(1, at.toEpochMilli()), limit);
        int changed = 0;
        for (Key key : keys) changed += transaction(c -> {
            requirePlayer(c, key.playerId());
            Receipt receipt = read(c, key.playerId(), key.nonce()).orElseThrow(() -> new SQLException("Expiry custody vanished"));
            return expire(c, receipt, at).state() != receipt.state() ? 1 : 0;
        });
        return changed;
    }

    /**
     * Authenticated operator/offline boundary ONLY; no runtime player endpoint. Caller must quiesce
     * the old dispatch and exclusively verify inventory/stock plus the evidence bytes before using
     * this API. Non-delivery never returns the old request to RESERVED; offer and nonce remain burned.
     */
    public Receipt reconcile(ItemCustody expected, UUID runId, OperatorResolution resolution) throws SQLException {
        Objects.requireNonNull(expected); Objects.requireNonNull(runId); Objects.requireNonNull(resolution);
        return transaction(c -> reconcileIn(c, expected, runId, resolution, false).receipt());
    }

    /** fresh is true only when this call wrote the decision; false is an exact replay of the kept one. */
    public record Reconciled(Receipt receipt, boolean fresh) {
        public Reconciled { Objects.requireNonNull(receipt); }
    }

    /**
     * Offline item-repair recovery only (ItemRepairRepository.resolveReturn), server stopped, inside the caller's
     * open transaction. Same rules as {@link #reconcile}, for a REPAIR_RETURN row only. A DELIVERY_INTENT left by a run
     * that died with the server is first held as INTERRUPTED_RUN, exactly what the next start would do.
     */
    public static Reconciled reconcileHeldReturn(Connection c, ItemCustody expected, UUID runId, OperatorResolution resolution)
            throws SQLException {
        Objects.requireNonNull(c); Objects.requireNonNull(expected); Objects.requireNonNull(runId); Objects.requireNonNull(resolution);
        if (c.getAutoCommit()) throw new SQLException("Return reconciliation requires the caller's transaction");
        if (!isRepairReturn(expected)) throw new IllegalArgumentException("Only repair returns use reconcileHeldReturn");
        return reconcileIn(c, expected, runId, resolution, true);
    }

    private static Reconciled reconcileIn(Connection c, ItemCustody expected, UUID runId, OperatorResolution resolution,
                                          boolean holdInterruptedIntent) throws SQLException {
        requirePlayer(c, expected.playerId());
        Receipt receipt = required(c, expected); runBinding(receipt, runId);
        Optional<OperatorResolution> old = resolution(c, expected.requestNonce());
        if (old.isPresent()) {
            if (!old.get().equals(resolution)) throw new SQLException("Conflicting operator reconciliation");
            return new Reconciled(receipt, false);
        }
        if (holdInterruptedIntent && receipt.state() == State.DELIVERY_INTENT)
            receipt = transition(c, receipt, State.AMBIGUOUS, runId, Uncertainty.INTERRUPTED_RUN.name());
        if (receipt.state() != State.AMBIGUOUS) throw new SQLException("Operator reconciliation requires held ambiguity");
        boolean unreadable = PAYLOAD_UNREADABLE.equals(receipt.outcome());
        // Staff gave the items back by hand: only for a return held because its items could not be read, which the plugin
        // never started. Any other held delivery may have reached the player, so it is settled from the evidence.
        if (resolution.decision() == Decision.RETURNED_BY_STAFF
                && (!holdInterruptedIntent || !unreadable || evidence(c, expected.requestNonce()).isPresent()))
            throw new SQLException("RETURNED_BY_STAFF closes only a return held because its items could not be read");
        if (resolution.decision() == Decision.VERIFIED_DELIVERED && unreadable)
            throw new SQLException("This return never started, so it was not delivered. Use RETURNED_BY_STAFF if staff gave the items back");
        try (PreparedStatement ps = prepare(c, """
                INSERT INTO npc_item_custody_resolutions(request_nonce,resolution_id,operator_ref,decision,
                evidence_bytes,evidence_sha256,original_outcome) VALUES(?,?,?,?,?,?,?)
                """)) {
            id(ps, 1, expected.requestNonce()); id(ps, 2, resolution.resolutionId());
            ps.setString(3, resolution.operatorRef()); ps.setString(4, resolution.decision().name());
            ps.setBytes(5, resolution.evidence().bytes()); ps.setString(6, resolution.evidence().sha256());
            ps.setString(7, receipt.outcome()); one(ps.executeUpdate());
        }
        State state = resolution.decision() == Decision.VERIFIED_NOT_DELIVERED
                ? State.RECONCILED_NOT_DELIVERED : State.DELIVERED;
        return new Reconciled(transition(c, receipt, state, runId, resolution.decision().name()), true);
    }

    /** Read-only: the inventory counts the runtime recorded for this delivery, if it got that far. */
    public Optional<ItemDeliveryEvidence> recordedEvidence(UUID playerId, UUID requestNonce) throws SQLException {
        Objects.requireNonNull(playerId); Objects.requireNonNull(requestNonce);
        return transaction(c -> {
            if (!lockPlayer(c, playerId) || read(c, playerId, requestNonce).isEmpty()) return Optional.empty();
            return evidence(c, requestNonce);
        });
    }

    private List<Receipt> open(Connection c, UUID playerId, Instant now) throws SQLException {
        List<Receipt> held = new ArrayList<>();
        try (PreparedStatement ps = prepare(c, """
                SELECT * FROM npc_item_custody WHERE player_id=? AND state IN ('RESERVED','DELIVERY_INTENT','AMBIGUOUS')
                AND source<>'REPAIR_RETURN' ORDER BY request_nonce LIMIT ? FOR UPDATE
                """)) {
            id(ps, 1, playerId); ps.setInt(2, MAX_OPEN_REQUESTS_PER_PLAYER + 1);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) held.add(receipt(rs)); }
        }
        if (held.size() > MAX_OPEN_REQUESTS_PER_PLAYER) throw new SQLException("Corrupt player custody count");
        List<Receipt> active = new ArrayList<>();
        for (Receipt receipt : held) {
            Receipt current = expire(c, receipt, now);
            if (current.state() != State.EXPIRED) active.add(current);
        }
        return active;
    }

    private static Receipt expire(Connection c, Receipt receipt, Instant now) throws SQLException {
        return receipt.state() == State.RESERVED && !now.isBefore(receipt.request().expiresAt())
                && !isRepairReturn(receipt.request())
                && !CommerceOrderRepository.isPaidCustody(c,receipt.request())
                ? transition(c, receipt, State.EXPIRED, null, "RESERVATION_EXPIRED") : receipt;
    }

    /**
     * Items an item repair already took. They never expire (the row still carries the nominal 15 minute expiry the
     * schema requires) and never count against the commerce budget.
     */
    static boolean isRepairReturn(ItemCustody request) {
        return request.source() == ItemCustody.Source.REPAIR_RETURN;
    }

    /**
     * ItemRepairRepository only: its settle (the items were proven taken) and its offline return resolution (staff
     * proved a stack never arrived, so the same stack is issued again). The caller holds an open transaction.
     * Inserts a RESERVED REPAIR_RETURN row without the commerce budget, because taken items must always get a custody
     * row. Refused outside a transaction, for any other source, or past one return set of 4 stacks.
     */
    public static Receipt reserveReturn(Connection c, ItemCustody request) throws SQLException {
        Objects.requireNonNull(c); Objects.requireNonNull(request);
        if (c.getAutoCommit()) throw new SQLException("Repair return requires the caller's transaction");
        if (!isRepairReturn(request)) throw new IllegalArgumentException("Only repair returns use reserveReturn");
        requirePlayer(c, request.playerId());
        if (read(c, request.playerId(), request.requestNonce()).isPresent()) throw new SQLException("Repair return nonce already used");
        try (PreparedStatement ps = prepare(c, """
                INSERT INTO npc_item_custody(request_nonce,player_id,offer_id,source,quantity,payload_bytes,
                payload_sha256,reserved_at_millis,expires_at_millis,state) VALUES(?,?,?,?,?,?,?,?,?,'RESERVED')
                """)) {
            id(ps, 1, request.requestNonce()); id(ps, 2, request.playerId()); id(ps, 3, request.offerId());
            ps.setString(4, request.source().name()); ps.setInt(5, request.quantity());
            ps.setBytes(6, request.payload().bytes()); ps.setString(7, request.payload().sha256());
            ps.setLong(8, request.reservedAt().toEpochMilli()); ps.setLong(9, request.expiresAt().toEpochMilli());
            one(ps.executeUpdate());
        }
        if (undeliveredReturns(c, request.playerId()) > MAX_RETURN_STACKS)
            throw new SQLException("Repair return set exceeds its bound");
        return new Receipt(request, State.RESERVED, null, null);
    }

    /** Undelivered REPAIR_RETURN rows for the player, counted apart from the commerce budget (bounded read). */
    public static int undeliveredReturns(Connection c, UUID playerId) throws SQLException {
        try (PreparedStatement ps = prepare(c, """
                SELECT COUNT(*) FROM (SELECT request_nonce FROM npc_item_custody WHERE player_id=? AND source='REPAIR_RETURN'
                AND state IN ('RESERVED','DELIVERY_INTENT','AMBIGUOUS') LIMIT ?) bounded
                """)) {
            id(ps, 1, playerId); ps.setInt(2, MAX_RETURN_STACKS * 4 + 1);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    private static Receipt required(Connection c, ItemCustody expected) throws SQLException {
        Receipt receipt = read(c, expected.playerId(), expected.requestNonce())
                .orElseThrow(() -> new SQLException("Unknown item custody for this player"));
        binding(receipt, expected);
        return receipt;
    }

    private static void binding(Receipt stored, ItemCustody expected) throws SQLException {
        if (!stored.request().equals(expected)) throw new SQLException("Item custody replay binding conflicts");
    }
    private static void runBinding(Receipt receipt, UUID runId) throws SQLException {
        if (!runId.equals(receipt.deliveryRunId())) throw new SQLException("Item custody delivery run conflicts");
    }

    private static Optional<Receipt> read(Connection c, UUID playerId, UUID nonce) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_item_custody WHERE request_nonce=? AND player_id=? FOR UPDATE")) {
            id(ps, 1, nonce); id(ps, 2, playerId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(receipt(rs)) : Optional.empty(); }
        }
    }
    private static Receipt receipt(ResultSet rs) throws SQLException {
        try {
            CustodyBytes bytes = new CustodyBytes(rs.getBytes("payload_bytes"));
            if (!bytes.sha256().equals(rs.getString("payload_sha256"))) throw new SQLException("Item custody payload hash mismatch");
            ItemCustody request = new ItemCustody(uuid(rs, "request_nonce"), uuid(rs, "player_id"), uuid(rs, "offer_id"),
                    ItemCustody.Source.valueOf(rs.getString("source")), rs.getInt("quantity"), bytes,
                    Instant.ofEpochMilli(rs.getLong("reserved_at_millis")), Instant.ofEpochMilli(rs.getLong("expires_at_millis")));
            return new Receipt(request, State.valueOf(rs.getString("state")), uuid(rs, "delivery_run_id"), rs.getString("outcome_code"));
        } catch (IllegalArgumentException | NullPointerException invalid) { throw new SQLException("Invalid stored item custody", invalid); }
    }
    private static Optional<ItemDeliveryEvidence> evidence(Connection c, UUID nonce) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_item_delivery_evidence WHERE request_nonce=? FOR UPDATE")) {
            id(ps, 1, nonce);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                try {
                    return Optional.of(new ItemDeliveryEvidence(uuid(rs, "player_id"), rs.getString("payload_sha256"),
                            rs.getString("before_inventory_sha256"), rs.getString("after_inventory_sha256"),
                            rs.getInt("before_units"), rs.getInt("after_units"), rs.getInt("leftover_units"), rs.getBoolean("returned_normally")));
                } catch (IllegalArgumentException | NullPointerException invalid) { throw new SQLException("Invalid stored inventory evidence", invalid); }
            }
        }
    }
    private static Optional<OperatorResolution> resolution(Connection c, UUID nonce) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_item_custody_resolutions WHERE request_nonce=? FOR UPDATE")) {
            id(ps, 1, nonce);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                try {
                    CustodyBytes bytes = new CustodyBytes(rs.getBytes("evidence_bytes"));
                    if (!bytes.sha256().equals(rs.getString("evidence_sha256"))) throw new SQLException("Operator evidence hash mismatch");
                    return Optional.of(new OperatorResolution(uuid(rs, "resolution_id"), rs.getString("operator_ref"),
                            Decision.valueOf(rs.getString("decision")), bytes));
                } catch (IllegalArgumentException | NullPointerException invalid) { throw new SQLException("Invalid stored operator evidence", invalid); }
            }
        }
    }

    private static Receipt transition(Connection c, Receipt old, State state, UUID runId, String outcome) throws SQLException {
        try (PreparedStatement ps = prepare(c, """
                UPDATE npc_item_custody SET state=?,delivery_run_id=?,outcome_code=?,updated_at=CURRENT_TIMESTAMP(3)
                WHERE request_nonce=? AND player_id=? AND state=? AND delivery_run_id<=>?
                """)) {
            ps.setString(1, state.name()); nullableId(ps, 2, runId); ps.setString(3, outcome);
            id(ps, 4, old.request().requestNonce()); id(ps, 5, old.request().playerId());
            ps.setString(6, old.state().name()); nullableId(ps, 7, old.deliveryRunId()); one(ps.executeUpdate());
        }
        return new Receipt(old.request(), state, runId, outcome);
    }

    // Autocommit duplicate-key acquisition ends before taking the shared exclusive player lock.
    private void ensurePlayer(UUID playerId) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c,
                "INSERT INTO npc_item_custody_players(player_id) VALUES(?)")) {
            id(ps, 1, playerId);
            try { one(ps.executeUpdate()); }
            catch (SQLException duplicate) {
                if (duplicate.getErrorCode() != 1062 || !"23000".equals(duplicate.getSQLState())) throw duplicate;
            }
        }
    }
    private static boolean lockPlayer(Connection c, UUID playerId) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT player_id FROM npc_item_custody_players WHERE player_id=? FOR UPDATE")) {
            id(ps, 1, playerId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }
    private static void requirePlayer(Connection c, UUID playerId) throws SQLException {
        if (!lockPlayer(c, playerId)) throw new SQLException("Item custody player guard is missing");
    }
    @FunctionalInterface private interface Parameter { void set(PreparedStatement ps) throws SQLException; }
    private List<Key> selectKeys(String sql, Parameter parameter, int limit) throws SQLException {
        if (limit < 0) throw new IllegalArgumentException("Negative custody sweep limit");
        List<Key> keys = new ArrayList<>();
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c, sql)) {
            parameter.set(ps); ps.setInt(2, Math.min(limit, MAX_SWEEP));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) keys.add(new Key(uuid(rs, "player_id"), uuid(rs, "request_nonce")));
            }
        }
        return keys;
    }
    private static Instant checkedTime(Instant now) {
        Instant at = ItemCustody.normalize(now);
        if (at.toEpochMilli() < 0) throw new IllegalArgumentException("Invalid custody time");
        return at;
    }
    private static UUID uuid(ResultSet rs, String name) throws SQLException {
        byte[] bytes = rs.getBytes(name); return bytes == null ? null : Uuids.fromBytes(bytes);
    }
    private static void id(PreparedStatement ps, int index, UUID value) throws SQLException { ps.setBytes(index, Uuids.toBytes(value)); }
    private static void nullableId(PreparedStatement ps, int index, UUID value) throws SQLException {
        if (value == null) ps.setNull(index, Types.BINARY); else id(ps, index, value);
    }
    private static void one(int count) throws SQLException {
        if (count != 1) throw new SQLException("Item custody write did not affect exactly one row");
    }
    private static PreparedStatement prepare(Connection c, String sql) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        try { ps.setQueryTimeout(5); return ps; }
        catch (SQLException failure) { ps.close(); throw failure; }
    }
    @FunctionalInterface private interface Work<T> { T run(Connection c) throws SQLException; }
    private <T> T transaction(Work<T> work) throws SQLException {
        try (Connection c = database.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED); c.setAutoCommit(false);
            try { T result = work.run(c); c.commit(); return result; }
            catch (SQLException | RuntimeException failure) {
                try { c.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }
}

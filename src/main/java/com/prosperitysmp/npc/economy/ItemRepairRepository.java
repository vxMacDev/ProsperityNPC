package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.commerce.CustodyBytes;
import com.prosperitysmp.npc.commerce.ItemCollectionEvidence;
import com.prosperitysmp.npc.commerce.ItemCustody;
import com.prosperitysmp.npc.commerce.ItemCustodyRepository;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.formulas.IncidentType;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL only for item repairs: no Bukkit, Vault, transfer journal, cap or economy event. Items in, repair out:
 * a hand-in is written (INTENT) before any item is taken, and the repair applies only after the take is proven
 * durable, in the same transaction as its receipt and memories. Items proven taken while the home changed come
 * back as REPAIR_RETURN custody, created only here. Anything unclear is AMBIGUOUS and is never retried.
 * Lock order: custody player guard, then the property, then custody rows, then hand-in rows, then the incident.
 */
public final class ItemRepairRepository {
    public enum State { INTENT, CANCELLED, APPLIED, RETURN_ISSUED, AMBIGUOUS, RECONCILED_NOT_COLLECTED }
    public enum Refusal { PLAYER_ITEMS_HELD, RETURNS_PENDING, COLLECTION_OPEN, CASH_PENDING, OWNERSHIP_CHANGED, TARGET_CHANGED }
    public enum Uncertainty { EVIDENCE_UNCERTAIN, INTERRUPTED_RUN, TAKE_FAILED, SETTLE_FAILED }
    public enum Decision { VERIFIED_COLLECTED, VERIFIED_NOT_COLLECTED }
    public static final int MAX_SWEEP = 100;
    /** Started is true only for a freshly written INTENT. detail names a changed target (for logs). */
    public record Begin(boolean started, Refusal refusal, String detail) {
        static Begin ok() { return new Begin(true, null, null); }
        static Begin refused(Refusal refusal, String detail) { return new Begin(false, Objects.requireNonNull(refusal), detail); }
    }
    public record Stored(ItemRepairQuote quote, State state, UUID runId, String outcome,
                         Instant createdAt, Instant updatedAt) {
        public Stored {
            Objects.requireNonNull(quote);
            Objects.requireNonNull(state);
            Objects.requireNonNull(runId);
        }
    }
    /** returns are the RESERVED REPAIR_RETURN custody rows of this hand-in, in stack order. */
    public record Settlement(State state, String outcome, List<ItemCustody> returns) {
        public Settlement {
            Objects.requireNonNull(state);
            returns = List.copyOf(returns);
        }
    }
    public record OperatorResolution(UUID resolutionId, String operatorRef, Decision decision, CustodyBytes evidence) {
        public OperatorResolution {
            Objects.requireNonNull(resolutionId);
            Objects.requireNonNull(decision);
            Objects.requireNonNull(evidence);
            if (operatorRef == null || !operatorRef.matches("[A-Za-z0-9_.:@-]{1,96}"))
                throw new IllegalArgumentException("Invalid operator reference");
        }
    }
    /**
     * One row for /npcadmin itemrepair list. heldReturn is the request nonce of a return stack that is held (AMBIGUOUS) or
     * mid-delivery (DELIVERY_INTENT), named by heldReturnState; both null when there is none.
     */
    public record Summary(UUID collectionId, UUID playerId, long incidentId, State state, String outcome, int undeliveredReturns,
                          UUID heldReturn, String heldReturnState) { }
    private record Key(UUID collectionId, UUID playerId) { }

    private final Database database;

    public ItemRepairRepository(Database database) { this.database = Objects.requireNonNull(database); }

    /**
     * Writes INTENT for a frozen quote, or names why not. Nothing is written on a refusal. The incident snapshot,
     * owner, epoch and enabled flag are checked under the same locks the repair will use.
     */
    public Begin begin(ItemRepairQuote q, UUID runId, Instant at, LocalDate currentDay) throws SQLException {
        Objects.requireNonNull(q);
        Objects.requireNonNull(runId);
        Objects.requireNonNull(at);
        Objects.requireNonNull(currentDay);
        ensurePlayer(q.playerId());
        return transaction(c -> {
            requirePlayer(c, q.playerId());
            if (!ownerMatches(c, q)) return Begin.refused(Refusal.OWNERSHIP_CHANGED, "OWNERSHIP_CHANGED");
            if (exists(c, """
                    SELECT request_nonce FROM npc_item_custody WHERE player_id=?
                    AND state IN ('DELIVERY_INTENT','AMBIGUOUS') LIMIT 1 FOR UPDATE
                    """, q.playerId())) return Begin.refused(Refusal.PLAYER_ITEMS_HELD, null);
            // A contract hand-over that is running or held for staff owns this inventory. Inert while that table is empty.
            if (exists(c, """
                    SELECT contract_id FROM npc_town_contracts WHERE open_player_id=?
                    AND status IN ('HANDOVER_INTENT','AMBIGUOUS') LIMIT 1 FOR UPDATE
                    """, q.playerId())) return Begin.refused(Refusal.PLAYER_ITEMS_HELD, null);
            if (ItemCustodyRepository.undeliveredReturns(c, q.playerId()) > 0) return Begin.refused(Refusal.RETURNS_PENDING, null);
            if (exists(c, """
                    SELECT collection_id FROM npc_item_repair_collections WHERE (player_id=? OR incident_id=?)
                    AND state IN ('INTENT','AMBIGUOUS') LIMIT 1 FOR UPDATE
                    """, q.playerId(), q.repair().incidentId())) return Begin.refused(Refusal.COLLECTION_OPEN, null);
            // A cash repair that is waiting or held for review: never let the owner pay twice.
            if (exists(c, """
                    SELECT event_id FROM npc_purchase_intents WHERE property_id=? AND kind='REPAIR' AND repair_incident_id=?
                    AND state IN ('WAITING','REVIEW_REQUIRED') LIMIT 1
                    """, q.propertyId(), q.repair().incidentId())) return Begin.refused(Refusal.CASH_PENDING, null);
            String problem = IncidentRepair.itemTargetProblem(c, q, at, currentDay);
            if (problem != null) return Begin.refused(Refusal.TARGET_CHANGED, problem);
            insert(c, q, runId);
            return Begin.ok();
        });
    }

    /**
     * Read-only: the incident's current repair snapshot under the repair locks, for building a frozen quote.
     * Empty when the incident is resolved, prevented, missing or the home's owner, epoch or enabled flag differ.
     */
    public Optional<RepairQuote> currentRepair(UUID propertyId, UUID playerId, int ownershipEpoch, long incidentId,
                                               Instant at, LocalDate currentDay, LocalDate quoteDay) throws SQLException {
        Objects.requireNonNull(propertyId);
        Objects.requireNonNull(playerId);
        Objects.requireNonNull(at);
        Objects.requireNonNull(currentDay);
        Objects.requireNonNull(quoteDay);
        if (incidentId <= 0) return Optional.empty();
        return transaction(c -> {
            try (PreparedStatement ps = prepare(c,
                    "SELECT owner_uuid,ownership_epoch,enabled FROM npc_properties WHERE property_id=? FOR UPDATE")) {
                id(ps, 1, propertyId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || !playerId.equals(uuid(rs, "owner_uuid")) || rs.getInt("ownership_epoch") != ownershipEpoch
                            || !rs.getBoolean("enabled")) return Optional.empty();
                }
            }
            try {
                return Optional.of(IncidentRepair.quote(c, propertyId, incidentId, at, currentDay, quoteDay));
            } catch (SQLException failure) {
                if (IncidentRepair.isTargetFailure(failure)) return Optional.empty();
                throw failure;
            }
        });
    }

    /** Read-only: the type of an open, unprevented incident at this home, for the typed command. */
    public Optional<IncidentType> openIncidentType(UUID propertyId, long incidentId) throws SQLException {
        Objects.requireNonNull(propertyId);
        if (incidentId <= 0) return Optional.empty();
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c, """
                SELECT incident_type FROM npc_incidents WHERE property_id=? AND incident_id=?
                AND resolved_at IS NULL AND prevented=FALSE
                """)) {
            id(ps, 1, propertyId);
            ps.setLong(2, incidentId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                try { return Optional.of(IncidentType.valueOf(rs.getString(1))); }
                catch (IllegalArgumentException | NullPointerException invalid) { return Optional.empty(); }
            }
        }
    }

    /** INTENT to CANCELLED, only for its own run and only before any item was taken. */
    public Stored cancelBeforeTake(UUID collectionId, UUID playerId, UUID runId, String reason) throws SQLException {
        Objects.requireNonNull(runId);
        code(reason);
        return locked(collectionId, playerId, (c, stored) -> {
            runBinding(stored, runId);
            return stored.state() == State.INTENT ? transition(c, stored, State.CANCELLED, reason) : stored;
        });
    }

    /** INTENT to AMBIGUOUS for its own run. Never retried; only the offline tool resolves it. */
    public Stored markAmbiguous(UUID collectionId, UUID playerId, UUID runId, Uncertainty reason) throws SQLException {
        Objects.requireNonNull(runId);
        Objects.requireNonNull(reason);
        return locked(collectionId, playerId, (c, stored) -> {
            runBinding(stored, runId);
            return stored.state() == State.INTENT ? transition(c, stored, State.AMBIGUOUS, reason.name()) : stored;
        });
    }

    /**
     * Records the take evidence (an exact replay is a no-op, a conflict throws) and settles in one transaction:
     * not confirmed is AMBIGUOUS; confirmed with the same owner and target applies the repair; confirmed with a
     * changed owner or target rolls the repair attempt back and issues exact REPAIR_RETURN custody.
     */
    public Settlement settle(UUID collectionId, UUID playerId, UUID runId, ItemCollectionEvidence evidence,
                             boolean ownershipStillValid, Instant at, LocalDate completionDay) throws SQLException {
        Objects.requireNonNull(runId);
        Objects.requireNonNull(evidence);
        Objects.requireNonNull(at);
        Objects.requireNonNull(completionDay);
        return lockedSettle(collectionId, playerId, (c, stored) -> {
            runBinding(stored, runId);
            ItemRepairQuote q = stored.quote();
            if (!evidence.playerId().equals(q.playerId()) || !evidence.collectionId().equals(q.collectionId()))
                throw new SQLException("Item evidence does not bind this hand-in");
            Optional<ItemCollectionEvidence> old = evidence(c, collectionId, true);
            if (old.isPresent()) {
                if (!old.get().equals(evidence)) throw new SQLException("Conflicting item evidence");
                return settlement(c, stored);
            }
            if (stored.state() != State.INTENT && stored.state() != State.AMBIGUOUS)
                throw new SQLException("Item evidence without an open hand-in");
            insertEvidence(c, evidence);
            // Late evidence never releases a hand-in that recovery already held.
            if (stored.state() == State.AMBIGUOUS) return settlement(c, stored);
            if (!evidence.confirms(q.playerId(), q.beforeUnits(), q.quantities()))
                return settlement(c, transition(c, stored, State.AMBIGUOUS, Uncertainty.EVIDENCE_UNCERTAIN.name()));
            return collected(c, stored, ownershipStillValid, at, completionDay);
        });
    }

    /** Bounded startup barrier: another run's INTENT becomes AMBIGUOUS INTERRUPTED_RUN. Call until zero. */
    public int recoverOtherRuns(UUID currentRun, int limit) throws SQLException {
        Objects.requireNonNull(currentRun);
        if (limit < 0) throw new IllegalArgumentException("Negative sweep limit");
        var keys = new ArrayList<Key>();
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c, """
                SELECT collection_id,player_id FROM npc_item_repair_collections WHERE state='INTENT' AND run_id<>?
                ORDER BY collection_id LIMIT ?
                """)) {
            id(ps, 1, currentRun);
            ps.setInt(2, Math.min(limit, MAX_SWEEP));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) keys.add(new Key(uuid(rs, "collection_id"), uuid(rs, "player_id")));
            }
        }
        int changed = 0;
        for (Key key : keys) changed += locked(key.collectionId(), key.playerId(), (c, stored) -> {
            if (stored.state() != State.INTENT || currentRun.equals(stored.runId())) return stored;
            return transition(c, stored, State.AMBIGUOUS, Uncertainty.INTERRUPTED_RUN.name());
        }).state() == State.AMBIGUOUS ? 1 : 0;
        return changed;
    }

    /**
     * Offline tool only, server stopped. A held (or crashed INTENT) hand-in is closed by a staff decision read from
     * the stopped server's player file. VERIFIED_NOT_COLLECTED closes with nothing to return. VERIFIED_COLLECTED
     * applies the repair, or returns the items if the home changed. A different second decision throws.
     */
    public Settlement reconcile(UUID collectionId, UUID playerId, OperatorResolution resolution, Instant at,
                                LocalDate completionDay) throws SQLException {
        Objects.requireNonNull(resolution);
        Objects.requireNonNull(at);
        Objects.requireNonNull(completionDay);
        return lockedSettle(collectionId, playerId, (c, stored) -> {
            Optional<OperatorResolution> old = resolution(c, collectionId);
            if (old.isPresent()) {
                if (!old.get().equals(resolution)) throw new SQLException("Conflicting item repair resolution");
                return settlement(c, stored);
            }
            if (stored.state() != State.AMBIGUOUS && stored.state() != State.INTENT)
                throw new SQLException("Item repair resolution requires a held hand-in");
            try (PreparedStatement ps = prepare(c, """
                    INSERT INTO npc_item_repair_resolutions(collection_id,resolution_id,operator_ref,decision,
                    evidence_bytes,evidence_sha256,original_outcome) VALUES(?,?,?,?,?,?,?)
                    """)) {
                id(ps, 1, collectionId);
                id(ps, 2, resolution.resolutionId());
                ps.setString(3, resolution.operatorRef());
                ps.setString(4, resolution.decision().name());
                ps.setBytes(5, resolution.evidence().bytes());
                ps.setString(6, resolution.evidence().sha256());
                ps.setString(7, stored.outcome() == null ? Uncertainty.INTERRUPTED_RUN.name() : stored.outcome());
                one(ps.executeUpdate());
            }
            if (resolution.decision() == Decision.VERIFIED_NOT_COLLECTED)
                return settlement(c, transition(c, stored, State.RECONCILED_NOT_COLLECTED, resolution.decision().name()));
            return collected(c, stored, true, at, completionDay);
        });
    }

    /**
     * Read-only, for the offline tool: the counts the plugin recorded when it took this hand-in's items, if it got that far.
     * The tool trusts a saved file only when these agree with it.
     */
    public Optional<ItemCollectionEvidence> takeEvidence(UUID collectionId) throws SQLException {
        Objects.requireNonNull(collectionId);
        try (Connection c = database.getConnection()) { return evidence(c, collectionId, false); }
    }

    public Optional<Stored> find(UUID collectionId, UUID playerId) throws SQLException {
        Objects.requireNonNull(collectionId);
        Objects.requireNonNull(playerId);
        try (Connection c = database.getConnection()) {
            return read(c, collectionId, false).filter(stored -> stored.quote().playerId().equals(playerId));
        }
    }

    /** RESERVED returns waiting for the player, in hand-in and stack order (at most one set of 4). */
    public List<ItemCustody> pendingReturns(UUID playerId) throws SQLException {
        Objects.requireNonNull(playerId);
        var out = new ArrayList<ItemCustody>();
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c, """
                SELECT i.* FROM npc_item_custody i JOIN npc_item_repair_returns r ON r.request_nonce=i.request_nonce
                WHERE i.player_id=? AND i.source='REPAIR_RETURN' AND i.state='RESERVED'
                ORDER BY r.collection_id,r.stack_no LIMIT 9
                """)) {
            id(ps, 1, playerId);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(custody(rs)); }
        }
        if (out.size() > 8) throw new SQLException("Repair returns exceed their bound");
        return List.copyOf(out);
    }

    /**
     * Players with RESERVED returns they can collect now, for the desk line after a restart (bounded). A player with
     * any held delivery is left out: no delivery starts for them until staff settle it.
     */
    public List<UUID> playersWithPendingReturns(int limit) throws SQLException {
        var out = new ArrayList<UUID>();
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c, """
                SELECT DISTINCT i.player_id FROM npc_item_custody i JOIN npc_item_repair_returns r ON r.request_nonce=i.request_nonce
                WHERE i.source='REPAIR_RETURN' AND i.state='RESERVED' AND NOT EXISTS (
                  SELECT 1 FROM npc_item_custody h WHERE h.player_id=i.player_id AND h.state='AMBIGUOUS') LIMIT ?
                """)) {
            ps.setInt(1, Math.clamp(limit, 0, 1024));
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(uuid(rs, "player_id")); }
        }
        return List.copyOf(out);
    }

    /** Open, held and return-pending hand-ins for staff (read-only, bounded). */
    public List<Summary> listOpen(int limit) throws SQLException {
        var out = new ArrayList<Summary>();
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c, """
                SELECT k.collection_id,k.player_id,k.incident_id,k.state,k.outcome_code,
                  (SELECT COUNT(*) FROM npc_item_repair_returns r JOIN npc_item_custody i ON i.request_nonce=r.request_nonce
                   WHERE r.collection_id=k.collection_id AND i.state IN ('RESERVED','DELIVERY_INTENT','AMBIGUOUS')) AS undelivered,
                  (SELECT r.request_nonce FROM npc_item_repair_returns r JOIN npc_item_custody i ON i.request_nonce=r.request_nonce
                   WHERE r.collection_id=k.collection_id AND i.state IN ('DELIVERY_INTENT','AMBIGUOUS') ORDER BY r.stack_no LIMIT 1) AS held_nonce,
                  (SELECT i.state FROM npc_item_repair_returns r JOIN npc_item_custody i ON i.request_nonce=r.request_nonce
                   WHERE r.collection_id=k.collection_id AND i.state IN ('DELIVERY_INTENT','AMBIGUOUS') ORDER BY r.stack_no LIMIT 1) AS held_state
                FROM npc_item_repair_collections k
                WHERE k.state IN ('INTENT','AMBIGUOUS') OR (k.state='RETURN_ISSUED' AND EXISTS (
                  SELECT 1 FROM npc_item_repair_returns r JOIN npc_item_custody i ON i.request_nonce=r.request_nonce
                  WHERE r.collection_id=k.collection_id AND i.state IN ('RESERVED','DELIVERY_INTENT','AMBIGUOUS')))
                ORDER BY k.created_at,k.collection_id LIMIT ?
                """)) {
            ps.setInt(1, Math.clamp(limit, 0, MAX_SWEEP));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new Summary(uuid(rs, "collection_id"), uuid(rs, "player_id"), rs.getLong("incident_id"),
                        State.valueOf(rs.getString("state")), rs.getString("outcome_code"), rs.getInt("undelivered"),
                        uuid(rs, "held_nonce"), rs.getString("held_state")));
            }
        }
        return List.copyOf(out);
    }

    /**
     * Any hand-in that still needs a runtime: running, held, or with items not yet given back. A hand-in whose returns
     * were all delivered or settled needs nothing, so later starts with the feature off build nothing for it.
     */
    public boolean hasOpenWork() throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c, """
                SELECT 1 FROM npc_item_repair_collections k WHERE k.state IN ('INTENT','AMBIGUOUS') OR (k.state='RETURN_ISSUED' AND EXISTS (
                  SELECT 1 FROM npc_item_repair_returns r JOIN npc_item_custody i ON i.request_nonce=r.request_nonce
                  WHERE r.collection_id=k.collection_id AND i.state IN ('RESERVED','DELIVERY_INTENT','AMBIGUOUS'))) LIMIT 1
                """); ResultSet rs = ps.executeQuery()) {
            return rs.next();
        }
    }

    /**
     * The reference of a held item delivery for this player (any source), if one exists. While it is held no delivery
     * starts for the player, so returns cannot be collected until staff settle it.
     */
    public Optional<UUID> heldDelivery(UUID playerId) throws SQLException {
        Objects.requireNonNull(playerId);
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c, """
                SELECT request_nonce FROM npc_item_custody WHERE player_id=? AND state='AMBIGUOUS' ORDER BY request_nonce LIMIT 1
                """)) {
            id(ps, 1, playerId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(uuid(rs, "request_nonce")) : Optional.empty(); }
        }
    }

    // ---- held returns (offline tool) ------------------------------------------------------------------

    /** Which hand-in stack a REPAIR_RETURN custody row gives back. */
    public record ReturnBinding(UUID collectionId, int stackNo) {
        public ReturnBinding { Objects.requireNonNull(collectionId); }
    }

    /** state is the held row's new custody state; reissued is the same stack issued again, or null. */
    public record ReturnResolution(ItemCustodyRepository.State state, ItemCustody reissued) {
        public ReturnResolution { Objects.requireNonNull(state); }
    }

    /** Read-only: the hand-in stack this custody row currently gives back, if it is a return at all. */
    public Optional<ReturnBinding> returnBinding(UUID requestNonce) throws SQLException {
        Objects.requireNonNull(requestNonce);
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c,
                "SELECT collection_id,stack_no FROM npc_item_repair_returns WHERE request_nonce=?")) {
            id(ps, 1, requestNonce);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(new ReturnBinding(uuid(rs, "collection_id"), rs.getInt("stack_no"))) : Optional.empty();
            }
        }
    }

    /**
     * Offline tool only, server stopped. Settles one held REPAIR_RETURN delivery (AMBIGUOUS, or DELIVERY_INTENT left by
     * a run that died) with a staff decision, in one transaction. VERIFIED_DELIVERED closes it. VERIFIED_NOT_DELIVERED
     * closes it and issues the same stack again as a fresh RESERVED return that the hand-in points at, so the items are
     * never lost. offer_id names the held row and is unique, so the reissue is written once and a replay issues nothing.
     * RETURNED_BY_STAFF, for a PAYLOAD_UNREADABLE return that staff gave back by hand, closes as DELIVERED and issues
     * nothing, because the same unreadable bytes cannot help.
     */
    public ReturnResolution resolveReturn(ItemCustody expected, UUID runId, ItemCustodyRepository.OperatorResolution resolution,
                                          Instant at) throws SQLException {
        Objects.requireNonNull(expected);
        Objects.requireNonNull(runId);
        Objects.requireNonNull(resolution);
        Objects.requireNonNull(at);
        if (expected.source() != ItemCustody.Source.REPAIR_RETURN) throw new IllegalArgumentException("Only item repair returns");
        return transaction(c -> {
            requirePlayer(c, expected.playerId());
            var done = ItemCustodyRepository.reconcileHeldReturn(c, expected, runId, resolution);
            if (!done.fresh()) return new ReturnResolution(done.receipt().state(), reissueOf(c, expected).orElse(null));
            ReturnBinding binding;
            try (PreparedStatement ps = prepare(c,
                    "SELECT collection_id,stack_no FROM npc_item_repair_returns WHERE request_nonce=? FOR UPDATE")) {
                id(ps, 1, expected.requestNonce());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new SQLException("This delivery is not an item repair return");
                    binding = new ReturnBinding(uuid(rs, "collection_id"), rs.getInt("stack_no"));
                }
            }
            if (done.receipt().state() == ItemCustodyRepository.State.DELIVERED)
                return new ReturnResolution(ItemCustodyRepository.State.DELIVERED, null);
            Instant reserved = Instant.ofEpochMilli(at.toEpochMilli());
            var again = new ItemCustody(reissueNonce(resolution.resolutionId()), expected.playerId(), expected.requestNonce(),
                    ItemCustody.Source.REPAIR_RETURN, expected.quantity(), expected.payload(), reserved,
                    reserved.plusSeconds(ItemCustody.MAX_RESERVATION_SECONDS));
            ItemCustodyRepository.reserveReturn(c, again);
            try (PreparedStatement ps = prepare(c, """
                    UPDATE npc_item_repair_returns SET request_nonce=? WHERE request_nonce=? AND collection_id=? AND stack_no=?
                    """)) {
                id(ps, 1, again.requestNonce());
                id(ps, 2, expected.requestNonce());
                id(ps, 3, binding.collectionId());
                ps.setInt(4, binding.stackNo());
                one(ps.executeUpdate());
            }
            return new ReturnResolution(ItemCustodyRepository.State.RECONCILED_NOT_DELIVERED, again);
        });
    }

    /** The reissue nonce is fixed by the staff decision, so the same decision can never mint two stacks. */
    static UUID reissueNonce(UUID resolutionId) {
        return UUID.nameUUIDFromBytes(("prosperitynpc:repair-return-reissue:" + resolutionId).getBytes(StandardCharsets.UTF_8));
    }

    private static Optional<ItemCustody> reissueOf(Connection c, ItemCustody held) throws SQLException {
        try (PreparedStatement ps = prepare(c, """
                SELECT * FROM npc_item_custody WHERE offer_id=? AND player_id=? AND source='REPAIR_RETURN'
                """)) {
            id(ps, 1, held.requestNonce());
            id(ps, 2, held.playerId());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(custody(rs)) : Optional.empty(); }
        }
    }

    // ---- settle internals ------------------------------------------------------------------------------

    /** Items are proven taken. Apply when owner and target still match; otherwise return the exact items. */
    private Settlement collected(Connection c, Stored stored, boolean ownershipStillValid, Instant at,
                                 LocalDate completionDay) throws SQLException {
        ItemRepairQuote q = stored.quote();
        String reason = !ownershipStillValid || !ownerMatches(c, q) ? "OWNERSHIP_CHANGED" : null;
        if (reason == null) {
            Savepoint attempt = c.setSavepoint();
            try {
                IncidentRepair.applyItems(c, q, at, completionDay);
                c.releaseSavepoint(attempt);
                return settlement(c, transition(c, stored, State.APPLIED, "ITEMS_APPLIED"));
            } catch (SQLException failure) {
                if (!IncidentRepair.isTargetFailure(failure)) throw failure;
                // The repair attempt is undone; the proven take is answered with exact returns.
                c.rollback(attempt);
                c.clearWarnings();
                reason = failure.getMessage();
            }
        }
        Instant reserved = Instant.ofEpochMilli(at.toEpochMilli());
        for (int stack = 0; stack < q.returnPlan().size(); stack++) {
            var plan = q.returnPlan().get(stack);
            var custody = new ItemCustody(UUID.randomUUID(), q.playerId(), UUID.randomUUID(), ItemCustody.Source.REPAIR_RETURN,
                    plan.quantity(), plan.payload(), reserved, reserved.plusSeconds(ItemCustody.MAX_RESERVATION_SECONDS));
            ItemCustodyRepository.reserveReturn(c, custody);
            try (PreparedStatement ps = prepare(c,
                    "INSERT INTO npc_item_repair_returns(collection_id,stack_no,request_nonce) VALUES(?,?,?)")) {
                id(ps, 1, q.collectionId());
                ps.setInt(2, stack);
                id(ps, 3, custody.requestNonce());
                one(ps.executeUpdate());
            }
        }
        String outcome = reason.matches("[A-Z_]{1,32}") ? reason : "REPAIR_TARGET_CHANGED";
        return settlement(c, transition(c, stored, State.RETURN_ISSUED, outcome));
    }

    private Settlement settlement(Connection c, Stored stored) throws SQLException {
        var returns = new ArrayList<ItemCustody>();
        if (stored.state() == State.RETURN_ISSUED) {
            try (PreparedStatement ps = prepare(c, """
                    SELECT i.* FROM npc_item_repair_returns r JOIN npc_item_custody i ON i.request_nonce=r.request_nonce
                    WHERE r.collection_id=? ORDER BY r.stack_no
                    """)) {
                id(ps, 1, stored.quote().collectionId());
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) returns.add(custody(rs)); }
            }
        }
        return new Settlement(stored.state(), stored.outcome(), returns);
    }

    private static boolean ownerMatches(Connection c, ItemRepairQuote q) throws SQLException {
        try (PreparedStatement ps = prepare(c,
                "SELECT owner_uuid,ownership_epoch,enabled FROM npc_properties WHERE property_id=? FOR UPDATE")) {
            id(ps, 1, q.propertyId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                return q.playerId().equals(uuid(rs, "owner_uuid")) && rs.getInt("ownership_epoch") == q.ownershipEpoch()
                        && rs.getBoolean("enabled");
            }
        }
    }

    private static void insert(Connection c, ItemRepairQuote q, UUID runId) throws SQLException {
        try (PreparedStatement ps = prepare(c, """
                INSERT INTO npc_item_repair_collections(collection_id,player_id,property_id,incident_id,ownership_epoch,
                  incident_type,repair_snapshot_sha256,quote_day,cash_cents,value_ratio_bps,required_value_cents,
                  recipe_value_cents,price_version,quote_sha256,state,run_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,'INTENT',?)
                """)) {
            id(ps, 1, q.collectionId());
            id(ps, 2, q.playerId());
            id(ps, 3, q.propertyId());
            ps.setLong(4, q.repair().incidentId());
            ps.setInt(5, q.ownershipEpoch());
            ps.setString(6, q.repair().type().name());
            ps.setString(7, q.repair().snapshotSha256());
            ps.setString(8, q.quoteDay().toString());
            ps.setLong(9, q.cashCents());
            ps.setInt(10, q.ratioBps());
            ps.setLong(11, q.requiredValueCents());
            ps.setLong(12, q.recipeValueCents());
            ps.setString(13, q.priceVersion());
            ps.setString(14, q.quoteSha256());
            id(ps, 15, runId);
            one(ps.executeUpdate());
        }
        for (int i = 0; i < q.lines().size(); i++) {
            var line = q.lines().get(i);
            try (PreparedStatement ps = prepare(c, """
                    INSERT INTO npc_item_repair_lines(collection_id,line_no,material,quantity,unit_full_cents,
                      prototype_bytes,prototype_sha256,before_units) VALUES(?,?,?,?,?,?,?,?)
                    """)) {
                id(ps, 1, q.collectionId());
                ps.setInt(2, i);
                ps.setString(3, line.material());
                ps.setInt(4, line.quantity());
                ps.setLong(5, line.unitFullCents());
                ps.setBytes(6, line.prototype().bytes());
                ps.setString(7, line.prototype().sha256());
                ps.setInt(8, line.beforeUnits());
                one(ps.executeUpdate());
            }
        }
        for (int i = 0; i < q.returnPlan().size(); i++) {
            var stack = q.returnPlan().get(i);
            try (PreparedStatement ps = prepare(c, """
                    INSERT INTO npc_item_repair_return_plans(collection_id,stack_no,line_no,quantity,payload_bytes,payload_sha256)
                    VALUES(?,?,?,?,?,?)
                    """)) {
                id(ps, 1, q.collectionId());
                ps.setInt(2, i);
                ps.setInt(3, stack.lineNo());
                ps.setInt(4, stack.quantity());
                ps.setBytes(5, stack.payload().bytes());
                ps.setString(6, stack.payload().sha256());
                one(ps.executeUpdate());
            }
        }
    }

    private static void insertEvidence(Connection c, ItemCollectionEvidence evidence) throws SQLException {
        try (PreparedStatement ps = prepare(c, """
                INSERT INTO npc_item_repair_evidence(collection_id,player_id,before_inventory_sha256,after_inventory_sha256,
                  before_units_json,after_units_json,returned_normally,durable) VALUES(?,?,?,?,?,?,?,?)
                """)) {
            id(ps, 1, evidence.collectionId());
            id(ps, 2, evidence.playerId());
            ps.setString(3, evidence.beforeInventorySha256());
            ps.setString(4, evidence.afterInventorySha256());
            ps.setString(5, ItemCollectionEvidence.json(evidence.beforeUnits()));
            ps.setString(6, ItemCollectionEvidence.json(evidence.afterUnits()));
            ps.setBoolean(7, evidence.returnedNormally());
            ps.setBoolean(8, evidence.durable());
            one(ps.executeUpdate());
        }
    }

    private static Optional<ItemCollectionEvidence> evidence(Connection c, UUID collectionId, boolean lock) throws SQLException {
        try (PreparedStatement ps = prepare(c,
                "SELECT * FROM npc_item_repair_evidence WHERE collection_id=?" + (lock ? " FOR UPDATE" : ""))) {
            id(ps, 1, collectionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                try {
                    return Optional.of(new ItemCollectionEvidence(uuid(rs, "player_id"), collectionId,
                            rs.getString("before_inventory_sha256"), rs.getString("after_inventory_sha256"),
                            ItemCollectionEvidence.parseJson(rs.getString("before_units_json")),
                            ItemCollectionEvidence.parseJson(rs.getString("after_units_json")),
                            rs.getBoolean("returned_normally"), rs.getBoolean("durable")));
                } catch (IllegalArgumentException | NullPointerException invalid) {
                    throw new SQLException("Invalid stored item evidence", invalid);
                }
            }
        }
    }

    private static Optional<OperatorResolution> resolution(Connection c, UUID collectionId) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_item_repair_resolutions WHERE collection_id=? FOR UPDATE")) {
            id(ps, 1, collectionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                try {
                    CustodyBytes bytes = new CustodyBytes(rs.getBytes("evidence_bytes"));
                    if (!bytes.sha256().equals(rs.getString("evidence_sha256")))
                        throw new SQLException("Resolution evidence hash mismatch");
                    return Optional.of(new OperatorResolution(uuid(rs, "resolution_id"), rs.getString("operator_ref"),
                            Decision.valueOf(rs.getString("decision")), bytes));
                } catch (IllegalArgumentException | NullPointerException invalid) {
                    throw new SQLException("Invalid stored resolution", invalid);
                }
            }
        }
    }

    private static Stored transition(Connection c, Stored old, State state, String outcome) throws SQLException {
        try (PreparedStatement ps = prepare(c, """
                UPDATE npc_item_repair_collections SET state=?,outcome_code=?,updated_at=CURRENT_TIMESTAMP(3)
                WHERE collection_id=? AND state=? AND run_id=?
                """)) {
            ps.setString(1, state.name());
            ps.setString(2, outcome);
            id(ps, 3, old.quote().collectionId());
            ps.setString(4, old.state().name());
            id(ps, 5, old.runId());
            one(ps.executeUpdate());
        }
        return new Stored(old.quote(), state, old.runId(), outcome, old.createdAt(), old.updatedAt());
    }

    /** Reads the hand-in, its lines and return plan, and rebuilds the frozen quote; a changed hash is corrupt. */
    private static Optional<Stored> read(Connection c, UUID collectionId, boolean lock) throws SQLException {
        String suffix = lock ? " FOR UPDATE" : "";
        UUID player, property, run;
        long incident;
        int epoch, ratio;
        String type, snapshot, day, version, hash, state, outcome;
        long cash, required, value;
        Instant created, updated;
        try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_item_repair_collections WHERE collection_id=?" + suffix)) {
            id(ps, 1, collectionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                player = uuid(rs, "player_id");
                property = uuid(rs, "property_id");
                run = uuid(rs, "run_id");
                incident = rs.getLong("incident_id");
                epoch = rs.getInt("ownership_epoch");
                ratio = rs.getInt("value_ratio_bps");
                type = rs.getString("incident_type");
                snapshot = rs.getString("repair_snapshot_sha256");
                day = rs.getString("quote_day");
                version = rs.getString("price_version");
                hash = rs.getString("quote_sha256");
                state = rs.getString("state");
                outcome = rs.getString("outcome_code");
                cash = rs.getLong("cash_cents");
                required = rs.getLong("required_value_cents");
                value = rs.getLong("recipe_value_cents");
                created = time(rs, "created_at");
                updated = time(rs, "updated_at");
            }
        }
        var lines = new ArrayList<ItemRepairQuote.Line>();
        try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_item_repair_lines WHERE collection_id=? ORDER BY line_no" + suffix)) {
            id(ps, 1, collectionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (rs.getInt("line_no") != lines.size()) throw new SQLException("Item repair lines are not contiguous");
                    CustodyBytes prototype = new CustodyBytes(rs.getBytes("prototype_bytes"));
                    if (!prototype.sha256().equals(rs.getString("prototype_sha256"))) throw new SQLException("Prototype hash mismatch");
                    lines.add(new ItemRepairQuote.Line(rs.getString("material"), rs.getInt("quantity"), rs.getLong("unit_full_cents"),
                            prototype, rs.getInt("before_units")));
                }
            }
        }
        var plan = new ArrayList<ItemRepairQuote.ReturnStack>();
        try (PreparedStatement ps = prepare(c,
                "SELECT * FROM npc_item_repair_return_plans WHERE collection_id=? ORDER BY stack_no" + suffix)) {
            id(ps, 1, collectionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (rs.getInt("stack_no") != plan.size()) throw new SQLException("Return plan is not contiguous");
                    CustodyBytes payload = new CustodyBytes(rs.getBytes("payload_bytes"));
                    if (!payload.sha256().equals(rs.getString("payload_sha256"))) throw new SQLException("Return payload hash mismatch");
                    plan.add(new ItemRepairQuote.ReturnStack(rs.getInt("line_no"), rs.getInt("quantity"), payload));
                }
            }
        }
        try {
            var quote = new ItemRepairQuote(collectionId, property, player, epoch, LocalDate.parse(day),
                    new RepairQuote(incident, IncidentType.valueOf(type), snapshot), cash, ratio, required, value, version, lines, plan);
            if (!quote.quoteSha256().equals(hash)) throw new SQLException("Item repair quote hash mismatch");
            return Optional.of(new Stored(quote, State.valueOf(state), run, outcome, created, updated));
        } catch (IllegalArgumentException | NullPointerException | DateTimeException | ArithmeticException invalid) {
            throw new SQLException("Invalid stored item repair", invalid);
        }
    }

    @FunctionalInterface private interface Step<T> { T run(Connection c, Stored stored) throws SQLException; }

    /** Player guard, then the hand-in row. For steps that do not touch the property. */
    private Stored locked(UUID collectionId, UUID playerId, Step<Stored> step) throws SQLException {
        Objects.requireNonNull(collectionId);
        Objects.requireNonNull(playerId);
        return transaction(c -> {
            requirePlayer(c, playerId);
            Stored stored = read(c, collectionId, true).orElseThrow(() -> new SQLException("Unknown item repair"));
            if (!stored.quote().playerId().equals(playerId)) throw new SQLException("Item repair player conflicts");
            return step.run(c, stored);
        });
    }

    /** Player guard, then the property (the cash repair's first lock), then the hand-in row. */
    private Settlement lockedSettle(UUID collectionId, UUID playerId, Step<Settlement> step) throws SQLException {
        Objects.requireNonNull(collectionId);
        Objects.requireNonNull(playerId);
        return transaction(c -> {
            requirePlayer(c, playerId);
            Stored peek = read(c, collectionId, false).orElseThrow(() -> new SQLException("Unknown item repair"));
            if (!peek.quote().playerId().equals(playerId)) throw new SQLException("Item repair player conflicts");
            try (PreparedStatement ps = prepare(c, "SELECT property_id FROM npc_properties WHERE property_id=? FOR UPDATE")) {
                id(ps, 1, peek.quote().propertyId());
                try (ResultSet rs = ps.executeQuery()) { if (!rs.next()) throw new SQLException("Item repair property missing"); }
            }
            Stored stored = read(c, collectionId, true).orElseThrow(() -> new SQLException("Item repair vanished"));
            if (!stored.quote().equals(peek.quote())) throw new SQLException("Item repair changed while locking");
            return step.run(c, stored);
        });
    }

    private static void runBinding(Stored stored, UUID runId) throws SQLException {
        if (!runId.equals(stored.runId())) throw new SQLException("Item repair run conflicts");
    }

    private static String code(String value) {
        if (value == null || !value.matches("[A-Z_]{1,32}")) throw new IllegalArgumentException("Invalid outcome code");
        return value;
    }

    private static ItemCustody custody(ResultSet rs) throws SQLException {
        try {
            CustodyBytes bytes = new CustodyBytes(rs.getBytes("payload_bytes"));
            if (!bytes.sha256().equals(rs.getString("payload_sha256"))) throw new SQLException("Return payload hash mismatch");
            return new ItemCustody(uuid(rs, "request_nonce"), uuid(rs, "player_id"), uuid(rs, "offer_id"),
                    ItemCustody.Source.valueOf(rs.getString("source")), rs.getInt("quantity"), bytes,
                    Instant.ofEpochMilli(rs.getLong("reserved_at_millis")), Instant.ofEpochMilli(rs.getLong("expires_at_millis")));
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new SQLException("Invalid stored return custody", invalid);
        }
    }

    private static boolean exists(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement ps = prepare(c, sql)) {
            for (int i = 0; i < values.length; i++) {
                if (values[i] instanceof UUID id) id(ps, i + 1, id);
                else ps.setObject(i + 1, values[i]);
            }
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
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
    private static void requirePlayer(Connection c, UUID playerId) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT player_id FROM npc_item_custody_players WHERE player_id=? FOR UPDATE")) {
            id(ps, 1, playerId);
            try (ResultSet rs = ps.executeQuery()) { if (!rs.next()) throw new SQLException("Item custody player guard is missing"); }
        }
    }
    private static Instant time(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
    private static UUID uuid(ResultSet rs, String name) throws SQLException {
        byte[] bytes = rs.getBytes(name);
        return bytes == null ? null : Uuids.fromBytes(bytes);
    }
    private static void id(PreparedStatement ps, int index, UUID value) throws SQLException { ps.setBytes(index, Uuids.toBytes(value)); }
    private static void one(int count) throws SQLException {
        if (count != 1) throw new SQLException("Item repair write did not affect exactly one row");
    }
    private static PreparedStatement prepare(Connection c, String sql) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        try {
            ps.setQueryTimeout(5);
            return ps;
        } catch (SQLException failure) {
            ps.close();
            throw failure;
        }
    }
    @FunctionalInterface private interface Work<T> { T run(Connection c) throws SQLException; }
    private <T> T transaction(Work<T> work) throws SQLException {
        try (Connection c = database.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                if (c.getWarnings() != null) throw new SQLException("Item repair SQL warning", c.getWarnings());
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
}

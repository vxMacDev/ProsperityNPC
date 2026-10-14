package com.prosperitysmp.npc.town;

import com.prosperitysmp.npc.commerce.ItemCollectionEvidence;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.resident.RelationshipMemory;
import com.prosperitysmp.npc.resident.ResidentRepository;
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
 * Durable contracts and partnership hand-overs. SQL only: no Bukkit, Vault, transfer journal, cap or economy event.
 * Items flow one way (player to destroyed) and the only reward is an OBLIGATION_KEPT memory, still capped at four
 * points per resident per day by {@link RelationshipMemory}.
 *
 * <p>Hand-over state machine: ACTIVE, then HANDOVER_INTENT (written before any item is taken), then COMPLETED only
 * when the take evidence shows exactly the asked quantity left and the saved player file was proven. Anything else,
 * or a restart that finds HANDOVER_INTENT, is AMBIGUOUS: held for staff, never retried, and it blocks the player's
 * next contract until /npctown contract-resolve settles it. A partnership start or renewal is a PARTNERSHIP row
 * (day_slot 0) that goes straight to HANDOVER_INTENT and runs the same machine.
 *
 * <p>Lock order: item custody player guard (only where a hand-over starts, so a delivery or an item repair can never
 * overlap it), contract player guard, contract row, sorted resident profiles, partnership rows, resident memory.
 * Never takes property, economy or commerce locks. All calls are bounded synchronous JDBC work for a database worker.
 */
public final class TownContractRepository {
    public static final int MAX_LIST = 100;
    public static final int KEPT_WINDOW_DAYS = 30;
    public enum Code {
        ACCEPTED, ALREADY_ACTIVE, ACTIVE_CONFLICT, DAILY_LIMIT, RELATIONSHIP_TOO_LOW, UNKNOWN_GIVER, UNKNOWN_RECIPIENT,
        CANCELLED, ALREADY_CANCELLED, EXPIRED, NOT_ACTIVE, NOT_FOUND, WRONG_RECIPIENT, TIME_REGRESSION,
        /** HANDOVER_INTENT written: the caller may now take the items. */
        HANDOVER_STARTED,
        /** This player's hand-over is already running. */
        BUSY,
        /** A delivery or item repair owns this player's inventory right now. */
        ITEMS_BUSY,
        /** This hand-over is held for staff review. */
        HANDOVER_HELD,
        /** An earlier hand-over is held for staff review, which blocks new contracts. */
        HELD_FOR_REVIEW,
        COMPLETED, ALREADY_COMPLETED,
        PARTNER_STARTED, PARTNER_RENEWED, PARTNER_DAILY_LIMIT, PARTNER_STAGE, PARTNER_CONTRACTS, PARTNER_HOME,
        PARTNER_SEATS_FULL, PARTNER_HAS_ONE, PARTNER_NOT_RENEWABLE,
        RESOLVED_CONSUMED, RESOLVED_NOT_CONSUMED, NOT_HELD
    }
    /** Why a partnership hand-over recorded as consumed started or renewed no partnership. */
    public enum NoPartnership {
        /** Partnerships are off, or the resident has no offer with a perk. */
        NO_OFFER,
        /** The offer now asks for another material, or more of it, than this hand-over took. */
        OFFER_CHANGED,
        /** The row has no kept perks or term (written before V37). */
        NO_KEPT_TERMS,
        /** The lower of the kept and current perks is zero on every perk. */
        NO_PERK_LEFT,
        /** The owner or seat check failed. */
        CHECK_FAILED,
        /** A start, but the owner already has a partnership. */
        HAS_PARTNERSHIP,
        /** A renewal, but the owner has no active partnership with this resident to renew. */
        NOT_RENEWABLE
    }
    /**
     * detail: contracts still needed for PARTNER_CONTRACTS, else 0. noPartnership: for a consumed partnership hand-over
     * that started or renewed nothing, the reason; otherwise null.
     */
    public record Result(Code code, Optional<TownContract> contract, Optional<Partnership> partnership, int detail,
                         NoPartnership noPartnership) {
        public Result {
            Objects.requireNonNull(code); Objects.requireNonNull(contract); Objects.requireNonNull(partnership);
        }
        public Result(Code code, Optional<TownContract> contract, Optional<Partnership> partnership, int detail) {
            this(code, contract, partnership, detail, null);
        }
    }
    /**
     * What a partnership start or renewal needs, from config at the time of the call, with the offer's material and
     * quantity. At begin the perks and term are kept with the hand-in row; at settlement these are the current terms.
     */
    public record PartnerTerms(PartnerPerks perks, int termDays, int maxSeats, int renewWindowDays,
                               RelationshipMemory.Stage minimumStage, int minContracts, String material, int quantity) {
        public PartnerTerms {
            Objects.requireNonNull(perks); Objects.requireNonNull(minimumStage);
            if (termDays < 1 || termDays > 30 || maxSeats < 1 || maxSeats > 16 || renewWindowDays < 1
                    || renewWindowDays > termDays || minContracts < 0 || minContracts > 10
                    || material == null || !material.matches("[A-Z][A-Z0-9_]{0,63}")
                    || quantity < 1 || quantity > ContractDefinition.MAX_QUANTITY)
                throw new IllegalArgumentException("Invalid partnership terms");
        }
    }

    /**
     * True when the current offer asks for something other than what this hand-in collected: another material, or
     * more of it. Settling then would give perks for a cheaper hand-in than the offer now asks, so it never grants.
     */
    public static boolean offerChanged(ContractDefinition handIn, PartnerTerms current) {
        return !current.material().equals(handIn.material()) || current.quantity() > handIn.quantity();
    }
    public enum Decision { VERIFIED_CONSUMED, VERIFIED_NOT_CONSUMED }
    /** One held hand-over for staff lists and alerts. */
    public record Held(UUID contractId, UUID playerId, ContractDefinition.Kind kind, String definitionId, UUID residentId) { }

    private static final String COLUMNS = "contract_id, player_id, kind, definition_id, title, description, giver_id, "
            + "recipient_id, material, quantity, minimum_stage, duration_minutes, accepted_at_millis, accepted_economy_day, "
            + "day_slot, expires_at_millis, status, handover_run_id, closed_at_millis, completed_economy_day";
    private final Database database;

    public TownContractRepository(Database database) { this.database = Objects.requireNonNull(database); }

    // ---- contracts --------------------------------------------------------------------------------------------

    /**
     * Takes a trusted authored contract for a player, using one of today's perDay slots. Cancelled and expired
     * contracts keep their slot. A held hand-over blocks every new contract until staff settle it.
     */
    public Result accept(ContractDefinition definition, UUID player, Instant at, LocalDate economyDay, int perDay)
            throws SQLException {
        Objects.requireNonNull(definition); Objects.requireNonNull(player); Objects.requireNonNull(economyDay);
        if (definition.kind() == ContractDefinition.Kind.PARTNERSHIP) throw new IllegalArgumentException("Partnerships start through beginPartnership");
        if (perDay < 1 || perDay > 3) throw new IllegalArgumentException("Contracts per day must be 1..3");
        Instant now = TownContract.normalize(at);
        ensureGuard(player);
        return transaction(c -> {
            lockGuard(c, player);
            Optional<TownContract> open = openLocked(c, player, now);
            if (open.isPresent()) {
                TownContract existing = open.get();
                return switch (existing.status()) {
                    case AMBIGUOUS -> result(Code.HELD_FOR_REVIEW, existing);
                    case HANDOVER_INTENT -> result(Code.BUSY, existing);
                    default -> result(existing.definition().id().equals(definition.id()) ? Code.ALREADY_ACTIVE : Code.ACTIVE_CONFLICT, existing);
                };
            }
            if (regressed(c, player, now, economyDay)) return result(Code.TIME_REGRESSION);
            int used = slotsUsed(c, player, economyDay);
            if (used >= perDay) return result(Code.DAILY_LIMIT);
            Code missing = lockResidents(c, definition);
            if (missing != null) return result(missing);
            if (stage(c, definition.giverId(), player).ordinal() < definition.minimumStage().ordinal())
                return result(Code.RELATIONSHIP_TOO_LOW);
            TownContract contract = new TownContract(UUID.randomUUID(), player, definition, now, economyDay, used + 1,
                    now.plusSeconds(definition.durationMinutes() * 60L), TownContract.Status.ACTIVE, null, null, null);
            insert(c, contract);
            return result(Code.ACCEPTED, contract);
        });
    }

    /** Lazily expires; returns the player's open row (ACTIVE, HANDOVER_INTENT or AMBIGUOUS), if any. */
    public Optional<TownContract> current(UUID player, Instant at) throws SQLException {
        Objects.requireNonNull(player);
        Instant now = TownContract.normalize(at);
        return transaction(c -> guardExists(c, player) ? openLocked(c, player, now) : Optional.empty());
    }

    /** Cancelling writes no memory, positive or negative, and keeps today's slot used. */
    public Result cancel(UUID contractId, UUID player, Instant at) throws SQLException {
        Objects.requireNonNull(contractId); Objects.requireNonNull(player);
        Instant now = TownContract.normalize(at);
        return transaction(c -> {
            if (!guardExists(c, player)) return result(Code.NOT_FOUND);
            Optional<TownContract> found = findLocked(c, contractId, player);
            if (found.isEmpty()) return result(Code.NOT_FOUND);
            TownContract contract = found.get();
            if (contract.definition().kind() == ContractDefinition.Kind.PARTNERSHIP) return result(Code.NOT_ACTIVE, contract);
            return switch (contract.status()) {
                case CANCELLED -> result(Code.ALREADY_CANCELLED, contract);
                case EXPIRED -> result(Code.EXPIRED, contract);
                case HANDOVER_INTENT -> result(Code.BUSY, contract);
                case AMBIGUOUS -> result(Code.HANDOVER_HELD, contract);
                case ACTIVE -> !now.isBefore(contract.expiresAt())
                        ? result(Code.EXPIRED, close(c, contract, TownContract.Status.EXPIRED, null, contract.expiresAt(), null))
                        : now.isBefore(contract.acceptedAt()) ? result(Code.TIME_REGRESSION, contract)
                        : result(Code.CANCELLED, close(c, contract, TownContract.Status.CANCELLED, null, now, null));
                default -> result(Code.NOT_ACTIVE, contract);
            };
        });
    }

    /**
     * INTERNAL: the main-thread adapter must have checked the live session, the nearby authoritative avatar of
     * actualResident, survival mode, the world and the inventory BEFORE dispatching this. Writes HANDOVER_INTENT with
     * the run ID; only then may the caller take items. Refuses while a delivery or an item repair is mid-flight or
     * held for this player.
     */
    public Result beginHandover(UUID contractId, UUID player, UUID actualResident, UUID runId, Instant at) throws SQLException {
        Objects.requireNonNull(contractId); Objects.requireNonNull(player); Objects.requireNonNull(actualResident);
        Objects.requireNonNull(runId);
        Instant now = TownContract.normalize(at);
        ensureCustodyGuard(player);
        ensureGuard(player);
        return transaction(c -> {
            lockCustodyGuard(c, player);
            lockGuard(c, player);
            Optional<TownContract> found = findLocked(c, contractId, player);
            if (found.isEmpty()) return result(Code.NOT_FOUND);
            TownContract contract = found.get();
            switch (contract.status()) {
                case COMPLETED, RESOLVED_CONSUMED -> { return result(Code.ALREADY_COMPLETED, contract); }
                case HANDOVER_INTENT -> { return result(Code.BUSY, contract); }
                case AMBIGUOUS -> { return result(Code.HANDOVER_HELD, contract); }
                case EXPIRED -> { return result(Code.EXPIRED, contract); }
                case CANCELLED, RESOLVED_NOT_CONSUMED -> { return result(Code.NOT_ACTIVE, contract); }
                case ACTIVE -> { }
            }
            if (contract.definition().kind() == ContractDefinition.Kind.PARTNERSHIP) return result(Code.NOT_ACTIVE, contract);
            if (!now.isBefore(contract.expiresAt()))
                return result(Code.EXPIRED, close(c, contract, TownContract.Status.EXPIRED, null, contract.expiresAt(), null));
            if (now.isBefore(contract.acceptedAt())) return result(Code.TIME_REGRESSION, contract);
            if (!contract.definition().recipientId().equals(actualResident)) return result(Code.WRONG_RECIPIENT, contract);
            if (itemsBusy(c, player)) return result(Code.ITEMS_BUSY, contract);
            Code missing = lockResidents(c, contract.definition());
            if (missing != null) return result(missing, contract);
            return result(Code.HANDOVER_STARTED, transition(c, contract, TownContract.Status.HANDOVER_INTENT, runId, null, null));
        });
    }

    /**
     * INTERNAL, same main-thread duty as beginHandover. Starts the hand-over that pays for a partnership start or
     * renewal: checks everything the partnership needs under the resident lock (so seats are counted exactly), then
     * writes a PARTNERSHIP row straight to HANDOVER_INTENT. Uses no contract slot; at most one per player per day.
     * An open contract of any kind refuses with ACTIVE_CONFLICT.
     */
    public Result beginPartnership(boolean start, UUID player, PartnerOffer offer, PartnerTerms terms, UUID runId,
                                   Instant at, LocalDate economyDay) throws SQLException {
        Objects.requireNonNull(player); Objects.requireNonNull(offer); Objects.requireNonNull(terms);
        Objects.requireNonNull(runId); Objects.requireNonNull(economyDay);
        // Nothing is taken for an offer with no perk, or for terms that are not this offer's.
        if (!terms.material().equals(offer.material()) || terms.quantity() != offer.quantity() || !terms.perks().equals(offer.perks())
                || !terms.perks().any())
            throw new IllegalArgumentException("Partnership terms do not match the offer");
        Instant now = TownContract.normalize(at);
        UUID resident = offer.residentId();
        ContractDefinition definition = ContractDefinition.partnership(start, resident, offer.material(), offer.quantity(),
                terms.minimumStage());
        ensureCustodyGuard(player);
        ensureGuard(player);
        return transaction(c -> {
            lockCustodyGuard(c, player);
            lockGuard(c, player);
            Optional<TownContract> open = openLocked(c, player, now);
            if (open.isPresent()) {
                TownContract existing = open.get();
                return switch (existing.status()) {
                    case AMBIGUOUS -> result(Code.HELD_FOR_REVIEW, existing);
                    case HANDOVER_INTENT -> result(Code.BUSY, existing);
                    default -> result(Code.ACTIVE_CONFLICT, existing);
                };
            }
            if (regressed(c, player, now, economyDay)) return result(Code.TIME_REGRESSION);
            if (partnerDayUsed(c, player, economyDay)) return result(Code.PARTNER_DAILY_LIMIT);
            if (itemsBusy(c, player)) return result(Code.ITEMS_BUSY);
            if (lockResidents(c, definition) != null) return result(Code.UNKNOWN_GIVER);
            Optional<Partnership> current = PartnershipRepository.currentLocked(c, player, economyDay, now);
            Code refusal = partnerRefusal(c, start, player, resident, terms, current, economyDay, now, null);
            if (refusal != null) return new Result(refusal, Optional.empty(), current,
                    refusal == Code.PARTNER_CONTRACTS ? terms.minContracts() - keptLocked(c, resident, player, keptSince(economyDay)) : 0);
            TownContract contract = new TownContract(UUID.randomUUID(), player, definition, now, economyDay, 0,
                    now.plusSeconds(definition.durationMinutes() * 60L), TownContract.Status.HANDOVER_INTENT, runId, null, null);
            insert(c, contract);
            // The perks and term this hand-in was confirmed for, kept with it for settlement.
            try (PreparedStatement ps = prepare(c, """
                    UPDATE npc_town_contracts SET partner_fee_discount_pct=?, partner_rank_bonus_points=?, partner_term_days=?
                    WHERE contract_id=? AND kind='PARTNERSHIP' AND status='HANDOVER_INTENT'
                    """)) {
                ps.setInt(1, terms.perks().feeDiscountPct());
                ps.setInt(2, terms.perks().rankBonusPoints());
                ps.setInt(3, terms.termDays());
                ps.setBytes(4, Uuids.toBytes(contract.contractId()));
                one(ps.executeUpdate());
            }
            return new Result(Code.HANDOVER_STARTED, Optional.of(contract), current, 0);
        });
    }

    /**
     * Records the take evidence (an exact replay is a no-op, a different one throws) and settles in one transaction.
     * COMPLETED, with OBLIGATION_KEPT for the giver and recipient (and the partnership started or renewed), needs:
     * the same player, the before count recorded when the hand-over was asked, exactly quantity gone, a changed
     * inventory, a normal return and a proven save. Anything else is AMBIGUOUS. Late evidence never releases a
     * hand-over that recovery already held. A partnership check that fails at this point throws, so the caller
     * holds the hand-over for staff; it never completes without the partnership it paid for. terms are the current
     * ones: the partnership gets the lower of them and the perks and term kept at begin, and a current offer with
     * another material or a higher quantity throws (held for staff).
     */
    public Result finishHandover(UUID contractId, UUID player, UUID runId, int expectedBefore, ItemCollectionEvidence evidence,
                                 Instant at, LocalDate economyDay, PartnerTerms terms) throws SQLException {
        Objects.requireNonNull(contractId); Objects.requireNonNull(player); Objects.requireNonNull(runId);
        Objects.requireNonNull(evidence); Objects.requireNonNull(economyDay);
        Instant now = TownContract.normalize(at);
        return transaction(c -> {
            lockGuard(c, player);
            TownContract contract = findLocked(c, contractId, player).orElseThrow(() -> new SQLException("Unknown contract hand-over"));
            if (!runId.equals(contract.runId())) throw new SQLException("Contract hand-over run conflicts");
            if (!evidence.playerId().equals(player) || !evidence.collectionId().equals(runId) || evidence.beforeUnits().size() != 1)
                throw new SQLException("Hand-over evidence does not bind this contract");
            Optional<ItemCollectionEvidence> old = evidence(c, contractId);
            if (old.isPresent()) {
                if (!old.get().equals(evidence)) throw new SQLException("Conflicting hand-over evidence");
                return replay(contract);
            }
            if (contract.status() != TownContract.Status.HANDOVER_INTENT && contract.status() != TownContract.Status.AMBIGUOUS)
                throw new SQLException("Hand-over evidence without an open hand-over");
            insertEvidence(c, contract, runId, evidence);
            if (contract.status() == TownContract.Status.AMBIGUOUS) return result(Code.HANDOVER_HELD, contract);
            if (!confirms(evidence, player, expectedBefore, contract.definition().quantity()))
                return result(Code.HANDOVER_HELD, transition(c, contract, TownContract.Status.AMBIGUOUS, runId, null, null));
            return consumed(c, contract, TownContract.Status.COMPLETED, now, economyDay, terms, true);
        });
    }

    /** True only when exactly quantity units left a known before count, with a changed, normal and proven save. */
    public static boolean confirms(ItemCollectionEvidence evidence, UUID player, int expectedBefore, int quantity) {
        return evidence.confirms(player, List.of(expectedBefore), List.of(quantity));
    }

    /** HANDOVER_INTENT to AMBIGUOUS for its own run. Never retried. */
    public Optional<TownContract> markAmbiguous(UUID contractId, UUID player, UUID runId) throws SQLException {
        Objects.requireNonNull(contractId); Objects.requireNonNull(player); Objects.requireNonNull(runId);
        return transaction(c -> {
            lockGuard(c, player);
            Optional<TownContract> found = findLocked(c, contractId, player);
            if (found.isEmpty()) return Optional.empty();
            TownContract contract = found.get();
            if (contract.status() != TownContract.Status.HANDOVER_INTENT || !runId.equals(contract.runId())) return found;
            return Optional.of(transition(c, contract, TownContract.Status.AMBIGUOUS, runId, null, null));
        });
    }

    /**
     * Before any item was taken (the main thread decided not to take): a contract goes back to ACTIVE with its
     * deadline unchanged, and a partnership hand-over row is removed, so nothing is used up. Only for its own run
     * and only while no evidence exists.
     */
    public Optional<TownContract> cancelBeforeTake(UUID contractId, UUID player, UUID runId) throws SQLException {
        Objects.requireNonNull(contractId); Objects.requireNonNull(player); Objects.requireNonNull(runId);
        return transaction(c -> {
            lockGuard(c, player);
            Optional<TownContract> found = findLocked(c, contractId, player);
            if (found.isEmpty()) return Optional.empty();
            TownContract contract = found.get();
            if (contract.status() != TownContract.Status.HANDOVER_INTENT || !runId.equals(contract.runId())
                    || evidence(c, contractId).isPresent()) return found;
            if (contract.definition().kind() == ContractDefinition.Kind.PARTNERSHIP) {
                try (PreparedStatement ps = prepare(c, "DELETE FROM npc_town_contracts WHERE contract_id=? AND status='HANDOVER_INTENT'")) {
                    ps.setBytes(1, Uuids.toBytes(contractId));
                    one(ps.executeUpdate());
                }
                return Optional.empty();
            }
            return Optional.of(transition(c, contract, TownContract.Status.ACTIVE, null, null, null));
        });
    }

    /**
     * A begin call whose answer was lost (it threw after the database may have committed). Nothing was taken, so if
     * this run's HANDOVER_INTENT row exists it is undone exactly as {@link #cancelBeforeTake} does. No row: nothing.
     */
    public Optional<TownContract> cancelUnconfirmedBegin(UUID player, UUID runId) throws SQLException {
        Objects.requireNonNull(player); Objects.requireNonNull(runId);
        UUID contractId;
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c, """
                SELECT contract_id FROM npc_town_contracts WHERE player_id=? AND handover_run_id=? AND status='HANDOVER_INTENT'
                """)) {
            ps.setBytes(1, Uuids.toBytes(player));
            ps.setBytes(2, Uuids.toBytes(runId));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                contractId = Uuids.fromBytes(rs.getBytes(1));
            }
        }
        return cancelBeforeTake(contractId, player, runId);
    }

    /**
     * Startup barrier, before any encounter can start: every HANDOVER_INTENT left by a stop or crash becomes AMBIGUOUS.
     * Returns the held IDs (at most limit per call); call until empty.
     */
    public List<Held> recoverStaleIntents(int limit) throws SQLException {
        var keys = new ArrayList<UUID[]>();
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c, """
                SELECT contract_id, player_id FROM npc_town_contracts WHERE status='HANDOVER_INTENT' ORDER BY contract_id LIMIT ?
                """)) {
            ps.setInt(1, Math.clamp(limit, 1, MAX_LIST));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) keys.add(new UUID[] {Uuids.fromBytes(rs.getBytes(1)), Uuids.fromBytes(rs.getBytes(2))});
            }
        }
        var held = new ArrayList<Held>();
        for (UUID[] key : keys) {
            Optional<TownContract> changed = transaction(c -> {
                lockGuard(c, key[1]);
                Optional<TownContract> found = findLocked(c, key[0], key[1]);
                if (found.isEmpty() || found.get().status() != TownContract.Status.HANDOVER_INTENT) return Optional.empty();
                return Optional.of(transition(c, found.get(), TownContract.Status.AMBIGUOUS, found.get().runId(), null, null));
            });
            changed.ifPresent(contract -> held.add(held(contract)));
        }
        return List.copyOf(held);
    }

    /**
     * Staff decision on a held hand-over. consumed: RESOLVED_CONSUMED with the memory, and for a partnership
     * hand-over the partnership starts or renews when the seat, owner and home checks still pass and the offer still
     * asks for the same material and no more of it, at the lower of the kept and current terms (otherwise it is
     * recorded consumed with no partnership). not consumed: RESOLVED_NOT_CONSUMED with no memory. Neither returns items
     * or money. The same decision again is a no-op; a different one throws.
     */
    public Result resolve(UUID contractId, Decision decision, String operatorRef, String caseRef, Instant at,
                          LocalDate economyDay, PartnerTerms terms) throws SQLException {
        Objects.requireNonNull(contractId); Objects.requireNonNull(decision); Objects.requireNonNull(economyDay);
        requireRef(operatorRef); requireRef(caseRef);
        Instant now = TownContract.normalize(at);
        UUID player = owner(contractId).orElse(null);
        if (player == null) return result(Code.NOT_FOUND);
        return transaction(c -> {
            lockGuard(c, player);
            TownContract contract = findLocked(c, contractId, player).orElseThrow(() -> new SQLException("Contract vanished"));
            Optional<Decision> previous = resolution(c, contractId);
            if (previous.isPresent()) {
                if (previous.get() != decision) throw new SQLException("Conflicting hand-over resolution");
                return result(decision == Decision.VERIFIED_CONSUMED ? Code.RESOLVED_CONSUMED : Code.RESOLVED_NOT_CONSUMED, contract);
            }
            if (contract.status() != TownContract.Status.AMBIGUOUS) return result(Code.NOT_HELD, contract);
            Result outcome;
            if (decision == Decision.VERIFIED_NOT_CONSUMED) {
                outcome = result(Code.RESOLVED_NOT_CONSUMED,
                        close(c, contract, TownContract.Status.RESOLVED_NOT_CONSUMED, contract.runId(), now, null));
            } else {
                outcome = consumed(c, contract, TownContract.Status.RESOLVED_CONSUMED, now, economyDay, terms, false);
                outcome = new Result(Code.RESOLVED_CONSUMED, outcome.contract(), outcome.partnership(), 0, outcome.noPartnership());
            }
            try (PreparedStatement ps = prepare(c, """
                    INSERT INTO npc_contract_handover_resolutions (contract_id, resolution_id, operator_ref, case_ref,
                    decision, partnership_started) VALUES (?,?,?,?,?,?)
                    """)) {
                ps.setBytes(1, Uuids.toBytes(contractId));
                ps.setBytes(2, Uuids.toBytes(UUID.randomUUID()));
                ps.setString(3, operatorRef);
                ps.setString(4, caseRef);
                ps.setString(5, decision.name());
                ps.setBoolean(6, outcome.partnership().isPresent());
                one(ps.executeUpdate());
            }
            return outcome;
        });
    }

    // ---- reads ------------------------------------------------------------------------------------------------

    /** Contracts kept FOR this resident (as giver) since sinceDay, derived from rows, never stored as a score. */
    public int keptFor(UUID resident, UUID player, LocalDate sinceDay) throws SQLException {
        try (Connection c = database.getConnection()) { return keptLocked(c, resident, player, sinceDay); }
    }

    /** Contract slots used on this economy day. */
    public int slotsUsed(UUID player, LocalDate day) throws SQLException {
        try (Connection c = database.getConnection()) { return slotsUsed(c, player, day); }
    }

    /** Hand-overs held for staff, for health. */
    public int heldCount() throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c,
                "SELECT COUNT(*) FROM npc_town_contracts WHERE status='AMBIGUOUS'"); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** Held hand-overs, oldest first, at most 100. */
    public List<Held> listHeld(int max) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c, "SELECT " + COLUMNS
                + " FROM npc_town_contracts WHERE status='AMBIGUOUS' ORDER BY accepted_at_millis, contract_id LIMIT ?")) {
            ps.setInt(1, Math.clamp(max, 1, MAX_LIST));
            var out = new ArrayList<Held>();
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(held(read(rs))); }
            return List.copyOf(out);
        }
    }

    /** Owner-scoped lookup, no expiry write. */
    public Optional<TownContract> find(UUID contractId, UUID player) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c, "SELECT " + COLUMNS
                + " FROM npc_town_contracts WHERE contract_id=? AND player_id=?")) {
            ps.setBytes(1, Uuids.toBytes(contractId));
            ps.setBytes(2, Uuids.toBytes(player));
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(read(rs)) : Optional.empty(); }
        }
    }

    /** Staff lookup by ID alone, no lock and no expiry write. */
    public Optional<TownContract> peek(UUID contractId) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c, "SELECT " + COLUMNS
                + " FROM npc_town_contracts WHERE contract_id=?")) {
            ps.setBytes(1, Uuids.toBytes(contractId));
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(read(rs)) : Optional.empty(); }
        }
    }

    /** True when the player has used today's partnership hand-over. */
    public boolean partnerDayUsed(UUID player, LocalDate day) throws SQLException {
        try (Connection c = database.getConnection()) { return partnerDayUsed(c, player, day); }
    }

    /** Partnership seats used at a resident today: active partnerships plus starts still being handed over or held. */
    public int seatsUsed(UUID resident, LocalDate day) throws SQLException {
        try (Connection c = database.getConnection()) {
            int active;
            try (PreparedStatement ps = prepare(c, """
                    SELECT COUNT(*) FROM npc_partnerships WHERE resident_id=? AND status='ACTIVE' AND last_active_day>=?
                    """)) {
                ps.setBytes(1, Uuids.toBytes(resident));
                ps.setString(2, day.toString());
                try (ResultSet rs = ps.executeQuery()) { rs.next(); active = rs.getInt(1); }
            }
            return active + reservedSeats(c, resident, null, false);
        }
    }

    /** True when every enrolled, unpaused home of the player has advertising at level 3 (and at least one exists). */
    public boolean advertisingTop(UUID player) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c, """
                SELECT COUNT(*), COALESCE(SUM(CASE WHEN u.level=3 AND u.disabled_incident_id IS NULL THEN 1 ELSE 0 END),0)
                FROM npc_properties p LEFT JOIN npc_upgrades u ON u.property_id=p.property_id AND u.upgrade_type='ADVERTISING'
                WHERE p.owner_uuid=? AND p.enabled=TRUE
                """)) {
            ps.setBytes(1, Uuids.toBytes(player));
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1) > 0 && rs.getInt(2) == rs.getInt(1); }
        }
    }

    /** True when the player owns at least one enrolled home that is not paused. */
    public boolean ownsEnrolledHome(UUID player) throws SQLException {
        try (Connection c = database.getConnection()) { return ownsHome(c, player); }
    }

    // ---- internals --------------------------------------------------------------------------------------------

    /** The checks a partnership start or renewal needs. Null means it may go ahead. */
    private Code partnerRefusal(Connection c, boolean start, UUID player, UUID resident, PartnerTerms terms,
                                Optional<Partnership> current, LocalDate day, Instant now, UUID excludeContract) throws SQLException {
        if (stage(c, resident, player).ordinal() < terms.minimumStage().ordinal()) return Code.PARTNER_STAGE;
        if (start) {
            if (current.isPresent()) return Code.PARTNER_HAS_ONE;
            if (keptLocked(c, resident, player, keptSince(day)) < terms.minContracts()) return Code.PARTNER_CONTRACTS;
            if (!ownsHome(c, player)) return Code.PARTNER_HOME;
            int seats = PartnershipRepository.seatsLocked(c, resident, day, now) + reservedSeats(c, resident, excludeContract, true);
            if (seats >= terms.maxSeats()) return Code.PARTNER_SEATS_FULL;
            return null;
        }
        if (current.isEmpty() || !current.get().residentId().equals(resident)
                || !current.get().renewableOn(day, terms.renewWindowDays())) return Code.PARTNER_NOT_RENEWABLE;
        if (!ownsHome(c, player)) return Code.PARTNER_HOME;
        return null;
    }

    /** Close as consumed, write the memories and, for a partnership hand-over, start or renew it. */
    private Result consumed(Connection c, TownContract contract, TownContract.Status status, Instant now, LocalDate day,
                            PartnerTerms terms, boolean strict) throws SQLException {
        ContractDefinition d = contract.definition();
        if (lockResidents(c, d) != null) throw new SQLException("Contract resident missing");
        UUID player = contract.playerId();
        Optional<Partnership> partnership = Optional.empty();
        Code code = Code.COMPLETED;
        NoPartnership none = null;
        if (d.kind() == ContractDefinition.Kind.PARTNERSHIP) {
            // Settle from what this hand-in was confirmed for (kept with the row at begin), lowered to the current
            // config. A changed material, a raised quantity or no kept terms never grants: strict holds for staff,
            // a staff resolve records consumed with no partnership.
            Optional<HandIn> kept = terms == null || !terms.perks().any() || offerChanged(d, terms)
                    ? Optional.empty() : handIn(c, contract.contractId());
            PartnerPerks perks = kept.map(k -> PartnerPerks.effective(k.perks(), terms.perks())).orElse(PartnerPerks.NONE);
            if (!perks.any()) {
                if (strict) throw new SQLException("Partnership offer is gone or changed; hold the hand-over for staff");
                none = terms == null || !terms.perks().any() ? NoPartnership.NO_OFFER
                        : offerChanged(d, terms) ? NoPartnership.OFFER_CHANGED
                        : kept.isEmpty() ? NoPartnership.NO_KEPT_TERMS : NoPartnership.NO_PERK_LEFT;
            } else {
                int termDays = Math.min(kept.orElseThrow().termDays(), terms.termDays());
                Optional<Partnership> current = PartnershipRepository.currentLocked(c, player, day, now);
                boolean start = d.partnerStart();
                Code refusal = strict ? partnerRefusal(c, start, player, d.giverId(), terms, current, day, now, contract.contractId())
                        : ownerRefusal(c, start, player, d.giverId(), terms, current, day, now, contract.contractId());
                if (refusal != null) {
                    if (strict) throw new SQLException("Partnership check failed after the take: " + refusal);
                    none = noPartnership(refusal);
                } else if (start) {
                    partnership = Optional.of(PartnershipRepository.start(c, contract.contractId(), player, d.giverId(),
                            perks, day, termDays));
                    code = Code.PARTNER_STARTED;
                } else {
                    partnership = Optional.of(PartnershipRepository.renew(c, contract.contractId(), current.orElseThrow(), termDays));
                    code = Code.PARTNER_RENEWED;
                }
            }
        }
        TownContract closed = close(c, contract, status, contract.runId(), now, day);
        String source = (d.kind() == ContractDefinition.Kind.PARTNERSHIP ? "town-partner:" : "town-contract:") + contract.contractId();
        for (UUID resident : sortedResidents(d)) {
            ResidentRepository.appendVerified(c, new RelationshipMemory.Event(resident, player, source,
                    RelationshipMemory.Kind.OBLIGATION_KEPT, now, day));
        }
        return new Result(code, Optional.of(closed), partnership, 0, none);
    }

    /** The perks and term a partnership hand-in row was confirmed for. */
    private record HandIn(PartnerPerks perks, int termDays) { }

    /** The kept terms of a partnership hand-in row (the caller holds the row). Empty for a row written before V37. */
    private static Optional<HandIn> handIn(Connection c, UUID contractId) throws SQLException {
        try (PreparedStatement ps = prepare(c, """
                SELECT partner_fee_discount_pct, partner_rank_bonus_points, partner_term_days FROM npc_town_contracts
                WHERE contract_id=? AND kind='PARTNERSHIP'
                """)) {
            ps.setBytes(1, Uuids.toBytes(contractId));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("Partnership hand-in row missing");
                int fee = rs.getInt(1); boolean noFee = rs.wasNull();
                int rank = rs.getInt(2); boolean noRank = rs.wasNull();
                int term = rs.getInt(3); boolean noTerm = rs.wasNull();
                if (noFee || noRank || noTerm) return Optional.empty();
                try {
                    if (term < 1 || term > 30) throw new IllegalArgumentException("term");
                    return Optional.of(new HandIn(new PartnerPerks(fee, rank), term));
                } catch (IllegalArgumentException invalid) { throw new SQLException("Invalid kept partnership terms", invalid); }
            }
        }
    }

    /** Why a staff resolve's check refused the partnership, by the refusal it gave. */
    static NoPartnership noPartnership(Code refusal) {
        return switch (refusal) {
            case PARTNER_HAS_ONE -> NoPartnership.HAS_PARTNERSHIP;
            case PARTNER_NOT_RENEWABLE -> NoPartnership.NOT_RENEWABLE;
            default -> NoPartnership.CHECK_FAILED;
        };
    }

    /** Staff resolve: only the seat and owner checks. */
    private static Code ownerRefusal(Connection c, boolean start, UUID player, UUID resident, PartnerTerms terms,
                                     Optional<Partnership> current, LocalDate day, Instant now, UUID self) throws SQLException {
        if (!ownsHome(c, player)) return Code.PARTNER_HOME;
        if (start) {
            if (current.isPresent()) return Code.PARTNER_HAS_ONE;
            int seats = PartnershipRepository.seatsLocked(c, resident, day, now) + reservedSeats(c, resident, self, true);
            return seats >= terms.maxSeats() ? Code.PARTNER_SEATS_FULL : null;
        }
        return current.isEmpty() || !current.get().residentId().equals(resident) || !current.get().activeOn(day)
                ? Code.PARTNER_NOT_RENEWABLE : null;
    }

    private static Result replay(TownContract contract) {
        return switch (contract.status()) {
            case COMPLETED, RESOLVED_CONSUMED -> result(Code.ALREADY_COMPLETED, contract);
            case AMBIGUOUS -> result(Code.HANDOVER_HELD, contract);
            default -> result(Code.NOT_ACTIVE, contract);
        };
    }

    /** Partnership starts at this resident still being handed over or held: each holds a seat. */
    private static int reservedSeats(Connection c, UUID resident, UUID exclude, boolean lock) throws SQLException {
        try (PreparedStatement ps = prepare(c, """
                SELECT contract_id FROM npc_town_contracts WHERE giver_id=? AND kind='PARTNERSHIP'
                AND definition_id='partner-start' AND status IN ('HANDOVER_INTENT','AMBIGUOUS') LIMIT 4097
                """ + (lock ? " FOR UPDATE" : ""))) {
            ps.setBytes(1, Uuids.toBytes(resident));
            int count = 0;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) if (exclude == null || !exclude.equals(Uuids.fromBytes(rs.getBytes(1)))) count++;
            }
            return count;
        }
    }

    /** The first day of the 30-economy-day window that ends on day. */
    public static LocalDate keptSince(LocalDate day) { return day.minusDays(KEPT_WINDOW_DAYS - 1L); }

    private static boolean ownsHome(Connection c, UUID player) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT 1 FROM npc_properties WHERE owner_uuid=? AND enabled=TRUE LIMIT 1")) {
            ps.setBytes(1, Uuids.toBytes(player));
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private static int keptLocked(Connection c, UUID resident, UUID player, LocalDate since) throws SQLException {
        try (PreparedStatement ps = prepare(c, """
                SELECT COUNT(*) FROM npc_town_contracts WHERE giver_id=? AND player_id=? AND kind IN ('SUPPLY','DELIVER')
                AND status IN ('COMPLETED','RESOLVED_CONSUMED') AND completed_economy_day>=?
                """)) {
            ps.setBytes(1, Uuids.toBytes(resident));
            ps.setBytes(2, Uuids.toBytes(player));
            ps.setString(3, since.toString());
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    private static int slotsUsed(Connection c, UUID player, LocalDate day) throws SQLException {
        try (PreparedStatement ps = prepare(c, """
                SELECT COUNT(*) FROM npc_town_contracts WHERE player_id=? AND accepted_economy_day=? AND day_slot>=1
                """)) {
            ps.setBytes(1, Uuids.toBytes(player));
            ps.setString(2, day.toString());
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    private static boolean partnerDayUsed(Connection c, UUID player, LocalDate day) throws SQLException {
        try (PreparedStatement ps = prepare(c, """
                SELECT 1 FROM npc_town_contracts WHERE player_id=? AND accepted_economy_day=? AND day_slot=0 LIMIT 1
                """)) {
            ps.setBytes(1, Uuids.toBytes(player));
            ps.setString(2, day.toString());
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private static boolean regressed(Connection c, UUID player, Instant now, LocalDate day) throws SQLException {
        try (PreparedStatement ps = prepare(c, """
                SELECT MAX(accepted_at_millis), MAX(accepted_economy_day) FROM npc_town_contracts WHERE player_id=?
                """)) {
            ps.setBytes(1, Uuids.toBytes(player));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                long last = rs.getLong(1);
                if (rs.wasNull()) return false;
                String lastDay = rs.getString(2);
                return last > now.toEpochMilli() || lastDay != null && LocalDate.parse(lastDay).isAfter(day);
            }
        }
    }

    /** A delivery mid-flight or held, or an item repair running or held: that runtime owns the inventory. */
    private static boolean itemsBusy(Connection c, UUID player) throws SQLException {
        for (String sql : List.of(
                "SELECT request_nonce FROM npc_item_custody WHERE player_id=? AND state IN ('DELIVERY_INTENT','AMBIGUOUS') LIMIT 1 FOR UPDATE",
                "SELECT collection_id FROM npc_item_repair_collections WHERE player_id=? AND state IN ('INTENT','AMBIGUOUS') LIMIT 1 FOR UPDATE")) {
            try (PreparedStatement ps = prepare(c, sql)) {
                ps.setBytes(1, Uuids.toBytes(player));
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return true; }
            }
        }
        return false;
    }

    private static Optional<TownContract> openLocked(Connection c, UUID player, Instant now) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT " + COLUMNS + " FROM npc_town_contracts WHERE open_player_id=? FOR UPDATE")) {
            ps.setBytes(1, Uuids.toBytes(player));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                TownContract contract = read(rs);
                if (contract.status() == TownContract.Status.ACTIVE && !now.isBefore(contract.expiresAt())) {
                    close(c, contract, TownContract.Status.EXPIRED, null, contract.expiresAt(), null);
                    return Optional.empty();
                }
                return Optional.of(contract);
            }
        }
    }

    private static Optional<TownContract> findLocked(Connection c, UUID contractId, UUID player) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT " + COLUMNS
                + " FROM npc_town_contracts WHERE contract_id=? AND player_id=? FOR UPDATE")) {
            ps.setBytes(1, Uuids.toBytes(contractId));
            ps.setBytes(2, Uuids.toBytes(player));
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(read(rs)) : Optional.empty(); }
        }
    }

    private Optional<UUID> owner(UUID contractId) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c,
                "SELECT player_id FROM npc_town_contracts WHERE contract_id=?")) {
            ps.setBytes(1, Uuids.toBytes(contractId));
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(Uuids.fromBytes(rs.getBytes(1))) : Optional.empty(); }
        }
    }

    private static Optional<Decision> resolution(Connection c, UUID contractId) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT decision FROM npc_contract_handover_resolutions WHERE contract_id=? FOR UPDATE")) {
            ps.setBytes(1, Uuids.toBytes(contractId));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                try { return Optional.of(Decision.valueOf(rs.getString(1))); }
                catch (IllegalArgumentException invalid) { throw new SQLException("Invalid stored resolution", invalid); }
            }
        }
    }

    private static Optional<ItemCollectionEvidence> evidence(Connection c, UUID contractId) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_contract_handover_evidence WHERE contract_id=? FOR UPDATE")) {
            ps.setBytes(1, Uuids.toBytes(contractId));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                try {
                    return Optional.of(new ItemCollectionEvidence(playerOf(c, contractId), Uuids.fromBytes(rs.getBytes("run_id")),
                            rs.getString("before_inventory_sha256"), rs.getString("after_inventory_sha256"),
                            List.of(rs.getInt("before_units")), List.of(rs.getInt("after_units")),
                            rs.getBoolean("returned_normally"), rs.getBoolean("disk_proof")));
                } catch (IllegalArgumentException invalid) { throw new SQLException("Invalid stored hand-over evidence", invalid); }
            }
        }
    }

    private static UUID playerOf(Connection c, UUID contractId) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT player_id FROM npc_town_contracts WHERE contract_id=?")) {
            ps.setBytes(1, Uuids.toBytes(contractId));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("Evidence without a contract");
                return Uuids.fromBytes(rs.getBytes(1));
            }
        }
    }

    private static void insertEvidence(Connection c, TownContract contract, UUID runId, ItemCollectionEvidence e) throws SQLException {
        try (PreparedStatement ps = prepare(c, """
                INSERT INTO npc_contract_handover_evidence (contract_id, run_id, material, expected_units, before_units,
                after_units, before_inventory_sha256, after_inventory_sha256, returned_normally, disk_proof)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """)) {
            ps.setBytes(1, Uuids.toBytes(contract.contractId()));
            ps.setBytes(2, Uuids.toBytes(runId));
            ps.setString(3, contract.definition().material());
            ps.setInt(4, contract.definition().quantity());
            ps.setInt(5, e.beforeUnits().getFirst());
            ps.setInt(6, e.afterUnits().getFirst());
            ps.setString(7, e.beforeInventorySha256());
            ps.setString(8, e.afterInventorySha256());
            ps.setBoolean(9, e.returnedNormally());
            ps.setBoolean(10, e.durable());
            one(ps.executeUpdate());
        }
    }

    private static List<UUID> sortedResidents(ContractDefinition d) {
        return d.giverId().equals(d.recipientId()) ? List.of(d.giverId())
                : java.util.stream.Stream.of(d.giverId(), d.recipientId()).sorted().toList();
    }

    private static Code lockResidents(Connection c, ContractDefinition d) throws SQLException {
        for (UUID resident : sortedResidents(d)) {
            try (PreparedStatement ps = prepare(c, "SELECT resident_id FROM npc_resident_profiles WHERE resident_id=? FOR UPDATE")) {
                ps.setBytes(1, Uuids.toBytes(resident));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return resident.equals(d.giverId()) ? Code.UNKNOWN_GIVER : Code.UNKNOWN_RECIPIENT;
                }
            }
        }
        return null;
    }

    /** Current locking read of the exact V2 memory columns, replayed under the profile lock. */
    private static RelationshipMemory.Stage stage(Connection c, UUID resident, UUID player) throws SQLException {
        List<RelationshipMemory.Event> events = new ArrayList<>();
        try (PreparedStatement ps = prepare(c, """
                SELECT source_ref, kind, occurred_at_millis, economy_day FROM npc_resident_memory
                WHERE resident_id=? AND player_id=? ORDER BY occurred_at_millis, source_ref LIMIT ? FOR UPDATE
                """)) {
            ps.setBytes(1, Uuids.toBytes(resident));
            ps.setBytes(2, Uuids.toBytes(player));
            ps.setInt(3, RelationshipMemory.MAX_EVENTS + 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (events.size() == RelationshipMemory.MAX_EVENTS) throw new SQLException("resident memory exceeds replay bound");
                    try {
                        events.add(new RelationshipMemory.Event(resident, player, rs.getString("source_ref"),
                                RelationshipMemory.Kind.valueOf(rs.getString("kind")),
                                Instant.ofEpochMilli(rs.getLong("occurred_at_millis")), LocalDate.parse(rs.getString("economy_day"))));
                    } catch (IllegalArgumentException | java.time.DateTimeException | NullPointerException e) {
                        throw new SQLException("invalid stored resident memory", e);
                    }
                }
            }
        }
        try { return RelationshipMemory.replay(resident, player, events).stage(); }
        catch (IllegalArgumentException e) { throw new SQLException("invalid resident memory chronology or payload", e); }
    }

    private static void insert(Connection c, TownContract t) throws SQLException {
        try (PreparedStatement ps = prepare(c, "INSERT INTO npc_town_contracts (" + COLUMNS
                + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ContractDefinition d = t.definition();
            ps.setBytes(1, Uuids.toBytes(t.contractId()));
            ps.setBytes(2, Uuids.toBytes(t.playerId()));
            ps.setString(3, d.kind().name());
            ps.setString(4, d.id());
            ps.setString(5, d.title());
            ps.setString(6, d.description());
            ps.setBytes(7, Uuids.toBytes(d.giverId()));
            ps.setBytes(8, Uuids.toBytes(d.recipientId()));
            ps.setString(9, d.material());
            ps.setInt(10, d.quantity());
            ps.setString(11, d.minimumStage().name());
            ps.setInt(12, d.durationMinutes());
            ps.setLong(13, t.acceptedAt().toEpochMilli());
            ps.setString(14, t.acceptedEconomyDay().toString());
            ps.setInt(15, t.daySlot());
            ps.setLong(16, t.expiresAt().toEpochMilli());
            ps.setString(17, t.status().name());
            if (t.runId() == null) ps.setNull(18, Types.BINARY); else ps.setBytes(18, Uuids.toBytes(t.runId()));
            ps.setNull(19, Types.BIGINT);
            ps.setNull(20, Types.VARCHAR);
            one(ps.executeUpdate());
        }
    }

    private static TownContract transition(Connection c, TownContract t, TownContract.Status next, UUID run,
                                           Instant closed, LocalDate completed) throws SQLException {
        TownContract updated = t.with(next, run, closed, completed);
        try (PreparedStatement ps = prepare(c, """
                UPDATE npc_town_contracts SET status=?, handover_run_id=?, closed_at_millis=?, completed_economy_day=?,
                updated_at=CURRENT_TIMESTAMP(3) WHERE contract_id=? AND player_id=? AND status=?
                """)) {
            ps.setString(1, next.name());
            if (run == null) ps.setNull(2, Types.BINARY); else ps.setBytes(2, Uuids.toBytes(run));
            if (updated.closedAt() == null) ps.setNull(3, Types.BIGINT); else ps.setLong(3, updated.closedAt().toEpochMilli());
            ps.setString(4, completed == null ? null : completed.toString());
            ps.setBytes(5, Uuids.toBytes(t.contractId()));
            ps.setBytes(6, Uuids.toBytes(t.playerId()));
            ps.setString(7, t.status().name());
            one(ps.executeUpdate());
        }
        return updated;
    }

    private static TownContract close(Connection c, TownContract t, TownContract.Status next, UUID run, Instant at,
                                      LocalDate completed) throws SQLException {
        return transition(c, t, next, run, at, completed);
    }

    private static TownContract read(ResultSet rs) throws SQLException {
        try {
            ContractDefinition d = new ContractDefinition(rs.getString("definition_id"),
                    ContractDefinition.Kind.valueOf(rs.getString("kind")), rs.getString("title"), rs.getString("description"),
                    Uuids.fromBytes(rs.getBytes("giver_id")), Uuids.fromBytes(rs.getBytes("recipient_id")),
                    rs.getString("material"), rs.getInt("quantity"),
                    RelationshipMemory.Stage.valueOf(rs.getString("minimum_stage")), rs.getInt("duration_minutes"));
            byte[] run = rs.getBytes("handover_run_id");
            long closed = rs.getLong("closed_at_millis");
            Instant closedAt = rs.wasNull() ? null : Instant.ofEpochMilli(closed);
            String completed = rs.getString("completed_economy_day");
            return new TownContract(Uuids.fromBytes(rs.getBytes("contract_id")), Uuids.fromBytes(rs.getBytes("player_id")), d,
                    Instant.ofEpochMilli(rs.getLong("accepted_at_millis")), LocalDate.parse(rs.getString("accepted_economy_day")),
                    rs.getInt("day_slot"), Instant.ofEpochMilli(rs.getLong("expires_at_millis")),
                    TownContract.Status.valueOf(rs.getString("status")), run == null ? null : Uuids.fromBytes(run),
                    closedAt, completed == null ? null : LocalDate.parse(completed));
        } catch (IllegalArgumentException | java.time.DateTimeException | NullPointerException e) {
            throw new SQLException("invalid stored contract", e);
        }
    }

    private static Held held(TownContract t) {
        return new Held(t.contractId(), t.playerId(), t.definition().kind(), t.definition().id(), t.definition().recipientId());
    }

    private static void requireRef(String ref) {
        if (ref == null || !ref.matches("[A-Za-z0-9_.:@-]{1,96}")) throw new IllegalArgumentException("Invalid reference");
    }

    private static Result result(Code code) { return new Result(code, Optional.empty(), Optional.empty(), 0); }
    private static Result result(Code code, TownContract t) { return new Result(code, Optional.of(t), Optional.empty(), 0); }

    private void ensureGuard(UUID player) throws SQLException {
        ensure("INSERT INTO npc_town_contract_players (player_id) VALUES (?)", player);
    }

    private void ensureCustodyGuard(UUID player) throws SQLException {
        ensure("INSERT INTO npc_item_custody_players (player_id) VALUES (?)", player);
    }

    // Autocommit duplicate-key insert, released before the exclusive guard lock is taken.
    private void ensure(String sql, UUID player) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c, sql)) {
            ps.setBytes(1, Uuids.toBytes(player));
            try { one(ps.executeUpdate()); }
            catch (SQLException e) { if (e.getErrorCode() != 1062 || !"23000".equals(e.getSQLState())) throw e; }
        }
    }

    private static boolean guardExists(Connection c, UUID player) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT player_id FROM npc_town_contract_players WHERE player_id=? FOR UPDATE")) {
            ps.setBytes(1, Uuids.toBytes(player));
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private static void lockGuard(Connection c, UUID player) throws SQLException {
        if (!guardExists(c, player)) throw new SQLException("Contract player guard is missing");
    }

    private static void lockCustodyGuard(Connection c, UUID player) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT player_id FROM npc_item_custody_players WHERE player_id=? FOR UPDATE")) {
            ps.setBytes(1, Uuids.toBytes(player));
            try (ResultSet rs = ps.executeQuery()) { if (!rs.next()) throw new SQLException("Item custody player guard is missing"); }
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
                try { c.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }

    private static void one(int rows) throws SQLException {
        if (rows != 1) throw new SQLException("Contract write did not affect exactly one row");
    }

    private static PreparedStatement prepare(Connection c, String sql) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        try { ps.setQueryTimeout(5); return ps; }
        catch (SQLException failure) { ps.close(); throw failure; }
    }
}

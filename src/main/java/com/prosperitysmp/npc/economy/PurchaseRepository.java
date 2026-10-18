package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.formulas.UpgradeType;
import com.prosperitysmp.npc.formulas.IncidentType;
import com.prosperitysmp.npc.clock.EconomyClock;
import com.prosperitysmp.npc.property.InsuranceRepository;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.TenantRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Owner purchase receipts (utility, repair, upgrade, eviction, insurance). UtilityPaymentRuntime enqueues and
 * completes them, and keeps the home's ownership lease until the commit is acknowledged. Each receipt is bound to
 * the ownership epoch it was quoted for. SQL ownership here is a mirror, not ARM authority. This repository never
 * calls Vault or infers payment from a queued command.
 */
public final class PurchaseRepository {
    public enum State { WAITING, APPLIED, DECLINED, REVIEW_REQUIRED }
    public record Receipt(long eventId, PurchaseRequest request, long quotedCents, State state, String outcome) { }
    public static final class MaximumExceededException extends SQLException {
        private static final long serialVersionUID = 1L;
        private MaximumExceededException() { super("Purchase exceeds authorized maximum"); }
    }
    /** A scoped incident is already resolved. This refusal makes no new payment or effect. */
    public static final class RepairUnavailableException extends SQLException {
        private static final long serialVersionUID = 1L;
        private RepairUnavailableException() { super("Repair target already resolved"); }
    }
    /**
     * item-repairs.block-cash-while-items-held: an item hand-in for this incident is running or held for staff.
     * Refused before any intent or debit; no money moves.
     */
    public static final class ItemsPendingException extends SQLException {
        private static final long serialVersionUID = 1L;
        private ItemsPendingException() { super("Repair has an item hand-in waiting"); }
    }
    /** Missing or departed tenant, rejected before a new payment intent. */
    public static final class EvictionUnavailableException extends SQLException {
        private static final long serialVersionUID = 1L;
        private EvictionUnavailableException() { super("Eviction tenancy unavailable"); }
    }
    /** Predictable stale/needless purchase, rejected before any event is inserted. */
    public static final class TargetUnavailableException extends SQLException {
        private static final long serialVersionUID = 1L;
        private TargetUnavailableException(String reason) { super("Purchase rejected: " + reason); }
    }
    public static final class InsufficientFundsException extends SQLException {
        private static final long serialVersionUID = 1L;
        private InsufficientFundsException() { super("Insufficient available funds; no intent created"); }
    }
    private record PropertyState(UUID owner, int epoch, boolean enabled, int condition, Property property) { }
    private final InsuranceRepository insurance = new InsuranceRepository();
    private final Database database;
    private final TransferJournal transfers;
    private final Clock clock;
    private final EconomyClock economyClock;
    private final BooleanSupplier blockCashWhileItemsHeld;
    private final PurchasePrices prices;

    /** Runtime wiring must supply the configured reserve and economy clock. Never reads item-repair rows. */
    public PurchaseRepository(Database database, UUID reserve, Clock clock, ZoneId economyZone, int resetHour) {
        this(database, reserve, clock, economyZone, resetHour, () -> false);
    }

    /**
     * blockCashWhileItemsHeld is item-repairs.block-cash-while-items-held, read per request. On: a cash repair for
     * an incident with a running or held item hand-in is refused before any intent or debit. It acts only on
     * item-repair rows, which exist only after that feature ran.
     */
    public PurchaseRepository(Database database, UUID reserve, Clock clock, ZoneId economyZone, int resetHour,
                              BooleanSupplier blockCashWhileItemsHeld) {
        this(database, reserve, clock, economyZone, resetHour, blockCashWhileItemsHeld, PurchasePrices.DEFAULT);
    }

    public PurchaseRepository(Database database, UUID reserve, Clock clock, ZoneId economyZone, int resetHour,
                              BooleanSupplier blockCashWhileItemsHeld, PurchasePrices prices) {
        this.database = Objects.requireNonNull(database);
        this.transfers = new TransferJournal(database, reserve);
        this.clock = Objects.requireNonNull(clock);
        this.economyClock = new EconomyClock(clock, economyZone, resetHour);
        this.blockCashWhileItemsHeld = Objects.requireNonNull(blockCashWhileItemsHeld);
        this.prices = Objects.requireNonNull(prices);
    }

    public PurchaseRequest quotePrice(PurchaseRequest request) { return prices.quote(request); }

    /** Reads an exact incident quote without reserving funds or changing any gameplay state. */
    public PurchaseRequest quoteRepair(UUID requestId, UUID propertyId, UUID payerId, int ownershipEpoch,
                                       long incidentId, LocalDate day) throws SQLException {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(propertyId);
        Objects.requireNonNull(payerId);
        Objects.requireNonNull(day);
        if (ownershipEpoch < 0 || incidentId <= 0 || day.getYear() < 1 || day.getYear() > 9999)
            throw new IllegalArgumentException("Invalid repair scope");
        return transaction(c -> {
            PropertyState p = property(c, propertyId);
            if (p == null || !p.enabled() || !payerId.equals(p.owner()) || p.epoch() != ownershipEpoch)
                throw new SQLException("Repair quote rejected: property ownership unavailable");
            try (PreparedStatement ps = prepare(c,
                    "SELECT resolved_at FROM npc_incidents WHERE property_id=? AND incident_id=? FOR UPDATE")) {
                id(ps, 1, propertyId);
                ps.setLong(2, incidentId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (strictNext(c, ps, rs)) {
                        boolean resolved = rs.getObject("resolved_at", LocalDateTime.class) != null;
                        if (strictNext(c, ps, rs)) throw new SQLException("Duplicate repair target");
                        if (resolved) throw new RepairUnavailableException();
                    }
                }
            }
            if (blockCashWhileItemsHeld.getAsBoolean() && IncidentRepair.itemsHeld(c, incidentId))
                throw new ItemsPendingException();
            Instant at = clock.instant();
            return new PurchaseRequest(requestId, propertyId, payerId, ownershipEpoch,
                    PurchaseRequest.Kind.REPAIR, null, null, null, day,
                    IncidentRepair.quote(c, propertyId, incidentId, at, economyClock.dayAt(at), day));
        });
    }

    /** Read-only, exact current occupancy quote. Eligibility/ARM authority remain coordinator duties. */
    public PurchaseRequest quoteEviction(UUID requestId, UUID propertyId, UUID payerId, int ownershipEpoch,
                                         UUID tenantId, LocalDate day) throws SQLException {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(propertyId);
        Objects.requireNonNull(payerId);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(day);
        if (ownershipEpoch < 0 || day.getYear() < 1 || day.getYear() > 9999)
            throw new IllegalArgumentException("Invalid eviction scope");
        return transaction(c -> {
            PropertyState p = property(c, propertyId);
            if (p == null || !p.enabled() || !payerId.equals(p.owner()) || p.epoch() != ownershipEpoch)
                throw new SQLException("Eviction ownership unavailable");
            if (day.isAfter(economyClock.today())) throw new SQLException("Eviction quote is in the future");
            EvictionQuote target = evictionTarget(c, propertyId, tenantId);
            if (target == null) throw new EvictionUnavailableException();
            return prices.quote(new PurchaseRequest(requestId, propertyId, payerId, ownershipEpoch,
                    PurchaseRequest.Kind.EVICT, null, null, tenantId, day, null, target));
        });
    }

    private static boolean needsTransferProof(PurchaseRequest q) {
        return q.configuredPriceCents() != null || q.kind() == PurchaseRequest.Kind.REPAIR
                || q.kind() == PurchaseRequest.Kind.INSURANCE || q.eviction() != null;
    }

    /** Capacity and overlap are read under the same property guard used by enqueue/complete. */
    public PurchaseRequest quoteInsurance(UUID requestId, UUID propertyId, UUID payerId, int ownershipEpoch,
                                          LocalDate day) throws SQLException {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(propertyId);
        Objects.requireNonNull(payerId);
        Objects.requireNonNull(day);
        if (ownershipEpoch < 0 || day.getYear() < 1 || day.getYear() > 9999)
            throw new IllegalArgumentException("Invalid insurance scope");
        return transaction(c -> {
            PropertyState p = property(c, propertyId);
            if (p == null || !p.enabled() || !payerId.equals(p.owner()) || p.epoch() != ownershipEpoch)
                throw new TargetUnavailableException("OWNERSHIP_CHANGED");
            if (!day.equals(economyClock.today())) throw new TargetUnavailableException("INSURANCE_QUOTE_DAY_CHANGED");
            if (!insurance.canPurchase(c, propertyId, day)) throw new TargetUnavailableException("INSURANCE_TERM_RESERVED");
            return prices.quote(new PurchaseRequest(requestId, propertyId, payerId, ownershipEpoch,
                    PurchaseRequest.Kind.INSURANCE, null, null, null, day, null, null,
                    new PurchaseRequest.InsuranceQuote(p.property().capacity())));
        });
    }

    private static EvictionQuote evictionTarget(Connection c, UUID property, UUID tenant) throws SQLException {
        int capacity;
        try (PreparedStatement ps = prepare(c, "SELECT capacity FROM npc_properties WHERE property_id=? FOR UPDATE")) {
            id(ps, 1, property);
            try (ResultSet rs = ps.executeQuery()) {
                if (!strictNext(c, ps, rs)) return null;
                capacity = rs.getInt("capacity");
                if (rs.wasNull() || capacity < 1 || capacity > 4 || strictNext(c, ps, rs))
                    throw new SQLException("Invalid eviction property capacity");
            }
        }
        try (PreparedStatement ps = prepare(c, "SELECT property_id,slot,row_version FROM npc_tenants WHERE tenant_id=? FOR UPDATE")) {
            id(ps, 1, tenant);
            try (ResultSet rs = ps.executeQuery()) {
                if (!strictNext(c, ps, rs)) return null;
                if (!property.equals(uuid(rs, "property_id"))) return null;
                int slot = rs.getInt("slot");
                if (rs.wasNull() || slot < 0 || slot >= capacity) throw new SQLException("Invalid eviction slot");
                long revision = rs.getLong("row_version");
                if (rs.wasNull() || revision < 0 || revision == Long.MAX_VALUE || strictNext(c, ps, rs))
                    throw new SQLException("Invalid eviction revision");
                return new EvictionQuote(revision, slot);
            }
        }
    }

    private void checkEvictionHistory(Connection c, PurchaseRequest q) throws SQLException {
        List<Receipt> old = new ArrayList<>();
        try (PreparedStatement ps = prepare(c, """
                SELECT * FROM npc_purchase_intents WHERE property_id=? AND tenant_id=? AND kind='EVICT'
                AND state IN ('WAITING','REVIEW_REQUIRED','DECLINED')
                ORDER BY event_id LIMIT 101 ROWS EXAMINED 1024
                """)) {
            id(ps, 1, q.propertyId());
            id(ps, 2, q.tenantId());
            try (ResultSet rs = ps.executeQuery()) {
                while (strictNext(c, ps, rs)) {
                    if (old.size() >= 100) throw new SQLException("Eviction history requires review");
                    old.add(readReceipt(rs));
                }
            }
        }
        for (Receipt receipt : old) {
            if (receipt.state() != State.DECLINED) throw new SQLException("Unresolved earlier eviction requires review");
            requireRetryableDecline(c, receipt);
        }
    }

    private static boolean strictNext(Connection c, PreparedStatement ps, ResultSet rs) throws SQLException {
        checkWarnings(c, ps, rs);
        boolean present = rs.next();
        checkWarnings(c, ps, rs);
        return present;
    }
    private static void checkWarnings(Connection c, PreparedStatement ps, ResultSet rs) throws SQLException {
        if (c.getWarnings() != null || ps.getWarnings() != null || rs.getWarnings() != null)
            throw new SQLException("Incomplete purchase target read");
    }

    /**
     * Property lock serializes requests for the same semantic action. Repeated waiting,
     * applied and review-required actions return their original receipt/price. Only an
     * unequivocally declined attempt permits a new request UUID; old UUIDs never retry.
     */
    public Receipt enqueue(PurchaseRequest request) throws SQLException {
        return enqueue(request, Long.MAX_VALUE);
    }

    /** Enforce consent before any event/intent insert; a failed rollback remains uncertain. */
    public Receipt enqueue(PurchaseRequest request, long maximumCents) throws SQLException {
        return enqueue(request, maximumCents, Long.MAX_VALUE);
    }

    /** Available balance is a preflight hint, not proof or permission to overdraw at dispatch. */
    public Receipt enqueue(PurchaseRequest request, long maximumCents, long availableCents) throws SQLException {
        Objects.requireNonNull(request);
        if (maximumCents <= 0 || availableCents < 0) throw new IllegalArgumentException("Invalid purchase maximum or balance");
        return transaction(c -> {
            PropertyState property = property(c, request.propertyId());
            String invalid = ownershipProblem(property, request);
            if (invalid != null) throw new SQLException("Purchase rejected: " + invalid);
            if (request.cents() > maximumCents) throw new MaximumExceededException();
            Optional<Receipt> priorId = byRequest(c, request.requestId());
            if (priorId.isPresent()) {
                Receipt prior = priorId.get();
                if (!request.equals(prior.request()) || request.cents() != prior.quotedCents())
                    throw new SQLException("Purchase request UUID payload conflict");
                return prior;
            }
            if (!prices.quote(request).equals(request)) throw new TargetUnavailableException("PRICE_CHANGED");
            if (request.kind() == PurchaseRequest.Kind.EVICT && request.eviction() == null)
                throw new SQLException("New eviction requires a bound tenancy quote");
            Optional<Receipt> action = lastAction(c, request);
            if (action.isPresent() && action.get().state() != State.DECLINED) return action.get();
            if (needsTransferProof(request) && action.isPresent())
                requireRetryableDecline(c, action.get());
            if (request.kind() == PurchaseRequest.Kind.EVICT) checkEvictionHistory(c, request);
            String targetProblem = targetProblem(c, request, property, blockCashWhileItemsHeld.getAsBoolean());
            if ("REPAIR_ITEMS_PENDING".equals(targetProblem)) throw new ItemsPendingException();
            if ("TENANT_MOVED_OR_MISSING".equals(targetProblem)) throw new EvictionUnavailableException();
            if ("NO_MAINTENANCE_NEEDED".equals(targetProblem) || "UPGRADE_TARGET_CHANGED".equals(targetProblem)
                    || (request.kind() == PurchaseRequest.Kind.INSURANCE && targetProblem != null))
                throw new TargetUnavailableException(targetProblem);
            if (targetProblem != null) throw new SQLException("Purchase rejected: " + targetProblem);
            if (request.cents() > availableCents) throw new InsufficientFundsException();
            EconomyEvent event = EconomyEvent.pending(request.eventKey(), request.cents(), request.source(),
                    request.propertyId(), request.payerId(), request.ownershipEpoch(), request.payerId(), request.day().toString());
            long eventId = EconomyEventRepository.insertPending(c, event);
            if (eventId <= 0) throw new SQLException("Purchase event exists without its matching intent");
            String insuranceColumn = request.insurance() == null ? "" : ",insurance_capacity";
            String insuranceValue = request.insurance() == null ? "" : ",?";
            String priceColumn = request.configuredPriceCents() == null ? "" : ",configured_price_cents";
            String priceValue = request.configuredPriceCents() == null ? "" : ",?";
            try (PreparedStatement ps = prepare(c, """
                    INSERT INTO npc_purchase_intents (event_id,request_id,property_id,payer_uuid,ownership_epoch,
                      kind,upgrade_type,target_level,tenant_id,economy_day,quoted_amount_cents,action_key,
                      repair_incident_id,repair_type,repair_snapshot_sha256,evict_tenancy_revision,evict_slot
                    """ + insuranceColumn + priceColumn + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?"
                    + insuranceValue + priceValue + ")")) {
                ps.setLong(1, eventId);
                id(ps, 2, request.requestId());
                id(ps, 3, request.propertyId());
                id(ps, 4, request.payerId());
                ps.setInt(5, request.ownershipEpoch());
                ps.setString(6, request.kind().name());
                ps.setString(7, request.upgrade() == null ? null : request.upgrade().name());
                if (request.targetLevel() == null) ps.setNull(8, Types.TINYINT);
                else ps.setInt(8, request.targetLevel());
                id(ps, 9, request.tenantId());
                ps.setString(10, request.day().toString());
                ps.setLong(11, request.cents());
                ps.setString(12, request.actionKey());
                if (request.repair() == null) {
                    ps.setNull(13, Types.BIGINT);
                    ps.setNull(14, Types.VARCHAR);
                    ps.setNull(15, Types.VARCHAR);
                } else {
                    ps.setLong(13, request.repair().incidentId());
                    ps.setString(14, request.repair().type().name());
                    ps.setString(15, request.repair().snapshotSha256());
                }
                if (request.eviction() == null) {
                    ps.setNull(16, Types.BIGINT);
                    ps.setNull(17, Types.TINYINT);
                } else {
                    ps.setLong(16, request.eviction().tenancyRevision());
                    ps.setInt(17, request.eviction().slot());
                }
                int next = 18;
                if (request.insurance() != null) ps.setInt(next++, request.insurance().capacity());
                if (request.configuredPriceCents() != null) ps.setLong(next, request.configuredPriceCents());
                one(ps.executeUpdate());
            }
            return new Receipt(eventId, request, request.cents(), State.WAITING, null);
        });
    }

    public Optional<Receipt> findByRequest(UUID request) throws SQLException {
        return findByRequest(database, request);
    }

    /** Receipt lookup needs no reserve account, so it stays available before one is configured. */
    public static Optional<Receipt> findByRequest(Database database, UUID request) throws SQLException {
        Objects.requireNonNull(database);
        Objects.requireNonNull(request);
        try (Connection c = database.getConnection()) { return byRequest(c, request); }
    }

    /** Bounded worker feed; deliberately no legacy generic event is turned into an invented intent. */
    public List<Long> selectReadyIds(int max) throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c, """
                SELECT i.event_id FROM npc_purchase_intents i JOIN npc_economy_events e ON e.event_id=i.event_id
                WHERE i.state='WAITING' AND (e.state NOT IN ('PENDING','CLAIMED')
                  OR (i.kind='EVICT' AND i.evict_tenancy_revision IS NULL)) ORDER BY i.event_id LIMIT ?
                """)) {
            ps.setInt(1, Math.clamp(max, 0, 100));
            List<Long> ids = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) ids.add(rs.getLong(1)); }
            return List.copyOf(ids);
        }
    }

    /**
     * Lock order: property -> money event -> intent -> affected upgrade/tenant.
     * State APPLIED and the benefit commit together. A crash before/after commit
     * can therefore be replayed without a second benefit. PAID is an upstream
     * proof contract, not proof the current Skript bridge is safe to enable.
     */
    public Optional<Receipt> complete(long eventId) throws SQLException {
        if (eventId <= 0) return Optional.empty();
        return transaction(c -> {
            Optional<Receipt> observed = byEvent(c, eventId, false);
            if (observed.isEmpty()) return Optional.empty();
            PropertyState property = property(c, observed.get().request().propertyId());
            String eventState;
            String bindingProblem;
            try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_economy_events WHERE event_id=? FOR UPDATE")) {
                ps.setLong(1, eventId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new SQLException("Purchase ledger event missing");
                    eventState = rs.getString("state");
                    bindingProblem = bindingProblem(rs, observed.get());
                }
            }
            Receipt receipt = byEvent(c, eventId, true).orElseThrow(() -> new SQLException("Purchase intent vanished"));
            if (receipt.state() != State.WAITING) return Optional.of(receipt);
            if (!receipt.request().equals(observed.get().request()) || receipt.quotedCents() != observed.get().quotedCents())
                return Optional.of(finish(c, receipt, State.REVIEW_REQUIRED, "INTENT_CHANGED"));
            if (bindingProblem != null) return Optional.of(finish(c, receipt, State.REVIEW_REQUIRED, bindingProblem));
            if (receipt.request().kind() == PurchaseRequest.Kind.EVICT && receipt.request().eviction() == null)
                return Optional.of(finish(c, receipt, State.REVIEW_REQUIRED, "LEGACY_EVICTION_UNBOUND"));
            if ("PENDING".equals(eventState) || "CLAIMED".equals(eventState)) return Optional.of(receipt);
            if ("FAILED".equals(eventState)) {
                if (needsTransferProof(receipt.request()) && !transfers.confirmedDeclined(c, eventId))
                    return Optional.of(finish(c, receipt, State.REVIEW_REQUIRED, "DECLINE_PROOF_MISSING"));
                return Optional.of(finish(c, receipt, State.DECLINED,
                        transfers.confirmedCompensated(c, eventId) ? "PAYMENT_COMPENSATED" : "PAYMENT_DECLINED"));
            }
            if (!"PAID".equals(eventState)) return Optional.of(finish(c, receipt, State.REVIEW_REQUIRED, "PAYMENT_UNCERTAIN"));
            if (needsTransferProof(receipt.request()) && !transfers.confirmedPaid(c, eventId))
                return Optional.of(finish(c, receipt, State.REVIEW_REQUIRED, "TRANSFER_PROOF_MISSING"));
            String problem = ownershipProblem(property, receipt.request());
            // Money already moved here: never refuse for a held hand-in. A later item settle returns those items.
            if (problem == null) problem = targetProblem(c, receipt.request(), property, false);
            if (problem != null) return Optional.of(finish(c, receipt, State.REVIEW_REQUIRED, problem));
            apply(c, receipt, property);
            return Optional.of(finish(c, receipt, State.APPLIED, "BENEFIT_APPLIED"));
        });
    }

    /** On an unexpected completion failure, hold for inspection, never re-charge or auto-refund. */
    public void markReview(long eventId, String reason) throws SQLException {
        if (eventId <= 0 || reason == null || !reason.matches("[A-Z_]{1,48}")) throw new IllegalArgumentException("Invalid review reason");
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c,
                "UPDATE npc_purchase_intents SET state='REVIEW_REQUIRED',outcome_code=?,completed_at=CURRENT_TIMESTAMP(3) WHERE event_id=? "
                + "AND state='WAITING'")) {
            ps.setString(1, reason);
            ps.setLong(2, eventId);
            ps.executeUpdate();
        }
    }

    private static String bindingProblem(ResultSet event, Receipt receipt) throws SQLException {
        PurchaseRequest q = receipt.request();
        if (receipt.quotedCents() != q.cents()) return "QUOTE_PRICE_MISMATCH";
        boolean same = q.eventKey().equals(event.getString("idempotency_key"))
                && q.source().key().equals(event.getString("source"))
                && EventSource.Direction.FROM_PUBLIC.moneyFlowKey().equals(event.getString("direction"))
                && q.propertyId().equals(uuid(event, "property_id")) && q.payerId().equals(uuid(event, "counterparty_uuid"))
                && q.payerId().equals(uuid(event, "benefit_unit_id"))
                && Objects.equals(q.ownershipEpoch(), event.getObject("ownership_epoch"))
                && q.day().toString().equals(event.getString("economy_day"))
                && receipt.quotedCents() > 0 && event.getLong("requested_amount_cents") == receipt.quotedCents();
        if (!same) return "PAYMENT_BINDING_MISMATCH";
        if ("PAID".equals(event.getString("state"))
                && (event.getObject("paid_amount_cents") == null || event.getLong("paid_amount_cents") != receipt.quotedCents()))
            return "PAID_AMOUNT_MISMATCH";
        if ("FAILED".equals(event.getString("state")) && event.getLong("paid_amount_cents") != 0)
            return "FAILED_WITH_MOVEMENT";
        return null;
    }

    /** Never use an intent's DECLINED label alone as permission for another external call. */
    private void requireRetryableDecline(Connection c, Receipt observed) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_economy_events WHERE event_id=? FOR UPDATE")) {
            ps.setLong(1, observed.eventId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || !"FAILED".equals(rs.getString("state")) || bindingProblem(rs, observed) != null)
                    throw new SQLException("Prior purchase decline lacks matching failed event");
            }
        }
        Receipt locked = byEvent(c, observed.eventId(), true)
                .orElseThrow(() -> new SQLException("Prior purchase receipt vanished"));
        if (!locked.equals(observed) || locked.state() != State.DECLINED
                || !transfers.confirmedDeclined(c, observed.eventId()))
            throw new SQLException("Prior purchase decline lacks no-movement proof");
    }

    private static PropertyState property(Connection c, UUID property) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_properties WHERE property_id=? FOR UPDATE")) {
            id(ps, 1, property);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Property p = new Property(property, uuid(rs, "world_uuid"), rs.getString("region_id"),
                        rs.getString("market_id"), rs.getString("class_key"), rs.getInt("capacity"),
                        rs.getInt("reference_rent_cents"), rs.getInt("asking_rent_cents"), uuid(rs, "owner_uuid"),
                        rs.getInt("ownership_epoch"), rs.getInt("condition_score"), rs.getBoolean("enabled"));
                if (p.capacity() < 1 || p.capacity() > 4 || rs.next()) throw new SQLException("Invalid purchase property");
                return new PropertyState(p.ownerUuid(), p.ownershipEpoch(), p.enabled(), p.conditionScore(), p);
            }
        }
    }

    private static String ownershipProblem(PropertyState property, PurchaseRequest q) {
        if (property == null) return "PROPERTY_MISSING";
        if (!property.enabled()) return "PROPERTY_DISABLED";
        if (!q.payerId().equals(property.owner()) || q.ownershipEpoch() != property.epoch()) return "OWNERSHIP_CHANGED";
        return null;
    }

    private String targetProblem(Connection c, PurchaseRequest q, PropertyState property,
                                 boolean blockWhileItemsHeld) throws SQLException {
        return switch (q.kind()) {
            case MAINTAIN -> property.condition() >= 100 ? "NO_MAINTENANCE_NEEDED" : null;
            case REPAIR -> {
                Instant at = clock.instant();
                yield IncidentRepair.targetProblem(c, q, at, economyClock.dayAt(at), blockWhileItemsHeld);
            }
            case UPGRADE -> {
                int level = 0;
                try (PreparedStatement ps = prepare(c,
                        "SELECT level FROM npc_upgrades WHERE property_id=? AND upgrade_type=? FOR UPDATE")) {
                    id(ps, 1, q.propertyId());
                    ps.setString(2, q.upgrade().name());
                    try (ResultSet rs = ps.executeQuery()) { if (rs.next()) level = rs.getInt(1); }
                }
                yield level != q.targetLevel() - 1 ? "UPGRADE_TARGET_CHANGED" : null;
            }
            case EVICT -> {
                if (q.eviction() == null) yield "LEGACY_EVICTION_UNBOUND";
                if (q.day().isAfter(economyClock.today())) yield "EVICTION_QUOTE_IN_FUTURE";
                EvictionQuote current = evictionTarget(c, q.propertyId(), q.tenantId());
                yield current == null ? "TENANT_MOVED_OR_MISSING"
                        : !current.equals(q.eviction()) ? "TENANCY_CHANGED" : null;
            }
            case INSURANCE -> {
                LocalDate today = economyClock.today();
                yield q.insurance().capacity() != property.property().capacity() ? "INSURANCE_CAPACITY_CHANGED"
                        : q.day().isAfter(today) ? "INSURANCE_QUOTE_IN_FUTURE"
                        : !insurance.canPurchase(c, q.propertyId(), today) ? "INSURANCE_TERM_RESERVED" : null;
            }
        };
    }

    private void apply(Connection c, Receipt receipt, PropertyState property) throws SQLException {
        PurchaseRequest q = receipt.request();
        switch (q.kind()) {
            case INSURANCE -> {
                Instant at = clock.instant();
                LocalDate completionDay = economyClock.dayAt(at);
                if (completionDay.isBefore(q.day())) throw new SQLException("Insurance completion precedes quote day");
                // confirmedPaid was checked above, on this connection, before any coverage exists.
                var result = insurance.applyPaid(c, receipt.eventId(), property.property(), at, completionDay);
                if (result.receipt().eventId() != receipt.eventId()
                        || result.receipt().priceCents() != receipt.quotedCents())
                    throw new SQLException("Insurance application binding differs");
            }
            case REPAIR -> {
                Instant at = clock.instant();
                if (at.isBefore(repairPaidAt(c, receipt.eventId())))
                    throw new SQLException("Repair completion precedes confirmed payment");
                LocalDate completionDay = economyClock.dayAt(at);
                if (completionDay.isBefore(q.day())) throw new SQLException("Repair completion precedes quote day");
                IncidentRepair.apply(c, receipt, at, completionDay);
            }
            case MAINTAIN -> {
                try (PreparedStatement ps = prepare(c,
                        "UPDATE npc_properties SET condition_score=LEAST(100,condition_score+5),row_version=row_version+1 WHERE "
                        + "property_id=?")) {
                    id(ps, 1, q.propertyId());
                    one(ps.executeUpdate());
                }
            }
            case EVICT -> {
                if (clock.instant().isBefore(repairPaidAt(c, receipt.eventId())))
                    throw new SQLException("Eviction completion precedes confirmed payment");
                if (!TenantRepository.vacate(c, q.tenantId(), q.propertyId(), clock, receipt.eventId()))
                    throw new SQLException("Eviction tenancy changed");
            }
            case UPGRADE -> {
                // Level-zero rows may already exist; distinguish them from an absent row.
                int updated;
                try (PreparedStatement ps = prepare(c, """
                        UPDATE npc_upgrades SET level=?,purchase_cost_cents=purchase_cost_cents+?,installed_at=CURRENT_TIMESTAMP(3)
                        WHERE property_id=? AND upgrade_type=? AND level=?
                        """)) {
                    ps.setInt(1, q.targetLevel());
                    ps.setLong(2, receipt.quotedCents());
                    id(ps, 3, q.propertyId());
                    ps.setString(4, q.upgrade().name());
                    ps.setInt(5, q.targetLevel() - 1);
                    updated = ps.executeUpdate();
                }
                if (updated == 0 && q.targetLevel() == 1) {
                    try (PreparedStatement ps = prepare(c,
                            "INSERT INTO npc_upgrades(property_id,upgrade_type,level,purchase_cost_cents,installed_at) VALUES "
                            + "(?,?,1,?,CURRENT_TIMESTAMP(3))")) {
                        id(ps, 1, q.propertyId());
                        ps.setString(2, q.upgrade().name());
                        ps.setLong(3, receipt.quotedCents());
                        one(ps.executeUpdate());
                    }
                } else one(updated);
            }
        }
    }

    private static Receipt finish(Connection c, Receipt receipt, State state, String outcome) throws SQLException {
        try (PreparedStatement ps = prepare(c,
                "UPDATE npc_purchase_intents SET state=?,outcome_code=?,completed_at=CURRENT_TIMESTAMP(3) WHERE event_id=? AND "
                + "state='WAITING'")) {
            ps.setString(1, state.name());
            ps.setString(2, outcome);
            ps.setLong(3, receipt.eventId());
            one(ps.executeUpdate());
        }
        return new Receipt(receipt.eventId(), receipt.request(), receipt.quotedCents(), state, outcome);
    }

    private static Instant repairPaidAt(Connection c, long eventId) throws SQLException {
        try (PreparedStatement ps = prepare(c,
                "SELECT finalized_at FROM npc_economy_events WHERE event_id=? AND state='PAID' FOR UPDATE")) {
            ps.setLong(1, eventId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("Repair payment timestamp missing");
                LocalDateTime at = rs.getObject("finalized_at", LocalDateTime.class);
                if (at == null || at.getYear() < 1 || at.getYear() > 9999)
                    throw new SQLException("Repair payment timestamp invalid");
                return at.toInstant(ZoneOffset.UTC);
            }
        }
    }

    private static Optional<Receipt> byEvent(Connection c, long id, boolean lock) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_purchase_intents WHERE event_id=?" + (lock ? " FOR UPDATE" : ""))) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(readReceipt(rs)) : Optional.empty(); }
        }
    }
    private static Optional<Receipt> byRequest(Connection c, UUID request) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_purchase_intents WHERE request_id=?")) {
            id(ps, 1, request);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(readReceipt(rs)) : Optional.empty(); }
        }
    }
    private static Optional<Receipt> lastAction(Connection c, PurchaseRequest q) throws SQLException {
        try (PreparedStatement ps = prepare(c, """
                SELECT * FROM npc_purchase_intents WHERE property_id=? AND ownership_epoch=? AND action_key=?
                ORDER BY event_id DESC LIMIT 1
                """)) {
            id(ps, 1, q.propertyId());
            ps.setInt(2, q.ownershipEpoch());
            ps.setString(3, q.actionKey());
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(readReceipt(rs)) : Optional.empty(); }
        }
    }
    /** Reads one stored purchase row. The caller still verifies its event and payment authority. */
    public static Receipt readReceipt(ResultSet rs) throws SQLException {
        try {
            PurchaseRequest.Kind kind = PurchaseRequest.Kind.valueOf(rs.getString("kind"));
            Long price = configuredPrice(rs);
            String upgrade = rs.getString("upgrade_type");
            Integer level = rs.getObject("target_level") == null ? null : rs.getInt("target_level");
            Long incident = rs.getObject("repair_incident_id") == null ? null : rs.getLong("repair_incident_id");
            String repairType = rs.getString("repair_type"), repairHash = rs.getString("repair_snapshot_sha256");
            RepairQuote repair = incident == null ? null : new RepairQuote(incident,
                    IncidentType.valueOf(repairType), repairHash, kind == PurchaseRequest.Kind.REPAIR ? price : null);
            if (incident == null && (repairType != null || repairHash != null))
                throw new IllegalArgumentException("Incomplete repair receipt");
            Long tenancy = rs.getObject("evict_tenancy_revision") == null ? null : rs.getLong("evict_tenancy_revision");
            Integer slot = rs.getObject("evict_slot") == null ? null : rs.getInt("evict_slot");
            if ((tenancy == null) != (slot == null)) throw new IllegalArgumentException("Incomplete eviction receipt");
            EvictionQuote eviction = tenancy == null ? null : new EvictionQuote(tenancy, slot);
            PurchaseRequest.InsuranceQuote insurance = kind == PurchaseRequest.Kind.INSURANCE
                    ? new PurchaseRequest.InsuranceQuote(rs.getInt("insurance_capacity")) : null;
            PurchaseRequest request = new PurchaseRequest(uuid(rs, "request_id"), uuid(rs, "property_id"), uuid(rs, "payer_uuid"),
                    rs.getInt("ownership_epoch"), kind,
                    upgrade == null ? null : UpgradeType.valueOf(upgrade), level, uuid(rs, "tenant_id"),
                    LocalDate.parse(rs.getString("economy_day")), repair, eviction, insurance, price);
            if (!request.actionKey().equals(rs.getString("action_key")) || rs.getLong("quoted_amount_cents") <= 0)
                throw new IllegalArgumentException("Invalid action or quote");
            return new Receipt(rs.getLong("event_id"), request, rs.getLong("quoted_amount_cents"), State.valueOf(rs.getString("state")),
                    rs.getString("outcome_code"));
        } catch (IllegalArgumentException | NullPointerException | DateTimeException error) {
            throw new SQLException("Invalid purchase receipt", error);
        }
    }
    private static Long configuredPrice(ResultSet rs) throws SQLException {
        var columns = rs.getMetaData();
        for (int i = 1; i <= columns.getColumnCount(); i++) {
            if (!"configured_price_cents".equals(columns.getColumnLabel(i))) continue;
            var price = rs.getBigDecimal(i);
            try { return price == null ? null : price.longValueExact(); }
            catch (ArithmeticException invalid) { throw new SQLException("Invalid configured purchase price", invalid); }
        }
        return null;
    }
    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        byte[] bytes = rs.getBytes(column);
        return bytes == null ? null : Uuids.fromBytes(bytes);
    }
    private static void id(PreparedStatement ps, int index, UUID id) throws SQLException {
        if (id == null) ps.setNull(index, Types.BINARY);
        else ps.setBytes(index, Uuids.toBytes(id));
    }
    private static void one(int rows) throws SQLException {
        if (rows != 1) throw new SQLException("Purchase write did not affect exactly one row");
    }
    private static PreparedStatement prepare(Connection c, String sql) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        try {
            ps.setQueryTimeout(5);
            return ps;
        } catch (SQLException error) {
            ps.close();
            throw error;
        }
    }
    @FunctionalInterface private interface Transaction<T> { T run(Connection connection) throws SQLException; }
    private <T> T transaction(Transaction<T> work) throws SQLException {
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
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

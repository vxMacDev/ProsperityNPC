package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.clock.EconomyClock;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.economy.EventSource;
import com.prosperitysmp.npc.economy.PurchaseRequest;
import com.prosperitysmp.npc.economy.RepairQuote;
import com.prosperitysmp.npc.economy.TransferJournal;
import com.prosperitysmp.npc.economy.TransferProof;
import com.prosperitysmp.npc.economy.UtilityBill;
import com.prosperitysmp.npc.formulas.IncidentType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * SQL-only consent accounting, not payment authorization. Caller owns live ARM/permission,
 * ownership and financial fences, and provider/lifecycle authority. Only a fresh reservation
 * followed by a fresh binding may proceed to the existing transfer's fresh Start authority.
 * Crash/replay, missing receipts and unknown dispatch results never return spending budget.
 * This repository never writes existing receipt, money, property or gameplay tables.
 */
public final class AutoCareRepository {
    public enum Action { UTILITY, CASH_REPAIR, MAINTAIN }
    public enum State { RESERVED, BOUND, SPENT, DECLINED, HELD }
    public enum ReserveStatus { RESERVED, REPLAY, DISABLED, NOT_ALLOWED, LIMIT, TARGET_UNAVAILABLE }
    public record Consent(UUID propertyId, UUID ownerUuid, int ownershipEpoch, long revision,
                          boolean enabled, long dailyCeilingCents, Set<Action> actions) {
        public Consent { actions = Set.copyOf(actions); }
    }
    public record Claim(UUID claimId, UUID propertyId, UUID ownerUuid, int ownershipEpoch,
                        long consentRevision, LocalDate day, Action action, Long incidentId,
                        long quotedCents, State state, Long eventId, Instant createdAt, String outcome) { }
    public record Reservation(ReserveStatus status, Claim claim) {
        public boolean newlyReserved() { return status == ReserveStatus.RESERVED; }
    }
    public record Binding(boolean newlyBound, Claim claim) { }
    private record Mirror(UUID owner, int epoch, boolean enabled, int condition) { }
    private record Payment(String domainState, String ledgerState, Long paidCents, String key, EventSource source) { }
    private enum Proof { PENDING, PAID, DECLINED, UNCERTAIN }
    private static final int MAX_FEED = 100;
    private final Database database;
    private final UUID reserve;
    private final EconomyClock days;

    public AutoCareRepository(Database database, UUID reserve, Clock clock, ZoneId zone, int resetHour) {
        this.database = Objects.requireNonNull(database); this.reserve = Objects.requireNonNull(reserve);
        days = new EconomyClock(Objects.requireNonNull(clock), Objects.requireNonNull(zone), resetHour);
    }

    /** Explicit replacement consent. Equal settings are idempotent; old epochs remain inert history. */
    public Consent configure(UUID property, UUID owner, int epoch, boolean enabled,
                             long dailyCeilingCents, Set<Action> actions) throws SQLException {
        scope(property, owner, epoch); Objects.requireNonNull(actions);
        Set<Action> captured = Set.copyOf(actions);
        if (dailyCeilingCents < 0 || (enabled && (dailyCeilingCents == 0 || captured.isEmpty())))
            throw new IllegalArgumentException("Invalid auto-care consent ceiling/actions");
        return transaction(c -> {
            require(matches(mirror(c, property), owner, epoch, enabled), "Auto-care owner unavailable");
            Consent old = consent(c, property, epoch);
            if (old != null) {
                require(old.ownerUuid().equals(owner), "Auto-care consent owner differs");
                if (old.enabled() == enabled && old.dailyCeilingCents() == dailyCeilingCents && old.actions().equals(captured)) return old;
                require(old.revision() < Long.MAX_VALUE, "Auto-care consent revision exhausted");
                try (var ps = prepare(c, "UPDATE npc_auto_care_consents SET enabled=?,daily_ceiling_cents=?,action_mask=?,"
                        + "revision=revision+1,updated_at=UTC_TIMESTAMP(3) WHERE property_id=? AND ownership_epoch=? AND revision=?",
                        enabled, dailyCeilingCents, mask(captured), property, epoch, old.revision())) { one(c, ps); }
            } else {
                try (var ps = prepare(c, "INSERT INTO npc_auto_care_consents(property_id,ownership_epoch,owner_uuid,enabled,"
                        + "daily_ceiling_cents,action_mask) VALUES(?,?,?,?,?,?)", property, epoch, owner,
                        enabled, dailyCeilingCents, mask(captured))) { one(c, ps); }
            }
            return Objects.requireNonNull(consent(c, property, epoch));
        });
    }

    public Optional<Consent> current(UUID property, UUID owner, int epoch) throws SQLException {
        scope(property, owner, epoch);
        return transaction(c -> matches(mirror(c, property), owner, epoch, false)
                ? Optional.ofNullable(consent(c, property, epoch)) : Optional.empty());
    }

    /** At most one slot/action/property/day, even after decline, sale or disable/re-enable. */
    public Reservation reserve(UUID property, UUID owner, int epoch, LocalDate day,
                               Action action, Long incidentId, long quotedCents) throws SQLException {
        scope(property, owner, epoch); day(day); quote(action, incidentId, quotedCents);
        return transaction(c -> {
            Mirror p = mirror(c, property);
            if (!matches(p, owner, epoch, true)) return refused(ReserveStatus.TARGET_UNAVAILABLE);
            Claim old = actionClaim(c, property, day, action);
            if (old != null) {
                require(old.ownerUuid().equals(owner) && old.ownershipEpoch() == epoch
                        && Objects.equals(old.incidentId(), incidentId) && old.quotedCents() == quotedCents,
                        "Auto-care semantic slot payload differs");
                return new Reservation(ReserveStatus.REPLAY, old);
            }
            if (!day.equals(days.today())) return refused(ReserveStatus.TARGET_UNAVAILABLE);
            Consent consent = consent(c, property, epoch);
            if (consent == null || !consent.enabled()) return refused(ReserveStatus.DISABLED);
            require(consent.ownerUuid().equals(owner), "Auto-care consent owner differs");
            if (!consent.actions().contains(action)) return refused(ReserveStatus.NOT_ALLOWED);
            if (!target(c, p, property, action, incidentId, quotedCents)) return refused(ReserveStatus.TARGET_UNAVAILABLE);
            long retained = retained(c, property, epoch, day);
            if (quotedCents > consent.dailyCeilingCents() || retained > consent.dailyCeilingCents() - quotedCents)
                return refused(ReserveStatus.LIMIT);
            UUID id = claimId(property, owner, epoch, day, action, incidentId);
            try (var ps = prepare(c, """
                    INSERT INTO npc_auto_care_claims(claim_id,property_id,owner_uuid,ownership_epoch,consent_revision,
                      economy_day,action,incident_id,quoted_cents) VALUES(?,?,?,?,?,?,?,?,?)
                    """, id, property, owner, epoch, consent.revision(), day, action.name(), incidentId, quotedCents)) { one(c, ps); }
            require(day.equals(days.today()), "Auto-care reservation crossed economy reset");
            return new Reservation(ReserveStatus.RESERVED, required(c, id));
        });
    }

    /**
     * Parent prepares the EXISTING domain receipt with requestId=claimId, then binds BEFORE
     * any debit intent. No gap authorizes money calls. A repeated binding never returns fresh authority.
     */
    public Binding bind(UUID claimId, long eventId) throws SQLException {
        Objects.requireNonNull(claimId);
        if (eventId <= 0) throw new IllegalArgumentException("Invalid auto-care event");
        return transaction(c -> {
            Claim claim = locked(c, claimId);
            Payment payment = payment(c, claim, eventId);
            if (claim.eventId() != null) {
                require(claim.eventId() == eventId, "Auto-care payment binding conflicts");
                return new Binding(false, claim);
            }
            require(claim.state() == State.RESERVED, "Held auto-care claim cannot bind a new payment");
            require(matches(mirror(c, claim.propertyId()), claim.ownerUuid(), claim.ownershipEpoch(), true), "Auto-care owner changed");
            Consent consent = consent(c, claim.propertyId(), claim.ownershipEpoch());
            require(consent != null && consent.enabled() && consent.ownerUuid().equals(claim.ownerUuid())
                    && consent.revision() == claim.consentRevision() && consent.actions().contains(claim.action())
                    && retained(c, claim.propertyId(), claim.ownershipEpoch(), claim.day()) <= consent.dailyCeilingCents()
                    && claim.day().equals(days.today()), "Auto-care consent changed before binding");
            require("WAITING".equals(payment.domainState()) && "PENDING".equals(payment.ledgerState())
                    && payment.paidCents() == null && proof(c, claim, eventId, payment) == Proof.PENDING,
                    "Auto-care binding must precede money intent");
            try (var ps = prepare(c, "UPDATE npc_auto_care_claims SET state='BOUND',event_id=?,updated_at=UTC_TIMESTAMP(3) "
                    + "WHERE claim_id=? AND state='RESERVED' AND event_id IS NULL", eventId, claimId)) { one(c, ps); }
            return new Binding(true, required(c, claimId));
        });
    }

    /** Historical accepted obligations reconcile even after consent/ownership changes. Never dispatches. */
    public Claim reconcile(UUID claimId) throws SQLException {
        Objects.requireNonNull(claimId);
        return transaction(c -> {
            Claim claim = locked(c, claimId);
            if (claim.eventId() == null) return claim;
            Payment payment = payment(c, claim, claim.eventId());
            Proof proof = proof(c, claim, claim.eventId(), payment);
            State next;
            String outcome;
            if (proof == Proof.PAID && (claim.action() == Action.UTILITY ? "PAID" : "APPLIED").equals(payment.domainState())) {
                next = State.SPENT; outcome = "PAYMENT_CONFIRMED";
            } else if (proof == Proof.DECLINED && "DECLINED".equals(payment.domainState())) {
                next = State.DECLINED; outcome = "NO_MOVEMENT_CONFIRMED";
            } else if (proof == Proof.PENDING && "WAITING".equals(payment.domainState())) {
                require(claim.state() != State.SPENT && claim.state() != State.DECLINED,
                        "Auto-care terminal evidence changed");
                return claim;
            }
            else { next = State.HELD; outcome = "PAYMENT_OR_EFFECT_UNCERTAIN"; }
            require((claim.state() != State.SPENT || next == State.SPENT)
                    && (claim.state() != State.DECLINED || next == State.DECLINED), "Auto-care terminal evidence changed");
            return finish(c, claim, next, outcome);
        });
    }

    /** Unknown preparation/dispatch/commit: retain the whole quote, including unbound reservations. */
    public Claim hold(UUID claimId) throws SQLException {
        Objects.requireNonNull(claimId);
        return transaction(c -> {
            Claim claim = locked(c, claimId);
            return claim.state() == State.SPENT || claim.state() == State.DECLINED ? claim
                    : finish(c, claim, State.HELD, "COORDINATOR_UNCERTAIN");
        });
    }

    public Optional<Claim> find(UUID claimId) throws SQLException {
        Objects.requireNonNull(claimId);
        try (Connection c = database.getConnection()) { return Optional.ofNullable(claim(c, claimId, false)); }
    }

    /** Binary UUID cursor prevents a fixed first page from starving larger portfolios. */
    public List<Consent> enabled(UUID afterProperty, int limit) throws SQLException {
        limit(limit);
        try (Connection c = database.getConnection(); var ps = prepare(c, """
                SELECT a.* FROM npc_auto_care_consents a JOIN npc_properties p ON p.property_id=a.property_id
                  AND p.owner_uuid=a.owner_uuid AND p.ownership_epoch=a.ownership_epoch
                WHERE a.enabled=TRUE AND p.enabled=TRUE
                """ + (afterProperty == null ? "" : " AND a.property_id>? ")
                + " ORDER BY a.property_id LIMIT ? ROWS EXAMINED 8192",
                afterProperty == null ? new Object[]{limit} : new Object[]{afterProperty, limit})) {
            var rows = new ArrayList<Consent>();
            try (var rs = ps.executeQuery()) { while (next(c, ps, rs)) rows.add(consent(rs)); }
            return List.copyOf(rows);
        }
    }
    public List<Consent> enabled(int limit) throws SQLException { return enabled(null, limit); }

    /** Includes HELD and old incarnations for truthful reconciliation, not fresh dispatch. */
    public List<Claim> pending(UUID afterClaim, int limit) throws SQLException {
        limit(limit);
        try (Connection c = database.getConnection(); var ps = prepare(c, "SELECT * FROM npc_auto_care_claims "
                + "WHERE state IN ('RESERVED','BOUND','HELD') " + (afterClaim == null ? "" : "AND claim_id>? ")
                + "ORDER BY claim_id LIMIT ? ROWS EXAMINED 8192",
                afterClaim == null ? new Object[]{limit} : new Object[]{afterClaim, limit})) {
            var rows = new ArrayList<Claim>();
            try (var rs = ps.executeQuery()) { while (next(c, ps, rs)) rows.add(claim(rs)); }
            return List.copyOf(rows);
        }
    }
    public List<Claim> pending(int limit) throws SQLException { return pending(null, limit); }

    public static UUID claimId(UUID property, UUID owner, int epoch, LocalDate day, Action action, Long incidentId) {
        scope(property, owner, epoch); day(day); Objects.requireNonNull(action);
        if ((action == Action.CASH_REPAIR) != (incidentId != null) || (incidentId != null && incidentId <= 0))
            throw new IllegalArgumentException("Invalid auto-care incident");
        return UUID.nameUUIDFromBytes(("auto-care-v1|" + property + "|" + owner + "|" + epoch + "|"
                + day + "|" + action + "|" + incidentId).getBytes(StandardCharsets.US_ASCII));
    }

    private long retained(Connection c, UUID property, int epoch, LocalDate day) throws SQLException {
        List<Claim> claims = new ArrayList<>();
        try (var ps = prepare(c, "SELECT * FROM npc_auto_care_claims WHERE property_id=? AND ownership_epoch=? "
                + "AND economy_day=? LIMIT 4 FOR UPDATE", property, epoch, day); var rs = ps.executeQuery()) {
            while (next(c, ps, rs)) { require(claims.size() < 3, "Auto-care daily slot overflow"); claims.add(claim(rs)); }
        }
        long total = 0;
        for (Claim claim : claims) {
            if (claim.state() == State.DECLINED) {
                Payment payment = payment(c, claim, Objects.requireNonNull(claim.eventId()));
                require("DECLINED".equals(payment.domainState()) && proof(c, claim, claim.eventId(), payment) == Proof.DECLINED,
                        "Auto-care released budget lacks no-movement proof");
            } else total = Math.addExact(total, claim.quotedCents());
        }
        return total;
    }

    private static boolean target(Connection c, Mirror property, UUID propertyId, Action action, Long incidentId, long cents)
            throws SQLException {
        if (action == Action.MAINTAIN) return property.condition() < 100;
        if (action == Action.UTILITY) return true; // Exact frozen bill is verified at binding, before any money intent.
        try (var ps = prepare(c, "SELECT * FROM npc_incidents WHERE property_id=? AND incident_id=? FOR UPDATE", propertyId, incidentId);
             var rs = ps.executeQuery()) {
            if (!next(c, ps, rs)) return false;
            IncidentType type = value(IncidentType.class, rs.getString("incident_type"));
            require(number(rs, "cash_repair_cents", 1, Long.MAX_VALUE) == type.cashRepairCents()
                    && rs.getInt("condition_damage") == type.conditionDamage()
                    && type.itemRepairSpec().equals(rs.getString("item_repair_spec")), "Auto-care incident terms differ");
            boolean available = rs.getObject("resolved_at") == null && number(rs, "prevented", 0, 1) == 0 && cents == type.cashRepairCents();
            require(!next(c, ps, rs), "Duplicate auto-care incident"); return available;
        }
    }

    private Payment payment(Connection c, Claim claim, long eventId) throws SQLException {
        String ledger, key; Long paid; EventSource source = claim.action() == Action.UTILITY ? EventSource.UTILITY : EventSource.REPAIR;
        try (var ps = prepare(c, "SELECT * FROM npc_economy_events WHERE event_id=? FOR UPDATE", eventId); var rs = ps.executeQuery()) {
            require(next(c, ps, rs), "Auto-care payment event missing");
            require(claim.propertyId().equals(uuid(rs, "property_id")) && claim.ownerUuid().equals(uuid(rs, "counterparty_uuid"))
                    && claim.ownerUuid().equals(uuid(rs, "benefit_unit_id")) && claim.ownershipEpoch() == number(rs, "ownership_epoch", 0, Integer.MAX_VALUE)
                    && claim.day().toString().equals(rs.getString("economy_day")) && source.key().equals(rs.getString("source"))
                    && source.direction().moneyFlowKey().equals(rs.getString("direction"))
                    && claim.quotedCents() == number(rs, "requested_amount_cents", 1, Long.MAX_VALUE), "Auto-care event binding differs");
            ledger = rs.getString("state"); key = rs.getString("idempotency_key"); paid = nullableLong(rs, "paid_amount_cents");
            require(!next(c, ps, rs), "Duplicate auto-care event");
        }
        String domain;
        if (claim.action() == Action.UTILITY) {
            try (var ps = prepare(c, """
                    SELECT p.request_id,p.state,b.* FROM npc_utility_payments p JOIN npc_utility_bills b
                      ON b.property_id=p.property_id AND b.economy_day=p.economy_day
                    WHERE p.event_id=? FOR UPDATE
                    """, eventId); var rs = ps.executeQuery()) {
                require(next(c, ps, rs), "Auto-care utility receipt missing");
                UtilityBill bill = new UtilityBill(uuid(rs, "property_id"), uuid(rs, "owner_uuid"), rs.getInt("ownership_epoch"),
                        rs.getLong("property_revision"), LocalDate.parse(rs.getString("economy_day")), rs.getInt("capacity"),
                        rs.getInt("occupied_tenants"), rs.getInt("installed_upgrade_levels"), instant(rs, "issued_at"),
                        rs.getString("clock_zone"), rs.getInt("reset_hour"));
                require(claim.claimId().equals(uuid(rs, "request_id")) && claim.propertyId().equals(bill.propertyId())
                        && claim.ownerUuid().equals(bill.ownerId()) && claim.ownershipEpoch() == bill.ownershipEpoch()
                        && claim.day().equals(bill.day()) && claim.quotedCents() == bill.cents()
                        && bill.cents() == number(rs, "amount_cents", 1, Long.MAX_VALUE)
                        && bill.fingerprint().equals(rs.getString("payload_sha256"))
                        && bill.periodStart().equals(instant(rs, "period_start")) && bill.dueAt().equals(instant(rs, "due_at"))
                        && ("utility|" + claim.claimId()).equals(key), "Auto-care utility payload differs");
                domain = rs.getString("state"); require(!next(c, ps, rs), "Duplicate auto-care utility receipt");
            }
        } else {
            try (var ps = prepare(c, "SELECT * FROM npc_purchase_intents WHERE event_id=? FOR UPDATE", eventId); var rs = ps.executeQuery()) {
                require(next(c, ps, rs), "Auto-care purchase receipt missing");
                require(rs.getObject("upgrade_type") == null && rs.getObject("target_level") == null && rs.getBytes("tenant_id") == null
                        && rs.getObject("evict_tenancy_revision") == null && rs.getObject("evict_slot") == null, "Auto-care purchase action differs");
                RepairQuote repair = claim.action() == Action.CASH_REPAIR ? new RepairQuote(number(rs, "repair_incident_id", 1, Long.MAX_VALUE),
                        value(IncidentType.class, rs.getString("repair_type")), rs.getString("repair_snapshot_sha256")) : null;
                if (repair == null) require(rs.getObject("repair_incident_id") == null && rs.getString("repair_type") == null
                        && rs.getString("repair_snapshot_sha256") == null, "Auto-care maintenance target differs");
                PurchaseRequest q = new PurchaseRequest(uuid(rs, "request_id"), uuid(rs, "property_id"), uuid(rs, "payer_uuid"),
                        rs.getInt("ownership_epoch"), value(PurchaseRequest.Kind.class, rs.getString("kind")), null, null, null,
                        LocalDate.parse(rs.getString("economy_day")), repair);
                require(q.kind() == (claim.action() == Action.CASH_REPAIR ? PurchaseRequest.Kind.REPAIR : PurchaseRequest.Kind.MAINTAIN)
                        && claim.claimId().equals(q.requestId()) && claim.propertyId().equals(q.propertyId())
                        && claim.ownerUuid().equals(q.payerId()) && claim.ownershipEpoch() == q.ownershipEpoch() && claim.day().equals(q.day())
                        && (repair == null || Objects.equals(claim.incidentId(), repair.incidentId()))
                        && claim.quotedCents() == q.cents() && q.cents() == number(rs, "quoted_amount_cents", 1, Long.MAX_VALUE)
                        && q.eventKey().equals(key) && q.actionKey().equals(rs.getString("action_key")), "Auto-care purchase payload differs");
                domain = rs.getString("state"); require(!next(c, ps, rs), "Duplicate auto-care purchase receipt");
            }
        }
        return new Payment(domain, ledger, paid, key, source);
    }

    /** TransferProof is reused; accounts and the journal's exact v1 request hash are checked here. */
    private Proof proof(Connection c, Claim claim, long eventId, Payment payment) throws SQLException {
        try (var ps = prepare(c, "SELECT * FROM npc_transfer_attempts WHERE event_id=? FOR UPDATE", eventId); var rs = ps.executeQuery()) {
            if (!next(c, ps, rs)) {
                require("PENDING".equals(payment.ledgerState()) && payment.paidCents() == null, "Auto-care transfer evidence missing");
                return Proof.PENDING;
            }
            var handle = new TransferJournal.Handle(uuid(rs, "attempt_id"), eventId, uuid(rs, "run_id"), rs.getString("provider_sha256"));
            TransferJournal.State state = value(TransferJournal.State.class, rs.getString("state"));
            boolean creditStarted = rs.getObject("credit_intent_at") != null;
            String hash = sha("transfer-v1|" + eventId + "|" + payment.source() + "|" + claim.propertyId() + "|" + claim.ownerUuid()
                    + "|" + claim.ownershipEpoch() + "|" + claim.ownerUuid() + "|" + claim.day() + "|" + claim.quotedCents() + "|" + payment.key());
            require(claim.ownerUuid().equals(uuid(rs, "payer_uuid")) && reserve.equals(uuid(rs, "payee_uuid"))
                    && claim.quotedCents() == number(rs, "amount_cents", 1, Long.MAX_VALUE) && hash.equals(rs.getString("request_sha256")),
                    "Auto-care transfer binding differs");
            require(!next(c, ps, rs), "Duplicate auto-care transfer");
            var debit = observation(c, handle.attemptId(), TransferProof.Leg.DEBIT);
            var credit = observation(c, handle.attemptId(), TransferProof.Leg.CREDIT);
            boolean confirmedDebit = TransferProof.evaluate(claim.quotedCents(), TransferProof.Leg.DEBIT, debit) == TransferProof.LegOutcome.CONFIRMED;
            String expectedLedger = switch (state) { case PAID -> "PAID"; case DECLINED, COMPENSATED -> "FAILED";
                case AMBIGUOUS -> "AMBIGUOUS"; default -> "CLAIMED"; };
            require(expectedLedger.equals(payment.ledgerState())
                    && (state != TransferJournal.State.PAID || Objects.equals(payment.paidCents(), claim.quotedCents()))
                    && ((state != TransferJournal.State.DECLINED && state != TransferJournal.State.COMPENSATED) || Objects.equals(payment.paidCents(), 0L))
                    && ((state == TransferJournal.State.PAID || state == TransferJournal.State.DECLINED || state == TransferJournal.State.COMPENSATED)
                        || payment.paidCents() == null), "Auto-care transfer ledger phase differs");
            return switch (state) {
                case PAID -> {
                    require(creditStarted && TransferProof.evaluateTransfer(claim.quotedCents(), debit, credit) == TransferProof.TransferOutcome.PAID,
                            "Auto-care paid proof missing"); yield Proof.PAID;
                }
                case DECLINED -> {
                    require(!creditStarted && credit == null && TransferProof.evaluate(claim.quotedCents(), TransferProof.Leg.DEBIT, debit)
                            == TransferProof.LegOutcome.NO_MOVEMENT, "Auto-care decline proof missing"); yield Proof.DECLINED;
                }
                case DEBIT_INTENT -> { require(!creditStarted && debit == null && credit == null, "Invalid auto-care debit phase"); yield Proof.PENDING; }
                case CREDIT_READY, CREDIT_INTENT -> {
                    require(confirmedDebit && credit == null && creditStarted == (state == TransferJournal.State.CREDIT_INTENT),
                            "Invalid auto-care credit phase"); yield Proof.PENDING;
                }
                case AMBIGUOUS -> {
                    require((!creditStarted || confirmedDebit) && (credit == null || (creditStarted && confirmedDebit)),
                            "Invalid auto-care uncertain evidence"); yield Proof.UNCERTAIN;
                }
                case COMPENSATED -> Proof.UNCERTAIN; // Refund is not proof of NO original movement; no automatic budget release.
            };
        }
    }

    private static TransferProof.Observation observation(Connection c, UUID attempt, TransferProof.Leg leg) throws SQLException {
        try (var ps = prepare(c, "SELECT * FROM npc_transfer_observations WHERE attempt_id=? AND leg=? FOR UPDATE", attempt, leg.name());
             var rs = ps.executeQuery()) {
            if (!next(c, ps, rs)) return null;
            var result = new TransferProof.Observation(number(rs, "response_present", 0, 1) == 1,
                    number(rs, "response_success", 0, 1) == 1, dollars(rs, "reported_bits"), dollars(rs, "before_bits"),
                    dollars(rs, "after_bits"), number(rs, "threw", 0, 1) == 1);
            require(!next(c, ps, rs), "Duplicate auto-care observation"); return result;
        }
    }
    private static Double dollars(ResultSet rs, String column) throws SQLException {
        Long bits = nullableLong(rs, column); return bits == null ? null : Double.longBitsToDouble(bits);
    }
    private static Claim locked(Connection c, UUID id) throws SQLException {
        Claim observed = claim(c, id, false); require(observed != null, "Auto-care claim missing");
        mirror(c, observed.propertyId()); Claim current = required(c, id);
        require(observed.propertyId().equals(current.propertyId()), "Auto-care claim property changed"); return current;
    }
    private static Claim required(Connection c, UUID id) throws SQLException {
        Claim claim = claim(c, id, true); require(claim != null, "Auto-care claim missing"); return claim;
    }
    private static Claim claim(Connection c, UUID id, boolean lock) throws SQLException {
        try (var ps = prepare(c, "SELECT * FROM npc_auto_care_claims WHERE claim_id=?" + (lock ? " FOR UPDATE" : ""), id);
             var rs = ps.executeQuery()) {
            if (!next(c, ps, rs)) return null; Claim claim = claim(rs);
            require(!next(c, ps, rs), "Duplicate auto-care claim"); return claim;
        }
    }
    private static Claim actionClaim(Connection c, UUID property, LocalDate day, Action action) throws SQLException {
        try (var ps = prepare(c, "SELECT * FROM npc_auto_care_claims WHERE property_id=? AND economy_day=? AND action=? FOR UPDATE",
                property, day, action.name()); var rs = ps.executeQuery()) {
            if (!next(c, ps, rs)) return null; Claim claim = claim(rs);
            require(!next(c, ps, rs), "Duplicate auto-care semantic slot"); return claim;
        }
    }
    private static Claim claim(ResultSet rs) throws SQLException {
        Claim claim = new Claim(uuid(rs, "claim_id"), uuid(rs, "property_id"), uuid(rs, "owner_uuid"),
                (int) number(rs, "ownership_epoch", 0, Integer.MAX_VALUE), number(rs, "consent_revision", 0, Long.MAX_VALUE),
                rs.getObject("economy_day", LocalDate.class), value(Action.class, rs.getString("action")), nullableLong(rs, "incident_id"),
                number(rs, "quoted_cents", 1, Long.MAX_VALUE), value(State.class, rs.getString("state")), nullableLong(rs, "event_id"),
                instant(rs, "created_at"), rs.getString("outcome_code"));
        quote(claim.action(), claim.incidentId(), claim.quotedCents());
        require(claim.claimId().equals(claimId(claim.propertyId(), claim.ownerUuid(), claim.ownershipEpoch(), claim.day(), claim.action(), claim.incidentId()))
                && (claim.eventId() == null || claim.eventId() > 0)
                && (claim.state() != State.RESERVED || (claim.eventId() == null && claim.outcome() == null))
                && (claim.state() != State.BOUND || (claim.eventId() != null && claim.outcome() == null))
                && (claim.state() != State.SPENT || (claim.eventId() != null && "PAYMENT_CONFIRMED".equals(claim.outcome())))
                && (claim.state() != State.DECLINED || (claim.eventId() != null && "NO_MOVEMENT_CONFIRMED".equals(claim.outcome())))
                && (claim.state() != State.HELD || claim.outcome() != null), "Invalid auto-care claim binding/state");
        return claim;
    }
    private static Consent consent(Connection c, UUID property, int epoch) throws SQLException {
        try (var ps = prepare(c, "SELECT * FROM npc_auto_care_consents WHERE property_id=? AND ownership_epoch=? FOR UPDATE", property, epoch);
             var rs = ps.executeQuery()) {
            if (!next(c, ps, rs)) return null; Consent result = consent(rs);
            require(!next(c, ps, rs), "Duplicate auto-care consent"); return result;
        }
    }
    private static Consent consent(ResultSet rs) throws SQLException {
        int mask = (int) number(rs, "action_mask", 0, 7); var actions = EnumSet.noneOf(Action.class);
        for (Action action : Action.values()) if ((mask & bit(action)) != 0) actions.add(action);
        Consent result = new Consent(uuid(rs, "property_id"), uuid(rs, "owner_uuid"), (int) number(rs, "ownership_epoch", 0, Integer.MAX_VALUE),
                number(rs, "revision", 0, Long.MAX_VALUE), number(rs, "enabled", 0, 1) == 1,
                number(rs, "daily_ceiling_cents", 0, Long.MAX_VALUE), actions);
        require(!result.enabled() || (result.dailyCeilingCents() > 0 && !result.actions().isEmpty()), "Invalid auto-care enabled consent"); return result;
    }
    private static Mirror mirror(Connection c, UUID property) throws SQLException {
        try (var ps = prepare(c, "SELECT owner_uuid,ownership_epoch,enabled,condition_score FROM npc_properties WHERE property_id=? FOR UPDATE", property);
             var rs = ps.executeQuery()) {
            require(next(c, ps, rs), "Auto-care property missing");
            byte[] owner = rs.getBytes("owner_uuid");
            Mirror result = new Mirror(owner == null ? null : uuid(owner), (int) number(rs, "ownership_epoch", 0, Integer.MAX_VALUE),
                    number(rs, "enabled", 0, 1) == 1, (int) number(rs, "condition_score", 0, 100));
            require(!next(c, ps, rs), "Duplicate auto-care property"); return result;
        }
    }
    private static boolean matches(Mirror mirror, UUID owner, int epoch, boolean requireEnabled) {
        return owner.equals(mirror.owner()) && epoch == mirror.epoch() && (!requireEnabled || mirror.enabled());
    }
    private static Claim finish(Connection c, Claim claim, State state, String outcome) throws SQLException {
        if (claim.state() == state && Objects.equals(claim.outcome(), outcome)) return claim;
        try (var ps = prepare(c, "UPDATE npc_auto_care_claims SET state=?,outcome_code=?,updated_at=UTC_TIMESTAMP(3) WHERE claim_id=? AND state=?",
                state.name(), outcome, claim.claimId(), claim.state().name())) { one(c, ps); }
        return required(c, claim.claimId());
    }
    private static Reservation refused(ReserveStatus status) { return new Reservation(status, null); }
    private static int bit(Action action) { return switch (action) { case UTILITY -> 1; case CASH_REPAIR -> 2; case MAINTAIN -> 4; }; }
    private static int mask(Set<Action> actions) { int mask = 0; for (Action action : actions) mask |= bit(action); return mask; }
    private static void quote(Action action, Long incident, long cents) {
        Objects.requireNonNull(action);
        boolean valid = switch (action) { case UTILITY -> incident == null && cents >= 100 && cents <= 700;
            case MAINTAIN -> incident == null && cents == 1000;
            case CASH_REPAIR -> incident != null && incident > 0 && (cents == 2000 || cents == 3500 || cents == 5000); };
        if (!valid) throw new IllegalArgumentException("Invalid auto-care action/quote");
    }
    private static void scope(UUID property, UUID owner, int epoch) {
        Objects.requireNonNull(property); Objects.requireNonNull(owner);
        if (epoch < 0) throw new IllegalArgumentException("Invalid auto-care epoch");
    }
    private static void day(LocalDate day) {
        Objects.requireNonNull(day);
        if (day.getYear() < 1 || day.getYear() > 9998) throw new IllegalArgumentException("Invalid auto-care day");
    }
    private static void limit(int limit) { if (limit < 1 || limit > MAX_FEED) throw new IllegalArgumentException("Auto-care feed limit must be 1..100"); }
    private static String sha(String payload) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static UUID uuid(ResultSet rs, String column) throws SQLException { return uuid(rs.getBytes(column)); }
    private static UUID uuid(byte[] bytes) throws SQLException { require(bytes != null && bytes.length == 16, "Invalid auto-care UUID"); return Uuids.fromBytes(bytes); }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime at = rs.getObject(column, LocalDateTime.class); require(at != null && at.getYear() >= 1 && at.getYear() <= 9999, "Invalid auto-care timestamp");
        return at.toInstant(ZoneOffset.UTC);
    }
    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column) == null ? null : number(rs, column, Long.MIN_VALUE, Long.MAX_VALUE);
    }
    private static long number(ResultSet rs, String column, long min, long max) throws SQLException {
        var n = rs.getBigDecimal(column); require(n != null, "Missing auto-care numeric value");
        try { long value = n.longValueExact(); require(value >= min && value <= max, "Invalid auto-care numeric value"); return value; }
        catch (ArithmeticException failure) { throw new SQLException("Invalid auto-care numeric precision", failure); }
    }
    private static <T extends Enum<T>> T value(Class<T> type, String text) throws SQLException {
        try { return Enum.valueOf(type, text); } catch (IllegalArgumentException | NullPointerException failure) { throw new SQLException("Invalid auto-care enum", failure); }
    }
    private static PreparedStatement prepare(Connection c, String sql, Object... values) throws SQLException {
        var ps = c.prepareStatement(sql);
        try {
            ps.setQueryTimeout(5);
            for (int i = 0; i < values.length; i++) {
                if (values[i] instanceof UUID id) ps.setBytes(i + 1, Uuids.toBytes(id)); else ps.setObject(i + 1, values[i]);
            }
            return ps;
        } catch (SQLException | RuntimeException failure) { ps.close(); throw failure; }
    }
    private static boolean next(Connection c, PreparedStatement ps, ResultSet rs) throws SQLException {
        warnings(c, ps, rs); boolean present = rs.next(); warnings(c, ps, rs); return present;
    }
    private static void warnings(Connection c, PreparedStatement ps, ResultSet rs) throws SQLException {
        require(c.getWarnings() == null && (ps == null || ps.getWarnings() == null) && (rs == null || rs.getWarnings() == null),
                "Auto-care query warning; partial data refused");
    }
    private static void one(Connection c, PreparedStatement ps) throws SQLException { require(ps.executeUpdate() == 1, "Auto-care write affected unexpected rows"); warnings(c, ps, null); }
    private static void require(boolean condition, String message) throws SQLException { if (!condition) throw new SQLException(message); }
    @FunctionalInterface private interface Work<T> { T run(Connection c) throws SQLException; }
    private <T> T transaction(Work<T> work) throws SQLException {
        try (Connection c = database.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED); c.setAutoCommit(false);
            try { T result = work.run(c); warnings(c, null, null); c.commit(); return result; }
            catch (SQLException | RuntimeException failure) {
                try { c.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                if (failure instanceof SQLException sql) throw sql;
                throw new SQLException("Invalid auto-care stored state", failure);
            }
        }
    }
}

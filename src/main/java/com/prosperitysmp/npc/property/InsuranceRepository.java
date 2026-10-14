package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.formulas.PropertyFormulas;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** SQL-only insurance effects. Every operation uses a caller-owned transaction; no money calls. */
public final class InsuranceRepository {
    public enum ApplyStatus { APPLIED, ALREADY_APPLIED }
    public enum PreventionStatus { PREVENTED, ALREADY_PREVENTED }
    public record CoverageReceipt(long eventId, UUID propertyId, UUID ownerUuid, int ownershipEpoch,
                                  int capacity, long priceCents, Instant paidAt, Instant appliedAt,
                                  LocalDate appliedDay, LocalDate startDay, LocalDate endDayExclusive) { }
    public record PreventionReceipt(long eventId, long incidentId, LocalDate economyDay, Instant preventedAt) { }
    public record CoverageView(CoverageReceipt receipt, Optional<PreventionReceipt> prevention,
                               Optional<CoverageReceipt> nextTerm) {
        public CoverageView(CoverageReceipt receipt, Optional<PreventionReceipt> prevention) {
            this(receipt, prevention, Optional.empty());
        }
        public boolean used() { return prevention.isPresent(); }
    }
    public record ApplyResult(ApplyStatus status, CoverageReceipt receipt) { }
    public record PreventionResult(PreventionStatus status, PreventionReceipt receipt) { }
    private record PropertyState(UUID owner, int epoch, int capacity, boolean enabled) { }
    private record Payment(Instant paidAt, boolean waiting) { }

    public InsuranceRepository() { }

    /**
     * Caller MUST first verify TransferJournal.confirmedPaid(c,eventId) in this SAME transaction.
     * Lock order: property, event, purchase intent, transfer proof, insurance. Existing locks may
     * be reacquired here. Apply before marking the matching WAITING purchase intent APPLIED.
     * The supplied day is the authoritative APPLICATION economy day, not a delayed payment day.
     */
    public ApplyResult applyPaid(Connection c, long eventId, Property expected, Instant at, LocalDate day)
            throws SQLException {
        transaction(c); scope(expected); time(at); day(day);
        if (eventId <= 0) throw new IllegalArgumentException("Invalid insurance event");
        Instant appliedAt = at.truncatedTo(ChronoUnit.MILLIS);
        PropertyState actual = property(c, expected.propertyId());
        Payment payment = payment(c, eventId, expected);
        Instant paidAt = payment.paidAt();
        Optional<CoverageReceipt> prior = find(c, eventId);
        if (prior.isPresent()) {
            CoverageReceipt receipt = prior.get();
            require(matches(receipt, expected) && receipt.paidAt().equals(paidAt), "Insurance replay conflicts");
            return new ApplyResult(ApplyStatus.ALREADY_APPLIED, receipt);
        }
        require(payment.waiting(), "Applied purchase lacks its atomic insurance receipt; no backfill permitted");
        require(matches(actual, expected), "Insurance ownership/capacity unavailable");
        require(!appliedAt.isBefore(paidAt), "Insurance cannot precede payment");
        require(canPurchaseLocked(c, expected.propertyId(), expected.ownerUuid(), expected.ownershipEpoch(), day),
                "Insurance term already reserved");
        LocalDate start = day.plusDays(1), end = start.plusDays(7);
        day(end);
        CoverageReceipt receipt = new CoverageReceipt(eventId, expected.propertyId(), expected.ownerUuid(),
                expected.ownershipEpoch(), expected.capacity(), PropertyFormulas.insurancePriceCents(expected.capacity()),
                paidAt, appliedAt, day, start, end);
        try (PreparedStatement ps = prepare(c, """
                INSERT INTO npc_property_insurance(event_id,property_id,owner_uuid,ownership_epoch,capacity,
                  price_cents,paid_at,applied_at,applied_day,start_day,end_day_exclusive)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """, eventId, receipt.propertyId(), receipt.ownerUuid(), receipt.ownershipEpoch(), receipt.capacity(),
                receipt.priceCents(), utc(paidAt), utc(appliedAt), day, start, end)) {
            one(c, ps);
        }
        require(find(c, eventId).orElseThrow(() -> new SQLException("Insurance receipt missing")).equals(receipt),
                "Insurance receipt readback differs");
        return new ApplyResult(ApplyStatus.APPLIED, receipt);
    }

    /** Tomorrow's seven-day interval must not overlap this owner's incarnation, even if used. */
    public boolean canPurchase(Connection c, UUID propertyId, LocalDate applicationDay) throws SQLException {
        transaction(c); Objects.requireNonNull(propertyId); day(applicationDay);
        PropertyState p = property(c, propertyId);
        return p.enabled() && p.owner() != null && canPurchaseLocked(c, propertyId, p.owner(), p.epoch(), applicationDay);
    }

    /** Exact immutable terms, including historical owners. Does not confer current coverage. */
    public Optional<CoverageReceipt> find(Connection c, long eventId) throws SQLException {
        transaction(c);
        if (eventId <= 0) return Optional.empty();
        try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_property_insurance WHERE event_id=?", eventId);
             ResultSet rs = ps.executeQuery()) {
            if (!next(c, ps, rs)) return Optional.empty();
            CoverageReceipt result = receipt(rs);
            require(!next(c, ps, rs), "Duplicate insurance receipt");
            return Optional.of(result);
        }
    }

    /** Nonlocking current-incarnation snapshot; an active term wins over an adjacent renewal. */
    public Optional<CoverageView> snapshot(Connection c, Property property, LocalDate day) throws SQLException {
        transaction(c); Objects.requireNonNull(property); Objects.requireNonNull(property.propertyId()); day(day);
        return view(c, property, null, day, false);
    }

    public Optional<CoverageView> snapshot(Connection c, Property property, Instant at, LocalDate day) throws SQLException {
        transaction(c); Objects.requireNonNull(property); Objects.requireNonNull(property.propertyId()); time(at); day(day);
        return view(c, property, at, day, false);
    }

    /** Current incarnation's pending/active term, including consumption. Locks property first. */
    public Optional<CoverageView> current(Connection c, Property expected, Instant at, LocalDate day)
            throws SQLException {
        transaction(c); scope(expected); time(at); day(day);
        if (!matches(property(c, expected.propertyId()), expected)) return Optional.empty();
        return view(c, expected, at, day, true);
    }

    public boolean isCovered(Connection c, Property expected, Instant at, LocalDate day) throws SQLException {
        Optional<CoverageView> view = current(c, expected, at, day);
        return view.isPresent() && !view.get().used() && !day.isBefore(view.get().receipt().startDay());
    }

    /**
     * Call ONLY while generating a new prevented incident, before its daily roll receipt.
     * The incident must already be inserted with prevented=true in this transaction, with exact
     * generated time/day. Caller atomically records PREVENTED roll and applies NO damage/disable.
     * An existing daily roll can never be retrospectively insured.
     */
    public PreventionResult recordPrevention(Connection c, Property expected, long incidentId,
                                             Instant at, LocalDate day) throws SQLException {
        transaction(c); scope(expected); time(at); day(day);
        if (incidentId <= 0) throw new IllegalArgumentException("Invalid insurance incident");
        Instant generatedAt = at.truncatedTo(ChronoUnit.MILLIS);
        require(matches(property(c, expected.propertyId()), expected), "Insurance ownership unavailable");
        Optional<PreventionReceipt> prior = preventionForIncident(c, incidentId);
        if (prior.isPresent()) {
            PreventionReceipt receipt = prior.get();
            CoverageReceipt term = find(c, receipt.eventId()).orElseThrow(() -> new SQLException("Insurance term missing"));
            require(matches(term, expected) && receipt.economyDay().equals(day)
                    && receipt.preventedAt().equals(generatedAt), "Insurance prevention replay conflicts");
            incident(c, expected.propertyId(), incidentId, generatedAt, day);
            return new PreventionResult(PreventionStatus.ALREADY_PREVENTED, receipt);
        }
        CoverageView view = current(c, expected, generatedAt, day)
                .orElseThrow(() -> new SQLException("Insurance coverage unavailable"));
        require(!view.used() && !day.isBefore(view.receipt().startDay()), "Insurance coverage unavailable");
        incident(c, expected.propertyId(), incidentId, generatedAt, day);
        try (PreparedStatement ps = prepare(c, "SELECT outcome FROM npc_incident_rolls "
                + "WHERE property_id=? AND economy_day=? FOR UPDATE", expected.propertyId(), day.toString());
             ResultSet rs = ps.executeQuery()) {
            require(!next(c, ps, rs), "Existing incident roll cannot be insured retrospectively");
        }
        PreventionReceipt receipt = new PreventionReceipt(view.receipt().eventId(), incidentId, day, generatedAt);
        try (PreparedStatement ps = prepare(c, "INSERT INTO npc_insurance_preventions "
                + "(event_id,incident_id,economy_day,prevented_at) VALUES(?,?,?,?)",
                receipt.eventId(), incidentId, day, utc(generatedAt))) {
            one(c, ps);
        }
        require(prevention(c, receipt.eventId()).orElseThrow().equals(receipt), "Insurance prevention readback differs");
        return new PreventionResult(PreventionStatus.PREVENTED, receipt);
    }

    private static boolean canPurchaseLocked(Connection c, UUID property, UUID owner, int epoch, LocalDate day) throws SQLException {
        LocalDate start = day.plusDays(1), end = start.plusDays(7);
        day(end);
        try (PreparedStatement ps = prepare(c, "SELECT event_id FROM npc_property_insurance "
                + "WHERE property_id=? AND owner_uuid=? AND ownership_epoch=? "
                + "AND start_day<? AND end_day_exclusive>? LIMIT 1 FOR UPDATE", property, owner, epoch, end, start);
             ResultSet rs = ps.executeQuery()) {
            return !next(c, ps, rs);
        }
    }

    private static Optional<CoverageView> view(Connection c, Property property, Instant at, LocalDate day, boolean lock)
            throws SQLException {
        if (property.ownerUuid() == null) return Optional.empty();
        var terms = new ArrayList<CoverageReceipt>(2);
        String sql = "SELECT * FROM npc_property_insurance WHERE property_id=? AND owner_uuid=? AND ownership_epoch=? "
                + "AND end_day_exclusive>? AND applied_day<=? " + (at == null ? "" : "AND applied_at<=? ")
                + "ORDER BY start_day,event_id LIMIT 3" + (lock ? " FOR UPDATE" : "");
        Object[] args = at == null
                ? new Object[]{property.propertyId(), property.ownerUuid(), property.ownershipEpoch(), day, day}
                : new Object[]{property.propertyId(), property.ownerUuid(), property.ownershipEpoch(), day, day, utc(at)};
        try (PreparedStatement ps = prepare(c, sql, args); ResultSet rs = ps.executeQuery()) {
            while (next(c, ps, rs)) {
                require(terms.size() < 2, "Insurance term bound exceeded");
                CoverageReceipt term = receipt(rs);
                require(terms.isEmpty() || !terms.getLast().endDayExclusive().isAfter(term.startDay()),
                        "Overlapping insurance terms require review");
                terms.add(term);
            }
        }
        if (terms.isEmpty()) return Optional.empty();
        CoverageReceipt selected = terms.getFirst();
        return Optional.of(new CoverageView(selected, preventionQuery(c, "event_id", selected.eventId(), lock),
                terms.size() == 2 ? Optional.of(terms.getLast()) : Optional.empty()));
    }

    private static PropertyState property(Connection c, UUID propertyId) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT owner_uuid,ownership_epoch,capacity,enabled "
                + "FROM npc_properties WHERE property_id=? FOR UPDATE", propertyId); ResultSet rs = ps.executeQuery()) {
            require(next(c, ps, rs), "Insurance property missing");
            byte[] owner = rs.getBytes("owner_uuid");
            PropertyState p = new PropertyState(owner == null ? null : uuid(owner),
                    (int) number(rs, "ownership_epoch", 0, Integer.MAX_VALUE),
                    (int) number(rs, "capacity", 1, 4), number(rs, "enabled", 0, 1) == 1);
            require(!next(c, ps, rs), "Duplicate insurance property");
            return p;
        }
    }

    private static Payment payment(Connection c, long eventId, Property expected) throws SQLException {
        long price = PropertyFormulas.insurancePriceCents(expected.capacity());
        Instant paidAt;
        String eventDay;
        try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_economy_events WHERE event_id=? FOR UPDATE", eventId);
             ResultSet rs = ps.executeQuery()) {
            require(next(c, ps, rs), "Insurance payment missing");
            require("PAID".equals(rs.getString("state")) && "npc-property-insurance".equals(rs.getString("source"))
                    && "TRANSFER_FROM_PUBLIC".equals(rs.getString("direction"))
                    && expected.propertyId().equals(uuid(rs.getBytes("property_id")))
                    && expected.ownerUuid().equals(uuid(rs.getBytes("counterparty_uuid")))
                    && expected.ownerUuid().equals(uuid(rs.getBytes("benefit_unit_id")))
                    && expected.ownershipEpoch() == number(rs, "ownership_epoch", 0, Integer.MAX_VALUE)
                    && price == number(rs, "requested_amount_cents", 1, Long.MAX_VALUE)
                    && price == number(rs, "paid_amount_cents", 1, Long.MAX_VALUE), "Insurance payment binding differs");
            paidAt = instant(rs, "finalized_at");
            eventDay = rs.getString("economy_day");
            require(!next(c, ps, rs), "Duplicate insurance payment");
        }
        boolean waiting;
        try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_purchase_intents WHERE event_id=? FOR UPDATE", eventId);
             ResultSet rs = ps.executeQuery()) {
            require(next(c, ps, rs), "Insurance purchase intent missing");
            require("INSURANCE".equals(rs.getString("kind"))
                    && ("WAITING".equals(rs.getString("state")) || "APPLIED".equals(rs.getString("state")))
                    && expected.propertyId().equals(uuid(rs.getBytes("property_id")))
                    && expected.ownerUuid().equals(uuid(rs.getBytes("payer_uuid")))
                    && expected.ownershipEpoch() == number(rs, "ownership_epoch", 0, Integer.MAX_VALUE)
                    && expected.capacity() == number(rs, "insurance_capacity", 1, 4)
                    && price == number(rs, "quoted_amount_cents", 1, Long.MAX_VALUE)
                    && Objects.equals(eventDay, rs.getString("economy_day")), "Insurance purchase binding differs");
            waiting = "WAITING".equals(rs.getString("state"));
            require(!next(c, ps, rs), "Duplicate insurance purchase");
        }
        return new Payment(paidAt, waiting);
    }

    private static void incident(Connection c, UUID property, long incidentId, Instant at, LocalDate day) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT property_id,economy_day,created_at,prevented,resolved_at "
                + "FROM npc_incidents WHERE incident_id=? FOR UPDATE", incidentId); ResultSet rs = ps.executeQuery()) {
            require(next(c, ps, rs), "Insurance incident missing");
            require(property.equals(uuid(rs.getBytes("property_id"))) && day.toString().equals(rs.getString("economy_day"))
                    && at.equals(instant(rs, "created_at")) && number(rs, "prevented", 0, 1) == 1
                    && rs.getObject("resolved_at") == null, "Insurance cannot cover an existing or unprevented incident");
            require(!next(c, ps, rs), "Duplicate insurance incident");
        }
    }

    private static Optional<PreventionReceipt> prevention(Connection c, long eventId) throws SQLException {
        return preventionQuery(c, "event_id", eventId);
    }
    private static Optional<PreventionReceipt> preventionForIncident(Connection c, long incidentId) throws SQLException {
        return preventionQuery(c, "incident_id", incidentId);
    }
    private static Optional<PreventionReceipt> preventionQuery(Connection c, String column, long id) throws SQLException {
        return preventionQuery(c, column, id, true);
    }
    private static Optional<PreventionReceipt> preventionQuery(Connection c, String column, long id, boolean lock) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_insurance_preventions WHERE " + column + "=?"
                + (lock ? " FOR UPDATE" : ""), id);
             ResultSet rs = ps.executeQuery()) {
            if (!next(c, ps, rs)) return Optional.empty();
            PreventionReceipt result = new PreventionReceipt(number(rs, "event_id", 1, Long.MAX_VALUE),
                    number(rs, "incident_id", 1, Long.MAX_VALUE), date(rs, "economy_day"), instant(rs, "prevented_at"));
            require(!next(c, ps, rs), "Duplicate insurance prevention");
            return Optional.of(result);
        }
    }
    private static CoverageReceipt receipt(ResultSet rs) throws SQLException {
        CoverageReceipt r = new CoverageReceipt(number(rs, "event_id", 1, Long.MAX_VALUE), uuid(rs.getBytes("property_id")),
                uuid(rs.getBytes("owner_uuid")), (int) number(rs, "ownership_epoch", 0, Integer.MAX_VALUE),
                (int) number(rs, "capacity", 1, 4), number(rs, "price_cents", 1, Long.MAX_VALUE),
                instant(rs, "paid_at"), instant(rs, "applied_at"), date(rs, "applied_day"),
                date(rs, "start_day"), date(rs, "end_day_exclusive"));
        require(r.priceCents() == PropertyFormulas.insurancePriceCents(r.capacity()) && !r.appliedAt().isBefore(r.paidAt())
                && r.startDay().equals(r.appliedDay().plusDays(1)) && r.endDayExclusive().equals(r.startDay().plusDays(7)),
                "Invalid stored insurance terms");
        return r;
    }
    private static boolean matches(PropertyState actual, Property expected) {
        return actual.enabled() && expected.ownerUuid().equals(actual.owner())
                && expected.ownershipEpoch() == actual.epoch() && expected.capacity() == actual.capacity();
    }
    private static boolean matches(CoverageReceipt receipt, Property expected) {
        return receipt.propertyId().equals(expected.propertyId()) && receipt.ownerUuid().equals(expected.ownerUuid())
                && receipt.ownershipEpoch() == expected.ownershipEpoch() && receipt.capacity() == expected.capacity();
    }
    private static void scope(Property p) {
        Objects.requireNonNull(p); Objects.requireNonNull(p.propertyId()); Objects.requireNonNull(p.ownerUuid());
        if (p.ownershipEpoch() < 0 || p.capacity() < 1 || p.capacity() > 4)
            throw new IllegalArgumentException("Invalid insurance property scope");
    }
    private static void transaction(Connection c) throws SQLException {
        Objects.requireNonNull(c);
        require(!c.getAutoCommit(), "Insurance requires caller-owned transaction");
    }
    private static void time(Instant at) {
        Objects.requireNonNull(at);
        int year = utc(at).getYear();
        if (year < 1 || year > 9999) throw new IllegalArgumentException("Insurance time out of SQL range");
    }
    private static void day(LocalDate day) {
        Objects.requireNonNull(day);
        if (day.getYear() < 1 || day.getYear() > 9999) throw new IllegalArgumentException("Insurance day out of SQL range");
    }
    private static LocalDateTime utc(Instant at) { return LocalDateTime.ofInstant(at, ZoneOffset.UTC); }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        require(value != null, "Missing insurance timestamp");
        return value.toInstant(ZoneOffset.UTC);
    }
    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        LocalDate value = rs.getObject(column, LocalDate.class);
        require(value != null && value.getYear() >= 1 && value.getYear() <= 9999, "Invalid insurance date");
        return value;
    }
    private static UUID uuid(byte[] value) throws SQLException {
        require(value != null && value.length == 16, "Invalid insurance UUID");
        return Uuids.fromBytes(value);
    }
    private static long number(ResultSet rs, String column, long min, long max) throws SQLException {
        java.math.BigDecimal value = rs.getBigDecimal(column);
        require(value != null, "Missing insurance numeric field");
        try {
            long result = value.longValueExact();
            require(result >= min && result <= max, "Invalid insurance numeric field");
            return result;
        } catch (ArithmeticException failure) { throw new SQLException("Invalid insurance numeric precision", failure); }
    }
    private static PreparedStatement prepare(Connection c, String sql, Object... args) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        try {
            ps.setQueryTimeout(5);
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof UUID id) ps.setBytes(i + 1, Uuids.toBytes(id));
                else ps.setObject(i + 1, args[i]);
            }
            return ps;
        } catch (SQLException | RuntimeException failure) { ps.close(); throw failure; }
    }
    private static void one(Connection c, PreparedStatement ps) throws SQLException {
        require(ps.executeUpdate() == 1, "Insurance write affected unexpected rows");
        warnings(c, ps, null);
    }
    private static boolean next(Connection c, PreparedStatement ps, ResultSet rs) throws SQLException {
        warnings(c, ps, rs);
        boolean result = rs.next();
        warnings(c, ps, rs);
        return result;
    }
    private static void warnings(Connection c, PreparedStatement ps, ResultSet rs) throws SQLException {
        require(c.getWarnings() == null && ps.getWarnings() == null && (rs == null || rs.getWarnings() == null),
                "Insurance query returned warnings; partial results rejected");
    }
    private static void require(boolean condition, String reason) throws SQLException {
        if (!condition) throw new SQLException(reason);
    }
}

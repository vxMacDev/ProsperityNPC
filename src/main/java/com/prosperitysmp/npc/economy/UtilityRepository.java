package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.clock.EconomyClock;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.formulas.UpgradeType;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * SQL-only daily obligation and payment receipts. No wallet calls or runtime wiring.
 * SQL ownership is a mirror: a future coordinator must serialize ARM changes and
 * enforce permissions/lifecycle/provider authority before dispatch. Legacy utility
 * writers must remain disabled. An acknowledged new preparation is not itself a
 * payment authorization; uncertain commits are read back with the same request ID.
 */
public final class UtilityRepository {
    public enum State { WAITING, PAID, DECLINED, REVIEW_REQUIRED }
    public record Receipt(UtilityBill bill, UUID requestId, int attemptNumber, long eventId,
                          State state, String outcome, Instant paidAt) { }
    public record Preparation(boolean newlyQueued, Receipt receipt) { }
    /** No payment was prepared. Suppressed rollback/close failures still require review. */
    public static final class MaximumExceededException extends SQLException {
        private static final long serialVersionUID = 1L;
        private MaximumExceededException() { super("Utility bill exceeds authorized maximum"); }
    }
    private record PropertyState(UUID owner, int epoch, long revision, int capacity, boolean enabled) { }
    private record Ledger(String state, Long paidCents, Instant finalizedAt) { }
    private record RequestScope(UUID property, LocalDate day) { }
    private static final int MAX_ATTEMPTS = 16;
    private final Database database;
    private final TransferJournal transfers;
    private final UUID reserve;
    private final Clock clock;
    private final ZoneId zone;
    private final int resetHour;
    private final EconomyClock economyClock;

    public UtilityRepository(Database database, UUID reserve, Clock clock, ZoneId zone, int resetHour) {
        this.database = Objects.requireNonNull(database);
        this.reserve = Objects.requireNonNull(reserve);
        this.transfers = new TransferJournal(database, reserve);
        this.clock = Objects.requireNonNull(clock);
        this.zone = Objects.requireNonNull(zone);
        this.resetHour = resetHour;
        this.economyClock = new EconomyClock(clock, zone, resetHour);
    }

    /** One immutable bill/property/day. Only an explicit new UUID after a proved decline retries. */
    public Preparation preparePayment(UUID requestId, UUID propertyId, UUID ownerId,
                                      int ownershipEpoch, LocalDate day) throws SQLException {
        return preparePayment(requestId, propertyId, ownerId, ownershipEpoch, day, Long.MAX_VALUE);
    }

    /** The caller's maximum is enforced before inserting a bill, event or payment attempt. */
    public Preparation preparePayment(UUID requestId, UUID propertyId, UUID ownerId,
                                      int ownershipEpoch, LocalDate day, long maximumCents) throws SQLException {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(propertyId);
        Objects.requireNonNull(ownerId);
        requireDay(day);
        if (maximumCents <= 0) throw new IllegalArgumentException("Invalid utility maximum");
        if (ownershipEpoch < 0 || reserve.equals(ownerId)) throw new IllegalArgumentException("Invalid utility payer");
        return transaction(c -> {
            PropertyState property = property(c, propertyId);
            RequestScope existing = requestScope(c, "request_id", requestId, 0);
            if (existing != null && (!existing.property().equals(propertyId) || !existing.day().equals(day)))
                throw new SQLException("Utility request UUID payload conflict");
            UtilityBill bill = bill(c, propertyId, day);
            if (bill != null && bill.cents() > maximumCents) throw new MaximumExceededException();
            List<Receipt> history = bill == null ? List.of() : attempts(c, bill);
            reconcileScope(c, propertyId, day, history);
            if (bill != null && (!bill.ownerId().equals(ownerId) || bill.ownershipEpoch() != ownershipEpoch))
                throw new SQLException("Utility bill belongs to its original owner and epoch");
            if (existing != null) {
                for (Receipt receipt : history) if (receipt.requestId().equals(requestId))
                    return new Preparation(false, receipt);
                throw new SQLException("Utility request missing from its bill history");
            }
            if (!property.enabled() || !ownerId.equals(property.owner()) || ownershipEpoch != property.epoch())
                throw new SQLException("Utility ownership unavailable");
            LocalDate preparationDay = economyClock.today();
            if (bill == null) {
                if (!day.equals(preparationDay)) throw new SQLException("New utility bill must be for current day");
                bill = new UtilityBill(propertyId, ownerId, ownershipEpoch, property.revision(), day,
                        property.capacity(), occupancy(c, propertyId, property.capacity()), upgrades(c, propertyId),
                        clock.instant().truncatedTo(ChronoUnit.MILLIS), zone.getId(), resetHour);
                if (bill.cents() > maximumCents) throw new MaximumExceededException();
                insertBill(c, bill);
            } else {
                if (day.isAfter(preparationDay) || clock.instant().isBefore(bill.issuedAt()))
                    throw new SQLException("Utility retry precedes bill issuance");
                if (history.isEmpty()) throw new SQLException("Utility bill has no payment attempt");
                for (Receipt prior : history) {
                    if (prior.state() != State.DECLINED || !transfers.confirmedDeclined(c, prior.eventId()))
                        throw new SQLException("Utility retry requires proved no-movement declines");
                }
            }
            if (history.size() >= MAX_ATTEMPTS) throw new SQLException("Utility retry limit requires staff review");
            EconomyEvent event = EconomyEvent.pending(eventKey(requestId), bill.cents(), EventSource.UTILITY,
                    propertyId, ownerId, ownershipEpoch, ownerId, day.toString());
            long eventId = EconomyEventRepository.insertPending(c, event);
            if (eventId <= 0) throw new SQLException("Utility event exists without matching receipt");
            Receipt receipt = new Receipt(bill, requestId, history.size() + 1, eventId, State.WAITING, null, null);
            try (PreparedStatement ps = prepare(c, """
                    INSERT INTO npc_utility_payments
                      (request_id,property_id,economy_day,attempt_number,event_id,state)
                    VALUES (?,?,?,?,?,'WAITING')
                    """)) {
                id(ps, 1, requestId);
                id(ps, 2, propertyId);
                ps.setString(3, day.toString());
                ps.setInt(4, receipt.attemptNumber());
                ps.setLong(5, eventId);
                one(ps.executeUpdate());
            }
            // A blocked insert may cross reset. Roll back all three new rows together.
            if (!preparationDay.equals(economyClock.today())) throw new SQLException("Utility preparation crossed reset");
            return new Preparation(true, receipt);
        });
    }

    /** Persist only actual two-leg payment or a proved no-movement failure, never a ledger label alone. */
    public Optional<Receipt> reconcile(long eventId) throws SQLException {
        if (eventId <= 0) return Optional.empty();
        return transaction(c -> {
            RequestScope scope = requestScope(c, "event_id", null, eventId);
            if (scope == null) return Optional.empty();
            property(c, scope.property());
            UtilityBill bill = bill(c, scope.property(), scope.day());
            if (bill == null) throw new SQLException("Utility obligation disappeared");
            List<Receipt> history = attempts(c, bill);
            reconcileScope(c, scope.property(), scope.day(), history);
            Receipt receipt = history.stream().filter(r -> r.eventId() == eventId).findFirst()
                    .orElseThrow(() -> new SQLException("Utility attempt disappeared"));
            if (receipt.state() != State.WAITING) return Optional.of(receipt);
            Ledger ledger = ledger(c, receipt);
            if ("PENDING".equals(ledger.state()) || "CLAIMED".equals(ledger.state())) return Optional.of(receipt);
            State state = State.REVIEW_REQUIRED;
            String outcome = "TRANSFER_UNCERTAIN";
            Instant paidAt = null;
            if ("PAID".equals(ledger.state()) && transfers.confirmedPaid(c, eventId)) {
                paidAt = ledger.finalizedAt();
                if (paidAt == null || paidAt.isBefore(bill.issuedAt()) || paidAt.isAfter(clock.instant()))
                    throw new SQLException("Utility payment chronology invalid");
                state = State.PAID;
                outcome = "TRANSFER_CONFIRMED";
            } else if ("FAILED".equals(ledger.state()) && transfers.confirmedDeclined(c, eventId)) {
                state = State.DECLINED;
                outcome = "PAYMENT_DECLINED";
            }
            try (PreparedStatement ps = prepare(c, """
                    UPDATE npc_utility_payments SET state=?,outcome_code=?,paid_at=?
                    WHERE event_id=? AND state='WAITING'
                    """)) {
                ps.setString(1, state.name());
                ps.setString(2, outcome);
                time(ps, 3, paidAt);
                ps.setLong(4, eventId);
                one(ps.executeUpdate());
            }
            return Optional.of(new Receipt(bill, receipt.requestId(), receipt.attemptNumber(), eventId, state, outcome, paidAt));
        });
    }

    /** Complete bounded history for one bill; reads are not fresh ARM/payment authorization. */
    public List<Receipt> history(UUID propertyId, LocalDate day) throws SQLException {
        Objects.requireNonNull(propertyId);
        requireDay(day);
        return transaction(c -> {
            property(c, propertyId);
            UtilityBill bill = bill(c, propertyId, day);
            List<Receipt> result = bill == null ? List.of() : attempts(c, bill);
            reconcileScope(c, propertyId, day, result);
            if (bill != null && result.isEmpty()) throw new SQLException("Utility bill has no payment attempt");
            return result;
        });
    }

    private static PropertyState property(Connection c, UUID property) throws SQLException {
        try (PreparedStatement ps = prepare(c, """
                SELECT owner_uuid,ownership_epoch,row_version,capacity,enabled FROM npc_properties
                WHERE property_id=? FOR UPDATE
                """)) {
            id(ps, 1, property);
            try (ResultSet rs = ps.executeQuery()) {
                if (!next(c, ps, rs)) throw new SQLException("Utility property missing");
                PropertyState p = new PropertyState(uuid(rs, "owner_uuid", true), integer(rs, "ownership_epoch"),
                        number(rs, "row_version"), integer(rs, "capacity"), integer(rs, "enabled") == 1);
                int enabled = integer(rs, "enabled");
                if (p.epoch() < 0 || p.revision() < 0 || p.revision() == Long.MAX_VALUE
                        || p.capacity() < 1 || p.capacity() > 4 || enabled < 0 || enabled > 1 || next(c, ps, rs))
                    throw new SQLException("Malformed utility property");
                return p;
            }
        }
    }

    private static int occupancy(Connection c, UUID property, int capacity) throws SQLException {
        Set<Integer> slots = new HashSet<>();
        try (PreparedStatement ps = prepare(c, """
                SELECT slot FROM npc_tenants WHERE property_id=? ORDER BY tenant_id
                LIMIT 5 ROWS EXAMINED 128 FOR UPDATE
                """)) {
            id(ps, 1, property);
            try (ResultSet rs = ps.executeQuery()) {
                while (next(c, ps, rs)) {
                    int slot = integer(rs, "slot");
                    if (slot < 0 || slot >= capacity || !slots.add(slot)) throw new SQLException("Invalid utility tenancy slots");
                }
            }
        }
        return slots.size();
    }

    private static int upgrades(Connection c, UUID property) throws SQLException {
        Set<UpgradeType> seen = new HashSet<>();
        int levels = 0;
        try (PreparedStatement ps = prepare(c, """
                SELECT upgrade_type,level FROM npc_upgrades WHERE property_id=? ORDER BY upgrade_type
                LIMIT 3 ROWS EXAMINED 128 FOR UPDATE
                """)) {
            id(ps, 1, property);
            try (ResultSet rs = ps.executeQuery()) {
                while (next(c, ps, rs)) {
                    UpgradeType type;
                    try { type = UpgradeType.valueOf(rs.getString("upgrade_type")); }
                    catch (RuntimeException invalid) { throw new SQLException("Invalid utility upgrade type", invalid); }
                    int level = integer(rs, "level");
                    if (level < 0 || level > 3 || !seen.add(type) || seen.size() > 2)
                        throw new SQLException("Invalid utility installed levels");
                    levels += level;
                }
            }
        }
        return levels;
    }

    private static UtilityBill bill(Connection c, UUID property, LocalDate day) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_utility_bills WHERE property_id=? AND economy_day=? FOR UPDATE")) {
            id(ps, 1, property);
            ps.setString(2, day.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!next(c, ps, rs)) return null;
                UtilityBill bill = readBill(rs);
                if (!property.equals(bill.propertyId()) || !day.equals(bill.day()) || next(c, ps, rs))
                    throw new SQLException("Utility bill scope mismatch");
                return bill;
            }
        }
    }

    /** Shared receipt codec; the caller supplies unaliased bill columns, never inferred current inputs. */
    static UtilityBill readBill(ResultSet rs) throws SQLException {
        try {
            UtilityBill bill = new UtilityBill(uuid(rs, "property_id", false), uuid(rs, "owner_uuid", false),
                    integer(rs, "ownership_epoch"), number(rs, "property_revision"), parseDay(rs.getString("economy_day")),
                    integer(rs, "capacity"), integer(rs, "occupied_tenants"), integer(rs, "installed_upgrade_levels"),
                    instant(rs, "issued_at"), rs.getString("clock_zone"), integer(rs, "reset_hour"));
            if (!bill.periodStart().equals(instant(rs, "period_start")) || !bill.dueAt().equals(instant(rs, "due_at"))
                    || bill.cents() != number(rs, "amount_cents") || !bill.fingerprint().equals(rs.getString("payload_sha256")))
                throw new SQLException("Utility bill fingerprint or terms mismatch");
            return bill;
        } catch (RuntimeException invalid) { throw new SQLException("Invalid utility bill", invalid); }
    }

    private static void insertBill(Connection c, UtilityBill b) throws SQLException {
        try (PreparedStatement ps = prepare(c, """
                INSERT INTO npc_utility_bills(property_id,economy_day,owner_uuid,ownership_epoch,property_revision,
                  capacity,occupied_tenants,installed_upgrade_levels,issued_at,period_start,due_at,clock_zone,
                  reset_hour,amount_cents,payload_sha256) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            id(ps, 1, b.propertyId());
            ps.setString(2, b.day().toString());
            id(ps, 3, b.ownerId());
            ps.setInt(4, b.ownershipEpoch());
            ps.setLong(5, b.propertyRevision());
            ps.setInt(6, b.capacity());
            ps.setInt(7, b.occupiedTenants());
            ps.setInt(8, b.installedUpgradeLevels());
            time(ps, 9, b.issuedAt());
            time(ps, 10, b.periodStart());
            time(ps, 11, b.dueAt());
            ps.setString(12, b.zoneId());
            ps.setInt(13, b.resetHour());
            ps.setLong(14, b.cents());
            ps.setString(15, b.fingerprint());
            one(ps.executeUpdate());
        }
    }

    private List<Receipt> attempts(Connection c, UtilityBill bill) throws SQLException {
        List<Receipt> rows = new ArrayList<>();
        try (PreparedStatement ps = prepare(c, """
                SELECT * FROM npc_utility_payments WHERE property_id=? AND economy_day=?
                ORDER BY attempt_number LIMIT 17 ROWS EXAMINED 512 FOR UPDATE
                """)) {
            id(ps, 1, bill.propertyId());
            ps.setString(2, bill.day().toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (next(c, ps, rs)) {
                    try {
                        Receipt r = new Receipt(bill, uuid(rs, "request_id", false), integer(rs, "attempt_number"),
                                number(rs, "event_id"), State.valueOf(rs.getString("state")), rs.getString("outcome_code"),
                                instant(rs, "paid_at"));
                        if (r.attemptNumber() != rows.size() + 1 || rows.size() >= MAX_ATTEMPTS || r.eventId() <= 0
                                || !bill.propertyId().equals(uuid(rs, "property_id", false))
                                || !bill.day().equals(parseDay(rs.getString("economy_day"))))
                            throw new SQLException("Utility attempt order or scope invalid");
                        boolean valid = switch (r.state()) {
                            case WAITING -> r.paidAt() == null && r.outcome() == null;
                            case PAID -> r.paidAt() != null && "TRANSFER_CONFIRMED".equals(r.outcome());
                            case DECLINED -> r.paidAt() == null && "PAYMENT_DECLINED".equals(r.outcome());
                            case REVIEW_REQUIRED -> r.paidAt() == null && r.outcome() != null && !r.outcome().isBlank();
                        };
                        if (!valid || (!rows.isEmpty() && rows.getLast().state() != State.DECLINED))
                            throw new SQLException("Invalid utility payment history");
                        rows.add(r);
                    } catch (RuntimeException invalid) { throw new SQLException("Invalid utility payment receipt", invalid); }
                }
            }
        }
        for (Receipt r : rows) {
            Ledger l = ledger(c, r);
            if (r.state() == State.PAID && (!"PAID".equals(l.state()) || !transfers.confirmedPaid(c, r.eventId())
                    || !r.paidAt().equals(l.finalizedAt()) || r.paidAt().isBefore(bill.issuedAt()) || r.paidAt().isAfter(clock.instant())))
                throw new SQLException("Stored utility payment lacks bound proof");
            if (r.state() == State.DECLINED && (!"FAILED".equals(l.state()) || !transfers.confirmedDeclined(c, r.eventId())))
                throw new SQLException("Stored utility decline lacks no-movement proof");
        }
        return List.copyOf(rows);
    }

    /** Legacy or orphan ledger rows are a competing charge, not an invitation to bill again. */
    private static void reconcileScope(Connection c, UUID property, LocalDate day, List<Receipt> attempts) throws SQLException {
        Set<Long> remaining = new HashSet<>();
        for (Receipt r : attempts) if (!remaining.add(r.eventId())) throw new SQLException("Duplicate utility event");
        try (PreparedStatement ps = prepare(c, """
                SELECT event_id FROM npc_economy_events WHERE property_id=? AND source=? AND economy_day=?
                ORDER BY event_id LIMIT 17 ROWS EXAMINED 512 FOR UPDATE
                """)) {
            id(ps, 1, property);
            ps.setString(2, EventSource.UTILITY.key());
            ps.setString(3, day.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (next(c, ps, rs)) if (!remaining.remove(number(rs, "event_id")))
                    throw new SQLException("Unmanaged utility event requires review");
            }
        }
        if (!remaining.isEmpty()) throw new SQLException("Utility ledger history incomplete");
    }

    private static Ledger ledger(Connection c, Receipt r) throws SQLException {
        UtilityBill b = r.bill();
        try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_economy_events WHERE event_id=? FOR UPDATE")) {
            ps.setLong(1, r.eventId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!next(c, ps, rs)) throw new SQLException("Utility event missing");
                if (!eventKey(r.requestId()).equals(rs.getString("idempotency_key"))
                        || !EventSource.UTILITY.key().equals(rs.getString("source"))
                        || !EventSource.UTILITY.direction().moneyFlowKey().equals(rs.getString("direction"))
                        || !b.propertyId().equals(uuid(rs, "property_id", false))
                        || !b.ownerId().equals(uuid(rs, "counterparty_uuid", false))
                        || b.ownershipEpoch() != integer(rs, "ownership_epoch")
                        || !b.ownerId().equals(uuid(rs, "benefit_unit_id", false))
                        || !b.day().equals(parseDay(rs.getString("economy_day")))
                        || b.cents() != number(rs, "requested_amount_cents"))
                    throw new SQLException("Utility event binding mismatch");
                String state = rs.getString("state");
                if (state == null || !Set.of("PENDING", "CLAIMED", "PAID", "FAILED", "AMBIGUOUS").contains(state))
                    throw new SQLException("Invalid utility ledger state");
                Long paid = rs.getObject("paid_amount_cents") == null ? null : number(rs, "paid_amount_cents");
                Instant finalized = instant(rs, "finalized_at");
                if (("PENDING".equals(state) || "CLAIMED".equals(state)) && (paid != null || finalized != null))
                    throw new SQLException("Unsettled utility event carries a settlement");
                if (next(c, ps, rs)) throw new SQLException("Duplicate utility ledger row");
                return new Ledger(state, paid, finalized);
            }
        }
    }

    private static RequestScope requestScope(Connection c, String key, UUID request, long event) throws SQLException {
        String sql = switch (key) {
            case "request_id" -> "SELECT property_id,economy_day FROM npc_utility_payments WHERE request_id=?";
            case "event_id" -> "SELECT property_id,economy_day FROM npc_utility_payments WHERE event_id=?";
            default -> throw new IllegalArgumentException("Unknown utility lookup");
        };
        try (PreparedStatement ps = prepare(c, sql)) {
            if (request == null) ps.setLong(1, event);
            else id(ps, 1, request);
            try (ResultSet rs = ps.executeQuery()) {
                if (!next(c, ps, rs)) return null;
                RequestScope scope = new RequestScope(uuid(rs, "property_id", false), parseDay(rs.getString("economy_day")));
                if (next(c, ps, rs)) throw new SQLException("Duplicate utility request");
                return scope;
            }
        }
    }

    private static String eventKey(UUID request) { return "utility|" + request; }
    private static void requireDay(LocalDate day) {
        Objects.requireNonNull(day);
        if (day.getYear() < 1 || day.getYear() > 9998) throw new IllegalArgumentException("Invalid utility day");
    }
    private static LocalDate parseDay(String value) throws SQLException {
        try {
            LocalDate day = LocalDate.parse(value);
            requireDay(day);
            if (!day.toString().equals(value)) throw new IllegalArgumentException("Noncanonical day");
            return day;
        } catch (RuntimeException invalid) { throw new SQLException("Invalid utility day", invalid); }
    }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        if (value == null) return null;
        if (value.getYear() < 1 || value.getYear() > 9999 || value.getNano() % 1_000_000 != 0)
            throw new SQLException("Invalid utility timestamp");
        return value.toInstant(ZoneOffset.UTC);
    }
    private static UUID uuid(ResultSet rs, String column, boolean nullable) throws SQLException {
        byte[] bytes = rs.getBytes(column);
        if (nullable && bytes == null) return null;
        if (bytes == null || bytes.length != 16) throw new SQLException("Invalid utility identity");
        return Uuids.fromBytes(bytes);
    }
    private static long number(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        if (rs.wasNull()) throw new SQLException("Missing utility number");
        return value;
    }
    private static int integer(ResultSet rs, String column) throws SQLException {
        long value = number(rs, column);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) throw new SQLException("Utility integer overflow");
        return (int) value;
    }
    private static boolean next(Connection c, PreparedStatement ps, ResultSet rs) throws SQLException {
        warnings(c, ps, rs);
        boolean next = rs.next();
        warnings(c, ps, rs);
        return next;
    }
    private static void warnings(Connection c, PreparedStatement ps, ResultSet rs) throws SQLException {
        if (c.getWarnings() != null || ps.getWarnings() != null || rs.getWarnings() != null)
            throw new SQLException("Incomplete utility read");
    }
    private static void id(PreparedStatement ps, int index, UUID id) throws SQLException { ps.setBytes(index, Uuids.toBytes(id)); }
    private static void time(PreparedStatement ps, int index, Instant at) throws SQLException {
        ps.setObject(index, at == null ? null : LocalDateTime.ofInstant(at, ZoneOffset.UTC));
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
    private static void one(int count) throws SQLException { if (count != 1) throw new SQLException("Utility write count mismatch"); }
    @FunctionalInterface private interface Transaction<T> { T run(Connection c) throws SQLException; }
    private <T> T transaction(Transaction<T> work) throws SQLException {
        try (Connection c = database.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
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

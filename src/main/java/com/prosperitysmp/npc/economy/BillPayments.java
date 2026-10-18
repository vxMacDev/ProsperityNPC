package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.Uuids;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Payment attempts for configured-bill invoices (dev.65), in the same shape as {@link UtilityRepository}: one
 * ledger event per attempt, keyed {@code bills|<requestId>}, paid to the reserve through the journaled transfer.
 * A new attempt is allowed only after every earlier one is a proved no-movement decline. Ambiguity freezes the
 * invoice for staff. Nothing here moves money; it only prepares and settles the rows around a transfer.
 */
public final class BillPayments {
    public enum State { WAITING, PAID, DECLINED, REVIEW_REQUIRED }

    /** One attempt. {@code cents} and {@code day} are the invoice's frozen total and economy day. */
    public record Receipt(UUID invoiceId, UUID propertyId, UUID ownerId, int ownershipEpoch, LocalDate day, long cents,
                          UUID requestId, int attemptNumber, long eventId, State state, String outcome, Instant paidAt) { }

    public record Preparation(boolean newlyQueued, Receipt receipt) { }

    /** The invoice is no longer payable by this owner: paid, expired, or the home changed hands. */
    public static final class InvoiceUnavailableException extends SQLException {
        private static final long serialVersionUID = 1L;
        private InvoiceUnavailableException() { super("Invoice is not payable"); }
    }

    public static final class InvoiceHeldException extends SQLException {
        private static final long serialVersionUID = 1L;
        private InvoiceHeldException() { super("Invoice has an unresolved payment or reached its retry limit"); }
    }

    public static final class MaximumExceededException extends SQLException {
        private static final long serialVersionUID = 1L;
        private MaximumExceededException() { super("Invoice exceeds authorized maximum"); }
    }

    /** Never an overdraft: the admission balance must cover the whole invoice. No partial payment. */
    public static final class InsufficientFundsException extends SQLException {
        private static final long serialVersionUID = 1L;
        private InsufficientFundsException() { super("Invoice exceeds available funds"); }
    }

    private record Invoice(UUID invoiceId, UUID propertyId, LocalDate day, UUID owner, UUID benefitUnit, int epoch,
                           LocalDate payableUntil, long cents, String state) { }
    private record Ledger(String state, Long paidCents, Instant finalizedAt) { }

    static final int MAX_ATTEMPTS = 16;
    private final Database database;
    private final TransferJournal transfers;
    private final UUID reserve;
    private final Clock clock;

    public BillPayments(Database database, UUID reserve, Clock clock) {
        this.database = Objects.requireNonNull(database);
        this.reserve = Objects.requireNonNull(reserve);
        this.transfers = new TransferJournal(database, reserve);
        this.clock = Objects.requireNonNull(clock);
    }

    /** The ledger idempotency key for one attempt. Fits npc_economy_events.idempotency_key (VARCHAR(160)). */
    public static String eventKey(UUID request) { return "bills|" + request; }

    /** Read-only payer receipt; a sale or a disabled billing runtime does not erase it. */
    public static Optional<Receipt> findByRequest(Database database, UUID request, UUID payer) throws SQLException {
        Objects.requireNonNull(database);
        Objects.requireNonNull(request);
        Objects.requireNonNull(payer);
        try (Connection c = database.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setAutoCommit(false);
            try (PreparedStatement begin = prepare(c, "START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY")) {
                begin.execute();
            }
            try { return readReceipt(c, request, payer); }
            finally { c.rollback(); }
        }
    }

    private static Optional<Receipt> readReceipt(Connection c, UUID request, UUID payer) throws SQLException {
        try (PreparedStatement ps = prepare(c, """
                SELECT p.*,i.property_id,i.owner_uuid,i.ownership_epoch,i.economy_day,i.total_cents,
                       i.benefit_unit_id,i.state AS invoice_state,
                       e.idempotency_key,e.source,e.direction,e.property_id AS event_property,
                       e.counterparty_uuid,e.ownership_epoch AS event_epoch,e.economy_day AS event_day,
                       e.benefit_unit_id AS event_unit,e.requested_amount_cents,e.paid_amount_cents,
                       e.state AS event_state,e.finalized_at
                FROM npc_charge_payments p JOIN npc_charge_invoices i ON i.invoice_id=p.invoice_id
                JOIN npc_economy_events e ON e.event_id=p.event_id
                WHERE p.request_id=? AND i.owner_uuid=? LIMIT 2
                """)) {
            id(ps, 1, request);
            id(ps, 2, payer);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                try {
                    LocalDateTime paid = rs.getObject("paid_at", LocalDateTime.class);
                    var receipt = new Receipt(Uuids.fromBytes(rs.getBytes("invoice_id")), Uuids.fromBytes(rs.getBytes("property_id")),
                            Uuids.fromBytes(rs.getBytes("owner_uuid")), rs.getInt("ownership_epoch"),
                            LocalDate.parse(rs.getString("economy_day")), rs.getLong("total_cents"),
                            Uuids.fromBytes(rs.getBytes("request_id")), rs.getInt("attempt_number"), rs.getLong("event_id"),
                            State.valueOf(rs.getString("state")), rs.getString("outcome_code"),
                            paid == null ? null : paid.toInstant(ZoneOffset.UTC));
                    if (!request.equals(receipt.requestId()) || !payer.equals(receipt.ownerId())
                            || receipt.ownershipEpoch() < 0 || receipt.attemptNumber() < 1 || receipt.attemptNumber() > MAX_ATTEMPTS
                            || receipt.eventId() <= 0 || receipt.cents() <= 0
                            || !eventKey(request).equals(rs.getString("idempotency_key"))
                            || !EventSource.BILLS.key().equals(rs.getString("source"))
                            || !EventSource.BILLS.direction().moneyFlowKey().equals(rs.getString("direction"))
                            || !receipt.propertyId().equals(Uuids.fromBytes(rs.getBytes("event_property")))
                            || !payer.equals(Uuids.fromBytes(rs.getBytes("counterparty_uuid")))
                            || receipt.ownershipEpoch() != rs.getInt("event_epoch")
                            || !receipt.day().toString().equals(rs.getString("event_day"))
                            || !Uuids.fromBytes(rs.getBytes("benefit_unit_id")).equals(Uuids.fromBytes(rs.getBytes("event_unit")))
                            || receipt.cents() != rs.getLong("requested_amount_cents"))
                        throw new SQLException("Bill receipt binding mismatch");
                    String ledgerState = rs.getString("event_state");
                    if (!Set.of("PENDING", "CLAIMED", "PAID", "FAILED", "AMBIGUOUS").contains(ledgerState))
                        throw new SQLException("Invalid bill receipt ledger state");
                    if (receipt.state() == State.PAID) {
                        LocalDateTime finalized = rs.getObject("finalized_at", LocalDateTime.class);
                        if (!"PAID".equals(ledgerState) || !"PAID".equals(rs.getString("invoice_state"))
                                || !"TRANSFER_CONFIRMED".equals(receipt.outcome()) || receipt.paidAt() == null
                                || finalized == null || !receipt.paidAt().equals(finalized.toInstant(ZoneOffset.UTC))
                                || rs.getObject("paid_amount_cents") == null || rs.getLong("paid_amount_cents") != receipt.cents())
                            throw new SQLException("Bill receipt settlement mismatch");
                    } else if (receipt.paidAt() != null
                            || (receipt.state() == State.WAITING && receipt.outcome() != null)
                            || (receipt.state() == State.DECLINED && (!"FAILED".equals(ledgerState)
                                    || !"PAYMENT_DECLINED".equals(receipt.outcome())
                                    || rs.getObject("paid_amount_cents") == null || rs.getLong("paid_amount_cents") != 0))) {
                        throw new SQLException("Bill receipt state mismatch");
                    }
                    if (rs.next()) throw new SQLException("Duplicate bill receipt");
                    if ((receipt.state() == State.PAID || receipt.state() == State.DECLINED)
                            && !TransferJournal.receiptProvedReadOnly(c, receipt.eventId(), payer, receipt.cents(), receipt.state() == State.PAID))
                        throw new SQLException("Bill receipt lacks transfer proof");
                    return Optional.of(receipt);
                } catch (IllegalArgumentException | NullPointerException | java.time.DateTimeException invalid) {
                    throw new SQLException("Invalid bill receipt", invalid);
                }
            }
        }
    }

    /**
     * Queues one attempt for an ISSUED invoice that is still payable on {@code today}. The caller's maximum and the
     * admission balance are both checked before any row is written.
     */
    public Preparation preparePayment(UUID requestId, UUID invoiceId, UUID ownerId, int ownershipEpoch, LocalDate today,
                                      long maximumCents, long availableCents) throws SQLException {
        return preparePayment(requestId, invoiceId, ownerId, ownershipEpoch, today, maximumCents, availableCents, null);
    }

    /** A supplied property must match before any attempt or event is written. */
    public Preparation preparePayment(UUID requestId, UUID invoiceId, UUID ownerId, int ownershipEpoch, LocalDate today,
                                      long maximumCents, long availableCents, UUID expectedProperty) throws SQLException {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(invoiceId);
        Objects.requireNonNull(ownerId);
        Objects.requireNonNull(today);
        if (maximumCents <= 0) throw new IllegalArgumentException("Invalid bill maximum");
        if (ownershipEpoch < 0 || reserve.equals(ownerId)) throw new IllegalArgumentException("Invalid bill payer");
        return transaction(c -> {
            Invoice invoice = invoice(c, invoiceId);
            if (invoice == null) throw new InvoiceUnavailableException();
            if (expectedProperty != null && !expectedProperty.equals(invoice.propertyId())) throw new InvoiceUnavailableException();
            List<Receipt> history = attempts(c, invoice);
            reconcileScope(c, invoice, history);
            for (Receipt r : history) if (r.requestId().equals(requestId)) return new Preparation(false, r);
            if (requestExists(c, requestId)) throw new SQLException("Bill request UUID payload conflict");
            if (!"ISSUED".equals(invoice.state()) || today.isAfter(invoice.payableUntil()) || today.isBefore(invoice.day())
                    || !invoice.owner().equals(ownerId) || invoice.epoch() != ownershipEpoch)
                throw new InvoiceUnavailableException();
            if (!currentOwner(c, invoice)) throw new InvoiceUnavailableException();
            if (invoice.cents() > maximumCents) throw new MaximumExceededException();
            if (invoice.cents() > availableCents) throw new InsufficientFundsException();
            for (Receipt prior : history) {
                if (prior.state() != State.DECLINED) throw new InvoiceHeldException();
                if (!transfers.confirmedDeclined(c, prior.eventId()))
                    throw new SQLException("Bill retry requires proved no-movement declines");
            }
            if (history.size() >= MAX_ATTEMPTS) throw new InvoiceHeldException();
            EconomyEvent event = EconomyEvent.pending(eventKey(requestId), invoice.cents(), EventSource.BILLS,
                    invoice.propertyId(), ownerId, ownershipEpoch, invoice.benefitUnit(), invoice.day().toString());
            long eventId = EconomyEventRepository.insertPending(c, event);
            if (eventId <= 0) throw new SQLException("Bill event exists without matching receipt");
            try (PreparedStatement ps = prepare(c, """
                    INSERT INTO npc_charge_payments(request_id,invoice_id,attempt_number,event_id,state)
                    VALUES (?,?,?,?,'WAITING')
                    """)) {
                id(ps, 1, requestId);
                id(ps, 2, invoiceId);
                ps.setInt(3, history.size() + 1);
                ps.setLong(4, eventId);
                one(ps.executeUpdate());
            }
            return new Preparation(true, new Receipt(invoiceId, invoice.propertyId(), ownerId, ownershipEpoch, invoice.day(),
                    invoice.cents(), requestId, history.size() + 1, eventId, State.WAITING, null, null));
        });
    }

    /**
     * Settles a WAITING attempt from its proved transfer: PAID marks the invoice PAID, a proved no-movement decline
     * leaves it ISSUED, anything else holds it for staff.
     * A reviewed attempt can decline once the journal proves its recovery.
     */
    public Optional<Receipt> reconcile(long eventId) throws SQLException {
        if (eventId <= 0) return Optional.empty();
        return transaction(c -> {
            UUID invoiceId;
            try (PreparedStatement ps = prepare(c, "SELECT invoice_id FROM npc_charge_payments WHERE event_id=?")) {
                ps.setLong(1, eventId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    invoiceId = Uuids.fromBytes(rs.getBytes(1));
                }
            }
            Invoice invoice = invoice(c, invoiceId);
            if (invoice == null) throw new SQLException("Invoice disappeared");
            List<Receipt> history = attempts(c, invoice);
            reconcileScope(c, invoice, history);
            Receipt receipt = history.stream().filter(r -> r.eventId() == eventId).findFirst()
                    .orElseThrow(() -> new SQLException("Bill attempt disappeared"));
            if (receipt.state() != State.WAITING && receipt.state() != State.REVIEW_REQUIRED) return Optional.of(receipt);
            boolean reviewed = receipt.state() == State.REVIEW_REQUIRED;
            if (reviewed && (!"TRANSFER_UNCERTAIN".equals(receipt.outcome()) || receipt.paidAt() != null))
                throw new SQLException("Invalid reviewed bill receipt");
            Ledger ledger = ledger(c, invoice, receipt);
            if ("PENDING".equals(ledger.state()) || "CLAIMED".equals(ledger.state())) return Optional.of(receipt);
            boolean declined = "FAILED".equals(ledger.state()) && transfers.confirmedDeclined(c, eventId);
            if (reviewed && !declined) return Optional.of(receipt);
            State state = State.REVIEW_REQUIRED;
            String outcome = "TRANSFER_UNCERTAIN";
            Instant paidAt = null;
            if ("PAID".equals(ledger.state()) && transfers.confirmedPaid(c, eventId)) {
                paidAt = ledger.finalizedAt();
                if (paidAt == null || paidAt.isAfter(clock.instant())) throw new SQLException("Bill payment chronology invalid");
                state = State.PAID;
                outcome = "TRANSFER_CONFIRMED";
            } else if (declined) {
                state = State.DECLINED;
                outcome = "PAYMENT_DECLINED";
            }
            try (PreparedStatement ps = prepare(c, """
                    UPDATE npc_charge_payments SET state=?,outcome_code=?,paid_at=? WHERE event_id=? AND state=?
                    """)) {
                ps.setString(1, state.name());
                ps.setString(2, outcome);
                ps.setObject(3, paidAt == null ? null : LocalDateTime.ofInstant(paidAt, ZoneOffset.UTC));
                ps.setLong(4, eventId);
                ps.setString(5, receipt.state().name());
                one(ps.executeUpdate());
            }
            if (state == State.PAID) {
                try (PreparedStatement ps = prepare(c,
                        "UPDATE npc_charge_invoices SET state='PAID' WHERE invoice_id=? AND state='ISSUED'")) {
                    id(ps, 1, invoiceId);
                    one(ps.executeUpdate());
                }
            }
            return Optional.of(new Receipt(invoiceId, invoice.propertyId(), receipt.ownerId(), receipt.ownershipEpoch(),
                    invoice.day(), invoice.cents(), receipt.requestId(), receipt.attemptNumber(), eventId, state, outcome, paidAt));
        });
    }

    /** Every attempt for one invoice, in order. Reads are not payment authority. */
    public List<Receipt> history(UUID invoiceId) throws SQLException {
        Objects.requireNonNull(invoiceId);
        return transaction(c -> {
            Invoice invoice = invoice(c, invoiceId);
            if (invoice == null) return List.of();
            List<Receipt> rows = attempts(c, invoice);
            reconcileScope(c, invoice, rows);
            return rows;
        });
    }

    /**
     * The journal-proved rent paid to this home's owner on {@code day}: every PAID rent event for the home that day
     * whose transfer proof holds. Caller's transaction. Used by bill formulas as {@code rent_paid_cents}.
     */
    public long provedRentPaidCents(Connection c, UUID property, LocalDate day) throws SQLException {
        long total = 0;
        List<long[]> rows = new ArrayList<>();
        try (PreparedStatement ps = prepare(c, """
                SELECT event_id,paid_amount_cents FROM npc_economy_events
                WHERE property_id=? AND source=? AND economy_day=? AND state='PAID' ORDER BY event_id LIMIT 33
                """)) {
            id(ps, 1, property);
            ps.setString(2, EventSource.RENT.key());
            ps.setString(3, day.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (rows.size() >= 32) throw new SQLException("Too many rent events for one home and day");
                    rows.add(new long[]{rs.getLong(1), rs.getLong(2)});
                }
            }
        }
        for (long[] row : rows) {
            if (row[1] <= 0 || !transfers.confirmedPaid(c, row[0])) continue;
            total = Math.addExact(total, row[1]);
        }
        return total;
    }

    private static Invoice invoice(Connection c, UUID invoiceId) throws SQLException {
        try (PreparedStatement ps = prepare(c, """
                SELECT invoice_id,property_id,economy_day,owner_uuid,benefit_unit_id,ownership_epoch,payable_until_day,
                       total_cents,state FROM npc_charge_invoices WHERE invoice_id=? FOR UPDATE
                """)) {
            id(ps, 1, invoiceId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                Invoice invoice = new Invoice(Uuids.fromBytes(rs.getBytes("invoice_id")), Uuids.fromBytes(rs.getBytes("property_id")),
                        LocalDate.parse(rs.getString("economy_day")), Uuids.fromBytes(rs.getBytes("owner_uuid")),
                        Uuids.fromBytes(rs.getBytes("benefit_unit_id")), rs.getInt("ownership_epoch"),
                        LocalDate.parse(rs.getString("payable_until_day")), rs.getLong("total_cents"), rs.getString("state"));
                if (!invoice.invoiceId().equals(invoiceId) || invoice.cents() <= 0 || rs.next())
                    throw new SQLException("Invoice row invalid");
                return invoice;
            }
        }
    }

    private static boolean currentOwner(Connection c, Invoice invoice) throws SQLException {
        try (PreparedStatement ps = prepare(c,
                "SELECT owner_uuid,ownership_epoch,enabled FROM npc_properties WHERE property_id=? FOR UPDATE")) {
            id(ps, 1, invoice.propertyId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                byte[] owner = rs.getBytes("owner_uuid");
                return owner != null && invoice.owner().equals(Uuids.fromBytes(owner))
                        && rs.getInt("ownership_epoch") == invoice.epoch() && rs.getInt("enabled") == 1;
            }
        }
    }

    private static boolean requestExists(Connection c, UUID requestId) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT 1 FROM npc_charge_payments WHERE request_id=?")) {
            id(ps, 1, requestId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    private List<Receipt> attempts(Connection c, Invoice invoice) throws SQLException {
        List<Receipt> rows = new ArrayList<>();
        try (PreparedStatement ps = prepare(c, """
                SELECT p.request_id,p.attempt_number,p.event_id,p.state,p.outcome_code,p.paid_at,e.counterparty_uuid,
                       e.ownership_epoch FROM npc_charge_payments p JOIN npc_economy_events e ON e.event_id=p.event_id
                WHERE p.invoice_id=? ORDER BY p.attempt_number LIMIT 17 FOR UPDATE
                """)) {
            id(ps, 1, invoice.invoiceId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    State state;
                    try { state = State.valueOf(rs.getString("state")); }
                    catch (RuntimeException invalid) { throw new SQLException("Invalid bill payment state", invalid); }
                    LocalDateTime paid = rs.getObject("paid_at", LocalDateTime.class);
                    Receipt r = new Receipt(invoice.invoiceId(), invoice.propertyId(), Uuids.fromBytes(rs.getBytes("counterparty_uuid")),
                            rs.getInt("ownership_epoch"), invoice.day(), invoice.cents(), Uuids.fromBytes(rs.getBytes("request_id")),
                            rs.getInt("attempt_number"), rs.getLong("event_id"), state, rs.getString("outcome_code"),
                            paid == null ? null : paid.toInstant(ZoneOffset.UTC));
                    if (r.attemptNumber() != rows.size() + 1 || rows.size() >= MAX_ATTEMPTS
                            || (!rows.isEmpty() && rows.getLast().state() != State.DECLINED))
                        throw new SQLException("Invalid bill payment history");
                    rows.add(r);
                }
            }
        }
        for (Receipt r : rows) {
            Ledger l = ledger(c, invoice, r);
            if (r.state() == State.PAID && (!"PAID".equals(l.state()) || !transfers.confirmedPaid(c, r.eventId())))
                throw new SQLException("Stored bill payment lacks bound proof");
            if (r.state() == State.DECLINED && (!"FAILED".equals(l.state()) || !transfers.confirmedDeclined(c, r.eventId())))
                throw new SQLException("Stored bill decline lacks no-movement proof");
        }
        return List.copyOf(rows);
    }

    /** Every bills event for this home and invoice day must belong to one of this invoice's attempts. */
    private static void reconcileScope(Connection c, Invoice invoice, List<Receipt> attempts) throws SQLException {
        Set<Long> remaining = new HashSet<>();
        for (Receipt r : attempts) if (!remaining.add(r.eventId())) throw new SQLException("Duplicate bill event");
        try (PreparedStatement ps = prepare(c, """
                SELECT event_id FROM npc_economy_events WHERE property_id=? AND source=? AND economy_day=?
                ORDER BY event_id LIMIT 17 FOR UPDATE
                """)) {
            id(ps, 1, invoice.propertyId());
            ps.setString(2, EventSource.BILLS.key());
            ps.setString(3, invoice.day().toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) if (!remaining.remove(rs.getLong(1)))
                    throw new SQLException("Unmanaged bill event requires review");
            }
        }
        if (!remaining.isEmpty()) throw new SQLException("Bill ledger history incomplete");
    }

    private static Ledger ledger(Connection c, Invoice invoice, Receipt r) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_economy_events WHERE event_id=? FOR UPDATE")) {
            ps.setLong(1, r.eventId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("Bill event missing");
                byte[] property = rs.getBytes("property_id"), payer = rs.getBytes("counterparty_uuid"),
                        unit = rs.getBytes("benefit_unit_id");
                if (!eventKey(r.requestId()).equals(rs.getString("idempotency_key"))
                        || !EventSource.BILLS.key().equals(rs.getString("source"))
                        || !EventSource.BILLS.direction().moneyFlowKey().equals(rs.getString("direction"))
                        || property == null || !invoice.propertyId().equals(Uuids.fromBytes(property))
                        || payer == null || !invoice.owner().equals(Uuids.fromBytes(payer))
                        || rs.getInt("ownership_epoch") != invoice.epoch()
                        || unit == null || !invoice.benefitUnit().equals(Uuids.fromBytes(unit))
                        || !invoice.day().toString().equals(rs.getString("economy_day"))
                        || invoice.cents() != rs.getLong("requested_amount_cents"))
                    throw new SQLException("Bill event binding mismatch");
                String state = rs.getString("state");
                if (state == null || !Set.of("PENDING", "CLAIMED", "PAID", "FAILED", "AMBIGUOUS").contains(state))
                    throw new SQLException("Invalid bill ledger state");
                Long paid = rs.getObject("paid_amount_cents") == null ? null : rs.getLong("paid_amount_cents");
                LocalDateTime finalized = rs.getObject("finalized_at", LocalDateTime.class);
                if (("PENDING".equals(state) || "CLAIMED".equals(state)) && (paid != null || finalized != null))
                    throw new SQLException("Unsettled bill event carries a settlement");
                if (rs.next()) throw new SQLException("Duplicate bill ledger row");
                return new Ledger(state, paid, finalized == null ? null : finalized.toInstant(ZoneOffset.UTC));
            }
        }
    }

    private static void id(PreparedStatement ps, int index, UUID id) throws SQLException { ps.setBytes(index, Uuids.toBytes(id)); }

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

    private static void one(int count) throws SQLException { if (count != 1) throw new SQLException("Bill write count mismatch"); }

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

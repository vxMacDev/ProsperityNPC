package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.ReadSnapshot;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.property.UtilityServiceAssessment;
import com.prosperitysmp.npc.property.UtilityServiceAssessment.Case;
import com.prosperitysmp.npc.property.UtilityServiceAssessment.Outcome;
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
import java.util.Objects;
import java.util.UUID;

/**
 * Worker-only read-only assessment of V13 obligations and their proof-checked receipt producer.
 * Not a wallet reconciler, complete legacy audit, current-owner authorization or premium score.
 * Raw transfer observations are not re-evaluated here: UtilityRepository owns that boundary.
 * Unreconciled transfer outcomes stay unknown, never converted into success by this reader.
 */
public final class UtilityServiceRepository {
    private static final int MAX_CANDIDATES = 4096;
    private final Database database;
    private final Clock clock;

    public UtilityServiceRepository(Database database, Clock clock) {
        this.database = Objects.requireNonNull(database);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Complete matured deadline cohort [dueFrom,capturedAt], no more than 30 elapsed days. */
    public UtilityServiceAssessment read(UUID owner, Instant dueFrom) throws SQLException {
        try (ReadSnapshot snapshot = ReadSnapshot.open(database, clock)) { return read(snapshot, owner, dueFrom); }
    }

    /** Internal composition: the caller owns the shared snapshot and its lifecycle. */
    public UtilityServiceAssessment read(ReadSnapshot snapshot, UUID owner, Instant dueFrom) throws SQLException {
        return readInTransaction(snapshot.connection(database), snapshot.capturedAt(), owner, dueFrom);
    }

    /** Trusted admission revalidation; caller owns the transaction and its captured cutoff. */
    public UtilityServiceAssessment readInTransaction(Connection c, Instant at, UUID owner, Instant dueFrom) throws SQLException {
        Objects.requireNonNull(owner); Objects.requireNonNull(dueFrom);
        ReadSnapshot.requireTransaction(c);
        try {
            // Also checks the public model's UTC bounds before deriving SQL calendar bounds.
            new UtilityServiceAssessment(owner, at, dueFrom, java.util.List.of());
            // Local reset hour 0..23 and ZoneOffset's +/-18h bound place the
            // bill day on the UTC due date or at most two dates before it.
            LocalDate earliest = dueFrom.atOffset(ZoneOffset.UTC).toLocalDate().minusDays(2);
            LocalDate latest = at.atOffset(ZoneOffset.UTC).toLocalDate().plusDays(1);
            if (earliest.getYear() < 1) earliest = LocalDate.of(1, 1, 1);
            if (latest.getYear() > 9998) latest = LocalDate.of(9998, 12, 31);
            var cases = new ArrayList<Case>();
            int candidates = 0;
            try (PreparedStatement ps = c.prepareStatement(QUERY)) {
                ps.setQueryTimeout(5);
                ps.setBytes(1, Uuids.toBytes(owner));
                ps.setString(2, earliest.toString()); ps.setString(3, latest.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (next(c, ps, rs)) {
                        require(++candidates <= MAX_CANDIDATES, "Utility service exceeds bounded cohort");
                        UtilityBill bill = UtilityRepository.readBill(rs);
                        require(owner.equals(bill.ownerId()) && !bill.issuedAt().isAfter(at), "Utility origin owner/time mismatch");
                        // Validate stored clock/hash BEFORE filtering by the derived deadline.
                        if (bill.dueAt().isBefore(dueFrom) || bill.dueAt().isAfter(at)) continue;
                        cases.add(readCase(rs, bill, at));
                    }
                }
            }
            return new UtilityServiceAssessment(owner, at, dueFrom, cases);
        } catch (RuntimeException failure) {
            throw new SQLException("Invalid utility service evidence", failure);
        }
    }

    private static Case readCase(ResultSet rs, UtilityBill b, Instant at) throws SQLException {
        require(b.propertyId().equals(uuid(rs, "current_property", false)), "Utility property disappeared");
        UUID currentOwner = uuid(rs, "current_owner", true);
        long currentEpoch = number(rs, "current_epoch", 0, Integer.MAX_VALUE);
        require(currentEpoch >= b.ownershipEpoch(), "Utility ownership epoch regressed");
        boolean originalOwner = b.ownerId().equals(currentOwner) && currentEpoch == b.ownershipEpoch();
        long count = number(rs, "attempt_count", 0, 16);
        long eventCount = number(rs, "scope_event_count", 0, 16);
        require(count == eventCount && number(rs, "matched_event_count", 0, 16) == count,
                "Utility scope contains unmanaged or missing events");
        if (count == 0) {
            require(rs.getObject("payment_event") == null && rs.getObject("attempt_max") == null,
                    "Unexpected utility attempt");
            return new Case(b, null, null, originalOwner ? Outcome.UNVERIFIED_PAYMENT : Outcome.HANDOVER_REVIEW);
        }
        long attempt = number(rs, "payment_attempt", 1, 16);
        require(attempt == count && number(rs, "attempt_max", 1, 16) == count
                && number(rs, "prior_declines", 0, 15) == count - 1,
                "Utility attempt history is noncontiguous or unresolved");
        long event = number(rs, "payment_event", 1, Long.MAX_VALUE);
        UUID request = uuid(rs, "payment_request", false);
        require(b.propertyId().equals(uuid(rs, "payment_property", false))
                && b.day().toString().equals(rs.getString("payment_day")), "Utility payment scope mismatch");
        String state = rs.getString("payment_state"), outcome = rs.getString("payment_outcome");
        Instant paidAt = time(rs, "payment_paid_at", true);
        require(event == number(rs, "ledger_event", 1, Long.MAX_VALUE)
                && ("utility|" + request).equals(rs.getString("ledger_key"))
                && EventSource.UTILITY.key().equals(rs.getString("ledger_source"))
                && EventSource.UTILITY.direction().moneyFlowKey().equals(rs.getString("ledger_direction"))
                && b.propertyId().equals(uuid(rs, "ledger_property", false))
                && b.ownerId().equals(uuid(rs, "ledger_payer", false))
                && b.ownerId().equals(uuid(rs, "ledger_benefit", false))
                && b.ownershipEpoch() == number(rs, "ledger_epoch", 0, Integer.MAX_VALUE)
                && b.day().toString().equals(rs.getString("ledger_day"))
                && b.cents() == number(rs, "ledger_requested", 1, Long.MAX_VALUE), "Utility service ledger binding mismatch");
        String ledger = rs.getString("ledger_state");
        require(ledger != null && java.util.Set.of("PENDING", "CLAIMED", "PAID", "FAILED", "AMBIGUOUS").contains(ledger),
                "Utility service ledger state invalid");
        Long paid = rs.getObject("ledger_paid") == null ? null : number(rs, "ledger_paid", 0, Long.MAX_VALUE);
        Instant created = time(rs, "ledger_created", false);
        Instant finalized = time(rs, "ledger_finalized", true), claimed = time(rs, "ledger_claimed", true);
        require(!created.isBefore(b.issuedAt()) && !created.isAfter(at), "Utility event creation time invalid");
        if (finalized != null) require(!finalized.isBefore(b.issuedAt()) && !finalized.isAfter(at), "Utility finalization time invalid");
        if (claimed != null) require(!claimed.isBefore(b.issuedAt()) && !claimed.isAfter(at), "Utility claim time invalid");
        if (claimed != null && finalized != null) require(!claimed.isAfter(finalized), "Utility ledger times reversed");
        if (claimed != null) require(!claimed.isBefore(created), "Utility claim precedes event creation");
        if (finalized != null) require(!finalized.isBefore(created), "Utility finalization precedes event creation");
        if ("CLAIMED".equals(ledger) || "PAID".equals(ledger) || "FAILED".equals(ledger))
            require(claimed != null, "Claimed or settled utility lacks claim time");
        if ("PENDING".equals(ledger)) require(paid == null && finalized == null && claimed == null, "Pending utility has settlement fields");
        if ("CLAIMED".equals(ledger)) require(paid == null && finalized == null, "Claimed utility has settlement fields");
        if ("PAID".equals(ledger)) require(Objects.equals(paid, b.cents()) && finalized != null, "Paid utility ledger amount/time invalid");
        if ("FAILED".equals(ledger)) require(Objects.equals(paid, 0L) && finalized != null, "Declined utility ledger amount/time invalid");

        if ("PAID".equals(state)) {
            require("TRANSFER_CONFIRMED".equals(outcome) && "PAID".equals(ledger) && paidAt != null
                    && paidAt.equals(finalized), "Utility paid receipt disagrees with ledger");
            // This terminal receipt was written only after both transfer proofs were checked.
            return new Case(b, event, paidAt, paidAt.isAfter(b.dueAt()) ? Outcome.LATE : Outcome.ON_TIME);
        }
        require(paidAt == null, "Unpaid utility receipt carries payment time");
        boolean knownUnpaid;
        if ("DECLINED".equals(state)) {
            require("PAYMENT_DECLINED".equals(outcome) && "FAILED".equals(ledger), "Utility decline receipt mismatch");
            knownUnpaid = true;
        } else if ("WAITING".equals(state)) {
            require(outcome == null, "Waiting utility has terminal outcome");
            knownUnpaid = "PENDING".equals(ledger);
        } else if ("REVIEW_REQUIRED".equals(state)) {
            require(outcome != null && outcome.matches("[A-Z_]{1,48}"), "Utility review outcome invalid");
            knownUnpaid = false;
        } else throw new SQLException("Utility payment state invalid");
        Outcome classified = !originalOwner ? Outcome.HANDOVER_REVIEW
                : knownUnpaid ? Outcome.OVERDUE : Outcome.UNVERIFIED_PAYMENT;
        return new Case(b, event, null, classified);
    }

    private static final String QUERY = """
            SELECT b.*,
              p.property_id AS current_property,p.owner_uuid AS current_owner,p.ownership_epoch AS current_epoch,
              (SELECT COUNT(*) FROM npc_utility_payments a
                WHERE a.property_id=b.property_id AND a.economy_day=b.economy_day) AS attempt_count,
              (SELECT MAX(a.attempt_number) FROM npc_utility_payments a
                WHERE a.property_id=b.property_id AND a.economy_day=b.economy_day) AS attempt_max,
              (SELECT COUNT(*) FROM npc_utility_payments a
                WHERE a.property_id=b.property_id AND a.economy_day=b.economy_day
                AND a.attempt_number<q.attempt_number AND BINARY a.state='DECLINED') AS prior_declines,
              (SELECT COUNT(*) FROM npc_economy_events e0
                WHERE e0.property_id=b.property_id AND e0.economy_day=b.economy_day
                AND e0.source='npc-property-utility') AS scope_event_count,
              (SELECT COUNT(*) FROM npc_utility_payments a JOIN npc_economy_events linked ON linked.event_id=a.event_id
                WHERE a.property_id=b.property_id AND a.economy_day=b.economy_day
                AND linked.property_id=b.property_id AND linked.economy_day=b.economy_day
                AND BINARY linked.source='npc-property-utility') AS matched_event_count,
              q.event_id AS payment_event,q.request_id AS payment_request,q.attempt_number AS payment_attempt,
              q.property_id AS payment_property,q.economy_day AS payment_day,q.state AS payment_state,
              q.outcome_code AS payment_outcome,q.paid_at AS payment_paid_at,
              e.event_id AS ledger_event,e.idempotency_key AS ledger_key,e.source AS ledger_source,
              e.direction AS ledger_direction,e.property_id AS ledger_property,e.counterparty_uuid AS ledger_payer,
              e.ownership_epoch AS ledger_epoch,e.benefit_unit_id AS ledger_benefit,e.economy_day AS ledger_day,
              e.requested_amount_cents AS ledger_requested,e.paid_amount_cents AS ledger_paid,
              e.state AS ledger_state,e.created_at AS ledger_created,e.claimed_at AS ledger_claimed,e.finalized_at AS ledger_finalized
            FROM npc_utility_bills b
            LEFT JOIN npc_properties p ON p.property_id=b.property_id
            LEFT JOIN npc_utility_payments q ON q.property_id=b.property_id AND q.economy_day=b.economy_day
              AND q.attempt_number=(SELECT MAX(a.attempt_number) FROM npc_utility_payments a
                WHERE a.property_id=b.property_id AND a.economy_day=b.economy_day)
            LEFT JOIN npc_economy_events e ON e.event_id=q.event_id
            WHERE b.owner_uuid=? AND b.economy_day>=? AND b.economy_day<=?
            ORDER BY b.economy_day,b.property_id
            LIMIT 4097 ROWS EXAMINED 262144
            """;

    private static UUID uuid(ResultSet rs, String column, boolean nullable) throws SQLException {
        byte[] bytes = rs.getBytes(column);
        if (nullable && bytes == null) return null;
        require(bytes != null && bytes.length == 16, "Invalid utility service identity");
        return Uuids.fromBytes(bytes);
    }
    private static long number(ResultSet rs, String column, long min, long max) throws SQLException {
        long value = rs.getLong(column);
        require(!rs.wasNull() && value >= min && value <= max, "Invalid utility service number");
        return value;
    }
    private static Instant time(ResultSet rs, String column, boolean nullable) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        if (nullable && value == null) return null;
        require(value != null && value.getYear() >= 1 && value.getYear() <= 9999 && value.getNano() % 1_000_000 == 0,
                "Invalid utility service time");
        return value.toInstant(ZoneOffset.UTC);
    }
    private static void require(boolean valid, String message) throws SQLException { if (!valid) throw new SQLException(message); }
    private static boolean next(Connection c, PreparedStatement ps, ResultSet rs) throws SQLException {
        warnings(c, ps, rs); boolean next = rs.next(); warnings(c, ps, rs); return next;
    }
    private static void warnings(Connection c, PreparedStatement ps, ResultSet rs) throws SQLException {
        if (c.getWarnings() != null || ps.getWarnings() != null || (rs != null && rs.getWarnings() != null))
            throw new SQLException("Incomplete utility service evidence");
    }
}

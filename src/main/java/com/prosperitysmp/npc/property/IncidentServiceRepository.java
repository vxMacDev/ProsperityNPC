package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.ReadSnapshot;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.economy.PurchaseRequest;
import com.prosperitysmp.npc.economy.RepairQuote;
import com.prosperitysmp.npc.formulas.IncidentType;
import com.prosperitysmp.npc.property.IncidentServiceAssessment.Case;
import com.prosperitysmp.npc.property.IncidentServiceAssessment.Outcome;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;

/**
 * Worker-only, read-only incident deadline cohort, not an overall landlord rating.
 * Original incident rolls identify responsibility; service receipts prove completed
 * repairs. Neither occupancy nor a quiet/blocked day earns a service observation.
 * Legacy incidents without a roll are OUTSIDE this evidence scope, not successful.
 */
public final class IncidentServiceRepository {
    private final Database database;
    private final Clock clock;

    public IncidentServiceRepository(Database database, Clock clock) {
        this.database = Objects.requireNonNull(database);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Matured deadlines only, inclusive [dueFrom,capturedAt], maximum trailing 30 days. */
    public IncidentServiceAssessment read(UUID owner, Instant dueFrom) throws SQLException {
        try (ReadSnapshot snapshot = ReadSnapshot.open(database, clock)) { return read(snapshot, owner, dueFrom); }
    }

    /** Internal composition: never commits, rolls back or opens a second connection. */
    public IncidentServiceAssessment read(ReadSnapshot snapshot, UUID owner, Instant dueFrom) throws SQLException {
        return readInTransaction(snapshot.connection(database), snapshot.capturedAt(), owner, dueFrom);
    }

    /** Trusted admission revalidation; caller owns the transaction and its captured cutoff. */
    public IncidentServiceAssessment readInTransaction(Connection c, Instant at, UUID owner, Instant dueFrom) throws SQLException {
        Objects.requireNonNull(owner); Objects.requireNonNull(dueFrom);
        ReadSnapshot.requireTransaction(c);
        try {
            if (!dueFrom.isBefore(at) || Duration.between(dueFrom, at).compareTo(Duration.ofDays(30)) > 0)
                throw new IllegalArgumentException("Service evidence window must be positive and at most 30 days");
            // Roll v7 creates exactly a 24-hour obligation. Bound on that immutable
            // origin, not a mutable incident due_at which could hide changed terms.
            Instant firstMillisecond = dueFrom.truncatedTo(ChronoUnit.MILLIS);
            if (firstMillisecond.isBefore(dueFrom)) firstMillisecond = firstMillisecond.plusMillis(1);
            var cases = new ArrayList<Case>();
            try (PreparedStatement ps = c.prepareStatement(QUERY)) {
                ps.setQueryTimeout(5);
                ps.setBytes(1, Uuids.toBytes(owner));
                ps.setObject(2, sqlTime(firstMillisecond.minus(Duration.ofDays(1))));
                ps.setObject(3, sqlTime(at.minus(Duration.ofDays(1))));
                try (ResultSet rs = ps.executeQuery()) {
                    while (next(c, ps, rs)) {
                        require(cases.size() < 512, "Service evidence exceeds bounded cohort");
                        cases.add(readCase(rs, owner, at));
                    }
                }
            }
            return new IncidentServiceAssessment(owner, at, dueFrom, cases);
        } catch (RuntimeException failure) {
            throw new SQLException("Invalid incident service evidence", failure);
        }
    }

    private static Case readCase(ResultSet rs, UUID owner, Instant at) throws SQLException {
        UUID property = uuid(rs, "roll_property", false);
        long incident = number(rs, "roll_incident", 1, Long.MAX_VALUE);
        int epoch = (int) number(rs, "roll_epoch", 0, Integer.MAX_VALUE);
        require(owner.equals(uuid(rs, "roll_owner", false)), "Service origin owner mismatch");
        IncidentType type = IncidentType.valueOf(rs.getString("roll_type"));
        int before = (int) number(rs, "condition_before", 0, 100);
        int after = (int) number(rs, "condition_after", 0, 100);
        require(after == Math.max(0, before - type.conditionDamage()), "Service origin damage mismatch");
        Instant created = time(rs, "roll_at", false), due = created.plus(Duration.ofDays(1));
        LocalDate rollDay = day(rs.getString("roll_day"));
        require(incident == number(rs, "incident_id", 1, Long.MAX_VALUE)
                && property.equals(uuid(rs, "incident_property", false))
                && property.equals(uuid(rs, "current_property", false))
                && type.name().equals(rs.getString("incident_type"))
                && type.cashRepairCents() == number(rs, "cash_repair_cents", 1, Long.MAX_VALUE)
                && type.conditionDamage() == number(rs, "condition_damage", 0, 100)
                && type.itemRepairSpec().equals(rs.getString("item_repair_spec"))
                && rollDay.equals(day(rs.getString("incident_day")))
                && created.equals(time(rs, "created_at", false))
                && due.equals(time(rs, "due_at", false))
                && number(rs, "prevented", 0, 1) == 0,
                "Incident disagrees with recorded service obligation");
        Instant resolved = time(rs, "resolved_at", true);
        UUID resolver = uuid(rs, "resolver_uuid", true);
        require((resolved == null) == (resolver == null), "Incomplete service resolution");
        if (resolved != null) require(!resolved.isBefore(created) && !resolved.isAfter(at), "Invalid service resolution time");
        UUID currentOwner = uuid(rs, "current_owner", true);
        int currentEpoch = (int) number(rs, "current_epoch", 0, Integer.MAX_VALUE);
        require(currentEpoch >= epoch, "Service ownership epoch regressed");

        boolean cash = rs.getObject("service_event") != null, items = rs.getBytes("item_collection") != null;
        require(!(cash && items), "Both cash and item service facts for one incident");
        if (items) return itemCase(rs, owner, property, incident, epoch, currentEpoch, type, created, due, resolved, resolver);
        if (!cash) {
            Outcome outcome = resolved != null ? Outcome.UNVERIFIED_RESOLUTION
                    : owner.equals(currentOwner) && epoch == currentEpoch ? Outcome.OVERDUE : Outcome.HANDOVER_REVIEW;
            return new Case(incident, property, epoch, created, due, resolved, null, outcome);
        }
        long event = number(rs, "service_event", 1, Long.MAX_VALUE);
        UUID payer = uuid(rs, "service_payer", false);
        int repairEpoch = (int) number(rs, "service_epoch", 0, Integer.MAX_VALUE);
        require(repairEpoch >= epoch && repairEpoch <= currentEpoch, "Service repair ownership epoch invalid");
        require(resolved != null && payer.equals(resolver)
                && property.equals(uuid(rs, "service_property", false))
                && type.name().equals(rs.getString("service_type"))
                && type.cashRepairCents() == number(rs, "service_cash", 1, Long.MAX_VALUE)
                && created.equals(time(rs, "service_created", false))
                && due.equals(time(rs, "service_due", false))
                && resolved.equals(time(rs, "service_repaired", false)), "Service completion fact mismatch");

        // This fact is written only inside the proof-checked benefit transaction.
        // Still bind its purchase and ledger identities; PAID/resolved labels alone
        // never create a service fact or backfill historical successful repairs.
        RepairQuote repair = new RepairQuote(incident, type, rs.getString("service_hash"));
        var q = new PurchaseRequest(uuid(rs, "purchase_request", false), property, payer, repairEpoch,
                PurchaseRequest.Kind.REPAIR, null, null, null, day(rs.getString("purchase_day")), repair);
        require(event == number(rs, "purchase_event", 1, Long.MAX_VALUE)
                && property.equals(uuid(rs, "purchase_property", false))
                && payer.equals(uuid(rs, "purchase_payer", false))
                && repairEpoch == number(rs, "purchase_epoch", 0, Integer.MAX_VALUE)
                && "REPAIR".equals(rs.getString("purchase_kind"))
                && "APPLIED".equals(rs.getString("purchase_state"))
                && "BENEFIT_APPLIED".equals(rs.getString("purchase_outcome"))
                && time(rs, "purchase_completed", false) != null
                && q.actionKey().equals(rs.getString("purchase_action"))
                && q.cents() == number(rs, "purchase_cash", 1, Long.MAX_VALUE)
                && incident == number(rs, "purchase_incident", 1, Long.MAX_VALUE)
                && type.name().equals(rs.getString("purchase_type"))
                && repair.snapshotSha256().equals(rs.getString("purchase_hash")), "Service purchase binding mismatch");
        require(event == number(rs, "ledger_event", 1, Long.MAX_VALUE)
                && "PAID".equals(rs.getString("ledger_state"))
                && q.eventKey().equals(rs.getString("ledger_key"))
                && q.source().key().equals(rs.getString("ledger_source"))
                && q.source().direction().moneyFlowKey().equals(rs.getString("ledger_direction"))
                && property.equals(uuid(rs, "ledger_property", false))
                && payer.equals(uuid(rs, "ledger_payer", false))
                && payer.equals(uuid(rs, "ledger_benefit", false))
                && repairEpoch == number(rs, "ledger_epoch", 0, Integer.MAX_VALUE)
                && q.day().equals(day(rs.getString("ledger_day")))
                && q.cents() == number(rs, "ledger_requested", 1, Long.MAX_VALUE)
                && q.cents() == number(rs, "ledger_paid", 1, Long.MAX_VALUE)
                && !resolved.isBefore(time(rs, "ledger_finalized", false)), "Service ledger binding mismatch");
        Outcome outcome = !payer.equals(owner) || repairEpoch != epoch ? Outcome.HANDOVER_REVIEW
                : resolved.isAfter(due) ? Outcome.LATE : Outcome.ON_TIME;
        return new Case(incident, property, epoch, created, due, resolved, event, outcome);
    }

    /**
     * An item repair counts like a cash repair only when its receipt binds an APPLIED hand-in for the same incident,
     * home, payer (the resolver), type, cash equivalent, value, times and snapshot. Anything else throws, so a forged
     * receipt can never raise a rating. Item repairs add no rent funding; this is service history only.
     */
    private static Case itemCase(ResultSet rs, UUID owner, UUID property, long incident, int epoch, int currentEpoch,
                                 IncidentType type, Instant created, Instant due, Instant resolved, UUID resolver)
            throws SQLException {
        UUID collection = uuid(rs, "item_collection", false);
        UUID payer = uuid(rs, "item_payer", false);
        int repairEpoch = (int) number(rs, "item_epoch", 0, Integer.MAX_VALUE);
        require(repairEpoch >= epoch && repairEpoch <= currentEpoch, "Item repair ownership epoch invalid");
        long cash = number(rs, "item_cash", 1, Long.MAX_VALUE);
        long value = number(rs, "item_value", 1, Long.MAX_VALUE);
        require(resolved != null && payer.equals(resolver)
                && property.equals(uuid(rs, "item_property", false))
                && type.name().equals(rs.getString("item_type"))
                && type.cashRepairCents() == cash && value >= cash
                && created.equals(time(rs, "item_created", false))
                && due.equals(time(rs, "item_due", false))
                && resolved.equals(time(rs, "item_repaired", false)), "Item repair completion fact mismatch");
        String hash = rs.getString("item_hash");
        require(collection.equals(uuid(rs, "col_id", false))
                && "APPLIED".equals(rs.getString("col_state"))
                && incident == number(rs, "col_incident", 1, Long.MAX_VALUE)
                && property.equals(uuid(rs, "col_property", false))
                && payer.equals(uuid(rs, "col_player", false))
                && repairEpoch == number(rs, "col_epoch", 0, Integer.MAX_VALUE)
                && type.name().equals(rs.getString("col_type"))
                && cash == number(rs, "col_cash", 1, Long.MAX_VALUE)
                && value == number(rs, "col_value", 1, Long.MAX_VALUE)
                && hash != null && hash.matches("[a-f0-9]{64}") && hash.equals(rs.getString("col_hash")),
                "Item repair hand-in binding mismatch");
        Outcome outcome = !payer.equals(owner) || repairEpoch != epoch ? Outcome.HANDOVER_REVIEW
                : resolved.isAfter(due) ? Outcome.LATE : Outcome.ON_TIME;
        return new Case(incident, property, epoch, created, due, resolved, null, collection, outcome);
    }

    private static final String QUERY = """
            SELECT r.property_id AS roll_property,r.owner_uuid AS roll_owner,r.ownership_epoch AS roll_epoch,
              r.incident_id AS roll_incident,r.incident_type AS roll_type,r.economy_day AS roll_day,
              r.recorded_at AS roll_at,r.condition_before,r.condition_after,
              i.incident_id,i.property_id AS incident_property,i.incident_type,i.cash_repair_cents,
              i.condition_damage,i.item_repair_spec,i.economy_day AS incident_day,i.created_at,i.due_at,
              i.resolved_at,i.resolver_uuid,i.prevented,
              p.property_id AS current_property,p.owner_uuid AS current_owner,p.ownership_epoch AS current_epoch,
              s.event_id AS service_event,s.property_id AS service_property,s.payer_uuid AS service_payer,
              s.ownership_epoch AS service_epoch,s.incident_type AS service_type,s.cash_cents AS service_cash,
              s.repair_snapshot_sha256 AS service_hash,s.incident_created_at AS service_created,
              s.incident_due_at AS service_due,s.repaired_at AS service_repaired,
              q.event_id AS purchase_event,q.request_id AS purchase_request,q.property_id AS purchase_property,
              q.payer_uuid AS purchase_payer,q.ownership_epoch AS purchase_epoch,q.kind AS purchase_kind,
              q.state AS purchase_state,q.outcome_code AS purchase_outcome,q.completed_at AS purchase_completed,
              q.economy_day AS purchase_day,q.action_key AS purchase_action,q.quoted_amount_cents AS purchase_cash,
              q.repair_incident_id AS purchase_incident,q.repair_type AS purchase_type,q.repair_snapshot_sha256 AS purchase_hash,
              e.event_id AS ledger_event,e.state AS ledger_state,e.idempotency_key AS ledger_key,
              e.source AS ledger_source,e.direction AS ledger_direction,e.property_id AS ledger_property,
              e.counterparty_uuid AS ledger_payer,e.benefit_unit_id AS ledger_benefit,e.ownership_epoch AS ledger_epoch,
              e.economy_day AS ledger_day,e.requested_amount_cents AS ledger_requested,
              e.paid_amount_cents AS ledger_paid,e.finalized_at AS ledger_finalized,
              x.collection_id AS item_collection,x.property_id AS item_property,x.payer_uuid AS item_payer,
              x.ownership_epoch AS item_epoch,x.incident_type AS item_type,x.cash_equivalent_cents AS item_cash,
              x.recipe_value_cents AS item_value,x.repair_snapshot_sha256 AS item_hash,
              x.incident_created_at AS item_created,x.incident_due_at AS item_due,x.repaired_at AS item_repaired,
              k.collection_id AS col_id,k.state AS col_state,k.incident_id AS col_incident,k.property_id AS col_property,
              k.player_id AS col_player,k.ownership_epoch AS col_epoch,k.incident_type AS col_type,
              k.cash_cents AS col_cash,k.recipe_value_cents AS col_value,k.repair_snapshot_sha256 AS col_hash
            FROM npc_incident_rolls r
            LEFT JOIN npc_incidents i ON i.incident_id=r.incident_id
            LEFT JOIN npc_properties p ON p.property_id=r.property_id
            LEFT JOIN npc_incident_service_receipts s ON s.incident_id=r.incident_id
            LEFT JOIN npc_purchase_intents q ON q.event_id=s.event_id
            LEFT JOIN npc_economy_events e ON e.event_id=s.event_id
            LEFT JOIN npc_incident_item_repair_receipts x ON x.incident_id=r.incident_id
            LEFT JOIN npc_item_repair_collections k ON k.collection_id=x.collection_id
            WHERE r.owner_uuid=? AND r.outcome='INCIDENT' AND r.recorded_at>=? AND r.recorded_at<=?
            ORDER BY r.recorded_at,r.property_id LIMIT 513 ROWS EXAMINED 65536
            """;

    private static LocalDateTime sqlTime(Instant at) {
        LocalDateTime time = LocalDateTime.ofInstant(at, ZoneOffset.UTC);
        if (time.getYear() < 1 || time.getYear() > 9999) throw new IllegalArgumentException("Service time out of SQL range");
        return time;
    }
    private static Instant time(ResultSet rs, String column, boolean nullable) throws SQLException {
        LocalDateTime value = rs.getObject(column, LocalDateTime.class);
        if (value == null) { require(nullable, "Missing service time: " + column); return null; }
        require(value.getYear() >= 1 && value.getYear() <= 9999, "Invalid service time: " + column);
        return value.toInstant(ZoneOffset.UTC);
    }
    private static LocalDate day(String value) {
        if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}")) throw new IllegalArgumentException("Invalid service day");
        LocalDate day = LocalDate.parse(value);
        if (day.getYear() < 1) throw new IllegalArgumentException("Invalid service day");
        return day;
    }
    private static UUID uuid(ResultSet rs, String column, boolean nullable) throws SQLException {
        byte[] bytes = rs.getBytes(column);
        if (bytes == null) { require(nullable, "Missing service identity: " + column); return null; }
        require(bytes.length == 16, "Invalid service identity: " + column);
        return Uuids.fromBytes(bytes);
    }
    private static long number(ResultSet rs, String column, long min, long max) throws SQLException {
        long value = rs.getLong(column);
        require(!rs.wasNull() && value >= min && value <= max, "Invalid service number: " + column);
        return value;
    }
    private static boolean next(Connection c, PreparedStatement ps, ResultSet rs) throws SQLException {
        warnings(c, ps, rs); boolean next = rs.next(); warnings(c, ps, rs); return next;
    }
    private static void warnings(Connection c, PreparedStatement ps, ResultSet rs) throws SQLException {
        if (c.getWarnings() != null || ps.getWarnings() != null || (rs != null && rs.getWarnings() != null))
            throw new SQLException("Incomplete service evidence read");
    }
    private static void require(boolean condition, String message) throws SQLException {
        if (!condition) throw new SQLException(message);
    }
}

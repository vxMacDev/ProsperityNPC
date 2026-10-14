package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.formulas.IncidentType;
import com.prosperitysmp.npc.formulas.UpgradeType;
import com.prosperitysmp.npc.resident.RelationshipMemory;
import com.prosperitysmp.npc.resident.ResidentRepository;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Exact cash-repair effects only. Caller holds the property lock, verifies payment/ownership,
 * and commits the receipt with these effects. Every failure requires caller rollback.
 * This is not a runtime authorization boundary and does not authorize generic maintenance.
 */
final class IncidentRepair {
    private static final int MAX_RESIDENTS = 4;
    private static final int MAX_UPGRADES = 2;

    private record Property(int capacity, int condition, long revision) { }
    private record Incident(long id, UUID property, IncidentType type, long cents, String items,
                            int damage, UpgradeType affected, LocalDate day, LocalDateTime created,
                            LocalDateTime due, LocalDateTime resolved, UUID resolver, boolean prevented) { }
    private record Resident(UUID id, int slot, long revision) { }
    // V1 upgrades have no revision column; bind every stored identity/term instead.
    private record Upgrade(long id, UpgradeType type, int level, long cost,
                           LocalDateTime installed, long disabledIncident) { }
    private record Snapshot(Property property, Incident incident, List<Resident> residents,
                            List<Upgrade> upgrades, RepairQuote quote) { }

    private IncidentRepair() { }

    static RepairQuote quote(Connection c, UUID propertyId, long incidentId, Instant at,
                             LocalDate currentDay, LocalDate quoteDay) throws SQLException {
        requireTransaction(c);
        Snapshot current = snapshot(c, propertyId, incidentId);
        completionTime(at, currentDay, quoteDay, current.incident());
        return current.quote();
    }

    /** Domain rejection is named; JDBC failures/warnings still escape and require rollback. */
    static String targetProblem(Connection c, PurchaseRequest q, Instant at, LocalDate currentDay) throws SQLException {
        return targetProblem(c, q, at, currentDay, false);
    }

    /**
     * blockWhileItemsHeld (item-repairs.block-cash-while-items-held): an item hand-in for this incident that is
     * running or held for staff refuses the cash repair with REPAIR_ITEMS_PENDING, before any debit. It reads only
     * item-repair rows, which exist only after that feature ran, so with none it changes nothing.
     */
    static String targetProblem(Connection c, PurchaseRequest q, Instant at, LocalDate currentDay,
                                boolean blockWhileItemsHeld) throws SQLException {
        requireTransaction(c);
        try {
            requireRequest(q);
            Snapshot current = snapshot(c, q.propertyId(), q.repair().incidentId());
            completionTime(at, currentDay, q.day(), current.incident());
            if (blockWhileItemsHeld && itemsHeld(c, q.repair().incidentId())) return "REPAIR_ITEMS_PENDING";
            return q.repair().equals(current.quote()) ? null : "REPAIR_TARGET_CHANGED";
        } catch (TargetFailure failure) {
            return failure.getMessage();
        }
    }

    /** An item hand-in for this incident is running (INTENT) or held for staff (AMBIGUOUS). */
    static boolean itemsHeld(Connection c, long incidentId) throws SQLException {
        try (PreparedStatement ps = prepare(c, """
                SELECT collection_id FROM npc_item_repair_collections
                WHERE incident_id=? AND state IN ('INTENT','AMBIGUOUS') LIMIT 1 FOR UPDATE
                """, incidentId); ResultSet rs = ps.executeQuery()) {
            return next(c, ps, rs);
        }
    }

    /** Item hand-in quote check under the caller's locks: null when the frozen repair still matches. */
    static String itemTargetProblem(Connection c, ItemRepairQuote q, Instant at, LocalDate currentDay) throws SQLException {
        requireTransaction(c);
        try {
            require(q != null, "REPAIR_QUOTE_INVALID");
            Snapshot current = snapshot(c, q.propertyId(), q.repair().incidentId());
            completionTime(at, currentDay, q.quoteDay(), current.incident());
            return q.repair().equals(current.quote()) ? null : "REPAIR_TARGET_CHANGED";
        } catch (TargetFailure failure) {
            return failure.getMessage();
        }
    }

    /** Domain failure names thrown by IncidentRepair, so callers can tell a changed target from a JDBC failure. */
    static boolean isTargetFailure(SQLException failure) { return failure instanceof TargetFailure; }

    static void apply(Connection c, PurchaseRepository.Receipt receipt, Instant at,
                      LocalDate completionDay) throws SQLException {
        requireTransaction(c);
        require(receipt != null && receipt.eventId() > 0
                && receipt.state() == PurchaseRepository.State.WAITING, "REPAIR_RECEIPT_INVALID");
        PurchaseRequest q = receipt.request();
        requireRequest(q);
        require(receipt.quotedCents() == q.repair().type().cashRepairCents(), "QUOTE_PRICE_MISMATCH");
        Snapshot current = snapshot(c, q.propertyId(), q.repair().incidentId());
        require(q.repair().equals(current.quote()), "REPAIR_TARGET_CHANGED");
        Incident incident = current.incident();
        LocalDateTime completed = completionTime(at, completionDay, q.day(), incident);
        applyBenefit(c, current, q.propertyId(), q.payerId(), completed, completionDay,
                "repair:" + incident.id() + ":event:" + receipt.eventId());
        // Same transaction as resolution, condition, facilities, memories and APPLIED.
        // No upsert: a duplicate is a broken lifecycle, not permission to reapply benefits.
        try (PreparedStatement ps = prepare(c, """
                INSERT INTO npc_incident_service_receipts (incident_id,event_id,property_id,payer_uuid,
                  ownership_epoch,incident_type,cash_cents,repair_snapshot_sha256,
                  incident_created_at,incident_due_at,repaired_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, incident.id(), receipt.eventId(), q.propertyId(), q.payerId(), q.ownershipEpoch(),
                incident.type().name(), receipt.quotedCents(), q.repair().snapshotSha256(),
                incident.created(), incident.due(), completed)) {
            require(updated(c, ps) == 1, "REPAIR_SERVICE_RECEIPT_INVALID");
        }
        warnings(c, null, null);
    }

    /**
     * Item repair: the same effects as a cash repair, no money. Caller holds the custody player guard and the
     * property lock, has proven the items durably taken, and commits this with the hand-in's APPLIED state.
     * Writes the item service receipt instead of the cash one. Every failure requires caller rollback.
     */
    static void applyItems(Connection c, ItemRepairQuote q, Instant at, LocalDate completionDay) throws SQLException {
        requireTransaction(c);
        require(q != null && q.cashCents() == q.repair().type().cashRepairCents(), "REPAIR_QUOTE_INVALID");
        Snapshot current = snapshot(c, q.propertyId(), q.repair().incidentId());
        require(q.repair().equals(current.quote()), "REPAIR_TARGET_CHANGED");
        Incident incident = current.incident();
        LocalDateTime completed = completionTime(at, completionDay, q.quoteDay(), incident);
        applyBenefit(c, current, q.propertyId(), q.playerId(), completed, completionDay,
                "repair:" + incident.id() + ":items:" + q.collectionId());
        try (PreparedStatement ps = prepare(c, """
                INSERT INTO npc_incident_item_repair_receipts (incident_id,collection_id,property_id,payer_uuid,
                  ownership_epoch,incident_type,cash_equivalent_cents,recipe_value_cents,repair_snapshot_sha256,
                  incident_created_at,incident_due_at,repaired_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """, incident.id(), q.collectionId(), q.propertyId(), q.playerId(), q.ownershipEpoch(),
                incident.type().name(), q.cashCents(), q.recipeValueCents(), q.repair().snapshotSha256(),
                incident.created(), incident.due(), completed)) {
            require(updated(c, ps) == 1, "REPAIR_SERVICE_RECEIPT_INVALID");
        }
        warnings(c, null, null);
    }

    /** Shared repair effects: resolve, condition allowance, upgrades back on, one memory per resident in UUID order. */
    private static void applyBenefit(Connection c, Snapshot current, UUID propertyId, UUID resolver,
                                     LocalDateTime completed, LocalDate completionDay, String memorySource)
            throws SQLException {
        Incident incident = current.incident();
        try (PreparedStatement ps = prepare(c, """
                UPDATE npc_incidents SET resolved_at=?,resolver_uuid=?
                WHERE property_id=? AND incident_id=? AND resolved_at IS NULL
                  AND resolver_uuid IS NULL AND prevented=FALSE
                """, completed, resolver, propertyId, incident.id())) {
            require(updated(c, ps) == 1, "REPAIR_TARGET_CHANGED");
        }
        // Fixed allowance, not a claim to restore the property's pre-incident condition.
        try (PreparedStatement ps = prepare(c, """
                UPDATE npc_properties SET condition_score=LEAST(100,condition_score+?),row_version=row_version+1
                WHERE property_id=? AND condition_score=? AND row_version=?
                """, incident.type().conditionDamage(), propertyId, current.property().condition(),
                current.property().revision())) {
            require(updated(c, ps) == 1, "REPAIR_PROPERTY_CHANGED");
        }
        try (PreparedStatement ps = prepare(c, """
                UPDATE npc_upgrades SET disabled_incident_id=NULL
                WHERE property_id=? AND disabled_incident_id=?
                """, propertyId, incident.id())) {
            require(updated(c, ps) == current.upgrades().size(), "REPAIR_UPGRADES_CHANGED");
        }
        for (Resident resident : current.residents()) {
            ResidentRepository.appendVerified(c, new RelationshipMemory.Event(resident.id(), resolver,
                    memorySource, RelationshipMemory.Kind.REPAIR_COMPLETED, completed.toInstant(ZoneOffset.UTC),
                    completionDay));
        }
    }

    private static Snapshot snapshot(Connection c, UUID propertyId, long incidentId) throws SQLException {
        require(propertyId != null && incidentId > 0, "REPAIR_QUOTE_INVALID");
        Property property = property(c, propertyId);
        Incident incident = incident(c, propertyId, incidentId);
        List<Resident> residents = residents(c, propertyId, property.capacity());
        List<Upgrade> upgrades = upgrades(c, propertyId, incidentId);
        // Do not inner-join away tenants whose persistent identity is missing. Lock each
        // existing profile separately in Java UUID order before any memory can be appended.
        for (Resident resident : residents) lockProfile(c, resident.id());
        RepairQuote quote = new RepairQuote(incidentId, incident.type(), hash(property, incident, residents, upgrades));
        return new Snapshot(property, incident, residents, upgrades, quote);
    }

    private static Property property(Connection c, UUID propertyId) throws SQLException {
        try (PreparedStatement ps = prepare(c, """
                SELECT property_id,capacity,condition_score,row_version FROM npc_properties
                WHERE property_id=? FOR UPDATE
                """, propertyId); ResultSet rs = ps.executeQuery()) {
            require(next(c, ps, rs), "PROPERTY_MISSING");
            require(propertyId.equals(uuid(rs, "property_id", false, "REPAIR_PROPERTY_INVALID")),
                    "REPAIR_PROPERTY_INVALID");
            Property property = new Property((int) number(rs, "capacity", 1, MAX_RESIDENTS, "REPAIR_PROPERTY_INVALID"),
                    (int) number(rs, "condition_score", 0, 100, "REPAIR_PROPERTY_INVALID"),
                    number(rs, "row_version", 0, Long.MAX_VALUE - 1, "REPAIR_PROPERTY_INVALID"));
            require(!next(c, ps, rs), "REPAIR_PROPERTY_INVALID");
            return property;
        }
    }

    private static Incident incident(Connection c, UUID propertyId, long incidentId) throws SQLException {
        try (PreparedStatement ps = prepare(c, """
                SELECT incident_id,property_id,incident_type,cash_repair_cents,item_repair_spec,
                       condition_damage,affected_upgrade_type,economy_day,created_at,due_at,
                       resolved_at,resolver_uuid,prevented,
                       CAST(created_at AS CHAR) AS created_at_text,
                       CAST(due_at AS CHAR) AS due_at_text,
                       CAST(resolved_at AS CHAR) AS resolved_at_text FROM npc_incidents
                WHERE property_id=? AND incident_id=? FOR UPDATE
                """, propertyId, incidentId); ResultSet rs = ps.executeQuery()) {
            require(next(c, ps, rs), "INCIDENT_MISSING");
            String problem = "INCIDENT_INVALID";
            Incident value = new Incident(number(rs, "incident_id", 1, Long.MAX_VALUE, problem),
                    uuid(rs, "property_id", false, problem),
                    enumeration(rs.getString("incident_type"), IncidentType.class, false, problem),
                    number(rs, "cash_repair_cents", 1, Integer.MAX_VALUE, problem), rs.getString("item_repair_spec"),
                    (int) number(rs, "condition_damage", 0, 100, problem),
                    enumeration(rs.getString("affected_upgrade_type"), UpgradeType.class, true, problem),
                    day(rs.getString("economy_day"), problem), timestamp(rs, "created_at", false, problem),
                    timestamp(rs, "due_at", false, problem), timestamp(rs, "resolved_at", true, problem),
                    uuid(rs, "resolver_uuid", true, problem), number(rs, "prevented", 0, 1, problem) == 1);
            require(!next(c, ps, rs), problem);
            require(value.id() == incidentId && propertyId.equals(value.property())
                    && value.cents() == value.type().cashRepairCents()
                    && value.type().itemRepairSpec().equals(value.items())
                    && value.damage() == (value.prevented() ? 0 : value.type().conditionDamage())
                    && !value.due().isBefore(value.created())
                    && (value.resolved() == null || !value.resolved().isBefore(value.created())), problem);
            require(value.resolved() != null || value.resolver() == null, problem);
            if (value.prevented()) {
                require(value.resolved() != null && value.resolver() == null, problem);
                throw new TargetFailure("INCIDENT_PREVENTED");
            }
            if (value.resolved() != null) {
                require(value.resolver() != null, problem);
                throw new TargetFailure("INCIDENT_RESOLVED");
            }
            return value;
        }
    }

    private static List<Resident> residents(Connection c, UUID propertyId, int capacity) throws SQLException {
        List<Resident> rows = new ArrayList<>();
        var slots = new HashSet<Integer>();
        var identities = new HashSet<UUID>();
        try (PreparedStatement ps = prepare(c, """
                SELECT tenant_id,property_id,slot,row_version FROM npc_tenants
                WHERE property_id=? ORDER BY tenant_id LIMIT 5 FOR UPDATE
                """, propertyId); ResultSet rs = ps.executeQuery()) {
            while (next(c, ps, rs)) {
                String problem = "REPAIR_RESIDENTS_INVALID";
                require(rows.size() < capacity && rows.size() < MAX_RESIDENTS, problem);
                UUID id = uuid(rs, "tenant_id", false, problem);
                int slot = (int) number(rs, "slot", 0, capacity - 1, problem);
                long revision = number(rs, "row_version", 0, Long.MAX_VALUE, problem);
                require(propertyId.equals(uuid(rs, "property_id", false, problem))
                        && slots.add(slot) && identities.add(id), problem);
                rows.add(new Resident(id, slot, revision));
            }
        }
        rows.sort(Comparator.comparing(Resident::id));
        return List.copyOf(rows);
    }

    private static List<Upgrade> upgrades(Connection c, UUID propertyId, long incidentId) throws SQLException {
        List<Upgrade> rows = new ArrayList<>();
        var types = new HashSet<UpgradeType>();
        var identities = new HashSet<Long>();
        try (PreparedStatement ps = prepare(c, """
                SELECT id,property_id,upgrade_type,level,purchase_cost_cents,installed_at,disabled_incident_id,
                       CAST(installed_at AS CHAR) AS installed_at_text
                FROM npc_upgrades WHERE property_id=? AND disabled_incident_id=?
                ORDER BY upgrade_type,id LIMIT 3 ROWS EXAMINED 32 FOR UPDATE
                """, propertyId, incidentId); ResultSet rs = ps.executeQuery()) {
            while (next(c, ps, rs)) {
                String problem = "REPAIR_UPGRADES_INVALID";
                require(rows.size() < MAX_UPGRADES, problem);
                Upgrade value = new Upgrade(number(rs, "id", 1, Long.MAX_VALUE, problem),
                        enumeration(rs.getString("upgrade_type"), UpgradeType.class, false, problem),
                        (int) number(rs, "level", 0, UpgradeType.MAX_LEVEL, problem),
                        number(rs, "purchase_cost_cents", 0, Integer.MAX_VALUE, problem),
                        timestamp(rs, "installed_at", true, problem),
                        number(rs, "disabled_incident_id", 1, Long.MAX_VALUE, problem));
                require(propertyId.equals(uuid(rs, "property_id", false, problem))
                        && value.disabledIncident() == incidentId && types.add(value.type())
                        && identities.add(value.id()), problem);
                require(value.level() == 0 ? value.cost() == 0 && value.installed() == null
                        : value.cost() > 0 && value.installed() != null, problem);
                rows.add(value);
            }
        }
        rows.sort(Comparator.comparing(value -> value.type().name()));
        return List.copyOf(rows);
    }

    private static void lockProfile(Connection c, UUID resident) throws SQLException {
        try (PreparedStatement ps = prepare(c,
                "SELECT resident_id FROM npc_resident_profiles WHERE resident_id=? FOR UPDATE", resident);
             ResultSet rs = ps.executeQuery()) {
            require(next(c, ps, rs), "RESIDENT_PROFILE_MISSING");
            require(resident.equals(uuid(rs, "resident_id", false, "REPAIR_RESIDENTS_INVALID"))
                    && !next(c, ps, rs), "REPAIR_RESIDENTS_INVALID");
        }
    }

    private static String hash(Property property, Incident incident, List<Resident> residents,
                               List<Upgrade> upgrades) throws SQLException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            fields(digest, "incident-repair-v1", incident.id(), incident.property(), incident.type().name(),
                    incident.cents(), incident.items(), incident.damage(),
                    incident.affected() == null ? null : incident.affected().name(), incident.day(),
                    incident.created(), incident.due(), incident.resolved(), incident.resolver(), incident.prevented(),
                    property.capacity(), residents.size());
            // Condition and property revision deliberately do not invalidate a repair after maintenance.
            for (Resident resident : residents) fields(digest, resident.id(), resident.slot(), resident.revision());
            fields(digest, upgrades.size());
            for (Upgrade upgrade : upgrades) {
                fields(digest, upgrade.id(), upgrade.type().name(), upgrade.level(), upgrade.cost(),
                        upgrade.installed(), upgrade.disabledIncident());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new SQLException("Required SHA-256 unavailable", impossible);
        }
    }

    /** Four-byte big-endian UTF-8 byte length; -1 denotes null, distinct from an empty field. */
    private static void fields(MessageDigest digest, Object... values) {
        for (Object value : values) {
            byte[] bytes = value == null ? null : value.toString().getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes == null ? -1 : bytes.length).array());
            if (bytes != null) digest.update(bytes);
        }
    }

    private static LocalDateTime completionTime(Instant at, LocalDate completionDay, LocalDate quoteDay,
                                                Incident incident) throws SQLException {
        String problem = "REPAIR_COMPLETION_TIME_INVALID";
        require(at != null && completionDay != null, problem);
        day(completionDay.toString(), problem);
        require(!completionDay.isBefore(quoteDay) && !completionDay.isBefore(incident.day()), problem);
        try {
            LocalDateTime completed = LocalDateTime.ofInstant(at.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
            require(completed.getYear() >= 1 && completed.getYear() <= 9999
                    && !completed.isBefore(incident.created()), problem);
            return completed;
        } catch (DateTimeException | ArithmeticException invalid) {
            throw new TargetFailure(problem, invalid);
        }
    }

    private static void requireRequest(PurchaseRequest q) throws SQLException {
        require(q != null && q.kind() == PurchaseRequest.Kind.REPAIR && q.repair() != null
                && q.propertyId() != null && q.payerId() != null && q.requestId() != null
                && q.ownershipEpoch() >= 0 && q.upgrade() == null && q.targetLevel() == null
                && q.tenantId() == null && q.day() != null, "REPAIR_QUOTE_INVALID");
        day(q.day().toString(), "REPAIR_QUOTE_INVALID");
        require(q.repair().incidentId() > 0 && q.repair().type() != null
                && q.repair().snapshotSha256() != null && q.repair().snapshotSha256().matches("[a-f0-9]{64}")
                && q.cents() == q.repair().type().cashRepairCents(), "REPAIR_QUOTE_INVALID");
    }

    private static long number(ResultSet rs, String column, long minimum, long maximum, String problem)
            throws SQLException {
        BigDecimal raw = rs.getBigDecimal(column);
        require(raw != null, problem);
        try {
            long value = raw.longValueExact();
            require(value >= minimum && value <= maximum, problem);
            return value;
        } catch (ArithmeticException invalid) {
            throw new TargetFailure(problem, invalid);
        }
    }

    private static UUID uuid(ResultSet rs, String column, boolean nullable, String problem) throws SQLException {
        byte[] raw = rs.getBytes(column);
        if (raw == null && nullable) return null;
        require(raw != null && raw.length == 16, problem);
        return Uuids.fromBytes(raw);
    }

    private static <E extends Enum<E>> E enumeration(String raw, Class<E> type, boolean nullable, String problem)
            throws SQLException {
        if (raw == null && nullable) return null;
        require(raw != null, problem);
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException invalid) {
            throw new TargetFailure(problem, invalid);
        }
    }

    private static LocalDate day(String raw, String problem) throws SQLException {
        require(raw != null && raw.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"), problem);
        try {
            LocalDate value = LocalDate.parse(raw);
            require(value.getYear() >= 1 && value.getYear() <= 9999 && value.toString().equals(raw), problem);
            return value;
        } catch (DateTimeException invalid) {
            throw new TargetFailure(problem, invalid);
        }
    }

    private static LocalDateTime timestamp(ResultSet rs, String column, boolean nullable, String problem)
            throws SQLException {
        // Connector/J binary DATETIME getString loses leading fractional zeros. SQL text
        // preserves them, allowing an independent comparison with the typed UTC wall time.
        String raw = rs.getString(column + "_text");
        try {
            LocalDateTime value = rs.getObject(column, LocalDateTime.class);
            if (raw == null && value == null && nullable) return null;
            require(raw != null && value != null, problem);
            // Reject zero/invalid SQL dates and coercion, including a zero date mapped to null.
            LocalDateTime parsed = LocalDateTime.parse(raw.replace(' ', 'T'));
            require(parsed.equals(value) && value.getYear() >= 1 && value.getYear() <= 9999
                    && value.getNano() % 1_000_000 == 0, problem);
            return value;
        } catch (DateTimeException invalid) {
            throw new TargetFailure(problem, invalid);
        }
    }

    private static void requireTransaction(Connection c) throws SQLException {
        if (c == null || c.getAutoCommit()) throw new SQLException("REPAIR_TRANSACTION_REQUIRED");
        warnings(c, null, null);
    }

    private static PreparedStatement prepare(Connection c, String sql, Object... values) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        try {
            ps.setQueryTimeout(5);
            for (int i = 0; i < values.length; i++) {
                if (values[i] instanceof UUID id) ps.setBytes(i + 1, Uuids.toBytes(id));
                else ps.setObject(i + 1, values[i]);
            }
            warnings(c, ps, null);
            return ps;
        } catch (SQLException | RuntimeException failure) {
            try { ps.close(); } catch (SQLException close) { failure.addSuppressed(close); }
            throw failure;
        }
    }

    private static boolean next(Connection c, PreparedStatement ps, ResultSet rs) throws SQLException {
        // ResultSet.next() can clear the prior row's warnings; inspect both sides of it.
        warnings(c, ps, rs);
        boolean present = rs.next();
        warnings(c, ps, rs);
        return present;
    }

    private static int updated(Connection c, PreparedStatement ps) throws SQLException {
        warnings(c, ps, null);
        int count = ps.executeUpdate();
        warnings(c, ps, null);
        return count;
    }

    private static void warnings(Connection c, PreparedStatement ps, ResultSet rs) throws SQLException {
        if (c.getWarnings() != null) throw new SQLException("REPAIR_SQL_WARNING", c.getWarnings());
        if (ps != null && ps.getWarnings() != null) throw new SQLException("REPAIR_SQL_WARNING", ps.getWarnings());
        if (rs != null && rs.getWarnings() != null) throw new SQLException("REPAIR_SQL_WARNING", rs.getWarnings());
    }

    private static void require(boolean valid, String problem) throws TargetFailure {
        if (!valid) throw new TargetFailure(problem);
    }

    private static final class TargetFailure extends SQLException {
        private static final long serialVersionUID = 1L;

        private TargetFailure(String problem) { super(problem); }
        private TargetFailure(String problem, Throwable cause) { super(problem, cause); }
    }
}

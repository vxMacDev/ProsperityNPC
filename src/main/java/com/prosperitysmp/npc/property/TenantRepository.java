package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.Uuids;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Data access for NPC tenants. */
public final class TenantRepository {

    private final Database database;
    private final Clock clock;

    public TenantRepository(Database database) {
        this(database, Clock.systemUTC());
    }

    public TenantRepository(Database database, Clock clock) {
        this.database = Objects.requireNonNull(database);
        this.clock = Objects.requireNonNull(clock);
    }

    public int countByProperty(UUID propertyId) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM npc_tenants WHERE property_id = ?")) {
            ps.setBytes(1, Uuids.toBytes(propertyId));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public List<Tenant> listByProperty(UUID propertyId) throws SQLException {
        List<Tenant> out = new ArrayList<>();
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT tenant_id, display_name, slot, tier, archetype, satisfaction "
                             + "FROM npc_tenants WHERE property_id = ? ORDER BY slot")) {
            ps.setBytes(1, Uuids.toBytes(propertyId));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Tenant(
                            Uuids.fromBytes(rs.getBytes("tenant_id")),
                            rs.getString("display_name"),
                            propertyId,
                            rs.getInt("slot"),
                            rs.getString("tier"),
                            rs.getString("archetype"),
                            rs.getInt("satisfaction")));
                }
            }
        }
        return out;
    }

    /** Bounded cosmetic read, not admission or ownership authority. Reject a corrupt occupancy set. */
    public List<Tenant> listForDisplay(UUID propertyId, int capacity) throws SQLException {
        Objects.requireNonNull(propertyId);
        if (capacity < 1 || capacity > 4) throw new IllegalArgumentException("Invalid display capacity");
        var out = new ArrayList<Tenant>();
        var slots = new java.util.HashSet<Integer>();
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT tenant_id,display_name,slot,tier,archetype,satisfaction "
                     + "FROM npc_tenants WHERE property_id=? ORDER BY slot,tenant_id LIMIT ?")) {
            ps.setQueryTimeout(5);
            ps.setBytes(1, Uuids.toBytes(propertyId));
            ps.setInt(2, capacity + 1);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (out.size() == capacity) throw new SQLException("Display occupancy exceeds capacity");
                    int slot = rs.getInt("slot");
                    if (rs.wasNull() || slot < 0 || slot >= capacity || !slots.add(slot))
                        throw new SQLException("Invalid display occupancy slot");
                    String name = rs.getString("display_name"), tier = rs.getString("tier"), archetype = rs.getString("archetype");
                    if (name == null || name.isBlank() || name.length() > 64
                            || name.codePoints().anyMatch(ch -> Character.isISOControl(ch) || ch == 0x00a7)
                            || !("STANDARD".equals(tier) || "PREMIUM".equals(tier))
                            || archetype == null || archetype.isBlank())
                        throw new SQLException("Invalid display tenant metadata");
                    int satisfaction = rs.getInt("satisfaction");
                    if (rs.wasNull() || satisfaction < 0 || satisfaction > 100)
                        throw new SQLException("Invalid display satisfaction");
                    out.add(new Tenant(Uuids.fromBytes(rs.getBytes("tenant_id")), name, propertyId,
                            slot, tier, archetype, satisfaction));
                }
                if (c.getWarnings() != null || ps.getWarnings() != null || rs.getWarnings() != null)
                    throw new SQLException("Display occupancy read warning");
            }
        }
        return List.copyOf(out);
    }

    /**
     * Premium tenants at a property that are eligible to move today: tier PREMIUM and
     * either never moved or past their move cooldown. Ordered by slot for determinism.
     * Economy-day strings are ISO 'YYYY-MM-DD', so a lexical compare is a date compare.
     */
    public List<Tenant> movablePremiumTenants(UUID propertyId, String today) throws SQLException {
        List<Tenant> out = new ArrayList<>();
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT tenant_id, display_name, slot, tier, archetype, satisfaction "
                             + "FROM npc_tenants WHERE property_id = ? AND tier = 'PREMIUM' "
                             + "AND (move_cooldown_until_day IS NULL OR move_cooldown_until_day <= ?) "
                             + "ORDER BY slot")) {
            ps.setBytes(1, Uuids.toBytes(propertyId));
            ps.setString(2, today);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Tenant(
                            Uuids.fromBytes(rs.getBytes("tenant_id")),
                            rs.getString("display_name"),
                            propertyId,
                            rs.getInt("slot"),
                            rs.getString("tier"),
                            rs.getString("archetype"),
                            rs.getInt("satisfaction")));
                }
            }
        }
        return out;
    }

    /**
     * Relocates a tenant to a new property/slot and pins its move cooldown. This is
     * the authoritative move: it commits before any cosmetic avatar walk, so cash
     * and occupancy stay correct even if rendering later fails (§8.4).
     */
    public void moveTenant(UUID tenantId, UUID newProperty, int newSlot, String cooldownUntilDay)
            throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE npc_tenants SET property_id = ?, slot = ?, move_cooldown_until_day = ?, "
                             + "row_version = row_version + 1 WHERE tenant_id = ?")) {
            ps.setBytes(1, Uuids.toBytes(newProperty));
            ps.setInt(2, newSlot);
            ps.setString(3, cooldownUntilDay);
            ps.setBytes(4, Uuids.toBytes(tenantId));
            ps.executeUpdate();
        }
    }

    public void insert(Tenant t) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO npc_tenants
                       (tenant_id, display_name, property_id, slot, tier, archetype, satisfaction)
                     VALUES (?, ?, ?, ?, ?, ?, ?)
                     """)) {
            ps.setBytes(1, Uuids.toBytes(t.tenantId()));
            ps.setString(2, t.displayName());
            ps.setBytes(3, Uuids.toBytes(t.propertyId()));
            ps.setInt(4, t.slot());
            ps.setString(5, t.tier());
            ps.setString(6, t.archetype());
            ps.setInt(7, t.satisfaction());
            ps.executeUpdate();
        }
    }

    /** Departure is not identity deletion. Keep the row and its monotonic occupancy revision. */
    public boolean vacate(UUID tenantId) throws SQLException {
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            try {
                boolean changed = vacate(c, tenantId, null, clock, null);
                c.commit();
                return changed;
            } catch (SQLException | RuntimeException failure) {
                rollback(c, failure);
                throw failure;
            }
        }
    }

    /** Caller-owned transaction; expectedProperty scopes paid eviction to its locked property. */
    public static boolean vacate(Connection c, UUID tenantId, UUID expectedProperty) throws SQLException {
        return vacate(c, tenantId, expectedProperty, Clock.systemUTC(), null);
    }

    /**
     * Trusted lifecycle boundary. PurchaseRepository supplies its already proof-checked event;
     * this is not a public eviction command or authority to charge money. All state commits
     * in the caller's transaction. Generic departures never claim a voluntary cause.
     */
    public static boolean vacate(Connection c, UUID tenantId, UUID expectedProperty, Clock clock,
                                  Long purchaseEventId) throws SQLException {
        return record(c, tenantId, expectedProperty, clock, purchaseEventId, purchaseEventId == null
                ? TenancyDeparture.Cause.DEPARTURE : TenancyDeparture.Cause.PAID_EVICTION) != null;
    }

    /**
     * Caller-owned transaction. A resident leaves a neglected home by their own choice: no purchase, no
     * money, identity and memories kept. Returns the recorded fact, or null when the resident is no
     * longer housed at {@code expectedProperty}.
     */
    public static TenancyDeparture vacateVoluntarily(Connection c, UUID tenantId, UUID expectedProperty, Clock clock)
            throws SQLException {
        return record(c, tenantId, Objects.requireNonNull(expectedProperty), clock, null, TenancyDeparture.Cause.VOLUNTARY);
    }

    private static TenancyDeparture record(Connection c, UUID tenantId, UUID expectedProperty, Clock clock,
                                           Long purchaseEventId, TenancyDeparture.Cause cause) throws SQLException {
        Objects.requireNonNull(tenantId); Objects.requireNonNull(clock); Objects.requireNonNull(cause);
        if (c.getAutoCommit()) throw new SQLException("Vacating requires an explicit transaction");
        // Discover without a tenant lock, then take the shared property-before-tenant order.
        byte[] observedHome = expectedProperty == null ? null : Uuids.toBytes(expectedProperty);
        if (expectedProperty == null) {
            try (PreparedStatement ps = query(c, "SELECT property_id FROM npc_tenants WHERE tenant_id=?")) {
                ps.setBytes(1, Uuids.toBytes(tenantId));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!next(c, ps, rs)) return null;
                    observedHome = rs.getBytes("property_id");
                    if (observedHome != null && observedHome.length != 16) throw new SQLException("Invalid tenant property");
                    if (next(c, ps, rs)) throw new SQLException("Duplicate tenant");
                }
            }
        }
        UUID owner = null;
        int epoch = 0;
        if (observedHome != null) {
            try (PreparedStatement ps = query(c, "SELECT owner_uuid,ownership_epoch FROM npc_properties WHERE property_id=? FOR UPDATE")) {
                ps.setBytes(1, observedHome);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!next(c, ps, rs)) throw new SQLException("Departure property missing");
                    owner = nullableUuid(rs, "owner_uuid");
                    epoch = rs.getInt("ownership_epoch");
                    if (rs.wasNull() || epoch < 0) throw new SQLException("Invalid departure ownership epoch");
                    if (next(c, ps, rs)) throw new SQLException("Duplicate property");
                }
            }
        }
        try (PreparedStatement ps = query(c, "SELECT property_id,slot,row_version FROM npc_tenants WHERE tenant_id=? FOR UPDATE")) {
            ps.setBytes(1, Uuids.toBytes(tenantId));
            try (ResultSet rs = ps.executeQuery()) {
                if (!next(c, ps, rs)) return null;
                byte[] home = rs.getBytes("property_id");
                int slot = rs.getInt("slot");
                boolean noSlot = rs.wasNull();
                if ((home == null) != noSlot || (!noSlot && (slot < 0 || slot >= 4)))
                    throw new SQLException("Invalid tenant occupancy");
                long revision = revision(rs);
                if (next(c, ps, rs)) throw new SQLException("Duplicate tenant");
                if (home == null || (expectedProperty != null && !expectedProperty.equals(Uuids.fromBytes(home)))) return null;
                if (!Arrays.equals(home, observedHome)) throw new SQLException("Departure tenancy changed while locking");
                if (revision == Long.MAX_VALUE) throw new SQLException("Tenant revision exhausted");
                TenancyDeparture departure;
                try {
                    departure = new TenancyDeparture(tenantId, Uuids.fromBytes(home), owner, epoch, revision, slot,
                            Instant.ofEpochMilli(clock.millis()), cause, purchaseEventId);
                } catch (IllegalArgumentException invalid) { throw new SQLException("Invalid departure evidence", invalid); }
                if (purchaseEventId != null) verifyEvictionBinding(c, departure);
                insertDeparture(c, departure);
                try (PreparedStatement update = query(c, "UPDATE npc_tenants SET property_id=NULL,slot=NULL,row_version=row_version+1 WHERE tenant_id=? AND row_version=?")) {
                    update.setBytes(1, Uuids.toBytes(tenantId)); update.setLong(2, revision);
                    if (update.executeUpdate() != 1) throw new SQLException("Tenant departure changed");
                    warnings(c, update, null);
                }
                return departure;
            }
        }
    }

    /** Exact immutable departure fact; absence is unknown, not evidence of retention. */
    public Optional<TenancyDeparture> departure(UUID resident, long before) throws SQLException {
        Objects.requireNonNull(resident);
        if (before < 0 || before == Long.MAX_VALUE) throw new IllegalArgumentException("Invalid departure revision");
        try (Connection c = database.getConnection(); PreparedStatement ps = query(c,
                "SELECT * FROM npc_tenancy_departures WHERE resident_id=? AND occupancy_revision_before=? LIMIT 2")) {
            ps.setBytes(1, Uuids.toBytes(resident)); ps.setLong(2, before);
            try (ResultSet rs = ps.executeQuery()) {
                if (!next(c, ps, rs)) return Optional.empty();
                TenancyDeparture result = readDeparture(rs);
                if (!resident.equals(result.residentId()) || before != result.occupancyRevisionBefore()
                        || next(c, ps, rs)) throw new SQLException("Departure identity changed");
                return Optional.of(result);
            }
        }
    }

    /** Shared stored-fact decoder; callers own a consistent snapshot and warning checks. */
    public static TenancyDeparture readDeparture(ResultSet rs) throws SQLException {
        try {
            LocalDateTime at = rs.getObject("departed_at", LocalDateTime.class);
            Long event = rs.getObject("purchase_event_id") == null ? null : rs.getLong("purchase_event_id");
            TenancyDeparture result = new TenancyDeparture(requiredUuid(rs, "resident_id"), requiredUuid(rs, "property_id"),
                    nullableUuid(rs, "owner_at_departure"), (int) departureNumber(rs, "ownership_epoch", Integer.MAX_VALUE),
                    departureNumber(rs, "occupancy_revision_before", Long.MAX_VALUE - 1),
                    (int) departureNumber(rs, "slot", 3), at.toInstant(ZoneOffset.UTC),
                    TenancyDeparture.Cause.valueOf(rs.getString("cause")), event);
            if (result.occupancyRevisionAfter() != departureNumber(rs, "occupancy_revision_after", Long.MAX_VALUE)
                    || !result.fingerprint().equals(rs.getString("payload_sha256")))
                throw new SQLException("Departure fingerprint or revision mismatch");
            return result;
        } catch (RuntimeException invalid) { throw new SQLException("Invalid stored departure", invalid); }
    }

    private static void insertDeparture(Connection c, TenancyDeparture d) throws SQLException {
        try (PreparedStatement ps = query(c, """
                INSERT INTO npc_tenancy_departures(resident_id,occupancy_revision_before,occupancy_revision_after,
                  property_id,owner_at_departure,ownership_epoch,slot,departed_at,cause,purchase_event_id,payload_sha256)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            ps.setBytes(1, Uuids.toBytes(d.residentId())); ps.setLong(2, d.occupancyRevisionBefore());
            ps.setLong(3, d.occupancyRevisionAfter()); ps.setBytes(4, Uuids.toBytes(d.propertyId()));
            ps.setBytes(5, d.ownerAtDeparture() == null ? null : Uuids.toBytes(d.ownerAtDeparture()));
            ps.setInt(6, d.ownershipEpoch()); ps.setInt(7, d.slot());
            ps.setObject(8, LocalDateTime.ofInstant(d.departedAt(), ZoneOffset.UTC));
            ps.setString(9, d.cause().name()); ps.setObject(10, d.purchaseEventId()); ps.setString(11, d.fingerprint());
            if (ps.executeUpdate() != 1) throw new SQLException("Departure evidence not inserted");
            warnings(c, ps, null);
        }
    }

    private static void verifyEvictionBinding(Connection c, TenancyDeparture d) throws SQLException {
        try (PreparedStatement ps = query(c, """
                SELECT i.tenant_id,i.evict_tenancy_revision,i.evict_slot,i.kind,i.state AS intent_state,e.*
                FROM npc_purchase_intents i JOIN npc_economy_events e ON e.event_id=i.event_id WHERE e.event_id=? FOR UPDATE
                """)) {
            ps.setLong(1, d.purchaseEventId());
            try (ResultSet rs = ps.executeQuery()) {
                if (!next(c, ps, rs)) throw new SQLException("Departure purchase missing");
                LocalDateTime paidAt = rs.getObject("finalized_at", LocalDateTime.class);
                if (d.ownerAtDeparture() == null || !"EVICT".equals(rs.getString("kind")) || !"WAITING".equals(rs.getString("intent_state"))
                        || !"PAID".equals(rs.getString("state"))
                        || !"npc-property-eviction".equals(rs.getString("source"))
                        || !d.residentId().equals(requiredUuid(rs, "tenant_id"))
                        || !d.propertyId().equals(requiredUuid(rs, "property_id"))
                        || !Objects.equals(d.ownerAtDeparture(), nullableUuid(rs, "counterparty_uuid"))
                        || rs.getObject("evict_tenancy_revision") == null
                        || d.occupancyRevisionBefore() != rs.getLong("evict_tenancy_revision")
                        || rs.getObject("evict_slot") == null || d.slot() != rs.getInt("evict_slot")
                        || rs.getObject("ownership_epoch") == null || d.ownershipEpoch() != rs.getInt("ownership_epoch")
                        || paidAt == null || paidAt.toInstant(ZoneOffset.UTC).isAfter(d.departedAt()))
                    throw new SQLException("Departure eviction binding changed");
                if (next(c, ps, rs)) throw new SQLException("Duplicate departure purchase");
            }
        }
    }

    private static UUID nullableUuid(ResultSet rs, String column) throws SQLException {
        byte[] bytes = rs.getBytes(column);
        if (bytes == null) return null;
        if (bytes.length != 16) throw new SQLException("Invalid departure UUID");
        return Uuids.fromBytes(bytes);
    }
    private static UUID requiredUuid(ResultSet rs, String column) throws SQLException {
        UUID value = nullableUuid(rs, column);
        if (value == null) throw new SQLException("Missing departure UUID");
        return value;
    }
    private static long departureNumber(ResultSet rs, String column, long max) throws SQLException {
        long value = rs.getLong(column);
        if (rs.wasNull() || value < 0 || value > max) throw new SQLException("Invalid departure number");
        return value;
    }

    /** Compatibility for existing departure callers; never physically deletes a resident. */
    @Deprecated
    public void delete(UUID tenantId) throws SQLException { vacate(tenantId); }

    /**
     * Trusted SQL lifecycle primitive, not a public move-in offer. Eligibility, notice,
     * cooldown and live ownership coordination belong to the finite market coordinator.
     * Only a vacant current revision may re-enter; identity and cooldown are untouched.
     */
    public void rehouse(UUID tenantId, long expectedRevision, UUID propertyId, int slot) throws SQLException {
        java.util.Objects.requireNonNull(tenantId); java.util.Objects.requireNonNull(propertyId);
        if (expectedRevision < 0 || expectedRevision == Long.MAX_VALUE || slot < 0 || slot >= 4)
            throw new IllegalArgumentException("Invalid tenancy revision/slot");
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            try {
                // Same property-before-tenant order as paid repair; contenders share this lock.
                try (PreparedStatement ps = query(c, "SELECT capacity,enabled,owner_uuid FROM npc_properties WHERE property_id=? FOR UPDATE")) {
                    ps.setBytes(1, Uuids.toBytes(propertyId));
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!next(c, ps, rs)) throw new SQLException("Target property missing");
                        int capacity = rs.getInt("capacity");
                        if (rs.wasNull() || capacity < 1 || capacity > 4 || slot >= capacity
                                || !rs.getBoolean("enabled") || rs.getBytes("owner_uuid") == null)
                            throw new SQLException("Target property unavailable");
                        if (next(c, ps, rs)) throw new SQLException("Duplicate property");
                    }
                }
                try (PreparedStatement ps = query(c, "SELECT property_id,slot,row_version FROM npc_tenants WHERE tenant_id=? FOR UPDATE")) {
                    ps.setBytes(1, Uuids.toBytes(tenantId));
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!next(c, ps, rs)) throw new SQLException("Tenant missing");
                        if (rs.getBytes("property_id") != null || rs.getObject("slot") != null || revision(rs) != expectedRevision)
                            throw new SQLException("Tenant vacancy changed");
                        if (next(c, ps, rs)) throw new SQLException("Duplicate tenant");
                    }
                }
                try (PreparedStatement ps = query(c, "SELECT tenant_id FROM npc_tenants WHERE property_id=? AND slot=? LIMIT 1 FOR UPDATE")) {
                    ps.setBytes(1, Uuids.toBytes(propertyId)); ps.setInt(2, slot);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (next(c, ps, rs)) throw new SQLException("Target slot occupied");
                    }
                }
                try (PreparedStatement ps = query(c, "UPDATE npc_tenants SET property_id=?,slot=?,row_version=row_version+1 WHERE tenant_id=? AND row_version=? AND property_id IS NULL AND slot IS NULL")) {
                    ps.setBytes(1, Uuids.toBytes(propertyId)); ps.setInt(2, slot);
                    ps.setBytes(3, Uuids.toBytes(tenantId)); ps.setLong(4, expectedRevision);
                    if (ps.executeUpdate() != 1) throw new SQLException("Tenant re-entry changed");
                    warnings(c, ps, null);
                }
                c.commit();
            } catch (SQLException | RuntimeException failure) {
                rollback(c, failure);
                throw failure;
            }
        }
    }

    private static long revision(ResultSet rs) throws SQLException {
        long value = rs.getLong("row_version");
        if (rs.wasNull() || value < 0) throw new SQLException("Invalid tenant revision");
        return value;
    }
    private static PreparedStatement query(Connection c, String sql) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        try { ps.setQueryTimeout(5); return ps; }
        catch (SQLException failure) { ps.close(); throw failure; }
    }
    private static boolean next(Connection c, PreparedStatement ps, ResultSet rs) throws SQLException {
        warnings(c, ps, rs);
        boolean present = rs.next();
        warnings(c, ps, rs);
        return present;
    }
    private static void warnings(Connection c, PreparedStatement ps, ResultSet rs) throws SQLException {
        if (c.getWarnings() != null || ps.getWarnings() != null || (rs != null && rs.getWarnings() != null))
            throw new SQLException("Incomplete tenancy operation");
    }
    private static void rollback(Connection c, Throwable failure) {
        try { c.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
    }

    /** Lowest unoccupied slot index in [0, capacity), or -1 if full. */
    public int firstFreeSlot(UUID propertyId, int capacity) throws SQLException {
        boolean[] used = new boolean[capacity];
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT slot FROM npc_tenants WHERE property_id = ?")) {
            ps.setBytes(1, Uuids.toBytes(propertyId));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int s = rs.getInt(1);
                    if (s >= 0 && s < capacity) {
                        used[s] = true;
                    }
                }
            }
        }
        for (int i = 0; i < capacity; i++) {
            if (!used[i]) {
                return i;
            }
        }
        return -1;
    }
}

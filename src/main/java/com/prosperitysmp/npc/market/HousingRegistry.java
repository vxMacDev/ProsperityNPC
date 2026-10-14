package com.prosperitysmp.npc.market;

import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.Uuids;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Trusted authoring API for a finite cohort. No occupancy, money, or player-facing writer. */
public final class HousingRegistry {
    public static final int MAX_MEMBERS = 512;
    public record Member(UUID residentId, HousingPolicy policy, LocalDate availableSince, long revision, boolean enabled) {
        public Member {
            Objects.requireNonNull(residentId); Objects.requireNonNull(policy); day(availableSince);
            if (revision < 0 || revision == Long.MAX_VALUE) throw new IllegalArgumentException("Invalid housing revision");
        }
    }
    private final Database database;
    public HousingRegistry(Database database) { this.database = Objects.requireNonNull(database); }

    /** Exact re-import is a no-op, not permission to reset policy, priority, revision, or enabled state. */
    public boolean admit(UUID residentId, HousingPolicy policy, LocalDate availableSince) throws SQLException {
        Objects.requireNonNull(residentId); Objects.requireNonNull(policy); day(availableSince);
        return transaction(c -> {
            lock(c);
            Optional<Member> prior = find(c, residentId, true);
            if (prior.isPresent()) {
                Member old = prior.get();
                if (!old.policy().equals(policy) || !old.availableSince().equals(availableSince))
                    throw new SQLException("Housing admission payload conflict");
                return false;
            }
            try (PreparedStatement ps = prepare(c, "SELECT resident_id FROM npc_resident_profiles WHERE resident_id=? FOR UPDATE", residentId);
                 ResultSet rs = ps.executeQuery()) {
                if (!next(c, ps, rs) || next(c, ps, rs)) throw new SQLException("Housing requires an existing resident");
            }
            try (PreparedStatement ps = prepare(c, "SELECT COUNT(*) FROM npc_housing_members"); ResultSet rs = ps.executeQuery()) {
                if (!next(c, ps, rs)) throw new SQLException("Housing count missing");
                long count = rs.getLong(1);
                if (rs.wasNull() || count < 0 || count >= MAX_MEMBERS || next(c, ps, rs))
                    throw new SQLException("Housing registry capacity reached or invalid");
            }
            try (PreparedStatement ps = prepare(c, """
                    INSERT INTO npc_housing_members (resident_id,world_uuid,market_id,class_key,tier,
                      max_daily_rent_cents,minimum_condition,available_since)
                    VALUES (?,?,?,?,?,?,?,?)
                    """, residentId, policy.worldId(), policy.marketId(), policy.classKey(), policy.tier().name(),
                    policy.maxDailyRentCents(), policy.minimumCondition(), availableSince.toString())) {
                one(c, ps);
            }
            return true;
        });
    }

    public Member setEnabled(UUID residentId, long expectedRevision, boolean enabled) throws SQLException {
        Objects.requireNonNull(residentId);
        if (expectedRevision < 0 || expectedRevision == Long.MAX_VALUE) throw new IllegalArgumentException("Invalid housing revision");
        return transaction(c -> {
            lock(c);
            Member old = find(c, residentId, true).orElseThrow(() -> new SQLException("Housing member missing"));
            if (old.revision() != expectedRevision) throw new SQLException("Housing membership changed");
            if (old.enabled() == enabled) return old;
            if (old.revision() >= Long.MAX_VALUE - 1) throw new SQLException("Housing revision exhausted");
            try (PreparedStatement ps = prepare(c, "UPDATE npc_housing_members SET enabled=?,row_version=row_version+1 WHERE resident_id=? AND row_version=?",
                    enabled, residentId, expectedRevision)) { one(c, ps); }
            return new Member(residentId, old.policy(), old.availableSince(), old.revision() + 1, enabled);
        });
    }

    /** Explicit staff policy edit, never an occupied lease rewrite or a cooldown reset. */
    public Member setVacantPolicy(UUID residentId, long expectedRevision, HousingPolicy policy) throws SQLException {
        Objects.requireNonNull(residentId); Objects.requireNonNull(policy);
        return transaction(c -> {
            lock(c);
            Member old = find(c, residentId, true).orElseThrow(() -> new SQLException("Housing member missing"));
            if (old.revision() != expectedRevision) throw new SQLException("Housing membership changed");
            if (old.policy().equals(policy)) return old;
            if (old.revision() >= Long.MAX_VALUE - 1) throw new SQLException("Housing revision exhausted");
            try (var ps = prepare(c, "SELECT property_id,slot FROM npc_tenants WHERE tenant_id=? FOR UPDATE", residentId);
                 var rs = ps.executeQuery()) {
                if (next(c, ps, rs) && (rs.getBytes("property_id") != null || rs.getObject("slot") != null || next(c, ps, rs)))
                    throw new SQLException("Policy change requires a verified vacant resident");
            }
            try (var ps = prepare(c, """
                    UPDATE npc_housing_members SET world_uuid=?,market_id=?,class_key=?,tier=?,max_daily_rent_cents=?,
                      minimum_condition=?,row_version=row_version+1 WHERE resident_id=? AND row_version=?
                    """, policy.worldId(), policy.marketId(), policy.classKey(), policy.tier().name(),
                    policy.maxDailyRentCents(), policy.minimumCondition(), residentId, expectedRevision)) { one(c, ps); }
            try (var ps = prepare(c, "UPDATE npc_tenants SET tier=? WHERE tenant_id=? AND property_id IS NULL AND slot IS NULL",
                    policy.tier().name(), residentId)) { ps.executeUpdate(); warnings(c, ps, null); }
            return new Member(residentId, policy, old.availableSince(), old.revision() + 1, old.enabled());
        });
    }

    public Optional<Member> find(UUID residentId) throws SQLException {
        Objects.requireNonNull(residentId);
        try (Connection c = database.getConnection()) { return find(c, residentId, false); }
    }

    /** Complete cohort, including disabled members. Never hide an overflow by returning a prefix. */
    public List<Member> list() throws SQLException {
        try (Connection c = database.getConnection(); PreparedStatement ps = prepare(c,
                "SELECT * FROM npc_housing_members ORDER BY resident_id LIMIT 513"); ResultSet rs = ps.executeQuery()) {
            List<Member> members = new ArrayList<>();
            while (next(c, ps, rs)) {
                if (members.size() >= MAX_MEMBERS) throw new SQLException("Housing registry exceeds snapshot bound");
                members.add(read(rs));
            }
            return List.copyOf(members);
        }
    }

    static Optional<Member> find(Connection c, UUID resident, boolean lock) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_housing_members WHERE resident_id=?" + (lock ? " FOR UPDATE" : ""), resident);
             ResultSet rs = ps.executeQuery()) {
            if (!next(c, ps, rs)) return Optional.empty();
            Member member = read(rs);
            if (next(c, ps, rs)) throw new SQLException("Duplicate housing member");
            return Optional.of(member);
        }
    }
    static Member read(ResultSet rs) throws SQLException {
        try {
            long price = rs.getLong("max_daily_rent_cents");
            if (rs.wasNull()) throw new IllegalArgumentException("Missing housing budget");
            int condition = rs.getInt("minimum_condition");
            if (rs.wasNull()) throw new IllegalArgumentException("Missing housing condition");
            long revision = rs.getLong("row_version");
            if (rs.wasNull()) throw new IllegalArgumentException("Missing housing revision");
            int enabled = rs.getInt("enabled");
            if (rs.wasNull() || enabled < 0 || enabled > 1) throw new IllegalArgumentException("Invalid housing state");
            String date = rs.getString("available_since");
            if (date == null || !date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException("Invalid housing date");
            LocalDate day = LocalDate.parse(date);
            if (!day.toString().equals(date)) throw new IllegalArgumentException("Noncanonical housing date");
            HousingPolicy policy = new HousingPolicy(Uuids.fromBytes(rs.getBytes("world_uuid")), rs.getString("market_id"),
                    rs.getString("class_key"), HousingPolicy.Tier.valueOf(rs.getString("tier")), price, condition);
            return new Member(Uuids.fromBytes(rs.getBytes("resident_id")), policy, day, revision, enabled == 1);
        } catch (IllegalArgumentException | java.time.DateTimeException failure) {
            throw new SQLException("Invalid housing membership", failure);
        }
    }
    static void lock(Connection c) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT singleton_id FROM npc_housing_registry_lock WHERE singleton_id=1 FOR UPDATE");
             ResultSet rs = ps.executeQuery()) {
            if (!next(c, ps, rs) || rs.getInt(1) != 1 || next(c, ps, rs)) throw new SQLException("Housing admission lock missing");
        }
    }
    private static void day(LocalDate date) {
        Objects.requireNonNull(date);
        if (date.getYear() < 1 || date.getYear() > 9999) throw new IllegalArgumentException("Housing date out of range");
    }
    private static PreparedStatement prepare(Connection c, String sql, Object... values) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        try {
            ps.setQueryTimeout(5);
            for (int i = 0; i < values.length; i++) {
                if (values[i] instanceof UUID id) ps.setBytes(i + 1, Uuids.toBytes(id));
                else ps.setObject(i + 1, values[i]);
            }
            return ps;
        } catch (SQLException | RuntimeException failure) { ps.close(); throw failure; }
    }
    private static boolean next(Connection c, PreparedStatement ps, ResultSet rs) throws SQLException {
        warnings(c, ps, rs); boolean present = rs.next(); warnings(c, ps, rs); return present;
    }
    private static void one(Connection c, PreparedStatement ps) throws SQLException {
        if (ps.executeUpdate() != 1) throw new SQLException("Housing write did not affect one member");
        warnings(c, ps, null);
    }
    private static void warnings(Connection c, PreparedStatement ps, ResultSet rs) throws SQLException {
        if (c.getWarnings() != null || ps.getWarnings() != null || (rs != null && rs.getWarnings() != null))
            throw new SQLException("Incomplete housing operation");
    }
    private interface Work<T> { T apply(Connection c) throws SQLException; }
    private <T> T transaction(Work<T> work) throws SQLException {
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            try { T result = work.apply(c); c.commit(); return result; }
            catch (SQLException | RuntimeException failure) {
                try { c.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
    }
}

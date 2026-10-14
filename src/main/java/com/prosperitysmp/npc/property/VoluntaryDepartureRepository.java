package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.formulas.PropertyFormulas;
import com.prosperitysmp.npc.sim.SimulationLogic;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Worker-only daily voluntary departures: neglect lowers occupancy. A resident of a neglected home may
 * leave by their own choice, decided by the deterministic per-(property, day) leave roll. No money moves,
 * the resident keeps their identity and memories, and the normal move cooldown applies. At most one
 * departure per property per day (primary key) and {@link #MAX_SERVER_DAILY} per server per day, both
 * checked under the rent mutex, so a property whose rent is already reserved for the day is never touched
 * and a replay of the same day changes nothing.
 */
public final class VoluntaryDepartureRepository {
    public static final int MAX_SERVER_DAILY = 3, MAX_TARGETS = 512;
    private static final int QUERY_SECONDS = 5, MAX_RESIDENTS = 4;

    /** Property identity captured under an ownership lease; skipped if the stored row no longer matches. */
    public record Target(UUID propertyId, UUID ownerId, int ownershipEpoch) {
        public Target {
            Objects.requireNonNull(propertyId); Objects.requireNonNull(ownerId);
            if (ownershipEpoch < 0) throw new IllegalArgumentException("Invalid ownership epoch");
        }
    }

    /**
     * moods: decide from resident satisfaction (only residents with a recorded mood). Otherwise decide
     * from condition and overdue incidents. A home is neglected below the matching threshold or, without
     * moods, with an overdue incident; a well-kept home never rolls.
     */
    public record Policy(boolean moods, int serverDailyLimit, int cooldownDays, int satisfactionBelow, int conditionBelow) {
        public Policy {
            if (serverDailyLimit < 0 || serverDailyLimit > MAX_SERVER_DAILY || cooldownDays < 1 || cooldownDays > 30
                    || satisfactionBelow < 0 || satisfactionBelow > 100 || conditionBelow < 0 || conditionBelow > 100)
                throw new IllegalArgumentException("Invalid voluntary departure policy");
        }
    }

    public record Departed(UUID residentId, UUID propertyId, long revisionBefore, int score, int chanceBp,
                           LocalDate cooldownUntil) { }

    /** failed: properties whose decision could not be committed; they were left unchanged. */
    public record Result(List<Departed> departed, List<UUID> failed) {
        public Result { departed = List.copyOf(departed); failed = List.copyOf(failed); }
    }

    private record Candidate(UUID resident, int satisfaction) { }
    private record Decision(UUID resident, int score, int chanceBp) { }

    private final Database database;
    private final SimulationLogic logic;
    private final Clock clock;

    public VoluntaryDepartureRepository(Database database, SimulationLogic logic, Clock clock) {
        this.database = Objects.requireNonNull(database);
        this.logic = Objects.requireNonNull(logic);
        this.clock = Objects.requireNonNull(clock);
    }

    public Result runDay(LocalDate day, List<Target> targets, Policy policy) throws SQLException {
        Objects.requireNonNull(day); Objects.requireNonNull(policy);
        if (targets.size() > MAX_TARGETS || day.getYear() < 1 || day.getYear() > 9998)
            throw new IllegalArgumentException("Voluntary departure scope");
        Map<UUID, Target> sorted = new LinkedHashMap<>();
        for (Target t : targets.stream().sorted(Comparator.comparing(t -> t.propertyId().toString())).toList())
            if (sorted.put(t.propertyId(), t) != null) throw new IllegalArgumentException("Duplicate property");
        List<Departed> departed = new ArrayList<>();
        List<UUID> failed = new ArrayList<>();
        if (policy.serverDailyLimit() == 0) return new Result(departed, failed);
        LocalDate cooldown = day.plusDays(policy.cooldownDays());
        for (Target target : sorted.values()) {
            try (Connection c = database.getConnection()) {
                if (count(c, "SELECT COUNT(*) FROM npc_voluntary_departures WHERE economy_day=?", day.toString())
                        >= policy.serverDailyLimit()) break;
                Decision decision = decide(c, day, target, policy);
                if (decision == null) continue;
                Departed result = commit(c, day, target, decision, cooldown, policy);
                if (result != null) departed.add(result);
            } catch (SQLException | RuntimeException failure) {
                failed.add(target.propertyId());
            }
        }
        return new Result(departed, failed);
    }

    /** Read-only. Null means no departure today for this home. */
    private Decision decide(Connection c, LocalDate day, Target target, Policy policy) throws SQLException {
        int condition;
        try (PreparedStatement ps = prepare(c, "SELECT enabled,owner_uuid,ownership_epoch,condition_score FROM npc_properties WHERE property_id=?",
                target.propertyId()); ResultSet rs = ps.executeQuery()) {
            if (!rs.next() || !rs.getBoolean("enabled") || !sameOwner(rs, target)) return null;
            condition = rs.getInt("condition_score");
        }
        if (blocked(c, day, target.propertyId())) return null;
        List<Candidate> candidates = candidates(c, day, target.propertyId(), policy.moods());
        if (candidates.isEmpty()) return null;
        int score;
        boolean overdue;
        if (policy.moods()) {
            score = candidates.stream().mapToInt(Candidate::satisfaction).min().orElseThrow();
            overdue = false;
            if (score >= policy.satisfactionBelow()) return null;
            int least = score;
            candidates = candidates.stream().filter(x -> x.satisfaction() == least).toList();
        } else {
            score = Math.max(0, Math.min(100, condition));
            overdue = exists(c, """
                    SELECT 1 FROM npc_incidents WHERE property_id=? AND resolved_at IS NULL AND prevented=FALSE AND due_at<?
                    """, target.propertyId(), LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
            if (score >= policy.conditionBelow() && !overdue) return null;
        }
        if (!logic.leavesToday(target.propertyId(), day.toString(), score, overdue)) return null;
        UUID resident = candidates.get(logic.leavingIndex(target.propertyId(), day.toString(), candidates.size())).resident();
        return new Decision(resident, score, PropertyFormulas.leaveChanceBp(score, overdue));
    }

    /** One property, one transaction. Rent mutex first, then property before tenant, matching rent reservation. */
    private Departed commit(Connection c, LocalDate day, Target target, Decision decision, LocalDate cooldown,
                            Policy policy) throws SQLException {
        c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        c.setAutoCommit(false);
        try {
            try (PreparedStatement ps = prepare(c, "SELECT singleton FROM npc_rent_mutex WHERE singleton=1 FOR UPDATE");
                 ResultSet rs = ps.executeQuery()) { if (!rs.next()) throw new SQLException("Rent mutex unavailable"); }
            try (PreparedStatement ps = prepare(c, "SELECT enabled,owner_uuid,ownership_epoch FROM npc_properties WHERE property_id=? FOR UPDATE",
                    target.propertyId()); ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || !rs.getBoolean("enabled") || !sameOwner(rs, target)) { c.rollback(); return null; }
            }
            if (count(c, "SELECT COUNT(*) FROM npc_voluntary_departures WHERE economy_day=?", day.toString()) >= policy.serverDailyLimit()
                    || blocked(c, day, target.propertyId())
                    || candidates(c, day, target.propertyId(), policy.moods()).stream().noneMatch(x -> x.resident().equals(decision.resident()))) {
                c.rollback(); return null;
            }
            String prior;
            try (PreparedStatement ps = prepare(c, "SELECT move_cooldown_until_day FROM npc_tenants WHERE tenant_id=? FOR UPDATE",
                    decision.resident()); ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) { c.rollback(); return null; }
                prior = rs.getString(1);
            }
            TenancyDeparture departure = TenantRepository.vacateVoluntarily(c, decision.resident(), target.propertyId(), clock);
            if (departure == null) { c.rollback(); return null; }
            LocalDate until = prior != null && LocalDate.parse(prior).isAfter(cooldown) ? LocalDate.parse(prior) : cooldown;
            try (PreparedStatement ps = prepare(c, "UPDATE npc_tenants SET move_cooldown_until_day=? WHERE tenant_id=? AND property_id IS NULL",
                    until.toString(), decision.resident())) {
                if (ps.executeUpdate() != 1) throw new SQLException("Departure cooldown not recorded");
            }
            try (PreparedStatement ps = prepare(c, """
                    INSERT INTO npc_voluntary_departures(economy_day,property_id,resident_id,occupancy_revision_before,
                      score,leave_chance_bp,cooldown_until) VALUES (?,?,?,?,?,?,?)
                    """, day.toString(), target.propertyId(), decision.resident(), departure.occupancyRevisionBefore(),
                    decision.score(), decision.chanceBp(), until.toString())) {
                if (ps.executeUpdate() != 1) throw new SQLException("Voluntary departure not recorded");
            }
            c.commit();
            return new Departed(decision.resident(), target.propertyId(), departure.occupancyRevisionBefore(),
                    decision.score(), decision.chanceBp(), until);
        } catch (SQLException | RuntimeException failure) {
            try { c.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        } finally {
            c.setAutoCommit(true);
        }
    }

    /** Already departed today, or today's rent is already reserved for this home. */
    private static boolean blocked(Connection c, LocalDate day, UUID property) throws SQLException {
        return exists(c, "SELECT 1 FROM npc_voluntary_departures WHERE economy_day=? AND property_id=?", day.toString(), property)
                || exists(c, "SELECT 1 FROM npc_rent_reservations WHERE economy_day=? AND property_id=?", day.toString(), property);
    }

    /**
     * Housed residents who could leave today: in the enabled housing roster (so they can be housed again),
     * outside their move cooldown, not moved in today and with no rent already reserved for them today.
     * With moods, only residents whose mood has been recorded.
     */
    private static List<Candidate> candidates(Connection c, LocalDate day, UUID property, boolean moods) throws SQLException {
        List<Candidate> out = new ArrayList<>();
        String today = day.toString();
        try (PreparedStatement ps = prepare(c, """
                SELECT t.tenant_id,t.satisfaction FROM npc_tenants t
                JOIN npc_housing_members m ON m.resident_id=t.tenant_id AND m.enabled=TRUE
                WHERE t.property_id=? AND (t.move_cooldown_until_day IS NULL OR t.move_cooldown_until_day<=?)
                  AND NOT EXISTS(SELECT 1 FROM npc_rent_cohort r WHERE r.resident_id=t.tenant_id AND r.economy_day=?)
                  AND NOT EXISTS(SELECT 1 FROM npc_housing_moves h WHERE h.resident_id=t.tenant_id AND h.economy_day=?)
                """ + (moods ? " AND t.mood_day IS NOT NULL" : "") + " ORDER BY t.tenant_id LIMIT " + (MAX_RESIDENTS + 1),
                property, today, today, today); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                if (out.size() >= MAX_RESIDENTS) throw new SQLException("Occupancy exceeds capacity");
                out.add(new Candidate(Uuids.fromBytes(rs.getBytes("tenant_id")), Math.max(0, Math.min(100, rs.getInt("satisfaction")))));
            }
        }
        return out;
    }

    private static boolean sameOwner(ResultSet rs, Target target) throws SQLException {
        byte[] owner = rs.getBytes("owner_uuid");
        return owner != null && target.ownerId().equals(Uuids.fromBytes(owner)) && rs.getInt("ownership_epoch") == target.ownershipEpoch();
    }

    private static boolean exists(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement ps = prepare(c, sql, values); ResultSet rs = ps.executeQuery()) { return rs.next(); }
    }

    private static long count(Connection c, String sql, Object... values) throws SQLException {
        try (PreparedStatement ps = prepare(c, sql, values); ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) throw new SQLException("Count unavailable");
            return rs.getLong(1);
        }
    }

    private static PreparedStatement prepare(Connection c, String sql, Object... values) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        try {
            ps.setQueryTimeout(QUERY_SECONDS);
            for (int i = 0; i < values.length; i++) {
                if (values[i] instanceof UUID uuid) ps.setBytes(i + 1, Uuids.toBytes(uuid));
                else ps.setObject(i + 1, values[i]);
            }
            return ps;
        } catch (SQLException failure) {
            try { ps.close(); } catch (SQLException close) { failure.addSuppressed(close); }
            throw failure;
        }
    }
}

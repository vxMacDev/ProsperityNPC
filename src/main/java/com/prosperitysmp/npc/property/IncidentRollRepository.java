package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.clock.EconomyClock;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.economy.RepairPrices;
import com.prosperitysmp.npc.formulas.IncidentType;
import com.prosperitysmp.npc.formulas.UpgradeType;
import com.prosperitysmp.npc.sim.SimulationLogic;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.Objects;
import java.util.UUID;

/**
 * One durable incident decision per property/day, including quiet and blocked days.
 * SQL-only: caller must coordinate live ARM/lifecycle authority. No cash or tenant mutation.
 */
public final class IncidentRollRepository {
    public enum Outcome { NONE, INCIDENT, BLOCKED_OPEN, PREVENTED }
    /** conditionAfter is the current locked condition, including on a replay after maintenance. */
    public record Result(boolean newlyRecorded, Outcome outcome, Long incidentId, int conditionAfter) { }
    private record PropertyState(UUID owner, int epoch, int condition, long revision, boolean enabled, Property property) { }
    private record Upgrade(long id) { }
    private final Database database;
    private final SimulationLogic simulation;
    private final Clock clock;
    private final EconomyClock economyClock;
    private final RepairPrices prices;
    private final InsuranceRepository insurance = new InsuranceRepository();

    public IncidentRollRepository(Database database, SimulationLogic simulation, Clock clock, ZoneId zone, int resetHour) {
        this(database, simulation, clock, zone, resetHour, RepairPrices.DEFAULT);
    }

    public IncidentRollRepository(Database database, SimulationLogic simulation, Clock clock, ZoneId zone, int resetHour,
                                  RepairPrices prices) {
        this.database = Objects.requireNonNull(database);
        this.simulation = Objects.requireNonNull(simulation);
        this.clock = Objects.requireNonNull(clock);
        this.economyClock = new EconomyClock(clock, zone, resetHour);
        this.prices = Objects.requireNonNull(prices);
    }

    /** Settlement shares this authority; clock policy changes require reconstruction, not config reload. */
    public LocalDate today() { return economyClock.today(); }

    public Result roll(UUID property, UUID expectedOwner, int expectedEpoch, LocalDate day) throws SQLException {
        Objects.requireNonNull(property);
        Objects.requireNonNull(expectedOwner);
        Objects.requireNonNull(day);
        if (expectedEpoch < 0 || day.getYear() < 1 || day.getYear() > 9999)
            throw new IllegalArgumentException("Invalid incident day/epoch");
        try (Connection c = database.getConnection()) {
            c.setAutoCommit(false);
            try {
                PropertyState p = property(c, property);
                require(p.enabled() && p.owner().equals(expectedOwner) && p.epoch() == expectedEpoch,
                        "Incident property ownership unavailable");
                Instant at = clock.instant().truncatedTo(ChronoUnit.MILLIS);
                require(day.equals(economyClock.dayAt(at)), "Incident roll requires the current economy day");
                LocalDateTime now = LocalDateTime.ofInstant(at, ZoneOffset.UTC);
                LocalDateTime due = now.plusDays(1);
                require(now.getYear() >= 1 && due.getYear() <= 9999, "Incident clock out of SQL range");
                Result prior = prior(c, property, day, p.condition());
                if (prior != null) {
                    c.commit();
                    return prior;
                }
                // Older incident records do not prove that damage was applied atomically. Never adopt them.
                try (PreparedStatement ps = prepare(c,
                        "SELECT incident_id FROM npc_incidents WHERE property_id=? AND economy_day=? LIMIT 1 FOR UPDATE",
                        property, day.toString()); ResultSet rs = ps.executeQuery()) {
                    require(!next(c, ps, rs), "Legacy incident day requires review before adoption");
                }
                Long open = openIncident(c, property);
                Outcome outcome;
                IncidentType type = null;
                Long incident = open;
                int condition = p.condition();
                if (open != null) outcome = Outcome.BLOCKED_OPEN;
                else {
                    type = simulation.incidentForDay(property, day.toString(), condition).orElse(null);
                    outcome = type == null ? Outcome.NONE : Outcome.INCIDENT;
                    if (type != null) {
                        EnumMap<UpgradeType, Upgrade> upgrades = upgrades(c, property);
                        UpgradeType affected = upgrades.containsKey(UpgradeType.ADVERTISING)
                                ? UpgradeType.ADVERTISING : upgrades.containsKey(UpgradeType.FACILITIES)
                                ? UpgradeType.FACILITIES : null;
                        boolean covered = insurance.isCovered(c, p.property(), at, day);
                        incident = insertIncident(c, property, day, now, due, type, affected, covered);
                        if (covered) {
                            var prevention = insurance.recordPrevention(c, p.property(), incident, at, day);
                            require(prevention.status() == InsuranceRepository.PreventionStatus.PREVENTED
                                    && prevention.receipt().incidentId() == incident,
                                    "Insurance prevention binding differs");
                            outcome = Outcome.PREVENTED;
                        } else {
                            condition = Math.max(0, condition - type.conditionDamage());
                            try (PreparedStatement ps = prepare(c, "UPDATE npc_properties SET condition_score=?,row_version=row_version+1 "
                                    + "WHERE property_id=? AND row_version=?", condition, property, p.revision())) {
                                one(c, ps);
                            }
                            if (affected != null) {
                                try (PreparedStatement ps = prepare(c, "UPDATE npc_upgrades SET disabled_incident_id=? "
                                        + "WHERE id=? AND property_id=? AND disabled_incident_id IS NULL",
                                        incident, upgrades.get(affected).id(), property)) {
                                    one(c, ps);
                                }
                            }
                        }
                    }
                }
                try (PreparedStatement ps = prepare(c, """
                        INSERT INTO npc_incident_rolls (property_id,economy_day,ownership_epoch,owner_uuid,outcome,
                          incident_id,incident_type,condition_before,condition_after,recorded_at)
                        VALUES (?,?,?,?,?,?,?,?,?,?)
                        """, property, day.toString(), expectedEpoch, expectedOwner, outcome.name(), incident,
                        type == null ? null : type.name(), p.condition(), condition, now)) {
                    one(c, ps);
                }
                warnings(c, null, null);
                c.commit();
                return new Result(true, outcome, incident, condition);
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

    private static PropertyState property(Connection c, UUID property) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT * FROM npc_properties WHERE property_id=? FOR UPDATE", property);
             ResultSet rs = ps.executeQuery()) {
            require(next(c, ps, rs), "Incident property missing");
            byte[] owner = rs.getBytes("owner_uuid");
            require(owner != null && owner.length == 16, "Incident owner missing");
            Property full = new Property(property, Uuids.fromBytes(rs.getBytes("world_uuid")), rs.getString("region_id"),
                    rs.getString("market_id"), rs.getString("class_key"), (int) number(rs, "capacity", 1, 4),
                    rs.getInt("reference_rent_cents"), rs.getInt("asking_rent_cents"), Uuids.fromBytes(owner),
                    (int) number(rs, "ownership_epoch", 0, Integer.MAX_VALUE),
                    (int) number(rs, "condition_score", 0, 100), number(rs, "enabled", 0, 1) == 1);
            PropertyState p = new PropertyState(full.ownerUuid(), full.ownershipEpoch(), full.conditionScore(),
                    number(rs, "row_version", 0, Long.MAX_VALUE - 1), full.enabled(), full);
            require(!next(c, ps, rs), "Duplicate incident property");
            return p;
        }
    }

    private static Result prior(Connection c, UUID property, LocalDate day, int currentCondition) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT outcome,incident_id,incident_type,condition_before,condition_after "
                + "FROM npc_incident_rolls WHERE property_id=? AND economy_day=? FOR UPDATE", property, day.toString());
             ResultSet rs = ps.executeQuery()) {
            if (!next(c, ps, rs)) return null;
            Outcome outcome;
            try { outcome = Outcome.valueOf(rs.getString("outcome")); }
            catch (IllegalArgumentException | NullPointerException e) { throw new SQLException("Invalid incident outcome", e); }
            Long incident = rs.getObject("incident_id") == null ? null : number(rs, "incident_id", 1, Long.MAX_VALUE);
            String type = rs.getString("incident_type");
            int before = (int) number(rs, "condition_before", 0, 100), after = (int) number(rs, "condition_after", 0, 100);
            switch (outcome) {
                case NONE -> require(incident == null && type == null && before == after, "Invalid quiet-day receipt");
                case BLOCKED_OPEN -> require(incident != null && type == null && before == after, "Invalid blocked-day receipt");
                case INCIDENT, PREVENTED -> {
                    IncidentType kind;
                    try { kind = IncidentType.valueOf(type); }
                    catch (IllegalArgumentException | NullPointerException e) { throw new SQLException("Invalid incident type", e); }
                    require(incident != null && after == (outcome == Outcome.PREVENTED
                            ? before : Math.max(0, before - kind.conditionDamage())), "Invalid incident receipt");
                }
            }
            require(!next(c, ps, rs), "Duplicate incident outcome");
            return new Result(false, outcome, incident, currentCondition);
        }
    }

    private static Long openIncident(Connection c, UUID property) throws SQLException {
        try (PreparedStatement ps = prepare(c, "SELECT incident_id FROM npc_incidents WHERE property_id=? "
                + "AND resolved_at IS NULL AND prevented=FALSE ORDER BY incident_id LIMIT 2 ROWS EXAMINED 1024 FOR UPDATE",
                property); ResultSet rs = ps.executeQuery()) {
            if (!next(c, ps, rs)) return null;
            long id = number(rs, "incident_id", 1, Long.MAX_VALUE);
            require(!next(c, ps, rs), "Multiple open incidents require review");
            return id;
        }
    }

    private static EnumMap<UpgradeType, Upgrade> upgrades(Connection c, UUID property) throws SQLException {
        EnumMap<UpgradeType, Upgrade> result = new EnumMap<>(UpgradeType.class);
        int count = 0;
        try (PreparedStatement ps = prepare(c, "SELECT id,upgrade_type,level,disabled_incident_id FROM npc_upgrades "
                + "WHERE property_id=? ORDER BY upgrade_type LIMIT 3 FOR UPDATE", property); ResultSet rs = ps.executeQuery()) {
            while (next(c, ps, rs)) {
                require(++count <= 2, "Too many incident upgrades");
                UpgradeType type;
                try { type = UpgradeType.valueOf(rs.getString("upgrade_type")); }
                catch (IllegalArgumentException | NullPointerException e) { throw new SQLException("Invalid upgrade type", e); }
                int level = (int) number(rs, "level", 0, 3);
                require(rs.getObject("disabled_incident_id") == null, "Upgrade disabled without an open incident");
                if (level > 0) {
                    Upgrade previous = result.put(type, new Upgrade(number(rs, "id", 1, Long.MAX_VALUE)));
                    require(previous == null, "Duplicate upgrade type");
                }
            }
        }
        return result;
    }

    private long insertIncident(Connection c, UUID property, LocalDate day, LocalDateTime at,
                                       LocalDateTime due, IncidentType type, UpgradeType affected, boolean prevented) throws SQLException {
        long cash = prices.cents(type);
        Long price = cash == type.cashRepairCents() ? null : cash;
        String priceColumn = price == null ? "" : ",configured_repair_cents";
        String priceValue = price == null ? "" : ",?";
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO npc_incidents (property_id,incident_type,cash_repair_cents,item_repair_spec,
                  condition_damage,affected_upgrade_type,economy_day,created_at,due_at,prevented%s)
                VALUES (?,?,?,?,?,?,?,?,?,?%s)
                """.formatted(priceColumn, priceValue), Statement.RETURN_GENERATED_KEYS)) {
            ps.setQueryTimeout(5);
            bind(ps, property, type.name(), cash, type.itemRepairSpec(), type.conditionDamage(),
                    affected == null ? null : affected.name(), day.toString(), at, due, prevented);
            if (price != null) ps.setLong(11, price);
            one(c, ps);
            try (ResultSet rs = ps.getGeneratedKeys()) {
                require(next(c, ps, rs), "Incident key missing");
                long id = rs.getLong(1);
                require(!rs.wasNull() && id > 0 && !next(c, ps, rs), "Invalid incident key");
                return id;
            }
        }
    }

    private static PreparedStatement prepare(Connection c, String sql, Object... values) throws SQLException {
        PreparedStatement ps = c.prepareStatement(sql);
        try {
            ps.setQueryTimeout(5);
            bind(ps, values);
            return ps;
        } catch (SQLException | RuntimeException e) {
            ps.close();
            throw e;
        }
    }
    private static void bind(PreparedStatement ps, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) {
            if (values[i] instanceof UUID id) ps.setBytes(i + 1, Uuids.toBytes(id));
            else ps.setObject(i + 1, values[i]);
        }
    }
    private static void one(Connection c, PreparedStatement ps) throws SQLException {
        require(ps.executeUpdate() == 1, "Incident write affected unexpected rows");
        warnings(c, ps, null);
    }
    private static boolean next(Connection c, PreparedStatement ps, ResultSet rs) throws SQLException {
        warnings(c, ps, rs);
        boolean hasRow = rs.next();
        warnings(c, ps, rs);
        return hasRow;
    }
    private static long number(ResultSet rs, String column, long min, long max) throws SQLException {
        BigDecimal raw = rs.getBigDecimal(column);
        require(raw != null, "Missing incident numeric field");
        try {
            long n = raw.longValueExact();
            require(n >= min && n <= max, "Invalid incident numeric field");
            return n;
        } catch (ArithmeticException e) { throw new SQLException("Invalid incident numeric precision", e); }
    }
    private static void warnings(Connection c, PreparedStatement ps, ResultSet rs) throws SQLException {
        if (c.getWarnings() != null || (ps != null && ps.getWarnings() != null) || (rs != null && rs.getWarnings() != null))
            throw new SQLException("Incident query returned a warning; partial results rejected");
    }
    private static void require(boolean condition, String error) throws SQLException {
        if (!condition) throw new SQLException(error);
    }
}

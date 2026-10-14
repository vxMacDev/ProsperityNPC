package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.Uuids;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Worker-only. Writes only mood and satisfaction (plus the day marker) on housed residents; never
 * touches occupancy, row_version or money. Each resident updates at most once per economy day, so
 * a repeated or interrupted run is safe and simply continues.
 */
public final class TenantMoodRepository {
    public static final int MAX_BATCH = 4096;
    private static final int QUERY_SECONDS = 5;
    private static final int MAX_INCIDENTS = 16;
    private final Database database;

    public TenantMoodRepository(Database database) { this.database = Objects.requireNonNull(database); }

    private record Housed(UUID tenant, int satisfaction, UUID property, int condition, long reference, long asking) { }

    /** Returns how many residents moved to this day's value. Residents already done for the day are skipped. */
    public int updateDay(LocalDate day) throws SQLException { return updateDay(day, MAX_BATCH); }

    public int updateDay(LocalDate day, int limit) throws SQLException { return updateDay(day, limit, Set.of()); }

    /**
     * Residents of {@code paused} homes (owner away today) are not stepped: their mood and satisfaction stay
     * as they were and their day marker is not advanced, so they simply continue once the owner is back.
     */
    public int updateDay(LocalDate day, int limit, Set<UUID> paused) throws SQLException {
        Objects.requireNonNull(day);
        Objects.requireNonNull(paused);
        if (limit < 1 || limit > MAX_BATCH) throw new IllegalArgumentException("Invalid mood batch size");
        if (paused.size() > MAX_BATCH) throw new IllegalArgumentException("Too many paused homes");
        String today = day.toString();
        List<Object> args = new ArrayList<>();
        args.add(today);
        args.addAll(paused.stream().sorted().toList());
        args.add(limit);
        String skip = paused.isEmpty() ? "" : " AND t.property_id NOT IN (" + "?,".repeat(paused.size() - 1) + "?)";
        try (Connection c = database.getConnection()) {
            List<Housed> housed = new ArrayList<>();
            try (PreparedStatement ps = prepare(c, """
                    SELECT t.tenant_id, t.satisfaction, p.property_id, p.condition_score,
                           p.reference_rent_cents, p.asking_rent_cents
                    FROM npc_tenants t JOIN npc_properties p ON p.property_id=t.property_id
                    WHERE p.enabled=TRUE AND (t.mood_day IS NULL OR t.mood_day<?)""" + skip + """

                    ORDER BY t.tenant_id LIMIT ?
                    """, args.toArray()); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) housed.add(new Housed(Uuids.fromBytes(rs.getBytes("tenant_id")), rs.getInt("satisfaction"),
                        Uuids.fromBytes(rs.getBytes("property_id")), rs.getInt("condition_score"),
                        rs.getLong("reference_rent_cents"), rs.getLong("asking_rent_cents")));
            }
            Map<UUID, TenantMood.Inputs> perProperty = new HashMap<>();
            int updated = 0;
            for (Housed h : housed) {
                TenantMood.Inputs in = perProperty.get(h.property());
                if (in == null) {
                    in = new TenantMood.Inputs(h.condition(), openIncidentAges(c, h.property(), day),
                            bill(c, h.property(), day.minusDays(1)), h.asking(), h.reference());
                    perProperty.put(h.property(), in);
                }
                int satisfaction = TenantMood.next(Math.max(0, Math.min(100, h.satisfaction())), TenantMood.target(in));
                try (PreparedStatement ps = prepare(c, """
                        UPDATE npc_tenants SET satisfaction=?, mood=?, mood_day=?
                        WHERE tenant_id=? AND (mood_day IS NULL OR mood_day<?)
                        """, satisfaction, TenantMood.Band.of(satisfaction).name(), today, h.tenant(), today)) {
                    updated += ps.executeUpdate();
                }
            }
            return updated;
        }
    }

    /** The resident's stored band, or empty when the resident has no row or has never been updated. */
    public Optional<TenantMood.Band> band(UUID tenant) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = prepare(c, "SELECT mood FROM npc_tenants "
                     + "WHERE tenant_id=? AND property_id IS NOT NULL AND mood_day IS NOT NULL", tenant);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return Optional.empty();
            try { return Optional.of(TenantMood.Band.valueOf(rs.getString(1))); }
            catch (IllegalArgumentException unknown) { return Optional.empty(); }
        }
    }

    private static List<Integer> openIncidentAges(Connection c, UUID property, LocalDate day) throws SQLException {
        List<Integer> ages = new ArrayList<>();
        try (PreparedStatement ps = prepare(c, """
                SELECT economy_day FROM npc_incidents
                WHERE property_id=? AND resolved_at IS NULL AND prevented=FALSE
                ORDER BY incident_id LIMIT ?
                """, property, MAX_INCIDENTS); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) ages.add((int) Math.max(0, ChronoUnit.DAYS.between(LocalDate.parse(rs.getString(1)), day)));
        }
        return ages;
    }

    private static TenantMood.Bill bill(Connection c, UUID property, LocalDate billDay) throws SQLException {
        try (PreparedStatement ps = prepare(c, """
                SELECT EXISTS(SELECT 1 FROM npc_utility_payments u WHERE u.property_id=b.property_id
                              AND u.economy_day=b.economy_day AND u.state='PAID') AS paid,
                       EXISTS(SELECT 1 FROM npc_utility_payments u WHERE u.property_id=b.property_id
                              AND u.economy_day=b.economy_day AND u.state IN ('WAITING','REVIEW_REQUIRED')) AS unsettled
                FROM npc_utility_bills b WHERE b.property_id=? AND b.economy_day=?
                """, property, billDay.toString()); ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) return TenantMood.Bill.NONE;
            if (rs.getBoolean("paid")) return TenantMood.Bill.PAID;
            // An unresolved or ambiguous payment is not proof of neglect.
            return rs.getBoolean("unsettled") ? TenantMood.Bill.NONE : TenantMood.Bill.UNPAID;
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
            try {
                ps.close();
            } catch (SQLException close) {
                failure.addSuppressed(close);
            }
            throw failure;
        }
    }
}

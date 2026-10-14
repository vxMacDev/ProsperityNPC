package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.formulas.IncidentType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/** Data access for property incidents (at most one unresolved; one roll per day). */
public final class IncidentRepository {

    private final Database database;

    public IncidentRepository(Database database) {
        this.database = database;
    }

    /** True if the property has an unresolved incident (blocks new ones). */
    public boolean hasOpenIncident(UUID propertyId) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM npc_incidents WHERE property_id = ? AND resolved_at IS NULL "
                             + "AND prevented = FALSE LIMIT 1")) {
            ps.setBytes(1, Uuids.toBytes(propertyId));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** True if a roll already exists for this (property, day) — prevents rerolls. */
    public boolean hasRollForDay(UUID propertyId, String economyDay) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM npc_incidents WHERE property_id = ? AND economy_day = ? LIMIT 1")) {
            ps.setBytes(1, Uuids.toBytes(propertyId));
            ps.setString(2, economyDay);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** True if an unresolved incident is past its due time (feeds occupancy leave-chance). */
    public boolean hasOverdueIncident(UUID propertyId) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM npc_incidents WHERE property_id = ? AND resolved_at IS NULL "
                             + "AND prevented = FALSE AND due_at < CURRENT_TIMESTAMP(3) LIMIT 1")) {
            ps.setBytes(1, Uuids.toBytes(propertyId));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Legacy record-only import/fixture API, not the runtime generation path.
     * Runtime generation uses IncidentRollRepository so damage and evidence commit together.
     * {@code prevented} true means insurance
     * absorbed it (still recorded for audit, but it does no damage and is resolved).
     */
    public void create(UUID propertyId, IncidentType type, String affectedUpgradeType,
                       String economyDay, long dueAtEpochMillis, boolean prevented) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO npc_incidents
                       (property_id, incident_type, cash_repair_cents, item_repair_spec, condition_damage,
                        affected_upgrade_type, economy_day, due_at, resolved_at, prevented)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            ps.setBytes(1, Uuids.toBytes(propertyId));
            ps.setString(2, type.name());
            ps.setLong(3, type.cashRepairCents());
            ps.setString(4, type.itemRepairSpec());
            ps.setInt(5, prevented ? 0 : type.conditionDamage());
            if (affectedUpgradeType == null) {
                ps.setNull(6, java.sql.Types.VARCHAR);
            } else {
                ps.setString(6, affectedUpgradeType);
            }
            ps.setString(7, economyDay);
            // Bind UTC wall-clock fields directly; Timestamp encoding uses the JVM/driver zone.
            ps.setObject(8, LocalDateTime.ofInstant(Instant.ofEpochMilli(dueAtEpochMillis), ZoneOffset.UTC));
            if (prevented) {
                ps.setObject(9, LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(System.currentTimeMillis()), ZoneOffset.UTC));
            } else {
                ps.setNull(9, java.sql.Types.TIMESTAMP);
            }
            ps.setBoolean(10, prevented);
            ps.executeUpdate();
        }
    }
}

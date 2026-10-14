package com.prosperitysmp.npc.property;

import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.formulas.UpgradeType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

/** Data access for property upgrades (ADVERTISING / FACILITIES, levels 0-3). */
public final class UpgradeRepository {

    private final Database database;

    public UpgradeRepository(Database database) {
        this.database = database;
    }

    public int levelOf(UUID propertyId, String upgradeType) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT level FROM npc_upgrades WHERE property_id = ? AND upgrade_type = ?")) {
            ps.setBytes(1, Uuids.toBytes(propertyId));
            ps.setString(2, upgradeType);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    public int advertisingLevel(UUID propertyId) throws SQLException {
        return levelOf(propertyId, "ADVERTISING");
    }

    public int facilitiesLevel(UUID propertyId) throws SQLException {
        return levelOf(propertyId, "FACILITIES");
    }

    /** Operational benefit only; installed-level billing and sequential upgrade checks remain separate. */
    public int effectiveLevel(UUID propertyId, UpgradeType type) throws SQLException {
        Objects.requireNonNull(propertyId);
        Objects.requireNonNull(type);
        try (Connection c = database.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT level,disabled_incident_id FROM npc_upgrades WHERE property_id=? AND upgrade_type=?")) {
            ps.setQueryTimeout(5);
            ps.setBytes(1, Uuids.toBytes(propertyId));
            ps.setString(2, type.name());
            try (ResultSet rs = ps.executeQuery()) {
                checkWarnings(c, ps, rs);
                boolean present = rs.next();
                checkWarnings(c, ps, rs);
                if (!present) return 0;
                int level = rs.getInt("level");
                if (rs.wasNull() || level < 0 || level > UpgradeType.MAX_LEVEL)
                    throw new SQLException("Invalid effective upgrade level");
                int effective = rs.getObject("disabled_incident_id") == null ? level : 0;
                checkWarnings(c, ps, rs);
                boolean duplicate = rs.next();
                checkWarnings(c, ps, rs);
                if (duplicate)
                    throw new SQLException("Incomplete effective upgrade read");
                return effective;
            }
        }
    }

    private static void checkWarnings(Connection c, PreparedStatement ps, ResultSet rs) throws SQLException {
        if (c.getWarnings() != null || ps.getWarnings() != null || rs.getWarnings() != null)
            throw new SQLException("Incomplete effective upgrade read");
    }

    /** Sets an upgrade's level (upsert), accumulating the purchase cost for audit. */
    public void setLevel(UUID propertyId, String upgradeType, int level, long costCents) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO npc_upgrades (property_id, upgrade_type, level, purchase_cost_cents, installed_at)
                     VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP(3))
                     ON DUPLICATE KEY UPDATE level = VALUES(level),
                       purchase_cost_cents = purchase_cost_cents + VALUES(purchase_cost_cents),
                       installed_at = CURRENT_TIMESTAMP(3)
                     """)) {
            ps.setBytes(1, Uuids.toBytes(propertyId));
            ps.setString(2, upgradeType);
            ps.setInt(3, level);
            ps.setLong(4, costCents);
            ps.executeUpdate();
        }
    }

    /** Sum of all installed upgrade levels (drives the utilities cost). */
    public int totalLevels(UUID propertyId) throws SQLException {
        try (Connection c = database.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COALESCE(SUM(level), 0) FROM npc_upgrades WHERE property_id = ?")) {
            ps.setBytes(1, Uuids.toBytes(propertyId));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }
}

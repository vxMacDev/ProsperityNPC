package com.prosperitysmp.npc.charge.meter;

import com.prosperitysmp.npc.db.Uuids;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Cumulative meters report a running total, so a bill charges the increase since the last reading. The first
 * reading only sets the baseline and bills nothing. A lower reading means the meter was reset: nothing is billed,
 * the new value becomes the baseline and staff are told. Nothing here ever bills a difference that went down.
 */
public final class MeterBaselines {
    /** What one cumulative reading means. {@code units} is what the bill line may use; only INCREASE bills. */
    public enum Kind { FIRST, INCREASE, RESET }

    public record Delta(Kind kind, long units) { }

    private MeterBaselines() { }

    public static Delta delta(OptionalLong previous, long current) {
        if (current < 0) throw new IllegalArgumentException("negative reading");
        if (previous.isEmpty()) return new Delta(Kind.FIRST, 0);
        long before = previous.getAsLong();
        if (current < before) return new Delta(Kind.RESET, 0);
        return new Delta(Kind.INCREASE, current - before);
    }

    /** The stored baseline, locked for the caller's transaction. */
    public static OptionalLong read(Connection c, UUID property, String meterId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT last_value FROM npc_meter_baselines WHERE property_id=? AND meter_id=? FOR UPDATE")) {
            ps.setQueryTimeout(5);
            ps.setBytes(1, Uuids.toBytes(property));
            ps.setString(2, meterId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return OptionalLong.empty();
                long value = rs.getLong(1);
                if (rs.next()) throw new SQLException("Duplicate meter baseline");
                return OptionalLong.of(value);
            }
        }
    }

    /** Stores the latest reading as the baseline. Caller's transaction, same one as the invoice. */
    public static void write(Connection c, UUID property, String meterId, long value, LocalDate day) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO npc_meter_baselines(property_id,meter_id,last_value,last_day) VALUES (?,?,?,?)
                ON DUPLICATE KEY UPDATE last_value=VALUES(last_value),last_day=VALUES(last_day),updated_at=CURRENT_TIMESTAMP(3)
                """)) {
            ps.setQueryTimeout(5);
            ps.setBytes(1, Uuids.toBytes(property));
            ps.setString(2, meterId);
            ps.setLong(3, value);
            ps.setString(4, day.toString());
            ps.executeUpdate();
        }
    }
}

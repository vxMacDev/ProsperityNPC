package com.prosperitysmp.npc.charge.meter;

import com.prosperitysmp.npc.charge.BillsTestDatabase;
import com.prosperitysmp.npc.db.Uuids;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MeterBaselinesIT {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 17);
    private static BillsTestDatabase db;

    @BeforeAll static void boot() throws Exception {
        db = BillsTestDatabase.boot("npc_meter_baseline");
    }

    @AfterAll static void stop() throws Exception {
        if (db != null) db.close();
    }

    @BeforeEach void reset() throws SQLException {
        db.execute("DELETE FROM npc_meter_baselines");
    }

    private static OptionalLong read(UUID home, String meter) throws SQLException {
        try (Connection c = db.database().getConnection()) {
            return MeterBaselines.read(c, home, meter);
        }
    }

    private static void write(UUID home, String meter, long value, LocalDate day) throws SQLException {
        try (Connection c = db.database().getConnection()) {
            MeterBaselines.write(c, home, meter, value, day);
        }
    }

    private static String lastDay(UUID home, String meter) throws SQLException {
        try (Connection c = db.database().getConnection();
             var ps = c.prepareStatement("SELECT last_day FROM npc_meter_baselines WHERE property_id=? AND meter_id=?")) {
            ps.setBytes(1, Uuids.toBytes(home));
            ps.setString(2, meter);
            try (var rows = ps.executeQuery()) {
                assertTrue(rows.next());
                return rows.getString(1);
            }
        }
    }

    @Test void anUnknownMeterHasNoBaseline() throws Exception {
        UUID home = db.home(UUID.randomUUID());
        assertEquals(OptionalLong.empty(), read(home, "power"));
        assertEquals(OptionalLong.empty(), read(UUID.randomUUID(), "power"));
    }

    @Test void aWrittenBaselineReadsBack() throws Exception {
        UUID home = db.home(UUID.randomUUID());
        write(home, "power", 1_234, DAY);
        assertEquals(OptionalLong.of(1_234), read(home, "power"));
        assertEquals("2026-10-17", lastDay(home, "power"));
    }

    @Test void aSecondWriteReplacesTheBaselineInsteadOfAddingARow() throws Exception {
        UUID home = db.home(UUID.randomUUID());
        write(home, "power", 100, DAY);
        write(home, "power", 40, DAY.plusDays(1));
        assertEquals(OptionalLong.of(40), read(home, "power"));
        assertEquals("2026-10-18", lastDay(home, "power"));
        assertEquals(1, db.count("npc_meter_baselines"));
    }

    @Test void baselinesAreKeptPerHomeAndPerMeter() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID first = db.home(owner), second = db.home(owner);
        write(first, "power", 10, DAY);
        write(second, "power", 20, DAY);
        write(first, "water", 30, DAY);
        assertEquals(OptionalLong.of(10), read(first, "power"));
        assertEquals(OptionalLong.of(20), read(second, "power"));
        assertEquals(OptionalLong.of(30), read(first, "water"));
        assertEquals(OptionalLong.empty(), read(second, "water"));
        assertEquals(3, db.count("npc_meter_baselines"));
    }

    @Test void meterIdsAreCaseSensitive() throws Exception {
        UUID home = db.home(UUID.randomUUID());
        write(home, "power", 10, DAY);
        assertEquals(OptionalLong.empty(), read(home, "Power"));
    }

    @Test void theBoundsOfAReadingAreStored() throws Exception {
        UUID home = db.home(UUID.randomUUID());
        write(home, "zero", 0, DAY);
        write(home, "most", 1_000_000_000L, DAY);
        assertEquals(OptionalLong.of(0), read(home, "zero"));
        assertEquals(OptionalLong.of(1_000_000_000L), read(home, "most"));
    }

    @Test void theTableRefusesAReadingOutsideTheMeterRange() throws Exception {
        UUID home = db.home(UUID.randomUUID());
        assertThrows(SQLException.class, () -> write(home, "power", 1_000_000_001L, DAY));
        assertThrows(SQLException.class, () -> write(home, "power", -1, DAY));
        assertEquals(0, db.count("npc_meter_baselines"));
    }

    @Test void aBaselineNeedsAnEnrolledHome() {
        assertThrows(SQLException.class, () -> write(UUID.randomUUID(), "power", 1, DAY));
    }

    @Test void aDuplicatedBaselineRowIsAnErrorNotAGuess() throws Exception {
        UUID home = db.home(UUID.randomUUID());
        db.execute("ALTER TABLE npc_meter_baselines ADD INDEX ix_bills_dup (property_id), DROP PRIMARY KEY");
        try {
            db.execute("INSERT INTO npc_meter_baselines(property_id,meter_id,last_value,last_day) VALUES (?,?,?,?)", home, "power", 5L, "2026-10-17");
            db.execute("INSERT INTO npc_meter_baselines(property_id,meter_id,last_value,last_day) VALUES (?,?,?,?)", home, "power", 9L, "2026-10-17");
            var failure = assertThrows(SQLException.class, () -> read(home, "power"));
            assertEquals("Duplicate meter baseline", failure.getMessage());
        } finally {
            db.execute("DELETE FROM npc_meter_baselines");
            db.execute("ALTER TABLE npc_meter_baselines ADD PRIMARY KEY (property_id,meter_id), DROP INDEX ix_bills_dup");
        }
    }
}

package com.prosperitysmp.npc.command;

import com.prosperitysmp.npc.economy.accounting.InternalSink;
import com.prosperitysmp.npc.economy.accounting.SkriptSink;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G6: the /npcadmin health lines that dev.64 now builds from the selected pieces. The expected strings are the
 * dev.63 text, written out from the dev.63 concatenations, for the default pieces (arm and skript).
 */
class HealthTextGoldenTest {

    @Test void propertiesLineIsTheDev63TextForArm() {
        assertEquals("Properties: 12 enrolled; ARM available; last reconcile 42s ago",
                NpcAdminCommand.propertiesLine(12, "ARM", true, "42s ago", 0, 0));
        assertEquals("Properties: 3 enrolled; ARM UNAVAILABLE; last reconcile never; 2 region(s) MISSING (map change?)"
                        + "; 1 waiting on a busy home",
                NpcAdminCommand.propertiesLine(3, "ARM", false, "never", 2, 1));
    }

    @Test void bridgeLineIsTheDev63Text() {
        assertEquals("Skript bridge: heartbeat present (it publishes accounting totals; it never moves money)",
                NpcAdminCommand.bridgeLine(true, true));
        assertEquals("Skript bridge: no heartbeat (it publishes accounting totals; it never moves money)",
                NpcAdminCommand.bridgeLine(true, false));
        assertEquals("Skript bridge: not initialized (it publishes accounting totals; it never moves money)",
                NpcAdminCommand.bridgeLine(false, false));
    }

    @Test void accountingLineIsTheDev63TextForSkript() {
        var skript = new SkriptSink(() -> true);
        assertEquals("Accounting projection: acknowledged", NpcAdminCommand.accountingLine(skript, "skript", true));
        assertEquals("Accounting projection: waiting for verified snapshot and Skript acknowledgement",
                NpcAdminCommand.accountingLine(skript, "skript", false));
        // No economy ledger (no database): no sink was chosen, and the line reads as it always did.
        assertEquals("Accounting projection: waiting for verified snapshot and Skript acknowledgement",
                NpcAdminCommand.accountingLine(null, "", false));
    }

    @Test void otherModesNameThemselves() {
        assertEquals("Accounting projection: acknowledged by the plugin (accounting.sink=internal; no Skript totals are published)",
                NpcAdminCommand.accountingLine(new InternalSink(), "internal", true));
        assertTrue(NpcAdminCommand.accountingLine(null, "ledger", false).contains("'ledger' is unknown"));
        assertEquals("Properties: 1 enrolled; Example available; last reconcile never",
                NpcAdminCommand.propertiesLine(1, "Example", true, "never", 0, 0));
    }
}

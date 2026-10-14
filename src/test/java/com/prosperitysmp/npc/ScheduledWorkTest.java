package com.prosperitysmp.npc;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ScheduledWorkTest {
    @Test void independentPhasesDoNotRequireCashEnabled() {
        assertTrue(ProsperityNpcPlugin.hasScheduledWork(true, false, true, false));
        assertTrue(ProsperityNpcPlugin.hasScheduledWork(true, false, false, true));
        assertTrue(ProsperityNpcPlugin.hasScheduledWork(true, true, false, false));
        assertFalse(ProsperityNpcPlugin.hasScheduledWork(true, false, false, false));
    }

    @Test void systemSwitchAlwaysStopsTheScheduler() {
        for (int flags = 0; flags < 8; flags++) {
            assertFalse(ProsperityNpcPlugin.hasScheduledWork(false,
                    (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0));
        }
    }
}

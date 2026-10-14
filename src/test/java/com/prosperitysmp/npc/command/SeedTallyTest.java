package com.prosperitysmp.npc.command;

import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentRepository;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SeedTallyTest {
    @Test void aMismatchIsNamedAndTheLoopGoesOn() throws Exception {
        var cast = ResidentCatalogue.betaCast();
        var tally = new NpcTownCommand.SeedTally();
        tally.record(cast.get(0), () -> { throw new ResidentRepository.ProfileMismatchException(); });
        tally.record(cast.get(1), () -> true);
        tally.record(cast.get(2), () -> false);
        assertEquals(List.of("Resident cast: 1 registered, 1 unchanged.",
                "Mara Vale is already saved with different details. The saved version is kept."), tally.lines());
    }

    @Test void aProfileMismatchLogsTheSameErrorTypeAsDev58() {
        assertEquals("SQLException", NpcTownCommand.errorType(new ResidentRepository.ProfileMismatchException()));
        assertEquals("SQLException", NpcTownCommand.errorType(new SQLException("down")));
        assertEquals("IllegalStateException", NpcTownCommand.errorType(new IllegalStateException()));
    }

    @Test void anyOtherDatabaseFailureStillStopsTheSeed() {
        var tally = new NpcTownCommand.SeedTally();
        var person = ResidentCatalogue.betaCast().getFirst();
        assertThrows(SQLException.class, () -> tally.record(person, () -> { throw new SQLException("down"); }));
    }
}

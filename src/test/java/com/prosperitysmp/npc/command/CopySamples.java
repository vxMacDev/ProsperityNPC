package com.prosperitysmp.npc.command;

import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentRepository;

/** Renders the new staff replies from the real code so the copy lint test reads what staff will see. */
public final class CopySamples {
    private CopySamples() { }

    public static String all() {
        try {
            var tally = new NpcTownCommand.SeedTally();
            var cast = ResidentCatalogue.betaCast();
            tally.record(cast.get(0), () -> true);
            tally.record(cast.get(1), () -> false);
            tally.record(cast.get(2), () -> { throw new ResidentRepository.ProfileMismatchException(); });
            return String.join("\n", tally.lines()) + "\n" + NpcAdminCommand.skippedSkinsLine(1) + "\n"
                    + NpcAdminCommand.skippedSkinsLine(2) + "\n"
                    + com.prosperitysmp.npc.market.HousingRuntime.HELD_FOR_SKIN_REPLY;
        } catch (java.sql.SQLException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}

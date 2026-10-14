package com.prosperitysmp.npc.command;

import com.prosperitysmp.npc.town.PartnerPerks;
import com.prosperitysmp.npc.town.Partnership;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Renders the contract staff replies from the real code so the copy lint test reads what staff will see. */
public final class NpcTownCopySamples {
    private NpcTownCopySamples() { }

    public static List<String> all() {
        var out = new ArrayList<String>();
        UUID resident = new UUID(0, 9);
        var row = new Partnership(UUID.randomUUID(), new UUID(0, 1), resident, LocalDate.of(2026, 10, 14),
                LocalDate.of(2026, 10, 20), new PartnerPerks(40, 0), Partnership.Status.ACTIVE, null, null, UUID.randomUUID(), 1);
        out.addAll(NpcTownCommand.partnerLines(List.of(row, row), id -> "Ellis Ward", 4));
        out.addAll(NpcTownCommand.partnerLines(List.of(), id -> "Ellis Ward", 4));
        for (boolean consumed : new boolean[] {true, false})
            for (boolean partnership : new boolean[] {true, false})
                for (boolean started : new boolean[] {true, false}) {
                    out.add(NpcTownCommand.resolveLine(UUID.randomUUID(), consumed, "case-1", partnership, started, null));
                    for (var why : com.prosperitysmp.npc.town.TownContractRepository.NoPartnership.values())
                        out.add(NpcTownCommand.resolveLine(UUID.randomUUID(), consumed, "case-1", partnership, started, why));
                }
        return List.copyOf(out);
    }
}

package com.prosperitysmp.npc.command;

import com.prosperitysmp.npc.town.TownContractRepository.NoPartnership;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** The staff reply after contract-resolve names the real reason a partnership did not start. */
class NpcTownResolveLineTest {
    private static final UUID ID = new UUID(0, 42);

    @Test void theReplyNamesWhyNoPartnershipStarted() {
        String base = "Handover " + ID + " marked consumed. Case case-1.";
        assertEquals(base + " The partnership is active.", NpcTownCommand.resolveLine(ID, true, "case-1", true, true, null));
        assertEquals(base + " No partnership started: a seat or home check failed.",
                NpcTownCommand.resolveLine(ID, true, "case-1", true, false, NoPartnership.CHECK_FAILED));
        // Partnerships off or the offer removed: no seat or home check ran, so the reply says so.
        assertEquals(base + " No partnership started: partnerships are off or this resident has no offer.",
                NpcTownCommand.resolveLine(ID, true, "case-1", true, false, NoPartnership.NO_OFFER));
        assertEquals(base, NpcTownCommand.resolveLine(ID, true, "case-1", false, false, null), "a plain contract");
        // The offer now asks for another material or more of it: no partnership, and the reply says why.
        assertEquals(base + " No partnership started: the offer changed after this handover.",
                NpcTownCommand.resolveLine(ID, true, "case-1", true, false, NoPartnership.OFFER_CHANGED));
        // A row from before V37, and kept and current perks whose lower value is 0 on every perk: no seat or home check ran.
        assertEquals(base + " No partnership started: this handover has no saved partnership terms. It is older than the update that saves them.",
                NpcTownCommand.resolveLine(ID, true, "case-1", true, false, NoPartnership.NO_KEPT_TERMS));
        assertEquals(base + " No partnership started: the saved perks and the current offer leave no perk above 0.",
                NpcTownCommand.resolveLine(ID, true, "case-1", true, false, NoPartnership.NO_PERK_LEFT));
        assertEquals(base + " No new partnership started. If this handover was resolved before, the first reply says what happened.",
                NpcTownCommand.resolveLine(ID, true, "case-1", true, false, null), "a repeat of an earlier resolve decides nothing");
        assertEquals("Handover " + ID + " marked not consumed. Case case-1.",
                NpcTownCommand.resolveLine(ID, false, "case-1", true, false, NoPartnership.CHECK_FAILED));
        // The owner already has a partnership, or a renewal has nothing left to renew: no seat or home check failed.
        assertEquals(base + " No partnership started: the player already has a partnership.",
                NpcTownCommand.resolveLine(ID, true, "case-1", true, false, NoPartnership.valueOf("HAS_PARTNERSHIP")));
        assertEquals(base + " No partnership started: the player has no active partnership with this resident to renew.",
                NpcTownCommand.resolveLine(ID, true, "case-1", true, false, NoPartnership.valueOf("NOT_RENEWABLE")));
        // Every reason has its own line.
        var lines = new java.util.HashSet<String>();
        for (NoPartnership why : NoPartnership.values()) lines.add(NpcTownCommand.resolveLine(ID, true, "case-1", true, false, why));
        assertEquals(NoPartnership.values().length, lines.size());
    }

    /** The command needs Bukkit, so the call is pinned in its source: the reason is the one the repository returned. */
    @Test void theCommandPassesTheRepositoryReason() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/prosperitysmp/npc/command/NpcTownCommand.java"));
        assertTrue(source.contains("var terms = plugin.partnershipsOn() ? catalogue.partnerOffer(giver).map(catalogue::terms).orElse(null) : null;"));
        assertTrue(source.contains("outcome.partnership().isPresent(), outcome.noPartnership()), Set.of());"));
    }
}

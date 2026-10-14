package com.prosperitysmp.npc.town;

import com.prosperitysmp.npc.resident.ResidentCast;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentProfile;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TownTaskCatalogueTest {
    private static final UUID MARA = ResidentCatalogue.idFor("mara-vale");
    private static final UUID ELLIS = ResidentCatalogue.idFor("ellis-ward");
    private static final UUID NICO = ResidentCatalogue.idFor("nico-reed");

    @Test void tasksAreKeyedByTheBuiltInResidentIds() {
        assertEquals(List.of(MARA, ELLIS, NICO), ResidentCatalogue.betaCast().stream().map(ResidentProfile::id).toList());
        // The same UUIDs that are already saved on servers, derived independently of the code.
        assertEquals(UUID.nameUUIDFromBytes("prosperitynpc:dev-cast:v1:mara-vale".getBytes(StandardCharsets.UTF_8)), MARA);
        var material = TownTaskCatalogue.find("mara-material-list").orElseThrow();
        assertEquals(MARA, material.giverId());
        assertEquals(ELLIS, material.recipientId());
        assertEquals(2, TownTaskCatalogue.offers(MARA).size());
        assertEquals("Ellis Ward", TownTaskCatalogue.name(ELLIS));
        assertEquals("the named resident", TownTaskCatalogue.name(UUID.randomUUID()));
    }

    @Test void aCastWithoutEllisHidesEveryTaskThatNeedsHim() {
        var withoutEllis = new ResidentCast(List.of(ResidentCatalogue.betaCast().get(0), ResidentCatalogue.betaCast().get(2)),
                Map.of(), ResidentCast.Source.FILE);
        assertEquals(List.of("mara-restoration-plan"), TownTaskCatalogue.offers(MARA, withoutEllis).stream().map(TownTaskDefinition::id).toList());
        assertTrue(TownTaskCatalogue.offers(ELLIS, withoutEllis).isEmpty());
        assertEquals(List.of("nico-home-note"), TownTaskCatalogue.offers(NICO, withoutEllis).stream().map(TownTaskDefinition::id).toList());
        assertTrue(TownTaskCatalogue.find("mara-material-list", withoutEllis).isEmpty());
        assertTrue(TownTaskCatalogue.find("nico-local-partnership", withoutEllis).isEmpty());
        assertTrue(TownTaskCatalogue.find("nico-home-note", withoutEllis).isPresent(), "positive control");
        assertEquals("the named resident", TownTaskCatalogue.name(ELLIS, withoutEllis));
        assertEquals(TownTaskCatalogue.offers(MARA), TownTaskCatalogue.offers(MARA, ResidentCast.builtIn()));
    }
}

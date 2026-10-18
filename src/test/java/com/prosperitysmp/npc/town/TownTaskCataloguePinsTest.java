package com.prosperitysmp.npc.town;

import com.prosperitysmp.npc.resident.RelationshipMemory.Stage;
import com.prosperitysmp.npc.resident.ResidentCast;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** The six authored errands exactly as shipped, and which of them a smaller cast can still offer. */
class TownTaskCataloguePinsTest {
    private static final UUID MARA = ResidentCatalogue.idFor("mara-vale");
    private static final UUID ELLIS = ResidentCatalogue.idFor("ellis-ward");
    private static final UUID NICO = ResidentCatalogue.idFor("nico-reed");

    static Stream<Arguments> shipped() {
        return Stream.of(
                Arguments.of("mara-material-list", "Restoration materials",
                        "Take my sealed materials list to Ellis Ward.", MARA, ELLIS, Stage.STRANGER),
                Arguments.of("ellis-neighborhood-note", "A neighborhood introduction",
                        "Take my introduction note to Nico Reed.", ELLIS, NICO, Stage.STRANGER),
                Arguments.of("nico-home-note", "Settling into the neighborhood",
                        "Take my household note to Mara Vale.", NICO, MARA, Stage.STRANGER),
                Arguments.of("mara-restoration-plan", "A trusted restoration contact",
                        "I trust your follow-through. Take this restoration plan to Nico Reed.", MARA, NICO, Stage.TRUSTED),
                Arguments.of("ellis-supplier-introduction", "A trusted supplier introduction",
                        "Take my sealed supplier introduction to Mara Vale. We will remember who delivered it.", ELLIS, MARA, Stage.TRUSTED),
                Arguments.of("nico-local-partnership", "A trusted local contact",
                        "Take my local partnership note to Ellis Ward.", NICO, ELLIS, Stage.TRUSTED));
    }

    @ParameterizedTest @MethodSource("shipped")
    void eachShippedErrandIsExactlyAsAuthored(String id, String title, String description, UUID giver, UUID recipient, Stage stage) {
        TownTaskDefinition task = TownTaskCatalogue.find(id).orElseThrow();
        assertEquals(id, task.id());
        assertEquals(title, task.title());
        assertEquals(description, task.description());
        assertEquals(giver, task.giverId());
        assertEquals(recipient, task.recipientId());
        assertEquals(stage, task.minimumStage());
        assertEquals(180, task.durationMinutes());
    }

    @Test void everyResidentGivesOneNoTrustErrandThenOneTrustedErrandInThatOrder() {
        for (UUID giver : List.of(MARA, ELLIS, NICO)) {
            List<TownTaskDefinition> offers = TownTaskCatalogue.offers(giver);
            assertEquals(2, offers.size());
            assertEquals(Stage.STRANGER, offers.get(0).minimumStage());
            assertEquals(Stage.TRUSTED, offers.get(1).minimumStage());
        }
    }

    @Test void offersFollowTheAuthoredOrderPerGiver() {
        assertEquals(List.of("mara-material-list", "mara-restoration-plan"), TownTaskCatalogue.offers(MARA).stream().map(TownTaskDefinition::id).toList());
        assertEquals(List.of("ellis-neighborhood-note", "ellis-supplier-introduction"), TownTaskCatalogue.offers(ELLIS).stream().map(TownTaskDefinition::id).toList());
        assertEquals(List.of("nico-home-note", "nico-local-partnership"), TownTaskCatalogue.offers(NICO).stream().map(TownTaskDefinition::id).toList());
    }

    @Test void aResidentWhoIsNotInTheCatalogueOffersNothing() {
        assertEquals(List.of(), TownTaskCatalogue.offers(new UUID(9, 9)));
    }

    @Test void anUnknownErrandIdIsNotFound() {
        assertEquals(Optional.empty(), TownTaskCatalogue.find("no-such-errand"));
        assertEquals(Optional.empty(), TownTaskCatalogue.find("Mara-Material-List"));
        assertEquals(Optional.empty(), TownTaskCatalogue.find(""));
    }

    @Test void theOffersListIsImmutable() {
        assertThrows(UnsupportedOperationException.class, () -> TownTaskCatalogue.offers(MARA).clear());
    }

    private static ResidentCast cast(UUID... keep) {
        return new ResidentCast(ResidentCatalogue.betaCast().stream().filter(p -> List.of(keep).contains(p.id())).toList(), Map.of(),
                ResidentCast.Source.FILE);
    }

    @Test void anErrandIsHiddenWhenItsRecipientIsOutOfTheCast() {
        ResidentCast withoutEllis = cast(MARA, NICO);
        assertEquals(List.of("mara-restoration-plan"), TownTaskCatalogue.offers(MARA, withoutEllis).stream().map(TownTaskDefinition::id).toList());
        assertEquals(Optional.empty(), TownTaskCatalogue.find("mara-material-list", withoutEllis));
        assertTrue(TownTaskCatalogue.find("mara-restoration-plan", withoutEllis).isPresent());
    }

    @Test void anErrandIsHiddenWhenItsGiverIsOutOfTheCast() {
        ResidentCast withoutMara = cast(ELLIS, NICO);
        assertEquals(List.of(), TownTaskCatalogue.offers(MARA, withoutMara));
        assertEquals(Optional.empty(), TownTaskCatalogue.find("mara-material-list", withoutMara));
    }

    @Test void aCastOfOneOffersNoErrandsAtAll() {
        ResidentCast onlyMara = cast(MARA);
        for (UUID giver : List.of(MARA, ELLIS, NICO)) assertEquals(List.of(), TownTaskCatalogue.offers(giver, onlyMara));
    }

    @Test void theFullBuiltInCastOffersEverythingThroughBothEntryPoints() {
        ResidentCast all = ResidentCast.builtIn();
        for (UUID giver : List.of(MARA, ELLIS, NICO)) assertEquals(TownTaskCatalogue.offers(giver), TownTaskCatalogue.offers(giver, all));
    }

    @Test void aNameComesFromTheCastOrIsTheGenericFallback() {
        assertEquals("Mara Vale", TownTaskCatalogue.name(MARA));
        assertEquals("Ellis Ward", TownTaskCatalogue.name(ELLIS, ResidentCast.builtIn()));
        assertEquals("the named resident", TownTaskCatalogue.name(new UUID(9, 9)));
        assertEquals("the named resident", TownTaskCatalogue.name(ELLIS, cast(MARA)));
    }
}

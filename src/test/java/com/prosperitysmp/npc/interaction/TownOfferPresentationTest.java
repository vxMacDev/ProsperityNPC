package com.prosperitysmp.npc.interaction;

import com.prosperitysmp.npc.resident.*;
import com.prosperitysmp.npc.town.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TownOfferPresentationTest {
    private static final UUID PLAYER = new UUID(0, 70);
    private static final Instant NOW = Instant.parse("2026-09-29T15:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 29);
    private static final ResidentProfile MARA = ResidentCatalogue.betaCast().getFirst();

    private static RelationshipMemory.State stranger(ResidentProfile profile) {
        return RelationshipMemory.replay(profile.id(), PLAYER, List.of());
    }
    private static EncounterView base(ResidentProfile profile) {
        return EncounterView.of(profile, stranger(profile),
                ResidentDialogue.reply(profile, stranger(profile), ResidentDialogue.Topic.BUSINESS, 1000));
    }

    @Test void baselineWorkDoesNotRequireOwningPropertyOrTrustedHistory() {
        var offer = TownTaskCatalogue.offers(MARA.id()).getFirst();
        var view = TownOfferPresentation.view(base(MARA), MARA, stranger(MARA), Optional.empty(), List.of(), DAY,
                Set.of(offer.recipientId()));
        assertEquals(1, view.actions().size());
        assertEquals(ResidentAction.Kind.ACCEPT, view.actions().getFirst().kind());
        assertEquals(offer.id(), view.actions().getFirst().definitionId());
        assertTrue(view.line().contains("not cash or items"));
        assertTrue(view.line().contains("Trusted"));
    }

    @Test void unreachableRecipientIsNotPresentedAsAnAvailableOffer() {
        var view = TownOfferPresentation.view(base(MARA), MARA, stranger(MARA), Optional.empty(), List.of(), DAY, Set.of());
        assertTrue(view.actions().isEmpty());
        assertTrue(view.line().contains("no reachable errand"));
    }

    @Test void onlyTheActualRecipientGetsADeliveryButton() {
        var definition = TownTaskCatalogue.offers(MARA.id()).getFirst();
        var task = active(definition);
        var giverView = TownOfferPresentation.view(base(MARA), MARA, stranger(MARA), Optional.of(task), List.of(), DAY, Set.of());
        assertFalse(giverView.actions().stream().anyMatch(action -> action.kind() == ResidentAction.Kind.COMPLETE));
        var recipient = ResidentCatalogue.betaCast().stream().filter(profile -> profile.id().equals(definition.recipientId())).findFirst().orElseThrow();
        var recipientView = TownOfferPresentation.view(base(recipient), recipient, stranger(recipient), Optional.of(task), List.of(), DAY, Set.of());
        assertEquals(ResidentAction.Kind.COMPLETE, recipientView.actions().getFirst().kind());
        assertEquals(task.taskId(), recipientView.actions().getFirst().taskId());
    }

    @Test void cancellingDoesNotOfferADailyReroll() {
        var definition = TownTaskCatalogue.offers(MARA.id()).getFirst();
        var task = new TownTask(UUID.randomUUID(), PLAYER, definition, NOW, DAY, NOW.plusSeconds(10800),
                TownTask.Status.CANCELLED, NOW.plusSeconds(60), null);
        var view = TownOfferPresentation.view(base(MARA), MARA, stranger(MARA), Optional.empty(), List.of(task), DAY,
                Set.of(definition.recipientId()));
        assertTrue(view.actions().isEmpty());
        assertTrue(view.line().contains("taken an errand this service day"));
    }

    @Test void authoredCatalogueIsFiniteUniqueAndDoesNotPointToUnknownResidents() {
        Set<UUID> ids = ResidentCatalogue.betaCast().stream().map(ResidentProfile::id).collect(java.util.stream.Collectors.toSet());
        Set<String> definitions = new HashSet<>();
        for (var resident : ResidentCatalogue.betaCast()) for (var task : TownTaskCatalogue.offers(resident.id())) {
            assertTrue(definitions.add(task.id()));
            assertTrue(ids.contains(task.giverId()) && ids.contains(task.recipientId()));
        }
        assertEquals(6, definitions.size());
    }

    @Test void opportunitiesAndActionResultsFitThePrivateSpeechBudget() {
        for (var profile : ResidentCatalogue.betaCast()) {
            var reachable = ResidentCatalogue.betaCast().stream().map(ResidentProfile::id)
                    .collect(java.util.stream.Collectors.toSet());
            var view = TownOfferPresentation.view(base(profile), profile, stranger(profile), Optional.empty(), List.of(), DAY, reachable);
            assertTrue(ResidentSpeech.wrap(view.line()).size() <= 6, view.line());
            for (var code : TownTaskRepository.Code.values()) {
                String reply = TownOfferPresentation.replyLine(TownOfferPresentation.result(code), view.line());
                assertFalse(ResidentSpeech.wrap(reply).isEmpty());
                assertTrue(ResidentSpeech.wrap(reply).size() <= 6, reply);
            }
        }
    }

    private static TownTask active(TownTaskDefinition definition) {
        return new TownTask(UUID.randomUUID(), PLAYER, definition, NOW, DAY, NOW.plusSeconds(10800),
                TownTask.Status.ACTIVE, null, null);
    }
}

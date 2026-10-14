package com.prosperitysmp.npc.interaction;

import com.prosperitysmp.npc.resident.*;
import com.prosperitysmp.npc.training.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TrainingPresentationTest {
    @Test void offersAreRestrictedToTheActualTradeAndJobsMembership() {
        var mara = ResidentCatalogue.betaCast().getFirst();
        var memory = RelationshipMemory.replay(mara.id(), UUID.randomUUID(), List.of());
        var base = EncounterView.of(mara, memory, ResidentDialogue.reply(mara, memory, ResidentDialogue.Topic.BUSINESS, 1000));
        var view = TrainingPresentation.add(base, mara, Set.of("builder", "hunter"),
                new TrainingRuntime.View(TrainingRuntime.Status.NONE, Optional.empty()));
        assertEquals(1, view.actions().size());
        assertEquals(ResidentAction.Kind.TRAIN, view.actions().getFirst().kind());
        assertEquals("builder", view.actions().getFirst().definitionId());
        assertEquals(mara.id(), view.actions().getFirst().recipientId());
        var disabled = TrainingPresentation.add(base, mara, Set.of("builder"),
                new TrainingRuntime.View(TrainingRuntime.Status.DISABLED, Optional.empty()));
        assertTrue(disabled.actions().isEmpty());
    }

    @Test void reservedSlotKeepsTrainReachableWhenErrandsFillEverySlot() {
        var mara = ResidentCatalogue.betaCast().getFirst();
        var memory = RelationshipMemory.replay(mara.id(), UUID.randomUUID(), List.of());
        var base = EncounterView.of(mara, memory, ResidentDialogue.reply(mara, memory, ResidentDialogue.Topic.BUSINESS, 1000));
        var errands = new ArrayList<ResidentAction>();
        for (String id : List.of("one", "two", "three"))
            errands.add(new ResidentAction(ResidentAction.Kind.ACCEPT, id, null, mara.id(), "Errand " + id));
        var full = new EncounterView(base.name(), base.occupation(), base.familiarity(), base.line(), base.topics(), errands);
        var status = new TrainingRuntime.View(TrainingRuntime.Status.NONE, Optional.empty());
        var before = TrainingPresentation.add(full, mara, Set.of("builder"), status);
        assertTrue(before.actions().stream().noneMatch(a -> a.kind() == ResidentAction.Kind.TRAIN), "default behaviour unchanged");
        assertEquals(3, before.actions().size());
        var after = TrainingPresentation.add(full, mara, Set.of("builder"), status, true);
        assertEquals(3, after.actions().size());
        assertEquals(ResidentAction.Kind.TRAIN, after.actions().getLast().kind());
        assertEquals(List.of("one", "two"), after.actions().subList(0, 2).stream().map(ResidentAction::definitionId).toList());
        var unemployed = TrainingPresentation.add(full, mara, Set.of("hunter"), status, true);
        assertEquals(full.actions(), unemployed.actions(), "no errand is dropped when Train cannot be offered");
    }

    @Test void resultTextFitsPrivateSpeechAndNeverPromisesUnconfirmedBenefits() {
        for (var code : TrainingRepository.Code.values()) {
            String line = TrainingPresentation.result(TrainingRepository.Result.denied(code));
            assertFalse(ResidentSpeech.wrap(line).isEmpty());
            assertTrue(ResidentSpeech.wrap(line).size() <= 6, line);
            if (code != TrainingRepository.Code.GRANTED) assertFalse(line.startsWith("Practice agreed"));
        }
    }

    @Test void unresolvedRefreshCannotOfferAnotherTrainingSession() {
        var mara = ResidentCatalogue.betaCast().getFirst();
        var memory = RelationshipMemory.replay(mara.id(), UUID.randomUUID(), List.of());
        var base = EncounterView.of(mara, memory, ResidentDialogue.reply(mara, memory, ResidentDialogue.Topic.BUSINESS, 1000));
        for (var status : List.of(TrainingRuntime.Status.UNKNOWN, TrainingRuntime.Status.LOADING)) {
            var view = TrainingPresentation.add(base, mara, Set.of("builder"),
                    new TrainingRuntime.View(status, Optional.empty()));
            assertTrue(view.actions().isEmpty());
            assertTrue(view.line().contains("Checking your saved training"));
        }
    }
}

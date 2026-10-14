package com.prosperitysmp.npc.resident;

import org.junit.jupiter.api.Test;
import com.prosperitysmp.npc.interaction.EncounterView;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ResidentActivityTest {
    private final ResidentProfile person = ResidentCatalogue.betaCast().getFirst();
    private final UUID player = UUID.randomUUID();

    @Test void talkingDoesNotInventAWalkForWaitingOrArrivedResidents() {
        assertEquals(ResidentActivity.State.WAITING_ROUTE,ResidentActivity.observed(true,false,false,true,false));
        assertEquals(ResidentActivity.State.AT_STOP,ResidentActivity.observed(true,false,false,true,true));
        assertEquals(ResidentActivity.State.PAUSED,ResidentActivity.observed(true,true,true,true,false));
        assertEquals(ResidentActivity.State.WALKING,ResidentActivity.observed(true,true,true,false,false));
        assertEquals(ResidentActivity.State.PREVIEW,ResidentActivity.observed(false,false,false,true,false));
    }

    @Test void runtimeWaitingDoesNotClaimScheduleArrivalOrWork() {
        var state = RelationshipMemory.replay(person.id(), player, List.of());
        var observed = new ResidentActivity(ResidentActivity.State.WAITING_ROUTE, ResidentRoutine.Activity.WORK);
        var reply = ResidentDialogue.reply(person, state, ResidentDialogue.Topic.ROUTINE, 6000, 0);
        var view = EncounterView.of(person,state,reply).withActivity(ResidentDialogue.Topic.ROUTINE,observed);
        assertEquals("I'm staying here until my route is clear.", view.line());
        assertNull(reply.evidenceRef());
        assertEquals(0, state.reliability());
    }

    @Test void observationNeverReplacesVerifiedMemoryOrBusinessTerms() {
        var event = new RelationshipMemory.Event(person.id(), player, "repair:proof", RelationshipMemory.Kind.REPAIR_COMPLETED,
                Instant.parse("2026-09-06T12:00:00Z"), LocalDate.of(2026,9,6));
        var state = RelationshipMemory.replay(person.id(), player, List.of(event));
        var observed = new ResidentActivity(ResidentActivity.State.PAUSED, ResidentRoutine.Activity.ERRAND);
        for (var topic : List.of(ResidentDialogue.Topic.GREETING, ResidentDialogue.Topic.HOME, ResidentDialogue.Topic.BUSINESS)) {
            var view = EncounterView.of(person,state,ResidentDialogue.reply(person,state,topic,6000,1));
            assertEquals(view,view.withActivity(topic,observed));
        }
    }

    @Test void latestObservationReplacesOldMovementClaimWithoutChangingOtherPresentation() {
        var view = new EncounterView("Mara", "Restorer", "Acquaintance", "old line", List.of(ResidentDialogue.Topic.ROUTINE));
        var old = view.withActivity(ResidentDialogue.Topic.ROUTINE,
                new ResidentActivity(ResidentActivity.State.PAUSED,ResidentRoutine.Activity.WORK));
        var current = old.withActivity(ResidentDialogue.Topic.ROUTINE,
                new ResidentActivity(ResidentActivity.State.AT_STOP,ResidentRoutine.Activity.LEISURE));
        assertEquals("I've reached my leisure stop. I have a little time to talk.",current.line());
        assertEquals(view.name(),current.name());
        assertEquals(view.familiarity(),current.familiarity());
        assertEquals(view.topics(),current.topics());
    }

    @Test void moodSentenceSurvivesTheActivityOverride() {
        var view = new EncounterView("Mara", "Restorer", "Acquaintance", "old line", List.of(ResidentDialogue.Topic.ROUTINE))
                .withMood("I'm happy with my home.");
        assertEquals("old line I'm happy with my home.", view.line());
        var shown = view.withActivity(ResidentDialogue.Topic.ROUTINE,
                new ResidentActivity(ResidentActivity.State.AT_STOP,ResidentRoutine.Activity.LEISURE));
        assertEquals("I've reached my leisure stop. I have a little time to talk. I'm happy with my home.", shown.line());
        assertEquals(view.withActivity(ResidentDialogue.Topic.GREETING, null), view);
    }

    @Test void everyMovementStateIsBoundedPlainTextWithoutClaimingEconomicOutput() {
        for (var state : ResidentActivity.State.values()) for (var intent : ResidentRoutine.Activity.values()) {
            String line = new ResidentActivity(state,intent).line();
            assertTrue(line.length() < 130);
            assertFalse(line.contains("$") || line.contains("earned") || line.contains("own "));
            assertFalse(line.contains("\n"));
        }
        assertTrue(new ResidentActivity(ResidentActivity.State.PREVIEW, ResidentRoutine.Activity.HOME).line().contains("isn't connected"));
        assertTrue(new ResidentActivity(ResidentActivity.State.WALKING, ResidentRoutine.Activity.WORK).line().contains("on my way"));
        assertTrue(new ResidentActivity(ResidentActivity.State.AT_STOP, ResidentRoutine.Activity.LEISURE).line().contains("reached"));
        assertTrue(new ResidentActivity(ResidentActivity.State.PAUSED, ResidentRoutine.Activity.ERRAND).line().contains("paused"));
    }
}

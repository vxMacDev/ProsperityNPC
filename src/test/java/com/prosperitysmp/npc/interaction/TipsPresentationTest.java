package com.prosperitysmp.npc.interaction;

import com.prosperitysmp.npc.resident.*;
import com.prosperitysmp.npc.town.*;
import com.prosperitysmp.npc.training.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TipsPresentationTest {
    private static final Instant NOW = Instant.parse("2026-10-13T12:00:00Z");
    private static final TrainingRuntime.View NONE = new TrainingRuntime.View(TrainingRuntime.Status.NONE, Optional.empty());

    @Test void selectionIsDeterministicPerPlayerAndDayAndRotatesDaily() {
        var tips = List.of("a", "b", "c", "d");
        var player = UUID.randomUUID();
        var day = LocalDate.of(2026, 10, 13);
        assertEquals(TipsPresentation.tip(tips, player, day), TipsPresentation.tip(tips, player, day));
        var seen = new HashSet<String>();
        for (int i = 0; i < 4; i++) seen.add(TipsPresentation.tip(tips, player, day.plusDays(i)).orElseThrow());
        assertEquals(4, seen.size());
        assertNotEquals(TipsPresentation.tip(tips, player, day), TipsPresentation.tip(tips, player, day.plusDays(1)));
        assertTrue(tips.contains(TipsPresentation.tip(tips, new UUID(Long.MIN_VALUE, -1L), LocalDate.of(1969, 1, 1)).orElseThrow()));
    }

    @Test void emptyOrBlankListHidesTheTopic() {
        assertTrue(TipsPresentation.clean(null).isEmpty());
        assertTrue(TipsPresentation.clean(List.of()).isEmpty());
        assertTrue(TipsPresentation.clean(Arrays.asList("  ", 5, null)).isEmpty());
        assertTrue(TipsPresentation.tip(List.of(), UUID.randomUUID(), LocalDate.now()).isEmpty());
        var mara = ResidentCatalogue.betaCast().getFirst();
        var memory = RelationshipMemory.replay(mara.id(), UUID.randomUUID(), List.of());
        var base = EncounterView.of(mara, memory, ResidentDialogue.reply(mara, memory, ResidentDialogue.Topic.GREETING, 1000));
        assertSame(base, TipsPresentation.withTopic(base, false));
        assertFalse(base.topics().contains(ResidentDialogue.Topic.TIPS));
        var shown = TipsPresentation.withTopic(base, true);
        assertTrue(shown.topics().contains(ResidentDialogue.Topic.TIPS));
        assertEquals(1, shown.topics().stream().filter(ResidentDialogue.Topic.TIPS::equals).count());
        assertSame(shown, TipsPresentation.withTopic(shown, true));
        assertEquals("Any tips?", EncounterView.topicLabel(ResidentDialogue.Topic.TIPS));
    }

    @Test void cleanBoundsAndNormalisesCopy() {
        var out = TipsPresentation.clean(List.of("  Vote — daily  ", "x".repeat(500)));
        assertEquals("Vote - daily", out.getFirst());
        assertEquals(TipsPresentation.MAX_LENGTH, out.get(1).length());
        var many = new ArrayList<String>();
        for (int i = 0; i < 100; i++) many.add("tip " + i);
        assertEquals(TipsPresentation.MAX_TIPS, TipsPresentation.clean(many).size());
    }

    @Test void statusReportsErrandTrainingOrNothing() {
        assertEquals("Nothing on your plate right now.", TipsPresentation.status(Optional.empty(), NONE, NOW));
        assertEquals(TipsPresentation.IDLE, TipsPresentation.status(Optional.empty(), null, NOW));
        var def = TownTaskCatalogue.find("mara-material-list").orElseThrow();
        var task = new TownTask(UUID.randomUUID(), UUID.randomUUID(), def, NOW, LocalDate.of(2026, 10, 13),
                NOW.plusSeconds(def.durationMinutes() * 60L), TownTask.Status.ACTIVE, null, null);
        String errand = TipsPresentation.status(Optional.of(task), NONE, NOW);
        assertTrue(errand.contains("Ellis"), errand);
        var grant = new TrainingGrant(UUID.randomUUID(), task.playerId(), UUID.randomUUID(), "builder", UUID.randomUUID(),
                NOW.minusSeconds(599), LocalDate.of(2026, 10, 13), NOW.plusSeconds(1201), 500, 1000);
        var active = new TrainingRuntime.View(TrainingRuntime.Status.ACTIVE, Optional.of(grant));
        String both = TipsPresentation.status(Optional.of(task), active, NOW);
        assertTrue(both.contains("builder training has 21 min left."), both);
        assertTrue(both.contains("Ellis"));
        assertEquals("Your builder training has 21 min left.", TipsPresentation.status(Optional.empty(), active, NOW));
        assertEquals(TipsPresentation.IDLE, TipsPresentation.status(Optional.empty(), active, NOW.plusSeconds(5000)));
        assertEquals(TipsPresentation.IDLE, TipsPresentation.status(Optional.of(task), NONE, NOW.plusSeconds(def.durationMinutes() * 60L)));
    }

    @Test void viewCarriesTipAndStatusInsideSpeechLimitWithNoActions() {
        var mara = ResidentCatalogue.betaCast().getFirst();
        var memory = RelationshipMemory.replay(mara.id(), UUID.randomUUID(), List.of());
        var base = EncounterView.of(mara, memory, ResidentDialogue.reply(mara, memory, ResidentDialogue.Topic.TIPS, 1000));
        var view = TipsPresentation.view(base, List.of("Vote every day."), UUID.randomUUID(), LocalDate.of(2026, 10, 13),
                Optional.empty(), NONE, NOW);
        assertEquals("Tip: Vote every day. Nothing on your plate right now.", view.line());
        assertTrue(view.actions().isEmpty());
        assertFalse(view.line().contains("—"));
        var longTip = TipsPresentation.clean(List.of("word ".repeat(60)));
        var capped = TipsPresentation.view(base, longTip, UUID.randomUUID(), LocalDate.of(2026, 10, 13), Optional.empty(), NONE, NOW);
        assertTrue(ResidentSpeech.wrap(capped.line()).size() <= 6);
        var def = TownTaskCatalogue.find("mara-material-list").orElseThrow();
        var task = new TownTask(UUID.randomUUID(), UUID.randomUUID(), def, NOW, LocalDate.of(2026, 10, 13),
                NOW.plusSeconds(def.durationMinutes() * 60L), TownTask.Status.ACTIVE, null, null);
        var grant = new TrainingGrant(UUID.randomUUID(), task.playerId(), UUID.randomUUID(), "builder", UUID.randomUUID(),
                NOW.minusSeconds(599), LocalDate.of(2026, 10, 13), NOW.plusSeconds(1201), 500, 1000);
        var active = new TrainingRuntime.View(TrainingRuntime.Status.ACTIVE, Optional.of(grant));
        var busy = TipsPresentation.view(base, longTip, task.playerId(), LocalDate.of(2026, 10, 13), Optional.of(task), active, NOW);
        String status = TipsPresentation.status(Optional.of(task), active, NOW);
        assertTrue(busy.line().contains(status), busy.line());
        assertTrue(ResidentSpeech.wrap(busy.line()).size() <= 6);
        assertTrue(status.contains("ask about Business for directions"));
    }

    @Test void unresolvedTrainingNeverReadsAsIdle() {
        for (var st : List.of(TrainingRuntime.Status.UNKNOWN, TrainingRuntime.Status.LOADING, TrainingRuntime.Status.UNCERTAIN)) {
            var view = new TrainingRuntime.View(st, Optional.empty());
            String alone = TipsPresentation.status(Optional.empty(), view, NOW);
            assertNotEquals(TipsPresentation.IDLE, alone, st.name());
            assertEquals(TipsPresentation.TRAINING_UNCHECKED, alone);
            var def = TownTaskCatalogue.find("mara-material-list").orElseThrow();
            var task = new TownTask(UUID.randomUUID(), UUID.randomUUID(), def, NOW, LocalDate.of(2026, 10, 13),
                    NOW.plusSeconds(def.durationMinutes() * 60L), TownTask.Status.ACTIVE, null, null);
            String both = TipsPresentation.status(Optional.of(task), view, NOW);
            assertTrue(both.contains("Ellis") && both.contains(TipsPresentation.TRAINING_UNCHECKED), both);
        }
        assertEquals(TipsPresentation.IDLE, TipsPresentation.status(Optional.empty(),
                new TrainingRuntime.View(TrainingRuntime.Status.NONE, Optional.empty()), NOW));
    }
}

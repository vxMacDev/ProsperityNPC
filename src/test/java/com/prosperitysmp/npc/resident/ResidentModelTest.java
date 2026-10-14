package com.prosperitysmp.npc.resident;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ResidentModelTest {
    private final ResidentProfile mara = ResidentCatalogue.betaCast().getFirst();
    private final UUID player = UUID.fromString("00000000-0000-0000-0009-000000000042");

    @Test void profileIsStableAcrossIndependentCalls() {
        assertEquals(ResidentCatalogue.draft(player, null), ResidentCatalogue.draft(player, null));
        assertEquals(ResidentCatalogue.betaCast(), ResidentCatalogue.betaCast());
        assertEquals("Mara Vale", mara.name());
    }

    @Test void existingIdentityNameIsNeverOverwrittenByGeneratedSurname() {
        assertEquals("Resident Forty", ResidentCatalogue.draft(player, "Resident Forty").name());
    }

    @Test void socioeconomicBackgroundDoesNotSelectTemperament() {
        var observed = new java.util.EnumMap<ResidentProfile.Means, HashSet<ResidentProfile.Temperament>>(ResidentProfile.Means.class);
        for (int i = 0; i < 600; i++) {
            var profile = ResidentCatalogue.draft(new UUID(1, i), null);
            observed.computeIfAbsent(profile.means(), ignored -> new HashSet<>()).add(profile.temperament());
        }
        for (var means : ResidentProfile.Means.values()) {
            assertEquals(ResidentProfile.Temperament.values().length, observed.get(means).size());
        }
    }

    @Test void authoredResidentsHaveDifferentIdentitiesAndWardrobes() {
        assertEquals(3, ResidentCatalogue.betaCast().stream().map(ResidentProfile::id).distinct().count());
        assertEquals(3, ResidentCatalogue.betaCast().stream().map(ResidentProfile::wardrobe).distinct().count());
        assertNotEquals(mara.wardrobe().work(), mara.wardrobe().leisure());
    }

    @Test void rejectsControlCodesOrExecutableLookingAssetPaths() {
        assertThrows(IllegalArgumentException.class, () -> ResidentCatalogue.draft(player, "Hello\nAdmin"));
        assertThrows(IllegalArgumentException.class, () -> ResidentCatalogue.draft(player, "\u00a7cAdmin"));
        assertThrows(IllegalArgumentException.class, () -> new ResidentProfile.Wardrobe("../secret", "work", "leisure"));
    }

    @Test void routineHasExactBoundariesAndWrapsWithoutAgingSimulation() {
        assertEquals(ResidentRoutine.Activity.HOME, ResidentRoutine.at(mara, 1999).activity());
        assertEquals(ResidentRoutine.Activity.WORK, ResidentRoutine.at(mara, 2000).activity());
        assertEquals(ResidentRoutine.Activity.ERRAND, ResidentRoutine.at(mara, 9000).activity());
        assertEquals(ResidentRoutine.Activity.LEISURE, ResidentRoutine.at(mara, 11000).activity());
        assertEquals(ResidentRoutine.Activity.HOME, ResidentRoutine.at(mara, 16000).activity());
        assertEquals(ResidentRoutine.at(mara, 5000), ResidentRoutine.at(mara, 29000));
        assertEquals(ResidentRoutine.at(mara, 23999), ResidentRoutine.at(mara, -1));
        assertNotNull(ResidentRoutine.at(mara, Long.MIN_VALUE));
    }

    @Test void routineNeverChangesProfileOrBalance() {
        var original = mara;
        for (int i = 0; i < 24000; i += 17) assertNotNull(ResidentRoutine.at(mara, i).outfitKey());
        assertEquals(original, ResidentCatalogue.betaCast().getFirst());
    }

    @Test void noInventedRepairThanksWithoutMemory() {
        var state = RelationshipMemory.replay(mara.id(), player, List.of());
        var reply = ResidentDialogue.reply(mara, state, ResidentDialogue.Topic.GREETING, 6000);
        assertNull(reply.evidenceRef());
        assertTrue(reply.line().contains("Mara Vale"));
        assertFalse(reply.line().contains("repair"));
    }

    @Test void repairThanksAreTiedToExactSource() {
        var event = new RelationshipMemory.Event(mara.id(), player, "incident:42:resolved",
                RelationshipMemory.Kind.REPAIR_COMPLETED, Instant.parse("2026-09-06T15:00:00Z"), LocalDate.of(2026, 9, 6));
        var reply = ResidentDialogue.reply(mara, RelationshipMemory.replay(mara.id(), player, List.of(event)),
                ResidentDialogue.Topic.GREETING, 6000);
        assertEquals(event.sourceRef(), reply.evidenceRef());
        assertTrue(reply.line().contains("repair"));
    }

    @Test void laterBrokenObligationOverridesEarlierPraise() {
        var repair = new RelationshipMemory.Event(mara.id(), player, "incident:42:resolved",
                RelationshipMemory.Kind.REPAIR_COMPLETED, Instant.parse("2026-09-06T15:00:00Z"), LocalDate.of(2026, 9, 6));
        var broken = new RelationshipMemory.Event(mara.id(), player, "contract:12:failed",
                RelationshipMemory.Kind.OBLIGATION_BROKEN, Instant.parse("2026-09-06T16:00:00Z"), LocalDate.of(2026, 9, 6));
        var reply = ResidentDialogue.reply(mara, RelationshipMemory.replay(mara.id(), player, List.of(broken, repair)),
                ResidentDialogue.Topic.GREETING, 6000);
        assertEquals(broken.sourceRef(), reply.evidenceRef());
        assertFalse(reply.line().contains("Thanks"));
    }

    @Test void dialogueRejectsAnotherResidentsMemory() {
        var state = RelationshipMemory.replay(UUID.randomUUID(), player, List.of());
        assertThrows(IllegalArgumentException.class,
                () -> ResidentDialogue.reply(mara, state, ResidentDialogue.Topic.GREETING, 0));
    }

    @Test void allAuthoredTopicsAreBoundedAndDoNotPromiseFakeDeals() {
        for (var person : ResidentCatalogue.betaCast()) {
            var state = RelationshipMemory.replay(person.id(), player, List.of());
            for (var topic : ResidentDialogue.Topic.values()) {
                var reply = ResidentDialogue.reply(person, state, topic, 6000);
                assertTrue(reply.line().length() <= 180);
                assertEquals(List.of(ResidentDialogue.Topic.ROUTINE, ResidentDialogue.Topic.HOME, ResidentDialogue.Topic.HISTORY,
                        ResidentDialogue.Topic.BUSINESS, ResidentDialogue.Topic.COMMERCE, ResidentDialogue.Topic.ABOUT), reply.choices());
                assertFalse(reply.line().contains("$"));
            }
        }
    }
}

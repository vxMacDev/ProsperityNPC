package com.prosperitysmp.npc.resident;

import com.prosperitysmp.npc.resident.ResidentProfile.Occupation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Authoring defaults are versioned and deterministic: the same ID must always be drafted as the same person. */
class ResidentCatalogueTest {
    @Test void theVersionIsOne() {
        assertEquals(1, ResidentCatalogue.VERSION);
    }

    @ParameterizedTest
    @CsvSource({
            "mara-vale,f23586b0-11ff-320e-874e-3bb29065d402",
            "ellis-ward,739e2063-a802-3e5a-8dc1-a9c03ed761a8",
            "nico-reed,716af3b1-66c6-3d62-b6fe-601e2b013ad9"})
    void anAuthoredKeyMapsToItsPermanentId(String key, String id) {
        assertEquals(UUID.fromString(id), ResidentCatalogue.idFor(key));
    }

    @Test void anotherKeyOrAnotherCaseGivesAnotherId() {
        assertNotEquals(ResidentCatalogue.idFor("someone"), ResidentCatalogue.idFor("someone-else"));
        assertNotEquals(ResidentCatalogue.idFor("Mara-Vale"), ResidentCatalogue.idFor("mara-vale"));
    }

    @Test void theBetaCastIsMaraEllisAndNicoInThatOrder() {
        List<ResidentProfile> cast = ResidentCatalogue.betaCast();
        assertEquals(List.of("Mara Vale", "Ellis Ward", "Nico Reed"), cast.stream().map(ResidentProfile::name).toList());
        assertEquals(List.of(ResidentCatalogue.idFor("mara-vale"), ResidentCatalogue.idFor("ellis-ward"),
                ResidentCatalogue.idFor("nico-reed")), cast.stream().map(ResidentProfile::id).toList());
    }

    @Test void maraValeIsAWarmOlderRestorer() {
        ResidentProfile mara = ResidentCatalogue.betaCast().get(0);
        assertEquals("Vale", mara.household());
        assertEquals(2, mara.generation());
        assertEquals(Occupation.RESTORER, mara.occupation());
        assertEquals(ResidentProfile.Means.STEADY, mara.means());
        assertEquals(ResidentProfile.LifeStage.OLDER_ADULT, mara.lifeStage());
        assertEquals(ResidentProfile.Temperament.WARM, mara.temperament());
        assertEquals(ResidentProfile.Preference.WELL_KEPT_HOME, mara.preference());
    }

    @Test void ellisWardIsADirectEstablishedShopkeeper() {
        ResidentProfile ellis = ResidentCatalogue.betaCast().get(1);
        assertEquals("Ward", ellis.household());
        assertEquals(1, ellis.generation());
        assertEquals(Occupation.SHOPKEEPER, ellis.occupation());
        assertEquals(ResidentProfile.Means.ESTABLISHED, ellis.means());
        assertEquals(ResidentProfile.LifeStage.ADULT, ellis.lifeStage());
        assertEquals(ResidentProfile.Temperament.DIRECT, ellis.temperament());
        assertEquals(ResidentProfile.Preference.CLEAR_TERMS, ellis.preference());
    }

    @Test void nicoReedIsAnInquisitiveYoungCourier() {
        ResidentProfile nico = ResidentCatalogue.betaCast().get(2);
        assertEquals("Reed", nico.household());
        assertEquals(1, nico.generation());
        assertEquals(Occupation.COURIER, nico.occupation());
        assertEquals(ResidentProfile.Means.STARTING_OUT, nico.means());
        assertEquals(ResidentProfile.LifeStage.YOUNG_ADULT, nico.lifeStage());
        assertEquals(ResidentProfile.Temperament.INQUISITIVE, nico.temperament());
        assertEquals(ResidentProfile.Preference.NEIGHBORHOOD_LIFE, nico.preference());
    }

    @Test void theBetaCastWardrobeKeysFollowTheAuthoredKey() {
        ResidentProfile.Wardrobe wardrobe = ResidentCatalogue.betaCast().get(0).wardrobe();
        assertEquals("resident/v1/mara-vale/everyday", wardrobe.everyday());
        assertEquals("resident/v1/mara-vale/work", wardrobe.work());
        assertEquals("resident/v1/mara-vale/leisure", wardrobe.leisure());
    }

    @Test void theBetaCastIsImmutable() {
        assertThrows(UnsupportedOperationException.class, () -> ResidentCatalogue.betaCast().add(ResidentCatalogue.betaCast().get(0)));
    }

    @Test void authoredUsesTheKeyForTheIdAndTheWardrobe() {
        ResidentProfile p = ResidentCatalogue.authored("ada-lane", "Ada Lane", "Lane", 3, Occupation.COOK,
                ResidentProfile.Means.STEADY, ResidentProfile.LifeStage.ADULT, ResidentProfile.Temperament.RESERVED,
                ResidentProfile.Preference.CLEAR_TERMS);
        assertEquals(ResidentCatalogue.idFor("ada-lane"), p.id());
        assertEquals("Ada Lane", p.name());
        assertEquals("Lane", p.household());
        assertEquals(3, p.generation());
        assertEquals("resident/v1/ada-lane/work", p.wardrobe().work());
    }

    @Test void generatedHouseholdsAreTheEightSurnamesInOrder() {
        assertEquals(List.of("Vale", "Ward", "Reed", "Ashby", "Bell", "Hale", "Mercer", "Linden"), ResidentCatalogue.generatedHouseholds());
    }

    @Test void theBetaCastSurnamesAreAmongTheGeneratedHouseholds() {
        for (ResidentProfile p : ResidentCatalogue.betaCast())
            assertTrue(ResidentCatalogue.generatedHouseholds().contains(p.household()), p.household());
    }

    private static UUID id(long n) { return new UUID(0, n); }

    @Test void aDraftForOneIdIsAGardenerFromTheWardHousehold() {
        ResidentProfile p = ResidentCatalogue.draft(id(1), null);
        assertEquals(id(1), p.id());
        assertEquals("Leon Ward", p.name());
        assertEquals("Ward", p.household());
        assertEquals(2, p.generation());
        assertEquals(Occupation.GARDENER, p.occupation());
        assertEquals(ResidentProfile.Means.ESTABLISHED, p.means());
        assertEquals(ResidentProfile.LifeStage.YOUNG_ADULT, p.lifeStage());
        assertEquals(ResidentProfile.Temperament.INQUISITIVE, p.temperament());
        assertEquals(ResidentProfile.Preference.WELL_KEPT_HOME, p.preference());
    }

    @Test void aDraftForIdTwoIsACarpenterFromTheValeHousehold() {
        ResidentProfile p = ResidentCatalogue.draft(id(2), null);
        assertEquals("Iris Vale", p.name());
        assertEquals("Vale", p.household());
        assertEquals(2, p.generation());
        assertEquals(Occupation.CARPENTER, p.occupation());
        assertEquals(ResidentProfile.Means.ESTABLISHED, p.means());
        assertEquals(ResidentProfile.LifeStage.YOUNG_ADULT, p.lifeStage());
        assertEquals(ResidentProfile.Temperament.INQUISITIVE, p.temperament());
        assertEquals(ResidentProfile.Preference.QUIET_ROUTINE, p.preference());
    }

    @Test void aDraftForIdThreeIsAReservedCourier() {
        ResidentProfile p = ResidentCatalogue.draft(id(3), null);
        assertEquals("Iris Mercer", p.name());
        assertEquals(Occupation.COURIER, p.occupation());
        assertEquals(ResidentProfile.Temperament.RESERVED, p.temperament());
        assertEquals(ResidentProfile.Preference.NEIGHBORHOOD_LIFE, p.preference());
    }

    @Test void aDraftForARealisticIdMatchesItsGoldenValues() {
        ResidentProfile p = ResidentCatalogue.draft(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"), null);
        assertEquals("Robin Ashby", p.name());
        assertEquals("Ashby", p.household());
        assertEquals(3, p.generation());
        assertEquals(Occupation.SHOPKEEPER, p.occupation());
        assertEquals(ResidentProfile.Means.ESTABLISHED, p.means());
        assertEquals(ResidentProfile.LifeStage.YOUNG_ADULT, p.lifeStage());
        assertEquals(ResidentProfile.Temperament.INQUISITIVE, p.temperament());
        assertEquals(ResidentProfile.Preference.CLEAR_TERMS, p.preference());
    }

    @Test void anExistingNameIsKeptButTheRestIsStillDrawnFromTheId() {
        ResidentProfile fresh = ResidentCatalogue.draft(id(1), null);
        ResidentProfile kept = ResidentCatalogue.draft(id(1), "Old Tenant Name");
        assertEquals("Old Tenant Name", kept.name());
        assertEquals(fresh.household(), kept.household());
        assertEquals(fresh.occupation(), kept.occupation());
        assertEquals(fresh.generation(), kept.generation());
        assertEquals(fresh.wardrobe(), kept.wardrobe());
    }

    @Test void aDraftWardrobeIsNamedAfterTheOccupationNotTheName() {
        ResidentProfile p = ResidentCatalogue.draft(id(1), "Anything");
        assertEquals("resident/v1/gardener/everyday", p.wardrobe().everyday());
        assertEquals("resident/v1/gardener/work", p.wardrobe().work());
        assertEquals("resident/v1/gardener/leisure", p.wardrobe().leisure());
    }

    @Test void aGeneratedNameIsAGivenNameAndTheHouseholdSurname() {
        for (long n = 1; n <= 200; n++) {
            ResidentProfile p = ResidentCatalogue.draft(id(n), null);
            assertTrue(p.name().endsWith(" " + p.household()), p.name());
            assertEquals(2, p.name().split(" ").length, p.name());
        }
    }

    @Test void draftsSpreadAcrossEveryOccupationMeansStageAndTemperament() {
        Set<Occupation> jobs = EnumSet.noneOf(Occupation.class);
        Set<ResidentProfile.Means> means = EnumSet.noneOf(ResidentProfile.Means.class);
        Set<ResidentProfile.LifeStage> stages = EnumSet.noneOf(ResidentProfile.LifeStage.class);
        Set<ResidentProfile.Temperament> temperaments = EnumSet.noneOf(ResidentProfile.Temperament.class);
        Set<ResidentProfile.Preference> preferences = EnumSet.noneOf(ResidentProfile.Preference.class);
        Set<Integer> generations = new HashSet<>();
        for (long n = 1; n <= 500; n++) {
            ResidentProfile p = ResidentCatalogue.draft(id(n), null);
            jobs.add(p.occupation());
            means.add(p.means());
            stages.add(p.lifeStage());
            temperaments.add(p.temperament());
            preferences.add(p.preference());
            generations.add(p.generation());
        }
        assertEquals(EnumSet.allOf(Occupation.class), jobs);
        assertEquals(EnumSet.allOf(ResidentProfile.Means.class), means);
        assertEquals(EnumSet.allOf(ResidentProfile.LifeStage.class), stages);
        assertEquals(EnumSet.allOf(ResidentProfile.Temperament.class), temperaments);
        assertEquals(Set.of(1, 2, 3), generations);
        assertEquals(EnumSet.allOf(ResidentProfile.Preference.class), preferences);
    }

    @Test void everyDraftedEverydayWardrobeKeyNamesItsOccupation() {
        for (long n = 1; n <= 200; n++) {
            ResidentProfile p = ResidentCatalogue.draft(id(n), null);
            String job = p.occupation().name().toLowerCase(Locale.ROOT);
            assertEquals("resident/v1/" + job + "/everyday", p.wardrobe().everyday());
        }
    }

    @Test void differentIdsAreNotAllDraftedAsTheSamePerson() {
        Set<String> names = new HashSet<>();
        for (long n = 1; n <= 100; n++) names.add(ResidentCatalogue.draft(id(n), null).name());
        assertTrue(names.size() > 20, "distinct names: " + names.size());
    }
}

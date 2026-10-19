package com.prosperitysmp.npc.resident;

import com.prosperitysmp.npc.resident.ResidentProfile.Wardrobe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The persistent character definition: which names, households, generations and outfit keys are storable. */
class ResidentProfileTest {
    private static final UUID ID = new UUID(0, 7);
    private static final Wardrobe WARDROBE = new Wardrobe("resident/v1/x/everyday", "resident/v1/x/work", "resident/v1/x/leisure");

    private static ResidentProfile profile(String name, String household, int generation) {
        return new ResidentProfile(ID, name, household, generation, ResidentProfile.Occupation.COOK,
                ResidentProfile.Means.STEADY, ResidentProfile.LifeStage.ADULT, ResidentProfile.Temperament.WARM,
                ResidentProfile.Preference.QUIET_ROUTINE, WARDROBE);
    }

    @Test void aProfileKeepsEveryField() {
        ResidentProfile p = profile("Mara Vale", "Vale", 2);
        assertEquals(ID, p.id());
        assertEquals("Mara Vale", p.name());
        assertEquals("Vale", p.household());
        assertEquals(2, p.generation());
        assertEquals(ResidentProfile.Occupation.COOK, p.occupation());
        assertEquals(ResidentProfile.Means.STEADY, p.means());
        assertEquals(ResidentProfile.LifeStage.ADULT, p.lifeStage());
        assertEquals(ResidentProfile.Temperament.WARM, p.temperament());
        assertEquals(ResidentProfile.Preference.QUIET_ROUTINE, p.preference());
        assertSame(WARDROBE, p.wardrobe());
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 4, 8})
    void aGenerationFromOneToEightIsAllowed(int generation) {
        assertEquals(generation, profile("A", "B", generation).generation());
    }

    @ParameterizedTest @ValueSource(ints = {0, -1, 9, 100, Integer.MAX_VALUE, Integer.MIN_VALUE})
    void aGenerationOutsideOneToEightIsRefused(int generation) {
        assertThrows(IllegalArgumentException.class, () -> profile("A", "B", generation));
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", "   ", "\t", "\n"})
    void aBlankNameOrHouseholdIsRefused(String blank) {
        assertThrows(IllegalArgumentException.class, () -> profile(blank, "Vale", 1));
        assertThrows(IllegalArgumentException.class, () -> profile("Mara Vale", blank, 1));
    }

    @Test void aMissingNameOrHouseholdIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> profile(null, "Vale", 1));
        assertThrows(IllegalArgumentException.class, () -> profile("Mara Vale", null, 1));
    }

    @Test void aNameOfSixtyFourCharactersIsAllowedAndSixtyFiveIsNot() {
        assertEquals(64, profile("n".repeat(64), "Vale", 1).name().length());
        assertThrows(IllegalArgumentException.class, () -> profile("n".repeat(65), "Vale", 1));
        assertEquals(64, profile("Mara Vale", "h".repeat(64), 1).household().length());
        assertThrows(IllegalArgumentException.class, () -> profile("Mara Vale", "h".repeat(65), 1));
    }

    @ParameterizedTest @ValueSource(strings = {"Mara\nVale", "Mara\tVale", "Mara\rVale", "Mara\u0000Vale", "Mara\u007fVale", "Mara\u0085Vale"})
    void aNameOrHouseholdWithAControlCharacterIsRefused(String text) {
        assertThrows(IllegalArgumentException.class, () -> profile(text, "Vale", 1));
        assertThrows(IllegalArgumentException.class, () -> profile("Mara Vale", text, 1));
    }

    @ParameterizedTest @ValueSource(strings = {"\u00a7cMara", "Mara\u00a7", "\u00a7"})
    void aSectionSignInANameOrHouseholdIsRefused(String text) {
        assertThrows(IllegalArgumentException.class, () -> profile(text, "Vale", 1));
        assertThrows(IllegalArgumentException.class, () -> profile("Mara Vale", text, 1));
    }

    @ParameterizedTest @ValueSource(strings = {"Mar\u00e1 O'Neil", "Anne-Marie", "Dr. Vale", "Ana & Co", "Mara 2nd", "\u00c9tienne"})
    void ordinaryPunctuationAndLettersInANameAreAllowed(String name) {
        assertEquals(name, profile(name, "Vale", 1).name());
    }

    @Test void aProfileNeedsItsIdAndEveryEnumValue() {
        assertThrows(NullPointerException.class, () -> new ResidentProfile(null, "A", "B", 1, ResidentProfile.Occupation.COOK,
                ResidentProfile.Means.STEADY, ResidentProfile.LifeStage.ADULT, ResidentProfile.Temperament.WARM,
                ResidentProfile.Preference.QUIET_ROUTINE, WARDROBE));
        assertThrows(NullPointerException.class, () -> new ResidentProfile(ID, "A", "B", 1, null,
                ResidentProfile.Means.STEADY, ResidentProfile.LifeStage.ADULT, ResidentProfile.Temperament.WARM,
                ResidentProfile.Preference.QUIET_ROUTINE, WARDROBE));
        assertThrows(NullPointerException.class, () -> new ResidentProfile(ID, "A", "B", 1, ResidentProfile.Occupation.COOK,
                null, ResidentProfile.LifeStage.ADULT, ResidentProfile.Temperament.WARM,
                ResidentProfile.Preference.QUIET_ROUTINE, WARDROBE));
        assertThrows(NullPointerException.class, () -> new ResidentProfile(ID, "A", "B", 1, ResidentProfile.Occupation.COOK,
                ResidentProfile.Means.STEADY, null, ResidentProfile.Temperament.WARM,
                ResidentProfile.Preference.QUIET_ROUTINE, WARDROBE));
        assertThrows(NullPointerException.class, () -> new ResidentProfile(ID, "A", "B", 1, ResidentProfile.Occupation.COOK,
                ResidentProfile.Means.STEADY, ResidentProfile.LifeStage.ADULT, null,
                ResidentProfile.Preference.QUIET_ROUTINE, WARDROBE));
        assertThrows(NullPointerException.class, () -> new ResidentProfile(ID, "A", "B", 1, ResidentProfile.Occupation.COOK,
                ResidentProfile.Means.STEADY, ResidentProfile.LifeStage.ADULT, ResidentProfile.Temperament.WARM,
                null, WARDROBE));
        assertThrows(NullPointerException.class, () -> new ResidentProfile(ID, "A", "B", 1, ResidentProfile.Occupation.COOK,
                ResidentProfile.Means.STEADY, ResidentProfile.LifeStage.ADULT, ResidentProfile.Temperament.WARM,
                ResidentProfile.Preference.QUIET_ROUTINE, null));
    }

    @Test void theEnumsListTheirValuesInTheDocumentedOrder() {
        assertEquals("[RESTORER, SHOPKEEPER, GARDENER, COURIER, COOK, CARPENTER]",
                java.util.Arrays.toString(ResidentProfile.Occupation.values()));
        assertEquals("[STARTING_OUT, STEADY, ESTABLISHED]", java.util.Arrays.toString(ResidentProfile.Means.values()));
        assertEquals("[YOUNG_ADULT, ADULT, OLDER_ADULT]", java.util.Arrays.toString(ResidentProfile.LifeStage.values()));
        assertEquals("[RESERVED, WARM, DIRECT, INQUISITIVE]", java.util.Arrays.toString(ResidentProfile.Temperament.values()));
        assertEquals("[WELL_KEPT_HOME, CLEAR_TERMS, QUIET_ROUTINE, NEIGHBORHOOD_LIFE]",
                java.util.Arrays.toString(ResidentProfile.Preference.values()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "0", "resident/v1/mara/everyday", "a_b-c/d", "9lives", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
    void aWardrobeKeyIsLowerCaseDigitsSlashUnderscoreAndDash(String key) {
        Wardrobe w = new Wardrobe(key, key, key);
        assertEquals(key, w.everyday());
        assertEquals(key, w.work());
        assertEquals(key, w.leisure());
    }

    @ParameterizedTest @ValueSource(strings = {"", "A", "Upper/case", "/leading", "_leading", "-leading", "has space", "dot.dot",
            "tab\tx", "new\nline", "back\\slash", "colon:x", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", "trailing\n"})
    void aWardrobeKeyOutsideTheGrammarIsRefusedInEverySlot(String key) {
        assertThrows(IllegalArgumentException.class, () -> new Wardrobe(key, "ok", "ok"));
        assertThrows(IllegalArgumentException.class, () -> new Wardrobe("ok", key, "ok"));
        assertThrows(IllegalArgumentException.class, () -> new Wardrobe("ok", "ok", key));
    }

    @Test void aMissingWardrobeKeyIsRefusedInEverySlot() {
        assertThrows(IllegalArgumentException.class, () -> new Wardrobe(null, "ok", "ok"));
        assertThrows(IllegalArgumentException.class, () -> new Wardrobe("ok", null, "ok"));
        assertThrows(IllegalArgumentException.class, () -> new Wardrobe("ok", "ok", null));
    }
}

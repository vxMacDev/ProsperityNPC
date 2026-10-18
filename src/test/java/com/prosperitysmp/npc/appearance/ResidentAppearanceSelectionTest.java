package com.prosperitysmp.npc.appearance;

import com.prosperitysmp.npc.appearance.ResidentAppearanceCatalogue.Status;
import com.prosperitysmp.npc.resident.ResidentCatalogue;
import com.prosperitysmp.npc.resident.ResidentProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Skin key grammar, the cache key mapping, and which skin a resident wears at each time of day. */
class ResidentAppearanceSelectionTest {
    private static final ResidentProfile MARA = ResidentCatalogue.betaCast().get(0);
    private static final String EVERYDAY = MARA.wardrobe().everyday();
    private static final String WORK = MARA.wardrobe().work();
    private static final String LEISURE = MARA.wardrobe().leisure();

    @ParameterizedTest
    @ValueSource(strings = {"resident/v1/mara-vale/everyday", "resident/v1/ab/work", "resident/v1/00/leisure", "resident/v1/a-b/work",
            "resident/v1/cook/everyday", "resident/v1/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/leisure"})
    void aWellFormedResidentKeyIsAcceptedWithTwoToThirtyTwoNameCharacters(String key) {
        assertTrue(ResidentAppearanceCatalogue.wellFormedResidentKey(key), key);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "resident/v1/a/work", "resident/v1/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/work", "resident/v1/Mara/work",
            "resident/v1/mara_vale/work", "resident/v1/-mara/work", "resident/v2/mara/work", "resident/v1/mara/sleep",
            "resident/v1/mara/Work", "resident/v1/mara/work/", "/resident/v1/mara/work", "resident/v1/mara/work\n",
            "resident/v1/mara//work", "resident/v1/ma ra/work", "resident//mara/work", "resident/v1/mara", "Resident/v1/mara/work",
            "resident/v1/mara/everyday ", "resident/v1/ma.ra/work", "resident/v1/m\u00e4ra/work"})
    void anythingElseIsNotAResidentKey(String key) {
        assertFalse(ResidentAppearanceCatalogue.wellFormedResidentKey(key), key);
    }

    @Test void aMissingKeyIsNotWellFormed() {
        assertFalse(ResidentAppearanceCatalogue.wellFormedResidentKey(null));
    }

    @ParameterizedTest
    @CsvSource({"resident/v1/mara-vale/everyday,cit-pnpc-resident-v1-mara-vale-everyday",
            "resident/v1/cook/work,cit-pnpc-resident-v1-cook-work", "resident/v1/ab/leisure,cit-pnpc-resident-v1-ab-leisure"})
    void theCacheKeyJoinsTheSegmentsWithDashes(String key, String cache) {
        assertEquals(cache, ResidentAppearanceCatalogue.citizensKey(key));
    }

    @Test void theCacheKeyRefusesWhatIsNotAResidentKey() {
        assertThrows(IllegalArgumentException.class, () -> ResidentAppearanceCatalogue.citizensKey("player/steve"));
        assertThrows(IllegalArgumentException.class, () -> ResidentAppearanceCatalogue.citizensKey(null));
        assertThrows(IllegalArgumentException.class, () -> ResidentAppearanceCatalogue.citizensKey(""));
    }

    @Test void aKeyInTheUniverseHasACacheKeyAndOneOutsideDoesNot() {
        var catalogue = new ResidentAppearanceCatalogue();
        assertTrue(catalogue.allows(EVERYDAY));
        assertEquals("cit-pnpc-resident-v1-mara-vale-everyday", catalogue.cacheKey(EVERYDAY));
        assertFalse(catalogue.allows("resident/v1/stranger-danger/everyday"));
        assertFalse(catalogue.allows(null));
        assertThrows(IllegalArgumentException.class, () -> catalogue.cacheKey("resident/v1/stranger-danger/everyday"));
        assertThrows(IllegalArgumentException.class, () -> catalogue.cacheKey(null));
    }

    @Test void theUniverseHoldsTheEighteenOccupationKeysAndNineFromTheBuiltInThree() {
        var keys = new ResidentAppearanceCatalogue().allowedKeys();
        assertEquals(27, keys.size());
        for (ResidentProfile.Occupation job : ResidentProfile.Occupation.values())
            for (String slot : new String[] {"everyday", "work", "leisure"})
                assertTrue(keys.contains("resident/v1/" + job.name().toLowerCase(java.util.Locale.ROOT) + "/" + slot), job + " " + slot);
        for (ResidentProfile profile : ResidentCatalogue.betaCast()) {
            assertTrue(keys.contains(profile.wardrobe().everyday()));
            assertTrue(keys.contains(profile.wardrobe().work()));
            assertTrue(keys.contains(profile.wardrobe().leisure()));
        }
    }

    @Test void aNewCatalogueHasNoAssetsAtRevisionZero() {
        var catalogue = new ResidentAppearanceCatalogue();
        assertEquals(0, catalogue.revision());
        assertEquals(0, catalogue.size());
        assertFalse(catalogue.installed(EVERYDAY));
        assertFalse(catalogue.hasEveryday(MARA));
        assertFalse(catalogue.installed(null));
    }

    @Test void everyReplacementRaisesTheRevisionByOneAndCountsItsAssets() {
        var catalogue = new ResidentAppearanceCatalogue();
        assertEquals(1, catalogue.replace(Map.of(EVERYDAY, TestSkins.synthetic('a'))));
        assertEquals(1, catalogue.size());
        assertEquals(2, catalogue.replace(Map.of(EVERYDAY, TestSkins.synthetic('a'), WORK, TestSkins.synthetic('b'))));
        assertEquals(2, catalogue.size());
        assertEquals(3, catalogue.replace(Map.of()));
        assertEquals(0, catalogue.size());
        assertEquals(3, catalogue.revision());
        assertFalse(catalogue.hasEveryday(MARA));
    }

    @Test void aReplacementOutsideTheUniverseIsRefusedWholeAndChangesNothing() {
        var catalogue = new ResidentAppearanceCatalogue();
        catalogue.replace(Map.of(EVERYDAY, TestSkins.synthetic('a')));
        assertThrows(IllegalArgumentException.class, () -> catalogue.replace(Map.of(WORK, TestSkins.synthetic('b'),
                "resident/v1/stranger-danger/everyday", TestSkins.synthetic('c'))));
        assertEquals(1, catalogue.revision());
        assertEquals(1, catalogue.size());
        assertTrue(catalogue.installed(EVERYDAY));
        assertFalse(catalogue.installed(WORK));
    }

    @Test void aMissingReplacementIsRefused() {
        assertThrows(NullPointerException.class, () -> new ResidentAppearanceCatalogue().replace(null));
    }

    @Test void withNothingInstalledEveryTimeOfDayIsMissingBase() {
        var catalogue = new ResidentAppearanceCatalogue();
        for (long time : new long[] {0, 3_000, 10_000, 12_000}) {
            var selection = catalogue.forSpawn(MARA, time);
            assertEquals(Status.MISSING_BASE, selection.status(), "t=" + time);
            assertTrue(selection.applied().isEmpty());
        }
    }

    @Test void withOnlyTheEverydaySkinTheWorkAndLeisureTimesFallBackToIt() {
        var catalogue = new ResidentAppearanceCatalogue();
        catalogue.replace(Map.of(EVERYDAY, TestSkins.synthetic('a')));
        var home = catalogue.forSpawn(MARA, 0);
        assertEquals(Status.REQUESTED, home.status());
        assertEquals(EVERYDAY, home.requestedKey());
        assertEquals(EVERYDAY, home.applied().orElseThrow().assetKey());
        var work = catalogue.forSpawn(MARA, 3_000);
        assertEquals(Status.BASE_FALLBACK, work.status());
        assertEquals(WORK, work.requestedKey());
        assertEquals(EVERYDAY, work.applied().orElseThrow().assetKey());
        var leisure = catalogue.forSpawn(MARA, 12_000);
        assertEquals(Status.BASE_FALLBACK, leisure.status());
        assertEquals(LEISURE, leisure.requestedKey());
    }

    @Test void withEveryOutfitInstalledEachTimeOfDayWearsItsOwn() {
        var catalogue = new ResidentAppearanceCatalogue();
        catalogue.replace(Map.of(EVERYDAY, TestSkins.synthetic('a'), WORK, TestSkins.synthetic('b'), LEISURE, TestSkins.synthetic('c')));
        assertEquals(EVERYDAY, catalogue.forSpawn(MARA, 0).applied().orElseThrow().assetKey());
        assertEquals(WORK, catalogue.forSpawn(MARA, 3_000).applied().orElseThrow().assetKey());
        assertEquals(EVERYDAY, catalogue.forSpawn(MARA, 10_000).applied().orElseThrow().assetKey());
        assertEquals(LEISURE, catalogue.forSpawn(MARA, 12_000).applied().orElseThrow().assetKey());
        assertEquals(Status.REQUESTED, catalogue.forSpawn(MARA, 3_000).status());
    }

    @Test void theSelectionRecordsTheRevisionItWasMadeAt() {
        var catalogue = new ResidentAppearanceCatalogue();
        catalogue.replace(Map.of(EVERYDAY, TestSkins.synthetic('a')));
        var before = catalogue.forSpawn(MARA, 0);
        catalogue.replace(Map.of(EVERYDAY, TestSkins.synthetic('a'), WORK, TestSkins.synthetic('b')));
        assertEquals(1, before.revision());
        assertEquals(2, catalogue.forSpawn(MARA, 0).revision());
        assertEquals(EVERYDAY, before.applied().orElseThrow().assetKey());
    }

    @Test void theAppliedCacheKeyMatchesTheAssetKey() {
        var catalogue = new ResidentAppearanceCatalogue();
        catalogue.replace(Map.of(EVERYDAY, TestSkins.synthetic('a')));
        var applied = catalogue.forSpawn(MARA, 0).applied().orElseThrow();
        assertEquals(ResidentAppearanceCatalogue.citizensKey(EVERYDAY), applied.citizensKey());
        assertNotNull(applied.skin());
    }

    @Test void aMissingResidentIsRefusedForASpawn() {
        assertThrows(NullPointerException.class, () -> new ResidentAppearanceCatalogue().forSpawn(null, 0));
    }

    @Test void theUniverseCapIsThreePerOccupationPlusNinePlusThreePerCastMember() {
        assertEquals(3 * 6 + 9 + 3 * 32, ResidentAppearanceCatalogue.MAX_UNIVERSE);
    }
}

package com.prosperitysmp.npc.nav;

import com.prosperitysmp.npc.resident.ResidentProfile.Occupation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The parts of the graph repository that need no database: route tags, document hashes and the small records. */
class NavGraphRepositoryPureTest {
    private static final String UUID_TEXT = new UUID(0x0123456789abcdefL, 0xfedcba9876543210L).toString();

    // ---- route tags ----

    @ParameterizedTest
    @ValueSource(strings = {"spawn", "home", "work", "market", "leisure"})
    void theFiveBareRouteTagsAreValid(String tag) {
        assertTrue(NavGraphRepository.isValidDraftTag(tag));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Home", "SPAWN", "bank", "home ", " home", "spawn:", "spawn:" + "x", "home:", "market:", ":home", "home:work",
            "home\n"})
    void anythingElseBareOrMalformedIsNotARouteTag(String tag) {
        assertFalse(NavGraphRepository.isValidDraftTag(tag));
    }

    @ParameterizedTest
    @ValueSource(strings = {"home", "work", "market", "leisure"})
    void aRouteTagMayBeScopedToOneResidentByUuid(String kind) {
        assertTrue(NavGraphRepository.isValidDraftTag(kind + ":" + UUID_TEXT));
    }

    @Test
    void spawnCannotBeScopedToAResident() {
        assertFalse(NavGraphRepository.isValidDraftTag("spawn:" + UUID_TEXT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0123456789ABCDEF-FEDC-BA98-7654-3210", "01234567-89AB-CDEF-FEDC-BA9876543210",
            "01234567-89ab-cdef-fedc-ba987654321", "01234567-89ab-cdef-fedc-ba98765432100", "01234567-89ab-cdef-fedc-ba98765432zz",
            "123456789-89ab-cdef-fedc-ba987654321", "0-0-0-0-0", ""})
    void aResidentScopeMustBeACanonicalLowercaseUuid(String suffix) {
        assertFalse(NavGraphRepository.isValidDraftTag("home:" + suffix), suffix);
    }

    @Test
    void anUppercaseVersionOfAGoodUuidIsRefusedBecauseItWouldNotRoundTrip() {
        assertFalse(NavGraphRepository.isValidDraftTag("work:" + UUID_TEXT.toUpperCase(Locale.ROOT)));
    }

    @ParameterizedTest
    @EnumSource(Occupation.class)
    void workCanBeScopedToEachOccupationInLowercase(Occupation occupation) {
        assertTrue(NavGraphRepository.isValidDraftTag("work:" + occupation.name().toLowerCase(Locale.ROOT)));
        assertFalse(NavGraphRepository.isValidDraftTag("work:" + occupation.name()), "uppercase is not the stored form");
    }

    @ParameterizedTest
    @ValueSource(strings = {"home:cook", "market:cook", "leisure:gardener", "work:wizard", "work:", "work:cook ", "work:cook:cook"})
    void onlyWorkTakesAnOccupationAndOnlyARealOne(String tag) {
        assertFalse(NavGraphRepository.isValidDraftTag(tag), tag);
    }

    @Test
    void aTagLongerThanSixtyFourCharactersIsNeverValid() {
        assertTrue(NavGraphRepository.isValidDraftTag("leisure:" + UUID_TEXT));
        assertFalse(NavGraphRepository.isValidDraftTag("home".repeat(17)));
        assertFalse(NavGraphRepository.isValidDraftTag("spawn" + " ".repeat(60)));
    }

    // ---- hashing ----

    @Test
    void theDocumentShaOfNothingIsTheKnownSha256OfTheEmptyString() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", NavGraphRepository.documentSha(""));
    }

    @Test
    void theDocumentShaOfAbcIsTheKnownSha256() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", NavGraphRepository.documentSha("abc"));
    }

    @Test
    void theDocumentShaIsOfTheUtf8BytesOfTheText() {
        // U+00E9 is C3 A9 in UTF-8; the Latin-1 byte E9 would give de2e331d...
        assertEquals("4a99557e4033c3539de2eb65472017cad5f9557f7a0625a09f1c3f6e2ba69c4c", NavGraphRepository.documentSha("\u00E9"));
        assertEquals("c4cc90ed3d26f12d4b08a75140970a7904035c31cbb4515a83f19b9003c00d1d", NavGraphRepository.documentSha("\u20AC"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a", "format: x\n", "line1\r\nline2", "€"})
    void theDocumentShaIsSixtyFourLowercaseHexDigits(String text) {
        assertTrue(NavGraphRepository.documentSha(text).matches("[0-9a-f]{64}"));
    }

    @Test
    void aLineEndingChangeChangesTheSha() {
        assertFalse(NavGraphRepository.documentSha("a\nb").equals(NavGraphRepository.documentSha("a\r\nb")));
    }

    @Test
    void aNullDocumentCannotBeHashed() {
        assertThrows(NullPointerException.class, () -> NavGraphRepository.documentSha(null));
    }

    // ---- records ----

    @Test
    void aCopyReportKeepsItsOwnImmutableCopyOfTheDetails() {
        List<String> details = new java.util.ArrayList<>(List.of("EXACT home @1,2,3"));
        NavGraphRepository.CopyReport report = new NavGraphRepository.CopyReport(true, 1, 0, 0, 0, 0, 1, 0, 1, details);
        details.add("later");
        assertEquals(List.of("EXACT home @1,2,3"), report.details());
        assertThrows(UnsupportedOperationException.class, () -> report.details().clear());
    }

    @Test
    void theSourceWordsAreTheStoredOnes() {
        assertEquals("scan", NavGraphRepository.SOURCE_SCAN);
        assertEquals("waypoints", NavGraphRepository.SOURCE_WAYPOINTS);
        assertEquals("file", NavGraphRepository.SOURCE_FILE);
    }
}

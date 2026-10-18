package com.prosperitysmp.npc.resident;

import com.prosperitysmp.npc.resident.ResidentCast.Source;
import com.prosperitysmp.npc.resident.ResidentProfile.Occupation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The cast record itself: its size and uniqueness rules, its lookups and its health line. */
class ResidentCastRecordTest {
    private static ResidentProfile person(long id, String name) {
        return new ResidentProfile(new UUID(0, id), name, "Household", 1, Occupation.COOK, ResidentProfile.Means.STEADY,
                ResidentProfile.LifeStage.ADULT, ResidentProfile.Temperament.WARM, ResidentProfile.Preference.QUIET_ROUTINE,
                new ResidentProfile.Wardrobe("resident/v1/x/everyday", "resident/v1/x/work", "resident/v1/x/leisure"));
    }

    private static ResidentCast cast(ResidentProfile... people) {
        return new ResidentCast(List.of(people), Map.of(), Source.FILE);
    }

    @Test void theBuiltInCastIsTheBetaThreeWithTheCodesOwnLines() {
        ResidentCast cast = ResidentCast.builtIn();
        assertEquals(ResidentCatalogue.betaCast(), cast.profiles());
        assertEquals(Source.BUILT_IN, cast.source());
        assertEquals(Map.of(), cast.lines());
    }

    @Test void aRejectedFileGivesTheSameThreeMarkedRejected() {
        ResidentCast cast = ResidentCast.rejected();
        assertEquals(ResidentCatalogue.betaCast(), cast.profiles());
        assertEquals(Source.FILE_REJECTED, cast.source());
    }

    @Test void theMaximumCastIsThirtyTwo() {
        assertEquals(32, ResidentCast.MAX_RESIDENTS);
    }

    @Test void anEmptyCastIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ResidentCast(List.of(), Map.of(), Source.FILE));
    }

    @Test void aCastOfThirtyTwoIsAllowedAndThirtyThreeIsNot() {
        List<ResidentProfile> thirtyTwo = new ArrayList<>();
        for (int i = 1; i <= 32; i++) thirtyTwo.add(person(i, "Name" + letters(i) + " Surname" + letters(i)));
        assertEquals(32, new ResidentCast(thirtyTwo, Map.of(), Source.FILE).profiles().size());
        List<ResidentProfile> thirtyThree = new ArrayList<>(thirtyTwo);
        thirtyThree.add(person(33, "Extra Person"));
        assertThrows(IllegalArgumentException.class, () -> new ResidentCast(thirtyThree, Map.of(), Source.FILE));
    }

    private static String letters(int n) {
        StringBuilder out = new StringBuilder();
        for (int v = n; v > 0; v /= 26) out.append((char) ('a' + v % 26));
        return out.toString();
    }

    @Test void twoResidentsWithTheSameIdAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> cast(person(1, "Ada Lane"), person(1, "Bea Moss")));
    }

    @Test void twoResidentsWithTheSameFullNameAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> cast(person(1, "Ada Lane"), person(2, "Ada Lane")));
    }

    @Test void twoResidentsWhoseFirstNamesDifferOnlyInCaseAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> cast(person(1, "Ada Lane"), person(2, "ADA Moss")));
        assertThrows(IllegalArgumentException.class, () -> cast(person(1, "Ada Lane"), person(2, "ada Moss")));
    }

    @Test void theSameSurnameWithDifferentFirstNamesIsAllowed() {
        assertEquals(2, cast(person(1, "Ada Lane"), person(2, "Bea Lane")).profiles().size());
    }

    @Test void authoredLinesForAResidentOutsideTheCastAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new ResidentCast(List.of(person(1, "Ada Lane")),
                Map.of(new UUID(0, 2), ResidentLines.NONE), Source.FILE));
    }

    @Test void authoredLinesForAMemberAreReturnedAndOthersGetNone() {
        ResidentLines lines = new ResidentLines(List.of("Hello one.", "Hello two."), List.of(), List.of());
        ResidentCast cast = new ResidentCast(List.of(person(1, "Ada Lane"), person(2, "Bea Moss")), Map.of(new UUID(0, 1), lines), Source.FILE);
        assertSame(lines, cast.lines(new UUID(0, 1)));
        assertSame(ResidentLines.NONE, cast.lines(new UUID(0, 2)));
        assertSame(ResidentLines.NONE, cast.lines(new UUID(0, 99)));
    }

    @Test void theProfileListIsAnImmutableCopy() {
        List<ResidentProfile> source = new ArrayList<>(List.of(person(1, "Ada Lane")));
        ResidentCast cast = new ResidentCast(source, Map.of(), Source.FILE);
        source.add(person(2, "Bea Moss"));
        assertEquals(1, cast.profiles().size());
        assertThrows(UnsupportedOperationException.class, () -> cast.profiles().clear());
    }

    @Test void aMissingSourceIsRefused() {
        assertThrows(NullPointerException.class, () -> new ResidentCast(List.of(person(1, "Ada Lane")), Map.of(), null));
    }

    @ParameterizedTest @CsvSource({"Ada,Ada Lane", "ada,Ada Lane", "ADA,Ada Lane", "bea,Bea Moss", "Bea,Bea Moss"})
    void aFirstNameLookupIgnoresCase(String query, String fullName) {
        ResidentCast cast = cast(person(1, "Ada Lane"), person(2, "Bea Moss"));
        assertEquals(fullName, cast.byFirstName(query).orElseThrow().name());
    }

    @ParameterizedTest @ValueSource(strings = {"Lane", "Ad", "Ada Lane", "", "Cy"})
    void aFirstNameLookupMatchesTheWholeFirstWordOnly(String query) {
        assertEquals(Optional.empty(), cast(person(1, "Ada Lane"), person(2, "Bea Moss")).byFirstName(query));
    }

    @Test void aMissingFirstNameFindsNothing() {
        assertEquals(Optional.empty(), cast(person(1, "Ada Lane")).byFirstName(null));
    }

    @Test void theLookupByKeyUsesThePermanentIdOfTheKey() {
        ResidentCast cast = ResidentCast.builtIn();
        assertEquals("Mara Vale", cast.byKey("mara-vale").orElseThrow().name());
        assertEquals("Nico Reed", cast.byKey("nico-reed").orElseThrow().name());
        assertEquals(Optional.empty(), cast.byKey("someone-else"));
        assertEquals(Optional.empty(), cast.byKey("Mara-Vale"));
        assertEquals(Optional.empty(), cast.byKey(null));
    }

    @Test void theLookupByIdAndContainsAgree() {
        ResidentCast cast = cast(person(1, "Ada Lane"));
        assertTrue(cast.contains(new UUID(0, 1)));
        assertFalse(cast.contains(new UUID(0, 2)));
        assertEquals("Ada Lane", cast.byId(new UUID(0, 1)).orElseThrow().name());
        assertEquals(Optional.empty(), cast.byId(new UUID(0, 2)));
    }

    @Test void firstNamesAreLowerCaseInCastOrder() {
        assertEquals(List.of("mara", "ellis", "nico"), ResidentCast.builtIn().firstNames());
        assertEquals(List.of("ada", "bea"), cast(person(1, "ADA Lane"), person(2, "Bea Moss")).firstNames());
    }

    @Test void aSingleWordNameIsItsOwnFirstName() {
        assertEquals(List.of("cher"), cast(person(1, "Cher")).firstNames());
        assertEquals("Cher", cast(person(1, "Cher")).byFirstName("cher").orElseThrow().name());
    }

    @Test void theHealthLineSaysWhereTheCastCameFromAndNeverQuotesAFile() {
        assertEquals("Residents: built-in three. residents.yml is off.", ResidentCast.builtIn().healthLine());
        assertEquals("Residents: residents.yml was rejected. Using the built-in three.", ResidentCast.rejected().healthLine());
        assertEquals("Residents: 2 from residents.yml", cast(person(1, "Ada Lane"), person(2, "Bea Moss")).healthLine());
        assertEquals("Residents: 1 from residents.yml", cast(person(1, "Ada Lane")).healthLine());
    }
}

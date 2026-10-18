package com.prosperitysmp.npc.nav.origin;

import com.prosperitysmp.npc.nav.NavGraphRepository;
import com.prosperitysmp.npc.nav.NavGraphRepository.GraphInfo;
import com.prosperitysmp.npc.nav.StreetRules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What each graph origin says about itself: its source word, its fingerprint, its fix hint and whether it always gates. */
class GraphOriginTest {
    private static final String SHA = "1".repeat(64);
    private static final String PALETTE = "2".repeat(64);
    private static final String RULES = "reach=12;length=16;share=0.4;step=1";

    private static GraphInfo info(String source, String originSha, String originRef) {
        return new GraphInfo(new UUID(0, 11), new UUID(0, 12), "midtown", "city", 3, "DRAFT", 40, 90,
                PALETTE, source, originSha, originRef);
    }

    // ---- the registry ----

    @Test
    void theRegistryFindsEachKnownSource() {
        assertSame(OriginRegistry.SCAN, OriginRegistry.of("scan"));
        assertSame(DocumentOrigin.WAYPOINTS, OriginRegistry.of("waypoints"));
        assertSame(DocumentOrigin.FILE, OriginRegistry.of("file"));
    }

    @Test
    void aGraphRowWithNoSourceIsAScan() {
        assertSame(OriginRegistry.SCAN, OriginRegistry.of(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "SCAN", "Scan", "Waypoints", "FILE", " scan", "scan ", "wp1", "fg1", "scan\n", "import"})
    void anyOtherSourceIsUnknownAndGetsNoOrigin(String source) {
        assertNull(OriginRegistry.of(source));
    }

    @Test
    void theUnknownSourceRefusalNamesTheSourceAndSaysItCannotBeUsed() {
        assertEquals("This graph's source 'tiles' is not known to this version of ProsperityNPC; it cannot be used here.",
                OriginRegistry.unknown("tiles"));
    }

    @Test
    void theUnknownSourceRefusalOfANullSourceStillReads() {
        assertEquals("This graph's source 'null' is not known to this version of ProsperityNPC; it cannot be used here.",
                OriginRegistry.unknown(null));
    }

    @Test
    void everyOriginReportsTheSourceWordTheRepositoryStores() {
        assertEquals(NavGraphRepository.SOURCE_SCAN, OriginRegistry.SCAN.source());
        assertEquals(NavGraphRepository.SOURCE_WAYPOINTS, DocumentOrigin.WAYPOINTS.source());
        assertEquals(NavGraphRepository.SOURCE_FILE, DocumentOrigin.FILE.source());
        assertEquals("scan", OriginRegistry.SCAN.source());
        assertEquals("waypoints", DocumentOrigin.WAYPOINTS.source());
        assertEquals("file", DocumentOrigin.FILE.source());
    }

    @Test
    void theRegistryRoundTripsEveryOriginsOwnSource() {
        for (GraphOrigin origin : List.of(OriginRegistry.SCAN, DocumentOrigin.WAYPOINTS, DocumentOrigin.FILE)) {
            assertSame(origin, OriginRegistry.of(origin.source()));
        }
    }

    // ---- scan origin ----

    @Test
    void aScanIsGatedOnlyWhenTheGeneralGateIsOn() {
        assertFalse(OriginRegistry.SCAN.alwaysGate());
    }

    @Test
    void aScansFingerprintIsExactlyThePaletteHash() throws SQLException {
        GraphInfo scan = info("scan", null, null);
        assertEquals(PALETTE, OriginRegistry.SCAN.fingerprintFor(scan, PALETTE, RULES, sha -> null));
        assertEquals("", OriginRegistry.SCAN.fingerprintFor(scan, "", RULES, sha -> null));
        assertNull(OriginRegistry.SCAN.fingerprintFor(scan, null, RULES, sha -> null));
    }

    @Test
    void aScansFingerprintIgnoresTheRulesAndNeverReadsADocument() throws SQLException {
        GraphInfo scan = info("scan", SHA, "district.yml");
        List<String> reads = new ArrayList<>();
        String first = OriginRegistry.SCAN.fingerprintFor(scan, PALETTE, null, sha -> {
            reads.add(sha);
            return "text";
        });
        String second = OriginRegistry.SCAN.fingerprintFor(scan, PALETTE, "reach=1;length=1;share=1.0;step=0", sha -> {
            reads.add(sha);
            return "text";
        });
        assertEquals(first, second);
        assertEquals(List.of(), reads);
    }

    @Test
    void theRescanHintCarriesTheBoxPaddedByThreeOnEachSideAndTheVerticalMarginsAndThePalette() {
        int[] box = {10, 60, 20, 30, 70, 40};
        assertEquals("  Rescan: /npcnav scanbox midtown overworld 7 58 17 33 73 43 city",
                OriginRegistry.SCAN.fixHint(info("scan", null, null), box, "overworld", 2, 3));
    }

    @Test
    void theRescanHintPadsNegativeCoordinatesTheSameWay() {
        int[] box = {-100, 5, -50, -90, 9, -40};
        assertEquals("  Rescan: /npcnav scanbox midtown nether -103 4 -53 -87 11 -37 city",
                OriginRegistry.SCAN.fixHint(info("scan", null, null), box, "nether", 1, 2));
    }

    @Test
    void theRescanHintWithNoVerticalMarginUsesTheBoxHeightsAsTheyAre() {
        int[] box = {0, 64, 0, 4, 66, 4};
        assertEquals("  Rescan: /npcnav scanbox midtown w -3 64 -3 7 66 7 city",
                OriginRegistry.SCAN.fixHint(info("scan", null, null), box, "w", 0, 0));
    }

    // ---- document origins ----

    @Test
    void bothDocumentOriginsAlwaysGate() {
        assertTrue(DocumentOrigin.WAYPOINTS.alwaysGate());
        assertTrue(DocumentOrigin.FILE.alwaysGate());
    }

    @Test
    void theFingerprintIsTheShaOfTheKindPrefixDocumentPaletteAndRulesJoinedByBars() {
        assertEquals(NavGraphRepository.documentSha("wp1|" + SHA + "|" + PALETTE + "|" + RULES),
                DocumentOrigin.WAYPOINTS.fingerprint(SHA, PALETTE, RULES));
        assertEquals(NavGraphRepository.documentSha("fg1|" + SHA + "|" + PALETTE + "|" + RULES),
                DocumentOrigin.FILE.fingerprint(SHA, PALETTE, RULES));
    }

    @Test
    void theFingerprintIsSixtyFourLowercaseHexDigits() {
        String fingerprint = DocumentOrigin.WAYPOINTS.fingerprint(SHA, PALETTE, RULES);
        assertEquals(64, fingerprint.length());
        assertTrue(fingerprint.matches("[0-9a-f]{64}"), fingerprint);
    }

    @Test
    void theFingerprintIsStableForTheSameInputs() {
        assertEquals(DocumentOrigin.FILE.fingerprint(SHA, PALETTE, RULES), DocumentOrigin.FILE.fingerprint(SHA, PALETTE, RULES));
    }

    @Test
    void movingACharacterAcrossABarChangesTheFingerprint() {
        assertNotEquals(DocumentOrigin.WAYPOINTS.fingerprint("ab", "c", RULES),
                DocumentOrigin.WAYPOINTS.fingerprint("a", "bc", RULES));
    }

    @ParameterizedTest
    @CsvSource({
            "12,16,0.4,1,reach=12;length=16;share=0.4;step=1",
            "0,1,1.0,0,reach=0;length=1;share=1.0;step=0",
            "64,32,0.35,1,reach=64;length=32;share=0.35;step=1",
            "8,10,0.5,0,reach=8;length=10;share=0.5;step=0",
            "12,16,0.001,1,reach=12;length=16;share=0.001;step=1"
    })
    void theRulesTextNamesEveryNumberThatChangesABuiltGraph(int reach, int length, double share, int step, String expected) {
        assertEquals(expected, DocumentOrigin.rules(new StreetRules(reach, length, share), step));
    }

    @Test
    void theRulesTextUsesADotWhateverTheServerLocaleIs() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("fr-FR"));
            assertEquals("reach=12;length=16;share=0.4;step=1", DocumentOrigin.rules(new StreetRules(12, 16, 0.4), 1));
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    void aDocumentGraphWithNoStoredDocumentShaHasNoFingerprintAndNeverAsksForOne() throws SQLException {
        List<String> reads = new ArrayList<>();
        GraphInfo noSha = info("waypoints", null, null);
        assertNull(DocumentOrigin.WAYPOINTS.fingerprintFor(noSha, PALETTE, RULES, sha -> {
            reads.add(sha);
            return "text";
        }));
        assertEquals(List.of(), reads);
    }

    @Test
    void aDocumentGraphWhoseDocumentIsGoneHasNoFingerprint() throws SQLException {
        assertNull(DocumentOrigin.FILE.fingerprintFor(info("file", SHA, "d.yml"), PALETTE, RULES, sha -> null));
    }

    @Test
    void aDocumentGraphWithPresentInputsGetsTheFingerprintOfItsOwnDocument() throws SQLException {
        List<String> reads = new ArrayList<>();
        String actual = DocumentOrigin.FILE.fingerprintFor(info("file", SHA, "d.yml"), PALETTE, RULES, sha -> {
            reads.add(sha);
            return "";
        });
        assertEquals(List.of(SHA), reads, "the document is looked up by the graph's own sha");
        assertEquals(DocumentOrigin.FILE.fingerprint(SHA, PALETTE, RULES), actual);
    }

    @Test
    void anEmptyStoredDocumentStillCountsAsPresent() throws SQLException {
        assertEquals(DocumentOrigin.WAYPOINTS.fingerprint(SHA, PALETTE, RULES),
                DocumentOrigin.WAYPOINTS.fingerprintFor(info("waypoints", SHA, null), PALETTE, RULES, sha -> ""));
    }

    @Test
    void aDatabaseFailureWhileReadingTheDocumentReachesTheCaller() {
        SQLException failure = new SQLException("lost connection");
        SQLException thrown = assertThrows(SQLException.class, () ->
                DocumentOrigin.WAYPOINTS.fingerprintFor(info("waypoints", SHA, null), PALETTE, RULES, sha -> {
                    throw failure;
                }));
        assertSame(failure, thrown);
    }

    @Test
    void theWaypointHintSendsStaffBackToTheBuildCommandOfTheRegion() {
        assertEquals("  Fix the listed points, then /npcnav wp build midtown",
                DocumentOrigin.WAYPOINTS.fixHint(info("waypoints", SHA, null), new int[]{0, 0, 0, 0, 0, 0}, "w", 1, 1));
    }

    @Test
    void theFileHintNamesTheRouteFileAndTheImportCommandWithTheWorld() {
        assertEquals("  Fix the route file district.yml, then /npcnav import midtown overworld <file>",
                DocumentOrigin.FILE.fixHint(info("file", SHA, "district.yml"), new int[]{0, 0, 0, 0, 0, 0}, "overworld", 1, 1));
    }

    @Test
    void theFileHintWithNoRecordedFileNameLeavesItOut() {
        assertEquals("  Fix the route file, then /npcnav import midtown overworld <file>",
                DocumentOrigin.FILE.fixHint(info("file", SHA, null), new int[]{0, 0, 0, 0, 0, 0}, "overworld", 1, 1));
    }

    @Test
    void theDocumentHintsIgnoreTheDriftBox() {
        GraphInfo waypoints = info("waypoints", SHA, null);
        assertEquals(DocumentOrigin.WAYPOINTS.fixHint(waypoints, new int[]{1, 2, 3, 4, 5, 6}, "w", 1, 1),
                DocumentOrigin.WAYPOINTS.fixHint(waypoints, new int[]{9, 9, 9, 9, 9, 9}, "w", 7, 7));
    }

    // ---- the graph row ----

    @Test
    void aGraphInfoBuiltWithoutASourceIsAScanWithNoOriginDocument() {
        GraphInfo legacy = new GraphInfo(new UUID(0, 1), new UUID(0, 2), "r", "p", 1, "ACTIVE", 2, 1, "h");
        assertEquals("scan", legacy.source());
        assertNull(legacy.originSha());
        assertNull(legacy.originRef());
    }

    @ParameterizedTest
    @NullSource
    void aNullSourceOnTheRowReadsAsScan(String source) {
        assertEquals("scan", info(source, null, null).source());
    }

    @Test
    void aGraphInfoKeepsAnExplicitSourceAndItsOriginFields() {
        GraphInfo file = info("file", SHA, "d.json");
        assertEquals("file", file.source());
        assertEquals(SHA, file.originSha());
        assertEquals("d.json", file.originRef());
    }
}

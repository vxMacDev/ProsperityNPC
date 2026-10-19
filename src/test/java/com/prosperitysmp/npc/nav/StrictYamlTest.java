package com.prosperitysmp.npc.nav;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The strict loader for staff-supplied files: what it refuses, what it still reads, and never echoing the input. */
class StrictYamlTest {
    private static final String MARKER = "SECRET_MARKER_91";
    private static final String BARE_NAME = "Use a bare file name like district.yml (letters, digits, . _ -, ending .yml, .yaml or .json).";
    private static final String GENERIC = "The file is not valid YAML or JSON, repeats a key, or nests too deeply.";

    private static String refusal(String text) {
        return assertThrows(StrictYaml.Refused.class, () -> StrictYaml.load(text)).getMessage();
    }

    private static String nested(int depth) {
        return "[".repeat(depth) + "1" + "]".repeat(depth);
    }

    // ---- load: what it accepts ----

    @Test
    void aPlainMapOfScalarsAndListsLoadsAsJavaMapsAndLists() {
        Object loaded = StrictYaml.load("name: hub\nat: [1, -2, 3]\nnote: null\nflag: true\n");
        Map<?, ?> map = (Map<?, ?>) loaded;
        assertEquals("hub", map.get("name"));
        assertEquals(List.of(1, -2, 3), map.get("at"));
        assertTrue(map.containsKey("note"));
        assertNull(map.get("note"));
        assertEquals(Boolean.TRUE, map.get("flag"));
    }

    @Test
    void jsonIsReadAsYamlFlowStyle() {
        Map<?, ?> map = (Map<?, ?>) StrictYaml.load("{\"a\": [1, 2, {\"b\": \"c\"}], \"d\": 0}");
        assertEquals(List.of(1, 2, Map.of("b", "c")), map.get("a"));
        assertEquals(0, map.get("d"));
    }

    @Test
    void anEmptyDocumentLoadsAsNothing() {
        assertNull(StrictYaml.load(""));
        assertNull(StrictYaml.load("# only a comment\n"));
    }

    @Test
    void zeroNegativeZeroAndBigPlainDecimalsAreWholeNumbersTheLoaderKeeps() {
        Map<?, ?> map = (Map<?, ?>) StrictYaml.load("a: 0\nb: -0\nc: -128\nd: 99999999999999999999\n");
        assertEquals(0, map.get("a"));
        assertEquals(0, map.get("b"));
        assertEquals(-128, map.get("c"));
        assertEquals(new BigInteger("99999999999999999999"), map.get("d"));
    }

    @Test
    void quotedNumbersAndQuotedMergeKeysAreText() {
        Map<?, ?> map = (Map<?, ?>) StrictYaml.load("a: '010'\nb: \"0x10\"\n\"<<\": 1\n");
        assertEquals("010", map.get("a"));
        assertEquals("0x10", map.get("b"));
        assertEquals(1, map.get("<<"));
    }

    @Test
    void decimalsThatAreNotWholeNumbersAreNotJudgedAsOddIntegers() {
        Map<?, ?> map = (Map<?, ?>) StrictYaml.load("a: 1.5\nb: 007.5\n");
        assertEquals(1.5, map.get("a"));
        assertEquals(7.5, map.get("b"));
    }

    @Test
    void eightLevelsOfNestingLoadAndTheNinthIsRefused() {
        assertEquals(1, descend(StrictYaml.load(nested(8)), 8));
        assertEquals(GENERIC, refusal(nested(9)));
    }

    private static Object descend(Object value, int levels) {
        Object at = value;
        for (int i = 0; i < levels; i++) {
            at = ((List<?>) at).get(0);
        }
        return at;
    }

    @Test
    void aDocumentOfExactlyTheLimitInCharactersIsAccepted() {
        String text = "#" + "x".repeat(StrictYaml.DEFAULT_MAX_BYTES - 1);
        assertEquals(StrictYaml.DEFAULT_MAX_BYTES, text.length());
        assertNull(StrictYaml.load(text));
    }

    @Test
    void aDocumentOneCharacterOverTheLimitIsTooBig() {
        String text = "#" + "x".repeat(StrictYaml.DEFAULT_MAX_BYTES);
        assertEquals("The file is too big.", refusal(text));
    }

    // ---- load: what it refuses ----

    @Test
    void aNullTextIsAnEmptyFile() {
        assertEquals("The file is empty.", refusal(null));
    }

    @Test
    void aByteOrderMarkIsRefusedWithAnInstructionToSavePlainUtf8() {
        assertEquals("The file starts with a byte-order mark; save it as plain UTF-8.", refusal("﻿a: 1\n"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "a: &x 1\nb: *x\n",
            "a: &x [1]\n",
            "- &first {k: v}\n- *first\n",
            "&top {a: 1}\n"
    })
    void anchorsAndAliasesAreRefusedAsAnchors(String text) {
        assertEquals("The file uses a YAML anchor; anchors are not allowed.", refusal(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "a: !!str 1\n",
            "a: !!int '5'\n",
            "a: !custom x\n",
            "a: !!python/object:java.lang.Object {}\n",
            "a: !!map {b: 1}\n",
            "a: !!seq [1]\n",
            "!!map {a: 1}\n"
    })
    void explicitTagsOnScalarsAndCollectionsAreRefused(String text) {
        assertEquals("The file uses an explicit YAML tag; tags are not allowed.", refusal(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {"<<: {a: 1}\n", "base: {a: 1}\nuse:\n  <<: {a: 2}\n", "{<<: {a: 1}}"})
    void aMergeKeyIsRefused(String text) {
        assertEquals("The file uses a YAML merge key; merge keys are not allowed.", refusal(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a: 010\n", "a: 0x1F\n", "a: 0b101\n", "a: 1_000\n", "a: 1:30\n", "a: +5\n", "a: -010\n", "a: [1, 00]\n"})
    void aWholeNumberNotWrittenInPlainDecimalIsRefused(String text) {
        assertEquals("The file writes a number in a form other than plain decimal (no leading zeros, 0x, 0b, _ or :); "
                + "write whole numbers like 72 or -128.", refusal(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "a: 1\na: 2\n",
            "{\"a\": 1, \"a\": 2}",
            "outer:\n  k: 1\n  k: 2\n",
            "a: [1\n",
            "a: b: c\n",
            "a: 1\n---\nb: 2\n",
            "a: \"unterminated\n"
    })
    void duplicateKeysBrokenSyntaxAndExtraDocumentsAreRefusedWithTheGenericMessage(String text) {
        assertEquals(GENERIC, refusal(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            MARKER + ": 1\n" + MARKER + ": 2\n",
            MARKER + ": &a 1\n",
            MARKER + ": !!str 1\n",
            "<<: {" + MARKER + ": 1}\n",
            MARKER + ": 010\n",
            MARKER + ": [1\n",
            "a: " + MARKER + "\n---\nb: 1\n"
    })
    void noRefusalMessageEverQuotesTheInput(String text) {
        assertFalse(refusal(text).contains(MARKER));
    }

    @Test
    void aTagInsideAnArrayOfNumbersIsStillCaught() {
        assertEquals("The file uses an explicit YAML tag; tags are not allowed.", refusal("a: [1, !!str 2]\n"));
    }

    // ---- the size limit is in UTF-8 bytes, not characters ----

    @Test
    void aDocumentLargerThanTheLimitInBytesIsTooBigEvenWhenItHasFewerCharacters() {
        String text = "#" + "€".repeat(600_000);
        assertTrue(text.getBytes(StandardCharsets.UTF_8).length > StrictYaml.DEFAULT_MAX_BYTES);
        assertEquals("The file is too big.", refusal(text));
    }

    // ---- readImportFile ----

    private static Path file(Path folder, String name, byte[] bytes) throws IOException {
        Path path = folder.resolve(name);
        Files.write(path, bytes);
        return path;
    }

    @ParameterizedTest
    @ValueSource(strings = {"../x.yml", "..\\x.yml", "sub/x.yml", "/etc/x.yml", ".hidden.yml", "a b.yml", "x.txt", "x.YML", "x.yml.txt",
            "x", "x.yml\n", ".yml", "a:stream.yml", "é.yml", "x.yaml/", "", "x.yml "})
    void aNameThatIsNotABareYamlOrJsonFileNameIsRefusedBeforeTheDiskIsTouched(String name, @TempDir Path folder) {
        StrictYaml.Refused refused = assertThrows(StrictYaml.Refused.class, () -> StrictYaml.readImportFile(folder, name, 1024));
        assertEquals(BARE_NAME, refused.getMessage());
    }

    @Test
    void aNullNameIsRefusedWithTheBareNameMessage(@TempDir Path folder) {
        assertEquals(BARE_NAME, assertThrows(StrictYaml.Refused.class, () -> StrictYaml.readImportFile(folder, null, 1024)).getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"district.yml", "district.yaml", "district.json", "A_b-c.1.yml", "a..yml", "1.yml"})
    void aBareNameWithAnAllowedEndingReadsItsText(String name, @TempDir Path folder) throws IOException {
        file(folder, name, "a: 1\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("a: 1\n", StrictYaml.readImportFile(folder, name, 1024));
    }

    @Test
    void aStemOfSixtyFourCharactersIsAcceptedAndOneOfSixtyFiveIsNot(@TempDir Path folder) throws IOException {
        String longest = "a".repeat(64) + ".yml";
        String tooLong = "a".repeat(65) + ".yml";
        assertEquals(68, longest.length());
        file(folder, longest, "x".getBytes(StandardCharsets.UTF_8));
        assertEquals("x", StrictYaml.readImportFile(folder, longest, 1024));
        assertEquals(BARE_NAME, assertThrows(StrictYaml.Refused.class, () -> StrictYaml.readImportFile(folder, tooLong, 1024)).getMessage());
    }

    @Test
    void aMissingFileIsNamedInTheRefusal(@TempDir Path folder) {
        StrictYaml.Refused refused = assertThrows(StrictYaml.Refused.class, () -> StrictYaml.readImportFile(folder, "missing.yml", 1024));
        assertEquals("No file missing.yml in the import folder.", refused.getMessage());
    }

    @Test
    void aFolderWithAFileLikeNameIsNotARegularFile(@TempDir Path folder) throws IOException {
        Files.createDirectory(folder.resolve("dir.yml"));
        StrictYaml.Refused refused = assertThrows(StrictYaml.Refused.class, () -> StrictYaml.readImportFile(folder, "dir.yml", 1024));
        assertEquals("That is not a regular file.", refused.getMessage());
    }

    @Test
    void aSymbolicLinkIsNeverFollowed(@TempDir Path folder, @TempDir Path elsewhere) throws IOException {
        Path target = file(elsewhere, "real.yml", "secret: 1\n".getBytes(StandardCharsets.UTF_8));
        try {
            Files.createSymbolicLink(folder.resolve("link.yml"), target);
        } catch (UnsupportedOperationException | IOException | SecurityException noPrivilege) {
            Assumptions.abort("symbolic links cannot be created here: " + noPrivilege.getClass().getSimpleName());
        }
        StrictYaml.Refused refused = assertThrows(StrictYaml.Refused.class, () -> StrictYaml.readImportFile(folder, "link.yml", 1024));
        assertEquals("That is not a regular file.", refused.getMessage());
    }

    @Test
    void aFileExactlyAtTheLimitReadsAndOneByteOverIsRefused(@TempDir Path folder) throws IOException {
        file(folder, "at.yml", "0123456789".getBytes(StandardCharsets.UTF_8));
        file(folder, "over.yml", "0123456789A".getBytes(StandardCharsets.UTF_8));
        assertEquals("0123456789", StrictYaml.readImportFile(folder, "at.yml", 10));
        StrictYaml.Refused refused = assertThrows(StrictYaml.Refused.class, () -> StrictYaml.readImportFile(folder, "over.yml", 10));
        assertEquals("The file is bigger than nav.import.max-bytes (10).", refused.getMessage());
    }

    @Test
    void theLimitCountsBytesNotCharacters(@TempDir Path folder) throws IOException {
        file(folder, "wide.yml", "€€".getBytes(StandardCharsets.UTF_8));
        assertEquals("€€", StrictYaml.readImportFile(folder, "wide.yml", 6));
        StrictYaml.Refused refused = assertThrows(StrictYaml.Refused.class, () -> StrictYaml.readImportFile(folder, "wide.yml", 5));
        assertEquals("The file is bigger than nav.import.max-bytes (5).", refused.getMessage());
    }

    @Test
    void anEmptyFileReadsAsEmptyTextWithALimitOfZero(@TempDir Path folder) throws IOException {
        file(folder, "empty.yml", new byte[0]);
        assertEquals("", StrictYaml.readImportFile(folder, "empty.yml", 0));
    }

    @Test
    void anyContentIsOverALimitOfZero(@TempDir Path folder) throws IOException {
        file(folder, "one.yml", new byte[]{'a'});
        StrictYaml.Refused refused = assertThrows(StrictYaml.Refused.class, () -> StrictYaml.readImportFile(folder, "one.yml", 0));
        assertEquals("The file is bigger than nav.import.max-bytes (0).", refused.getMessage());
    }

    @Test
    void bytesThatAreNotUtf8AreRefused(@TempDir Path folder) throws IOException {
        file(folder, "bad.yml", new byte[]{(byte) 0xC3, 0x28});
        StrictYaml.Refused refused = assertThrows(StrictYaml.Refused.class, () -> StrictYaml.readImportFile(folder, "bad.yml", 1024));
        assertEquals("The file is not UTF-8 text.", refused.getMessage());
    }

    @Test
    void aLoneContinuationByteAndATruncatedSequenceAreNotUtf8Either(@TempDir Path folder) throws IOException {
        file(folder, "lone.yml", new byte[]{'a', (byte) 0x80});
        file(folder, "cut.yml", new byte[]{'a', (byte) 0xE2, (byte) 0x82});
        assertEquals("The file is not UTF-8 text.", assertThrows(StrictYaml.Refused.class, () -> StrictYaml.readImportFile(folder, "lone.yml", 1024)).getMessage());
        assertEquals("The file is not UTF-8 text.", assertThrows(StrictYaml.Refused.class, () -> StrictYaml.readImportFile(folder, "cut.yml", 1024)).getMessage());
    }

    @Test
    void validMultiByteTextComesBackUnchanged(@TempDir Path folder) throws IOException {
        String text = "name: café € 😀\n";
        file(folder, "unicode.yml", text.getBytes(StandardCharsets.UTF_8));
        assertEquals(text, StrictYaml.readImportFile(folder, "unicode.yml", 1024));
    }

    @Test
    void aByteOrderMarkSurvivesTheReadAndThenStopsTheLoad(@TempDir Path folder) throws IOException {
        file(folder, "bom.yml", new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'a', ':', ' ', '1'});
        String text = StrictYaml.readImportFile(folder, "bom.yml", 1024);
        assertTrue(text.startsWith("﻿"));
        assertEquals("The file starts with a byte-order mark; save it as plain UTF-8.", refusal(text));
    }

    @Test
    void aFileNameMayNotAppearInsideTheMessageForADifferentProblem(@TempDir Path folder) throws IOException {
        file(folder, "bad.yml", new byte[]{(byte) 0xFF});
        String message = assertThrows(StrictYaml.Refused.class, () -> StrictYaml.readImportFile(folder, "bad.yml", 1024)).getMessage();
        assertFalse(message.contains("bad.yml"));
    }

    @Test
    void aHugeLimitStillReadsTheWholeFile(@TempDir Path folder) throws IOException {
        file(folder, "a.yml", "a: 1\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("a: 1\n", StrictYaml.readImportFile(folder, "a.yml", Long.MAX_VALUE));
    }

    @Test
    void theFileNamePatternAcceptsOnlyTheDocumentedShape() {
        assertTrue(StrictYaml.FILE_NAME.matcher("a.yml").matches());
        assertTrue(StrictYaml.FILE_NAME.matcher("a.yaml").matches());
        assertTrue(StrictYaml.FILE_NAME.matcher("a.json").matches());
        assertFalse(StrictYaml.FILE_NAME.matcher("a.yaml.yml.x").matches());
        assertFalse(StrictYaml.FILE_NAME.matcher("a.jsonl").matches());
        assertFalse(StrictYaml.FILE_NAME.matcher("a.yml\n").matches());
    }

    @Test
    void theDefaultLimitIsOneMebibyte() {
        assertEquals(1_048_576, StrictYaml.DEFAULT_MAX_BYTES);
    }
}

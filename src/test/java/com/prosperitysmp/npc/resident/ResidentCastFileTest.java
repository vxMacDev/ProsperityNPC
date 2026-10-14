package com.prosperitysmp.npc.resident;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ResidentCastFileTest {
    private static final Path EXAMPLE = Path.of("examples/residents.extended.yml");

    private static String entry(String key, String name, String household, String extra) {
        return "  - key: " + key + "\n    name: " + name + "\n    household: " + household
                + "\n    generation: 1\n    occupation: COOK\n    means: STEADY\n    life-stage: ADULT"
                + "\n    temperament: DIRECT\n    preference: CLEAR_TERMS\n" + extra;
    }

    private static String file(String... entries) {
        return "version: 1\nresidents:\n" + String.join("", entries);
    }

    private static final String OTTO = entry("otto-marsh", "Otto Marsh", "Marsh", "");
    private static final String VALID = file(OTTO, entry("bea-marsh", "Bea Marsh", "Marsh", ""));

    private static ResidentCast read(String text) {
        return ResidentCastFile.read(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static ResidentCast loadFrom(Path dir, String text) throws Exception {
        Files.writeString(dir.resolve(ResidentCastFile.FILE_NAME), text, StandardCharsets.UTF_8);
        return ResidentCastFile.load(dir, true);
    }

    private static String lines(String greeting) {
        return "    lines:\n      greeting:\n" + greeting;
    }

    @Test void theExampleFileStartsWithTheBuiltInThreeExactly() throws Exception {
        ResidentCast cast;
        try (var in = Files.newInputStream(EXAMPLE)) { cast = ResidentCastFile.read(in); }
        assertEquals(ResidentCast.Source.FILE, cast.source());
        assertEquals(9, cast.profiles().size());
        assertEquals(ResidentCatalogue.betaCast(), cast.profiles().subList(0, 3), "UUIDs, wardrobe keys and every enum");
        // Independent of the code's derivation: these are the UUIDs already saved on servers.
        for (String key : List.of("mara-vale", "ellis-ward", "nico-reed")) {
            UUID saved = UUID.nameUUIDFromBytes(("prosperitynpc:dev-cast:v1:" + key).getBytes(StandardCharsets.UTF_8));
            assertTrue(cast.contains(saved), key);
            assertTrue(ResidentCast.builtIn().contains(saved), key);
        }
        for (ResidentProfile builtIn : ResidentCatalogue.betaCast()) assertTrue(cast.lines(builtIn.id()).isEmpty());
        var otto = cast.byFirstName("Otto").orElseThrow();
        assertEquals(ResidentProfile.Occupation.COOK, otto.occupation());
        assertEquals("resident/v1/otto-marsh/everyday", otto.wardrobe().everyday());
        assertEquals("{name}. I cook. What do you need?", cast.lines(otto.id()).greeting().getFirst());
        assertEquals(List.of("mara", "ellis", "nico", "petra", "tobin", "otto", "bea", "june", "ruth"), cast.firstNames());
    }

    @Test void exampleSurnamesNeverShareAGeneratedHousehold() throws Exception {
        ResidentCast cast;
        try (var in = Files.newInputStream(EXAMPLE)) { cast = ResidentCastFile.read(in); }
        var generated = ResidentCatalogue.generatedHouseholds();
        assertTrue(generated.contains("Hale") && generated.contains("Ashby"), "positive control: the real list");
        for (ResidentProfile person : cast.profiles().subList(3, 9)) assertFalse(generated.contains(person.household()), person.name());
    }

    @Test void flagOffWithAFilePresentGivesExactlyTheBuiltInThree(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(ResidentCastFile.FILE_NAME), VALID, StandardCharsets.UTF_8);
        assertEquals(ResidentCast.builtIn(), ResidentCastFile.load(dir, false));
        var on = ResidentCastFile.load(dir, true);
        assertEquals(ResidentCast.Source.FILE, on.source());
        assertEquals(2, on.profiles().size());
    }

    @Test void aMissingFileWithTheFlagOnFallsBackToTheBuiltInThree(@TempDir Path dir) {
        var cast = ResidentCastFile.load(dir, true);
        assertEquals(ResidentCast.Source.FILE_REJECTED, cast.source());
        assertEquals(ResidentCatalogue.betaCast(), cast.profiles());
    }

    static Stream<String> rejected() {
        List<String> cases = new ArrayList<>();
        cases.add(VALID.replace("    generation: 1\n", "    generation: 1\n    nickname: Ot\n"));            // unknown field
        cases.add(VALID.replace("version: 1\n", "version: 1\nextra: true\n"));                           // unknown top field
        cases.add(VALID.replace("    means: STEADY\n", "    means: STEADY\n    means: STEADY\n"));        // duplicate YAML key
        cases.add(file(OTTO.replace("occupation: COOK", "occupation: &job COOK"),                        // scalar alias
                entry("bea-marsh", "Bea Marsh", "Marsh", "").replace("occupation: COOK", "occupation: *job")));
        cases.add("version: 1\nresidents:\n  - &otto\n    " + OTTO.substring(4) + "  - *otto\n");         // collection alias
        cases.add(file(OTTO.replace("name: Otto Marsh", "name: &n Otto Marsh")));                        // anchor
        cases.add(file(OTTO.replace("household: Marsh", "household: !!str Marsh")));                     // explicit tag
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 33; i++) many.append(entry("r" + i + "x", "R" + i + "x House", "House", ""));
        cases.add(file(many.toString()));                                                                // 33 residents
        cases.add(file(OTTO, entry("otto-marsh", "Bea Marsh", "Marsh", "")));                           // duplicate key
        cases.add(file(OTTO, entry("otto-two", "otto Hale", "Hale", "")));                              // duplicate first name
        cases.add(file(entry("otto-marsh", "Otto Marsh", "Hale", "")));                                 // household not surname
        cases.add(VALID.replaceFirst("COOK", "BAKER"));                                                  // bad enum
        cases.add(VALID.replaceFirst("COOK", "cook"));                                                   // enum names are exact
        cases.add(VALID.replaceFirst("generation: 1", "generation: 0"));
        cases.add(VALID.replaceFirst("generation: 1", "generation: 9"));
        cases.add(VALID.replaceFirst("generation: 1", "generation: \"1\""));
        cases.add(file(entry("cook", "Otto Marsh", "Marsh", "")));                                       // occupation key
        cases.add(file(entry("gardener", "Otto Marsh", "Marsh", "")));
        cases.add(file(entry("Otto", "Otto Marsh", "Marsh", "")));                                       // key pattern
        cases.add(file(entry("otto-marsh", "Otto  Marsh", "Marsh", "")));                                // two spaces
        cases.add(file(entry("otto-marsh", "Otto Marsh Jr", "Marsh", "")));
        cases.add(file(entry("otto-marsh", "Ot", "Ot", "")));
        cases.add(file(OTTO + lines("        - \"One line\"\n")));                                      // 1 line
        cases.add(file(OTTO + lines("        - \"a\"\n        - \"b\"\n        - \"c\"\n")));            // 3 lines
        cases.add(file(OTTO + lines("        - \"" + "x".repeat(161) + "\"\n        - \"b\"\n")));      // too long
        cases.add(file(OTTO + lines("        - \"\u00a7cRed\"\n        - \"b\"\n")));                    // section sign
        cases.add(file(OTTO + lines("        - \"[Bank] You received $500\"\n        - \"b\"\n")));     // dollar sign
        cases.add(file(OTTO + lines("        - \"Hi\\u0007\"\n        - \"b\"\n")));                    // control char
        cases.add(file(OTTO + lines("        - \"Hi {x}\"\n        - \"b\"\n")));                        // other token
        cases.add(file(OTTO + lines("        - \"Hi {name} {name}\"\n        - \"b\"\n")));              // token twice
        cases.add(file(OTTO + "    lines:\n      farewell:\n        - \"a\"\n        - \"b\"\n"));     // unknown line kind
        cases.add(file(OTTO + "    <<: {key: bea-marsh}\n"));                                          // merge key hides a duplicate
        cases.add(file(OTTO.replace("    generation: 1\n", "    <<: {generation: 1}\n")));                // merge key fills a field
        cases.add("<<: {version: 1}\nresidents:\n" + OTTO);                                              // merge key at the top
        cases.add(file(entry("otto-marsh", "$500 Marsh", "Marsh", "")));                                 // money sign in a name
        cases.add(file(entry("otto-marsh", "＄500 Marsh", "Marsh", "")));                           // fullwidth dollar in a name
        cases.add(file(entry("otto-marsh", "Ot{to} Marsh", "Marsh", "")));                               // brace in a name
        cases.add(file(entry("otto-marsh", "Otto Jr Marsh", "Marsh", "")));                        // non-breaking space
        cases.add(file(entry("otto-marsh", "Otto Jr Marsh", "Marsh", "")));                        // thin space
        cases.add(file(OTTO + lines("        - \"You got ＄500\"\n        - \"b\"\n")));          // fullwidth dollar
        cases.add(file(OTTO + lines("        - \"You got ﹩500\"\n        - \"b\"\n")));          // small dollar
        cases.add("version: 2\nresidents:\n" + OTTO);
        cases.add("version: 1\nresidents: []\n");
        cases.add("- just a list\n");
        cases.add("\ufeff" + VALID);
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("rejected")
    void everyRuleRejectsTheWholeFileAndFallsBackToTheBuiltInThree(String text) throws Exception {
        assertEquals(ResidentCast.Source.FILE, read(VALID).source(), "positive control");
        var error = assertThrows(IllegalArgumentException.class, () -> read(text));
        assertEquals("Invalid residents.yml", error.getMessage(), "no file contents in the message");
        assertNull(error.getCause());
        Path dir = Files.createTempDirectory("cast");
        try {
            var cast = loadFrom(dir, text);
            assertEquals(ResidentCast.Source.FILE_REJECTED, cast.source());
            assertEquals(ResidentCatalogue.betaCast(), cast.profiles());
            assertTrue(cast.lines().isEmpty());
        } finally {
            Files.deleteIfExists(dir.resolve(ResidentCastFile.FILE_NAME));
            Files.deleteIfExists(dir);
        }
    }

    @Test void aFileOneByteOverTheCapIsRejected(@TempDir Path dir) throws Exception {
        int cap = 128 * 1024; // the documented limit, independent of the constant
        String padded = VALID + "#" + "x".repeat(cap - VALID.length() - 2) + "\n";
        assertEquals(cap, padded.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(ResidentCast.Source.FILE, loadFrom(dir, padded).source(), "exactly 128 KiB still loads");
        assertEquals(ResidentCast.Source.FILE_REJECTED, loadFrom(dir, padded + " ").source());
        assertThrows(IllegalArgumentException.class, () -> read(padded + " "));
    }

    @Test void aSymlinkedFileIsRejected(@TempDir Path dir) throws Exception {
        Path target = dir.resolve("secret.yml");
        Files.writeString(target, VALID, StandardCharsets.UTF_8);
        try {
            Files.createSymbolicLink(dir.resolve(ResidentCastFile.FILE_NAME), target);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException unsupported) {
            assumeTrue(false, "this machine cannot create symlinks");
        }
        assertEquals(ResidentCast.Source.FILE_REJECTED, ResidentCastFile.load(dir, true).source());
        Files.delete(dir.resolve(ResidentCastFile.FILE_NAME));
        Files.copy(target, dir.resolve(ResidentCastFile.FILE_NAME));
        assertEquals(ResidentCast.Source.FILE, ResidentCastFile.load(dir, true).source(), "positive control");
    }

    @Test void aDirectoryInPlaceOfTheFileIsRejected(@TempDir Path dir) throws Exception {
        Files.createDirectory(dir.resolve(ResidentCastFile.FILE_NAME));
        assertEquals(ResidentCast.Source.FILE_REJECTED, ResidentCastFile.load(dir, true).source());
    }

    @Test void aQuotedLessThanPairInALineIsNotAMergeKey() {
        // Positive control for the merge-key rule: only a plain << scalar is a merge key.
        var cast = read(file(OTTO + lines("        - \"<<\"\n        - \"b\"\n")));
        assertEquals(ResidentCast.Source.FILE, cast.source());
    }

    @Test void namesWithPlainSpacesAndApostrophesStillLoad() {
        var cast = read(file(entry("otto-marsh", "O'Neil Marsh", "Marsh", "")));
        assertEquals("O'Neil Marsh", cast.profiles().getFirst().name());
    }

    @Test void firstNamesTheLookupFoldsTogetherCannotBothLoad() {
        // Positive control: each name loads on its own.
        assertEquals("Isa Marsh", read(file(entry("isa-marsh", "Isa Marsh", "Marsh", ""))).profiles().getFirst().name());
        assertEquals("ısa Marsh", read(file(entry("isa-marsh", "ısa Marsh", "Marsh", ""))).profiles().getFirst().name());
        // equalsIgnoreCase folds the dotless i to I, so /resident Isa could never reach the second one.
        assertThrows(IllegalArgumentException.class, () -> read(file(entry("isa-marsh", "Isa Marsh", "Marsh", ""),
                entry("dotless-marsh", "ısa Marsh", "Marsh", ""))));
    }

    @Test void firstNameLookupMatchesDev58() {
        var cast = ResidentCast.builtIn();
        assertEquals("Mara Vale", cast.byFirstName("MARA").orElseThrow().name());
        // dev.58 used equalsIgnoreCase, which folds a dotless i to I. toLowerCase would not.
        assertEquals("Nico Reed", cast.byFirstName("Nıco").orElseThrow().name());
        assertTrue(cast.byFirstName("Nic").isEmpty());
    }

    @Test void authoredLinesAreKeptPerResident() {
        var cast = read(file(OTTO + "    lines:\n      home:\n        - \"Clear terms, {name} says.\"\n        - \"b\"\n"));
        var otto = cast.byFirstName("otto").orElseThrow();
        assertTrue(cast.lines(otto.id()).greeting().isEmpty());
        assertEquals(List.of("Clear terms, {name} says.", "b"), cast.lines(otto.id()).home());
        assertEquals(ResidentLines.NONE, cast.lines(UUID.randomUUID()));
    }

    @Test void healthLinesNameTheSourceAndNeverTheContents() {
        assertEquals("Residents: built-in three. residents.yml is off.", ResidentCast.builtIn().healthLine());
        assertEquals("Residents: residents.yml was rejected. Using the built-in three.", ResidentCast.rejected().healthLine());
        assertEquals("Residents: 2 from residents.yml", read(VALID).healthLine());
    }
}

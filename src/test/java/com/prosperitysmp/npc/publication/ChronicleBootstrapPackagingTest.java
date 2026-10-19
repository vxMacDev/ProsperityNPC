package com.prosperitysmp.npc.publication;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChronicleBootstrapPackagingTest {
    private static final String CLASS = "com/prosperitysmp/npc/publication/bootstrap/ChronicleBootstrap";

    private static Path jar(Path folder, String name, Map<String, byte[]> entries) throws IOException {
        Path path = folder.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(path))) {
            for (var entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return path.toRealPath();
    }

    private static Map<String, byte[]> entries(String... namesAndContents) {
        Map<String, byte[]> map = new LinkedHashMap<>();
        for (int i = 0; i < namesAndContents.length; i += 2) {
            map.put(namesAndContents[i], namesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
        }
        return map;
    }

    private static String run(Path input, Path output) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            Path parent = output.getParent();
            Path resolved = Files.exists(parent) ? parent.toRealPath().resolve(output.getFileName()) : output;
            ChronicleBootstrapPackaging.main(new String[]{input.toString(), resolved.toString()});
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private static List<String> names(Path jar) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            List<String> names = new ArrayList<>();
            file.stream().map(JarEntry::getName).forEach(names::add);
            return names;
        }
    }

    @Test
    void anythingButTwoArgumentsIsRefusedAndWritesNothing(@TempDir Path folder) {
        for (String[] bad : new String[][]{{}, {"one"}, {"a", "b", "c"}}) {
            var failure = assertThrows(IllegalArgumentException.class, () -> ChronicleBootstrapPackaging.main(bad));
            assertEquals("Expected <built NPC jar> <new skinny bootstrap jar>", failure.getMessage());
        }
        assertEquals(0, folder.toFile().list().length);
    }

    @Test
    void onlyTheBootstrapClassAndItsNestedClassesAreCopiedInNameOrder(@TempDir Path folder) throws Exception {
        Path input = jar(folder, "npc.jar", entries(
                "plugin.yml", "name: x",
                "com/prosperitysmp/npc/Other.class", "other",
                CLASS + ".class", "main-bytes",
                CLASS + "$Proof.class", "proof-bytes",
                CLASS + "$Input.class", "input-bytes",
                CLASS + "Extra.class", "not nested",
                CLASS + "$Note.txt", "not a class"));
        Path output = folder.resolve("boot.jar");
        run(input, output);
        assertEquals(List.of("META-INF/MANIFEST.MF", CLASS + "$Input.class", CLASS + "$Proof.class", CLASS + ".class"), names(output));
        try (JarFile file = new JarFile(output.toFile())) {
            assertEquals("input-bytes", new String(file.getInputStream(file.getEntry(CLASS + "$Input.class")).readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("main-bytes", new String(file.getInputStream(file.getEntry(CLASS + ".class")).readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void theManifestNamesTheBootstrapClassAsThePremainClass(@TempDir Path folder) throws Exception {
        Path input = jar(folder, "npc.jar", entries(CLASS + ".class", "x"));
        Path output = folder.resolve("boot.jar");
        run(input, output);
        try (JarFile file = new JarFile(output.toFile())) {
            assertEquals("com.prosperitysmp.npc.publication.bootstrap.ChronicleBootstrap",
                    file.getManifest().getMainAttributes().getValue("Premain-Class"));
            assertEquals("1.0", file.getManifest().getMainAttributes().getValue("Manifest-Version"));
            assertEquals(null, file.getManifest().getMainAttributes().getValue("Agent-Class"));
        }
    }

    @Test
    void theHashPrintedIsTheSha256OfTheJarThatWasWritten(@TempDir Path folder) throws Exception {
        Path input = jar(folder, "npc.jar", entries(CLASS + ".class", "x"));
        Path output = folder.resolve("boot.jar");
        String printed = run(input, output);
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(output)));
        assertEquals("Bootstrap sha256=" + expected, printed.strip());
    }

    @Test
    void aJarWithoutTheBootstrapClassIsRefused(@TempDir Path folder) throws Exception {
        Path input = jar(folder, "npc.jar", entries("plugin.yml", "x", CLASS + "$Proof.class", "nested only"));
        var failure = assertThrows(IllegalArgumentException.class, () -> run(input, folder.resolve("boot.jar")));
        assertEquals("Bootstrap classes absent or exceed bound", failure.getMessage());
    }

    @Test
    void moreThanEightBootstrapClassesAreRefused(@TempDir Path folder) throws Exception {
        Map<String, byte[]> many = entries(CLASS + ".class", "m");
        for (int i = 0; i < 8; i++) many.put(CLASS + "$N" + i + ".class", new byte[]{1});
        Path input = jar(folder, "npc.jar", many);
        var failure = assertThrows(IllegalArgumentException.class, () -> run(input, folder.resolve("boot.jar")));
        assertEquals("Bootstrap classes absent or exceed bound", failure.getMessage());
    }

    @Test
    void exactlyEightBootstrapClassesAreAccepted(@TempDir Path folder) throws Exception {
        Map<String, byte[]> eight = entries(CLASS + ".class", "m");
        for (int i = 0; i < 7; i++) eight.put(CLASS + "$N" + i + ".class", new byte[]{1});
        Path output = folder.resolve("boot.jar");
        run(jar(folder, "npc.jar", eight), output);
        assertEquals(9, names(output).size());
    }

    @Test
    void aClassOfTwoHundredSixtyTwoThousandOneHundredFortyFourBytesIsAcceptedAndOneByteMoreIsNot(@TempDir Path folder) throws Exception {
        Map<String, byte[]> limit = new LinkedHashMap<>();
        limit.put(CLASS + ".class", new byte[262144]);
        Path accepted = folder.resolve("ok.jar");
        run(jar(folder, "in-ok.jar", limit), accepted);
        try (JarFile file = new JarFile(accepted.toFile())) {
            assertArrayEquals(new byte[262144], file.getInputStream(file.getEntry(CLASS + ".class")).readAllBytes());
        }
        Map<String, byte[]> over = new LinkedHashMap<>();
        over.put(CLASS + ".class", new byte[262145]);
        Path input = jar(folder, "in-over.jar", over);
        var failure = assertThrows(IllegalArgumentException.class, () -> run(input, folder.resolve("over.jar")));
        assertEquals("Bootstrap class exceeds bound", failure.getMessage());
    }

    @Test
    void anEmptyBootstrapClassIsRefused(@TempDir Path folder) throws Exception {
        Map<String, byte[]> empty = new LinkedHashMap<>();
        empty.put(CLASS + ".class", new byte[0]);
        Path input = jar(folder, "npc.jar", empty);
        var failure = assertThrows(IllegalArgumentException.class, () -> run(input, folder.resolve("boot.jar")));
        assertEquals("Bootstrap class exceeds bound", failure.getMessage());
    }

    @Test
    void anExistingOutputIsNeverOverwritten(@TempDir Path folder) throws Exception {
        Path input = jar(folder, "npc.jar", entries(CLASS + ".class", "x"));
        Path output = folder.resolve("boot.jar");
        Files.writeString(output, "precious");
        assertThrows(FileAlreadyExistsException.class, () -> run(input, output));
        assertEquals("precious", Files.readString(output));
    }

    @Test
    void aMissingInputIsRefusedAndNothingIsWritten(@TempDir Path folder) {
        Path output = folder.resolve("boot.jar");
        assertThrows(NoSuchFileException.class, () -> run(folder.resolve("absent.jar"), output));
        assertFalse(Files.exists(output));
    }

    @Test
    void anOutputInAFolderThatDoesNotExistIsRefusedAndNothingIsWritten(@TempDir Path folder) throws Exception {
        Path input = jar(folder, "npc.jar", entries(CLASS + ".class", "x"));
        assertThrows(NoSuchFileException.class, () -> run(input, folder.resolve("missing").resolve("boot.jar")));
        assertFalse(Files.exists(folder.resolve("missing")));
    }

    @Test
    void aDirectoryIsNotAnInputJar(@TempDir Path folder) throws Exception {
        Path directory = Files.createDirectory(folder.resolve("dir")).toRealPath();
        var failure = assertThrows(IllegalArgumentException.class, () -> run(directory, folder.resolve("boot.jar")));
        assertEquals("Invalid artifact path", failure.getMessage());
        assertFalse(Files.exists(folder.resolve("boot.jar")));
    }

    @Test
    void aRefusedPackagingLeavesNoOutputFile(@TempDir Path folder) throws Exception {
        Path input = jar(folder, "npc.jar", entries("plugin.yml", "x"));
        Path output = folder.resolve("boot.jar");
        assertThrows(IllegalArgumentException.class, () -> run(input, output));
        assertFalse(Files.exists(output));
    }
}

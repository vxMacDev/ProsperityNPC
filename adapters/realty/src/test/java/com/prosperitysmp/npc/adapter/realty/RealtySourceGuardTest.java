package com.prosperitysmp.npc.adapter.realty;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks that read the adapter's own files as text: the descriptor, the build file and the vendor source, which
 * these tests cannot compile without Realty's jars.
 */
class RealtySourceGuardTest {
    private static final Path ROOT = Path.of("");
    private static final Path VENDOR_VIEW = ROOT.resolve("src/vendor/java/com/prosperitysmp/npc/adapter/realty/RealRealtyView.java");
    private static final Path VENDOR_PLUGIN = ROOT.resolve("src/vendor/java/com/prosperitysmp/npc/adapter/realty/RealtyAdapterPlugin.java");

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    @Test void theDescriptorDependsOnProsperityNpcAndRealty() throws IOException {
        String text = read(ROOT.resolve("src/main/resources/plugin.yml"));
        assertTrue(text.startsWith("name: ProsperityNPC-Realty\n"), text);
        String depend = text.lines().filter(line -> line.startsWith("depend:")).findFirst().orElseThrow();
        assertTrue(depend.contains("ProsperityNPC") && depend.contains("Realty"), depend);
        assertTrue(text.contains("Unverified."), text);
        assertFalse(text.toLowerCase(Locale.ROOT).contains("tested"), text);
        assertFalse(Files.exists(ROOT.resolve("src/main/resources/paper-plugin.yml")), "one descriptor only");
    }

    @Test void theMainSourceNamesNoRealtyWorldGuardOrAdventureClass() throws IOException {
        try (Stream<Path> files = Files.walk(ROOT.resolve("src/main/java"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String text = read(file);
                for (String forbidden : List.of("io.github.md5sha256", "com.sk89q", "net.kyori")) {
                    assertFalse(text.contains(forbidden), file + " names " + forbidden);
                }
            }
        }
    }

    @Test void theVendorViewBindsEveryNamedEventAndNothingElse() throws IOException {
        assertEquals(14, BoundEvents.NAMES.size());
        assertEquals(14, new HashSet<>(BoundEvents.NAMES).size());
        String text = read(VENDOR_VIEW);
        for (String name : BoundEvents.NAMES) assertTrue(text.contains("bind(" + name + ".class,"), name);
        var bound = Pattern.compile("bind\\((\\w+)\\.class,").matcher(text).results().map(m -> m.group(1)).toList();
        assertEquals(BoundEvents.NAMES.size(), bound.size());
        assertEquals(new HashSet<>(BoundEvents.NAMES), new HashSet<>(bound));
    }

    @Test void theVendorViewNeverCancelsAndTheDisableListenerLivesInThePlugin() throws IOException {
        String view = read(VENDOR_VIEW);
        assertFalse(view.contains("setCancelled"));
        assertFalse(view.contains("PluginDisableEvent"));
        assertTrue(read(VENDOR_PLUGIN).contains("PluginDisableEvent"));
    }

    @Test void theBuildNamesOnlyTheTwoRealtyApis() throws IOException {
        String build = read(ROOT.resolve("build.gradle"));
        assertTrue(build.contains("'io.github.md5sha256:realty-paper-api:2.0.0'"));
        assertTrue(build.contains("'io.github.md5sha256:realty-backend-api:2.0.0'"));
        assertEquals(2, build.lines().filter(line -> line.contains("io.github.md5sha256:")).count());
        assertTrue(build.contains("archiveBaseName = 'ProsperityNPC-Realty'"));
        assertFalse(build.contains("sourceOnly"));
    }

    @Test void theStatusTextClaimsNoRealPlayerTesting() {
        assertTrue(RealtyPropertyBackend.VERIFICATION.contains("test server"));
        assertTrue(RealtyPropertyBackend.VERIFICATION.contains("not tested with real players"));
        assertEquals("unverified", new RealtySubject().backend.label());
    }
}

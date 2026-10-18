package com.prosperitysmp.npc.economy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Vault is optional: no main-source class outside economy/vault may name it, or the plugin would fail to load on a
 * server without Vault. And the reserve key is read in one place only, live.
 */
class NoVaultOutsideVaultPackageTest {
    private static final Path MAIN = Path.of("src/main/java");

    static List<String> filesMentioning(Path root, String needle) throws IOException {
        var out = new ArrayList<String>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList())
                if (Files.readString(file).contains(needle)) out.add(root.relativize(file).toString().replace('\\', '/'));
        }
        return out;
    }

    static boolean mentions(String code, String needle) {
        return code.contains(needle);
    }

    @Test void onlyTheVaultPackageNamesVault() throws IOException {
        // Positive control: the scanner finds the name in a sample.
        assertTrue(mentions("import net.milkbowl.vault.economy.Economy;", "net.milkbowl"));
        var found = filesMentioning(MAIN, "net.milkbowl");
        assertFalse(found.isEmpty(), "the Vault source itself must be found");
        for (String file : found)
            assertTrue(file.startsWith("com/prosperitysmp/npc/economy/vault/"), file + " names Vault");
    }

    @Test void theApiNeverNamesVault() throws IOException {
        assertEquals(List.of(), filesMentioning(Path.of("api/src/main/java"), "net.milkbowl"));
    }
}

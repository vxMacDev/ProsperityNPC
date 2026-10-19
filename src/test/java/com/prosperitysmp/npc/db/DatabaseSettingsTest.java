package com.prosperitysmp.npc.db;

import com.prosperitysmp.npc.CoreTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseSettingsTest {
    private final List<String> log = new ArrayList<>();

    private DatabaseSettings load(Path data, String yaml) {
        return DatabaseSettings.load(CoreTestSupport.plugin(data, CoreTestSupport.yaml(yaml), log));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "   ", "\t", "\n"})
    void aBlankPasswordMeansNotConfigured(String password) {
        assertFalse(new DatabaseSettings("h", 3306, "d", "u", password, 4, 5000).isConfigured());
    }

    @ParameterizedTest
    @ValueSource(strings = {"x", "p4ss", " padded ", "0"})
    void anyNonBlankPasswordMeansConfigured(String password) {
        assertTrue(new DatabaseSettings("h", 3306, "d", "u", password, 4, 5000).isConfigured());
    }

    @Test
    void theJdbcUrlNamesHostPortAndDatabase() {
        assertEquals("jdbc:mariadb://127.0.0.1:3306/prosperity_npc",
                new DatabaseSettings("127.0.0.1", 3306, "prosperity_npc", "u", "p", 6, 10000).jdbcUrl());
        assertEquals("jdbc:mariadb://db.internal:13306/other",
                new DatabaseSettings("db.internal", 13306, "other", "u", "p", 6, 10000).jdbcUrl());
    }

    @Test
    void theJdbcUrlCarriesNeitherTheUserNorThePassword() {
        String url = new DatabaseSettings("h", 1, "d", "theuser", "thepassword", 1, 1000).jdbcUrl();
        assertFalse(url.contains("theuser"));
        assertFalse(url.contains("thepassword"));
    }

    @Test
    void thePasswordIsNeverPartOfTheTextForm() {
        String text = new DatabaseSettings("h", 1, "d", "theuser", "s3cret-value", 1, 1000).toString();
        assertFalse(text.contains("s3cret-value"), text);
    }

    @Test
    void withNoDatabaseSectionTheDefaultsApplyAndTheServerIsNotConfigured(@TempDir Path data) {
        DatabaseSettings settings = load(data, "other: 1\n");
        assertEquals("127.0.0.1", settings.host());
        assertEquals(3306, settings.port());
        assertEquals("prosperity_npc", settings.database());
        assertEquals("npcuser", settings.user());
        assertEquals("", settings.password());
        assertEquals(6, settings.poolSize());
        assertEquals(10_000L, settings.connectionTimeoutMs());
        assertFalse(settings.isConfigured());
    }

    @Test
    void everyConfiguredValueIsReadFromItsKey(@TempDir Path data) {
        DatabaseSettings settings = load(data, """
                database:
                  host: db.example
                  port: 3307
                  name: npc_live
                  user: runner
                  password: from-config
                  pool-size: 9
                  connection-timeout-ms: 4500
                """);
        assertEquals(new DatabaseSettings("db.example", 3307, "npc_live", "runner", "from-config", 9, 4500L), settings);
        assertTrue(settings.isConfigured());
    }

    @Test
    void aPasswordInTheConfigWinsOverThePasswordFile(@TempDir Path data) throws IOException {
        Files.writeString(data.resolve("db-password.txt"), "from-file\n");
        assertEquals("from-config", load(data, "database:\n  password: from-config\n").password());
    }

    @Test
    void aBlankConfigPasswordFallsBackToTheTrimmedPasswordFile(@TempDir Path data) throws IOException {
        Files.writeString(data.resolve("db-password.txt"), "  from-file \r\n\n");
        DatabaseSettings settings = load(data, "database:\n  password: '   '\n");
        assertEquals("from-file", settings.password());
        assertTrue(settings.isConfigured());
    }

    @Test
    void withNoDatabaseSectionThePasswordFileIsStillUsed(@TempDir Path data) throws IOException {
        Files.writeString(data.resolve("db-password.txt"), "only-file");
        assertEquals("only-file", load(data, "x: 1\n").password());
    }

    @Test
    void aMissingPasswordFileLeavesAnEmptyPassword(@TempDir Path data) {
        DatabaseSettings settings = load(data, "database:\n  host: h\n");
        assertEquals("", settings.password());
        assertFalse(settings.isConfigured());
        assertTrue(log.isEmpty());
    }

    @Test
    void aPasswordFileThatCannotBeReadWarnsWithoutRevealingAnything(@TempDir Path data) throws IOException {
        Files.createDirectory(data.resolve("db-password.txt"));
        DatabaseSettings settings = load(data, "database:\n  host: h\n");
        assertEquals("", settings.password());
        assertEquals(1, log.size());
        assertTrue(log.get(0).startsWith("WARNING: Could not read db-password.txt: "), log.get(0));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, -100})
    void aPoolSizeBelowOneBecomesOne(int size, @TempDir Path data) {
        assertEquals(1, load(data, "database:\n  pool-size: " + size + "\n").poolSize());
    }

    @Test
    void aPoolSizeOfOneIsKept(@TempDir Path data) {
        assertEquals(1, load(data, "database:\n  pool-size: 1\n").poolSize());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -5, 1, 999})
    void aConnectionTimeoutBelowOneSecondBecomesOneSecond(long timeout, @TempDir Path data) {
        assertEquals(1000L, load(data, "database:\n  connection-timeout-ms: " + timeout + "\n").connectionTimeoutMs());
    }

    @ParameterizedTest
    @ValueSource(longs = {1000, 1001, 30000, 3000000000L})
    void aConnectionTimeoutOfOneSecondOrMoreIsKept(long timeout, @TempDir Path data) {
        assertEquals(timeout, load(data, "database:\n  connection-timeout-ms: " + timeout + "\n").connectionTimeoutMs());
    }

    @Test
    void aSettingsValueIsEqualToAnotherWithTheSameParts() {
        assertEquals(new DatabaseSettings("h", 1, "d", "u", "p", 2, 3000),
                new DatabaseSettings("h", 1, "d", "u", "p", 2, 3000));
    }
}

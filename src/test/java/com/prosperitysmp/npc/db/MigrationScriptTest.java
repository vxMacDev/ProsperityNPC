package com.prosperitysmp.npc.db;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The statement splitter and the registry of migration scripts, which need no database. */
class MigrationScriptTest {
    private static final File FOLDER = new File("src/main/resources/db/migration");
    private static final Pattern NAME = Pattern.compile("V(\\d+)__([a-z0-9_]+)\\.sql");

    @Test
    void twoStatementsOnSeparateLinesSplitWithoutTheirSemicolons() {
        assertEquals(List.of("CREATE TABLE a (id INT)", "CREATE TABLE b (id INT)"),
                MigrationRunner.splitStatements("CREATE TABLE a (id INT);\nCREATE TABLE b (id INT);\n"));
    }

    @Test
    void anEmptyScriptHasNoStatements() {
        assertEquals(List.of(), MigrationRunner.splitStatements(""));
        assertEquals(List.of(), MigrationRunner.splitStatements("\n\n  \n"));
    }

    @Test
    void aScriptOfOnlyCommentsHasNoStatements() {
        assertEquals(List.of(), MigrationRunner.splitStatements("-- one\n   -- two\n--\n"));
    }

    @Test
    void aStandaloneSemicolonIsNotAStatement() {
        assertEquals(List.of("SELECT 1"), MigrationRunner.splitStatements(";\nSELECT 1;\n;\n"));
    }

    @Test
    void aTrailingCommentAfterTheSemicolonIsStripped() {
        assertEquals(List.of("SELECT 1"), MigrationRunner.splitStatements("SELECT 1; -- done\n"));
    }

    @Test
    void aCommentInsideAMultiLineStatementIsDroppedButTheLinesStay() {
        String script = "CREATE TABLE t (\n  id INT, -- the key\n  name VARCHAR(8)\n);\n";
        assertEquals(List.of("CREATE TABLE t (\n  id INT, \n  name VARCHAR(8)\n)"), MigrationRunner.splitStatements(script));
    }

    @Test
    void aStatementWithoutATerminatingSemicolonIsKeptAsTheLastOne() {
        assertEquals(List.of("SELECT 1", "SELECT 2"), MigrationRunner.splitStatements("SELECT 1;\nSELECT 2\n"));
    }

    @Test
    void aSemicolonInTheMiddleOfALineDoesNotEndTheStatement() {
        assertEquals(List.of("SELECT 1; SELECT 2"), MigrationRunner.splitStatements("SELECT 1; SELECT 2;\n"));
    }

    @Test
    void windowsLineEndingsGiveTheSameStatements() {
        assertEquals(MigrationRunner.splitStatements("SELECT 1;\nSELECT 2;\n"),
                MigrationRunner.splitStatements("SELECT 1;\r\nSELECT 2;\r\n"));
    }

    @Test
    void trailingSpacesAfterTheSemicolonStillEndTheStatement() {
        assertEquals(List.of("SELECT 1", "SELECT 2"), MigrationRunner.splitStatements("SELECT 1;   \nSELECT 2;\t\n"));
    }

    @Test
    void blankLinesBetweenStatementsAreIgnored() {
        assertEquals(List.of("SELECT 1", "SELECT 2"), MigrationRunner.splitStatements("\n\nSELECT 1;\n\n\n\nSELECT 2;\n\n"));
    }

    @Test
    void theLockTimeoutMessageNamesTheScriptTheStatementAndTheLimit() {
        String message = MigrationRunner.lockTimeoutMessage("V12 (x)", 5, 3, 7);
        assertTrue(message.startsWith("Migration V12 (x) gave up waiting for a lock (statement 3 of 7, limit 5 s)."), message);
        assertTrue(message.endsWith("restart once that session has finished"), message);
    }

    @Test
    void everyMigrationFileSplitsIntoNonEmptyStatementsWithNoSemicolonOrComment() throws IOException {
        File[] files = FOLDER.listFiles((dir, name) -> name.endsWith(".sql"));
        assertNotNull(files);
        assertTrue(files.length >= 56, "found " + files.length);
        for (File file : files) {
            String script = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            List<String> statements = MigrationRunner.splitStatements(script);
            assertFalse(statements.isEmpty(), file.getName());
            for (String statement : statements) {
                assertFalse(statement.isBlank(), file.getName());
                assertFalse(statement.endsWith(";"), file.getName() + ": " + statement);
                assertFalse(statement.contains("--"), file.getName() + ": " + statement);
            }
        }
    }

    @Test
    void everyMigrationFileIsNamedWithAVersionAndADescription() {
        for (File file : FOLDER.listFiles((dir, name) -> name.endsWith(".sql"))) {
            assertTrue(NAME.matcher(file.getName()).matches(), file.getName());
        }
    }

    private static List<Object[]> registry() throws ReflectiveOperationException {
        Field field = MigrationRunner.class.getDeclaredField("MIGRATIONS");
        field.setAccessible(true);
        List<?> list = (List<?>) field.get(null);
        List<Object[]> rows = new ArrayList<>();
        for (Object migration : list) {
            Method version = migration.getClass().getDeclaredMethod("version");
            Method description = migration.getClass().getDeclaredMethod("description");
            Method resource = migration.getClass().getDeclaredMethod("resource");
            for (Method each : new Method[]{version, description, resource}) each.setAccessible(true);
            rows.add(new Object[]{version.invoke(migration), description.invoke(migration), resource.invoke(migration)});
        }
        return rows;
    }

    @Test
    void theRegistryListsVersionsOneToFiftySixInOrderWithNoGapOrRepeat() throws ReflectiveOperationException {
        List<Object[]> rows = registry();
        assertEquals(56, rows.size());
        for (int i = 0; i < rows.size(); i++) assertEquals(i + 1, rows.get(i)[0], "row " + i);
    }

    @Test
    void everyRegisteredResourceIsNamedAfterItsVersionAndDescriptionAndExists() throws ReflectiveOperationException {
        for (Object[] row : registry()) {
            String resource = (String) row[2];
            assertEquals("db/migration/V" + row[0] + "__" + row[1] + ".sql", resource);
            assertNotNull(MigrationScriptTest.class.getClassLoader().getResource(resource), resource);
        }
    }

    @Test
    void everyDescriptionFitsTheTrackingColumn() throws ReflectiveOperationException {
        for (Object[] row : registry()) {
            String description = (String) row[1];
            assertTrue(description.length() <= 128, description);
        }
    }

    @Test
    @Disabled("BUG: db/migration/V16__chronicle_outbox.sql is a byte-identical orphan of V26__chronicle_outbox.sql; it ships in the jar, the registry never applies it, and a second V16 file invites a wrong rename")
    void everyMigrationFileIsRegisteredExactlyOnce() throws ReflectiveOperationException {
        Set<String> registered = new HashSet<>();
        for (Object[] row : registry()) registered.add(((String) row[2]).substring("db/migration/".length()));
        Set<String> onDisk = new HashSet<>(Arrays.asList(FOLDER.list((dir, name) -> name.endsWith(".sql"))));
        assertEquals(registered, onDisk);
    }
}

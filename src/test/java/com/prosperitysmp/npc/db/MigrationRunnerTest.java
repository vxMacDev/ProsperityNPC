package com.prosperitysmp.npc.db;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The parts of the migration runner that need no database. */
class MigrationRunnerTest {

    @Test
    void theLockWaitIsShorterThanThePoolsSocketTimeoutSoTheServerGivesUpFirst() {
        // Database.connect sets a 10 second socket timeout; a longer lock wait would kill the connection instead.
        assertTrue(MigrationRunner.LOCK_WAIT_SECONDS >= 1 && MigrationRunner.LOCK_WAIT_SECONDS < 10);
    }

    @Test
    void onlyAMetadataLockTimeoutCountsAsOne() {
        assertTrue(MigrationRunner.isLockTimeout(new SQLException("Lock wait timeout exceeded", "HY000", 1205)));
        assertFalse(MigrationRunner.isLockTimeout(new SQLException("Deadlock found", "40001", 1213)));
        assertFalse(MigrationRunner.isLockTimeout(new SQLException("Duplicate column name", "42S21", 1060)));
    }

    @Test
    void v39NamesItsDdlAlgorithmSoItNeverQuietlyCopiesATable() throws Exception {
        String sql;
        try (var in = MigrationRunnerTest.class.getResourceAsStream("/db/migration/V39__nav_surface.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        var statements = MigrationRunner.splitStatements(sql);
        assertEquals(2, statements.size());
        for (String statement : statements) {
            assertTrue(statement.contains("ALGORITHM=INSTANT"), statement);
        }
        assertTrue(statements.get(0).contains("road_width SMALLINT UNSIGNED NULL"), statements.get(0));
    }

    /** A connection that records every statement and times out the ones that start with "LOCKED". */
    private static Connection fakeConnection(List<String> ran) {
        return (Connection) Proxy.newProxyInstance(MigrationRunnerTest.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("createStatement")) {
                        return Proxy.newProxyInstance(MigrationRunnerTest.class.getClassLoader(), new Class<?>[]{Statement.class},
                                (p, m, a) -> switch (m.getName()) {
                                    case "executeQuery" -> Proxy.newProxyInstance(MigrationRunnerTest.class.getClassLoader(),
                                            new Class<?>[]{ResultSet.class}, (r, rm, ra) -> switch (rm.getName()) {
                                                case "next" -> true;
                                                case "getInt" -> 50;
                                                default -> null;
                                            });
                                    case "execute" -> {
                                        ran.add((String) a[0]);
                                        if (((String) a[0]).startsWith("LOCKED")) {
                                            throw new SQLException("Lock wait timeout exceeded", "HY000", 1205);
                                        }
                                        yield false;
                                    }
                                    default -> null;
                                });
                    }
                    return null;
                });
    }

    @Test
    void aLockTimeoutOnALaterStatementSaysEarlierOnesMayAlreadyBeApplied() {
        List<String> ran = new ArrayList<>();
        var failure = assertThrows(SQLException.class, () -> MigrationRunner.applyStatements(fakeConnection(ran),
                "V39 (nav_surface)", List.of("ALTER one", "LOCKED two"), 5));
        assertEquals(1205, failure.getErrorCode());
        String message = failure.getMessage();
        assertTrue(message.contains("statement 2 of 2"), message);
        assertTrue(message.contains("V39 (nav_surface)"), message);
        assertTrue(message.contains("row lock"), "1205 is also an InnoDB row-lock wait: " + message);
        assertTrue(message.contains("already be applied"), message);
        assertFalse(message.contains("Nothing was left"), message);
        assertTrue(ran.contains("ALTER one"), "the first statement had already run");
    }

    @Test
    void aFirstStatementTimeoutIsNamedAsStatementOne() {
        var failure = assertThrows(SQLException.class, () -> MigrationRunner.applyStatements(fakeConnection(new ArrayList<>()),
                "V9", List.of("LOCKED one", "ALTER two"), 5));
        assertTrue(failure.getMessage().contains("statement 1 of 2"), failure.getMessage());
    }

    @Test
    void anyOtherFailureIsRethrownUntouched() {
        var original = new SQLException("Duplicate column name", "42S21", 1060);
        Connection c = (Connection) Proxy.newProxyInstance(MigrationRunnerTest.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("createStatement")) return null;
                    return Proxy.newProxyInstance(MigrationRunnerTest.class.getClassLoader(), new Class<?>[]{Statement.class},
                            (p, m, a) -> switch (m.getName()) {
                                case "executeQuery" -> Proxy.newProxyInstance(MigrationRunnerTest.class.getClassLoader(),
                                        new Class<?>[]{ResultSet.class}, (r, rm, ra) -> rm.getName().equals("next") ? Boolean.TRUE
                                        : rm.getName().equals("getInt") ? (Object) 50 : null);
                                case "execute" -> throw original;
                                default -> null;
                            });
                });
        assertEquals(original, assertThrows(SQLException.class,
                () -> MigrationRunner.applyStatements(c, "V9", List.of("ALTER one"), 5)));
    }
}

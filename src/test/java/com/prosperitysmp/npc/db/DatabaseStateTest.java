package com.prosperitysmp.npc.db;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The database wrapper and snapshot guards that need no running server. */
class DatabaseStateTest {
    private static final String REFUSAL = "Evidence requires an active repeatable-read transaction";

    private static Connection connection(boolean closed, boolean autoCommit, int isolation, SQLWarning warning) {
        return (Connection) Proxy.newProxyInstance(DatabaseStateTest.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isClosed" -> closed;
                    case "getAutoCommit" -> autoCommit;
                    case "getTransactionIsolation" -> isolation;
                    case "getWarnings" -> warning;
                    default -> throw new AssertionError("Unexpected connection call: " + method);
                });
    }

    @Test
    void anOpenRepeatableReadTransactionWithoutWarningsIsAccepted() throws SQLException {
        ReadSnapshot.requireTransaction(connection(false, false, Connection.TRANSACTION_REPEATABLE_READ, null));
    }

    @Test
    void aClosedConnectionIsRefused() {
        var failure = assertThrows(SQLException.class, () ->
                ReadSnapshot.requireTransaction(connection(true, false, Connection.TRANSACTION_REPEATABLE_READ, null)));
        assertEquals(REFUSAL, failure.getMessage());
    }

    @Test
    void anAutocommitConnectionIsRefused() {
        var failure = assertThrows(SQLException.class, () ->
                ReadSnapshot.requireTransaction(connection(false, true, Connection.TRANSACTION_REPEATABLE_READ, null)));
        assertEquals(REFUSAL, failure.getMessage());
    }

    @Test
    void everyIsolationLevelOtherThanRepeatableReadIsRefused() {
        for (int level : new int[]{Connection.TRANSACTION_READ_UNCOMMITTED, Connection.TRANSACTION_READ_COMMITTED,
                Connection.TRANSACTION_SERIALIZABLE, Connection.TRANSACTION_NONE}) {
            var failure = assertThrows(SQLException.class, () ->
                    ReadSnapshot.requireTransaction(connection(false, false, level, null)), "level " + level);
            assertEquals(REFUSAL, failure.getMessage());
        }
    }

    @Test
    void aConnectionCarryingAWarningIsRefused() {
        var failure = assertThrows(SQLException.class, () -> ReadSnapshot.requireTransaction(
                connection(false, false, Connection.TRANSACTION_REPEATABLE_READ, new SQLWarning("Data truncated"))));
        assertEquals(REFUSAL, failure.getMessage());
    }

    @Test
    void aNullConnectionIsRefused() {
        assertThrows(NullPointerException.class, () -> ReadSnapshot.requireTransaction(null));
    }

    @Test
    void openingASnapshotNeedsADatabaseAndAClock() {
        assertThrows(NullPointerException.class, () -> ReadSnapshot.open(null, Clock.systemUTC()));
        assertThrows(NullPointerException.class, () -> ReadSnapshot.open(new Database(), null));
    }

    @Test
    void aSnapshotOfADatabaseThatWasNeverConnectedFailsBeforeAnyWork() {
        var failure = assertThrows(SQLException.class, () -> ReadSnapshot.open(new Database(), Clock.systemUTC()));
        assertEquals("database not connected", failure.getMessage());
    }

    @Test
    void aDatabaseThatWasNeverConnectedReportsDown() {
        Database database = new Database();
        assertFalse(database.isConnected());
        assertEquals(Database.Status.down(), database.status());
    }

    @Test
    void theDownStatusIsNotConnectedNotHealthyAndHasNoSchema() {
        Database.Status down = Database.Status.down();
        assertFalse(down.connected());
        assertFalse(down.healthy());
        assertEquals(-1, down.schemaVersion());
        assertEquals(-1, down.tableCount());
    }

    @Test
    void askingForAConnectionBeforeConnectingFailsWithTheExactMessage() {
        var failure = assertThrows(SQLException.class, () -> new Database().getConnection());
        assertEquals("database not connected", failure.getMessage());
    }

    @Test
    void closingADatabaseThatWasNeverConnectedIsHarmlessAndRepeatable() {
        Database database = new Database();
        database.close();
        database.close();
        assertFalse(database.isConnected());
    }

    @Test
    void statusRecordsCompareByTheirParts() {
        assertEquals(new Database.Status(true, true, 56, 80), new Database.Status(true, true, 56, 80));
        assertNotEquals(new Database.Status(true, true, 56, 80), new Database.Status(true, false, 56, 80));
    }
}

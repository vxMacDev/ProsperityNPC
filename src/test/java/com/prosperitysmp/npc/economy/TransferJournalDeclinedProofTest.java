package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.economy.TransferProof.Observation;
import com.prosperitysmp.npc.economy.TransferProof.Refusal;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A declined row's proof from stored evidence alone. Only a debit that is neither a proved no-movement nor followed by
 * a credit has to read the database for a legacy refusal resolution; the connection fails the test on any other read.
 */
class TransferJournalDeclinedProofTest {
    private static final UUID ATTEMPT = new UUID(3, 3);
    private static final Observation NO_MOVEMENT = new Observation(true, false, 0.0, 100.0, 100.0, false);
    private static final Observation MOVED = new Observation(true, true, 10.0, 100.0, 90.0, false);
    private static final Observation REFUSED = Observation.refused(Refusal.INSUFFICIENT_FUNDS, null, 5.0, 5.0);
    private static final Observation THREW = new Observation(true, true, 10.0, 100.0, 90.0, true);

    private static boolean proof(EconomyProxyConnection c, boolean creditStarted, Observation debit, Observation credit)
            throws SQLException {
        return TransferJournal.declinedProof(c.connection, ATTEMPT, 1_000, creditStarted, debit, credit);
    }

    @Test
    void anyCreditObservationMeansNotDeclined() throws SQLException {
        var c = new EconomyProxyConnection(false);
        assertFalse(proof(c, true, NO_MOVEMENT, MOVED));
        assertFalse(proof(c, false, NO_MOVEMENT, REFUSED));
        assertEquals(List.of(), c.calls);
    }

    @Test
    void aDebitThatProvablyMovedNothingIsDeclinedWithoutARead() throws SQLException {
        var c = new EconomyProxyConnection(false);
        assertTrue(proof(c, false, NO_MOVEMENT, null));
        assertTrue(proof(c, true, NO_MOVEMENT, null));
        assertEquals(List.of(), c.calls);
    }

    @Test
    void aDebitRefusedBeforeItsCallIsDeclinedWithoutARead() throws SQLException {
        var c = new EconomyProxyConnection(false);
        assertTrue(proof(c, false, REFUSED, null));
        assertEquals(List.of(), c.calls);
    }

    @Test
    void aMovedOrUncertainDebitAfterAStartedCreditIsNotDeclined() throws SQLException {
        var c = new EconomyProxyConnection(false);
        assertFalse(proof(c, true, MOVED, null));
        assertFalse(proof(c, true, THREW, null));
        assertFalse(proof(c, true, null, null));
        assertEquals(List.of(), c.calls);
    }

    @Test
    void anUncertainDebitWithNoCreditLooksForALegacyResolution() {
        var c = new EconomyProxyConnection(false);
        AssertionError reached = assertThrows(AssertionError.class, () -> proof(c, false, THREW, null));
        assertEquals("unexpected connection call prepareStatement", reached.getMessage());
    }

    @Test
    void aFailedResponseThatStillMovedMoneyIsNotADecline() throws SQLException {
        var c = new EconomyProxyConnection(false);
        Observation declinedButMoved = new Observation(true, false, 0.0, 100.0, 99.0, false);
        assertFalse(proof(c, true, declinedButMoved, null), "a failed response that still moved money is not a decline");
        assertEquals(List.of(), c.calls);
    }
}

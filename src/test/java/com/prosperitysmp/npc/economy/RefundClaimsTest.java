package com.prosperitysmp.npc.economy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The refusals a refund claim makes before any SQL: the evidence hash, the caller's transaction and a refund that
 * ends where the debit left the payer. The connection fails the test on any call other than getAutoCommit.
 */
class RefundClaimsTest {
    private static final UUID PAYMENT = new UUID(1, 1);
    private static final UUID RESOLUTION = new UUID(2, 2);
    private static final UUID PAYER = new UUID(3, 3);
    private static final String HASH = "0123456789abcdef".repeat(4);
    private static final String NOT_MOVED = "The refund ends at the balance this payment's debit left, so it shows no refund";

    private static long bits(double value) { return Double.doubleToRawLongBits(value); }

    private static void claim(EconomyProxyConnection c, String hash, double refundAfter, double debitAfter) throws SQLException {
        RefundClaims.claim(c.connection, RefundClaims.Source.TRANSFER, PAYMENT, RESOLUTION, PAYER,
                bits(80.0), bits(refundAfter), bits(debitAfter), hash, false);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcde",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0",
            "0123456789ABCDEF0123456789abcdef0123456789abcdef0123456789abcdef",
            "g123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            " 123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcde\n"})
    void aMalformedEvidenceHashIsRefusedBeforeTheConnectionIsUsed(String hash) {
        var c = new EconomyProxyConnection(false);
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> claim(c, hash, 90.0, 80.0));
        assertEquals("Invalid refund evidence hash", refused.getMessage());
        assertEquals(List.of(), c.calls);
    }

    @Test
    void aMissingEvidenceHashIsRefused() {
        var c = new EconomyProxyConnection(false);
        assertEquals("Invalid refund evidence hash",
                assertThrows(IllegalArgumentException.class, () -> claim(c, null, 90.0, 80.0)).getMessage());
        assertEquals(List.of(), c.calls);
    }

    @Test
    void anAutocommitConnectionIsRefusedBeforeAnyStatement() {
        var c = new EconomyProxyConnection(true);
        SQLException refused = assertThrows(SQLException.class, () -> claim(c, HASH, 90.0, 80.0));
        assertEquals("Refund claim requires caller-owned transaction", refused.getMessage());
        assertEquals(List.of("getAutoCommit"), c.calls);
    }

    @Test
    void aRefundEndingAtTheDebitsAfterBalanceShowsNoRefund() {
        var c = new EconomyProxyConnection(false);
        SQLException refused = assertThrows(SQLException.class, () -> claim(c, HASH, 80.0, 80.0));
        assertEquals(NOT_MOVED, refused.getMessage());
        assertEquals(List.of("getAutoCommit"), c.calls);
    }

    @Test
    void negativeZeroAndZeroAreTheSameBalanceSoNeitherPassesForTheOther() {
        var first = new EconomyProxyConnection(false);
        assertEquals(NOT_MOVED, assertThrows(SQLException.class, () -> claim(first, HASH, -0.0, 0.0)).getMessage());
        var second = new EconomyProxyConnection(false);
        assertEquals(NOT_MOVED, assertThrows(SQLException.class, () -> claim(second, HASH, 0.0, -0.0)).getMessage());
    }

    @Test
    void aRefundThatMovedTheBalanceGoesOnToTheDatabase() {
        var c = new EconomyProxyConnection(false);
        AssertionError reached = assertThrows(AssertionError.class, () -> claim(c, HASH, 90.0, 80.0));
        assertEquals("unexpected connection call prepareStatement", reached.getMessage());
        assertEquals(List.of("getAutoCommit", "prepareStatement"), c.calls);
    }

    @Test
    void aOneStepDifferenceInTheDoubleIsAlreadyAMovedBalance() {
        var c = new EconomyProxyConnection(false);
        assertThrows(AssertionError.class, () -> claim(c, HASH, Math.nextUp(80.0), 80.0));
        assertEquals(List.of("getAutoCommit", "prepareStatement"), c.calls);
    }

    @Test
    void missingIdsAreRefusedBeforeTheHashIsRead() {
        var c = new EconomyProxyConnection(false);
        assertThrows(NullPointerException.class, () -> RefundClaims.claim(c.connection, null, PAYMENT, RESOLUTION, PAYER,
                1, 2, 3, HASH, false));
        assertThrows(NullPointerException.class, () -> RefundClaims.claim(c.connection, RefundClaims.Source.COMMERCE, null,
                RESOLUTION, PAYER, 1, 2, 3, HASH, false));
        assertThrows(NullPointerException.class, () -> RefundClaims.claim(c.connection, RefundClaims.Source.COMMERCE, PAYMENT,
                null, PAYER, 1, 2, 3, HASH, false));
        assertThrows(NullPointerException.class, () -> RefundClaims.claim(c.connection, RefundClaims.Source.COMMERCE, PAYMENT,
                RESOLUTION, null, 1, 2, 3, "bad", false));
        assertEquals(List.of(), c.calls);
    }

    @Test
    void theDistinctRefundNoticeNamesThePaymentAndTheOperator() {
        assertEquals("Staff asserted a distinct refund: payment " + PAYMENT + " was resolved by a second, separate refund"
                        + " with the same balances as one already used (operator staff:ann).",
                RefundClaims.distinctRefundNotice(PAYMENT, "staff:ann"));
    }

    @Test
    void bothPaymentTablesAreClaimSources() {
        assertEquals(List.of("TRANSFER", "COMMERCE"),
                java.util.Arrays.stream(RefundClaims.Source.values()).map(Enum::name).toList());
    }
}

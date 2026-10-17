package com.prosperitysmp.npc.economy;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RefundEvidenceTest {
    private static final UUID PLAYER = new UUID(7, 1), RESERVE = new UUID(7, 2), PAYMENT = new UUID(0xabc, 0xdef);

    private static long bits(String decimal) {
        return Double.doubleToRawLongBits(new BigDecimal(decimal).doubleValue());
    }

    private static byte[] observed(UUID account, long before, long after) {
        return observed(account, PAYMENT, before, after);
    }

    private static byte[] observed(UUID account, UUID payment, long before, long after) {
        return ("{\"account\":\"" + account + "\",\"payment\":\"" + payment + "\",\"before_bits\":" + before
                + ",\"after_bits\":" + after + "}").getBytes(StandardCharsets.UTF_8);
    }

    /** A rent payout refused at its credit: the reserve was debited, then other money reached it before the stop. */
    private static Properties rentRefund() {
        Properties p = new Properties();
        p.setProperty("refund_completed", "true");
        p.setProperty("refund_before_bits", Long.toString(bits("57455.928404927248686")));
        p.setProperty("refund_after_bits", Long.toString(bits("57468.268404927248686")));
        p.setProperty("reserve_before", "57455.928404927248686");
        p.setProperty("reserve_after", "57468.268404927248686");
        p.setProperty("owner_before", "1234.5678901");
        p.setProperty("owner_after", "1234.5678901");
        return p;
    }

    private static Properties purchaseRefund() {
        Properties p = new Properties();
        p.setProperty("refund_completed", "true");
        p.setProperty("refund_before_bits", Long.toString(bits("75.00")));
        p.setProperty("refund_after_bits", Long.toString(bits("85.00")));
        p.setProperty("reserve_before", "57492.488404927");
        p.setProperty("reserve_after", "57492.488404927");
        return p;
    }

    @Test void aReservePaidRefundRestoresTheReserveAndLeavesTheOwnerAlone() {
        // The reserve is the payer, so a true refund changes it. Requiring it unchanged made rent unrecoverable.
        Properties p = rentRefund();
        long before = bits("57455.928404927248686"), after = bits("57468.268404927248686");
        assertDoesNotThrow(() -> RefundEvidence.check(p, RESERVE, PAYMENT, true, observed(RESERVE, before, after)));
        p.setProperty("reserve_after", "57468.278404927248686");
        assertEquals("Reserve balances must be the refund's own before and after",
                assertThrows(IllegalArgumentException.class,
                        () -> RefundEvidence.check(p, RESERVE, PAYMENT, true, observed(RESERVE, before, after))).getMessage());
        Properties owner = rentRefund();
        owner.setProperty("owner_after", "1246.9078901");
        assertEquals("The owner's balance must be unchanged by the refund", assertThrows(IllegalArgumentException.class,
                () -> RefundEvidence.check(owner, RESERVE, PAYMENT, true, observed(RESERVE, before, after))).getMessage());
        Properties missing = rentRefund();
        missing.remove("owner_before");
        assertThrows(IllegalArgumentException.class,
                () -> RefundEvidence.check(missing, RESERVE, PAYMENT, true, observed(RESERVE, before, after)));
    }

    @Test void aPlayerPaidRefundMustNotComeOutOfTheReserve() {
        long before = bits("75.00"), after = bits("85.00");
        assertDoesNotThrow(() -> RefundEvidence.check(purchaseRefund(), PLAYER, PAYMENT, false, observed(PLAYER, before, after)));
        Properties moved = purchaseRefund();
        moved.setProperty("reserve_after", "57482.488404927");
        assertEquals("Completed correction with unchanged reserve required", assertThrows(IllegalArgumentException.class,
                () -> RefundEvidence.check(moved, PLAYER, PAYMENT, false, observed(PLAYER, before, after))).getMessage());
        Properties notDone = purchaseRefund();
        notDone.setProperty("refund_completed", "false");
        assertThrows(IllegalArgumentException.class,
                () -> RefundEvidence.check(notDone, PLAYER, PAYMENT, false, observed(PLAYER, before, after)));
    }

    @Test void theObservedRecordAndTheSavedBalanceMustAgreeWithTheTypedBits() {
        // Copying the debit's bits into the properties no longer proves anything on its own.
        long before = bits("75.00"), after = bits("85.00");
        Properties p = purchaseRefund();
        assertEquals("refund-observed.json names another account", assertThrows(IllegalArgumentException.class,
                () -> RefundEvidence.check(p, PLAYER, PAYMENT, false, observed(RESERVE, before, after))).getMessage());
        assertEquals("refund-observed.json records a different refund", assertThrows(IllegalArgumentException.class,
                () -> RefundEvidence.check(p, PLAYER, PAYMENT, false, observed(PLAYER, before, bits("85.01")))).getMessage());
        assertEquals("The payer's saved balance is not the refund's after value", assertThrows(IllegalArgumentException.class,
                () -> RefundEvidence.requireSaved(p, bits("84.00"))).getMessage());
        assertDoesNotThrow(() -> RefundEvidence.requireSaved(p, after));
        for (String broken : new String[]{"", "[]", "{\"account\":\"" + PLAYER + "\"", "{\"account\":1,,\"x\":2}",
                "{\"account\":\"a\",\"account\":\"b\"}", "{\"account\":{\"x\":1}}", "{\"before_bits\":1.5}"}) {
            assertThrows(IllegalArgumentException.class, () -> RefundEvidence.check(p, PLAYER, PAYMENT, false,
                    broken.getBytes(StandardCharsets.UTF_8)), broken);
        }
        String spaced = "{ \"account\" : \"" + PLAYER + "\" ,\n  \"payment\":\"" + PAYMENT + "\", \"before_bits\": " + before
                + ", \"after_bits\" :" + after + " }\n";
        assertDoesNotThrow(() -> RefundEvidence.check(p, PLAYER, PAYMENT, false, spaced.getBytes(StandardCharsets.UTF_8)));
    }

    @Test void theObservedRecordMustNameTheHeldPaymentItRefunds() {
        // A refund made for one payment cannot be filed against another, even by the same payer for the same amount.
        long before = bits("75.00"), after = bits("85.00");
        Properties p = purchaseRefund();
        assertDoesNotThrow(() -> RefundEvidence.check(p, PLAYER, PAYMENT, false, observed(PLAYER, PAYMENT, before, after)));
        assertEquals("refund-observed.json names another payment", assertThrows(IllegalArgumentException.class,
                () -> RefundEvidence.check(p, PLAYER, new UUID(7, 4), false, observed(PLAYER, PAYMENT, before, after))).getMessage());
        String upper = new String(observed(PLAYER, PAYMENT, before, after), StandardCharsets.UTF_8)
                .replace(PAYMENT.toString(), PAYMENT.toString().toUpperCase(java.util.Locale.ROOT));
        assertThrows(IllegalArgumentException.class, () -> RefundEvidence.check(p, PLAYER, PAYMENT, false,
                upper.getBytes(StandardCharsets.UTF_8)), "the id is written exactly as the tools print it");
        // A file in the format before dev.63 names no payment: refused with a plain reason, never read as a match.
        byte[] old = ("{\"account\":\"" + PLAYER + "\",\"before_bits\":" + before + ",\"after_bits\":" + after + "}")
                .getBytes(StandardCharsets.UTF_8);
        assertEquals("refund-observed.json does not name the payment it refunds. Files from before dev.63 are not accepted:"
                        + " add \"payment\" with the held transfer attempt id or commerce order id",
                assertThrows(IllegalArgumentException.class, () -> RefundEvidence.check(p, PLAYER, PAYMENT, false, old)).getMessage());
        byte[] number = ("{\"account\":\"" + PLAYER + "\",\"payment\":7,\"before_bits\":" + before + ",\"after_bits\":" + after + "}")
                .getBytes(StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class, () -> RefundEvidence.check(p, PLAYER, PAYMENT, false, number));
        // The reserve's refunds name their payment the same way.
        Properties rent = rentRefund();
        long rentBefore = bits("57455.928404927248686"), rentAfter = bits("57468.268404927248686");
        assertEquals("refund-observed.json names another payment", assertThrows(IllegalArgumentException.class,
                () -> RefundEvidence.check(rent, RESERVE, PAYMENT, true, observed(RESERVE, new UUID(7, 5), rentBefore, rentAfter)))
                .getMessage());
    }

    @Test void bothToolsCheckTheRecordAndOnlyAnUnresolvedRowNeedsTheSavedBalance() throws Exception {
        // The tools need a stopped, masked server and root, so their wiring is pinned in source. An exact replay of
        // an applied refund writes nothing new, so it does not need the payer's balance to be unchanged since.
        String transfer = Files.readString(Path.of("src/main/java/com/prosperitysmp/npc/economy/RefundRecoveryTool.java"));
        String commerce = Files.readString(Path.of("src/main/java/com/prosperitysmp/npc/commerce/CommerceRecoveryTool.java"));
        assertTrue(transfer.contains("RefundEvidence.check(p, attempt.payer(), handle.attemptId(), reservePays, observed);"));
        assertTrue(transfer.contains("if (!attempt.state().equals(\"COMPENSATED\"))\n"
                + "                    RefundEvidence.requireSaved(p, RefundEvidence.savedBalanceBits(Path.of(expectedRoot), attempt.payer()));"));
        assertTrue(transfer.contains("boolean reservePays = domain.equals(\"rent\") || domain.equals(\"appreciation\");"));
        assertTrue(commerce.contains("RefundEvidence.check(evidence, payer, orderId, false, observed);"));
        assertTrue(commerce.contains("if (found.state() != CommerceOrderRepository.State.COMPENSATED)\n"
                + "                        RefundEvidence.requireSaved(evidence, RefundEvidence.savedBalanceBits(Path.of(root), payer));"));
        assertFalse(transfer.contains("Completed correction with unchanged reserve required"), "one rule, in RefundEvidence");
        assertFalse(commerce.contains("Independently completed refund with unchanged reserve required"), "one rule, in RefundEvidence");
    }

    @Test void theDistinctRefundFlagReachesOnlyTheMatchingBalancesRule() throws Exception {
        // The flag needs --apply, is passed only to the resolution, and is reported once applied and settled. The
        // record's payer, payment and bits and the saved balance are checked above it and never see it.
        String transfer = Files.readString(Path.of("src/main/java/com/prosperitysmp/npc/economy/RefundRecoveryTool.java"))
                .replace("\r\n", "\n");
        String commerce = Files.readString(Path.of("src/main/java/com/prosperitysmp/npc/commerce/CommerceRecoveryTool.java"))
                .replace("\r\n", "\n");
        assertEquals(List.of(
                "boolean distinctRefund = mode.equals(\"--record-completed-refund\") && args.length == 5",
                "&& (args.length == 3 || (args.length == 4 && args[3].equals(\"--apply\")) || distinctRefund);",
                "Long.parseLong(required(p, \"refund_after_bits\")), legacy, apply, distinctRefund)",
                "if (distinctRefund)"),
                uses(transfer, "distinctRefund"));
        assertTrue(transfer.contains("args[3].equals(\"--apply\") && args[4].equals(\"--distinct-refund\");"));
        assertTrue(transfer.indexOf("RefundEvidence.check(p, attempt.payer(), handle.attemptId(), reservePays, observed);")
                < transfer.indexOf("legacy, apply, distinctRefund)"));
        assertEquals(List.of(
                "boolean distinctRefund = args.length == 5 && args[0].equals(\"--reconcile-payment\") && args[3].equals(\"--apply\")",
                "boolean apply = (args.length == 4 && args[0].equals(\"--reconcile-payment\") && args[3].equals(\"--apply\")) || distinctRefund;",
                "if (distinctRefund && decision != PaymentDecision.VERIFIED_REFUNDED_DEBIT)",
                "var receipt = repository.reconcilePayment(order, handle, resolution, legacy, apply, distinctRefund);",
                "if (distinctRefund)"),
                uses(commerce, "distinctRefund"));
        assertTrue(commerce.contains("&& args[4].equals(\"--distinct-refund\");"));
        assertTrue(commerce.indexOf("RefundEvidence.check(evidence, payer, orderId, false, observed);")
                < commerce.indexOf("legacy, apply, distinctRefund);"));
        // The report never throws, and in the transfer tool it runs only after the domain side has settled.
        assertTrue(transfer.contains("RefundClaims.reportDistinctRefund(db, RefundClaims.Source.TRANSFER, handle.attemptId(), resolutionId,"));
        assertTrue(transfer.indexOf("RefundClaims.reportDistinctRefund(") > transfer.indexOf("outcome = \"RESERVATION_RELEASED\";"));
        assertTrue(commerce.contains("RefundClaims.reportDistinctRefund(db, RefundClaims.Source.COMMERCE, orderId, resolution.resolutionId(),"));
        assertTrue(commerce.indexOf("RefundClaims.reportDistinctRefund(") > commerce.indexOf("legacy, apply, distinctRefund);"));
        for (String tool : List.of(transfer, commerce)) assertFalse(tool.contains("distinctRefundNotice"));
        assertEquals("Staff asserted a distinct refund: payment " + PAYMENT + " was resolved by a second, separate refund"
                + " with the same balances as one already used (operator staff:test).",
                RefundClaims.distinctRefundNotice(PAYMENT, "staff:test"));
    }

    private static List<String> uses(String source, String name) {
        return source.lines().filter(line -> line.contains(name)).map(String::strip).toList();
    }

    /** What EssentialsX serves Vault for a decimal: the nearest double, one step lower when that is above the decimal. */
    private static long served(String decimal) {
        BigDecimal value = new BigDecimal(decimal);
        double nearest = value.doubleValue();
        return Double.doubleToRawLongBits(new BigDecimal(nearest).compareTo(value) > 0 ? Math.nextDown(nearest) : nearest);
    }

    @Test void aBalanceEssentialsServesOneStepLowerStillMatches() {
        // EssentialsX's own Vault provider never reports more than the saved decimal. For 1234.5678901 the nearest double is
        // above it, so Vault reports the double below; a genuine refund ending there must not be refused.
        assertTrue(new BigDecimal(new BigDecimal("1234.5678901").doubleValue()).compareTo(new BigDecimal("1234.5678901")) > 0);
        assertNotEquals(bits("1234.5678901"), served("1234.5678901"));
        Properties p = purchaseRefund();
        p.setProperty("refund_after_bits", Long.toString(served("1234.5678901")));
        assertDoesNotThrow(() -> RefundEvidence.requireSaved(p, bits("1234.5678901")));
        p.setProperty("refund_after_bits", Long.toString(bits("1234.5678901")));
        assertDoesNotThrow(() -> RefundEvidence.requireSaved(p, bits("1234.5678901")), "the nearest double too (other Vault hooks)");
        // Two steps lower, one step higher, or a cent away is still refused.
        long nearest = bits("1234.5678901");
        for (long wrong : new long[]{Double.doubleToRawLongBits(Math.nextDown(Math.nextDown(Double.longBitsToDouble(nearest)))),
                Double.doubleToRawLongBits(Math.nextUp(Double.longBitsToDouble(nearest))), bits("1234.5578901")}) {
            p.setProperty("refund_after_bits", Long.toString(wrong));
            assertEquals("The payer's saved balance is not the refund's after value", assertThrows(IllegalArgumentException.class,
                    () -> RefundEvidence.requireSaved(p, nearest)).getMessage());
        }
        // A decimal whose nearest double is below it is served unchanged, so only that double matches from above.
        assertTrue(new BigDecimal(new BigDecimal("57492.488404927").doubleValue()).compareTo(new BigDecimal("57492.488404927")) <= 0);
        assertEquals(bits("57492.488404927"), served("57492.488404927"));

        // The reserve's own before and after in a reserve-paid refund are compared the same way.
        Properties rent = new Properties();
        rent.setProperty("refund_completed", "true");
        long before = served("57455.93"), after = served("57468.29");
        rent.setProperty("refund_before_bits", Long.toString(before));
        rent.setProperty("refund_after_bits", Long.toString(after));
        rent.setProperty("reserve_before", "57455.93");
        rent.setProperty("reserve_after", "57468.29");
        rent.setProperty("owner_before", "1234.5678901");
        rent.setProperty("owner_after", "1234.5678901");
        assertDoesNotThrow(() -> RefundEvidence.check(rent, RESERVE, PAYMENT, true, observed(RESERVE, before, after)));
        // 57455.93's nearest double is above it, so before is one step below the nearest and low is two steps below.
        assertNotEquals(bits("57455.93"), before);
        long low = Double.doubleToRawLongBits(Math.nextDown(Double.longBitsToDouble(before)));
        rent.setProperty("refund_before_bits", Long.toString(low));
        assertEquals("Reserve balances must be the refund's own before and after", assertThrows(IllegalArgumentException.class,
                () -> RefundEvidence.check(rent, RESERVE, PAYMENT, true, observed(RESERVE, low, after))).getMessage());
    }

    @Test void theSavedBalanceIsReadAsVaultWouldReportIt() throws Exception {
        assertEquals(bits("57492.488404927248686"), RefundEvidence.savedBalanceBits(
                "lastAccountName: Reserve\nmoney: '57492.488404927248686'\nnpc: true\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals(bits("18.5"), RefundEvidence.savedBalanceBits("money: \"18.5\"\r\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals(bits("-131.5"), RefundEvidence.savedBalanceBits("money: -131.5\n".getBytes(StandardCharsets.UTF_8)));
        // Only the top-level money line counts, and there must be exactly one.
        assertEquals(bits("10"), RefundEvidence.savedBalanceBits("money: '10'\nnested:\n  money: '99'\n".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> RefundEvidence.savedBalanceBits("money: '1'\nmoney: '2'\n".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> RefundEvidence.savedBalanceBits("balance: '1'\n".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> RefundEvidence.savedBalanceBits("money: 'lots'\n".getBytes(StandardCharsets.UTF_8)));
        Path root = Files.createTempDirectory("refund-evidence");
        Path userdata = Files.createDirectories(root.resolve("plugins/Essentials/userdata"));
        Files.writeString(userdata.resolve(PLAYER + ".yml"), "money: '85.00'\n");
        assertEquals(bits("85.00"), RefundEvidence.savedBalanceBits(root.toRealPath(), PLAYER));
        assertThrows(Exception.class, () -> RefundEvidence.savedBalanceBits(root.toRealPath(), RESERVE), "no userdata file");
    }
}

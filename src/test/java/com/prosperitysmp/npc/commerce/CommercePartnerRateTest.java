package com.prosperitysmp.npc.commerce;

import com.prosperitysmp.npc.resident.ResidentProfile;
import java.time.Instant;
import java.time.LocalDate;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The partner rate only shrinks the service fee: never the material part, never below a $1 fee. */
class CommercePartnerRateTest {
    private static final long[] UNITS = {1, 7, 42, 99, 100, 101, 999, 12_345, 1_000_000};

    /** The dev.58 formula, written out here so the legacy price is pinned independently of the code. */
    private static long legacy(CommerceCatalogue.Entry entry, long unit) {
        long materials = unit * entry.quantity();
        return materials + Math.max(100L, Math.floorDiv(materials * entry.serviceBasisPoints() + 9999L, 10000L));
    }

    @Test void zeroPercentIsTheLegacyPriceForEveryEntry() {
        // Positive control: cost(unit, 0) and cost(unit) are the dev.58 price.
        for (var occupation : ResidentProfile.Occupation.values())
            for (var entry : CommerceCatalogue.entries(occupation))
                for (long unit : UNITS) {
                    assertEquals(legacy(entry, unit), entry.cost(unit), entry.id() + " " + unit);
                    assertEquals(entry.cost(unit), entry.cost(unit, 0), entry.id() + " " + unit);
                }
    }

    @Test void materialsStayFullAndTheFeeNeverDropsBelowOneDollar() {
        for (var occupation : ResidentProfile.Occupation.values())
            for (var entry : CommerceCatalogue.entries(occupation))
                for (long unit : UNITS)
                    for (int pct = 1; pct <= 60; pct++) {
                        long materials = unit * entry.quantity();
                        long partner = entry.cost(unit, pct);
                        // Mutation guard: discounting the material part breaks this floor.
                        assertTrue(partner >= materials + 100, entry.id() + " " + unit + " " + pct);
                        assertTrue(partner <= entry.cost(unit), "a discount never raises the price");
                        long fee = partner - materials;
                        long expected = Math.max(100L, Math.ceilDiv(materials * entry.serviceBasisPoints() * (100L - pct), 1_000_000L));
                        assertEquals(expected, fee, entry.id() + " " + unit + " " + pct);
                    }
    }

    /**
     * DEV 2026-10-14, stone at 42 cents with Mara at 25%: the supplies price sits on the $1 fee floor and did not move,
     * yet it said "(partner rate)". The mark now shows only when the partner price is lower. Without a partner rate the
     * label is the dev.58 one.
     */
    @Test void thePartnerMarkShowsOnlyWhenThePriceIsLower() {
        var entries = CommerceCatalogue.entries(ResidentProfile.Occupation.RESTORER);
        var buy = entries.get(0); var hire = entries.get(1);
        assertEquals("Buy 32 stone - $14.44", CommerceRuntime.orderLabel(buy, 42, 0));
        assertEquals("Buy 32 stone - $14.44", CommerceRuntime.orderLabel(buy, 42, 25), "same price on the $1 floor, so no mark");
        assertEquals("Hire: 32 stone - $18.15", CommerceRuntime.orderLabel(hire, 42, 0));
        assertEquals("Hire: 32 stone - $16.97" + CommerceRuntime.PARTNER_RATE, CommerceRuntime.orderLabel(hire, 42, 25));
        for (var occupation : ResidentProfile.Occupation.values())
            for (var entry : CommerceCatalogue.entries(occupation))
                for (long unit : UNITS)
                    for (int pct = 0; pct <= 60; pct++)
                        assertEquals(entry.cost(unit, pct) < entry.cost(unit),
                                CommerceRuntime.orderLabel(entry, unit, pct).endsWith(CommerceRuntime.PARTNER_RATE), entry.id() + " " + unit + " " + pct);
    }

    @Test void theDiscountIsBoundedAndPricesAreVerified() {
        var entry = CommerceCatalogue.entries(ResidentProfile.Occupation.SHOPKEEPER).get(1);
        assertThrows(IllegalArgumentException.class, () -> entry.cost(42, -1));
        assertThrows(IllegalArgumentException.class, () -> entry.cost(42, 61));
        assertThrows(IllegalArgumentException.class, () -> entry.cost(0, 40));
        // Worker batch of 4 iron at $1.00: $4.00 materials, 35% fee is $1.40, 40% off is $0.84, so the $1 floor holds.
        assertEquals(540, entry.cost(100, 0));
        assertEquals(500, entry.cost(100, 40));
    }

    /**
     * CommerceRuntime.order needs a Bukkit player, so the call is pinned in its source: with partnerships on, the
     * quote is checked against the rate read on the worker, and a stale quote finishes with the refusal and returns
     * before payments.request. Off, nothing is read and pct is 0.
     */
    @Test void orderChecksThePartnerQuoteBeforeAnyPayment() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/prosperitysmp/npc/commerce/CommerceRuntime.java"));
        int start = source.indexOf("public void order(Player player, CommerceOrder quote");
        int end = source.indexOf("public void collect(", start);
        assertTrue(start > 0 && end > start);
        String order = source.substring(start, end);
        assertTrue(order.contains("boolean partnersOn = rates != null && rates.enabled();"));
        assertTrue(order.contains("int pct = partnersOn ? partnerPct(quote.playerId(), resident.id(), quote.economyDay()) : 0;"));
        String check = "if (partnersOn && !quoteStillValid(entry, quote, pct, plugin.commercePrices().unitCents(quote.material()))) {";
        int at = order.indexOf(check);
        int payment = order.indexOf("payments.request(player, quote, entry, pct,");
        assertTrue(at > 0, "the order re-checks the partner quote");
        assertEquals(at, order.lastIndexOf(check));
        assertTrue(payment > at, "the check runs before the payment is requested");
        assertEquals("finish(op, \"That quote expired. No payment started; ask for a fresh quote.\"); return;",
                order.substring(at + check.length(), payment).strip().lines().findFirst().orElseThrow().strip());
        assertEquals(payment, order.lastIndexOf("payments.request("), "one payment call");
    }

    /**
     * P2-A wiring. The rate the worker read is captured, so the main-thread quote check cannot see a partnership that
     * ended after the read. The order therefore hands payments.request a check built from that captured rate, the
     * payment runtime runs it inside the debit-intent transaction (its only beginDebit call), a discounted request
     * without a check is refused, and the refusal reaches the player as the expired-quote line.
     */
    @Test void theCapturedRateIsCheckedAgainInsideTheDebitTransaction() throws Exception {
        String runtime = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/prosperitysmp/npc/commerce/CommerceRuntime.java"));
        int start = runtime.indexOf("public void order(Player player, CommerceOrder quote");
        int end = runtime.indexOf("public void collect(", start);
        assertTrue(start > 0 && end > start);
        String order = runtime.substring(start, end);
        String check = "CommerceOrderRepository.DebitCheck stillPartner = pct > 0\n"
                + "                    ? c -> rates.stillGrants(c, quote.playerId(), resident.id(), quote.economyDay(), pct) : null;";
        assertTrue(order.contains(check), "the check is built from the captured rate");
        assertTrue(order.indexOf(check) < order.indexOf("payments.request(player, quote, entry, pct, stillPartner,"));
        assertEquals(1, order.split("payments\\.request\\(", -1).length - 1, "one payment call");
        assertTrue(order.contains("case EXPIRED -> \"That quote expired. No payment started; ask for a fresh quote.\";"));
        String payment = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/prosperitysmp/npc/economy/CommercePaymentRuntime.java"));
        assertTrue(payment.contains("try { return orders.beginDebit(order, handle, partnerCheck); }"));
        assertEquals(1, payment.split("orders\\.beginDebit\\(", -1).length - 1, "one debit intent");
        assertTrue(payment.contains("if (feeDiscountPct > 0 && partnerCheck == null) {"), "no discount without the check");
        assertTrue(payment.contains("case CANCELLED -> CommerceOrderRepository.QUOTE_EXPIRED.equals(receipt.outcome()) ? Status.EXPIRED : Status.CANCELLED;"));
    }

    @Test void anOrderIsRefusedWhenTheQuoteNoLongerMatchesTheCurrentRate() {
        var entry = CommerceCatalogue.entries(ResidentProfile.Occupation.SHOPKEEPER).get(1);
        UUID player = new UUID(0, 1), nonce = new UUID(0, 2), resident = new UUID(0, 3);
        LocalDate day = LocalDate.of(2026, 10, 14);
        long partnerCost = entry.cost(1_000, 40);
        var quote = new CommerceOrder(CommerceOrder.idFor(nonce, player, entry.source(), day), nonce, player, resident,
                entry.source(), entry.id(), entry.material(), entry.quantity(), partnerCost, day, Instant.parse("2026-10-14T12:00:00Z"),
                entry.delaySeconds());
        assertTrue(CommerceRuntime.quoteStillValid(entry, quote, 40, OptionalLong.of(1_000)));
        // The partnership ended: the full fee is due again, so the old partner quote is refused.
        assertFalse(CommerceRuntime.quoteStillValid(entry, quote, 0, OptionalLong.of(1_000)));
        assertFalse(CommerceRuntime.quoteStillValid(entry, quote, 40, OptionalLong.of(1_001)));
        assertFalse(CommerceRuntime.quoteStillValid(entry, quote, 40, OptionalLong.empty()));
    }
}

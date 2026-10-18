package com.prosperitysmp.npc.economy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UtilityPricesTest {
    private static final UUID PROPERTY = new UUID(0, 1), OWNER = new UUID(0, 2);
    private static final LocalDate DAY = LocalDate.of(2026, 10, 18);
    private static final Instant ISSUED = Instant.parse("2026-10-18T12:00:00Z");

    @Test void defaultTariffNormalizesToTheLegacyReceipt() {
        var old = new UtilityBill(PROPERTY, OWNER, 0, 0, DAY, 4, 2, 3, ISSUED, "UTC", 4);
        var explicit = bill(UtilityPrices.DEFAULT);
        assertEquals(old, explicit);
        assertNull(explicit.configuredPrices());
        assertEquals(400, old.cents());
        assertEquals(old.fingerprint(), explicit.fingerprint());
    }

    @Test void customHashBindsAllRatesEvenWhenTheirTotalsMatch() throws Exception {
        var first = bill(new UtilityPrices(200, 125, 100));
        var sameTotal = bill(new UtilityPrices(250, 100, 100));
        assertEquals(750, first.cents());
        assertEquals(first.cents(), sameTotal.cents());
        assertNotEquals(first.fingerprint(), sameTotal.fingerprint());
        String payload = "utility-bill-v2|" + PROPERTY + "|" + OWNER
                + "|0|0|2026-10-18|4|2|3|" + ISSUED.toEpochMilli() + "|UTC|4|750|200|125|100";
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(payload.getBytes(StandardCharsets.UTF_8))), first.fingerprint());
        assertEquals(first, bill(first.configuredPrices()));
        assertNotEquals(first.fingerprint(), bill(null).fingerprint());
        assertTrue(first.toString().contains("configuredPrices=UtilityPrices[baseCents=200, tenantCents=125, upgradeLevelCents=100]"));
    }

    @Test void positiveFlatBillsAndFreeIncrementsAreAllowed() {
        var flat = new UtilityPrices(1, 0, 0);
        flat.requireExactTotals();
        for (int tenants = 0; tenants <= 4; tenants++)
            for (int upgrades = 0; upgrades <= 6; upgrades++) assertEquals(1, flat.cents(tenants, upgrades));
    }

    @Test void mapLoaderRejectsMissingValuesAndMutableInputsDoNotChangeTheTariff() {
        var input = new java.util.HashMap<String, Object>();
        input.put("per-tenant-cents", null);
        assertThrows(IllegalArgumentException.class, () -> UtilityPrices.load(input));
        input.put("per-tenant-cents", "125");
        var loaded = UtilityPrices.load(input);
        input.put("per-tenant-cents", "250");
        assertEquals(125, loaded.tenantCents());
    }

    @Test void malformedOrUnpayableTariffsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new UtilityPrices(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new UtilityPrices(100, -1, 50));
        assertThrows(IllegalArgumentException.class, () -> new UtilityPrices(100, 75, -1));
        assertThrows(IllegalArgumentException.class, () -> new UtilityPrices(Long.MAX_VALUE, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new UtilityPrices(PurchasePrices.MAX_CENTS, 1, 0));
        var imprecise = new UtilityPrices(838860800, 0, 0);
        assertThrows(IllegalArgumentException.class, imprecise::requireExactTotals);
        assertThrows(IllegalArgumentException.class, () -> bill(imprecise));
        assertThrows(IllegalArgumentException.class, () -> UtilityPrices.DEFAULT.cents(5, 0));
        assertThrows(IllegalArgumentException.class, () -> UtilityPrices.DEFAULT.cents(0, 7));
    }

    private static UtilityBill bill(UtilityPrices prices) {
        return new UtilityBill(PROPERTY, OWNER, 0, 0, DAY, 4, 2, 3, ISSUED, "UTC", 4, prices);
    }
}

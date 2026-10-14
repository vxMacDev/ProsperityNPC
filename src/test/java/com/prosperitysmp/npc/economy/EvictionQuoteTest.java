package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.formulas.IncidentType;
import com.prosperitysmp.npc.formulas.UpgradeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EvictionQuoteTest {
    private static final UUID REQUEST = new UUID(0, 1);
    private static final UUID PROPERTY = new UUID(0, 2);
    private static final UUID PAYER = new UUID(0, 3);
    private static final UUID TENANT = new UUID(0, 4);
    private static final LocalDate DAY = LocalDate.of(2026, 9, 6);

    @Test
    void validRevisionAndSlotBoundariesKeepTenDollarPrice() {
        for (long revision : new long[]{0, 1, Long.MAX_VALUE - 1}) {
            for (int slot = 0; slot < 4; slot++) {
                EvictionQuote quote = new EvictionQuote(revision, slot);
                PurchaseRequest request = bound(quote);
                assertEquals(revision, quote.tenancyRevision());
                assertEquals(slot, quote.slot());
                assertEquals(quote, request.eviction());
                assertEquals(1_000, request.cents());
                assertEquals(EventSource.EVICTION, request.source());
                assertEquals(EventSource.Direction.FROM_PUBLIC, request.source().direction());
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"-9223372036854775808,0", "-1,0", "9223372036854775807,0", "0,-1", "0,4", "0,2147483647"})
    void invalidRevisionOrSlotFailsClosed(long revision, int slot) {
        assertThrows(IllegalArgumentException.class, () -> new EvictionQuote(revision, slot));
    }

    @Test
    void boundFingerprintIncludesRevisionAndSlotButActionExcludesSlot() throws Exception {
        PurchaseRequest first = bound(new EvictionQuote(17, 0));
        PurchaseRequest otherSlot = bound(new EvictionQuote(17, 3));
        PurchaseRequest nextTenancy = bound(new EvictionQuote(18, 0));
        assertEquals(fingerprint("|evict-v1|17|0"), first.eventKey());
        assertEquals(fingerprint("|evict-v1|17|3"), otherSlot.eventKey());
        assertNotEquals(first.eventKey(), otherSlot.eventKey());
        assertNotEquals(first.eventKey(), nextTenancy.eventKey());
        assertEquals("EVICT|" + TENANT + "|r17", first.actionKey());
        assertEquals(first.actionKey(), otherSlot.actionKey());
        assertNotEquals(first.actionKey(), nextTenancy.actionKey());
        assertTrue(first.eventKey().length() <= 160);
    }

    @Test
    void bothOldConstructorsPreserveHistoricalFingerprintAndAction() throws Exception {
        PurchaseRequest nine = new PurchaseRequest(REQUEST, PROPERTY, PAYER, 2,
                PurchaseRequest.Kind.EVICT, null, null, TENANT, DAY);
        PurchaseRequest ten = new PurchaseRequest(REQUEST, PROPERTY, PAYER, 2,
                PurchaseRequest.Kind.EVICT, null, null, TENANT, DAY, null);
        assertEquals(nine, ten);
        assertNull(nine.eviction());
        assertNull(nine.repair());
        assertEquals(1_000, nine.cents());
        assertEquals(fingerprint(""), nine.eventKey());
        assertEquals("EVICT|" + TENANT, nine.actionKey());
        assertNotEquals(nine.eventKey(), bound(new EvictionQuote(0, 0)).eventKey());
    }

    @Test
    void evictionBindingIsRejectedOnEveryOtherKindAndCannotReplaceTenantIdentity() {
        EvictionQuote quote = new EvictionQuote(0, 0);
        RepairQuote repair = new RepairQuote(1, IncidentType.PESTS, "a".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> new PurchaseRequest(REQUEST, PROPERTY, PAYER, 2,
                PurchaseRequest.Kind.MAINTAIN, null, null, null, DAY, null, quote));
        assertThrows(IllegalArgumentException.class, () -> new PurchaseRequest(REQUEST, PROPERTY, PAYER, 2,
                PurchaseRequest.Kind.UPGRADE, UpgradeType.ADVERTISING, 1, null, DAY, null, quote));
        assertThrows(IllegalArgumentException.class, () -> new PurchaseRequest(REQUEST, PROPERTY, PAYER, 2,
                PurchaseRequest.Kind.REPAIR, null, null, null, DAY, repair, quote));
        assertThrows(IllegalArgumentException.class, () -> new PurchaseRequest(REQUEST, PROPERTY, PAYER, 2,
                PurchaseRequest.Kind.EVICT, null, null, null, DAY, null, quote));
        assertThrows(IllegalArgumentException.class, () -> new PurchaseRequest(REQUEST, PROPERTY, PAYER, 2,
                PurchaseRequest.Kind.EVICT, null, null, TENANT, DAY, repair, quote));
    }

    private static PurchaseRequest bound(EvictionQuote quote) {
        return new PurchaseRequest(REQUEST, PROPERTY, PAYER, 2, PurchaseRequest.Kind.EVICT,
                null, null, TENANT, DAY, null, quote);
    }

    private static String fingerprint(String suffix) throws Exception {
        String payload = "purchase-v1|" + REQUEST + "|" + PROPERTY + "|" + PAYER
                + "|2|EVICT|null|null|" + TENANT + "|" + DAY + "|1000" + suffix;
        return "purchase|" + REQUEST + "|" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.US_ASCII)));
    }
}

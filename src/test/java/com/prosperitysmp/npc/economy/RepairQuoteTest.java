package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.formulas.IncidentType;
import com.prosperitysmp.npc.formulas.UpgradeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepairQuoteTest {
    private static final UUID REQUEST = new UUID(0, 1);
    private static final UUID PROPERTY = new UUID(0, 2);
    private static final UUID PAYER = new UUID(0, 3);
    private static final UUID TENANT = new UUID(0, 4);
    private static final LocalDate DAY = LocalDate.of(2026, 9, 6);
    private static final String HASH = "0123456789abcdef".repeat(4);

    @ParameterizedTest
    @CsvSource({"PESTS,2000", "PLUMBING,3500", "BREAK_IN,5000"})
    void eachIncidentUsesCanonicalCashPriceAndRepairSource(IncidentType type, long cents) {
        RepairQuote quote = new RepairQuote(17, type, HASH);
        PurchaseRequest request = repair(REQUEST, DAY, quote);
        assertEquals(cents, type.cashRepairCents());
        assertEquals(cents, quote.cents());
        assertEquals(cents, request.cents());
        assertEquals(quote, request.repair());
        assertEquals(EventSource.REPAIR, request.source());
        assertEquals(EventSource.Direction.FROM_PUBLIC, request.source().direction());
        assertEquals("REPAIR|17", request.actionKey());
        assertTrue(request.eventKey().matches("purchase\\|" + REQUEST + "\\|[a-f0-9]{64}"));
        assertTrue(request.eventKey().length() <= 160);
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, -1, 0})
    void incidentIdentityMustBePositive(long incident) {
        assertThrows(IllegalArgumentException.class, () -> new RepairQuote(incident, IncidentType.PESTS, HASH));
    }

    @ParameterizedTest
    @CsvSource({"PESTS,99c74d4bc1c4b48da3b5e6b1a61828ed334c72de24214a193eadb56f9bf70506",
            "PLUMBING,9f5b5de2a3ba5f3956d6baefdf698f109aad5e2e27c0a50d4920203f04abbf5d",
            "BREAK_IN,4fd0dad3b6560e2e301745d6815a5d7a383ea868b48f4b3eebbcb385e8a3bb48"})
    void explicitDefaultKeepsLegacyQuoteAndFingerprint(IncidentType type, String fingerprint) {
        var legacy = new RepairQuote(17, type, HASH);
        var explicit = new RepairQuote(17, type, HASH, (long) type.cashRepairCents());
        assertEquals(legacy, explicit);
        assertNull(explicit.configuredPriceCents());
        var request = repair(REQUEST, DAY, explicit);
        assertEquals("purchase|" + REQUEST + "|" + fingerprint, request.eventKey());
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2375, 100_000_000})
    void frozenPriceBindsRequestAndCannotBeRepriced(long cents) {
        var quote = new RepairQuote(17, IncidentType.PESTS, HASH, cents);
        var request = repair(REQUEST, DAY, quote);
        assertEquals(cents, request.cents());
        assertEquals(cents, request.configuredPriceCents());
        assertEquals(request, PurchasePrices.DEFAULT.quote(request));
        assertNotEquals(repair(REQUEST, DAY, new RepairQuote(17, IncidentType.PESTS, HASH)).eventKey(), request.eventKey());
        assertEquals(request, new PurchaseRequest(REQUEST, PROPERTY, PAYER, 0, PurchaseRequest.Kind.REPAIR,
                null, null, null, DAY, quote, null, null, cents));
        assertThrows(IllegalArgumentException.class, () -> request.withPriceCents(cents));
        assertThrows(IllegalArgumentException.class, () -> new PurchaseRequest(REQUEST, PROPERTY, PAYER, 0,
                PurchaseRequest.Kind.REPAIR, null, null, null, DAY, quote, null, null, cents + 1));
    }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE, -1, 0, 100_000_001, Long.MAX_VALUE})
    void invalidFrozenPriceIsRejected(long cents) {
        assertThrows(IllegalArgumentException.class, () -> new RepairQuote(17, IncidentType.PESTS, HASH, cents));
    }

    @Test
    void materialsMustCoverFrozenCashPriceAndRatio() {
        var quote = new RepairQuote(17, IncidentType.PESTS, HASH, 2375L);
        var oldItems = ItemRepairQuoteTest.valid();
        assertThrows(IllegalArgumentException.class, () -> new ItemRepairQuote(REQUEST, PROPERTY, PAYER, 0,
                DAY, quote, 2000, 15000, 3000, 3200, "v1", oldItems.lines(), oldItems.returnPlan()));
        assertThrows(IllegalArgumentException.class, () -> new ItemRepairQuote(REQUEST, PROPERTY, PAYER, 0,
                DAY, quote, 2375, 15000, 3563, 3200, "v1", oldItems.lines(), oldItems.returnPlan()));
        var line = ItemRepairQuoteTest.line("PAPER", 1, 3563, 1);
        var stack = ItemRepairQuoteTest.stack(0, 1);
        var accepted = new ItemRepairQuote(REQUEST, PROPERTY, PAYER, 0, DAY, quote, 2375, 15000,
                3563, 3563, "v1", java.util.List.of(line), java.util.List.of(stack));
        assertEquals(2375, accepted.cashCents());
        assertEquals(3563, accepted.requiredValueCents());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"abc", " ", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdefABCDEF",
            "g123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"})
    void snapshotMustBeExactlyLowercaseSha256(String hash) {
        assertThrows(IllegalArgumentException.class, () -> new RepairQuote(1, IncidentType.PESTS, hash));
    }

    @Test
    void hashLengthWhitespaceAndMissingTypeFailClosed() {
        for (String hash : new String[]{HASH.substring(1), HASH + "0", " " + HASH, HASH + "\n"}) {
            assertThrows(IllegalArgumentException.class, () -> new RepairQuote(1, IncidentType.PESTS, hash));
        }
        assertThrows(NullPointerException.class, () -> new RepairQuote(1, null, HASH));
        assertEquals(Long.MAX_VALUE, new RepairQuote(Long.MAX_VALUE, IncidentType.PESTS, HASH).incidentId());
    }

    @Test
    void repairRequiresOnlyRepairTargetAndLegacyKindsForbidIt() {
        RepairQuote quote = new RepairQuote(1, IncidentType.PESTS, HASH);
        assertThrows(IllegalArgumentException.class, () -> repair(REQUEST, DAY, null));
        assertThrows(IllegalArgumentException.class, () -> new PurchaseRequest(REQUEST, PROPERTY, PAYER, 0,
                PurchaseRequest.Kind.REPAIR, null, null, null, DAY));
        assertThrows(IllegalArgumentException.class, () -> new PurchaseRequest(REQUEST, PROPERTY, PAYER, 0,
                PurchaseRequest.Kind.REPAIR, UpgradeType.FACILITIES, null, null, DAY, quote));
        assertThrows(IllegalArgumentException.class, () -> new PurchaseRequest(REQUEST, PROPERTY, PAYER, 0,
                PurchaseRequest.Kind.REPAIR, null, 1, null, DAY, quote));
        assertThrows(IllegalArgumentException.class, () -> new PurchaseRequest(REQUEST, PROPERTY, PAYER, 0,
                PurchaseRequest.Kind.REPAIR, null, null, TENANT, DAY, quote));
        for (PurchaseRequest.Kind kind : new PurchaseRequest.Kind[]{PurchaseRequest.Kind.UPGRADE,
                PurchaseRequest.Kind.MAINTAIN, PurchaseRequest.Kind.EVICT}) {
            PurchaseRequest legacy = legacy(kind);
            assertThrows(IllegalArgumentException.class, () -> new PurchaseRequest(REQUEST, PROPERTY, PAYER, 0,
                    kind, legacy.upgrade(), legacy.targetLevel(), legacy.tenantId(), DAY, quote));
        }
    }

    @Test
    void repairFingerprintBindsIncidentTypeAndSnapshotButActionDeduplicatesTheIncident() {
        PurchaseRequest first = repair(REQUEST, DAY, new RepairQuote(1, IncidentType.PESTS, HASH));
        for (PurchaseRequest changed : new PurchaseRequest[]{
                repair(REQUEST, DAY, new RepairQuote(2, IncidentType.PESTS, HASH)),
                repair(REQUEST, DAY, new RepairQuote(1, IncidentType.PLUMBING, HASH)),
                repair(REQUEST, DAY, new RepairQuote(1, IncidentType.PESTS, "f".repeat(64))),
                repair(UUID.randomUUID(), DAY, first.repair()),
                repair(REQUEST, DAY.plusDays(1), first.repair())}) {
            assertNotEquals(first.eventKey(), changed.eventKey());
            assertEquals(first.repair().incidentId() == changed.repair().incidentId(),
                    first.actionKey().equals(changed.actionKey()));
        }
        assertEquals(first, repair(REQUEST, DAY, first.repair()));
        assertNotEquals(first.actionKey(), legacy(PurchaseRequest.Kind.MAINTAIN).actionKey());
    }

    @ParameterizedTest
    @EnumSource(value = PurchaseRequest.Kind.class, names = {"UPGRADE", "MAINTAIN", "EVICT"})
    void nineArgumentConstructorAndHistoricalFingerprintRemainIdentical(PurchaseRequest.Kind kind) throws Exception {
        PurchaseRequest old = legacy(kind);
        PurchaseRequest canonical = new PurchaseRequest(REQUEST, PROPERTY, PAYER, 0, kind,
                old.upgrade(), old.targetLevel(), old.tenantId(), DAY, null);
        assertEquals(old, canonical);
        assertNull(old.repair());
        String v3Payload = "purchase-v1|" + REQUEST + "|" + PROPERTY + "|" + PAYER + "|0|"
                + kind + "|" + old.upgrade() + "|" + old.targetLevel() + "|" + old.tenantId() + "|" + DAY
                + "|" + old.cents();
        String expected = "purchase|" + REQUEST + "|" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(v3Payload.getBytes(StandardCharsets.US_ASCII)));
        assertEquals(expected, old.eventKey(), "V3 receipts must not gain a null repair suffix");
        assertEquals(expected, canonical.eventKey());
    }

    private static PurchaseRequest repair(UUID request, LocalDate day, RepairQuote quote) {
        return new PurchaseRequest(request, PROPERTY, PAYER, 0, PurchaseRequest.Kind.REPAIR,
                null, null, null, day, quote);
    }

    @ParameterizedTest
    @CsvSource({"PESTS,2000,", "PLUMBING,3500,", "BREAK_IN,5000,", "PESTS,3500,3500",
            "PLUMBING,2375,2375", "BREAK_IN,100000000,100000000"})
    void storedPriceRequiresEitherLegacyTermsOrExplicitCustomTerms(IncidentType type, long cash, Long marker) {
        assertEquals(cash, RepairQuote.storedCents(type, cash, marker));
    }

    @ParameterizedTest
    @CsvSource({"PESTS,2375,", "PESTS,2000,2000", "PESTS,2375,2374", "PESTS,0,0",
            "PESTS,-1,-1", "PESTS,100000001,100000001", "PLUMBING,2000,", "BREAK_IN,5000,3500"})
    void storedCorruptMarkerCannotBeNormalizedAway(IncidentType type, long cash, Long marker) {
        assertThrows(IllegalArgumentException.class, () -> RepairQuote.storedCents(type, cash, marker));
    }

    private static PurchaseRequest legacy(PurchaseRequest.Kind kind) {
        return new PurchaseRequest(REQUEST, PROPERTY, PAYER, 0, kind,
                kind == PurchaseRequest.Kind.UPGRADE ? UpgradeType.ADVERTISING : null,
                kind == PurchaseRequest.Kind.UPGRADE ? 1 : null,
                kind == PurchaseRequest.Kind.EVICT ? TENANT : null, DAY);
    }
}

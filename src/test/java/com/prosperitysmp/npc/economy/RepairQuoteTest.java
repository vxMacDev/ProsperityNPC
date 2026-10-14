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

    private static PurchaseRequest legacy(PurchaseRequest.Kind kind) {
        return new PurchaseRequest(REQUEST, PROPERTY, PAYER, 0, kind,
                kind == PurchaseRequest.Kind.UPGRADE ? UpgradeType.ADVERTISING : null,
                kind == PurchaseRequest.Kind.UPGRADE ? 1 : null,
                kind == PurchaseRequest.Kind.EVICT ? TENANT : null, DAY);
    }
}

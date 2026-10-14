package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.formulas.UpgradeType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.UUID;

import static com.prosperitysmp.npc.economy.PurchaseRequest.Kind;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PurchaseRequestTest {
    private static final UUID REQUEST = new UUID(0, 1);
    private static final UUID PROPERTY = new UUID(0, 2);
    private static final UUID PAYER = new UUID(0, 3);
    private static final UUID TENANT = new UUID(0, 4);
    private static final LocalDate DAY = LocalDate.of(2026, 9, 6);

    @ParameterizedTest
    @CsvSource({"ADVERTISING,1,10000", "ADVERTISING,2,25000", "ADVERTISING,3,50000",
            "FACILITIES,1,15000", "FACILITIES,2,35000", "FACILITIES,3,70000"})
    void upgradeQuoteUsesFixedIntegerCentsAndIncludesTypeAndLevelInAction(
            UpgradeType type, int level, long cents) {
        PurchaseRequest request = request(Kind.UPGRADE, type, level, null);
        assertEquals(cents, request.cents());
        assertEquals(EventSource.UPGRADE, request.source());
        assertEquals(EventSource.Direction.FROM_PUBLIC, request.source().direction());
        assertEquals("UPGRADE|" + type.name() + "|" + level, request.actionKey());
        assertTrue(request.eventKey().matches("purchase\\|" + REQUEST + "\\|[a-f0-9]{64}"));
        assertEquals(request.eventKey(), request(Kind.UPGRADE, type, level, null).eventKey());
    }

    @Test
    void maintenanceAndEvictionHaveDistinctSourceBoundQuotes() {
        PurchaseRequest maintenance = request(Kind.MAINTAIN, null, null, null);
        PurchaseRequest eviction = request(Kind.EVICT, null, null, TENANT);
        assertEquals(1_000L, maintenance.cents());
        assertEquals(1_000L, eviction.cents());
        assertEquals(EventSource.REPAIR, maintenance.source());
        assertEquals(EventSource.EVICTION, eviction.source());
        assertEquals(EventSource.Direction.FROM_PUBLIC, maintenance.source().direction());
        assertEquals(EventSource.Direction.FROM_PUBLIC, eviction.source().direction());
        assertEquals("MAINTAIN|2026-09-06", maintenance.actionKey());
        assertEquals("EVICT|" + TENANT, eviction.actionKey());
    }

    @Test
    void insuranceBindsCapacityAndUsesTheExistingFullPriceSource() {
        PurchaseRequest first = new PurchaseRequest(REQUEST, PROPERTY, PAYER, 0, Kind.INSURANCE,
                null, null, null, DAY, null, null, new PurchaseRequest.InsuranceQuote(1));
        PurchaseRequest fourth = new PurchaseRequest(REQUEST, PROPERTY, PAYER, 0, Kind.INSURANCE,
                null, null, null, DAY, null, null, new PurchaseRequest.InsuranceQuote(4));
        assertEquals(1400, first.cents()); assertEquals(2000, fourth.cents());
        assertEquals(EventSource.INSURANCE, first.source());
        assertEquals(EventSource.Direction.FROM_PUBLIC, first.source().direction());
        assertNotEquals(first.eventKey(), fourth.eventKey());
        assertEquals("INSURANCE|2026-09-06", first.actionKey());
        assertThrows(IllegalArgumentException.class, () -> new PurchaseRequest.InsuranceQuote(0));
        assertThrows(IllegalArgumentException.class, () -> new PurchaseRequest.InsuranceQuote(5));
        assertThrows(IllegalArgumentException.class, () -> request(Kind.INSURANCE, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new PurchaseRequest(REQUEST, PROPERTY, PAYER, 0,
                Kind.MAINTAIN, null, null, null, DAY, null, null, new PurchaseRequest.InsuranceQuote(1)));
    }

    @Test
    void preInsuranceMaintenanceFingerprintIsByteForByteUnchanged() {
        assertEquals("purchase|"+REQUEST+"|a72a9519bd031d3e0b4312f5ba80a84c34a2b53f8e0d2a486fdd9a430d7adc93",
                request(Kind.MAINTAIN,null,null,null).eventKey());
        assertEquals(request(Kind.MAINTAIN,null,null,null),new PurchaseRequest(REQUEST,PROPERTY,PAYER,0,
                Kind.MAINTAIN,null,null,null,DAY,null,null,null));
    }

    @ParameterizedTest
    @EnumSource(value = Kind.class, names = {"UPGRADE", "MAINTAIN", "EVICT"})
    void freshRequestUuidChangesEventIdentityButNotActionIdentity(Kind kind) {
        PurchaseRequest first = valid(kind);
        PurchaseRequest retry = new PurchaseRequest(UUID.randomUUID(), PROPERTY, PAYER, 0,
                first.kind(), first.upgrade(), first.targetLevel(), first.tenantId(), DAY);
        assertNotEquals(first, retry);
        assertNotEquals(first.eventKey(), retry.eventKey());
        assertEquals(first.actionKey(), retry.actionKey());
        assertEquals(first.cents(), retry.cents());
    }

    @Test
    void actionsDistinguishUpgradeTypesLevelsDaysAndExactTenants() {
        assertNotEquals(request(Kind.UPGRADE, UpgradeType.ADVERTISING, 1, null).actionKey(),
                request(Kind.UPGRADE, UpgradeType.FACILITIES, 1, null).actionKey());
        assertNotEquals(request(Kind.UPGRADE, UpgradeType.ADVERTISING, 1, null).actionKey(),
                request(Kind.UPGRADE, UpgradeType.ADVERTISING, 2, null).actionKey());
        assertNotEquals(valid(Kind.MAINTAIN).actionKey(), new PurchaseRequest(REQUEST, PROPERTY, PAYER,
                0, Kind.MAINTAIN, null, null, null, DAY.plusDays(1)).actionKey());
        assertNotEquals(valid(Kind.EVICT).actionKey(),
                request(Kind.EVICT, null, null, UUID.randomUUID()).actionKey());
    }

    @Test
    void ledgerKeyBindsSameUuidToTheCompleteTypedQuote() {
        PurchaseRequest original = valid(Kind.EVICT);
        for (PurchaseRequest changed : new PurchaseRequest[]{
                new PurchaseRequest(REQUEST, UUID.randomUUID(), PAYER, 0, Kind.EVICT, null, null, TENANT, DAY),
                new PurchaseRequest(REQUEST, PROPERTY, UUID.randomUUID(), 0, Kind.EVICT, null, null, TENANT, DAY),
                new PurchaseRequest(REQUEST, PROPERTY, PAYER, 1, Kind.EVICT, null, null, TENANT, DAY),
                new PurchaseRequest(REQUEST, PROPERTY, PAYER, 0, Kind.EVICT, null, null, TENANT, DAY.plusDays(1)),
                request(Kind.EVICT, null, null, UUID.randomUUID()),
                valid(Kind.MAINTAIN), valid(Kind.UPGRADE),
                request(Kind.UPGRADE, UpgradeType.FACILITIES, 1, null)}) {
            assertNotEquals(original.eventKey(), changed.eventKey());
            assertTrue(changed.eventKey().length() <= 160, "fits V1 ledger key column");
        }
        assertNotEquals(valid(Kind.UPGRADE).eventKey(), request(Kind.UPGRADE, UpgradeType.FACILITIES, 1, null).eventKey());
        assertNotEquals(valid(Kind.UPGRADE).eventKey(), request(Kind.UPGRADE, UpgradeType.ADVERTISING, 2, null).eventKey());
    }

    @ParameterizedTest
    @ValueSource(strings = {"request", "property", "payer", "kind", "day"})
    void requiredIdentityAndDayCannotBeNull(String field) {
        assertThrows(NullPointerException.class, () -> new PurchaseRequest(
                field.equals("request") ? null : REQUEST, field.equals("property") ? null : PROPERTY,
                field.equals("payer") ? null : PAYER, 0, field.equals("kind") ? null : Kind.MAINTAIN,
                null, null, null, field.equals("day") ? null : DAY));
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1})
    void negativeOwnershipEpochIsRejected(int epoch) {
        assertThrows(IllegalArgumentException.class, () -> new PurchaseRequest(REQUEST, PROPERTY, PAYER,
                epoch, Kind.MAINTAIN, null, null, null, DAY));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 10000})
    void dayMustFitTheDurableFourDigitYear(int year) {
        assertThrows(IllegalArgumentException.class, () -> new PurchaseRequest(REQUEST, PROPERTY, PAYER,
                0, Kind.MAINTAIN, null, null, null, LocalDate.of(year, 1, 1)));
    }

    @Test
    void durableDateAndEpochBoundariesAreAccepted() {
        for (LocalDate day : new LocalDate[]{LocalDate.of(1, 1, 1), LocalDate.of(9999, 12, 31)}) {
            PurchaseRequest request = new PurchaseRequest(REQUEST, PROPERTY, PAYER, Integer.MAX_VALUE,
                    Kind.MAINTAIN, null, null, null, day);
            assertEquals(day, request.day());
            assertEquals("MAINTAIN|" + day, request.actionKey());
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 4, Integer.MAX_VALUE})
    void upgradeTargetMustBeAnInstalledLevel(int level) {
        assertThrows(IllegalArgumentException.class,
                () -> request(Kind.UPGRADE, UpgradeType.ADVERTISING, level, null));
    }

    @Test
    void upgradeRequiresTypeAndLevelAndForbidsTenant() {
        assertThrows(IllegalArgumentException.class, () -> request(Kind.UPGRADE, null, 1, null));
        assertThrows(IllegalArgumentException.class,
                () -> request(Kind.UPGRADE, UpgradeType.ADVERTISING, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> request(Kind.UPGRADE, UpgradeType.ADVERTISING, 1, TENANT));
    }

    @Test
    void maintenanceForbidsAllTargetFields() {
        assertThrows(IllegalArgumentException.class, () -> request(Kind.MAINTAIN, UpgradeType.FACILITIES, null, null));
        assertThrows(IllegalArgumentException.class, () -> request(Kind.MAINTAIN, null, 1, null));
        assertThrows(IllegalArgumentException.class, () -> request(Kind.MAINTAIN, null, null, TENANT));
    }

    @Test
    void evictionRequiresExactTenantAndForbidsUpgradeFields() {
        assertThrows(IllegalArgumentException.class, () -> request(Kind.EVICT, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> request(Kind.EVICT, UpgradeType.FACILITIES, null, TENANT));
        assertThrows(IllegalArgumentException.class, () -> request(Kind.EVICT, null, 1, TENANT));
    }

    private static PurchaseRequest valid(Kind kind) {
        return request(kind, kind == Kind.UPGRADE ? UpgradeType.ADVERTISING : null,
                kind == Kind.UPGRADE ? 1 : null, kind == Kind.EVICT ? TENANT : null);
    }

    private static PurchaseRequest request(Kind kind, UpgradeType upgrade, Integer level, UUID tenant) {
        return new PurchaseRequest(REQUEST, PROPERTY, PAYER, 0, kind, upgrade, level, tenant, DAY);
    }
}

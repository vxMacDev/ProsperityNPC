package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.clock.EconomyClock;
import com.prosperitysmp.npc.formulas.UpgradeType;
import com.prosperitysmp.npc.property.AutoCareRepository;
import com.prosperitysmp.npc.property.Property;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The admission checks every payment request makes on the main thread before it looks at the server, the plugin or
 * the player. The runtime's constructor needs the running plugin, so the object is made without it: its plugin is
 * absent, which means any request that gets past these checks stops at the first server readiness read. The
 * main-thread check answers from a flag the test sets.
 */
class UtilityPaymentRuntimeAdmissionTest {
    private static final Instant NOW = Instant.parse("2026-10-14T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.parse("2026-10-14");
    private static final UUID HOME = new UUID(0, 0x10);
    private static final UUID OWNER = new UUID(0, 0x20);
    private static final UUID TENANT = new UUID(0, 0x30);
    private static final UUID INVOICE = new UUID(0, 0x40);

    private Field serverField;
    private Object oldServer;
    private boolean primary = true;
    private UtilityPaymentRuntime runtime;
    private final AtomicInteger authorityReads = new AtomicInteger();
    private final List<String> replies = new ArrayList<>();
    private final BooleanSupplier authorized = () -> {
        authorityReads.incrementAndGet();
        return true;
    };
    private final Consumer<String> reply = replies::add;

    @BeforeEach
    void makeRuntime() throws Exception {
        serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        oldServer = serverField.get(null);
        serverField.set(null, Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
                (p, m, a) -> {
                    if (m.getName().equals("isPrimaryThread")) return primary;
                    throw new AssertionError(m.getName());
                }));
        Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        Method allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);
        runtime = (UtilityPaymentRuntime) allocate.invoke(unsafe, UtilityPaymentRuntime.class);
        set("days", new EconomyClock(Clock.fixed(NOW, ZoneOffset.UTC), ZoneOffset.UTC, 0));
        set("rentHeld", List.of());
        set("startupReady", true);
    }

    @AfterEach
    void restoreServer() throws Exception {
        serverField.set(null, oldServer);
    }

    private void set(String name, Object value) throws Exception {
        Field field = UtilityPaymentRuntime.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(runtime, value);
    }

    private static Property home(String region) {
        return new Property(UUID.nameUUIDFromBytes(region.getBytes()), new UUID(0, 1), region, "market", "house",
                2, 1000, 1200, OWNER, 1, 100, true);
    }

    private static AutoCareRepository.Claim claim(AutoCareRepository.State state, Long eventId, LocalDate day) {
        return new AutoCareRepository.Claim(new UUID(0, 0x50), HOME, OWNER, 1, 1, day,
                AutoCareRepository.Action.UTILITY, null, 1500, state, eventId, NOW, null);
    }

    /** Every request entry point with the given maximum, each answering whether it was admitted. */
    private List<Boolean> everyRequest(long maximumCents) {
        return List.of(
                runtime.request(HOME, OWNER, maximumCents, authorized, reply),
                runtime.requestRepair(HOME, OWNER, 9, maximumCents, authorized, reply),
                runtime.requestPurchase(HOME, OWNER, PurchaseRequest.Kind.MAINTAIN, null, null, maximumCents, authorized, reply),
                runtime.requestPurchase(HOME, OWNER, PurchaseRequest.Kind.UPGRADE, UpgradeType.FACILITIES, 2, maximumCents,
                        authorized, reply),
                runtime.requestEviction(HOME, OWNER, TENANT, maximumCents, authorized, reply),
                runtime.requestInsurance(HOME, OWNER, maximumCents, authorized, reply),
                runtime.requestBill(HOME, OWNER, INVOICE, maximumCents, authorized, reply),
                runtime.requestBillCollection(HOME, OWNER, INVOICE, maximumCents, authorized, paid -> { }));
    }

    /** The request got past admission: it stopped at the first server readiness read, which needs the plugin. */
    private static void reachesServerReadiness(Runnable request) {
        NullPointerException stop = assertThrows(NullPointerException.class, request::run);
        assertEquals("ready", stop.getStackTrace()[0].getMethodName());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, -100_000, Long.MIN_VALUE})
    void aMaximumOfZeroOrLessIsRefusedByEveryRequestBeforeAnyOtherCheck(long maximumCents) {
        assertEquals(List.of(false, false, false, false, false, false, false, false), everyRequest(maximumCents));
        assertEquals(0, authorityReads.get());
        assertEquals(List.of(), replies);
    }

    @Test
    void aBusyRuntimeRefusesEveryRequestWithoutReadingAuthority() throws Exception {
        set("busy", true);
        assertEquals(List.of(false, false, false, false, false, false, false, false), everyRequest(1000));
        assertFalse(runtime.requestRentBatch(authorized, reply));
        assertEquals(0, authorityReads.get());
        assertEquals(List.of(), replies);
    }

    @Test
    void aRecoveryHoldRefusesEveryRequestWithoutReadingAuthority() throws Exception {
        set("recovery", true);
        assertEquals(List.of(false, false, false, false, false, false, false, false), everyRequest(1000));
        assertFalse(runtime.requestRentBatch(authorized, reply));
        assertEquals(0, authorityReads.get());
    }

    @Test
    void untilStartupRecoveryFinishesNothingIsAdmitted() throws Exception {
        set("startupReady", false);
        assertFalse(runtime.request(HOME, OWNER, 1000, authorized, reply));
        assertFalse(runtime.requestRepair(HOME, OWNER, 9, 1000, authorized, reply));
        assertFalse(runtime.requestPurchase(HOME, OWNER, PurchaseRequest.Kind.MAINTAIN, null, null, 1000, authorized, reply));
        assertFalse(runtime.requestEviction(HOME, OWNER, TENANT, 1000, authorized, reply));
        assertFalse(runtime.requestInsurance(HOME, OWNER, 1000, authorized, reply));
        assertFalse(runtime.requestAutoCare(claim(AutoCareRepository.State.RESERVED, null, TODAY), authorized, paid -> { }));
        assertFalse(runtime.requestRentBatch(authorized, reply));
        assertEquals(0, authorityReads.get());
    }

    @Test
    void aClosedRuntimeAdmitsNothing() throws Exception {
        set("closed", true);
        assertFalse(runtime.request(HOME, OWNER, 1000, authorized, reply));
        assertFalse(runtime.requestInsurance(HOME, OWNER, 1000, authorized, reply));
        assertFalse(runtime.requestEviction(HOME, OWNER, TENANT, 1000, authorized, reply));
        assertFalse(runtime.requestRentBatch(authorized, reply));
        assertEquals(0, authorityReads.get());
    }

    @Test
    void anOpenRuntimeTakesAPositiveRequestOnToTheServerChecks() {
        reachesServerReadiness(() -> runtime.request(HOME, OWNER, 1, authorized, reply));
        reachesServerReadiness(() -> runtime.requestInsurance(HOME, OWNER, 1000, authorized, reply));
        reachesServerReadiness(() -> runtime.requestEviction(HOME, OWNER, TENANT, 1000, authorized, reply));
        reachesServerReadiness(() -> runtime.requestRentBatch(authorized, reply));
        assertEquals(0, authorityReads.get(), "authority is read after the server checks");
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void aRepairNeedsARealIncident(long incidentId) {
        assertFalse(runtime.requestRepair(HOME, OWNER, incidentId, 2000, authorized, reply));
        assertEquals(0, authorityReads.get());
    }

    @Test
    void aRepairForARealIncidentGoesOnToTheServerChecks() {
        reachesServerReadiness(() -> runtime.requestRepair(HOME, OWNER, 1, 2000, authorized, reply));
    }

    static Stream<Arguments> purchaseShapes() {
        return Stream.of(
                Arguments.of(PurchaseRequest.Kind.UPGRADE, UpgradeType.ADVERTISING, 1, true),
                Arguments.of(PurchaseRequest.Kind.UPGRADE, UpgradeType.FACILITIES, 3, true),
                Arguments.of(PurchaseRequest.Kind.MAINTAIN, null, null, true),
                Arguments.of(PurchaseRequest.Kind.UPGRADE, UpgradeType.ADVERTISING, 0, false),
                Arguments.of(PurchaseRequest.Kind.UPGRADE, UpgradeType.ADVERTISING, -1, false),
                Arguments.of(PurchaseRequest.Kind.UPGRADE, UpgradeType.ADVERTISING, 4, false),
                Arguments.of(PurchaseRequest.Kind.UPGRADE, null, 1, false),
                Arguments.of(PurchaseRequest.Kind.UPGRADE, UpgradeType.FACILITIES, null, false),
                Arguments.of(PurchaseRequest.Kind.MAINTAIN, UpgradeType.FACILITIES, null, false),
                Arguments.of(PurchaseRequest.Kind.MAINTAIN, null, 1, false),
                Arguments.of(PurchaseRequest.Kind.EVICT, null, null, false),
                Arguments.of(PurchaseRequest.Kind.REPAIR, null, null, false),
                Arguments.of(PurchaseRequest.Kind.INSURANCE, null, null, false),
                Arguments.of(null, null, null, false));
    }

    @ParameterizedTest(name = "{0} {1} level {2} admitted={3}")
    @MethodSource("purchaseShapes")
    void onlyAnUpgradeToALevelFromOneToThreeOrAPlainMaintenanceIsAPurchase(PurchaseRequest.Kind kind, UpgradeType upgrade,
                                                                           Integer level, boolean admitted) {
        Runnable request = () -> runtime.requestPurchase(HOME, OWNER, kind, upgrade, level, 5000, authorized, reply);
        if (admitted) reachesServerReadiness(request);
        else assertFalse(runtime.requestPurchase(HOME, OWNER, kind, upgrade, level, 5000, authorized, reply));
        assertEquals(0, authorityReads.get());
    }

    @Test
    void missingRequiredArgumentsAreRefusedEvenWhenTheRuntimeIsBusy() throws Exception {
        set("busy", true);
        assertThrows(NullPointerException.class, () -> runtime.requestEviction(HOME, OWNER, null, 1000, authorized, reply));
        assertThrows(NullPointerException.class, () -> runtime.requestBill(HOME, OWNER, null, 1000, authorized, reply));
        assertThrows(NullPointerException.class,
                () -> runtime.requestBillCollection(HOME, OWNER, null, 1000, authorized, paid -> { }));
        assertThrows(NullPointerException.class,
                () -> runtime.requestBillCollection(HOME, OWNER, INVOICE, 1000, authorized, null));
        assertThrows(NullPointerException.class, () -> runtime.request(null, OWNER, 0, authorized, reply));
        assertThrows(NullPointerException.class, () -> runtime.request(HOME, null, 0, authorized, reply));
        assertThrows(NullPointerException.class, () -> runtime.request(HOME, OWNER, 0, null, reply));
        assertThrows(NullPointerException.class, () -> runtime.request(HOME, OWNER, 0, authorized, null));
        assertThrows(NullPointerException.class, () -> runtime.requestRentBatch(null, reply));
        assertThrows(NullPointerException.class, () -> runtime.requestRentBatch(authorized, null));
        assertThrows(NullPointerException.class, () -> runtime.requestAutoCare(null, authorized, paid -> { }));
        assertThrows(NullPointerException.class,
                () -> runtime.requestAutoCare(claim(AutoCareRepository.State.RESERVED, null, TODAY), authorized, null));
        assertEquals(0, authorityReads.get());
    }

    static Stream<Arguments> autoCareClaims() {
        return Stream.of(
                Arguments.of(AutoCareRepository.State.BOUND, null, TODAY),
                Arguments.of(AutoCareRepository.State.SPENT, null, TODAY),
                Arguments.of(AutoCareRepository.State.DECLINED, null, TODAY),
                Arguments.of(AutoCareRepository.State.HELD, null, TODAY),
                Arguments.of(AutoCareRepository.State.RESERVED, 7L, TODAY),
                Arguments.of(AutoCareRepository.State.RESERVED, null, TODAY.minusDays(1)),
                Arguments.of(AutoCareRepository.State.RESERVED, null, TODAY.plusDays(1)));
    }

    @ParameterizedTest(name = "{0} event {1} day {2}")
    @MethodSource("autoCareClaims")
    void onlyAFreshlyReservedClaimForTodayEntersTheCoordinator(AutoCareRepository.State state, Long eventId, LocalDate day) {
        assertFalse(runtime.requestAutoCare(claim(state, eventId, day), authorized, paid -> { }));
        assertEquals(0, authorityReads.get());
    }

    @Test
    void aFreshlyReservedClaimForTodayGoesOnToTheServerChecks() {
        reachesServerReadiness(() -> runtime.requestAutoCare(claim(AutoCareRepository.State.RESERVED, null, TODAY),
                authorized, paid -> { }));
    }

    @Test
    void everyEntryPointRefusesToRunOffTheMainThread() {
        primary = false;
        List<Runnable> calls = List.of(
                () -> runtime.request(HOME, OWNER, 1000, authorized, reply),
                () -> runtime.requestRepair(HOME, OWNER, 1, 1000, authorized, reply),
                () -> runtime.requestPurchase(HOME, OWNER, PurchaseRequest.Kind.MAINTAIN, null, null, 1000, authorized, reply),
                () -> runtime.requestEviction(HOME, OWNER, TENANT, 1000, authorized, reply),
                () -> runtime.requestInsurance(HOME, OWNER, 1000, authorized, reply),
                () -> runtime.requestBill(HOME, OWNER, INVOICE, 1000, authorized, reply),
                () -> runtime.requestBillCollection(HOME, OWNER, INVOICE, 1000, authorized, paid -> { }),
                () -> runtime.requestAutoCare(claim(AutoCareRepository.State.RESERVED, null, TODAY), authorized, paid -> { }),
                () -> runtime.requestRentBatch(authorized, reply),
                () -> runtime.recoveryHeld(),
                () -> runtime.rentHeldStatus(),
                () -> runtime.status());
        for (Runnable call : calls) {
            IllegalStateException refused = assertThrows(IllegalStateException.class, call::run);
            assertEquals("Main thread required", refused.getMessage());
        }
        assertEquals(0, authorityReads.get());
    }

    @Test
    void theRecoveryHoldIsReportedAsItIs() throws Exception {
        assertFalse(runtime.recoveryHeld());
        set("recovery", true);
        assertTrue(runtime.recoveryHeld());
    }

    @Test
    void theStatusNamesTheStrongestHoldFirst() throws Exception {
        set("busy", true);
        assertEquals("busy", runtime.status());
        set("startupReady", false);
        assertEquals("startup-recovery-pending", runtime.status());
        set("recovery", true);
        assertEquals("recovery-held", runtime.status());
        set("closed", true);
        assertEquals("closed", runtime.status());
    }

    @Test
    void nothingHeldMeansNoRentHeldLine() throws Exception {
        assertNull(runtime.rentHeldStatus());
        set("rentHeldDay", TODAY);
        assertNull(runtime.rentHeldStatus(), "an empty list is not a hold");
    }

    @Test
    void aHoldFromAnotherDayOrWithNoDayIsNotShown() throws Exception {
        set("rentHeld", List.of(home("r1")));
        assertNull(runtime.rentHeldStatus(), "no day recorded");
        set("rentHeldDay", TODAY.minusDays(1));
        assertNull(runtime.rentHeldStatus());
        set("rentHeldDay", TODAY.plusDays(1));
        assertNull(runtime.rentHeldStatus());
    }

    @Test
    void oneHeldHomeIsNamedInTheSingular() throws Exception {
        set("rentHeld", List.of(home("plot-a")));
        set("rentHeldDay", TODAY);
        assertEquals("Rent held: owner unavailable for 1 property (plot-a)", runtime.rentHeldStatus());
    }

    @Test
    void fiveHeldHomesAreAllNamed() throws Exception {
        set("rentHeld", List.of(home("r1"), home("r2"), home("r3"), home("r4"), home("r5")));
        set("rentHeldDay", TODAY);
        assertEquals("Rent held: owner unavailable for 5 properties (r1, r2, r3, r4, r5)", runtime.rentHeldStatus());
    }

    @Test
    void moreThanFiveHeldHomesNameTheFirstFiveAndCountTheRest() throws Exception {
        set("rentHeld", List.of(home("r1"), home("r2"), home("r3"), home("r4"), home("r5"), home("r6"), home("r7")));
        set("rentHeldDay", TODAY);
        assertEquals("Rent held: owner unavailable for 7 properties (r1, r2, r3, r4, r5, +2 more)", runtime.rentHeldStatus());
    }
}

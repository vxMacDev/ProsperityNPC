package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.commerce.CommerceCatalogue;
import com.prosperitysmp.npc.commerce.CommerceOrder;
import com.prosperitysmp.npc.commerce.CommerceOrderRepository;
import com.prosperitysmp.npc.resident.ResidentProfile;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What a commerce payment request answers before it reads the server, the plugin or the player's wallet. The
 * runtime's constructor needs the running plugin, so the object is made without it: a request that gets past these
 * checks stops at the first server read inside available(). The main-thread check answers from a flag the test sets.
 */
class CommercePaymentRuntimeAdmissionTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 14);
    private static final UUID PLAYER = new UUID(0, 1), NONCE = new UUID(0, 2), RESIDENT = new UUID(0, 3);

    private Field serverField;
    private Object oldServer;
    private boolean primary = true;
    private CommercePaymentRuntime runtime;
    private final AtomicInteger authorityReads = new AtomicInteger();
    private final AtomicInteger playerCalls = new AtomicInteger();
    private final List<CommercePaymentRuntime.Result> replies = new ArrayList<>();
    private final BooleanSupplier authorized = () -> {
        authorityReads.incrementAndGet();
        return true;
    };
    private final Consumer<CommercePaymentRuntime.Result> reply = replies::add;
    private final CommerceOrderRepository.DebitCheck partnerCheck = c -> true;
    private Player player;
    private CommerceCatalogue.Entry entry;
    private CommerceOrder order;

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
        runtime = (CommercePaymentRuntime) allocate.invoke(unsafe, CommercePaymentRuntime.class);
        set("ready", true);
        player = (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (p, m, a) -> {
                    playerCalls.incrementAndGet();
                    throw new AssertionError(m.getName());
                });
        entry = CommerceCatalogue.entries(ResidentProfile.Occupation.SHOPKEEPER).get(1);
        order = new CommerceOrder(CommerceOrder.idFor(NONCE, PLAYER, entry.source(), DAY), NONCE, PLAYER, RESIDENT,
                entry.source(), entry.id(), entry.material(), entry.quantity(), entry.cost(1_000), DAY,
                Instant.parse("2026-10-14T12:00:00Z"), entry.delaySeconds());
    }

    @AfterEach
    void restoreServer() throws Exception {
        serverField.set(null, oldServer);
    }

    private void set(String name, Object value) throws Exception {
        Field field = CommercePaymentRuntime.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(runtime, value);
    }

    private List<CommercePaymentRuntime.Status> statuses() {
        return replies.stream().map(CommercePaymentRuntime.Result::status).toList();
    }

    /** The request got past admission: it stopped at the first server read, which needs the plugin. */
    private static void reachesServerReads(Runnable request) {
        NullPointerException stop = assertThrows(NullPointerException.class, request::run);
        assertEquals("available", stop.getStackTrace()[0].getMethodName());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 40, 60})
    void aPartnerRateWithoutItsCheckIsRefusedAsUnavailable(int discount) {
        assertFalse(runtime.request(player, order, entry, discount, null, authorized, reply));
        assertEquals(List.of(CommercePaymentRuntime.Status.UNAVAILABLE), statuses());
        assertNull(replies.getFirst().receipt());
        assertEquals(0, authorityReads.get());
        assertEquals(0, playerCalls.get());
    }

    @Test
    void theMissingPartnerCheckIsReportedAsUnavailableEvenWhileSettling() throws Exception {
        set("busy", true);
        assertFalse(runtime.request(player, order, entry, 25, null, authorized, reply));
        assertEquals(List.of(CommercePaymentRuntime.Status.UNAVAILABLE), statuses());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void noPartnerRateNeedsNoCheckAndGoesOnToTheServerReads(int discount) {
        reachesServerReads(() -> runtime.request(player, order, entry, discount, null, authorized, reply));
        assertEquals(List.of(), replies);
    }

    @Test
    void aPartnerRateWithItsCheckGoesOnToTheServerReads() {
        reachesServerReads(() -> runtime.request(player, order, entry, 40, partnerCheck, authorized, reply));
        reachesServerReads(() -> runtime.request(player, order, entry, authorized, reply));
        assertEquals(List.of(), replies);
        assertEquals(0, authorityReads.get(), "authority is read after the server checks");
    }

    @Test
    void beforeStartupFinishesARequestIsUnavailable() throws Exception {
        set("ready", false);
        assertFalse(runtime.request(player, order, entry, authorized, reply));
        assertEquals(List.of(CommercePaymentRuntime.Status.UNAVAILABLE), statuses());
        assertEquals(0, authorityReads.get());
        assertEquals(0, playerCalls.get());
    }

    @Test
    void aRecoveryHoldMakesARequestUnavailable() throws Exception {
        set("held", true);
        assertFalse(runtime.request(player, order, entry, authorized, reply));
        assertEquals(List.of(CommercePaymentRuntime.Status.UNAVAILABLE), statuses());
        assertEquals(0, authorityReads.get());
    }

    @Test
    void aClosedRuntimeMakesARequestUnavailable() throws Exception {
        set("closed", true);
        assertFalse(runtime.request(player, order, entry, 40, partnerCheck, authorized, reply));
        assertEquals(List.of(CommercePaymentRuntime.Status.UNAVAILABLE), statuses());
    }

    @Test
    void aSettlingRuntimeAnswersBusyRatherThanUnavailable() throws Exception {
        set("ready", false);
        set("busy", true);
        assertFalse(runtime.request(player, order, entry, authorized, reply));
        set("held", true);
        assertFalse(runtime.request(player, order, entry, authorized, reply));
        assertEquals(List.of(CommercePaymentRuntime.Status.BUSY, CommercePaymentRuntime.Status.BUSY), statuses());
        assertEquals(0, authorityReads.get());
    }

    @Test
    void eachRefusedRequestRepliesExactlyOnce() throws Exception {
        set("held", true);
        for (int i = 0; i < 3; i++) assertFalse(runtime.request(player, order, entry, authorized, reply));
        assertEquals(3, replies.size());
    }

    @Test
    void availableIsFalseWhileClosedHeldOrStarting() throws Exception {
        set("ready", false);
        assertFalse(runtime.available());
        set("ready", true);
        set("held", true);
        assertFalse(runtime.available());
        set("held", false);
        set("closed", true);
        assertFalse(runtime.available());
    }

    @Test
    void theStatusNamesTheStrongestHoldFirst() throws Exception {
        set("busy", true);
        assertEquals("SETTLING", runtime.status());
        set("ready", false);
        assertEquals("STARTUP_PENDING", runtime.status());
        set("held", true);
        assertEquals("RECOVERY_HELD", runtime.status());
        set("closed", true);
        assertEquals("CLOSED", runtime.status());
    }

    @Test
    void missingArgumentsAreRefusedBeforeAnyReply() throws Exception {
        set("held", true);
        assertThrows(NullPointerException.class, () -> runtime.request(null, order, entry, authorized, reply));
        assertThrows(NullPointerException.class, () -> runtime.request(player, null, entry, authorized, reply));
        assertThrows(NullPointerException.class, () -> runtime.request(player, order, null, authorized, reply));
        assertThrows(NullPointerException.class, () -> runtime.request(player, order, entry, null, reply));
        assertThrows(NullPointerException.class, () -> runtime.request(player, order, entry, authorized, null));
        assertThrows(NullPointerException.class, () -> runtime.request(player, order, entry, 40, null, authorized, null));
        assertEquals(List.of(), replies);
    }

    @Test
    void offTheMainThreadNothingRunsAndNothingIsReplied() {
        primary = false;
        List<Runnable> calls = List.of(
                () -> runtime.request(player, order, entry, authorized, reply),
                () -> runtime.request(player, order, entry, 40, null, authorized, reply),
                () -> runtime.available(),
                () -> runtime.status());
        for (Runnable call : calls) {
            IllegalStateException refused = assertThrows(IllegalStateException.class, call::run);
            assertEquals("Commerce settlement requires main thread", refused.getMessage());
        }
        assertEquals(List.of(), replies);
    }
}

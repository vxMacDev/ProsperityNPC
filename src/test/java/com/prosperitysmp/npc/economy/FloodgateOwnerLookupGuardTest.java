package com.prosperitysmp.npc.economy;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.link.PlayerLink;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** The owner lookup's argument checks and the answers that end a pass before or during an ask. */
class FloodgateOwnerLookupGuardTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 13);
    private static final UUID JAVA = UUID.fromString("7a1b2c3d-0000-4000-8000-0000000000b1");

    private final AtomicInteger playerLinkCalls = new AtomicInteger();
    private final AtomicReference<PlayerLink> link = new AtomicReference<>();
    private final List<String> info = Collections.synchronizedList(new ArrayList<>());
    private final List<UUID> saved = Collections.synchronizedList(new ArrayList<>());
    private final FloodgateApi api = (FloodgateApi) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{FloodgateApi.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "isFloodgateId" -> ((UUID) args[0]).getMostSignificantBits() == 0;
                case "getPlayerLink" -> { playerLinkCalls.incrementAndGet(); yield link.get(); }
                default -> throw new AssertionError("Unexpected Floodgate call: " + method.getName());
            });
    private final FloodgateOwnerLookup lookup = new FloodgateOwnerLookup(api, Duration.ofMillis(200), Runnable::run, info::add);

    private static UUID bedrock(int n) { return new UUID(0, 0x0009_0000_0000L + n); }

    private static PlayerLink link(Function<UUID, Object> answer) {
        return (PlayerLink) Proxy.newProxyInstance(FloodgateOwnerLookupGuardTest.class.getClassLoader(), new Class<?>[]{PlayerLink.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isEnabled" -> true;
                    case "getLinkedPlayer" -> answer.apply((UUID) args[0]);
                    default -> throw new AssertionError("Unexpected PlayerLink call: " + method.getName());
                });
    }

    @Test
    void aZeroOrNegativeTimeoutIsRefused() {
        assertEquals("Lookup timeout", assertThrows(IllegalArgumentException.class,
                () -> new FloodgateOwnerLookup(api, Duration.ZERO, Runnable::run, info::add)).getMessage());
        assertEquals("Lookup timeout", assertThrows(IllegalArgumentException.class,
                () -> new FloodgateOwnerLookup(api, Duration.ofMillis(-1), Runnable::run, info::add)).getMessage());
        assertDoesNotThrow(() -> new FloodgateOwnerLookup(api, Duration.ofNanos(1), Runnable::run, info::add));
    }

    @Test
    void missingConstructorArgumentsAreRefused() {
        assertThrows(NullPointerException.class, () -> new FloodgateOwnerLookup(null, Duration.ofSeconds(1), Runnable::run, info::add));
        assertThrows(NullPointerException.class, () -> new FloodgateOwnerLookup(api, null, Runnable::run, info::add));
        assertThrows(NullPointerException.class, () -> new FloodgateOwnerLookup(api, Duration.ofSeconds(1), null, info::add));
        assertThrows(NullPointerException.class, () -> new FloodgateOwnerLookup(api, Duration.ofSeconds(1), Runnable::run, null));
    }

    @Test
    void missingStartArgumentsAreRefusedEvenWithTheFlagOff() {
        assertThrows(NullPointerException.class, () -> lookup.start(false, null, List.of(), 1, (j, b) -> { }));
        assertThrows(NullPointerException.class, () -> lookup.start(false, DAY, null, 1, (j, b) -> { }));
        assertThrows(NullPointerException.class, () -> lookup.start(false, DAY, List.of(), 1, null));
    }

    @Test
    void onlyJavaOrNullOwnersNeverReachPlayerLink() {
        assertEquals(0, lookup.start(true, DAY, Arrays.asList(JAVA, null, new UUID(5, 5)), 50, (j, b) -> saved.add(j)));
        assertEquals(0, playerLinkCalls.get());
        assertTrue(info.isEmpty());
    }

    @Test
    void aNegativeLimitAsksNobody() {
        link.set(link(owner -> { throw new AssertionError("asked"); }));
        assertEquals(0, lookup.start(true, DAY, List.of(bedrock(1)), -1, (j, b) -> saved.add(j)));
        assertEquals(0, playerLinkCalls.get());
    }

    @Test
    void noPlayerLinkAsksNobodyAndMarksNobodyAsked() {
        assertEquals(0, lookup.start(true, DAY, List.of(bedrock(1)), 50, (j, b) -> saved.add(j)));
        assertEquals(1, playerLinkCalls.get());
        link.set(link(owner -> CompletableFuture.completedFuture(null)));
        assertEquals(1, lookup.start(true, DAY, List.of(bedrock(1)), 50, (j, b) -> saved.add(j)));
        assertEquals(List.of("Linked accounts lookup: asked 1, saved 0, failed 0."), info);
    }

    @Test
    void anAskThatThrowsOrAnswersNothingSavesNothing() {
        link.set(link(owner -> {
            if (owner.equals(bedrock(1))) throw new IllegalStateException("api down");
            return null;
        }));
        assertEquals(2, lookup.start(true, DAY, List.of(bedrock(1), bedrock(2)), 50, (j, b) -> saved.add(j)));
        assertTrue(saved.isEmpty());
        assertEquals(List.of("Linked accounts lookup: asked 2, saved 0, failed 1."), info);
    }

    @Test
    void duplicateOwnersAreAskedOnce() {
        AtomicInteger asks = new AtomicInteger();
        link.set(link(owner -> {
            asks.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }));
        assertEquals(1, lookup.start(true, DAY, List.of(bedrock(3), bedrock(3), bedrock(3)), 50, (j, b) -> saved.add(j)));
        assertEquals(1, asks.get());
    }
}

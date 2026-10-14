package com.prosperitysmp.npc.economy;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.link.PlayerLink;
import org.geysermc.floodgate.util.LinkedPlayer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** linked-accounts.lookup-floodgate-owners with a fake Floodgate: bounded, once a day, fails to nothing. */
class FloodgateOwnerLookupTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 13);
    private static final UUID JAVA_OWNER = UUID.fromString("7a1b2c3d-0000-4000-8000-0000000000a1");

    private final AtomicInteger linkCalls = new AtomicInteger(), playerLinkCalls = new AtomicInteger();
    private final List<UUID> asked = Collections.synchronizedList(new ArrayList<>());
    /** What PlayerLink answers per Bedrock UUID; missing means "not linked" (null). */
    private final Map<UUID, CompletableFuture<LinkedPlayer>> answers = new HashMap<>();
    private boolean linkEnabled = true;

    private final PlayerLink link = (PlayerLink) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {PlayerLink.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "isEnabled" -> linkEnabled;
                case "getLinkedPlayer" -> {
                    linkCalls.incrementAndGet();
                    asked.add((UUID) args[0]);
                    yield answers.getOrDefault((UUID) args[0], CompletableFuture.completedFuture(null));
                }
                default -> throw new AssertionError("Unexpected PlayerLink call: " + method.getName());
            });
    private final FloodgateApi api = (FloodgateApi) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {FloodgateApi.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "isFloodgateId" -> ((UUID) args[0]).getMostSignificantBits() == 0;
                case "getPlayerLink" -> { playerLinkCalls.incrementAndGet(); yield link; }
                default -> throw new AssertionError("Unexpected Floodgate call: " + method.getName());
            });

    private final List<String> info = Collections.synchronizedList(new ArrayList<>());
    private final List<UUID[]> saved = Collections.synchronizedList(new ArrayList<>());
    private final FloodgateOwnerLookup lookup = new FloodgateOwnerLookup(api, Duration.ofMillis(200), Runnable::run, info::add);
    private final FloodgateOwnerLookup.Saver saver = (java, bedrock) -> saved.add(new UUID[] {java, bedrock});

    private static UUID bedrock(int n) { return new UUID(0, 0x0009_0000_0000L + n); }

    @Test void subFlagOffNeverTouchesPlayerLink() {
        assertEquals(0, lookup.start(false, DAY, List.of(bedrock(1), bedrock(2)), 50, saver));
        assertEquals(0, playerLinkCalls.get());
        assertEquals(0, linkCalls.get());
        assertTrue(saved.isEmpty() && info.isEmpty());
        // Positive control: on, the same owners are asked.
        assertEquals(2, lookup.start(true, DAY, List.of(bedrock(1), bedrock(2)), 50, saver));
        assertEquals(2, linkCalls.get());
    }

    @Test void onlyFloodgateOwnersAreAskedAndAMatchingAnswerIsSaved() {
        answers.put(bedrock(1), CompletableFuture.completedFuture(LinkedPlayer.of("Ann", JAVA_OWNER, bedrock(1))));
        assertEquals(1, lookup.start(true, DAY, List.of(JAVA_OWNER, bedrock(1), JAVA_OWNER), 50, saver));
        assertEquals(List.of(bedrock(1)), asked);
        assertEquals(1, saved.size());
        assertArrayEquals(new UUID[] {JAVA_OWNER, bedrock(1)}, saved.getFirst());
        assertEquals(List.of("Linked accounts lookup: asked 1, saved 1, failed 0."), info);
    }

    @Test void theDailyLimitAndOncePerOwnerPerDayHold() {
        var owners = List.of(bedrock(1), bedrock(2), bedrock(3), bedrock(4), bedrock(5));
        assertEquals(3, lookup.start(true, DAY, owners, 3, saver));
        // The cycle runs again the same day: the limit is spent, nobody is asked twice.
        assertEquals(0, lookup.start(true, DAY, owners, 3, saver));
        assertEquals(3, linkCalls.get());
        assertEquals(3, asked.stream().distinct().count());
        // A bigger limit later the same day only reaches owners not asked yet.
        assertEquals(2, lookup.start(true, DAY, owners, 10, saver));
        assertEquals(5, asked.stream().distinct().count());
        assertEquals(5, asked.size());
        // The next economy day starts over.
        assertEquals(5, lookup.start(true, DAY.plusDays(1), owners, 10, saver));
        assertEquals(0, lookup.start(true, DAY.plusDays(1), owners, 0, saver), "a limit of 0 asks nobody");
    }

    @Test void aTimeoutAnErrorOrAMismatchSavesNothing() throws Exception {
        answers.put(bedrock(1), new CompletableFuture<>());                                            // never answers
        answers.put(bedrock(2), CompletableFuture.failedFuture(new IllegalStateException("api down")));
        answers.put(bedrock(3), CompletableFuture.completedFuture(LinkedPlayer.of("Ann", JAVA_OWNER, bedrock(9))));  // other account
        answers.put(bedrock(4), CompletableFuture.completedFuture(LinkedPlayer.of("Bo", bedrock(8), bedrock(4))));   // Floodgate "Java"
        assertEquals(5, lookup.start(true, DAY, List.of(bedrock(1), bedrock(2), bedrock(3), bedrock(4), bedrock(5)), 50, saver));
        for (int i = 0; i < 100 && info.isEmpty(); i++) Thread.sleep(20);
        assertTrue(saved.isEmpty());
        assertEquals(List.of("Linked accounts lookup: asked 5, saved 0, failed 4."), info, "bedrock(5) is simply not linked");
    }

    @Test void aSaveThatFailsCountsAsFailed() {
        answers.put(bedrock(1), CompletableFuture.completedFuture(LinkedPlayer.of("Ann", JAVA_OWNER, bedrock(1))));
        lookup.start(true, DAY, List.of(bedrock(1)), 50, (java, bedrock) -> { throw new IllegalArgumentException("reserve"); });
        assertEquals(List.of("Linked accounts lookup: asked 1, saved 0, failed 1."), info);
    }

    @Test void aSaveTheBusyWorkerRefusesCountsAsFailed() {
        // The plugin's link worker has a bounded queue; a full queue throws inside runAsync, before any future exists.
        var busy = new FloodgateOwnerLookup(api, Duration.ofMillis(200),
                work -> { throw new java.util.concurrent.RejectedExecutionException("queue full"); }, info::add);
        answers.put(bedrock(1), CompletableFuture.completedFuture(LinkedPlayer.of("Ann", JAVA_OWNER, bedrock(1))));
        answers.put(bedrock(2), CompletableFuture.completedFuture(LinkedPlayer.of("Bo", JAVA_OWNER, bedrock(9))));  // mismatch
        assertEquals(2, busy.start(true, DAY, List.of(bedrock(1), bedrock(2)), 50, saver));
        assertTrue(saved.isEmpty());
        assertEquals(List.of("Linked accounts lookup: asked 2, saved 0, failed 2."), info);
    }

    @Test void aDisabledPlayerLinkAsksNobody() {
        linkEnabled = false;
        assertEquals(0, lookup.start(true, DAY, List.of(bedrock(1)), 50, saver));
        assertEquals(0, linkCalls.get());
        // Not marked as asked: once Floodgate's linking is on, the owner is asked the same day.
        linkEnabled = true;
        assertEquals(1, lookup.start(true, DAY, List.of(bedrock(1)), 50, saver));
    }
}

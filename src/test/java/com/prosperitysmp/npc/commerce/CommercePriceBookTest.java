package com.prosperitysmp.npc.commerce;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** The full-price feed: ceiling cents, a fixed 10 second life, one publication at a time, main thread only. */
class CommercePriceBookTest {
    private static final long LIFETIME = 10_000_000_000L;
    private final AtomicLong time = new AtomicLong(1_000L);
    private final AtomicBoolean main = new AtomicBoolean(true);
    private final CommercePriceBook book = new CommercePriceBook(main::get, time::get);

    private void publish(String material, double dollars) {
        UUID token = book.begin();
        book.put(token, material, dollars);
        book.complete(token, "v1");
    }

    @ParameterizedTest
    @CsvSource({"19.999, 2000", "0.001, 1", "0.1, 10", "0.01, 1", "0.011, 2", "1.0, 100", "0.42, 42", "0.421, 43",
            "12.345, 1235", "999999.999, 100000000", "1000000.0, 100000000", "4.9E-324, 1"})
    void dollarsBecomeCeilingCents(double dollars, long cents) {
        publish("STONE", dollars);
        assertEquals(OptionalLong.of(cents), book.unitCents("STONE"));
        assertEquals(Map.of("STONE", cents), book.snapshot());
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, -0.0, -1.0, -0.001, 1_000_000.01, 1_000_001.0, Double.NaN,
            Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.MAX_VALUE})
    void pricesThatAreNotPositiveFiniteAndAtMostAMillionDollarsAreRefused(double dollars) {
        UUID token = book.begin();
        var refusal = assertThrows(IllegalArgumentException.class, () -> book.put(token, "STONE", dollars));
        assertEquals("Invalid full-price publication", refusal.getMessage());
        assertThrows(IllegalArgumentException.class, () -> book.complete(token, "v1"), "nothing was staged");
    }

    @Test void aStaleOrMissingTokenOrUnknownMaterialIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> book.put(UUID.randomUUID(), "STONE", 1.0), "no publication open");
        UUID first = book.begin();
        UUID second = book.begin();
        assertNotEquals(first, second);
        assertThrows(IllegalArgumentException.class, () -> book.put(first, "STONE", 1.0), "a replaced token");
        assertThrows(IllegalArgumentException.class, () -> book.put(null, "STONE", 1.0));
        assertThrows(IllegalArgumentException.class, () -> book.put(second, "DIAMOND", 1.0));
        assertThrows(IllegalArgumentException.class, () -> book.put(second, "stone", 1.0));
        assertThrows(IllegalArgumentException.class, () -> book.put(second, "", 1.0));
        book.put(second, "STONE", 1.0);
        assertEquals("Duplicate price row",
                assertThrows(IllegalArgumentException.class, () -> book.put(second, "STONE", 2.0)).getMessage());
        book.complete(second, "v2");
        assertEquals(100, book.unitCents("STONE").orElseThrow(), "the first row stands after a refused duplicate");
        assertThrows(IllegalArgumentException.class, () -> book.put(second, "COAL", 1.0), "a completed token is spent");
        assertThrows(IllegalArgumentException.class, () -> book.complete(second, "v3"));
    }

    @Test void putWithANullMaterialIsRefused() {
        UUID token = book.begin();
        assertThrows(NullPointerException.class, () -> book.put(token, null, 1.0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t", "line\nbreak", "tab\tinside", "bell\u0007", "nul\u0000", "del\u007f",
            "c1\u0085"})
    void completeRefusesBlankOrControlCharacterVersions(String version) {
        UUID token = book.begin();
        book.put(token, "STONE", 1.0);
        var refusal = assertThrows(IllegalArgumentException.class, () -> book.complete(token, version));
        assertEquals("Invalid price publication acknowledgement", refusal.getMessage());
        assertTrue(book.unitCents("STONE").isEmpty());
        assertNull(book.version());
    }

    @Test void versionsUpToNinetySixCharactersAreAccepted() {
        UUID token = book.begin();
        book.put(token, "STONE", 1.0);
        assertThrows(IllegalArgumentException.class, () -> book.complete(token, "v".repeat(97)));
        assertThrows(IllegalArgumentException.class, () -> book.complete(token, null));
        book.complete(token, "v".repeat(96));
        assertEquals("v".repeat(96), book.version());
        UUID next = book.begin();
        book.put(next, "COAL", 1.0);
        book.complete(next, "full-price é v2");
        assertEquals("full-price é v2", book.version(), "printable non-ASCII text is allowed");
    }

    @Test void anEmptyPublicationCannotComplete() {
        UUID token = book.begin();
        assertThrows(IllegalArgumentException.class, () -> book.complete(token, "v1"));
        assertNull(book.version());
        assertEquals(Map.of(), book.snapshot());
    }

    @Test void pricesLiveForJustUnderTenSeconds() {
        publish("STONE", 1.0);
        long published = time.get();
        time.set(published + LIFETIME - 1);
        assertEquals(OptionalLong.of(100), book.unitCents("STONE"));
        assertEquals(Map.of("STONE", 100L), book.snapshot());
        time.set(published + LIFETIME);
        assertEquals(OptionalLong.empty(), book.unitCents("STONE"));
        assertEquals(Map.of(), book.snapshot());
        assertEquals("v1", book.version(), "an expired publication keeps its version until replaced");
    }

    @Test void aClockThatRunsBackwardsMakesEveryPriceUnknown() {
        publish("STONE", 1.0);
        time.addAndGet(-1);
        assertEquals(OptionalLong.empty(), book.unitCents("STONE"));
        assertEquals(Map.of(), book.snapshot());
        time.addAndGet(1);
        assertEquals(OptionalLong.of(100), book.unitCents("STONE"));
    }

    @Test void theLifetimeIsCountedFromCompletionNotFromBegin() {
        UUID token = book.begin();
        book.put(token, "STONE", 1.0);
        time.addAndGet(LIFETIME * 5);
        book.complete(token, "v1");
        long completed = time.get();
        time.set(completed + LIFETIME - 1);
        assertEquals(100, book.unitCents("STONE").orElseThrow());
    }

    @Test void beginAndInvalidateDropTheCurrentPricesAndTheOpenPublication() {
        publish("STONE", 1.0);
        UUID open = book.begin();
        assertTrue(book.unitCents("STONE").isEmpty(), "a refresh never serves the old quote");
        assertNull(book.version());
        book.put(open, "COAL", 2.0);
        book.invalidate();
        assertThrows(IllegalArgumentException.class, () -> book.complete(open, "v2"), "invalidate spends the open token");
        assertEquals(Map.of(), book.snapshot());
        UUID next = book.begin();
        book.put(next, "STONE", 3.0);
        book.complete(next, "v3");
        assertEquals(Map.of("STONE", 300L), book.snapshot(), "the staged COAL row did not survive invalidate");
    }

    @Test void unpublishedMaterialsAreUnknownNeverZero() {
        publish("STONE", 1.0);
        assertEquals(OptionalLong.empty(), book.unitCents("COAL"));
        assertEquals(OptionalLong.empty(), book.unitCents("DIAMOND"));
        assertEquals(OptionalLong.empty(), book.unitCents("stone"));
    }

    @Test void theSnapshotIsImmutable() {
        UUID token = book.begin();
        book.put(token, "STONE", 1.0);
        book.put(token, "COAL", 0.5);
        book.complete(token, "v1");
        Map<String, Long> snapshot = book.snapshot();
        assertEquals(Map.of("STONE", 100L, "COAL", 50L), snapshot);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("WHEAT", 1L));
    }

    @Test void everyMethodRefusesOffTheMainThread() {
        UUID token = book.begin();
        main.set(false);
        List<Runnable> calls = List.of(book::begin, () -> book.put(token, "STONE", 1.0), () -> book.complete(token, "v"),
                () -> book.unitCents("STONE"), () -> book.configureMaterials(Set.of()), book::materials, book::version,
                book::snapshot, book::invalidate);
        for (Runnable call : calls) {
            var refusal = assertThrows(IllegalStateException.class, call::run);
            assertEquals("Price publication requires main thread", refusal.getMessage());
        }
        main.set(true);
        book.put(token, "STONE", 1.0);
        book.complete(token, "v1");
        assertEquals(100, book.unitCents("STONE").orElseThrow(), "the refused calls changed nothing");
    }

    @Test void theDefaultMaterialsAreTheEightCommerceMaterialsSorted() {
        assertEquals(List.of("BREAD", "COAL", "COBBLESTONE", "IRON_INGOT", "OAK_LOG", "RAW_IRON", "STONE", "WHEAT"),
                book.materials());
        assertEquals(Set.of("STONE", "COBBLESTONE", "OAK_LOG", "COAL", "RAW_IRON", "IRON_INGOT", "WHEAT", "BREAD"),
                CommercePriceBook.MATERIALS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"paper", "Paper", "1PAPER", "_PAPER", "PA PER", "PAPER-1", "", "PAPER\n"})
    void extraMaterialsMustBeUppercaseIdentifiers(String material) {
        var refusal = assertThrows(IllegalArgumentException.class, () -> book.configureMaterials(Set.of(material)));
        assertEquals("Invalid price material", refusal.getMessage());
        assertEquals(8, book.materials().size(), "a refused set leaves the materials unchanged");
    }

    @Test void extraMaterialsMayNotTakeTheSetPastSixtyFour() {
        var fiftySix = new HashSet<String>();
        for (int i = 0; i < 56; i++) fiftySix.add("EXTRA_" + i);
        book.configureMaterials(fiftySix);
        assertEquals(64, book.materials().size());
        var fiftySeven = new HashSet<>(fiftySix);
        fiftySeven.add("EXTRA_56");
        assertEquals("Too many price materials",
                assertThrows(IllegalArgumentException.class, () -> book.configureMaterials(fiftySeven)).getMessage());
        assertEquals(64, book.materials().size());
        var withDuplicatesOfDefaults = new HashSet<>(fiftySix);
        withDuplicatesOfDefaults.addAll(CommercePriceBook.MATERIALS);
        book.configureMaterials(withDuplicatesOfDefaults);
        assertEquals(64, book.materials().size(), "defaults named again do not count twice");
    }

    @Test void aSixtyFourCharacterMaterialIsTheLongestAccepted() {
        String longest = "A" + "B".repeat(63);
        book.configureMaterials(Set.of(longest));
        assertTrue(book.materials().contains(longest));
        assertThrows(IllegalArgumentException.class, () -> book.configureMaterials(Set.of(longest + "C")));
    }

    @Test void aMaterialRemovedFromTheSetCannotBePublishedButItsLivePriceStays() {
        book.configureMaterials(Set.of("GLASS"));
        publish("GLASS", 0.25);
        book.configureMaterials(Set.of());
        assertEquals(25, book.unitCents("GLASS").orElseThrow(), "a published price lives out its 10 seconds");
        UUID token = book.begin();
        assertThrows(IllegalArgumentException.class, () -> book.put(token, "GLASS", 0.25));
    }
}

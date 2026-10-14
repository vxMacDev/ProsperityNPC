package com.prosperitysmp.npc.commerce;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The price book accepts exactly the commerce materials unless item-repairs adds its recipe materials. */
class CommercePriceBookMaterialsTest {
    private final AtomicLong time = new AtomicLong(100);
    private final CommercePriceBook book = new CommercePriceBook(() -> true, time::get);

    @Test void defaultIsExactlyTheEightCommerceMaterials() {
        assertEquals(CommercePriceBook.MATERIALS.stream().sorted().toList(), book.materials());
        assertEquals(8, book.materials().size());
        var token = book.begin();
        assertThrows(IllegalArgumentException.class, () -> book.put(token, "PAPER", 1.0), "not published while item repairs are off");
        book.put(token, "STONE", 1.0);
        book.complete(token, "v1");
        assertEquals(100, book.unitCents("STONE").orElseThrow());
    }

    @Test void recipeMaterialsArePublishableOnlyWhileConfigured() {
        book.configureMaterials(Set.of("PAPER", "GLASS"));
        assertEquals(List.of("COAL", "COBBLESTONE", "GLASS", "IRON_INGOT", "OAK_LOG", "PAPER", "RAW_IRON", "STONE", "WHEAT", "BREAD")
                .stream().sorted().toList(), book.materials());
        var token = book.begin();
        book.put(token, "PAPER", 0.201);
        book.complete(token, "v2");
        assertEquals(21, book.unitCents("PAPER").orElseThrow(), "ceiling cents, never unknown as zero");
        assertTrue(book.unitCents("GLASS").isEmpty(), "unpublished stays unknown");
        time.addAndGet(10_000_000_000L);
        assertTrue(book.unitCents("PAPER").isEmpty(), "stale after 10 seconds");
        book.configureMaterials(Set.of());
        assertEquals(CommercePriceBook.MATERIALS.stream().sorted().toList(), book.materials());
        var next = book.begin();
        assertThrows(IllegalArgumentException.class, () -> book.put(next, "PAPER", 1.0));
        assertThrows(IllegalArgumentException.class, () -> book.configureMaterials(Set.of("paper")));
    }
}

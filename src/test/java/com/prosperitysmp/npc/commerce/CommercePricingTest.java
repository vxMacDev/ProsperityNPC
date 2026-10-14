package com.prosperitysmp.npc.commerce;

import com.prosperitysmp.npc.resident.ResidentProfile;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CommercePricingTest {
    @Test void feedCeilsAndExpiresAndIncompleteRefreshHoldsOldQuotes() {
        var time = new AtomicLong(100);
        var book = new CommercePriceBook(() -> true, time::get);
        var token = book.begin(); book.put(token, "STONE", .421); book.complete(token, "full-price-v1");
        assertEquals(43, book.unitCents("STONE").orElseThrow());
        assertTrue(book.unitCents("COAL").isEmpty());
        time.addAndGet(10_000_000_000L); assertTrue(book.unitCents("STONE").isEmpty());
        book.begin(); assertTrue(book.unitCents("STONE").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> book.complete(token, "old-token"));
    }

    @Test void everyAuthoredSupplyAndWorkerBatchCostsMoreThanFullMaterials() {
        for (var occupation : ResidentProfile.Occupation.values())
            for (var entry : CommerceCatalogue.entries(occupation)) {
                assertTrue(entry.cost(42) > entry.quantity() * 42L);
                assertThrows(IllegalArgumentException.class, () -> entry.cost(0));
                assertThrows(ArithmeticException.class, () -> entry.cost(Long.MAX_VALUE));
            }
    }
}

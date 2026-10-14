package com.prosperitysmp.npc.economy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FinancialFenceTest {
    @Test void propertyAndCommerceCannotInterleaveAcrossWaits() {
        var fence = new FinancialFence();
        var property = fence.acquire("property");
        assertThrows(IllegalStateException.class, () -> fence.acquire("merchant"));
        property.validate(); property.releaseVerified();
        var worker = fence.acquire("worker");
        assertThrows(IllegalStateException.class, property::validate);
        assertThrows(IllegalStateException.class, property::releaseVerified);
        worker.validate(); worker.releaseVerified();
        fence.acquire("rent").validate();
    }
}

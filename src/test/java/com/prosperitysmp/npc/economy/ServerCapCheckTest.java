package com.prosperitysmp.npc.economy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** The worker half of the SERVER_CAP_NEAR alert: a failed read is reported, never silently swallowed. */
class ServerCapCheckTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 13);
    private final List<String> alerts = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private final AtomicReference<LocalDate> warned = new AtomicReference<>();

    @Test
    void raisesAtNinetyPercentAndNotBelow() {
        assertTrue(UtilityPaymentRuntime.serverCapCheck(() -> 13500, 15000, DAY, alerts::add, warnings::add, warned));
        assertEquals(List.of("Server daily payout cap is 90 percent used."), alerts);
        assertFalse(UtilityPaymentRuntime.serverCapCheck(() -> 13499, 15000, DAY, alerts::add, warnings::add, warned));
        assertEquals(1, alerts.size());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void anUnreadableTotalIsLoggedOncePerEconomyDayAndRaisesNothing() {
        UtilityPaymentRuntime.SpentRead broken = () -> { throw new SQLException("Payment proof requires caller-owned transaction"); };
        assertFalse(UtilityPaymentRuntime.serverCapCheck(broken, 15000, DAY, alerts::add, warnings::add, warned));
        assertFalse(UtilityPaymentRuntime.serverCapCheck(broken, 15000, DAY, alerts::add, warnings::add, warned));
        assertEquals(1, warnings.size(), "once per economy day, not once per batch");
        assertTrue(warnings.getFirst().contains("Payment proof requires caller-owned transaction"), warnings.getFirst());
        assertTrue(alerts.isEmpty());
        assertFalse(UtilityPaymentRuntime.serverCapCheck(broken, 15000, DAY.plusDays(1), alerts::add, warnings::add, warned));
        assertEquals(2, warnings.size(), "a new economy day logs again");
        // Recovery: a readable total alerts normally after earlier failures.
        assertTrue(UtilityPaymentRuntime.serverCapCheck(() -> 15000, 15000, DAY.plusDays(1), alerts::add, warnings::add, warned));
        assertEquals(List.of("Server daily payout cap is 100 percent used."), alerts);
    }
}

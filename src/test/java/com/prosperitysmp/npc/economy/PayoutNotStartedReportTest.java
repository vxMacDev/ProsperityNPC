package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.alert.StaffAlerts;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A rent or appreciation payout that stops before its debit intent has already released its lease and moved no
 * money. Its report to staff and the server log must never throw, or the batch would stop and hold every later home
 * and the financial fence for recovery.
 */
class PayoutNotStartedReportTest {
    private final List<String> raised = new ArrayList<>(), logged = new ArrayList<>();

    @Test void aReportReachesStaffAndTheLog() {
        UtilityPaymentRuntime.reportNotStarted((kind, subject, text) -> raised.add(kind + "|" + subject + "|" + text), logged::add,
                StaffAlerts.Kind.PAYOUT_REFUSED, "property-1", "Payout not started.", "Payout not started for property 1.");
        assertEquals(List.of("PAYOUT_REFUSED|property-1|Payout not started."), raised);
        assertEquals(List.of("Payout not started for property 1."), logged);
        // A null alert or warning is not sent.
        UtilityPaymentRuntime.reportNotStarted((kind, subject, text) -> raised.add(text), logged::add, null, null, null, "Held.");
        assertEquals(1, raised.size());
        assertEquals("Held.", logged.getLast());
    }

    @Test void aLoggerThatThrowsIsContained() {
        assertDoesNotThrow(() -> UtilityPaymentRuntime.reportNotStarted((kind, subject, text) -> raised.add(text),
                text -> { throw new IllegalStateException("log handler failed"); },
                StaffAlerts.Kind.PAYOUT_REFUSED, "property-2", "Payout not started.", "Payout not started for property 2."));
        assertEquals(1, raised.size(), "the alert still went out");
        assertDoesNotThrow(() -> UtilityPaymentRuntime.reportNotStarted((kind, subject, text) -> raised.add(text),
                text -> { throw new NoClassDefFoundError("log handler class"); }, null, null, null, "Appreciation not started."));
    }

    @Test void anAlertThatThrowsIsContainedAndTheWarningIsStillLogged() {
        assertDoesNotThrow(() -> UtilityPaymentRuntime.reportNotStarted(
                (kind, subject, text) -> { throw new IllegalStateException("alerts failed"); }, logged::add,
                StaffAlerts.Kind.RESERVE_BELOW_FLOOR, "property-3", "Payout deferred.", "Payout deferred for property 3."));
        assertEquals(List.of("Payout deferred for property 3."), logged);
    }

    /** The handlers before the debit intent report only through the guard above, never directly. */
    @Test void preIntentHandlersReportOnlyThroughTheGuard() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/prosperitysmp/npc/economy/UtilityPaymentRuntime.java"))
                .replace("\r\n", "\n");
        for (String[] range : new String[][]{
                {"private CompletableFuture<Boolean> payRent(", "var handle = new TransferJournal.Handle("},
                {"private CompletableFuture<Integer> payAppreciation(", "BooleanSupplier stillOn ="}}) {
            int start = source.indexOf(range[0]);
            int end = source.indexOf(range[1], start);
            assertTrue(start > 0 && end > start, range[0]);
            String handlers = source.substring(start, end);
            assertTrue(handlers.contains("payoutNotStarted("), range[0]);
            assertFalse(handlers.contains("getLogger()"), range[0] + " logs directly");
            assertFalse(handlers.contains("alerts()"), range[0] + " alerts directly");
        }
    }
}

package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.alert.StaffAlerts;
import com.prosperitysmp.npc.economy.TransferProof.Leg;
import com.prosperitysmp.npc.economy.TransferProof.Observation;
import com.prosperitysmp.npc.economy.TransferProof.Refusal;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RefusalMonitorTest {
    private final List<String> raised = new ArrayList<>(), logged = new ArrayList<>();
    private boolean main = true;
    private final RefusalMonitor monitor = new RefusalMonitor((kind, subject, text) -> raised.add(kind + "|" + subject + "|" + text),
            logged::add, () -> main);

    private static Observation refused(Refusal reason, Throwable cause) {
        return Observation.refused(reason, cause, null, null);
    }

    @Test void aNormalRefusalIsLoggedWithoutItsMessageAndRaisesNothing() {
        monitor.refused("utility", "event 7", Leg.DEBIT, refused(Refusal.LEASE_REFUSED, new IllegalStateException("player Bob secret")));
        assertEquals(1, logged.size());
        assertTrue(logged.getFirst().contains("event 7") && logged.getFirst().contains("DEBIT")
                && logged.getFirst().contains("LEASE_REFUSED") && logged.getFirst().contains("java.lang.IllegalStateException"));
        assertFalse(logged.getFirst().contains("secret"), "never the message");
        assertTrue(raised.isEmpty());
        assertEquals(1, monitor.streak("utility"));
    }

    @Test void aFaultReasonAlertsStaff() {
        for (Refusal reason : Refusal.values()) {
            raised.clear();
            monitor.refused("commerce", "order " + reason, Leg.CREDIT, refused(reason, null));
            boolean alerted = raised.stream().anyMatch(r -> r.startsWith(StaffAlerts.Kind.PAYMENT_REFUSED_UNEXPECTEDLY + "|"));
            assertEquals(reason.unexpected(), alerted, reason.name());
            monitor.paid("commerce");
        }
    }

    @Test void fiveRefusalsInARowAlertButNotHavingTheMoneyDoesNotCount() {
        for (int i = 0; i < 20; i++) monitor.refused("rent", "event " + i, Leg.DEBIT, refused(Refusal.INSUFFICIENT_FUNDS, null));
        assertEquals(0, monitor.streak("rent"));
        assertTrue(raised.isEmpty());
        for (int i = 0; i < RefusalMonitor.REPEATED - 1; i++)
            monitor.refused("rent", "event " + i, Leg.DEBIT, refused(Refusal.COUNTERPARTY_UNVERIFIABLE, null));
        assertTrue(raised.isEmpty(), "four is not yet a streak");
        monitor.refused("rent", "event 99", Leg.DEBIT, refused(Refusal.BALANCE_UNVERIFIABLE, null));
        assertEquals(1, raised.size());
        assertTrue(raised.getFirst().startsWith(StaffAlerts.Kind.PAYMENT_REFUSALS_REPEATED + "|rent|"));
        // Another runtime keeps its own streak.
        assertEquals(0, monitor.streak("utility"));
    }

    @Test void aPaymentInBetweenResetsTheStreak() {
        for (int i = 0; i < RefusalMonitor.REPEATED - 1; i++)
            monitor.refused("utility", "event " + i, Leg.DEBIT, refused(Refusal.PRECHECK_REFUSED, null));
        monitor.paid("utility");
        assertEquals(0, monitor.streak("utility"));
        monitor.refused("utility", "event 9", Leg.DEBIT, refused(Refusal.PRECHECK_REFUSED, null));
        assertTrue(raised.isEmpty());
    }

    @Test void aRequestThatNeverStartedAlertsOnlyForAFault() {
        monitor.notStarted("utility", "request 1", new IllegalStateException("authority"));
        assertTrue(raised.isEmpty());
        monitor.notStarted("utility", "request 2", new NoSuchMethodError("missing"));
        assertEquals(1, raised.size());
        assertTrue(raised.getFirst().startsWith(StaffAlerts.Kind.PAYMENT_REFUSED_UNEXPECTEDLY + "|utility-request 2|"));
        assertTrue(raised.getFirst().contains("java.lang.NoSuchMethodError"));
        assertTrue(logged.stream().noneMatch(l -> l.contains("missing")), "never the message");
    }

    @Test void offTheMainThreadNothingIsCountedAndNothingIsThrown() {
        main = false;
        assertDoesNotThrow(() -> monitor.paid("utility"));
        assertDoesNotThrow(() -> monitor.refused("utility", "x", Leg.DEBIT, refused(Refusal.LEASE_REFUSED, null)));
        assertDoesNotThrow(() -> monitor.notStarted("utility", "x", new IllegalStateException("authority")));
        main = true;
        assertEquals(0, monitor.streak("utility"));
        assertTrue(logged.isEmpty() && raised.isEmpty());
        // Not a refusal: logged, never counted, never thrown.
        assertDoesNotThrow(() -> monitor.refused("utility", "x", Leg.DEBIT, new Observation(true, true, 1.0, 1.0, 0.0, false)));
        assertDoesNotThrow(() -> monitor.refused("utility", "x", Leg.DEBIT, null));
        assertEquals(0, monitor.streak("utility"));
    }

    @Test void aBrokenLogOrAlertNeverReachesThePaymentThatReported() {
        // The payment runtimes report a refusal before they store its evidence and release their leases. A report
        // that throws would leave the evidence unstored and the leases held.
        boolean[] mainThread = {true};
        var broken = new RefusalMonitor((kind, subject, text) -> { throw new IllegalStateException("alerts down"); },
                text -> { throw new IllegalStateException("log down"); }, () -> mainThread[0]);
        for (int i = 0; i < RefusalMonitor.REPEATED + 1; i++) {
            assertDoesNotThrow(() -> broken.refused("commerce", "order 1", Leg.DEBIT, refused(Refusal.LEASE_FAILED, null)));
            assertDoesNotThrow(() -> broken.notStarted("commerce", "order 2", new NoSuchMethodError("missing")));
        }
        assertEquals(2 * (RefusalMonitor.REPEATED + 1), broken.streak("commerce"), "the streak still counts");
        assertDoesNotThrow(() -> broken.paid("commerce"));
        assertEquals(0, broken.streak("commerce"));
        var throwingThread = new RefusalMonitor((kind, subject, text) -> { }, text -> { },
                () -> { throw new IllegalStateException("no server"); });
        assertDoesNotThrow(() -> throwingThread.refused("rent", "event 1", Leg.CREDIT, refused(Refusal.LEASE_REFUSED, null)));
        assertDoesNotThrow(() -> throwingThread.notStarted("rent", "batch 1", null));
        assertDoesNotThrow(() -> throwingThread.paid("rent"));
    }
}

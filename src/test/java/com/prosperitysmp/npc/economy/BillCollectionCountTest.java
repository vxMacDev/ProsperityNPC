package com.prosperitysmp.npc.economy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The automatic collection summary counts a bill as paid only when it was paid. */
class BillCollectionCountTest {
    private static final Path SOURCE = Path.of("src/main/java/com/prosperitysmp/npc/economy/UtilityPaymentRuntime.java");

    @Test void aDeclineOrInsufficientFundsIsNotCountedAsPaid() {
        assertTrue(UtilityPaymentRuntime.reportsPaid(true, UtilityPaymentRuntime.Result.BILL_PAID));
        assertTrue(UtilityPaymentRuntime.reportsPaid(true, UtilityPaymentRuntime.Result.ALREADY_PAID));
        var unpaid = EnumSet.allOf(UtilityPaymentRuntime.Result.class);
        unpaid.removeAll(EnumSet.of(UtilityPaymentRuntime.Result.BILL_PAID, UtilityPaymentRuntime.Result.ALREADY_PAID));
        assertTrue(unpaid.containsAll(EnumSet.of(UtilityPaymentRuntime.Result.DECLINED,
                UtilityPaymentRuntime.Result.INSUFFICIENT_FUNDS, UtilityPaymentRuntime.Result.BILL_UNAVAILABLE,
                UtilityPaymentRuntime.Result.BILL_HELD, UtilityPaymentRuntime.Result.OVER_CAP)));
        for (var state : unpaid) assertFalse(UtilityPaymentRuntime.reportsPaid(true, state), state.name());
    }

    @Test void otherPaymentsKeepReportingAFinishedRequest() {
        for (var state : UtilityPaymentRuntime.Result.values())
            assertTrue(UtilityPaymentRuntime.reportsPaid(false, state), state.name());
    }

    @Test void theFinishedCallbackReportsThroughTheBillRule() throws Exception {
        String code = Files.readString(SOURCE).replace("\r\n", "\n");
        assertTrue(code.contains("finished.accept(reportsPaid(invoiceId != null, state));"));
        assertFalse(code.contains("finished.accept(true)"));
    }
}

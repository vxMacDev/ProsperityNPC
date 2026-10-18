package com.prosperitysmp.npc.economy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

/** The one decision of which lifetime funding column a paid event adds to. */
class FundingColumnsTest {

    @ParameterizedTest
    @CsvSource({
            "RENT,successful_cash_payouts_cents",
            "APPRECIATION,successful_cash_payouts_cents",
            "UTILITY,successful_cash_sinks_cents",
            "UPGRADE,successful_cash_sinks_cents",
            "REPAIR,successful_cash_sinks_cents",
            "INSURANCE,successful_cash_sinks_cents",
            "EVICTION,successful_cash_sinks_cents",
            "BILLS,successful_cash_unfunded_cents"})
    void eachSourceAddsToExactlyOneNamedColumn(EventSource source, String column) {
        assertEquals(column, FundingColumns.fundingColumn(source));
    }

    @Test
    void theFundingColumnNameConstantsAreFixed() {
        assertEquals("successful_cash_payouts_cents", FundingColumns.PAYOUTS);
        assertEquals("successful_cash_sinks_cents", FundingColumns.SINKS);
        assertEquals("successful_cash_unfunded_cents", FundingColumns.UNFUNDED);
    }

    @ParameterizedTest
    @EnumSource(EventSource.class)
    void onlySinksThatFundRentReachTheColumnRentHeadroomReads(EventSource source) {
        boolean headroom = FundingColumns.SINKS.equals(FundingColumns.fundingColumn(source));
        assertEquals(!source.isPayout() && source.fundsRent(), headroom);
    }

    @Test
    void aMissingSourceIsRefused() {
        assertThrows(NullPointerException.class, () -> FundingColumns.fundingColumn(null));
    }
}

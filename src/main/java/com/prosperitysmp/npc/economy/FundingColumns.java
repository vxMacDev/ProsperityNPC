package com.prosperitysmp.npc.economy;

/**
 * The one place that decides which funding column a paid event adds to. Both funding writers (the transfer journal
 * and {@link EconomyEventRepository#finalizePaid}) call it, so they can never disagree.
 *
 * <ul>
 *   <li>Payouts add to {@code successful_cash_payouts_cents}.</li>
 *   <li>Sinks that fund rent add to {@code successful_cash_sinks_cents}, which is what rent headroom reads.</li>
 *   <li>Configured bills add to {@code successful_cash_unfunded_cents}, an audit total that no headroom reads.</li>
 * </ul>
 */
public final class FundingColumns {
    public static final String PAYOUTS = "successful_cash_payouts_cents";
    public static final String SINKS = "successful_cash_sinks_cents";
    public static final String UNFUNDED = "successful_cash_unfunded_cents";

    private FundingColumns() { }

    public static String fundingColumn(EventSource source) {
        if (source.isPayout()) return PAYOUTS;
        return source.fundsRent() ? SINKS : UNFUNDED;
    }
}

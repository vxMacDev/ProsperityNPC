package com.prosperitysmp.npc.formulas;

/**
 * The property-lifetime funding invariant. A property may pay out at most
 * its one-time seed plus a fixed fraction of the cash it has itself sunk, minus
 * what it has already paid out. This makes every property structurally a mild
 * net sink beyond its seed, and it never resets on sale.
 */
public final class Funding {

    private Funding() {
    }

    /** funded = seed + floor(successful_sinks * ratio). All cents; ratio typically 0.90. */
    public static long fundedCashCents(long seedCents, long successfulSinksCents, double ratio) {
        long fromSinks = (long) Math.floor(Math.max(0L, successfulSinksCents) * ratio);
        return Math.max(0L, seedCents) + fromSinks;
    }

    /** available = max(0, funded - successful_payouts). */
    public static long availableCashCents(long fundedCents, long successfulPayoutsCents) {
        return Math.max(0L, fundedCents - Math.max(0L, successfulPayoutsCents));
    }

    /** The most a new payout may be, given the current funding state. */
    public static long maxPayoutCents(long seedCents, long sinksCents, long payoutsCents, double ratio) {
        return availableCashCents(fundedCashCents(seedCents, sinksCents, ratio), payoutsCents);
    }
}

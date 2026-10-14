package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.formulas.Funding;

/**
 * Pure enforcement of the safe cash envelope for a single TO_PUBLIC (faucet)
 * payout. Every constraint is applied as a hard ceiling; the result is the most
 * that may be paid and is never negative. FROM_PUBLIC sinks do not pass through
 * this gate — they are unbounded reductions in liquidity.
 */
public final class PayoutGate {

    /** Remaining headroom (cents) under each daily cap for this settlement. */
    public record CapRemaining(long server, long benefitUnit, long property, long tenant, long appreciation) {
    }

    /** The property's lifetime funding state (cents). */
    public record FundingState(long seedCents, long sinksCents, long payoutsCents) {
    }

    private PayoutGate() {
    }

    /**
     * @param requestedCents the entitlement being paid
     * @param appreciation   true if this is an appreciation payout (uses the appreciation cap
     *                        instead of the per-tenant cap)
     * @param caps           remaining headroom under each cap
     * @param funding        the property's lifetime funding
     * @param centralBankCents current CentralBank balance
     * @param centralBankFloorCents balance that must remain after paying
     * @param fundingRatio   fraction of sinks that funds payouts (e.g. 0.90)
     * @return the amount that may actually be paid, in [0, requestedCents]
     */
    public static long allowedPayoutCents(long requestedCents, boolean appreciation, CapRemaining caps,
                                          FundingState funding, long centralBankCents, long centralBankFloorCents,
                                          double fundingRatio) {
        if (requestedCents <= 0) {
            return 0;
        }
        long a = Math.min(requestedCents, caps.server());
        a = Math.min(a, caps.benefitUnit());
        a = Math.min(a, caps.property());
        a = Math.min(a, appreciation ? caps.appreciation() : caps.tenant());

        long fundingAvailable = Funding.maxPayoutCents(
                funding.seedCents(), funding.sinksCents(), funding.payoutsCents(), fundingRatio);
        a = Math.min(a, fundingAvailable);

        long reserveHeadroom = Math.max(0L, centralBankCents - centralBankFloorCents);
        a = Math.min(a, reserveHeadroom);

        return Math.max(0L, a);
    }
}

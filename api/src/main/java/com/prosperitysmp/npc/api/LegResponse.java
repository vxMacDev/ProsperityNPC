package com.prosperitysmp.npc.api;

/** What the provider answered for one leg. Built by the adapter; a null response is allowed and means "no response object". */
public final class LegResponse {
    private final boolean success;
    private final double amountDollars;

    private LegResponse(boolean success, double amountDollars) {
        this.success = success;
        this.amountDollars = amountDollars;
    }

    public static LegResponse of(boolean success, double amountDollars) { return new LegResponse(success, amountDollars); }

    public boolean success() { return success; }

    /** The provider's own reported amount, as its raw double. */
    public double amountDollars() { return amountDollars; }
}

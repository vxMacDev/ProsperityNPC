package com.prosperitysmp.npc.economy.accounting;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * The default sink: the Skript bridge reads each snapshot, applies the totals to its money-flow variables and then
 * acknowledges the generation. Passive: the plugin never acknowledges for it.
 */
public final class SkriptSink implements AccountingSink {
    public static final String ID = "skript";
    public static final String READY = "Accounting projection: acknowledged";
    public static final String WAITING = "Accounting projection: waiting for verified snapshot and Skript acknowledgement";

    private final BooleanSupplier bridgeConnected;

    public SkriptSink(BooleanSupplier bridgeConnected) {
        this.bridgeConnected = Objects.requireNonNull(bridgeConnected);
    }

    @Override public String id() { return ID; }
    @Override public boolean selfAcknowledges() { return false; }
    @Override public boolean consumerPresent() { return bridgeConnected.getAsBoolean(); }
    @Override public String healthLine(boolean ready) { return ready ? READY : WAITING; }
}

package com.prosperitysmp.npc.economy.accounting;

/**
 * For a server without Skript: the plugin acknowledges each fresh, verified snapshot itself, so payments can run.
 * No Skript totals are published. A snapshot is still required: a failed or expired read still holds payments.
 */
public final class InternalSink implements AccountingSink {
    public static final String ID = "internal";
    public static final String READY =
            "Accounting projection: acknowledged by the plugin (accounting.sink=internal; no Skript totals are published)";
    public static final String WAITING = "Accounting projection: waiting for verified snapshot (accounting.sink=internal)";

    @Override public String id() { return ID; }
    @Override public boolean selfAcknowledges() { return true; }
    @Override public boolean consumerPresent() { return true; }
    @Override public String healthLine(boolean ready) { return ready ? READY : WAITING; }
}

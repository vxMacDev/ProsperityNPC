package com.prosperitysmp.npc.economy.accounting;

/**
 * Who confirms that NPC accounting totals have been applied before another payment may start. Core only, never part
 * of the adapter API: v1 has two built-in sinks and no third-party ones.
 */
public interface AccountingSink {
    /** The accounting.sink value that selects this sink. */
    String id();

    /** True when the plugin acknowledges each fresh snapshot itself instead of waiting for Skript. */
    boolean selfAcknowledges();

    /** True when whatever consumes the totals is there right now. */
    boolean consumerPresent();

    /** The "Accounting projection:" line of /npcadmin health. */
    String healthLine(boolean ready);

    /** The sink for an accounting.sink value, or null when the value is unknown. */
    static AccountingSink forId(String id, java.util.function.BooleanSupplier bridgeConnected) {
        return switch (id) {
            case SkriptSink.ID -> new SkriptSink(bridgeConnected);
            case InternalSink.ID -> new InternalSink();
            default -> null;
        };
    }
}

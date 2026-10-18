package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.ProsperityNpcPlugin;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Compatibility surface for the retired boolean-acknowledgement Skript bridge.
 * Heartbeats are observable, but legacy claims/acks cannot move ledger state.
 * The replacement must persist account-bound intent and proof for both Vault legs;
 * a heartbeat or config switch is not evidence that this protocol is installed.
 */
public final class BridgeApi {

    private static final long HEARTBEAT_STALE_MS = 30_000L;

    private final ProsperityNpcPlugin plugin;
    private final AtomicLong lastHeartbeat = new AtomicLong(0L);

    public BridgeApi(ProsperityNpcPlugin plugin, EconomyEventRepository repo) {
        this.plugin = plugin;
    }

    /** The bridge calls this each cycle so health/fail-closed knows it is alive. */
    public void heartbeat() {
        lastHeartbeat.set(System.currentTimeMillis());
    }

    public boolean isBridgeConnected() {
        long h = lastHeartbeat.get();
        return h > 0 && (System.currentTimeMillis() - h) < HEARTBEAT_STALE_MS;
    }

    /** Explicit release hold, not configurable: the durable transfer adapter is not yet connected. */
    public boolean supportsConfirmedTransfers() { return false; }

    /** Configured reserve account; blank until the server owner sets one, which keeps payments held. */
    public String centralBankUuid() {
        return ReserveAccount.liveText(plugin.getConfig());
    }

    public long centralBankFloorCents() {
        return plugin.economyLimits().centralBankFloorCents();
    }

    /** Old scripts get no work. Replacement claims need immutable attempts and ownership gates. */
    @Deprecated
    public List<String> claimBatch(int max) {
        return List.of();
    }

    /**
     * Refuses the legacy boolean acknowledgement: neither full money movement nor
     * safe failure can be established from these arguments. No automatic refund.
     */
    @Deprecated
    public boolean finalizeEvent(long eventId, boolean success, long movedCents) {
        return false;
    }
}

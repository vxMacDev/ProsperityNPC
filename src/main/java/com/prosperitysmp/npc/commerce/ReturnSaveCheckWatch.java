package com.prosperitysmp.npc.commerce;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Main thread only, one per delivery runtime (so per run). Counts how often the save made just before an item repair
 * return could not be made or read back. Such a return gives nothing and holds nothing, so without this nothing says why
 * it never arrives (for example on a host whose file system the save check always refuses). The first refusal of a
 * return is logged once; the {@value #ALERT_AFTER}th raises a staff alert. Bounded: past {@value #MAX_TRACKED} returns
 * the counts start again.
 */
public final class ReturnSaveCheckWatch {
    public enum Action { LOG, ALERT, NONE }
    public static final int ALERT_AFTER = 3;
    static final int MAX_TRACKED = 1024;
    private final Map<UUID, Integer> refusals = new HashMap<>();

    /** One more refused save check for this return: what to say about it. */
    public Action refused(UUID requestNonce) {
        Objects.requireNonNull(requestNonce);
        if (!refusals.containsKey(requestNonce) && refusals.size() >= MAX_TRACKED) refusals.clear();
        int count = Math.min(refusals.getOrDefault(requestNonce, 0) + 1, ALERT_AFTER + 1);
        refusals.put(requestNonce, count);
        return count == 1 ? Action.LOG : count == ALERT_AFTER ? Action.ALERT : Action.NONE;
    }

    int tracked() { return refusals.size(); }

    public static String logLine(UUID requestNonce, UUID playerId) {
        return "Item return " + requestNonce + " for player " + playerId + " was not delivered. The save made just before it"
                + " could not be read back, so nothing was given or held. If every return does this, check that this server's"
                + " file system supports the save check.";
    }

    public static String staffAlert(UUID requestNonce) {
        return "Staff alert: Item return " + requestNonce + " failed its save check " + ALERT_AFTER
                + " times. Nothing was given or held. See the server log.";
    }
}

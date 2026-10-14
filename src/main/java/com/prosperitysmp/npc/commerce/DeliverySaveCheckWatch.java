package com.prosperitysmp.npc.commerce;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Main thread only. Counts refusals of the save made just before a marked delivery (it could not be made or read
 * back). Such a delivery gives and holds nothing, so without a count nothing says why it never arrives. The first
 * refusal of a delivery is logged and the {@value #ALERT_AFTER}th raises a staff alert. The runtime keeps one instance
 * for item repair returns and one for paid orders, so order refusals never clear the return counts. Past
 * {@value #MAX_TRACKED} tracked deliveries the counts start again.
 */
public final class DeliverySaveCheckWatch {
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

    /** A paid order delivery whose save just before it could not be made or read back. Nothing started. */
    public static String orderLogLine(UUID requestNonce, UUID playerId) {
        return "Order delivery " + requestNonce + " for player " + playerId + " was not started. The save made just before it"
                + " could not be read back, so nothing was given or held. If every order does this, check that this server's"
                + " file system supports the save check.";
    }

    public static String orderStaffAlert(UUID requestNonce) {
        return "Staff alert: Order delivery " + requestNonce + " failed its save check " + ALERT_AFTER
                + " times. Nothing was given or held. See the server log.";
    }

    /**
     * A marked order delivery held for staff after it started. baselineUnits is the whole-file count of the item in the
     * save just before the delivery, which is otherwise kept only in memory.
     */
    public static String orderHeldLine(UUID requestNonce, UUID playerId, int baselineUnits) {
        return "Order delivery " + requestNonce + " for player " + playerId + " is held for staff. The save just before it held "
                + baselineUnits + " of the item anywhere in the file. Inspect it with CommerceRecoveryTool while the server is stopped.";
    }

    /** A paid order delivery found held at startup. outcome is why it is held, e.g. INTERRUPTED_RUN after a crash. */
    public static String orderHeldAtStartLine(UUID requestNonce, UUID playerId, String outcome) {
        return "Order delivery " + requestNonce + " for player " + playerId + " is held for staff (" + outcome + "). It blocks"
                + " that player's other item deliveries. Inspect it with CommerceRecoveryTool while the server is stopped.";
    }

    /** The startup report lists a limited number of held orders; this line counts the rest. */
    public static String orderHeldMoreLine(int more, int total) {
        return more + " more held order deliveries are not listed above (" + total + " held in all). Inspect them with"
                + " CommerceRecoveryTool while the server is stopped.";
    }

    public static String orderHeldAlert(UUID requestNonce) {
        return "Staff alert: Order delivery " + requestNonce + " is held for staff review. See the server log.";
    }
}

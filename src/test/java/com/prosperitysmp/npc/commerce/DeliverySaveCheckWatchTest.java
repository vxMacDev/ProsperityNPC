package com.prosperitysmp.npc.commerce;

import com.prosperitysmp.npc.commerce.DeliverySaveCheckWatch.Action;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A delivery whose save check keeps refusing is logged once per run, then raised to staff after repeated refusals.
 * Returns and paid orders each have their own lines and their own counts.
 */
class DeliverySaveCheckWatchTest {
    private static final UUID RETURN = new UUID(7, 1), OTHER = new UUID(7, 2), PLAYER = new UUID(7, 3);

    @Test void logsOnceThenAlertsOnTheThirdRefusalAndThenStaysQuiet() {
        var watch = new DeliverySaveCheckWatch();
        var actions = new ArrayList<Action>();
        for (int i = 0; i < 6; i++) actions.add(watch.refused(RETURN));
        assertEquals(List.of(Action.LOG, Action.NONE, Action.ALERT, Action.NONE, Action.NONE, Action.NONE), actions);
        // Each return is counted on its own.
        assertEquals(Action.LOG, watch.refused(OTHER));
        assertEquals(Action.NONE, watch.refused(RETURN));
        assertEquals(2, watch.tracked());
    }

    @Test void theCountIsBounded() {
        var watch = new DeliverySaveCheckWatch();
        for (int i = 0; i < DeliverySaveCheckWatch.MAX_TRACKED; i++) assertEquals(Action.LOG, watch.refused(new UUID(9, i)));
        assertEquals(DeliverySaveCheckWatch.MAX_TRACKED, watch.tracked());
        assertEquals(1024, DeliverySaveCheckWatch.MAX_TRACKED);
        assertEquals(Action.LOG, watch.refused(RETURN), "a new return still gets its line");
        assertEquals(1, watch.tracked(), "the counts start again instead of growing");
        assertEquals(Action.NONE, watch.refused(RETURN), "a known return is not logged twice");
    }

    /** The runtime keeps one watch for returns and one for orders: a run of order refusals never resets the return counts. */
    @Test void twoInstancesCountIndependently() {
        var returns = new DeliverySaveCheckWatch();
        var orders = new DeliverySaveCheckWatch();
        assertEquals(Action.LOG, returns.refused(RETURN));
        assertEquals(Action.NONE, returns.refused(RETURN));
        for (int i = 0; i <= DeliverySaveCheckWatch.MAX_TRACKED; i++) orders.refused(new UUID(10, i));
        assertEquals(1, orders.tracked(), "the order watch did reset");
        assertEquals(1, returns.tracked(), "the return watch kept its count");
        assertEquals(Action.ALERT, returns.refused(RETURN), "the third return refusal still alerts");
        assertEquals(Action.LOG, orders.refused(RETURN), "the same nonce is new to the other watch");
    }

    @Test void theReturnLinesAreUnchanged() {
        assertEquals("Item return " + RETURN + " for player " + PLAYER + " was not delivered. The save made just before it could"
                + " not be read back, so nothing was given or held. If every return does this, check that this server's file"
                + " system supports the save check.", DeliverySaveCheckWatch.logLine(RETURN, PLAYER));
        assertEquals("Staff alert: Item return " + RETURN + " failed its save check 3 times. Nothing was given or held. See the"
                + " server log.", DeliverySaveCheckWatch.staffAlert(RETURN));
    }

    @Test void theLinesSayWhatHappenedInPlainWords() {
        String log = DeliverySaveCheckWatch.logLine(RETURN, PLAYER);
        String alert = DeliverySaveCheckWatch.staffAlert(RETURN);
        assertTrue(log.contains(RETURN.toString()) && log.contains(PLAYER.toString()));
        assertTrue(log.contains("nothing was given or held"));
        assertTrue(alert.startsWith("Staff alert: Item return " + RETURN));
        assertTrue(alert.contains(DeliverySaveCheckWatch.ALERT_AFTER + " times"));
        String orderLog = DeliverySaveCheckWatch.orderLogLine(RETURN, PLAYER);
        String orderAlert = DeliverySaveCheckWatch.orderStaffAlert(RETURN);
        String held = DeliverySaveCheckWatch.orderHeldLine(RETURN, PLAYER, 40);
        String heldAlert = DeliverySaveCheckWatch.orderHeldAlert(RETURN);
        for (String order : List.of(orderLog, orderAlert, held, heldAlert)) {
            assertTrue(order.contains("Order delivery " + RETURN), order);
            assertFalse(order.contains("Item return"), "order lines never reuse the return wording: " + order);
        }
        assertTrue(orderLog.contains(PLAYER.toString()));
        for (String refused : List.of(orderLog, orderAlert))
            assertTrue(refused.toLowerCase(java.util.Locale.ROOT).contains("nothing was given or held"), refused);
        assertTrue(orderAlert.startsWith("Staff alert: Order delivery " + RETURN));
        assertTrue(held.contains(PLAYER.toString()) && held.contains(" held 40 of the item anywhere in the file."));
        assertTrue(held.contains("CommerceRecoveryTool"));
        assertTrue(heldAlert.startsWith("Staff alert: Order delivery " + RETURN));
        for (String line : List.of(log, alert, orderLog, orderAlert, held, heldAlert)) {
            // The nonce's UUID hyphens are expected; only em and en dashes are banned.
            assertFalse(line.contains("—") || line.contains("–"), "no dashes: " + line);
            assertFalse(line.contains("\n"), line);
        }
        for (String alertLine : List.of(alert, orderAlert, heldAlert)) assertTrue(alertLine.length() <= 200, "fits one staff alert line");
    }
}

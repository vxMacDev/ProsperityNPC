package com.prosperitysmp.npc.commerce;

import com.prosperitysmp.npc.commerce.ReturnSaveCheckWatch.Action;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** A return whose save check keeps refusing is logged once per run, then raised to staff after repeated refusals. */
class ReturnSaveCheckWatchTest {
    private static final UUID RETURN = new UUID(7, 1), OTHER = new UUID(7, 2), PLAYER = new UUID(7, 3);

    @Test void logsOnceThenAlertsOnTheThirdRefusalAndThenStaysQuiet() {
        var watch = new ReturnSaveCheckWatch();
        var actions = new ArrayList<Action>();
        for (int i = 0; i < 6; i++) actions.add(watch.refused(RETURN));
        assertEquals(List.of(Action.LOG, Action.NONE, Action.ALERT, Action.NONE, Action.NONE, Action.NONE), actions);
        // Each return is counted on its own.
        assertEquals(Action.LOG, watch.refused(OTHER));
        assertEquals(Action.NONE, watch.refused(RETURN));
        assertEquals(2, watch.tracked());
    }

    @Test void theCountIsBounded() {
        var watch = new ReturnSaveCheckWatch();
        for (int i = 0; i < ReturnSaveCheckWatch.MAX_TRACKED; i++) assertEquals(Action.LOG, watch.refused(new UUID(9, i)));
        assertEquals(ReturnSaveCheckWatch.MAX_TRACKED, watch.tracked());
        assertEquals(Action.LOG, watch.refused(RETURN), "a new return still gets its line");
        assertEquals(1, watch.tracked(), "the counts start again instead of growing");
        assertEquals(Action.NONE, watch.refused(RETURN), "positive control: a known return is not logged twice");
    }

    @Test void theLinesSayWhatHappenedInPlainWords() {
        String log = ReturnSaveCheckWatch.logLine(RETURN, PLAYER);
        String alert = ReturnSaveCheckWatch.staffAlert(RETURN);
        assertTrue(log.contains(RETURN.toString()) && log.contains(PLAYER.toString()));
        assertTrue(log.contains("nothing was given or held"));
        assertTrue(alert.startsWith("Staff alert: Item return " + RETURN));
        assertTrue(alert.contains(ReturnSaveCheckWatch.ALERT_AFTER + " times"));
        assertTrue(alert.length() <= 200, "fits one staff alert line");
        for (String line : List.of(log, alert)) {
            assertFalse(line.contains("—") || line.contains("–"), "no dashes: " + line);
            assertFalse(line.contains("\n"), line);
        }
    }
}

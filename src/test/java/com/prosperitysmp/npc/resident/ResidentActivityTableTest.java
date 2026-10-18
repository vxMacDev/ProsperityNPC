package com.prosperitysmp.npc.resident;

import com.prosperitysmp.npc.resident.ResidentActivity.State;
import com.prosperitysmp.npc.resident.ResidentRoutine.Activity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** What a rendered resident is observed doing, for every combination of the five facts, and the line that says so. */
class ResidentActivityTableTest {
    /** connected, walking, interrupted walk, paused, at a stop: the observed state. */
    @ParameterizedTest(name = "{0} {1} {2} {3} {4} -> {5}")
    @CsvSource({
            "false, false, false, false, false, PREVIEW",
            "false, false, false, false, true, PREVIEW",
            "false, false, false, true, false, PREVIEW",
            "false, false, false, true, true, PREVIEW",
            "false, false, true, false, false, PREVIEW",
            "false, false, true, false, true, PREVIEW",
            "false, false, true, true, false, PREVIEW",
            "false, false, true, true, true, PREVIEW",
            "false, true, false, false, false, PREVIEW",
            "false, true, false, false, true, PREVIEW",
            "false, true, false, true, false, PREVIEW",
            "false, true, false, true, true, PREVIEW",
            "false, true, true, false, false, PREVIEW",
            "false, true, true, false, true, PREVIEW",
            "false, true, true, true, false, PREVIEW",
            "false, true, true, true, true, PREVIEW",
            "true, false, false, false, false, WAITING_ROUTE",
            "true, false, false, false, true, AT_STOP",
            "true, false, false, true, false, WAITING_ROUTE",
            "true, false, false, true, true, AT_STOP",
            "true, false, true, false, false, WAITING_ROUTE",
            "true, false, true, false, true, AT_STOP",
            "true, false, true, true, false, PAUSED",
            "true, false, true, true, true, PAUSED",
            "true, true, false, false, false, WALKING",
            "true, true, false, false, true, WALKING",
            "true, true, false, true, false, WALKING",
            "true, true, false, true, true, WALKING",
            "true, true, true, false, false, WALKING",
            "true, true, true, false, true, WALKING",
            "true, true, true, true, false, PAUSED",
            "true, true, true, true, true, PAUSED"})
    void theObservedStateFollowsTheRuleForEveryCombination(boolean connected, boolean walking, boolean interrupted,
                                                           boolean paused, boolean atStop, State state) {
        assertEquals(state, ResidentActivity.observed(connected, walking, interrupted, paused, atStop));
    }

    @Test void aPausedWalkNeedsBothThePauseAndTheInterruption() {
        assertEquals(State.WALKING, ResidentActivity.observed(true, true, false, true, false));
        assertEquals(State.WALKING, ResidentActivity.observed(true, true, true, false, false));
        assertEquals(State.PAUSED, ResidentActivity.observed(true, true, true, true, false));
        assertEquals(State.PAUSED, ResidentActivity.observed(true, false, true, true, true));
    }

    @Test void aDisconnectedAvatarIsAPreviewWhateverElseIsTrue() {
        assertEquals(State.PREVIEW, ResidentActivity.observed(false, true, true, true, true));
    }

    static Stream<Arguments> lines() {
        String[] stops = {"quiet stop", "workday stop", "market stop", "leisure stop"};
        Stream.Builder<Arguments> out = Stream.builder();
        for (int i = 0; i < 4; i++) {
            Activity activity = Activity.values()[i];
            out.add(Arguments.of(State.PREVIEW, activity, "I'm staying here for this visit. My usual route isn't connected."));
            out.add(Arguments.of(State.WALKING, activity, "I'm on my way to my " + stops[i] + ". I can stop for a moment."));
            out.add(Arguments.of(State.AT_STOP, activity, "I've reached my " + stops[i] + ". I have a little time to talk."));
            out.add(Arguments.of(State.PAUSED, activity, "I've paused my walk to talk with you."));
            out.add(Arguments.of(State.WAITING_ROUTE, activity, "I'm staying here until my route is clear."));
        }
        return out.build();
    }

    @ParameterizedTest @MethodSource("lines")
    void eachStateHasItsLineAndOnlyWalkingAndArrivalNameTheStop(State state, Activity activity, String line) {
        assertEquals(line, new ResidentActivity(state, activity).line());
    }

    @Test void theActivityOrderIsHomeWorkErrandLeisure() {
        assertEquals("[HOME, WORK, ERRAND, LEISURE]", java.util.Arrays.toString(Activity.values()));
    }

    @Test void aMissingStateOrActivityIsRefused() {
        assertThrows(NullPointerException.class, () -> new ResidentActivity(null, Activity.HOME));
        assertThrows(NullPointerException.class, () -> new ResidentActivity(State.PAUSED, null));
    }
}

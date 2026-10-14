package com.prosperitysmp.npc.resident;

import java.util.Objects;

/** Observed presentation state, not evidence of employment, home ownership or economic activity. */
public record ResidentActivity(State state, ResidentRoutine.Activity intended) {
    public enum State { PREVIEW, WALKING, AT_STOP, PAUSED, WAITING_ROUTE }
    public ResidentActivity {
        Objects.requireNonNull(state);
        Objects.requireNonNull(intended);
    }

    public static State observed(boolean connected, boolean walking, boolean interruptedWalk, boolean paused, boolean atStop) {
        return !connected ? State.PREVIEW : paused && interruptedWalk ? State.PAUSED : walking ? State.WALKING
                : atStop ? State.AT_STOP : State.WAITING_ROUTE;
    }

    public String line() {
        String destination = switch (intended) {
            case HOME -> "quiet stop";
            case WORK -> "workday stop";
            case ERRAND -> "market stop";
            case LEISURE -> "leisure stop";
        };
        return switch (state) {
            case PREVIEW -> "I'm staying here for this visit. My usual route isn't connected.";
            case WALKING -> "I'm on my way to my " + destination + ". I can stop for a moment.";
            case AT_STOP -> "I've reached my " + destination + ". I have a little time to talk.";
            case PAUSED -> "I've paused my walk to talk with you.";
            case WAITING_ROUTE -> "I'm staying here until my route is clear.";
        };
    }
}

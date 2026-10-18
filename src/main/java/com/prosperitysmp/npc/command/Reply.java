package com.prosperitysmp.npc.command;

import com.prosperitysmp.npc.nav.NavGraphRepository;

import java.util.List;

/**
 * What the nav worker hands back to the main thread: lines for the sender, the district whose routes
 * changed (if any), and a world read to run next (if any).
 */
record Reply(List<Line> lines, NavGraphRepository.GraphInfo invalidate, NpcNavCommand.SweepJob next) {
    Reply {
        lines = List.copyOf(lines);
    }

    Reply(List<Line> lines, NavGraphRepository.GraphInfo invalidate) {
        this(lines, invalidate, null);
    }
}

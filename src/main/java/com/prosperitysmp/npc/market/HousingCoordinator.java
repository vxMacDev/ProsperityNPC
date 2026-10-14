package com.prosperitysmp.npc.market;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Internal SQL execution, NOT live ARM authorization. Each admission commits independently. */
public final class HousingCoordinator {
    @SuppressWarnings("serial") // Process-local retry context, not a serialized recovery record.
    public static final class Interrupted extends SQLException {
        private static final long serialVersionUID = 1L;
        private final HousingDecisionPlan plan;
        private final List<HousingMoveRepository.Result> completed;
        private final HousingMoveRequest unresolved;

        private Interrupted(HousingDecisionPlan plan, List<HousingMoveRepository.Result> completed,
                            HousingMoveRequest unresolved, Exception cause) {
            super("Housing plan stopped; reconcile unresolved request " + unresolved.requestId(), cause);
            this.plan = plan; this.completed = List.copyOf(completed); this.unresolved = unresolved;
        }
        public HousingDecisionPlan plan() { return plan; }
        public List<HousingMoveRepository.Result> completed() { return completed; }
        /** May have committed without acknowledgement. Do not assume failure means no admission. */
        public HousingMoveRequest unresolved() { return unresolved; }
    }

    private final HousingMoveRepository moves;
    public HousingCoordinator(HousingMoveRepository moves) { this.moves = Objects.requireNonNull(moves); }

    /** Exact retries reuse this plan; they do not quote again, allocate new IDs or promise new occupancy. */
    public List<HousingMoveRepository.Result> execute(HousingDecisionPlan plan) throws Interrupted {
        Objects.requireNonNull(plan);
        var completed = new ArrayList<HousingMoveRepository.Result>();
        for (HousingMoveRequest request : plan.requests()) {
            try {
                if (Thread.currentThread().isInterrupted()) throw new SQLException("Housing execution interrupted before request");
                completed.add(moves.apply(request, plan.snapshot().policy()));
            } catch (SQLException | RuntimeException failure) {
                throw new Interrupted(plan, completed, request, failure);
            }
        }
        return List.copyOf(completed);
    }
}

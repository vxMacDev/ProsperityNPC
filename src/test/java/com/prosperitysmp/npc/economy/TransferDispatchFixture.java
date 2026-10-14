package com.prosperitysmp.npc.economy;

import java.util.List;
import java.util.concurrent.CompletionStage;

/** Package bridge for the existing real-MariaDB fixture; not shipped in the plugin. */
public final class TransferDispatchFixture {
    private TransferDispatchFixture() { }
    public static CompletionStage<TransferJournal.Receipt> dispatch(TransferJournal journal,
            TransferJournal.Handle handle, List<String> calls) {
        var lease = new JournaledTransfer.Lease() {
            public void authorize(TransferJournal.Receipt r, TransferProof.Leg leg) { calls.add("authorize-" + leg); }
            public void releaseVerified(TransferJournal.Receipt r) { calls.add("release"); }
        };
        return new JournaledTransfer(journal, (r, leg) -> {
            calls.add("money-" + leg);
            double dollars = r.cents() / 100.0;
            return new TransferProof.Observation(true, true, dollars, 100.0,
                    leg == TransferProof.Leg.DEBIT ? 100.0 - dollars : 100.0 + dollars, false);
        }, Runnable::run, Runnable::run).submit(handle, lease);
    }
}

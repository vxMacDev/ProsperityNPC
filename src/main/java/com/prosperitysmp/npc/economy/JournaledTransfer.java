package com.prosperitysmp.npc.economy;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.prosperitysmp.npc.economy.TransferJournal.*;
import static com.prosperitysmp.npc.economy.TransferProof.*;

/**
 * Serial, non-retrying dispatch. SQL runs on worker; authority and provider calls
 * run on main. Used by the payment runtimes. Caller must acquire the cooperative
 * ownership/account lease and reserve budgets before submitting. An uncertain
 * operation latches this dispatcher shut and retains the lease for reconciliation.
 */
final class JournaledTransfer {
    interface Journal {
        Start beginDebit(Handle handle) throws Exception;
        Receipt recordDebit(Handle handle, Observation observation) throws Exception;
        Start beginCredit(Handle handle) throws Exception;
        Receipt recordCredit(Handle handle, Observation observation) throws Exception;
    }

    interface Lease {
        /** Recheck runtime switches, permission, ownership, provider and reserved budget. */
        void authorize(Receipt receipt, Leg leg) throws Exception;
        /** Main thread, called only after both durable acknowledgements or proved decline. */
        void releaseVerified(Receipt receipt) throws Exception;
    }

    @FunctionalInterface interface Money {
        Observation execute(Receipt receipt, Leg leg) throws Exception;
    }
    @FunctionalInterface private interface Checked<T> { T get() throws Exception; }

    private final Journal journal;
    private final Money money;
    private final Executor worker, main;
    private final AtomicBoolean occupied = new AtomicBoolean();

    JournaledTransfer(TransferJournal journal, Money money, Executor worker, Executor main) {
        this(new Journal() {
            public Start beginDebit(Handle h) throws Exception { return journal.beginDebit(h); }
            public Receipt recordDebit(Handle h, Observation o) throws Exception { return journal.recordDebit(h, o); }
            public Start beginCredit(Handle h) throws Exception { return journal.beginCredit(h); }
            public Receipt recordCredit(Handle h, Observation o) throws Exception { return journal.recordCredit(h, o); }
        }, money, worker, main);
        Objects.requireNonNull(journal);
    }

    JournaledTransfer(Journal journal, Money money, Executor worker, Executor main) {
        this.journal = Objects.requireNonNull(journal);
        this.money = Objects.requireNonNull(money);
        this.worker = Objects.requireNonNull(worker);
        this.main = Objects.requireNonNull(main);
    }

    /**
     * No queue: concurrent submissions are rejected before SQL. Rejection leaves
     * the caller responsible for its unused lease. Cancellation of the returned
     * view cannot cancel an in-flight provider call or release its ownership lock.
     */
    CompletionStage<Receipt> submit(Handle handle, Lease lease) {
        Objects.requireNonNull(handle);
        Objects.requireNonNull(lease);
        if (!occupied.compareAndSet(false, true))
            return CompletableFuture.<Receipt>failedFuture(new IllegalStateException("Transfer busy or recovery required"))
                    .minimalCompletionStage();
        return run(worker, () -> journal.beginDebit(handle))
                .thenCompose(start -> {
                    if (!start.newlyStarted()) return CompletableFuture.completedFuture(start.receipt());
                    require(start.receipt(), handle, State.DEBIT_INTENT);
                    return observe(start.receipt(), Leg.DEBIT, lease)
                            .thenCompose(o -> run(worker, () -> journal.recordDebit(handle, o)))
                            .thenCompose(debit -> {
                                if (debit.state() != State.CREDIT_READY) return CompletableFuture.completedFuture(debit);
                                require(debit, handle, State.CREDIT_READY);
                                return run(worker, () -> journal.beginCredit(handle)).thenCompose(credit -> {
                                    if (!credit.newlyStarted()) return CompletableFuture.completedFuture(credit.receipt());
                                    require(credit.receipt(), handle, State.CREDIT_INTENT);
                                    return observe(credit.receipt(), Leg.CREDIT, lease)
                                            .thenCompose(o -> run(worker, () -> journal.recordCredit(handle, o)));
                                });
                            });
                }).thenCompose(receipt -> run(main, () -> {
                    if (!receipt.handle().equals(handle)) throw new IllegalStateException("Transfer handle changed");
                    if (receipt.state() == State.PAID || receipt.state() == State.DECLINED) {
                        lease.releaseVerified(receipt);
                        occupied.set(false);
                    }
                    // Nonterminal/ambiguous readbacks do not authorize continuation or release.
                    return receipt;
                })).minimalCompletionStage();
    }

    private CompletableFuture<Observation> observe(Receipt receipt, Leg leg, Lease lease) {
        return run(main, () -> {
            lease.authorize(receipt, leg);
            return Objects.requireNonNull(money.execute(receipt, leg));
        });
    }

    private static void require(Receipt receipt, Handle handle, State expected) {
        if (!receipt.handle().equals(handle) || receipt.state() != expected)
            throw new IllegalStateException("Unexpected transfer acknowledgement");
    }

    private static <T> CompletableFuture<T> run(Executor executor, Checked<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try { result.complete(action.get()); }
                catch (Exception | LinkageError failure) { result.completeExceptionally(failure); }
            });
        } catch (RuntimeException failure) { result.completeExceptionally(failure); }
        return result;
    }
}

package com.prosperitysmp.npc.economy;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import static com.prosperitysmp.npc.economy.TransferJournal.*;
import static com.prosperitysmp.npc.economy.TransferProof.*;

/**
 * Serial, non-retrying dispatch. SQL runs on worker; authority and provider calls
 * run on main. Used by the payment runtimes. Caller must acquire the cooperative
 * ownership/account lease and reserve budgets before submitting. An uncertain
 * operation latches this dispatcher shut and retains the lease for reconciliation.
 * A leg refused before its money call is journaled as evidence: a refused debit is
 * a decline and releases the lease; a refused credit still latches for staff.
 */
final class JournaledTransfer {
    interface Journal {
        Start beginDebit(Handle handle) throws Exception;
        Receipt recordDebit(Handle handle, Observation observation) throws Exception;
        Start beginCredit(Handle handle) throws Exception;
        Receipt recordCredit(Handle handle, Observation observation) throws Exception;
    }

    interface Lease {
        /**
         * Before a debit, recheck runtime switches, permission, ownership, provider and reserved budget. Before a
         * credit, recheck only the provider, so nothing that changes between the legs strands the debit. A receipt
         * bound to another payment is a fault, not a refusal: throw {@link BindingFailed} for it, and the dispatcher
         * stays latched.
         */
        void authorize(Receipt receipt, Leg leg) throws Exception;
        /** Main thread, called only after both durable acknowledgements or proved decline. */
        void releaseVerified(Receipt receipt) throws Exception;
    }

    /** The journal receipt does not belong to the payment the lease was taken for. Never read as a refusal. */
    static final class BindingFailed extends RuntimeException {
        private static final long serialVersionUID = 1L;
        BindingFailed(String message) {
            super(message);
        }
    }

    @FunctionalInterface interface Money {
        Observation execute(Receipt receipt, Leg leg) throws Exception;
    }
    @FunctionalInterface private interface Checked<T> { T get() throws Exception; }

    private final Journal journal;
    private final Money money;
    private final Executor worker, main;
    private final BiConsumer<Leg, Observation> refusals;
    private final AtomicBoolean occupied = new AtomicBoolean();

    JournaledTransfer(TransferJournal journal, Money money, Executor worker, Executor main) {
        this(journal, money, worker, main, (leg, o) -> { });
    }

    /** refusals hears, on main, every leg that was refused before its money call. */
    JournaledTransfer(TransferJournal journal, Money money, Executor worker, Executor main, BiConsumer<Leg, Observation> refusals) {
        this(new Journal() {
            public Start beginDebit(Handle h) throws Exception { return journal.beginDebit(h); }
            public Receipt recordDebit(Handle h, Observation o) throws Exception { return journal.recordDebit(h, o); }
            public Start beginCredit(Handle h) throws Exception { return journal.beginCredit(h); }
            public Receipt recordCredit(Handle h, Observation o) throws Exception { return journal.recordCredit(h, o); }
        }, money, worker, main, refusals);
        Objects.requireNonNull(journal);
    }

    JournaledTransfer(Journal journal, Money money, Executor worker, Executor main) {
        this(journal, money, worker, main, (leg, o) -> { });
    }

    JournaledTransfer(Journal journal, Money money, Executor worker, Executor main, BiConsumer<Leg, Observation> refusals) {
        this.journal = Objects.requireNonNull(journal);
        this.money = Objects.requireNonNull(money);
        this.worker = Objects.requireNonNull(worker);
        this.main = Objects.requireNonNull(main);
        this.refusals = Objects.requireNonNull(refusals);
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
            try {
                lease.authorize(receipt, leg);
            } catch (BindingFailed fault) {
                // A wrong binding means a defect somewhere: it fails the chain and keeps the latch for staff.
                throw fault;
            } catch (Exception | LinkageError refused) {
                // money.execute has not run, so this leg made no provider call.
                Observation o = Observation.refused(TransferProof.expectedRefusal(refused)
                        ? Refusal.LEASE_REFUSED : Refusal.LEASE_FAILED, refused, null, null);
                heard(leg, o);
                return o;
            }
            // A failure inside the money call is never a refusal: it fails the chain and keeps the latch.
            Observation o = Objects.requireNonNull(money.execute(receipt, leg));
            if (o.refusedBeforeCall() != null) heard(leg, o);
            return o;
        });
    }

    /**
     * The listener only reports. It can never change the evidence or the chain: a listener that throws is logged,
     * and a log that throws too is dropped, so the refusal still reaches the journal.
     */
    private void heard(Leg leg, Observation refused) {
        RefusalMonitor.quietly(() -> refusals.accept(leg, refused));
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

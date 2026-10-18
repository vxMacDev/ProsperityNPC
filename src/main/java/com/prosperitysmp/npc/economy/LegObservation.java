package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.economy.TransferProof.Observation;
import com.prosperitysmp.npc.economy.TransferProof.Refusal;
import com.prosperitysmp.npc.api.LegResponse;
import com.prosperitysmp.npc.api.MoneySource;
import org.bukkit.OfflinePlayer;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * One immediate provider call, not a transfer coordinator. Invoke only after a
 * freshly acknowledged journal intent and live authority lease. No retry/refund,
 * SQL, benefit grant or runtime registration occurs here. The provider must also
 * satisfy the release's durability and exclusive-account mutation contract.
 * A check that stops the leg before the money call is returned as refusal evidence, never thrown.
 */
final class LegObservation {
    private final MoneySource.Pin pinned;
    private final Supplier<MoneySource.Pin> current;
    private final BooleanSupplier mainThread;

    LegObservation(MoneySource.Pin pinned, Supplier<MoneySource.Pin> current, BooleanSupplier mainThread) {
        this.pinned = Objects.requireNonNull(pinned);
        this.current = Objects.requireNonNull(current);
        this.mainThread = Objects.requireNonNull(mainThread);
    }

    /**
     * counterparty is the other side of the transfer. For a debit its balance is checked here, so a receiver
     * near the balance ceiling refuses the debit and can never strand it. A credit does not read it.
     */
    Observation execute(UUID expectedAccount, OfflinePlayer account, OfflinePlayer counterparty,
                        long cents, TransferProof.Leg leg) {
        if (!mainThread.getAsBoolean()) return Observation.refused(Refusal.WRONG_THREAD, null, null, null);
        if (expectedAccount == null || account == null || counterparty == null || leg == null
                || !expectedAccount.equals(account.getUniqueId()) || cents <= 0
                || TransferProof.toCents(cents / 100.0).orElse(-1) != cents)
            return Observation.refused(Refusal.INVALID_REQUEST, null, null, null);
        MoneySource.Leg providerLeg = leg == TransferProof.Leg.DEBIT ? MoneySource.Leg.DEBIT : MoneySource.Leg.CREDIT;
        Double before = null, after = null;
        LegResponse response = null;
        boolean failed = false, called = false;
        Refusal refusal = null;
        Throwable cause = null;
        try {
            if (current.get() != pinned || !pinned.enabled()) throw new Refused(Refusal.PROVIDER_CHANGED);
            // Receiver headroom is checked during the debit, so a balance limit can never strand a debit.
            if (leg == TransferProof.Leg.DEBIT && !TransferProof.canReceive(pinned.balanceDollars(counterparty), cents,
                    TransferProof.RECEIVER_LIMIT_DOLLARS)) throw new Refused(Refusal.COUNTERPARTY_UNVERIFIABLE);
            before = pinned.balanceDollars(account);
            if (leg == TransferProof.Leg.DEBIT) {
                OptionalLong available = TransferProof.availableCents(before);
                if (available.isEmpty()) throw new Refused(Refusal.BALANCE_UNVERIFIABLE);
                // Some Vault providers permit overdrafts. NPC purchases never borrow.
                if (available.getAsLong() < cents) throw new Refused(Refusal.INSUFFICIENT_FUNDS);
            } else if (!TransferProof.canReceive(before, cents,
                    TransferProof.MAX_BALANCE_DOLLARS.subtract(TransferProof.CREDIT_MARGIN_DOLLARS))) {
                throw new Refused(Refusal.BALANCE_UNVERIFIABLE);
            }
            // Re-check immediately before the sole side effect, including reentrant balance hooks.
            if (current.get() != pinned) throw new Refused(Refusal.PROVIDER_CHANGED);
            // From here on any failure may have moved money. This must stay the last statement before the call.
            called = true;
            response = pinned.leg(account, cents, providerLeg);
        } catch (Refused refused) {
            failed = true;
            refusal = refused.reason;
        } catch (RuntimeException | LinkageError uncertain) {
            failed = true;
            if (!called) {
                refusal = TransferProof.expectedRefusal(uncertain) ? Refusal.PRECHECK_REFUSED : Refusal.PRECHECK_FAILED;
                cause = uncertain;
            }
        }
        // Preserve post-call evidence even when the provider moved money and then threw.
        try {
            if (current.get() != pinned) throw new IllegalStateException("Provider changed");
            after = pinned.balanceDollars(account);
            if (current.get() != pinned || !pinned.enabled()) throw new IllegalStateException("Provider changed");
        } catch (RuntimeException | LinkageError uncertain) {
            failed = true;
        }
        if (refusal != null && !called) return Observation.refused(refusal, cause, before, after);
        return new Observation(response != null,
                response != null && response.success(),
                response == null ? null : response.amountDollars(), before, after, failed);
    }

    /** One of this class's own checks stopped the leg before the money call. */
    private static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient Refusal reason;
        Refused(Refusal reason) {
            super(null, null, false, false);
            this.reason = reason;
        }
    }
}

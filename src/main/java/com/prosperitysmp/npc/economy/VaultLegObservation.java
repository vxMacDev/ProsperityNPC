package com.prosperitysmp.npc.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * One immediate provider call, not a transfer coordinator. Invoke only after a
 * freshly acknowledged journal intent and live authority lease. No retry/refund,
 * SQL, benefit grant or runtime registration occurs here. The provider must also
 * satisfy the release's durability and exclusive-account mutation contract.
 */
final class VaultLegObservation {
    private final Economy pinned;
    private final Supplier<Economy> current;
    private final BooleanSupplier mainThread;

    VaultLegObservation(Economy pinned, Supplier<Economy> current, BooleanSupplier mainThread) {
        this.pinned = Objects.requireNonNull(pinned);
        this.current = Objects.requireNonNull(current);
        this.mainThread = Objects.requireNonNull(mainThread);
    }

    TransferProof.Observation execute(UUID expectedAccount, OfflinePlayer account, long cents, TransferProof.Leg leg) {
        if (!mainThread.getAsBoolean()) throw new IllegalStateException("Vault leg requires main thread");
        Objects.requireNonNull(expectedAccount);
        Objects.requireNonNull(account);
        Objects.requireNonNull(leg);
        double amount = cents / 100.0;
        if (!expectedAccount.equals(account.getUniqueId()) || cents <= 0
                || TransferProof.toCents(amount).orElse(-1) != cents) {
            throw new IllegalArgumentException("Invalid account or unrepresentable amount");
        }
        Double before = null, after = null;
        EconomyResponse response = null;
        boolean failed = false;
        try {
            if (current.get() != pinned || !pinned.isEnabled()) throw new IllegalStateException("Provider changed");
            before = pinned.getBalance(account);
            if (TransferProof.toCents(before).isEmpty()) throw new IllegalStateException("Unverifiable starting balance");
            // Some Vault providers permit overdrafts. NPC purchases never borrow.
            if (leg == TransferProof.Leg.DEBIT && TransferProof.toCents(before).orElseThrow() < cents)
                throw new IllegalStateException("Insufficient available balance");
            // Re-check immediately before the sole side effect, including reentrant balance hooks.
            if (current.get() != pinned) throw new IllegalStateException("Provider changed");
            response = leg == TransferProof.Leg.DEBIT
                    ? pinned.withdrawPlayer(account, amount) : pinned.depositPlayer(account, amount);
        } catch (RuntimeException | LinkageError uncertain) {
            failed = true;
        }
        // Preserve post-call evidence even when the provider moved money and then threw.
        try {
            if (current.get() != pinned) throw new IllegalStateException("Provider changed");
            after = pinned.getBalance(account);
            if (current.get() != pinned || !pinned.isEnabled()) throw new IllegalStateException("Provider changed");
        } catch (RuntimeException | LinkageError uncertain) {
            failed = true;
        }
        return new TransferProof.Observation(response != null,
                response != null && response.transactionSuccess(),
                response == null ? null : response.amount, before, after, failed);
    }
}

package com.prosperitysmp.npc.api;

import org.bukkit.OfflinePlayer;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Where player and reserve money lives. ProsperityNPC moves money only through one {@link Pin#leg} call per
 * journaled leg, and every check that may refuse a payment before the money call is made by ProsperityNPC
 * itself, never by the source.
 *
 * <p>Capability names: {@code scale} (must be "2"; anything else is refused at selection),
 * {@code balance-double-exact}, {@code artifact-hashable} (required), {@code non-player-accounts},
 * {@code offline-accounts}, {@code main-thread-only}, {@code reentrant-callbacks}, {@code permits-overdraft}
 * (informational).
 *
 * <p>There is deliberately no transfer, mint, create, set or has method.
 */
public interface MoneySource extends Piece {
    /** The provider to use for one transaction, captured on the main thread. Empty: no provider now. */
    Optional<Pin> pin();

    /**
     * One captured provider. {@link #balanceDollars} and {@link #stillCurrent} must not move money: a provider
     * that moves money inside a read is outside the isolation ProsperityNPC assumes.
     */
    interface Pin {
        /** Same registration as when pinned, with its owner plugin and provider still enabled. Moves no money. */
        boolean stillCurrent();

        /** The provider is enabled. */
        boolean enabled();

        /** The jar ProsperityNPC hashes into provider_sha256: the provider's own jar, not an API jar. */
        Path artifact();

        /** The provider's own balance as a raw double, never rounded. Moves no money. */
        double balanceDollars(OfflinePlayer account);

        /**
         * Exactly one provider money call: a withdraw for DEBIT, a deposit for CREDIT, of {@code cents / 100.0}.
         * Any return, including null, means the call was made. A provider exception must propagate unchanged.
         */
        LegResponse leg(OfflinePlayer account, long cents, Leg leg);
    }

    enum Leg { DEBIT, CREDIT }
}

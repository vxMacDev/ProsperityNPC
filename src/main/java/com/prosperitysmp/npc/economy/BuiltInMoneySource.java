package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.api.MoneySource;

/**
 * A money source that ships inside ProsperityNPC. Core only: third-party sources implement {@link MoneySource}.
 * These reads never move money and never show a balance.
 */
public interface BuiltInMoneySource extends MoneySource {
    /** Startup check: is a provider there right now. False prints the "Vault economy not found" line. */
    boolean probe();

    /** For /npcadmin diagnose: who provides the money, for example "Essentials via Vault". Never a balance. */
    String providerName();

    /** Why the source cannot be used, or "" when it can. */
    String reason();
}

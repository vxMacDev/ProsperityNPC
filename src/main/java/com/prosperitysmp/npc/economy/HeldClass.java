package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.economy.TransferProof.Leg;
import com.prosperitysmp.npc.economy.TransferProof.LegOutcome;
import com.prosperitysmp.npc.economy.TransferProof.Observation;
import com.prosperitysmp.npc.economy.TransferProof.TransferOutcome;

/**
 * Read-only sorting of a payment that holds the runtimes, by the stored evidence alone. It names the recovery
 * path a row could take; it never decides one. D and C need proof of which build wrote the row before any tool
 * acts on them.
 */
public enum HeldClass {
    /** No credit started. An older build's gate would have refused the debit before its money call. */
    D_LEGACY_DEBIT_REFUSAL,
    /** The debit fully moved; an older build's gate would have refused the credit before its money call. */
    C_LEGACY_CREDIT_REFUSAL,
    /** The debit fully moved; this build recorded the credit as refused before its money call. */
    R_REFUSED_CREDIT,
    /** Every leg now proves PAID, or the debit proves no movement. */
    E_NOW_PROVABLE,
    /** No observation, or any other shape. Stays held. */
    U_UNPROVABLE;

    public static HeldClass of(boolean ambiguous, long cents, boolean creditStarted, Observation debit, Observation credit) {
        if (!ambiguous || debit == null) return U_UNPROVABLE;
        if (credit == null && !creditStarted && TransferProof.evaluate(cents, Leg.DEBIT, debit) == LegOutcome.NO_MOVEMENT)
            return E_NOW_PROVABLE;
        if (creditStarted && credit != null && TransferProof.evaluateTransfer(cents, debit, credit) == TransferOutcome.PAID)
            return E_NOW_PROVABLE;
        if (!creditStarted && credit == null && TransferProof.legacyGateRefused(cents, Leg.DEBIT, debit))
            return D_LEGACY_DEBIT_REFUSAL;
        boolean debitMoved = TransferProof.evaluate(cents, Leg.DEBIT, debit) == LegOutcome.CONFIRMED;
        if (creditStarted && credit != null && debitMoved && credit.refusedBeforeCall() != null) return R_REFUSED_CREDIT;
        if (creditStarted && credit != null && debitMoved && TransferProof.legacyGateRefused(cents, Leg.CREDIT, credit))
            return C_LEGACY_CREDIT_REFUSAL;
        return U_UNPROVABLE;
    }
}

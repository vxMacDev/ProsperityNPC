package com.prosperitysmp.npc.adapter.realty;

import com.prosperitysmp.npc.api.contract.PropertyBackendContract;

/**
 * Realty has no guard that can veto: PB-03 is skipped, nothing is ever cancelled (PB-06 passes with an empty list),
 * events do not arrive off the main thread (PB-10) and there is no override flow (PB-13). The backend reads a
 * snapshot, so the subject settles after each silent change: it moves the clocks past a full refresh first.
 */
class RealtyContractTest extends PropertyBackendContract {
    @Override protected Subject subject(Scenario scenario) { return new RealtySubject(); }
}

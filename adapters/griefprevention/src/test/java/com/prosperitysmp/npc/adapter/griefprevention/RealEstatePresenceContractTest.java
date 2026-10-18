package com.prosperitysmp.npc.adapter.griefprevention;

import com.prosperitysmp.npc.api.contract.PropertyBackendContract;

/** Exercises the adapter's presence branch, not the RealEstate add-on's money handling. */
class RealEstatePresenceContractTest extends PropertyBackendContract {
    @Override protected Subject subject(Scenario scenario) {
        var subject = new GpSubject();
        subject.realEstate = true;
        return subject;
    }
}

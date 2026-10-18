package com.prosperitysmp.npc.adapter.griefprevention;

import com.prosperitysmp.npc.api.contract.PropertyBackendContract;

class GriefPreventionContractTest extends PropertyBackendContract {
    @Override protected Subject subject(Scenario scenario) { return new GpSubject(); }
}

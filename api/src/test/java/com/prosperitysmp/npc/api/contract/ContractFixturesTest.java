package com.prosperitysmp.npc.api.contract;

import com.prosperitysmp.npc.api.GuardResult;
import com.prosperitysmp.npc.api.OwnershipHooks;
import com.prosperitysmp.npc.api.PropertyBackend;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The scenario and subject types an adapter's contract test builds, and the defaults they fall back on. */
class ContractFixturesTest {

    @Test void aBalanceScenarioHasLegsThatWorkAndNothingToThrow() {
        var scenario = MoneySourceContract.Scenario.balance(10.5);
        assertEquals(10.5, scenario.balance());
        assertFalse(scenario.legsFail());
        assertNull(scenario.legThrows());
    }

    @Test void aFailingLegsScenarioKeepsTheBalanceAndFailsEveryLeg() {
        var scenario = MoneySourceContract.Scenario.failingLegs(100);
        assertEquals(100.0, scenario.balance());
        assertTrue(scenario.legsFail());
        assertNull(scenario.legThrows());
    }

    @Test void aThrowingLegsScenarioHandsBackTheSameExceptionObject() {
        var thrown = new IllegalStateException("provider diagnostic");
        var scenario = MoneySourceContract.Scenario.throwingLegs(7.25, thrown);
        assertEquals(7.25, scenario.balance());
        assertFalse(scenario.legsFail());
        assertSame(thrown, scenario.legThrows());
    }

    @Test void aBalanceKeepsEveryBitOfTheDouble() {
        double balance = 57492.488404927248686;
        assertEquals(Double.doubleToRawLongBits(balance),
                Double.doubleToRawLongBits(MoneySourceContract.Scenario.balance(balance).balance()));
    }

    @Test void theStandardPropertyScenarioIsAvailableAndFresh() {
        assertNotNull(PropertyBackendContract.Scenario.standard());
        assertNotSame(PropertyBackendContract.Scenario.standard(), PropertyBackendContract.Scenario.standard());
    }

    private static PropertyBackendContract.Subject plainSubject() {
        return new PropertyBackendContract.Subject() {
            @Override public PropertyBackend backend() { throw new AssertionError("not used"); }
            @Override public boolean knowsRegions() { return false; }
            @Override public UUID world() { return new UUID(1, 1); }
            @Override public String region() { return "r"; }
            @Override public UUID owner() { return new UUID(2, 2); }
            @Override public List<Location> cellsOf(String region) { return List.of(); }
            @Override public Location outside() { return new Location(null, 0, 64, 0); }
            @Override public void setOwner(String region, UUID owner) { }
            @Override public void addMember(String region, UUID member) { }
            @Override public void unrelatedEdit(String region) { }
            @Override public void removeRegion(String region) { }
            @Override public void makeUnreadable() { }
            @Override public List<UUID> unpayableOwners() { return List.of(new UUID(0, 0)); }
        };
    }

    @Test void aSubjectWithoutAGuardOffersNoControls() {
        assertNull(plainSubject().guard());
    }

    private static PropertyBackendContract.GuardControls plainControls() {
        return new PropertyBackendContract.GuardControls() {
            @Override public GuardResult install(OwnershipHooks hooks) { return GuardResult.failed("none"); }
            @Override public void uninstall() { }
            @Override public boolean changeOwner(String region, UUID owner, String reason) { return true; }
            @Override public void changeOwnerCancelledElsewhere(String region, UUID owner) { }
            @Override public List<String> neverCancelled() { return List.of(); }
            @Override public List<String> affectedWith(String region) { return List.of(); }
            @Override public void recreate(String region, UUID owner) { }
            @Override public void breakLinkage() { }
        };
    }

    @Test void guardControlsAssumeEventsOnTheMainThreadUnlessToldOtherwise() {
        assertFalse(plainControls().eventsOffMain());
    }

    @Test void guardControlsAssumeNoOverrideFlowUnlessToldOtherwise() {
        assertFalse(plainControls().supportsOverride());
    }

    @Test void anOverrideChangeWithoutAnOverrideFlowIsRefusedWithAMessage() {
        var thrown = assertThrows(UnsupportedOperationException.class,
                () -> plainControls().changeOwnerOverridden("r", new UUID(3, 3)));
        assertEquals("no override flow", thrown.getMessage());
    }
}

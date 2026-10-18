package com.prosperitysmp.npc.api.contract;

import com.prosperitysmp.npc.api.LegResponse;
import com.prosperitysmp.npc.api.MoneySource;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * What every money source must do. An adapter's test extends this class and builds a {@link Subject} over its own
 * fake provider for each {@link Scenario}. Journal-level properties (conservation, no compensating deposit) are not
 * here: they belong to ProsperityNPC's own transfer code, not to a source.
 */
public abstract class MoneySourceContract {

    /** What the fake provider should do. Every account reports {@code balance}. */
    public static final class Scenario {
        private final double balance;
        private final boolean legsFail;
        private final RuntimeException legThrows;

        private Scenario(double balance, boolean legsFail, RuntimeException legThrows) {
            this.balance = balance;
            this.legsFail = legsFail;
            this.legThrows = legThrows;
        }

        public static Scenario balance(double balance) { return new Scenario(balance, false, null); }

        /** The provider answers every money call with an unsuccessful response and moves nothing. */
        public static Scenario failingLegs(double balance) { return new Scenario(balance, true, null); }

        /** The provider throws this exact exception from every money call. */
        public static Scenario throwingLegs(double balance, RuntimeException thrown) { return new Scenario(balance, false, thrown); }

        public double balance() { return balance; }
        public boolean legsFail() { return legsFail; }
        public RuntimeException legThrows() { return legThrows; }
    }

    /** The source under test plus the controls the cases need over its fake provider. */
    public interface Subject {
        MoneySource source();

        /** An account the fake provider knows. */
        OfflinePlayer account();

        /**
         * Every provider money call so far, in order, as "withdraw <amount>" or "deposit <amount>" with the amount
         * written by {@link Double#toString(double)}. Balance reads are not listed.
         */
        List<String> moneyCalls();

        /** Every provider call so far, reads included, by method name. */
        List<String> allCalls();

        /** Another provider takes over the registration. */
        void replaceRegistration();

        /** The plugin that registered the provider is disabled. */
        void disableOwnerPlugin();

        /** The provider reports itself disabled. */
        void disableProvider();

        /** Where the provider's own classes come from: what artifact() must return. */
        Path providerArtifact();

        /** Where the money API's classes come from (the Vault jar for Vault): what artifact() must never return. */
        Path apiArtifact();
    }

    protected abstract Subject subject(Scenario s);

    /** The subject, broken on purpose while {@link ContractMeta} runs a mutant. */
    private Subject build(Scenario s) {
        return ContractMeta.wrap(subject(s));
    }

    private static MoneySource.Pin pin(Subject subject) {
        return subject.source().pin().orElseThrow(() -> new AssertionError("no pin over a registered provider"));
    }

    /**
     * The subject's own capabilities. ProsperityNPC's selector is what refuses another scale; its contract meta-test
     * runs this case's mutant through the selector to prove that.
     */
    @Test @CaseId(value = "MS-01", critical = true)
    void scaleIsTwoAndTheArtifactIsHashable() {
        var capabilities = build(Scenario.balance(10)).source().capabilities();
        assertEquals("2", capabilities.value("scale"), "ProsperityNPC refuses any other scale at selection");
        assertTrue(capabilities.flag("artifact-hashable"));
    }

    @Test @CaseId(value = "MS-02", critical = true)
    void balanceIsTheProvidersDoubleBitForBit() {
        for (double balance : new double[]{0.2899995, 57492.488404927248686, 8388608.0, Math.nextDown(1e9)}) {
            var subject = build(Scenario.balance(balance));
            double read = pin(subject).balanceDollars(subject.account());
            assertEquals(Double.doubleToRawLongBits(balance), Double.doubleToRawLongBits(read), "balance " + balance);
            assertEquals(List.of(), subject.moneyCalls(), "a read moves no money");
        }
    }

    @Test @CaseId(value = "MS-03", critical = true)
    void oneLegIsExactlyOneMoneyCall() {
        for (MoneySource.Leg leg : MoneySource.Leg.values()) {
            for (boolean fail : new boolean[]{false, true}) {
                var subject = build(fail ? Scenario.failingLegs(100) : Scenario.balance(100));
                var pinned = pin(subject);
                pinned.balanceDollars(subject.account());
                pinned.stillCurrent();
                assertEquals(List.of(), subject.moneyCalls(), "reads make no money call");
                LegResponse response = pinned.leg(subject.account(), 1234, leg);
                String expected = (leg == MoneySource.Leg.DEBIT ? "withdraw " : "deposit ") + 12.34;
                assertEquals(List.of(expected), subject.moneyCalls(), leg + (fail ? " failing" : ""));
                if (response != null) assertEquals(!fail, response.success());
            }
        }
    }

    @Test @CaseId(value = "MS-04", critical = true)
    void aProviderThrowPropagatesUnchanged() {
        var thrown = new IllegalStateException("provider diagnostic");
        for (MoneySource.Leg leg : MoneySource.Leg.values()) {
            var subject = build(Scenario.throwingLegs(100, thrown));
            var caught = assertThrows(RuntimeException.class, () -> pin(subject).leg(subject.account(), 500, leg));
            assertSame(thrown, caught, "the same exception object, not wrapped and not swallowed");
        }
    }

    @Test @CaseId(value = "MS-05", critical = true)
    void stillCurrentEndsWithTheRegistrationThePluginOrTheProvider() {
        var replaced = build(Scenario.balance(10));
        var a = pin(replaced);
        assertTrue(a.stillCurrent());
        replaced.replaceRegistration();
        assertFalse(a.stillCurrent(), "registration replaced");

        var pluginOff = build(Scenario.balance(10));
        var b = pin(pluginOff);
        pluginOff.disableOwnerPlugin();
        assertFalse(b.stillCurrent(), "owner plugin disabled");

        var providerOff = build(Scenario.balance(10));
        var c = pin(providerOff);
        providerOff.disableProvider();
        assertFalse(c.stillCurrent(), "provider disabled");
        assertFalse(c.enabled());
        assertEquals(List.of(), providerOff.moneyCalls());
    }

    @Test @CaseId(value = "MS-06", critical = true)
    void theArtifactIsTheProvidersOwnJar() {
        var subject = build(Scenario.balance(10));
        Path artifact = pin(subject).artifact();
        assertEquals(subject.providerArtifact(), artifact);
        assertNotEquals(subject.apiArtifact(), artifact, "hashing the API jar would hide a provider swap");
    }

    @Test @CaseId("MS-07")
    void theLegSendsExactlyCentsOverOneHundred() {
        for (long cents : new long[]{1, 29, 838860799}) {
            for (MoneySource.Leg leg : MoneySource.Leg.values()) {
                var subject = build(Scenario.balance(1e9));
                pin(subject).leg(subject.account(), cents, leg);
                String expected = (leg == MoneySource.Leg.DEBIT ? "withdraw " : "deposit ") + (cents / 100.0);
                assertEquals(List.of(expected), subject.moneyCalls(), cents + " cents");
            }
        }
    }

    @Test @CaseId("MS-11")
    void thereIsNoWayToMakeOrSetMoney() {
        for (Class<?> type : new Class<?>[]{MoneySource.class, MoneySource.Pin.class}) {
            for (Method method : type.getMethods()) {
                String name = method.getName().toLowerCase(Locale.ROOT);
                assertFalse(name.equals("transfer") || name.equals("mint") || name.startsWith("create")
                        || name.startsWith("set") || name.startsWith("has"), type.getSimpleName() + "." + method.getName());
            }
        }
    }
}

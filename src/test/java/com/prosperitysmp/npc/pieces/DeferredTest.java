package com.prosperitysmp.npc.pieces;

import com.prosperitysmp.npc.api.Capabilities;
import com.prosperitysmp.npc.api.LegResponse;
import com.prosperitysmp.npc.api.MoneySource;
import com.prosperitysmp.npc.api.ProsperityNpcApi;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** A deferred piece: unavailable before binding, bound once at startup, lost for good, never hot-swapped. */
class DeferredTest {
    private final List<String> log = new ArrayList<>();

    private static final MoneySource.Pin PIN = new MoneySource.Pin() {
        @Override public boolean stillCurrent() { return true; }
        @Override public boolean enabled() { return true; }
        @Override public Path artifact() { return Path.of("bank.jar"); }
        @Override public double balanceDollars(OfflinePlayer account) { return 0; }
        @Override public LegResponse leg(OfflinePlayer account, long cents, MoneySource.Leg leg) { throw new AssertionError("no money"); }
    };

    private static MoneySource bank() {
        return new MoneySource() {
            @Override public String id() { return "bank"; }
            @Override public int apiMajor() { return ProsperityNpcApi.MAJOR; }
            @Override public int apiMinor() { return ProsperityNpcApi.MINOR; }
            @Override public Capabilities capabilities() {
                return Capabilities.builder().value("scale", "2").flag("artifact-hashable", true).build();
            }
            @Override public Optional<Pin> pin() { return Optional.of(PIN); }
        };
    }

    private PieceSelector.Selection<MoneySource> select(MoneySource... pieces) {
        var candidates = new ArrayList<PieceSelector.Candidate<MoneySource>>();
        for (MoneySource piece : pieces) candidates.add(new PieceSelector.Candidate<>(piece, "BankPlugin", "2.0", 2));
        return new PieceSelector<>(MoneySource.class).select("bank", Map.of(), candidates);
    }

    @Test void unavailableBeforeBinding() {
        var deferred = new Deferred<MoneySource>("money.source", "bank", log::add);
        var source = new DeferredMoneySource(deferred);
        assertFalse(deferred.available());
        assertTrue(source.pin().isEmpty(), "no pin before the server has started");
        assertEquals(Deferred.State.WAITING, deferred.state());
    }

    @Test void boundOnceAtStartup() {
        var deferred = new Deferred<MoneySource>("money.source", "bank", log::add);
        var first = bank();
        deferred.serverLoaded(true, () -> select(first));
        assertTrue(deferred.available());
        assertSame(first, deferred.current().orElseThrow());
        assertEquals("BankPlugin", deferred.owner());
        deferred.serverLoaded(true, () -> select(bank()));
        assertSame(first, deferred.current().orElseThrow(), "a second startup event binds nothing new");
        var pin = new DeferredMoneySource(deferred).pin().orElseThrow();
        assertTrue(pin.stillCurrent());
    }

    @Test void aReloadBindsNothing() {
        var deferred = new Deferred<MoneySource>("money.source", "bank", log::add);
        deferred.serverLoaded(false, () -> select(bank()));
        assertFalse(deferred.available());
        assertTrue(log.getFirst().contains("restart required"), log.toString());
    }

    @Test void unregisterOrPluginDisableLosesItForTheRun() {
        var deferred = new Deferred<MoneySource>("money.source", "bank", log::add);
        var piece = bank();
        deferred.serverLoaded(true, () -> select(piece));
        var pin = new DeferredMoneySource(deferred).pin().orElseThrow();
        deferred.unregistered(new Object());
        assertTrue(deferred.available(), "another service leaving changes nothing");
        deferred.unregistered(piece);
        assertFalse(deferred.available());
        assertFalse(pin.stillCurrent(), "an in-flight pin stops being current");
        assertFalse(pin.enabled());

        var other = new Deferred<MoneySource>("money.source", "bank", log::add);
        other.serverLoaded(true, () -> select(bank()));
        other.pluginDisabled("SomethingElse");
        assertTrue(other.available());
        other.pluginDisabled("BankPlugin");
        assertFalse(other.available());
        other.serverLoaded(true, () -> select(bank()));
        assertFalse(other.available(), "never re-bound in the same run");
    }

    @Test void aLateRegistrationIsLoggedNotUsed() {
        var deferred = new Deferred<MoneySource>("money.source", "bank", log::add);
        deferred.registered("EarlyPlugin");
        assertTrue(log.isEmpty(), "before selection a registration is expected");
        deferred.serverLoaded(true, () -> select(bank()));
        var bound = deferred.current().orElseThrow();
        deferred.registered("LatePlugin");
        assertEquals(1, log.size());
        assertTrue(log.getFirst().contains("registered after selection; restart required"));
        assertSame(bound, deferred.current().orElseThrow());
    }

    @Test void aSelectionFailureLeavesItUnavailableWithTheReason() {
        var deferred = new Deferred<MoneySource>("money.source", "bank", log::add);
        deferred.serverLoaded(true, () -> select(bank(), bank()));
        assertFalse(deferred.available());
        assertTrue(deferred.reason().startsWith("duplicate id bank"), deferred.reason());
    }
}

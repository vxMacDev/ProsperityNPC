package com.prosperitysmp.npc.pieces;

import com.prosperitysmp.npc.api.Capabilities;
import com.prosperitysmp.npc.api.LegResponse;
import com.prosperitysmp.npc.api.MoneySource;
import com.prosperitysmp.npc.api.ProsperityNpcApi;
import org.bukkit.OfflinePlayer;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Stands in for a third-party money source until it is bound. No pin before binding, and a pin taken from it stops
 * being current the moment the piece is lost, so a payment already in flight is refused at its next check.
 */
public final class DeferredMoneySource implements MoneySource {
    private final Deferred<MoneySource> deferred;

    public DeferredMoneySource(Deferred<MoneySource> deferred) {
        this.deferred = Objects.requireNonNull(deferred);
    }

    public Deferred<MoneySource> deferred() { return deferred; }

    @Override public String id() { return deferred.id(); }
    @Override public int apiMajor() { return ProsperityNpcApi.MAJOR; }
    @Override public int apiMinor() { return ProsperityNpcApi.MINOR; }

    @Override
    public Capabilities capabilities() {
        return deferred.current().map(MoneySource::capabilities).orElseGet(() -> Capabilities.builder().build());
    }

    @Override
    public String label() {
        return deferred.current().map(MoneySource::label).orElse("");
    }

    @Override
    public Optional<Pin> pin() {
        var source = deferred.current().orElse(null);
        if (source == null) return Optional.empty();
        return source.pin().map(inner -> new BoundPin(deferred, inner));
    }

    private record BoundPin(Deferred<MoneySource> deferred, Pin inner) implements Pin {
        @Override public boolean stillCurrent() { return deferred.available() && inner.stillCurrent(); }
        @Override public boolean enabled() { return deferred.available() && inner.enabled(); }
        @Override public Path artifact() { return inner.artifact(); }
        @Override public double balanceDollars(OfflinePlayer account) { return inner.balanceDollars(account); }
        @Override public LegResponse leg(OfflinePlayer account, long cents, Leg leg) { return inner.leg(account, cents, leg); }
    }
}

package com.prosperitysmp.npc.api.contract.broken;

import com.prosperitysmp.npc.api.Capabilities;
import com.prosperitysmp.npc.api.LegResponse;
import com.prosperitysmp.npc.api.MoneySource;
import com.prosperitysmp.npc.api.contract.ContractMeta;
import com.prosperitysmp.npc.api.contract.MoneySourceContract;
import org.bukkit.OfflinePlayer;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Deliberately broken money sources, one per critical case of {@link MoneySourceContract}. Each wraps a working
 * subject and breaks exactly one rule. Used only by the contract meta-test.
 */
public enum MoneySourceMutants implements ContractMeta.Mutant {
    /** Reports scale 3, which the selector must refuse. */
    SCALE_IGNORED("MS-01", source -> new Source(source) {
        @Override public Capabilities capabilities() { return Capabilities.builder().value("scale", "3")
                .flag("artifact-hashable", true).build(); }
    }, pin -> pin),
    /** Rounds the balance to cents. */
    ROUNDS_BALANCE("MS-02", source -> source, pin -> new BrokenPin(pin) {
        @Override public double balanceDollars(OfflinePlayer account) {
            return Math.round(inner.balanceDollars(account) * 100.0) / 100.0;
        }
    }),
    /** Puts the money back with a deposit after a failed withdraw. */
    DEPOSITS_BACK("MS-03", source -> source, pin -> new BrokenPin(pin) {
        @Override public LegResponse leg(OfflinePlayer account, long cents, MoneySource.Leg leg) {
            LegResponse response = inner.leg(account, cents, leg);
            if (leg == MoneySource.Leg.DEBIT && (response == null || !response.success()))
                inner.leg(account, cents, MoneySource.Leg.CREDIT);
            return response;
        }
    }),
    /** Swallows a provider exception and reports a failed leg instead. */
    SWALLOWS_THROW("MS-04", source -> source, pin -> new BrokenPin(pin) {
        @Override public LegResponse leg(OfflinePlayer account, long cents, MoneySource.Leg leg) {
            try {
                return inner.leg(account, cents, leg);
            } catch (RuntimeException swallowed) {
                return LegResponse.of(false, 0);
            }
        }
    }),
    /** Checks only that the pin object is the same, never the registration or the enabled states. */
    IDENTITY_ONLY("MS-05", source -> source, pin -> new BrokenPin(pin) {
        @Override public boolean stillCurrent() { return true; }
        @Override public boolean enabled() { return true; }
    }),
    /** Hashes the money API's jar instead of the provider's. */
    HASHES_API_JAR("MS-06", source -> source, pin -> pin);

    private final String caseId;
    private final UnaryOperator<MoneySource> source;
    private final UnaryOperator<MoneySource.Pin> pin;

    MoneySourceMutants(String caseId, UnaryOperator<MoneySource> source, UnaryOperator<MoneySource.Pin> pin) {
        this.caseId = caseId;
        this.source = source;
        this.pin = pin;
    }

    @Override public String caseId() { return caseId; }

    @Override
    public Object mutate(Object subject) {
        if (!(subject instanceof MoneySourceContract.Subject good)) return subject;
        MoneySource broken = source.apply(new Source(good.source()) {
            @Override public Optional<Pin> pin() {
                return inner.pin().map(p -> brokenPin(p, good));
            }
        });
        return new MoneySourceContract.Subject() {
            @Override public MoneySource source() { return broken; }
            @Override public OfflinePlayer account() { return good.account(); }
            @Override public List<String> moneyCalls() { return good.moneyCalls(); }
            @Override public List<String> allCalls() { return good.allCalls(); }
            @Override public void replaceRegistration() { good.replaceRegistration(); }
            @Override public void disableOwnerPlugin() { good.disableOwnerPlugin(); }
            @Override public void disableProvider() { good.disableProvider(); }
            @Override public Path providerArtifact() { return good.providerArtifact(); }
            @Override public Path apiArtifact() { return good.apiArtifact(); }
        };
    }

    private MoneySource.Pin brokenPin(MoneySource.Pin good, MoneySourceContract.Subject subject) {
        if (this == HASHES_API_JAR) return new BrokenPin(good) {
            @Override public Path artifact() { return subject.apiArtifact(); }
        };
        return pin.apply(good);
    }

    /** A source that forwards everything to the working one. */
    private static class Source implements MoneySource {
        final MoneySource inner;
        Source(MoneySource inner) { this.inner = inner; }
        @Override public String id() { return inner.id(); }
        @Override public int apiMajor() { return inner.apiMajor(); }
        @Override public int apiMinor() { return inner.apiMinor(); }
        @Override public Capabilities capabilities() { return inner.capabilities(); }
        @Override public Optional<Pin> pin() { return inner.pin(); }
    }

    /** A pin that forwards everything to the working one. */
    private static class BrokenPin implements MoneySource.Pin {
        final MoneySource.Pin inner;
        BrokenPin(MoneySource.Pin inner) { this.inner = inner; }
        @Override public boolean stillCurrent() { return inner.stillCurrent(); }
        @Override public boolean enabled() { return inner.enabled(); }
        @Override public Path artifact() { return inner.artifact(); }
        @Override public double balanceDollars(OfflinePlayer account) { return inner.balanceDollars(account); }
        @Override public LegResponse leg(OfflinePlayer account, long cents, MoneySource.Leg leg) { return inner.leg(account, cents, leg); }
    }
}

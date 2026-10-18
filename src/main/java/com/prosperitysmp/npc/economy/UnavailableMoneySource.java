package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.api.Capabilities;
import com.prosperitysmp.npc.api.ProsperityNpcApi;

import java.util.Objects;
import java.util.Optional;

/** A money source that has no provider and never will this run, for example because Vault is not installed. */
public final class UnavailableMoneySource implements BuiltInMoneySource {
    private final String id;
    private final String reason;

    public UnavailableMoneySource(String id, String reason) {
        this.id = Objects.requireNonNull(id);
        this.reason = Objects.requireNonNull(reason);
    }

    @Override public String id() { return id; }
    @Override public int apiMajor() { return ProsperityNpcApi.MAJOR; }
    @Override public int apiMinor() { return ProsperityNpcApi.MINOR; }
    @Override public Capabilities capabilities() { return Capabilities.builder().value("scale", "2").build(); }
    @Override public Optional<Pin> pin() { return Optional.empty(); }
    @Override public boolean probe() { return false; }
    @Override public String providerName() { return "none"; }
    @Override public String reason() { return reason; }
}

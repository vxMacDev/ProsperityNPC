package com.prosperitysmp.npc.adapter.realty;

import io.github.md5sha256.realty.api.RealtyBackend;
import io.github.md5sha256.realty.database.entity.FreeholdContractEntity;
import io.github.md5sha256.realty.database.entity.LeaseholdContractEntity;
import io.github.md5sha256.realty.database.entity.RealtyRegionEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Realty's blocking database reads, turned into plots. Runs on the reader thread only. */
final class RealtyReader implements RealtyView.Source {
    private final Supplier<RealtyBackend> service;

    RealtyReader(Supplier<RealtyBackend> service) {
        this.service = service;
    }

    @Override
    public List<RealtyView.Ref> list() {
        var refs = new ArrayList<RealtyView.Ref>();
        for (var each : backend().getAllRegionsWithState()) refs.add(refOf(each.region()));
        return refs;
    }

    @Override
    public List<RealtyView.Plot> read(List<RealtyView.Ref> refs) {
        RealtyBackend backend = backend();
        var plots = new ArrayList<RealtyView.Plot>();
        for (var ref : refs) plot(backend, ref).ifPresent(plots::add);
        return plots;
    }

    @Override
    public Optional<RealtyView.Plot> load(UUID worldId, String regionId) {
        RealtyBackend backend = backend();
        var state = backend.getRegionWithState(regionId, worldId);
        if (state == null) return Optional.empty();
        return plot(backend, refOf(state.region()));
    }

    private RealtyBackend backend() {
        RealtyBackend backend = service.get();
        if (backend == null) throw new IllegalStateException("Realty's service is not registered");
        return backend;
    }

    private static RealtyView.Ref refOf(RealtyRegionEntity region) {
        return new RealtyView.Ref(region.worldId(), region.worldGuardRegionId(), region.realtyRegionId());
    }

    /** Empty when the region has no contract at all. A region with both kinds is reported as such and has no holder. */
    private static Optional<RealtyView.Plot> plot(RealtyBackend backend, RealtyView.Ref ref) {
        FreeholdContractEntity freehold = backend.getFreeholdContract(ref.regionId(), ref.worldId());
        LeaseholdContractEntity lease = backend.getLeaseholdContract(ref.regionId(), ref.worldId());
        if (freehold == null && lease == null) return Optional.empty();
        if (freehold != null && lease != null) return Optional.of(plot(ref, RealtyView.Kind.BOTH, null, null));
        if (freehold != null) return Optional.of(plot(ref, RealtyView.Kind.FREEHOLD, freehold.titleHolderId(), null));
        return Optional.of(plot(ref, RealtyView.Kind.LEASEHOLD, lease.tenantId(), lease));
    }

    private static RealtyView.Plot plot(RealtyView.Ref ref, RealtyView.Kind kind, UUID holder, LeaseholdContractEntity lease) {
        var holderKind = holder == null ? RealtyView.Holder.NONE : RealtyView.Holder.PLAYER;
        var start = lease == null ? null : lease.startDate();
        var end = lease == null ? null : lease.endDate();
        var termination = lease == null ? null : lease.terminationEffectiveDate();
        return new RealtyView.Plot(ref.worldId(), ref.regionId(), ref.realtyRegionId(), kind, holderKind, holder,
                start, end, termination);
    }
}

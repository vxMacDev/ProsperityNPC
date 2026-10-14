package com.prosperitysmp.npc.town;

import java.util.Objects;
import java.util.UUID;

/** Staff-authored nonfinancial placement of an existing persistent resident, not a tenancy. */
public record CivicPlacement(UUID residentId, UUID worldUuid, String districtId, boolean enabled, long revision) {
    public CivicPlacement {
        Objects.requireNonNull(residentId);
        Objects.requireNonNull(worldUuid);
        requireDistrict(districtId);
        if (revision < 1) throw new IllegalArgumentException("Placement revision must be positive");
    }

    static void requireDistrict(String district) {
        if (district == null || !district.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Invalid navigation district ID");
        }
    }
}

package com.prosperitysmp.npc.charge;

import com.prosperitysmp.npc.property.Property;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BillingRuntimeHoldRentTest {
    private static final LocalDate DAY = LocalDate.of(2026, 10, 18);
    private static final UUID WORLD = new UUID(0x51L << 56, 1);
    private static final UUID OWNER = UUID.fromString("7a1b2c3d-0000-4000-8000-0000000000a1");
    private static final UUID BUYER = UUID.fromString("6c000000-0000-4000-8000-0000000000c3");
    private static final UUID HOME = UUID.fromString("00000000-0000-4000-8000-00000000f001");
    private static final Supplier<List<BillingRuntime.Open>> NOT_READ = () -> { throw new AssertionError("cache read"); };

    private static ChargeCatalogue.Catalogue catalogue(String unpaid) throws Exception {
        return ChargeCatalogue.load(Map.of("enabled", true, "on-unpaid", unpaid,
                "limits", Map.of("per-owner-daily-max-cents", "1000", "server-daily-max-cents", "10000"),
                "policies", Map.of("test-bill", Map.of("version", "1", "title", "Bill", "amount", "100",
                        "max-cents", "1000"))), Map.of());
    }

    private static Property home(UUID owner, int epoch, boolean enabled) {
        return new Property(HOME, WORLD, "home", "test", "room", 4, 1000, 1000, owner, epoch, 80, enabled);
    }

    private static BillingRuntime.Open open(UUID owner, int epoch) {
        return new BillingRuntime.Open(HOME, owner, epoch, DAY.minusDays(1), DAY.plusDays(5));
    }

    @Test void holdRentWaitsUntilTheFirstCatalogueLoadHasFinished() throws Exception {
        // Startup: the catalogue is still the disabled placeholder, the live config asks for hold-rent.
        Optional<Set<UUID>> waiting = BillingRuntime.rentHolds(false, true, "hold-rent",
                ChargeCatalogue.Catalogue.DISABLED, DAY, NOT_READ, List.of(home(OWNER, 0, true)));
        assertTrue(waiting.isEmpty(), "rent must wait, not run unheld");
        // Loaded with hold-rent: the cache decides.
        assertEquals(Set.of(HOME), BillingRuntime.rentHolds(true, true, "hold-rent", catalogue("hold-rent"), DAY,
                () -> List.of(open(OWNER, 0)), List.of(home(OWNER, 0, true))).orElseThrow());
        // Loaded, but the block was refused at startup: bills stay off and rent runs as before.
        assertEquals(Set.of(), BillingRuntime.rentHolds(true, true, "hold-rent", ChargeCatalogue.Catalogue.DISABLED, DAY,
                NOT_READ, List.of(home(OWNER, 0, true))).orElseThrow());
        // Loaded with hold-rent, cache not readable: rent waits.
        assertTrue(BillingRuntime.rentHolds(true, true, "hold-rent", catalogue("hold-rent"), DAY, () -> null,
                List.of(home(OWNER, 0, true))).isEmpty());
    }

    @Test void withoutHoldRentInTheConfigNothingWaitsAndTheCacheIsNeverRead() throws Exception {
        for (Object enabled : new Object[]{null, false, "true"}) {
            assertEquals(Set.of(), BillingRuntime.rentHolds(false, enabled, "hold-rent", ChargeCatalogue.Catalogue.DISABLED,
                    DAY, NOT_READ, List.of()).orElseThrow(), String.valueOf(enabled));
        }
        for (Object unpaid : new Object[]{null, "none", "HOLD-RENT"}) {
            assertEquals(Set.of(), BillingRuntime.rentHolds(false, true, unpaid, ChargeCatalogue.Catalogue.DISABLED,
                    DAY, NOT_READ, List.of()).orElseThrow(), String.valueOf(unpaid));
        }
        assertEquals(Set.of(), BillingRuntime.rentHolds(true, true, "none", catalogue("none"), DAY, NOT_READ,
                List.of(home(OWNER, 0, true))).orElseThrow());
    }

    @Test void anOpenBillHoldsOnlyTheOwnershipItWasIssuedTo() throws Exception {
        var bill = List.of(open(OWNER, 3));
        var cat = catalogue("hold-rent");
        assertEquals(Set.of(HOME), BillingRuntime.rentHolds(true, true, "hold-rent", cat, DAY, () -> bill,
                List.of(home(OWNER, 3, true))).orElseThrow());
        for (Property changed : List.of(home(BUYER, 4, true), home(OWNER, 4, true), home(BUYER, 3, true),
                home(OWNER, 3, false), home(null, 3, true))) {
            assertEquals(Set.of(), BillingRuntime.rentHolds(true, true, "hold-rent", cat, DAY, () -> bill,
                    List.of(changed)).orElseThrow(), changed.toString());
        }
        assertEquals(Set.of(), BillingRuntime.rentHolds(true, true, "hold-rent", cat, DAY, () -> bill, List.of()).orElseThrow());
    }
}

package com.prosperitysmp.npc.adapter.realty;

import java.util.List;

/**
 * The simple names of the Realty post-events the vendor view binds. Each one changes who holds a plot, a lease
 * date, or whether the region is registered. The model fires these, and a source guard test checks the vendor file
 * against the list.
 */
final class BoundEvents {
    static final List<String> NAMES = List.of(
            "RegionBoughtEvent",
            "RegionRentedEvent",
            "RegionUnrentedEvent",
            "LeaseExpiredEvent",
            "LeaseTerminatedEvent",
            "AuctionWonPurchaseEvent",
            "OfferPurchaseCompletedEvent",
            "TitleTransferredEvent",
            "TenantSetEvent",
            "LeaseExtendedEvent",
            "LeaseTerminationScheduledEvent",
            "LeaseTerminationCancelledEvent",
            "RegionCreatedEvent",
            "RegionDeletedEvent");

    private BoundEvents() { }
}

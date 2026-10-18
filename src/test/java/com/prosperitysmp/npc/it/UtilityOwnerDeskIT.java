package com.prosperitysmp.npc.it;

import com.prosperitysmp.npc.economy.UtilityPrices;
import com.prosperitysmp.npc.economy.UtilityBill;
import com.prosperitysmp.npc.economy.UtilityRepository;
import com.prosperitysmp.npc.property.OwnerDeskData;
import com.prosperitysmp.npc.property.OwnerDeskRepository;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.PropertyRepository;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real SQL snapshots; no provider or connected-client claim. */
class UtilityOwnerDeskIT extends ContractItBase {
    private static final UUID OWNER = new UUID(0, 8201), RESERVE = new UUID(0, 8202);
    private static final String REGION = "utility-desk";
    private Property property;

    @BeforeEach void enrollHome() throws Exception {
        property = new Property(UUID.randomUUID(), WORLD, REGION, "test", "room", 2, 100, 100, OWNER, 0, 80, true);
        new PropertyRepository(database).insertEnrollment(property, 5000);
    }

    @Test void optInDeskReadCarriesFrozenBillWithoutWritesOrCurrentPriceDependency() throws Exception {
        assertTrue(view(OWNER).utilityBill().isEmpty());
        var tariffs = new UtilityPrices(1777, 0, 0);
        var bill = issue(tariffs);
        var before = rows("SELECT * FROM npc_utility_bills");
        assertTrue(new OwnerDeskRepository(database).read(WORLD, REGION, OWNER, DAY).orElseThrow().utilityBill().isEmpty());
        var desk = view(OWNER);
        assertEquals(bill, desk.utilityBill().orElseThrow());
        assertEquals(1777, desk.utilityMaximum(new UtilityPrices(2399, 0, 0)));
        assertTrue(desk.canPayUtility());
        assertEquals(before, rows("SELECT * FROM npc_utility_bills"));
        assertEquals(1, rows("SELECT * FROM npc_utility_payments").size());
    }

    @Test void earlierOwnershipIsVisibleButCannotAuthorizeTheDeskPayment() throws Exception {
        issue(UtilityPrices.DEFAULT);
        UUID nextOwner = new UUID(0, 8209);
        execute("UPDATE npc_properties SET owner_uuid=?,ownership_epoch=1 WHERE property_id=?", nextOwner, property.propertyId());
        var desk = view(nextOwner);
        assertEquals(OWNER, desk.utilityBill().orElseThrow().ownerId());
        assertFalse(desk.canPayUtility());
        assertEquals(100, desk.utilityMaximum(new UtilityPrices(2399, 0, 0)));
    }

    @Test void corruptFrozenTariffDoesNotBecomeAnOwnerDeskMaximum() throws Exception {
        issue(new UtilityPrices(1777, 0, 0));
        execute("UPDATE npc_utility_bills SET configured_base_cents=1778,amount_cents=1778 WHERE property_id=?", property.propertyId());
        assertThrows(SQLException.class, () -> view(OWNER));
    }

    private OwnerDeskData view(UUID owner) throws SQLException {
        return new OwnerDeskRepository(database, Clock.fixed(NOW, ZoneOffset.UTC), ZONE, 4)
                .read(WORLD, REGION, owner, DAY, false, true).orElseThrow();
    }

    private UtilityBill issue(UtilityPrices prices) throws SQLException {
        var waiting = new UtilityRepository(database, RESERVE, Clock.fixed(NOW, ZoneOffset.UTC), ZONE, 4, prices)
                .preparePayment(UUID.randomUUID(), property.propertyId(), OWNER, 0, DAY).receipt();
        // Keep the SQL-only fixture on the desk's fixed clock.
        execute("UPDATE npc_economy_events SET created_at=? WHERE event_id=?", NOW, waiting.eventId());
        return waiting.bill();
    }
}

package com.prosperitysmp.npc.arm;

import com.prosperitysmp.npc.api.RegionView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ArmRegionViewBehaviourTest {
    private static final UUID WORLD = new UUID(0, 11);
    private static final UUID OTHER_WORLD = new UUID(0, 12);
    private static final UUID OWNER = new UUID(0, 21);

    private static ArmRegionView view(String sellType, boolean sold) {
        return new ArmRegionView("c001", "world", WORLD, OWNER, sellType, sold);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SELL", "sell", "Sell", "sElL"})
    void sellTypeIsSellIgnoringCase(String sellType) {
        var v = view(sellType, true);
        assertTrue(v.isSell());
        assertEquals("sell", v.kind());
    }

    @ParameterizedTest
    @ValueSource(strings = {"RENT", "CONTRACT", "", " SELL", "SELL ", "SELLX"})
    void everyOtherSellTypeIsRent(String sellType) {
        var v = view(sellType, true);
        assertFalse(v.isSell());
        assertEquals("rent", v.kind());
    }

    @ParameterizedTest
    @NullSource
    void aMissingSellTypeIsRent(String sellType) {
        var v = view(sellType, false);
        assertFalse(v.isSell());
        assertEquals("rent", v.kind());
        assertNull(v.market());
    }

    @Test void marketIsTheRawSellTypeUnchanged() {
        assertEquals("sElL", view("sElL", true).market());
        assertEquals("sElL", view("sElL", true).sellType());
    }

    @Test void accessorsReturnTheConstructorValues() {
        var v = new ArmRegionView("Home-7", "town", WORLD, OWNER, "RENT", true);
        assertEquals("Home-7", v.regionId());
        assertEquals("town", v.worldName());
        assertEquals(WORLD, v.worldUuid());
        assertEquals(WORLD, v.worldId());
        assertTrue(v.sold());
        assertEquals(Optional.of(OWNER), v.ownerUuid());
        assertEquals(Optional.of(OWNER), v.owner());
    }

    @Test void aMissingOwnerIsEmptyFromBothAccessors() {
        var v = new ArmRegionView("c001", "world", WORLD, null, "SELL", false);
        assertEquals(Optional.empty(), v.ownerUuid());
        assertEquals(Optional.empty(), v.owner());
    }

    @ParameterizedTest
    @ValueSource(strings = {"c001", "C001"})
    void matchesByWorldUuidIgnoresRegionCase(String requested) {
        assertTrue(view("SELL", true).matches(WORLD, requested));
    }

    @Test void matchesByWorldUuidRefusesAnotherWorld() {
        assertFalse(view("SELL", true).matches(OTHER_WORLD, "c001"));
    }

    @Test void matchesByWorldUuidRefusesAViewWithoutAWorld() {
        var v = new ArmRegionView("c001", "world", null, OWNER, "SELL", true);
        assertFalse(v.matches(WORLD, "c001"));
        assertFalse(v.matches((UUID) null, "c001"));
    }

    @Test void matchesByWorldNameIsCaseSensitiveOnTheWorld() {
        var v = view("SELL", true);
        assertTrue(v.matches("world", "C001"));
        assertFalse(v.matches("World", "c001"));
        assertFalse(v.matches("WORLD", "c001"));
    }

    @Test void matchesByWorldNameRefusesNullsAndOtherRegions() {
        var v = view("SELL", true);
        assertFalse(v.matches((String) null, "c001"));
        assertFalse(v.matches("world", null));
        assertFalse(v.matches("world", "c0011"));
        assertFalse(v.matches("world", "c00"));
        assertFalse(new ArmRegionView(null, "world", WORLD, OWNER, "SELL", true).matches("world", "c001"));
        assertFalse(new ArmRegionView("c001", null, WORLD, OWNER, "SELL", true).matches("world", "c001"));
    }

    @Test void sameRegionAcceptsAnyBackendViewWithTheSameWorldAndId() {
        RegionView other = new RegionView() {
            @Override public UUID worldId() { return WORLD; }
            @Override public String regionId() { return "Plot-9"; }
            @Override public String market() { return "x"; }
            @Override public String kind() { return "owned"; }
            @Override public Optional<UUID> ownerUuid() { return Optional.empty(); }
            @Override public boolean matches(String worldName, String regionId) { return false; }
        };
        assertTrue(ArmRegionView.sameRegion(other, WORLD, "plot-9"));
        assertFalse(ArmRegionView.sameRegion(other, OTHER_WORLD, "plot-9"));
        assertFalse(ArmRegionView.sameRegion(other, WORLD, "plot-90"));
    }

    @Test void sameRegionRefusesEveryMissingPiece() {
        assertFalse(ArmRegionView.sameRegion(null, WORLD, "c001"));
        assertFalse(ArmRegionView.sameRegion(view("SELL", true), null, "c001"));
        assertFalse(ArmRegionView.sameRegion(view("SELL", true), WORLD, null));
        assertFalse(ArmRegionView.sameRegion(new ArmRegionView(null, "world", WORLD, OWNER, "SELL", true), WORLD, "c001"));
        assertFalse(ArmRegionView.sameRegion(new ArmRegionView("c001", "world", null, OWNER, "SELL", true), WORLD, "c001"));
    }

    @Test void equalViewsHaveEqualHashCodesBothWays() {
        var a = view("SELL", true);
        var b = view("SELL", true);
        assertEquals(a, b);
        assertEquals(b, a);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test void viewsWithAllNullFieldsAreEqual() {
        var a = new ArmRegionView(null, null, null, null, null, false);
        var b = new ArmRegionView(null, null, null, null, null, false);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test void everyFieldTakesPartInEquality() {
        var base = new ArmRegionView("c001", "world", WORLD, OWNER, "SELL", true);
        assertNotEquals(base, new ArmRegionView("c002", "world", WORLD, OWNER, "SELL", true));
        assertNotEquals(base, new ArmRegionView("C001", "world", WORLD, OWNER, "SELL", true), "equality keeps region case");
        assertNotEquals(base, new ArmRegionView("c001", "town", WORLD, OWNER, "SELL", true));
        assertNotEquals(base, new ArmRegionView("c001", "world", OTHER_WORLD, OWNER, "SELL", true));
        assertNotEquals(base, new ArmRegionView("c001", "world", WORLD, null, "SELL", true));
        assertNotEquals(base, new ArmRegionView("c001", "world", WORLD, OWNER, "sell", true));
        assertNotEquals(base, new ArmRegionView("c001", "world", WORLD, OWNER, "SELL", false));
        assertNotEquals(base, null);
        assertNotEquals(base, "c001");
    }

    @Test void toStringListsTheSixFieldsInOrder() {
        var v = new ArmRegionView("c001", "world", WORLD, OWNER, "SELL", true);
        assertEquals("ArmRegionView[regionId=c001, worldName=world, worldUuid=00000000-0000-0000-0000-00000000000b, "
                + "ownerUuid=00000000-0000-0000-0000-000000000015, sellType=SELL, sold=true]", v.toString());
    }

    @Test void toStringShowsMissingFieldsAsNull() {
        assertEquals("ArmRegionView[regionId=null, worldName=null, worldUuid=null, ownerUuid=null, sellType=null, sold=false]",
                new ArmRegionView(null, null, null, null, null, false).toString());
    }
}

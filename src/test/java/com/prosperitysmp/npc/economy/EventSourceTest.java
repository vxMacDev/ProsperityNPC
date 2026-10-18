package com.prosperitysmp.npc.economy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The persisted source keys, their money direction and whether a paid sink funds rent. */
class EventSourceTest {

    @ParameterizedTest
    @CsvSource({
            "RENT,npc-property-rent,TO_PUBLIC,false",
            "APPRECIATION,npc-property-appreciation,TO_PUBLIC,false",
            "UTILITY,npc-property-utility,FROM_PUBLIC,true",
            "UPGRADE,npc-property-upgrade,FROM_PUBLIC,true",
            "REPAIR,npc-property-repair,FROM_PUBLIC,true",
            "INSURANCE,npc-property-insurance,FROM_PUBLIC,true",
            "EVICTION,npc-property-eviction,FROM_PUBLIC,true",
            "BILLS,npc-property-bills,FROM_PUBLIC,false"})
    void eachSourceKeepsItsStoredKeyDirectionAndRentFunding(EventSource source, String key,
                                                            EventSource.Direction direction, boolean fundsRent) {
        assertEquals(key, source.key());
        assertEquals(direction, source.direction());
        assertEquals(fundsRent, source.fundsRent());
        assertEquals(direction == EventSource.Direction.TO_PUBLIC, source.isPayout());
    }

    @Test
    void constantOrderIsFixedBecauseTheNamesArePartOfAStoredHash() {
        assertEquals(List.of("RENT", "APPRECIATION", "UTILITY", "UPGRADE", "REPAIR", "INSURANCE", "EVICTION", "BILLS"),
                Arrays.stream(EventSource.values()).map(Enum::name).toList());
    }

    @Test
    void directionsNameTheMoneyFlowColumnValues() {
        assertEquals("TRANSFER_TO_PUBLIC", EventSource.Direction.TO_PUBLIC.moneyFlowKey());
        assertEquals("TRANSFER_FROM_PUBLIC", EventSource.Direction.FROM_PUBLIC.moneyFlowKey());
        assertEquals(2, EventSource.Direction.values().length);
    }

    @ParameterizedTest
    @EnumSource(EventSource.class)
    void onlyAppreciationReportsItselfAsAppreciation(EventSource source) {
        assertEquals(source == EventSource.APPRECIATION, source.isAppreciation());
    }

    @Test
    void keysAreUniqueAndFitTheLedgerSourceColumn() {
        Set<String> keys = new HashSet<>();
        for (EventSource source : EventSource.values()) {
            assertTrue(keys.add(source.key()), source.key());
            assertTrue(source.key().length() <= 48, source.key());
            assertTrue(source.key().matches("npc-property-[a-z]+"), source.key());
        }
        for (EventSource.Direction direction : EventSource.Direction.values())
            assertTrue(direction.moneyFlowKey().length() <= 24, direction.moneyFlowKey());
    }

    @Test
    void exactlyTwoSourcesArePayoutsAndOnlyBillsIsAnUnfundedSink() {
        assertEquals(List.of(EventSource.RENT, EventSource.APPRECIATION),
                Arrays.stream(EventSource.values()).filter(EventSource::isPayout).toList());
        assertEquals(List.of(EventSource.BILLS),
                Arrays.stream(EventSource.values()).filter(s -> !s.isPayout() && !s.fundsRent()).toList());
    }
}

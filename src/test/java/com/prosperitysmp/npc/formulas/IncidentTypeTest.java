package com.prosperitysmp.npc.formulas;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class IncidentTypeTest {
    @ParameterizedTest(name = "roll {0} -> {1}")
    @CsvSource({
            "0, PESTS", "39, PESTS", "40, PLUMBING", "74, PLUMBING", "75, BREAK_IN", "99, BREAK_IN",
            "-1, PESTS", "-2147483648, PESTS", "100, BREAK_IN", "2147483647, BREAK_IN"})
    void aRollPicksByCumulativeWeight(int roll, IncidentType expected) {
        assertEquals(expected, IncidentType.pickByRoll(roll));
    }

    @Test void everyRollInTheRangeGivesEachIncidentItsWeightInRolls() {
        Map<IncidentType, Integer> counts = new EnumMap<>(IncidentType.class);
        for (int roll = 0; roll < IncidentType.totalWeight(); roll++) counts.merge(IncidentType.pickByRoll(roll), 1, Integer::sum);
        assertEquals(Map.of(IncidentType.PESTS, 40, IncidentType.PLUMBING, 35, IncidentType.BREAK_IN, 25), counts);
    }

    @Test void theTableIsFixed() {
        assertEquals(List.of(IncidentType.PESTS, IncidentType.PLUMBING, IncidentType.BREAK_IN), List.of(IncidentType.values()));
        assertEquals(100, IncidentType.totalWeight());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "PESTS | 40 | 5 | 2000 | 16 paper + 8 baked_potato",
            "PLUMBING | 35 | 8 | 3500 | 16 copper_ingot + 4 iron_ingot",
            "BREAK_IN | 25 | 12 | 5000 | 32 iron_ingot + 8 glass"})
    void eachIncidentHasFixedDamageAndRepairPrices(IncidentType type, int weight, int damage, long cash, String items) {
        assertEquals(weight, type.weight());
        assertEquals(damage, type.conditionDamage());
        assertEquals(cash, type.cashRepairCents());
        assertEquals(items, type.itemRepairSpec());
    }
}

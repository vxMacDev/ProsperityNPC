package com.prosperitysmp.npc.economy.accounting;

import com.prosperitysmp.npc.ProsperityNpcPlugin;
import com.prosperitysmp.npc.economy.AccountingProjection;
import com.prosperitysmp.npc.economy.EventSource;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Skript accounting bridge calls these by reflection (skript-reflect), so their names and return types are a
 * frozen API. Changing any of them silently breaks the money-flow totals on the server.
 */
class FrozenSkriptApiTest {

    private static void returns(Class<?> owner, String name, Class<?> type, Class<?>... parameters) throws Exception {
        Method method = owner.getMethod(name, parameters);
        assertEquals(type, method.getReturnType(), owner.getSimpleName() + "." + name);
    }

    @Test void theBridgeSurfaceIsUnchanged() throws Exception {
        returns(ProsperityNpcPlugin.class, "accountingProjection", AccountingProjection.class);
        returns(AccountingProjection.class, "snapshot", AccountingProjection.Snapshot.class);
        returns(AccountingProjection.class, "acknowledge", boolean.class, long.class);
        returns(AccountingProjection.Snapshot.class, "getGeneration", long.class);
        returns(AccountingProjection.Snapshot.class, "getGroups", List.class);
        returns(AccountingProjection.Group.class, "getDay", String.class);
        returns(AccountingProjection.Group.class, "getCategory", String.class);
        returns(AccountingProjection.Group.class, "getSources", List.class);
        returns(AccountingProjection.SourceTotal.class, "getSource", String.class);
        returns(AccountingProjection.SourceTotal.class, "getCents", long.class);
    }

    @Test void theGroupShapeFitsTheSkriptVariables() throws Exception {
        // Every source key of each group. The 14 groups (seven days times two categories) are checked on real data by
        // AccountingProjectionCommerceIT.sevenDaysOfEverySourceKeepTheSameAccountingText.
        Field commerce = AccountingProjection.class.getDeclaredField("COMMERCE_SOURCES");
        commerce.setAccessible(true);
        @SuppressWarnings("unchecked") List<String> commerceSources = (List<String>) commerce.get(null);
        var sources = new ArrayList<String>();
        for (EventSource source : EventSource.values()) sources.add(source.key());
        sources.addAll(commerceSources);
        assertEquals(8, EventSource.values().length);
        assertEquals("npc-property-bills", EventSource.values()[7].key());
        assertEquals(sources.size(), new java.util.HashSet<>(sources).size(), "no duplicate accounting keys");
        assertEquals(2, commerceSources.size());
        assertTrue(sources.size() <= 32, "at most 32 sources per group");
        for (String source : sources) assertFalse(source.contains("::"), source + " would split a Skript list variable");
        // Positive control: the check does find a separator when one is there.
        assertTrue("npc::rent".contains("::"));
    }
}

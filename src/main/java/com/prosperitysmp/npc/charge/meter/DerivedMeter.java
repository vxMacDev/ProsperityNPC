package com.prosperitysmp.npc.charge.meter;

import com.prosperitysmp.npc.charge.ChargeVariables;
import com.prosperitysmp.npc.charge.FormulaException;

import java.util.Map;

/**
 * The built-in {@code derived:<variable>} meter: {@code units} is one of the home's own facts, for example the
 * number of tenants. It reads nothing outside ProsperityNPC's own tables.
 */
public final class DerivedMeter {
    public static final String PREFIX = "derived:";

    private DerivedMeter() { }

    public static long units(String variable, Map<String, Long> facts) {
        if (!ChargeVariables.DERIVABLE.contains(variable)) throw new FormulaException("cannot derive units from '" + variable + "'");
        Long value = facts.get(variable);
        if (value == null) throw new FormulaException("no value for '" + variable + "'");
        if (!ChargeVariables.HOME.get(variable).contains(value)) throw new FormulaException("'" + variable + "' is outside its range");
        return value;
    }
}

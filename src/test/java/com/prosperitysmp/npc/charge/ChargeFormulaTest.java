package com.prosperitysmp.npc.charge;

import com.prosperitysmp.npc.formulas.IncidentType;
import com.prosperitysmp.npc.formulas.PropertyFormulas;
import com.prosperitysmp.npc.formulas.UpgradeType;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The bill formula language: parity with today's prices, refused inputs, and a seeded fuzz. */
class ChargeFormulaTest {
    private static Map<String, Rational> values(Object... pairs) {
        Map<String, Rational> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], Rational.of(((Number) pairs[i + 1]).longValue()));
        return m;
    }

    private static long whole(PinnedCharges.Descriptor d, Map<String, Rational> v) {
        Rational r = d.parsed().evaluate(v);
        assertTrue(r.isInteger(), d.id() + " gives an exact whole number");
        return r.longValueExact();
    }

    @Test void utilityParityOverEveryValidInput() {
        int checked = 0;
        for (int capacity = 1; capacity <= 4; capacity++)
            for (int tenants = 0; tenants <= capacity; tenants++)
                for (int adv = 0; adv <= 3; adv++)
                    for (int fac = 0; fac <= 3; fac++) {
                        int levels = adv + fac;
                        assertEquals(PropertyFormulas.dailyUtilitiesCents(tenants, levels),
                                whole(PinnedCharges.UTILITY, values("tenants", tenants, "upgrade_levels", levels)));
                        checked++;
                    }
        assertEquals(224, checked, "14 capacity/tenancy pairs and 16 upgrade combinations");
    }

    @Test void upgradeInsuranceRepairMaintainEvictionParity() {
        for (int level = 1; level <= 3; level++) {
            assertEquals(UpgradeType.ADVERTISING.costToLevelCents(level), whole(PinnedCharges.UPGRADE_ADVERTISING, values("target_level", level)));
            assertEquals(UpgradeType.FACILITIES.costToLevelCents(level), whole(PinnedCharges.UPGRADE_FACILITIES, values("target_level", level)));
        }
        for (int capacity = 1; capacity <= 4; capacity++)
            assertEquals(PropertyFormulas.insurancePriceCents(capacity), whole(PinnedCharges.INSURANCE, values("capacity", capacity)));
        IncidentType[] incidents = IncidentType.values();
        assertEquals(3, incidents.length);
        for (int i = 0; i < incidents.length; i++)
            assertEquals(incidents[i].cashRepairCents(), whole(PinnedCharges.REPAIR_CASH, values("incident", i + 1)));
        assertEquals(PropertyFormulas.MAINTAIN_CENTS, whole(PinnedCharges.MAINTAIN, values()));
        assertEquals(PropertyFormulas.EVICTION_CENTS, whole(PinnedCharges.EVICTION, values()));
        for (var d : PinnedCharges.ALL) RangeCheck.check(d.parsed(), Map.of(), d.variables());
    }

    @Test void refusedInputs() {
        List<String> bad = List.of("java.lang.Runtime", "${x}", "1e9", "0x10", "１２", "NaN", "1;2", "a b",
                "pick(1)", "clamp(1,2)", "floor(1,2)", "exp(1)", "1 +", "(1", "1)", "", "   ", "1234567890123", "1.1234567",
                "1.", ".5", "a$", "'1'", "\"1\"", "1 ** 2", "A");
        for (String text : bad) assertThrows(FormulaException.class, () -> ChargeFormula.parse(text), text);
        String longText = "1+".repeat(5000) + "1";
        assertThrows(FormulaException.class, () -> ChargeFormula.parse(longText), "10,000 characters");
        String nested = "(".repeat(10_000) + "1" + ")".repeat(10_000);
        assertThrows(FormulaException.class, () -> ChargeFormula.parse(nested), "10,000 nested parentheses");
        assertThrows(FormulaException.class, () -> ChargeFormula.parse("(((((((((((((((((1)))))))))))))))))"), "17 deep");
        assertThrows(FormulaException.class, () -> ChargeFormula.parse("1/0").bind(Map.of(), Set.of()), "1/0");
        assertThrows(FormulaException.class, () -> ChargeFormula.parse("1/tenants").bind(Map.of(), Set.of("tenants")),
                "division by a variable");
        assertThrows(FormulaException.class, () -> ChargeFormula.parse("1/(1+1)").bind(Map.of(), Set.of()),
                "division only by a plain number or a vars entry");
        assertEquals(Rational.parse("0.5"), ChargeFormula.parse("1/(2)").bind(Map.of(), Set.of()).evaluate(Map.of()));
        assertThrows(FormulaException.class, () -> ChargeFormula.parse("x + 1").bind(Map.of(), Set.of("tenants")), "unknown name");
        assertThrows(FormulaException.class, () -> ChargeFormula.parse("rate / zero").bind(
                Map.of("rate", Rational.ONE, "zero", Rational.ZERO), Set.of()), "division by a zero constant");
        // A bound formula can still divide by a vars constant.
        assertEquals(Rational.of(5), ChargeFormula.parse("10 / two").bind(Map.of("two", Rational.of(2)), Set.of())
                .evaluate(Map.of("two", Rational.of(2))));
    }

    @Test void pickAndClampAtRuntime() {
        var pick = ChargeFormula.parse("pick(i, 10, 20, 30)");
        assertEquals(Rational.of(20), pick.evaluate(Map.of("i", Rational.of(2))));
        assertThrows(FormulaException.class, () -> pick.evaluate(Map.of("i", Rational.parse("0.5"))));
        assertThrows(FormulaException.class, () -> pick.evaluate(Map.of("i", Rational.of(4))));
        assertThrows(FormulaException.class, () -> ChargeFormula.parse("clamp(1, 10, 5)").evaluate(Map.of()));
        assertEquals(Rational.of(5), ChargeFormula.parse("clamp(9, 1, 5)").evaluate(Map.of()));
        assertEquals(Rational.of(2), ChargeFormula.parse("floor(5 / 2)").evaluate(Map.of()));
        assertEquals(Rational.of(3), ChargeFormula.parse("ceil(5 / 2)").evaluate(Map.of()));
        assertEquals(Rational.of(-3), ChargeFormula.parse("-(1+2)").evaluate(Map.of()));
        assertEquals(Rational.of(1), ChargeFormula.parse("min(4, 1, 9)").evaluate(Map.of()));
        assertEquals(Rational.of(9), ChargeFormula.parse("max(4, 1, 9)").evaluate(Map.of()));
    }

    @Test void rangeCheckRefusesNegativeChargesAndAcceptsZeroBoundary() {
        assertThrows(FormulaException.class,
                () -> RangeCheck.check(ChargeFormula.parse("-1"), Map.of(), Map.of()));

        var ranges = Map.of("tenants", new ChargeVariables.Range(0, 4));
        assertThrows(FormulaException.class,
                () -> RangeCheck.check(ChargeFormula.parse("1-tenants"), Map.of(), ranges));

        var result = RangeCheck.check(ChargeFormula.parse("4-tenants"), Map.of(), ranges);
        assertEquals(Rational.ZERO, result.lo());
        assertEquals(Rational.of(4), result.hi());
        var zero = RangeCheck.check(ChargeFormula.parse("0"), Map.of(), Map.of());
        assertEquals(Rational.ZERO, zero.lo());
        assertEquals(Rational.ZERO, zero.hi());
    }

    @Test void exactArithmeticNoIntermediateRounding() {
        // 0.1 + 0.2 is exactly 0.3, and 1/3 * 3 is exactly 1.
        assertEquals(Rational.parse("0.3"), ChargeFormula.parse("0.1 + 0.2").evaluate(Map.of()));
        assertEquals(Rational.ONE, ChargeFormula.parse("1 / 3 * 3").evaluate(Map.of()));
        assertEquals(Rational.of(1), Rational.parse("0.5").halfUp());
        assertEquals(Rational.of(0), Rational.parse("0.499999").halfUp());
    }

    @Test void oversizedValuesStop() {
        String big = "999999999999 * 999999999999 * 999999999999 * 999999999999";
        assertThrows(FormulaException.class, () -> ChargeFormula.parse(big).evaluate(Map.of()), "past 128 bits");
    }

    @Test void canonicalFormIgnoresWhitespace() {
        assertEquals("base+rate*units", ChargeFormula.parse("  base +  rate*units ").canonical());
        assertEquals(ChargeFormula.parse("base+rate*units").canonical(), ChargeFormula.parse("base + rate * units").canonical());
    }

    /** Seeded fuzz: only FormulaException is ever thrown, evaluation ends, results are exact values or an error. */
    @Test void seededFuzz() {
        Random random = new Random(65);
        String alphabet = "0123456789.+-*/(), abcmpxlfoeirkntu_";
        String[] words = {"min(", "max(", "clamp(", "pick(", "floor(", "ceil(", "a", "b", "tenants", "1", "0.5", ",", ")", "(",
                "+", "-", "*", "/", " "};
        int parsed = 0, evaluated = 0;
        for (int round = 0; round < 20_000; round++) {
            StringBuilder text = new StringBuilder();
            int length = 1 + random.nextInt(40);
            for (int i = 0; i < length; i++) {
                if (random.nextBoolean()) text.append(alphabet.charAt(random.nextInt(alphabet.length())));
                else text.append(words[random.nextInt(words.length)]);
            }
            try {
                ChargeFormula f = ChargeFormula.parse(text.toString());
                parsed++;
                f.bind(Map.of("a", Rational.of(3), "b", Rational.parse("0.25")), Set.of("tenants"));
                Rational r = f.evaluate(Map.of("a", Rational.of(3), "b", Rational.parse("0.25"), "tenants", Rational.of(2)));
                assertNotNull(r);
                assertTrue(r.numerator().bitLength() <= Rational.MAX_BITS && r.denominator().bitLength() <= Rational.MAX_BITS);
                evaluated++;
            } catch (FormulaException expected) {
                // The only error a formula may raise.
            }
        }
        assertTrue(parsed > 100 && evaluated > 50, "the fuzz reached evaluation: " + parsed + " parsed, " + evaluated + " evaluated");
    }
}

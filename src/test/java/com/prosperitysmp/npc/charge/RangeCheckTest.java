package com.prosperitysmp.npc.charge;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RangeCheckTest {
    private static final Map<String, ChargeVariables.Range> TENANTS = Map.of("tenants", new ChargeVariables.Range(0, 4));

    private static Rational r(long num, long den) {
        return Rational.of(BigInteger.valueOf(num), BigInteger.valueOf(den));
    }

    private static RangeCheck.Interval check(String formula, Map<String, ChargeVariables.Range> variables) {
        return RangeCheck.check(ChargeFormula.parse(formula), Map.of(), variables);
    }

    private static String refusal(String formula, Map<String, ChargeVariables.Range> variables) {
        return assertThrows(FormulaException.class, () -> check(formula, variables)).getMessage();
    }

    private static Map<String, ChargeVariables.Range> range(String name, long lo, long hi) {
        return Map.of(name, new ChargeVariables.Range(lo, hi));
    }

    @Test void aRisingLinearFormulaSpansItsEndPoints() {
        assertEquals(new RangeCheck.Interval(Rational.of(100), Rational.of(400), true), check("tenants * 75 + 100", TENANTS));
    }

    @Test void aSubtractionThatCanGoBelowZeroIsRefusedWithItsLowestValue() {
        assertEquals("the result can be below zero (lowest -200)", refusal("100 - tenants * 75", TENANTS));
    }

    @Test void theIntervalCheckIsCautiousAboutAProductOfTwoSignedFactors() {
        // (t - 2)^2 is never negative, but interval arithmetic cannot see the shared variable.
        assertEquals("the result can be below zero (lowest -4)", refusal("(tenants - 2) * (tenants - 2)", TENANTS));
    }

    @Test void aPickIndexOutsideTheListIsRefused() {
        assertEquals("pick index can be outside 1..2", refusal("pick(k, 10, 20)", range("k", 1, 3)));
        assertEquals("pick index can be outside 1..2", refusal("pick(k, 10, 20)", range("k", 0, 2)));
    }

    @Test void aPickIndexInsideTheListSpansTheReachableOptionsOnly() {
        assertEquals(new RangeCheck.Interval(Rational.of(10), Rational.of(20), true), check("pick(k, 10, 20)", range("k", 1, 2)));
        assertEquals(new RangeCheck.Interval(Rational.of(10), Rational.of(30), true),
                check("pick(k, 50, 10, 30, 5)", range("k", 2, 3)));
        assertEquals(new RangeCheck.Interval(Rational.of(5), Rational.of(5), true), check("pick(k, 50, 10, 30, 5)", range("k", 4, 4)));
    }

    @Test void aPickIndexThatIsNotAlwaysWholeIsRefused() {
        assertEquals("pick index is not always a whole number", refusal("pick(k / 2, 10, 20)", range("k", 2, 4)));
    }

    @Test void aPickOverFractionalOptionsIsNotWhole() {
        assertEquals(new RangeCheck.Interval(r(1, 2), Rational.of(3), false), check("pick(k, 0.5, 3)", range("k", 1, 2)));
    }

    @Test void aClampWhoseLowBoundCanPassItsHighBoundIsRefused() {
        assertEquals("clamp low bound can be above its high bound",
                refusal("clamp(x, lo, 7)", Map.of("x", new ChargeVariables.Range(0, 10), "lo", new ChargeVariables.Range(5, 9))));
    }

    @Test void aClampWhoseBoundsCanMeetIsAccepted() {
        var result = check("clamp(x, a, 5)", Map.of("x", new ChargeVariables.Range(0, 10), "a", new ChargeVariables.Range(0, 5)));
        assertEquals(new RangeCheck.Interval(Rational.ZERO, Rational.of(5), true), result);
    }

    @Test void aClampNarrowsTheRangeToItsBounds() {
        assertEquals(new RangeCheck.Interval(Rational.of(2), Rational.of(8), true), check("clamp(x, 2, 8)", range("x", 0, 10)));
    }

    @Test void divisionByAVariableOrByZeroIsRefused() {
        assertEquals("division is allowed only by a non-zero constant", refusal("tenants / tenants", TENANTS));
        assertEquals("division is allowed only by a non-zero constant", refusal("tenants / 0", TENANTS));
    }

    @Test void divisionByAConstantOtherThanOneIsNotWhole() {
        assertEquals(new RangeCheck.Interval(Rational.ZERO, Rational.of(2), false), check("tenants / 2", TENANTS));
        assertEquals(new RangeCheck.Interval(Rational.ZERO, Rational.of(4), true), check("tenants / 1", TENANTS));
        assertEquals(new RangeCheck.Interval(Rational.ZERO, Rational.of(4), true), check("-tenants / -1", TENANTS));
    }

    @Test void divisionByAVarsConstantUsesItsValue() {
        var result = RangeCheck.check(ChargeFormula.parse("tenants / rate"), Map.of("rate", Rational.of(4)), TENANTS);
        assertEquals(new RangeCheck.Interval(Rational.ZERO, Rational.ONE, false), result);
    }

    @Test void minAndMaxCombineTheBoundsOfEveryArgument() {
        var a = range("a", 0, 100);
        assertEquals(new RangeCheck.Interval(Rational.ZERO, Rational.of(50), true), check("min(a, 50)", a));
        assertEquals(new RangeCheck.Interval(Rational.of(50), Rational.of(100), true), check("max(a, 50)", a));
        assertEquals(new RangeCheck.Interval(Rational.ZERO, Rational.of(30), true), check("min(a, 50, 30)", a));
        assertEquals(new RangeCheck.Interval(Rational.ZERO, r(1, 2), false), check("min(a, 0.5)", a));
    }

    @Test void negationSwapsTheBounds() {
        assertEquals(new RangeCheck.Interval(Rational.of(6), Rational.of(10), true), check("10 + -tenants", TENANTS));
    }

    @Test void floorAndCeilRoundBothBoundsAndAreWhole() {
        assertEquals(new RangeCheck.Interval(Rational.ZERO, Rational.ONE, true), check("floor(tenants / 3)", TENANTS));
        assertEquals(new RangeCheck.Interval(Rational.ZERO, Rational.of(2), true), check("ceil(tenants / 3)", TENANTS));
    }

    @Test void aFractionalLiteralIsAnExactNonWholeInterval() {
        assertEquals(new RangeCheck.Interval(r(5, 2), r(5, 2), false), check("2.5", Map.of()));
        assertEquals(new RangeCheck.Interval(r(5, 2), r(5, 2), false), RangeCheck.Interval.exact(r(5, 2)));
        assertTrue(RangeCheck.Interval.exact(Rational.of(3)).whole());
    }

    @Test void anUnknownNameIsRefused() {
        assertEquals("unknown name 'nobody'", refusal("nobody + 1", TENANTS));
    }

    @Test void aVarsConstantTakesPrecedenceOverAVariableOfTheSameName() {
        var result = RangeCheck.check(ChargeFormula.parse("tenants * 3"), Map.of("tenants", Rational.of(2)), TENANTS);
        assertEquals(new RangeCheck.Interval(Rational.of(6), Rational.of(6), true), result);
    }

    @Test void nodesTheParserNeverBuildsAreRefused() {
        var unknownOperator = new ChargeFormula.Bin('%', new ChargeFormula.Num(Rational.ONE, "1"), new ChargeFormula.Num(Rational.ONE, "1"));
        assertEquals("unknown operator", assertThrows(FormulaException.class,
                () -> RangeCheck.range(unknownOperator, Map.of(), Map.of())).getMessage());
        var unknownFunction = new ChargeFormula.Call("sqrt", List.of(new ChargeFormula.Num(Rational.ONE, "1")));
        assertEquals("unknown function", assertThrows(FormulaException.class,
                () -> RangeCheck.range(unknownFunction, Map.of(), Map.of())).getMessage());
    }

    @Test void bindsAtFindsTheSmallestValueThatReachesTheCap() {
        var formula = ChargeFormula.parse("100 + 75 * tenants");
        assertEquals(Map.of("tenants", 2L), RangeCheck.bindsAt(formula, Map.of(), TENANTS, 250));
        assertEquals(Map.of("tenants", 3L), RangeCheck.bindsAt(formula, Map.of(), TENANTS, 251));
        assertEquals(Map.of("tenants", 0L), RangeCheck.bindsAt(formula, Map.of(), TENANTS, 100));
        assertEquals(Map.of("tenants", 4L), RangeCheck.bindsAt(formula, Map.of(), TENANTS, 400));
    }

    @Test void bindsAtLeavesOutAVariableWhereTheCapNeverBinds() {
        assertEquals(Map.of(), RangeCheck.bindsAt(ChargeFormula.parse("100 + 75 * tenants"), Map.of(), TENANTS, 10_000));
        assertEquals(Map.of(), RangeCheck.bindsAt(ChargeFormula.parse("100 + 75 * tenants"), Map.of(), TENANTS, 401));
    }

    @Test void bindsAtHoldsOtherVariablesAtTheirLowestAndSortsByName() {
        Map<String, ChargeVariables.Range> ranges = new LinkedHashMap<>();
        ranges.put("upgrade_levels", new ChargeVariables.Range(0, 6));
        ranges.put("tenants", new ChargeVariables.Range(0, 4));
        var result = RangeCheck.bindsAt(ChargeFormula.parse("tenants * 75 + upgrade_levels * 50"), Map.of(), ranges, 200);
        assertEquals(Map.of("tenants", 3L, "upgrade_levels", 4L), result);
        assertEquals(List.of("tenants", "upgrade_levels"), List.copyOf(result.keySet()));
    }

    @Test void bindsAtSkipsVarsConstants() {
        var result = RangeCheck.bindsAt(ChargeFormula.parse("rate * tenants"), Map.of("rate", Rational.of(100)), TENANTS, 200);
        assertEquals(Map.of("tenants", 2L), result);
    }

    @Test void bindsAtGivesNoReportForAVariableWhoseLowPointCannotBeWorkedOut() {
        var ranges = Map.of("k", new ChargeVariables.Range(0, 2), "n", new ChargeVariables.Range(0, 5));
        assertEquals(Map.of("k", 2L), RangeCheck.bindsAt(ChargeFormula.parse("pick(k, 10, 20) + n"), Map.of(), ranges, 15));
    }

    @Test void bindsAtIsCautiousForAFallingFormula() {
        assertEquals(Map.of(), RangeCheck.bindsAt(ChargeFormula.parse("400 - 75 * tenants"), Map.of(), TENANTS, 300));
    }
}

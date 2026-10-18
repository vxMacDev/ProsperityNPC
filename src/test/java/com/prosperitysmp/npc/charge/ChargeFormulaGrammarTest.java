package com.prosperitysmp.npc.charge;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class ChargeFormulaGrammarTest {
    private static Rational value(String formula) {
        return ChargeFormula.parse(formula).evaluate(Map.of());
    }

    private static String refusal(String formula) {
        return assertThrows(FormulaException.class, () -> ChargeFormula.parse(formula)).getMessage();
    }

    private static ChargeFormula.Num num(long v) {
        return new ChargeFormula.Num(Rational.of(v), Long.toString(v));
    }

    @ParameterizedTest(name = "{0} = {1}/{2}")
    @CsvSource(delimiter = '|', value = {
            "2 + 3 * 4 | 14 | 1",
            "(2 + 3) * 4 | 20 | 1",
            "10 - 4 - 3 | 3 | 1",
            "100 / 10 / 5 | 2 | 1",
            "2 * 3 / 4 | 3 | 2",
            "--5 | 5 | 1",
            "- 2 * 3 | -6 | 1",
            "2 * -3 | -6 | 1",
            "-2 - -3 | 1 | 1",
            "-(3) | -3 | 1",
            "floor(7 / 2) | 3 | 1",
            "ceil(7 / 2) | 4 | 1",
            "min(max(1, 5), 3) | 3 | 1",
            "clamp(0.25, 0.5, 2) | 1 | 2",
            "pick(3, 7, 8, 9) | 9 | 1",
            "1\t+\t2 | 3 | 1"})
    void operatorsFollowTheGrammarPrecedence(String formula, long num, long den) {
        assertEquals(Rational.of(BigInteger.valueOf(num), BigInteger.valueOf(den)), value(formula));
    }

    @Test void subtractionIsLeftAssociativeInTheTree() {
        var expected = new ChargeFormula.Bin('-', new ChargeFormula.Bin('-', num(10), num(4)), num(3));
        assertEquals(expected, ChargeFormula.parse("10 - 4 - 3").root());
    }

    @Test void multiplicationBindsTighterThanSubtractionInTheTree() {
        var expected = new ChargeFormula.Bin('-', new ChargeFormula.Var("a"),
                new ChargeFormula.Bin('*', new ChargeFormula.Var("b"), new ChargeFormula.Var("c")));
        assertEquals(expected, ChargeFormula.parse("a-b*c").root());
    }

    @Test void aCallKeepsItsArgumentsInOrder() {
        var expected = new ChargeFormula.Call("pick", List.of(new ChargeFormula.Var("i"), num(1), new ChargeFormula.Neg(num(2))));
        assertEquals(expected, ChargeFormula.parse("pick(i, 1, -2)").root());
    }

    @Test void namesAreSortedDistinctAndReadOnly() {
        var formula = ChargeFormula.parse("b + a * min(c, a)");
        assertEquals(List.of("a", "b", "c"), List.copyOf(formula.names()));
        assertThrows(UnsupportedOperationException.class, () -> formula.names().add("d"));
        assertEquals(Set.of(), ChargeFormula.parse("1 + 2").names());
    }

    @Test void theSixFunctionsAreTheOnlyFunctions() {
        assertEquals(Set.of("min", "max", "clamp", "pick", "floor", "ceil"), ChargeFormula.FUNCTIONS);
        assertEquals("unknown function 'sqrt'", refusal("sqrt(4)"));
        assertEquals("unknown function 'exp'", refusal("exp(1)"));
    }

    @Test void textKeepsTheOriginalAndEqualityIgnoresWhitespace() {
        var spaced = ChargeFormula.parse(" 1 + 2 ");
        var tight = ChargeFormula.parse("1+2");
        assertEquals(" 1 + 2 ", spaced.text());
        assertEquals(" 1 + 2 ", spaced.toString());
        assertEquals(tight, spaced);
        assertEquals(tight.hashCode(), spaced.hashCode());
        assertNotEquals(tight, ChargeFormula.parse("2+1"));
        assertNotEquals(tight, (Object) "1+2");
    }

    @Test void trailingZerosMakeADifferentDefinition() {
        var plain = ChargeFormula.parse("1.5");
        var padded = ChargeFormula.parse("1.50");
        assertEquals(plain.evaluate(Map.of()), padded.evaluate(Map.of()));
        assertNotEquals(plain, padded);
        assertEquals("1.5", plain.canonical());
        assertEquals("1.50", padded.canonical());
    }

    @Test void canonicalDropsSpacesAndTabsOnly() {
        assertEquals("min(a,2)", ChargeFormula.parse("min( a , 2 )").canonical());
        assertEquals("1+2", ChargeFormula.parse("1\t+\t2").canonical());
        assertEquals("-(-x)", ChargeFormula.parse(" - ( - x ) ").canonical());
    }

    @Test void twoHundredFiftySixCharactersIsTheLongestFormula() {
        assertEquals(Rational.ONE, value("1" + " ".repeat(255)));
        assertEquals("formula is longer than 256 characters", refusal("1" + " ".repeat(256)));
    }

    @Test void sixtyFourTokensIsTheMostAFormulaMayHave() {
        String sixtyFour = "-1" + "+1".repeat(31);
        assertEquals(Rational.of(30), value(sixtyFour));
        assertEquals("formula has more than 64 tokens", refusal("1" + "+1".repeat(32)));
    }

    @Test void sixteenParenthesesIsTheDeepestNesting() {
        assertEquals(Rational.ONE, value("(".repeat(16) + "1" + ")".repeat(16)));
        assertEquals("formula nests deeper than 16", refusal("(".repeat(17) + "1" + ")".repeat(17)));
    }

    @Test void sixteenUnaryMinusesIsTheDeepestNesting() {
        assertEquals(Rational.ONE, value("-".repeat(16) + "1"));
        assertEquals("formula nests deeper than 16", refusal("-".repeat(17) + "1"));
    }

    @Test void sixteenNestedCallsIsTheDeepestNesting() {
        assertEquals(Rational.of(2), value("floor(".repeat(16) + "2" + ")".repeat(16)));
        assertEquals("formula nests deeper than 16", refusal("floor(".repeat(17) + "2" + ")".repeat(17)));
    }

    @Test void aNameMayBeThirtyTwoCharacters() {
        String longest = "a".repeat(32);
        assertEquals(Set.of(longest), ChargeFormula.parse(longest).names());
        assertEquals("a name is longer than 32 characters", refusal("a".repeat(33)));
        assertEquals(Set.of("a_1"), ChargeFormula.parse("a_1").names());
    }

    @Test void aNumberMayNotRunIntoAName() {
        assertEquals("a number runs into a name at '2'", refusal("2tenants"));
        assertEquals("a number runs into a name at '2.5'", refusal("2.5x"));
        assertEquals("a number runs into a name at '2'", refusal("2_"));
    }

    @Test void charactersOutsideTheGrammarAreNamed() {
        assertEquals("character '%' is not allowed in a formula", refusal("1 % 2"));
        assertEquals("character 'A' is not allowed in a formula", refusal("A"));
        assertEquals("character '~' is not allowed in a formula", refusal("~1"));
        assertEquals("character '.' is not allowed in a formula", refusal("1.2.3"));
    }

    @Test void controlAndNonAsciiCharactersAreRefusedWithoutEchoingThem() {
        assertEquals("only plain ASCII is allowed in a formula", refusal("1 + é"));
        assertEquals("only plain ASCII is allowed in a formula", refusal("1\n+2"));
        assertEquals("only plain ASCII is allowed in a formula", refusal("1\u007f"));
        assertEquals("only plain ASCII is allowed in a formula", refusal("1\r"));
    }

    @Test void callsWithTheWrongNumberOfValuesAreRefused() {
        assertEquals("min() has the wrong number of values", refusal("min(1)"));
        assertEquals("max() has the wrong number of values", refusal("max(1)"));
        assertEquals("pick() has the wrong number of values", refusal("pick(1)"));
        assertEquals("clamp() has the wrong number of values", refusal("clamp(1, 2, 3, 4)"));
        assertEquals("floor() has the wrong number of values", refusal("floor(1, 2)"));
        assertEquals("ceil() has the wrong number of values", refusal("ceil(1, 2)"));
        assertEquals("unexpected ')'", refusal("ceil()"));
    }

    @Test void malformedShapesNameWhatWentWrong() {
        assertEquals("formula is missing", refusal(null));
        assertEquals("formula is empty", refusal(""));
        assertEquals("formula is empty", refusal(" \t "));
        assertEquals("unexpected '2'", refusal("1 2"));
        assertEquals("expected ')' at the end", refusal("(1"));
        assertEquals("expected ')' before '2'", refusal("min(1 2)"));
        assertEquals("formula ends too early", refusal("1 +"));
        assertEquals("expected '(' at the end", refusal("min"));
        assertEquals("unexpected ','", refusal(",1"));
    }

    @Test void bindReturnsTheSameFormula() {
        var formula = ChargeFormula.parse("tenants * rate");
        assertSame(formula, formula.bind(Map.of("rate", Rational.of(3)), Set.of("tenants")));
    }

    @Test void bindRefusesNamesThatAreNeitherConstantsNorVariables() {
        var failure = assertThrows(FormulaException.class,
                () -> ChargeFormula.parse("tenants + x").bind(Map.of(), Set.of("tenants")));
        assertEquals("unknown name 'x'", failure.getMessage());
    }

    @Test void bindRefusesDivisionByAnythingButANumberOrConstant() {
        String message = "division is allowed only by a number or a vars entry";
        assertEquals(message, assertThrows(FormulaException.class,
                () -> ChargeFormula.parse("1/tenants").bind(Map.of(), Set.of("tenants"))).getMessage());
        assertEquals(message, assertThrows(FormulaException.class,
                () -> ChargeFormula.parse("1/(1+1)").bind(Map.of(), Set.of())).getMessage());
        assertEquals(message, assertThrows(FormulaException.class,
                () -> ChargeFormula.parse("1/-2").bind(Map.of(), Set.of())).getMessage());
    }

    @Test void bindFindsADivisionByZeroAnywhereInTheTree() {
        for (String formula : List.of("1/0", "min(1/0, 2)", "-(1/0)", "2 * (3 + 4/0.0)", "clamp(1, 0, 2/zero)")) {
            var failure = assertThrows(FormulaException.class,
                    () -> ChargeFormula.parse(formula).bind(Map.of("zero", Rational.ZERO), Set.of()), formula);
            assertEquals("division by zero", failure.getMessage(), formula);
        }
    }

    @Test void evaluationNamesAMissingValue() {
        var failure = assertThrows(FormulaException.class, () -> ChargeFormula.parse("a + 1").evaluate(Map.of()));
        assertEquals("no value for 'a'", failure.getMessage());
    }

    @Test void evaluationRefusesABadPickOrClamp() {
        assertEquals("pick index is out of range", assertThrows(FormulaException.class,
                () -> value("pick(0 - 1, 5, 6)")).getMessage());
        assertEquals("pick index is out of range", assertThrows(FormulaException.class, () -> value("pick(3, 5, 6)")).getMessage());
        assertEquals("pick index is out of range", assertThrows(FormulaException.class, () -> value("pick(0, 5, 6)")).getMessage());
        assertEquals("pick index is not a whole number", assertThrows(FormulaException.class,
                () -> value("pick(1.5, 5, 6)")).getMessage());
        assertEquals("clamp low bound is above its high bound", assertThrows(FormulaException.class,
                () -> value("clamp(1, 3, 2)")).getMessage());
        assertEquals(Rational.of(2), value("clamp(1, 2, 2)"));
    }

    @Test void evaluationUsesTheSuppliedValuesExactly() {
        var formula = ChargeFormula.parse("rate * units + base");
        var result = formula.evaluate(Map.of("rate", Rational.parse("0.025"), "units", Rational.of(3), "base", Rational.of(1)));
        assertEquals(Rational.of(BigInteger.valueOf(43), BigInteger.valueOf(40)), result);
    }
}

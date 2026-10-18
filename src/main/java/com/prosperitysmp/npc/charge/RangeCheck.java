package com.prosperitysmp.npc.charge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The load-time range check. Every variable has a declared range, so the lowest and highest result a formula can
 * give is worked out without trying every value (interval arithmetic). The check is cautious: it may refuse a
 * formula that could never actually go wrong, but it never passes one that can.
 *
 * <p>It refuses a formula whose result can be below zero, a {@code pick} whose index is not provably a whole
 * number inside the list, and a {@code clamp} whose low bound can be above its high bound.
 */
public final class RangeCheck {
    /** A closed range of exact values, and whether every value in it is a whole number. */
    public record Interval(Rational lo, Rational hi, boolean whole) {
        static Interval exact(Rational v) { return new Interval(v, v, v.isInteger()); }
    }

    private RangeCheck() { }

    /**
     * The range of the formula's result over the given variable ranges and constants. Throws
     * {@link FormulaException} for a negative result, a bad pick or a bad clamp.
     */
    public static Interval check(ChargeFormula formula, Map<String, Rational> constants,
                                 Map<String, ChargeVariables.Range> variables) {
        Interval result = range(formula.root(), constants, variables);
        if (result.lo().signum() < 0) throw new FormulaException("the result can be below zero (lowest " + result.lo() + ")");
        return result;
    }

    static Interval range(ChargeFormula.Node node, Map<String, Rational> constants,
                          Map<String, ChargeVariables.Range> variables) {
        return switch (node) {
            case ChargeFormula.Num n -> Interval.exact(n.value());
            case ChargeFormula.Var v -> {
                Rational constant = constants.get(v.name());
                if (constant != null) yield Interval.exact(constant);
                ChargeVariables.Range r = variables.get(v.name());
                if (r == null) throw new FormulaException("unknown name '" + v.name() + "'");
                yield new Interval(Rational.of(r.lo()), Rational.of(r.hi()), true);
            }
            case ChargeFormula.Neg n -> {
                Interval a = range(n.operand(), constants, variables);
                yield new Interval(a.hi().negate(), a.lo().negate(), a.whole());
            }
            case ChargeFormula.Bin b -> {
                Interval l = range(b.left(), constants, variables), r = range(b.right(), constants, variables);
                yield switch (b.op()) {
                    case '+' -> new Interval(l.lo().add(r.lo()), l.hi().add(r.hi()), l.whole() && r.whole());
                    case '-' -> new Interval(l.lo().subtract(r.hi()), l.hi().subtract(r.lo()), l.whole() && r.whole());
                    case '*' -> hull(List.of(l.lo().multiply(r.lo()), l.lo().multiply(r.hi()),
                            l.hi().multiply(r.lo()), l.hi().multiply(r.hi())), l.whole() && r.whole());
                    case '/' -> {
                        // The divisor is a single known constant (checked by bind), so this range is exact.
                        if (!r.lo().equals(r.hi()) || r.lo().signum() == 0)
                            throw new FormulaException("division is allowed only by a non-zero constant");
                        yield hull(List.of(l.lo().divide(r.lo()), l.hi().divide(r.lo())),
                                l.whole() && (r.lo().equals(Rational.ONE) || r.lo().equals(Rational.ONE.negate())));
                    }
                    default -> throw new FormulaException("unknown operator");
                };
            }
            case ChargeFormula.Call c -> call(c, constants, variables);
        };
    }

    private static Interval call(ChargeFormula.Call c, Map<String, Rational> constants,
                                 Map<String, ChargeVariables.Range> variables) {
        List<Interval> args = new ArrayList<>();
        for (ChargeFormula.Node a : c.args()) args.add(range(a, constants, variables));
        return switch (c.function()) {
            case "min" -> {
                Rational lo = args.getFirst().lo(), hi = args.getFirst().hi();
                boolean whole = true;
                for (Interval a : args) {
                    lo = Rational.min(lo, a.lo());
                    hi = Rational.min(hi, a.hi());
                    whole &= a.whole();
                }
                yield new Interval(lo, hi, whole);
            }
            case "max" -> {
                Rational lo = args.getFirst().lo(), hi = args.getFirst().hi();
                boolean whole = true;
                for (Interval a : args) {
                    lo = Rational.max(lo, a.lo());
                    hi = Rational.max(hi, a.hi());
                    whole &= a.whole();
                }
                yield new Interval(lo, hi, whole);
            }
            case "clamp" -> {
                Interval x = args.get(0), lo = args.get(1), hi = args.get(2);
                if (lo.hi().compareTo(hi.lo()) > 0)
                    throw new FormulaException("clamp low bound can be above its high bound");
                yield new Interval(Rational.min(Rational.max(x.lo(), lo.lo()), hi.lo()),
                        Rational.min(Rational.max(x.hi(), lo.hi()), hi.hi()), x.whole() && lo.whole() && hi.whole());
            }
            case "pick" -> {
                Interval index = args.getFirst();
                int options = args.size() - 1;
                if (!index.whole()) throw new FormulaException("pick index is not always a whole number");
                if (index.lo().compareTo(Rational.ONE) < 0 || index.hi().compareTo(Rational.of(options)) > 0)
                    throw new FormulaException("pick index can be outside 1.." + options);
                int from = index.lo().numerator().intValueExact(), to = index.hi().numerator().intValueExact();
                Rational lo = null, hi = null;
                boolean whole = true;
                for (int i = from; i <= to; i++) {
                    Interval a = args.get(i);
                    lo = lo == null ? a.lo() : Rational.min(lo, a.lo());
                    hi = hi == null ? a.hi() : Rational.max(hi, a.hi());
                    whole &= a.whole();
                }
                yield new Interval(lo, hi, whole);
            }
            case "floor" -> new Interval(args.getFirst().lo().floor(), args.getFirst().hi().floor(), true);
            case "ceil" -> new Interval(args.getFirst().lo().ceil(), args.getFirst().hi().ceil(), true);
            default -> throw new FormulaException("unknown function");
        };
    }

    private static Interval hull(List<Rational> points, boolean whole) {
        Rational lo = points.getFirst(), hi = points.getFirst();
        for (Rational p : points) {
            lo = Rational.min(lo, p);
            hi = Rational.max(hi, p);
        }
        return new Interval(lo, hi, whole);
    }

    /**
     * For the load message: for each variable, the smallest value at which the result reaches {@code maxCents}
     * while every other variable sits at its lowest value. Variables where the cap never binds are left out.
     * Only exact evaluations are used, so a formula that is not steadily rising gives a cautious answer.
     */
    public static Map<String, Long> bindsAt(ChargeFormula formula, Map<String, Rational> constants,
                                            Map<String, ChargeVariables.Range> variables, long maxCents) {
        Map<String, Long> out = new java.util.TreeMap<>();
        Rational cap = Rational.of(maxCents);
        for (String name : formula.names()) {
            ChargeVariables.Range r = variables.get(name);
            if (r == null || constants.containsKey(name)) continue;
            Map<String, Rational> values = new java.util.HashMap<>(constants);
            for (var e : variables.entrySet()) values.put(e.getKey(), Rational.of(e.getValue().lo()));
            try {
                values.put(name, Rational.of(r.hi()));
                if (formula.evaluate(values).compareTo(cap) < 0) continue;
                long lo = r.lo(), hi = r.hi();
                while (lo < hi) {
                    long mid = lo + (hi - lo) / 2;
                    values.put(name, Rational.of(mid));
                    if (formula.evaluate(values).compareTo(cap) >= 0) hi = mid;
                    else lo = mid + 1;
                }
                out.put(name, lo);
            } catch (FormulaException unworkable) {
                // A point the formula cannot work out (for example a pick index of 0) gives no report for this name.
            }
        }
        return out;
    }
}

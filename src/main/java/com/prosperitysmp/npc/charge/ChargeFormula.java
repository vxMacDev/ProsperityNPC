package com.prosperitysmp.npc.charge;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The bill formula language. A hand-written parser turns text such as {@code base + rate * units} into a small
 * tree, and the tree is worked out with exact fractions. There is no script engine, no reflection and no strings:
 * a formula can only add, subtract, multiply, divide by a constant and call six fixed functions.
 *
 * <pre>
 * expr   := term (('+' | '-') term)*
 * term   := unary (('*' | '/') unary)*
 * unary  := '-' unary | atom
 * atom   := NUMBER | NAME | call | '(' expr ')'
 * call   := ('min' | 'max') '(' expr (',' expr)+ ')'
 *         | 'clamp' '(' expr ',' expr ',' expr ')'
 *         | 'pick' '(' expr (',' expr)+ ')'
 *         | ('floor' | 'ceil') '(' expr ')'
 * NUMBER := [0-9]{1,12} ('.' [0-9]{1,6})?
 * NAME   := [a-z][a-z0-9_]{0,31}
 * </pre>
 *
 * {@code pick(i, a, b, c)} gives {@code a} when {@code i} is 1, {@code b} when 2, and so on.
 */
public final class ChargeFormula {
    public static final int MAX_LENGTH = 256;
    public static final int MAX_TOKENS = 64;
    public static final int MAX_DEPTH = 16;
    /** No formula of 64 tokens needs more steps than this; reaching it is an error, never a hang. */
    public static final int MAX_STEPS = 4096;

    static final Set<String> FUNCTIONS = Set.of("min", "max", "clamp", "pick", "floor", "ceil");

    /** One node of a parsed formula. */
    public sealed interface Node permits Num, Var, Neg, Bin, Call { }
    public record Num(Rational value, String text) implements Node { }
    public record Var(String name) implements Node { }
    public record Neg(Node operand) implements Node { }
    public record Bin(char op, Node left, Node right) implements Node { }
    public record Call(String function, List<Node> args) implements Node { }

    private final String text;
    private final Node root;
    private final Set<String> names;

    private ChargeFormula(String text, Node root) {
        this.text = text;
        this.root = root;
        Set<String> found = new TreeSet<>();
        collect(root, found);
        this.names = Collections.unmodifiableSet(found);
    }

    /** Parses the text and checks its shape. Names are checked later by {@link #bind}. */
    public static ChargeFormula parse(String text) {
        if (text == null) throw new FormulaException("formula is missing");
        if (text.length() > MAX_LENGTH) throw new FormulaException("formula is longer than " + MAX_LENGTH + " characters");
        List<String> tokens = tokens(text);
        Parser parser = new Parser(tokens);
        Node root = parser.expr(0);
        if (parser.at < tokens.size()) throw new FormulaException("unexpected '" + tokens.get(parser.at) + "'");
        return new ChargeFormula(text, root);
    }

    /**
     * Checks every name against the policy: each must be a constant from {@code vars} or a variable the policy may
     * read. Division is allowed only by a literal or a constant, and never by zero.
     */
    public ChargeFormula bind(Map<String, Rational> constants, Set<String> variables) {
        for (String name : names) {
            if (!constants.containsKey(name) && !variables.contains(name))
                throw new FormulaException("unknown name '" + name + "'");
        }
        checkDivisors(root, constants);
        return this;
    }

    private static void checkDivisors(Node node, Map<String, Rational> constants) {
        switch (node) {
            case Num n -> { }
            case Var v -> { }
            case Neg n -> checkDivisors(n.operand(), constants);
            case Bin b -> {
                if (b.op() == '/') {
                    Rational divisor = switch (b.right()) {
                        case Num n -> n.value();
                        case Var v when constants.containsKey(v.name()) -> constants.get(v.name());
                        default -> throw new FormulaException("division is allowed only by a number or a vars entry");
                    };
                    if (divisor.signum() == 0) throw new FormulaException("division by zero");
                }
                checkDivisors(b.left(), constants);
                checkDivisors(b.right(), constants);
            }
            case Call c -> c.args().forEach(a -> checkDivisors(a, constants));
        }
    }

    /** Works the formula out exactly. Every name must have a value; the result is not rounded. */
    public Rational evaluate(Map<String, Rational> values) {
        int[] steps = {0};
        return eval(root, values, steps);
    }

    private static Rational eval(Node node, Map<String, Rational> values, int[] steps) {
        if (++steps[0] > MAX_STEPS) throw new FormulaException("formula took too many steps");
        return switch (node) {
            case Num n -> n.value();
            case Var v -> {
                Rational value = values.get(v.name());
                if (value == null) throw new FormulaException("no value for '" + v.name() + "'");
                yield value;
            }
            case Neg n -> eval(n.operand(), values, steps).negate();
            case Bin b -> {
                Rational l = eval(b.left(), values, steps), r = eval(b.right(), values, steps);
                yield switch (b.op()) {
                    case '+' -> l.add(r);
                    case '-' -> l.subtract(r);
                    case '*' -> l.multiply(r);
                    case '/' -> l.divide(r);
                    default -> throw new FormulaException("unknown operator");
                };
            }
            case Call c -> {
                List<Rational> args = new ArrayList<>(c.args().size());
                for (Node a : c.args()) args.add(eval(a, values, steps));
                yield switch (c.function()) {
                    case "min" -> args.stream().reduce(Rational::min).orElseThrow();
                    case "max" -> args.stream().reduce(Rational::max).orElseThrow();
                    case "clamp" -> {
                        if (args.get(1).compareTo(args.get(2)) > 0) throw new FormulaException("clamp low bound is above its high bound");
                        yield Rational.min(Rational.max(args.get(0), args.get(1)), args.get(2));
                    }
                    case "pick" -> {
                        Rational index = args.get(0);
                        if (!index.isInteger()) throw new FormulaException("pick index is not a whole number");
                        BigInteger i = index.numerator();
                        if (i.signum() <= 0 || i.compareTo(BigInteger.valueOf(args.size() - 1)) > 0)
                            throw new FormulaException("pick index is out of range");
                        yield args.get(i.intValueExact());
                    }
                    case "floor" -> args.get(0).floor();
                    case "ceil" -> args.get(0).ceil();
                    default -> throw new FormulaException("unknown function");
                };
            }
        };
    }

    public String text() { return text; }
    public Node root() { return root; }
    /** Every name the formula uses, sorted. */
    public Set<String> names() { return names; }

    /** The formula with all whitespace removed. Used in the policy hash. */
    public String canonical() {
        StringBuilder out = new StringBuilder(text.length());
        for (String token : tokens(text)) out.append(token);
        return out.toString();
    }

    private static void collect(Node node, Set<String> out) {
        switch (node) {
            case Num n -> { }
            case Var v -> out.add(v.name());
            case Neg n -> collect(n.operand(), out);
            case Bin b -> {
                collect(b.left(), out);
                collect(b.right(), out);
            }
            case Call c -> c.args().forEach(a -> collect(a, out));
        }
    }

    /** Splits text into tokens. Only the ASCII characters of the grammar are accepted. */
    static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        int i = 0, n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t') {
                i++;
                continue;
            }
            int start = i;
            if (c >= '0' && c <= '9') {
                while (i < n && isDigit(text.charAt(i))) i++;
                if (i < n && text.charAt(i) == '.') {
                    i++;
                    while (i < n && isDigit(text.charAt(i))) i++;
                }
                String number = text.substring(start, i);
                Rational.parse(number);
                if (i < n && isNameChar(text.charAt(i))) throw new FormulaException("a number runs into a name at '" + number + "'");
            } else if (c >= 'a' && c <= 'z') {
                while (i < n && isNameChar(text.charAt(i))) i++;
                if (i - start > 32) throw new FormulaException("a name is longer than 32 characters");
            } else if ("+-*/(),".indexOf(c) >= 0) {
                i++;
            } else {
                throw new FormulaException(c > 126 || c < 32
                        ? "only plain ASCII is allowed in a formula"
                        : "character '" + c + "' is not allowed in a formula");
            }
            out.add(text.substring(start, i));
            if (out.size() > MAX_TOKENS) throw new FormulaException("formula has more than " + MAX_TOKENS + " tokens");
        }
        if (out.isEmpty()) throw new FormulaException("formula is empty");
        return out;
    }

    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }
    private static boolean isNameChar(char c) { return (c >= 'a' && c <= 'z') || isDigit(c) || c == '_'; }

    private static final class Parser {
        private final List<String> tokens;
        private int at;

        Parser(List<String> tokens) {
            this.tokens = tokens;
        }

        private String peek() { return at < tokens.size() ? tokens.get(at) : null; }

        private void expect(String token) {
            if (!token.equals(peek())) throw new FormulaException("expected '" + token + "'" + (peek() == null ? " at the end" : " before '" + peek() + "'"));
            at++;
        }

        private static void depth(int depth) {
            if (depth > MAX_DEPTH) throw new FormulaException("formula nests deeper than " + MAX_DEPTH);
        }

        Node expr(int depth) {
            depth(depth);
            Node left = term(depth);
            while ("+".equals(peek()) || "-".equals(peek())) {
                char op = tokens.get(at++).charAt(0);
                left = new Bin(op, left, term(depth));
            }
            return left;
        }

        Node term(int depth) {
            Node left = unary(depth);
            while ("*".equals(peek()) || "/".equals(peek())) {
                char op = tokens.get(at++).charAt(0);
                left = new Bin(op, left, unary(depth));
            }
            return left;
        }

        Node unary(int depth) {
            if ("-".equals(peek())) {
                at++;
                depth(depth + 1);
                return new Neg(unary(depth + 1));
            }
            return atom(depth);
        }

        Node atom(int depth) {
            String token = peek();
            if (token == null) throw new FormulaException("formula ends too early");
            char c = token.charAt(0);
            if (isDigit(c)) {
                at++;
                return new Num(Rational.parse(token), token);
            }
            if (c >= 'a' && c <= 'z') {
                at++;
                if (FUNCTIONS.contains(token)) return call(token, depth + 1);
                if ("(".equals(peek())) throw new FormulaException("unknown function '" + token + "'");
                return new Var(token);
            }
            if (c == '(') {
                at++;
                Node inner = expr(depth + 1);
                expect(")");
                return inner;
            }
            throw new FormulaException("unexpected '" + token + "'");
        }

        Node call(String function, int depth) {
            depth(depth);
            expect("(");
            List<Node> args = new ArrayList<>();
            args.add(expr(depth));
            while (",".equals(peek())) {
                at++;
                args.add(expr(depth));
            }
            expect(")");
            int n = args.size();
            boolean ok = switch (function) {
                case "min", "max", "pick" -> n >= 2;
                case "clamp" -> n == 3;
                case "floor", "ceil" -> n == 1;
                default -> false;
            };
            if (!ok) throw new FormulaException(function + "() has the wrong number of values");
            return new Call(function, List.copyOf(args));
        }
    }

    @Override public boolean equals(Object o) {
        return o instanceof ChargeFormula f && root.equals(f.root);
    }

    @Override public int hashCode() { return Objects.hash(root); }

    @Override public String toString() { return text; }
}

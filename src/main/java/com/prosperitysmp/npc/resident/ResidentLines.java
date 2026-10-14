package com.prosperitysmp.npc.resident;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Owner-authored greeting, about and home lines for one resident, from residents.yml. Never stored in the DB.
 * Each list is empty (the code line is used) or exactly {@link ResidentDialogue#VARIANT_COUNT} lines. A line can't
 * hold '$' or any other money sign, a section sign or a control character, so it can't fake a price, a payment or a
 * coloured system message.
 */
public record ResidentLines(List<String> greeting, List<String> about, List<String> home) {
    public static final int MAX_LINE = 160;
    public static final String NAME_TOKEN = "{name}";
    public static final ResidentLines NONE = new ResidentLines(List.of(), List.of(), List.of());

    public ResidentLines {
        greeting = checked(greeting);
        about = checked(about);
        home = checked(home);
    }

    public boolean isEmpty() { return greeting.isEmpty() && about.isEmpty() && home.isEmpty(); }

    Optional<String> greeting(int variant, String name) { return pick(greeting, variant, name); }
    Optional<String> about(int variant, String name) { return pick(about, variant, name); }
    Optional<String> home(int variant, String name) { return pick(home, variant, name); }

    /** Replaces {name} with the resident's stored name. */
    public static String render(String line, String name) {
        return line.replace(NAME_TOKEN, Objects.requireNonNull(name));
    }

    /** 1 to 160 chars; no money sign, section sign or control char; '{' and '}' only inside one {name} token. */
    public static boolean validLine(String line) {
        if (line == null || line.isEmpty() || line.length() > MAX_LINE || !line.equals(line.strip())) return false;
        if (line.codePoints().anyMatch(c -> Character.isISOControl(c) || moneySign(c) || c == 0x00a7
                || Character.getType(c) == Character.FORMAT || c == 0x2028 || c == 0x2029)) return false;
        int token = line.indexOf(NAME_TOKEN);
        String rest = token < 0 ? line : line.substring(0, token) + line.substring(token + NAME_TOKEN.length());
        return rest.indexOf('{') < 0 && rest.indexOf('}') < 0;
    }

    /** '$' and every other currency symbol, including look-alikes such as the fullwidth and small dollar signs. */
    static boolean moneySign(int c) {
        return c == '$' || Character.getType(c) == Character.CURRENCY_SYMBOL;
    }

    private static List<String> checked(List<String> lines) {
        List<String> copy = List.copyOf(Objects.requireNonNull(lines));
        if (!copy.isEmpty() && copy.size() != ResidentDialogue.VARIANT_COUNT)
            throw new IllegalArgumentException("A line list needs exactly " + ResidentDialogue.VARIANT_COUNT + " lines");
        if (!copy.stream().allMatch(ResidentLines::validLine)) throw new IllegalArgumentException("Invalid resident line");
        return copy;
    }

    private static Optional<String> pick(List<String> lines, int variant, String name) {
        if (lines.isEmpty()) return Optional.empty();
        return Optional.of(render(lines.get(Math.floorMod(variant, ResidentDialogue.VARIANT_COUNT)), name));
    }
}

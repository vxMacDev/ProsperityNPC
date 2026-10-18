package com.prosperitysmp.npc.charge;

import com.google.gson.Gson;
import org.bukkit.configuration.ConfigurationSection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The configured bills and taxes, read from the {@code bills:} block of config.yml. The whole block loads or none of
 * it does: any problem gives a {@link Rejected} that names the key and says why, and the caller keeps bills off (at
 * startup) or keeps the previous catalogue (at reload).
 *
 * <p>Every number must be a quoted string, so YAML never guesses a type: {@code base: 050} or an unquoted
 * {@code 0.02} is an error, not a silently different value.
 */
public final class ChargeCatalogue {
    public static final int MAX_POLICIES = 16;
    public static final int MAX_TITLE = 32;
    public static final int MAX_PAYABLE_DAYS = 90;
    static final Pattern POLICY_ID = Pattern.compile("[a-z][a-z0-9-]{0,23}");
    static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{0,31}");
    static final Pattern METER_ID = Pattern.compile("[a-z][a-z0-9-]{1,31}");
    static final Pattern TITLE = Pattern.compile("[A-Za-z0-9 .,'()&/+-]{1," + MAX_TITLE + "}");

    /** Keys left out on purpose, with the reason the load error gives. */
    static final Map<String, String> FORBIDDEN = Map.of(
            "funds-rent", "configured bills never add rent funding",
            "payee", "configured bills are always paid to the server reserve",
            "counts-as-service", "configured bills are not a service charge",
            "grace-days", "there is no grace period; use payable-days",
            "late-fee", "there are no late fees or debts");
    /** Today's charges keep their own prices and code; they cannot be configured, switched or replaced here. */
    static final Set<String> EXISTING_CHARGES = Set.of("utility", "utilities", "upgrade", "upgrades", "maintain",
            "maintenance", "repair", "repairs", "insurance", "eviction");

    private static final Set<String> TOP_KEYS = Set.of("enabled", "auto-collect-enabled", "on-unpaid", "payable-days",
            "limits", "policies");
    private static final Set<String> LIMIT_KEYS = Set.of("per-owner-daily-max-cents", "server-daily-max-cents");
    private static final Set<String> POLICY_KEYS = Set.of("version", "title", "applies-to", "classes", "markets",
            "properties", "meter", "amount", "vars", "rounding", "min-cents", "max-cents");

    public enum OnUnpaid { NONE, HOLD_RENT }
    public enum AppliesTo { ALL, OCCUPIED, VACANT }
    public enum Rounding { FLOOR, CEIL, HALF_UP }

    /** Where a policy's {@code units} and {@code powered} come from. */
    public record MeterSpec(String kind, String target) {
        public static final String FLAT = "flat";
        public static final String DERIVED = "derived";
        public static final String EXTERNAL = "external";

        public boolean external() { return EXTERNAL.equals(kind); }

        /** The id written into an invoice line: {@code flat}, {@code derived:tenants} or the meter's own id. */
        public String id() { return DERIVED.equals(kind) ? DERIVED + ":" + target : EXTERNAL.equals(kind) ? target : FLAT; }
    }

    /** One configured bill or tax. */
    public record Policy(String id, int version, String title, AppliesTo appliesTo, List<String> classes,
                         List<String> markets, List<UUID> properties, MeterSpec meter, ChargeFormula formula,
                         SortedMap<String, Rational> vars, Rounding rounding, long minCents, long maxCents,
                         String sha256, Map<String, Long> bindsAt) {

        /** The variables this policy may read, with their ranges. */
        public Map<String, ChargeVariables.Range> variables() {
            return variablesFor(meter);
        }

        /** Whether this policy covers a home, before any reading. */
        public boolean appliesTo(UUID property, String classKey, String marketId, int tenants) {
            boolean occupancy = switch (appliesTo) {
                case ALL -> true;
                case OCCUPIED -> tenants > 0;
                case VACANT -> tenants == 0;
            };
            return occupancy && (classes.isEmpty() || classes.contains(classKey))
                    && (markets.isEmpty() || markets.contains(marketId))
                    && (properties.isEmpty() || properties.contains(property));
        }

        /**
         * The rounded amount in cents for one home. Every fact the formula reads must be present and inside its
         * range; anything wrong throws {@link FormulaException} and the line is not charged.
         */
        public long amountCents(Map<String, Long> facts) {
            Map<String, ChargeVariables.Range> ranges = variables();
            Map<String, Rational> values = new LinkedHashMap<>(vars);
            for (String name : formula.names()) {
                if (vars.containsKey(name)) continue;
                Long value = facts.get(name);
                ChargeVariables.Range range = ranges.get(name);
                if (value == null || range == null) throw new FormulaException("no value for '" + name + "'");
                if (!range.contains(value)) throw new FormulaException("'" + name + "' is outside its range");
                values.put(name, Rational.of(value));
            }
            Rational exact = formula.evaluate(values);
            if (exact.signum() < 0) throw new FormulaException("the result is below zero");
            Rational rounded = switch (rounding) {
                case FLOOR -> exact.floor();
                case CEIL -> exact.ceil();
                case HALF_UP -> exact.halfUp();
            };
            return rounded.longValueExact();
        }
    }

    /** A loaded catalogue. {@link #DISABLED} is the default: no bills, every switch off. */
    public record Catalogue(boolean enabled, boolean autoCollect, OnUnpaid onUnpaid, int payableDays,
                            long ownerDailyMaxCents, long serverDailyMaxCents, List<Policy> policies) {
        public static final Catalogue DISABLED = new Catalogue(false, false, OnUnpaid.NONE, 7, 0, 0, List.of());

        public boolean active() { return enabled && !policies.isEmpty(); }

        public Optional<Policy> policy(String id) {
            return policies.stream().filter(p -> p.id().equals(id)).findFirst();
        }
    }

    /** A block that did not load. The message names the key and the reason and holds no secrets. */
    public static final class Rejected extends Exception {
        private static final long serialVersionUID = 1L;

        public Rejected(String message) {
            super(message);
        }
    }

    private ChargeCatalogue() { }

    public static Map<String, ChargeVariables.Range> variablesFor(MeterSpec meter) {
        Map<String, ChargeVariables.Range> m = new LinkedHashMap<>(ChargeVariables.HOME);
        switch (meter.kind()) {
            case MeterSpec.FLAT -> m.put(ChargeVariables.UNITS, new ChargeVariables.Range(1, 1));
            case MeterSpec.DERIVED -> m.put(ChargeVariables.UNITS, ChargeVariables.HOME.get(meter.target()));
            default -> {
                m.put(ChargeVariables.UNITS, new ChargeVariables.Range(0, ChargeVariables.MAX_METER_UNITS));
                m.put(ChargeVariables.POWERED, new ChargeVariables.Range(0, 1));
            }
        }
        return Collections.unmodifiableMap(m);
    }

    /** Converts a Bukkit section to plain maps and lists, keeping every scalar's YAML type. */
    public static Map<String, Object> plain(ConfigurationSection section) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            out.put(key, value instanceof ConfigurationSection child ? plain(child) : value);
        }
        return out;
    }

    /**
     * Loads a {@code bills:} block. {@code known} maps "policy@version" to the definition hash already recorded in
     * the database; a policy that repeats a recorded version with a different definition is refused.
     */
    public static Catalogue load(Map<String, Object> raw, Map<String, String> known) throws Rejected {
        if (raw == null || raw.isEmpty()) return Catalogue.DISABLED;
        try {
            return parse(raw, known == null ? Map.of() : known);
        } catch (FormulaException | IllegalArgumentException failure) {
            throw new Rejected(failure.getMessage());
        }
    }

    private static Catalogue parse(Map<String, Object> raw, Map<String, String> known) throws Rejected {
        keys("bills", raw, TOP_KEYS);
        boolean enabled = flag("bills.enabled", raw.get("enabled"));
        boolean autoCollect = flag("bills.auto-collect-enabled", raw.get("auto-collect-enabled"));
        OnUnpaid onUnpaid = OnUnpaid.NONE;
        Object unpaid = raw.get("on-unpaid");
        if (unpaid != null) {
            if (!(unpaid instanceof String s)) throw new Rejected("bills.on-unpaid: must be none or hold-rent");
            onUnpaid = switch (s) {
                case "none" -> OnUnpaid.NONE;
                case "hold-rent" -> OnUnpaid.HOLD_RENT;
                default -> throw new Rejected("bills.on-unpaid: must be none or hold-rent");
            };
        }
        int payableDays = raw.containsKey("payable-days")
                ? (int) whole("bills.payable-days", raw.get("payable-days"), 1, MAX_PAYABLE_DAYS) : 7;
        long ownerMax = 0, serverMax = 0;
        Object limits = raw.get("limits");
        if (limits != null) {
            Map<String, Object> l = section("bills.limits", limits);
            keys("bills.limits", l, LIMIT_KEYS);
            if (l.containsKey("per-owner-daily-max-cents"))
                ownerMax = whole("bills.limits.per-owner-daily-max-cents", l.get("per-owner-daily-max-cents"),
                        1, ChargeLimits.OWNER_DAILY_CEILING_CENTS);
            if (l.containsKey("server-daily-max-cents"))
                serverMax = whole("bills.limits.server-daily-max-cents", l.get("server-daily-max-cents"),
                        1, ChargeLimits.SERVER_DAILY_CEILING_CENTS);
        }
        if (enabled && (ownerMax == 0 || serverMax == 0))
            throw new Rejected("bills.limits: per-owner-daily-max-cents and server-daily-max-cents are required once bills are enabled");
        List<Policy> policies = new ArrayList<>();
        Object list = raw.get("policies");
        if (list != null) {
            Map<String, Object> p = section("bills.policies", list);
            if (p.size() > MAX_POLICIES) throw new Rejected("bills.policies: at most " + MAX_POLICIES + " policies");
            for (Map.Entry<String, Object> e : new TreeMap<>(p).entrySet())
                policies.add(policy(e.getKey(), e.getValue(), known));
        }
        return new Catalogue(enabled, autoCollect, onUnpaid, payableDays, ownerMax, serverMax, List.copyOf(policies));
    }

    private static Policy policy(String id, Object value, Map<String, String> known) throws Rejected {
        String at = "bills.policies." + id;
        if (!POLICY_ID.matcher(id).matches())
            throw new Rejected(at + ": a policy id is a lowercase letter then up to 23 lowercase letters, digits or dashes");
        if (EXISTING_CHARGES.contains(id) || PinnedCharges.ALL.stream().anyMatch(d -> d.id().equals(id)))
            throw new Rejected(at + ": today's charges keep their own prices and cannot be configured here");
        Map<String, Object> p = section(at, value);
        keys(at, p, POLICY_KEYS);
        int version = (int) whole(at + ".version", required(at + ".version", p.get("version")), 1, 1_000_000);
        String title = text(at + ".title", required(at + ".title", p.get("title")));
        if (!TITLE.matcher(title).matches())
            throw new Rejected(at + ".title: 1 to " + MAX_TITLE + " plain characters");
        AppliesTo applies = AppliesTo.ALL;
        if (p.containsKey("applies-to")) {
            String a = text(at + ".applies-to", p.get("applies-to"));
            applies = switch (a) {
                case "all" -> AppliesTo.ALL;
                case "occupied" -> AppliesTo.OCCUPIED;
                case "vacant" -> AppliesTo.VACANT;
                default -> throw new Rejected(at + ".applies-to: must be all, occupied or vacant");
            };
        }
        List<String> classes = strings(at + ".classes", p.get("classes"));
        List<String> markets = strings(at + ".markets", p.get("markets"));
        List<UUID> properties = new ArrayList<>();
        for (String s : strings(at + ".properties", p.get("properties"))) {
            try {
                UUID u = UUID.fromString(s);
                if (!u.toString().equals(s)) throw new IllegalArgumentException();
                properties.add(u);
            } catch (IllegalArgumentException bad) {
                throw new Rejected(at + ".properties: '" + s + "' is not a property UUID");
            }
        }
        MeterSpec meter = meter(at + ".meter", p.containsKey("meter") ? text(at + ".meter", p.get("meter")) : "flat");
        SortedMap<String, Rational> vars = new TreeMap<>();
        Object varsRaw = p.get("vars");
        if (varsRaw != null) {
            Map<String, Object> v = section(at + ".vars", varsRaw);
            for (Map.Entry<String, Object> e : v.entrySet()) {
                String name = e.getKey();
                String key = at + ".vars." + name;
                if (!NAME.matcher(name).matches() || ChargeFormula.FUNCTIONS.contains(name))
                    throw new Rejected(key + ": not a usable name");
                if (ChargeVariables.HOME.containsKey(name) || name.equals(ChargeVariables.UNITS)
                        || name.equals(ChargeVariables.POWERED))
                    throw new Rejected(key + ": this name is a home variable and cannot be set");
                vars.put(name, number(key, e.getValue()));
            }
        }
        Rounding rounding = Rounding.FLOOR;
        if (p.containsKey("rounding")) {
            rounding = switch (text(at + ".rounding", p.get("rounding"))) {
                case "floor" -> Rounding.FLOOR;
                case "ceil" -> Rounding.CEIL;
                case "half-up" -> Rounding.HALF_UP;
                default -> throw new Rejected(at + ".rounding: must be floor, ceil or half-up");
            };
        }
        long maxCents = whole(at + ".max-cents", required(at + ".max-cents", p.get("max-cents")),
                1, ChargeLimits.PER_BILL_CEILING_CENTS);
        long minCents = p.containsKey("min-cents") ? whole(at + ".min-cents", p.get("min-cents"), 1, maxCents) : 1;
        String amount = text(at + ".amount", required(at + ".amount", p.get("amount")));
        Map<String, ChargeVariables.Range> ranges = variablesFor(meter);
        ChargeFormula formula;
        Map<String, Long> bindsAt;
        try {
            formula = ChargeFormula.parse(amount).bind(vars, ranges.keySet());
            RangeCheck.check(formula, vars, ranges);
            bindsAt = RangeCheck.bindsAt(formula, vars, ranges, maxCents);
        } catch (FormulaException bad) {
            throw new Rejected(at + ".amount: " + bad.getMessage());
        }
        String sha = sha256(canonical(id, version, title, applies, classes, markets, properties, meter, formula, vars,
                rounding, minCents, maxCents));
        String recorded = known.get(id + "@" + version);
        if (recorded != null && !recorded.equals(sha))
            throw new Rejected(at + ".version: version " + version + " was already used for a different definition; raise the version");
        return new Policy(id, version, title, applies, List.copyOf(classes), List.copyOf(markets), List.copyOf(properties),
                meter, formula, Collections.unmodifiableSortedMap(vars), rounding, minCents, maxCents, sha, Map.copyOf(bindsAt));
    }

    static String canonical(String id, int version, String title, AppliesTo applies, List<String> classes,
                            List<String> markets, List<UUID> properties, MeterSpec meter, ChargeFormula formula,
                            SortedMap<String, Rational> vars, Rounding rounding, long minCents, long maxCents) {
        Map<String, String> constants = new TreeMap<>();
        vars.forEach((name, value) -> constants.put(name, value.toString()));
        List<String> props = new ArrayList<>();
        for (UUID u : properties) props.add(u.toString());
        return new Gson().toJson(List.of("bill-policy-v2", id, version, title,
                applies.name().toLowerCase(Locale.ROOT), new java.util.TreeSet<>(classes),
                new java.util.TreeSet<>(markets), new java.util.TreeSet<>(props), meter.id(),
                formula.canonical(), constants, rounding.name().toLowerCase(Locale.ROOT), minCents, maxCents));
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static MeterSpec meter(String key, String value) throws Rejected {
        if (value.equals(MeterSpec.FLAT)) return new MeterSpec(MeterSpec.FLAT, null);
        if (value.startsWith(MeterSpec.DERIVED + ":")) {
            String variable = value.substring(MeterSpec.DERIVED.length() + 1);
            if (!ChargeVariables.DERIVABLE.contains(variable))
                throw new Rejected(key + ": derived meters count tenants, upgrade_levels, advertising_level, facilities_level or capacity");
            return new MeterSpec(MeterSpec.DERIVED, variable);
        }
        if (value.equals(MeterSpec.DERIVED) || !METER_ID.matcher(value).matches())
            throw new Rejected(key + ": must be flat, derived:<variable> or a registered meter id");
        return new MeterSpec(MeterSpec.EXTERNAL, value);
    }

    private static void keys(String at, Map<String, Object> map, Set<String> allowed) throws Rejected {
        for (String key : new TreeMap<>(map).keySet()) {
            String reason = FORBIDDEN.get(key);
            if (reason != null) throw new Rejected(at + "." + key + ": not supported, " + reason);
            if (at.equals("bills") && EXISTING_CHARGES.contains(key))
                throw new Rejected(at + "." + key + ": today's charges keep their own prices and cannot be configured here");
            if (!allowed.contains(key)) throw new Rejected(at + "." + key + ": unknown key");
        }
    }

    private static boolean flag(String key, Object value) throws Rejected {
        if (value == null) return false;
        if (!(value instanceof Boolean b)) throw new Rejected(key + ": must be true or false");
        return b;
    }

    private static Object required(String key, Object value) throws Rejected {
        if (value == null) throw new Rejected(key + ": required");
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(String key, Object value) throws Rejected {
        if (value instanceof ConfigurationSection s) return plain(s);
        if (value instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (var e : m.entrySet()) {
                if (!(e.getKey() instanceof String k)) throw new Rejected(key + ": keys must be text");
                out.put(k, e.getValue());
            }
            return out;
        }
        throw new Rejected(key + ": must be a section");
    }

    private static String text(String key, Object value) throws Rejected {
        if (!(value instanceof String s)) throw new Rejected(key + ": must be text");
        return s;
    }

    private static List<String> strings(String key, Object value) throws Rejected {
        if (value == null) return List.of();
        if (!(value instanceof List<?> list)) throw new Rejected(key + ": must be a list");
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof String s) || s.isEmpty() || s.length() > 128) throw new Rejected(key + ": every entry must be text");
            out.add(s);
        }
        return out;
    }

    /** A quoted number in the NUMBER grammar. Any other YAML type is refused, with the key named. */
    static Rational number(String key, Object value) throws Rejected {
        if (!(value instanceof String s))
            throw new Rejected(key + ": numbers must be quoted, for example \"50\" (YAML read this as "
                    + (value == null ? "nothing" : value.getClass().getSimpleName()) + ")");
        try {
            return Rational.parse(s);
        } catch (FormulaException bad) {
            throw new Rejected(key + ": '" + s + "' is not a number (digits, optionally a dot and up to 6 more digits)");
        }
    }

    static long whole(String key, Object value, long min, long max) throws Rejected {
        Rational r = number(key, value);
        if (!r.isInteger()) throw new Rejected(key + ": must be a whole number");
        long v = r.longValueExact();
        if (v < min || v > max) throw new Rejected(key + ": must be from " + min + " to " + max);
        return v;
    }
}

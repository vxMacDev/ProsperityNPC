package com.prosperitysmp.npc.economy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks, for the offline recovery tools, that an operator's refund is recorded consistently and that it really
 * happened. The journal separately proves that the refund moved back exactly what the debit took; here the
 * refund-observed.json beside the evidence must name the payer, the held payment it refunds and the same refund bits
 * as the evidence, and the payer's balance saved by EssentialsX on the stopped server must be the refund's after
 * value. So the refund has to be the payer's last balance change before the tool runs: staff make it with the server
 * stopped.
 *
 * refund-observed.json is one flat JSON object:
 * {"account":"&lt;payer uuid&gt;","payment":"&lt;payment id&gt;","before_bits":&lt;long&gt;,"after_bits":&lt;long&gt;}.
 * The payment id is the transfer attempt id or the commerce order id, written as the tools print it. The bits are
 * Double.doubleToRawLongBits of the balance Vault reports, as in the evidence properties.
 */
public final class RefundEvidence {
    private static final int MAX_USERDATA = 1024 * 1024;
    private static final Pattern FIELD = Pattern.compile("\"([a-z_]{1,32})\"\\s*:\\s*(\"[^\"\\\\]*\"|-?[0-9]{1,19})");
    private static final Pattern MONEY = Pattern.compile("(?m)^money:[ \\t]*(?:'([^'\\r\\n]*)'|\"([^\"\\r\\n]*)\"|([^ \\t\\r\\n#]+))[ \\t]*\\r?$");

    private RefundEvidence() { }

    /**
     * The refund evidence for one held payment. payer is the account the debit took money from, and payment is the
     * held payment's own id (its transfer attempt or commerce order). reservePays says the payer is the reserve (rent
     * and appreciation), otherwise it is the player (purchases, utility, commerce). Throws IllegalArgumentException
     * naming the first requirement that fails. A payment not yet resolved also needs {@link #requireSaved}; an exact
     * replay of an applied resolution does not, since it writes nothing new.
     */
    public static void check(Properties evidence, UUID payer, UUID payment, boolean reservePays, byte[] observedJson) {
        if (!"true".equals(evidence.getProperty("refund_completed")))
            throw new IllegalArgumentException("Completed refund required");
        long before = bits(evidence, "refund_before_bits"), after = bits(evidence, "refund_after_bits");
        Map<String, String> observed = fields(observedJson);
        if (!payer.toString().equals(unquoted(observed, "account")))
            throw new IllegalArgumentException("refund-observed.json names another account");
        // A refund made for one payment must not be filed against another of the same payer and amount.
        if (!observed.containsKey("payment"))
            throw new IllegalArgumentException("refund-observed.json does not name the payment it refunds. Files from before"
                    + " dev.63 are not accepted: add \"payment\" with the held transfer attempt id or commerce order id");
        if (!payment.toString().equals(unquoted(observed, "payment")))
            throw new IllegalArgumentException("refund-observed.json names another payment");
        if (!Long.toString(before).equals(observed.get("before_bits")) || !Long.toString(after).equals(observed.get("after_bits")))
            throw new IllegalArgumentException("refund-observed.json records a different refund");
        if (reservePays) {
            // The reserve paid, so the reserve is the account the refund restores, and the owner was never credited.
            if (!reportedAs(bitsOf(required(evidence, "reserve_before")), before)
                    || !reportedAs(bitsOf(required(evidence, "reserve_after")), after))
                throw new IllegalArgumentException("Reserve balances must be the refund's own before and after");
            if (!required(evidence, "owner_before").equals(required(evidence, "owner_after")))
                throw new IllegalArgumentException("The owner's balance must be unchanged by the refund");
        } else if (!required(evidence, "reserve_before").equals(required(evidence, "reserve_after"))) {
            // The player paid and the reserve was never credited, so the refund must not come out of the reserve.
            throw new IllegalArgumentException("Completed correction with unchanged reserve required");
        }
    }

    /** savedBits is the payer's balance as the stopped server saved it: the refund must be its last change. */
    public static void requireSaved(Properties evidence, long savedBits) {
        if (!reportedAs(savedBits, bits(evidence, "refund_after_bits")))
            throw new IllegalArgumentException("The payer's saved balance is not the refund's after value");
    }

    /**
     * True when observedBits is what Vault can report for a balance whose nearest double is nearestBits. EssentialsX (seen
     * in 2.20.1 to 2.22.0) takes that double one step toward negative infinity when it is above the saved decimal, so
     * a reported balance never exceeds the saved one. Some hooks report the nearest double.
     * Both are accepted: the two differ by one step of the double, far below a cent.
     */
    static boolean reportedAs(long nearestBits, long observedBits) {
        return observedBits == nearestBits
                || observedBits == Double.doubleToRawLongBits(Math.nextDown(Double.longBitsToDouble(nearestBits)));
    }

    /** The nearest double to a decimal balance, as raw bits. See {@link #reportedAs} for what Vault may report instead. */
    public static long bitsOf(String decimal) {
        try { return Double.doubleToRawLongBits(new BigDecimal(decimal.trim()).doubleValue()); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid balance: not a decimal"); }
    }

    /**
     * The balance EssentialsX saved for one account on the stopped server, as Vault would report it. The file is
     * plugins/Essentials/userdata/&lt;uuid&gt;.yml under the server root, and it must hold exactly one money line.
     */
    public static long savedBalanceBits(Path serverRoot, UUID account) throws Exception {
        Path file = serverRoot.resolve("plugins/Essentials/userdata").resolve(account + ".yml");
        if (!file.equals(file.toRealPath()) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.size(file) < 1 || Files.size(file) > MAX_USERDATA)
            throw new IllegalArgumentException("The payer's EssentialsX userdata file is missing or unsafe");
        byte[] bytes;
        try (var stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { bytes = stream.readNBytes(MAX_USERDATA + 1); }
        if (bytes.length > MAX_USERDATA) throw new IllegalArgumentException("The payer's userdata file changed size");
        return savedBalanceBits(bytes);
    }

    static long savedBalanceBits(byte[] userdata) {
        Matcher m = MONEY.matcher(new String(userdata, StandardCharsets.UTF_8));
        String value = null;
        int lines = 0;
        while (m.find()) {
            lines++;
            value = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
        }
        if (lines != 1) throw new IllegalArgumentException("The payer's userdata must hold exactly one money line");
        return bitsOf(value);
    }

    /** A flat JSON object of string and whole-number fields; anything else, or a repeated field, is refused. */
    static Map<String, String> fields(byte[] json) {
        String text = new String(json, StandardCharsets.UTF_8).strip();
        if (!text.startsWith("{") || !text.endsWith("}")) throw new IllegalArgumentException("refund-observed.json must be one JSON object");
        String body = text.substring(1, text.length() - 1).strip();
        Map<String, String> result = new HashMap<>();
        Matcher m = FIELD.matcher(body);
        int at = 0;
        while (at < body.length()) {
            if (!m.find(at) || m.start() != at) throw new IllegalArgumentException("refund-observed.json is not a flat object");
            if (result.put(m.group(1), m.group(2)) != null) throw new IllegalArgumentException("refund-observed.json repeats a field");
            at = m.end();
            while (at < body.length() && Character.isWhitespace(body.charAt(at))) at++;
            if (at < body.length()) {
                if (body.charAt(at) != ',') throw new IllegalArgumentException("refund-observed.json is not a flat object");
                at++;
                while (at < body.length() && Character.isWhitespace(body.charAt(at))) at++;
                if (at == body.length()) throw new IllegalArgumentException("refund-observed.json is not a flat object");
            }
        }
        return result;
    }

    private static String unquoted(Map<String, String> fields, String key) {
        String value = fields.get(key);
        return value == null || value.length() < 2 || !value.startsWith("\"") ? null : value.substring(1, value.length() - 1);
    }

    private static long bits(Properties p, String key) {
        try { return Long.parseLong(required(p, key)); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid evidence field: " + key); }
    }

    private static String required(Properties p, String key) {
        String value = p.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing evidence field: " + key);
        return value;
    }
}

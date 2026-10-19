package com.prosperitysmp.npc.charge;

import com.google.gson.Gson;
import com.google.gson.Strictness;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Stores the terms needed to explain a frozen invoice after the config changes. */
final class ChargePolicyCodec {
    static final int MAX_BYTES = 1_048_576;
    private static final Gson JSON = new Gson();

    private ChargePolicyCodec() { }

    static String encode(ChargeCatalogue.Policy policy) {
        var terms = new LinkedHashMap<String, Object>();
        terms.put("version", Integer.toString(policy.version()));
        terms.put("title", policy.title());
        terms.put("applies-to", policy.appliesTo().name().toLowerCase(Locale.ROOT));
        terms.put("classes", policy.classes());
        terms.put("markets", policy.markets());
        terms.put("properties", policy.properties().stream().map(Object::toString).toList());
        terms.put("meter", policy.meter().id());
        terms.put("amount", policy.formula().toString());
        var constants = new TreeMap<String, String>();
        policy.vars().forEach((name, value) -> constants.put(name, new BigDecimal(value.numerator())
                .divide(new BigDecimal(value.denominator())).stripTrailingZeros().toPlainString()));
        terms.put("vars", constants);
        terms.put("rounding", policy.rounding().name().toLowerCase(Locale.ROOT).replace('_', '-'));
        terms.put("min-cents", Long.toString(policy.minCents()));
        terms.put("max-cents", Long.toString(policy.maxCents()));
        String json = JSON.toJson(terms);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("Stored bill policy exceeds one MiB");
        return json;
    }

    static ChargeCatalogue.Policy decode(String id, int version, String hash, String json) throws ChargeCatalogue.Rejected {
        if (json == null) throw new ChargeCatalogue.Rejected("Stored terms are missing for " + id + "@" + version);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new ChargeCatalogue.Rejected("Stored terms are too large for " + id + "@" + version);
        try (var reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            reader.setNestingLimit(8);
            Map<String, Object> terms = JSON.fromJson(reader, new TypeToken<Map<String, Object>>() { }.getType());
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing document");
            var catalogue = ChargeCatalogue.load(Map.of("policies", Map.of(id, terms)), Map.of(id + "@" + version, hash));
            var policy = catalogue.policy(id).orElseThrow();
            if (policy.version() != version || !policy.sha256().equals(hash)) throw new IOException("Definition binding");
            return policy;
        } catch (ChargeCatalogue.Rejected | IOException | RuntimeException invalid) {
            throw new ChargeCatalogue.Rejected("Stored terms are invalid for " + id + "@" + version);
        }
    }
}

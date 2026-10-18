package com.prosperitysmp.npc.api;

import java.util.Collections;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * What a piece says it can do, as named flags and values. Unknown names read as false or "".
 * A final class with a builder, never a record, so it can grow without breaking adapters already compiled.
 */
public final class Capabilities {
    private final SortedMap<String, String> values;

    private Capabilities(SortedMap<String, String> values) {
        this.values = Collections.unmodifiableSortedMap(new TreeMap<>(values));
    }

    public static Builder builder() { return new Builder(); }

    /** True only when the name was set to "true". Unknown name: false. */
    public boolean flag(String name) { return "true".equals(values.get(name)); }

    /** The value set for the name. Unknown name: "". */
    public String value(String name) { return values.getOrDefault(name, ""); }

    public SortedMap<String, String> all() { return values; }

    @Override public String toString() { return values.toString(); }

    public static final class Builder {
        private final SortedMap<String, String> values = new TreeMap<>();

        private Builder() { }

        public Builder flag(String name, boolean on) { return value(name, Boolean.toString(on)); }

        public Builder value(String name, String value) {
            Objects.requireNonNull(name);
            Objects.requireNonNull(value);
            if (name.isBlank()) throw new IllegalArgumentException("Capability name is blank");
            values.put(name, value);
            return this;
        }

        public Capabilities build() { return new Capabilities(values); }
    }
}

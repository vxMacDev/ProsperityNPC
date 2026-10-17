package com.prosperitysmp.npc.db;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * For integration tests that build a partial schema from a few migrations: applies the V40 money-evidence
 * statements whose tables that schema has, so the transfer and commerce readers find their refusal columns. The
 * refund claims table is new in V40, so it is always created; its backfill runs only when its source tables exist.
 * Tests that apply every registered migration get V40 the normal way and do not need this.
 */
public final class V40Fixture {
    public static final String RESOURCE = "/db/migration/V40__money_evidence.sql";
    private static final Pattern TARGET = Pattern.compile("^(ALTER TABLE|UPDATE|CREATE TABLE IF NOT EXISTS|INSERT INTO)\\s+([a-z_]+)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SOURCE = Pattern.compile("\\b(?:FROM|JOIN)\\s+([a-z_]+)", Pattern.CASE_INSENSITIVE);

    private V40Fixture() { }

    /** Applies V40 to the tables present on this connection's schema. Returns how many statements ran. */
    public static int apply(Connection c) throws SQLException, IOException {
        Set<String> tables = new HashSet<>();
        try (Statement st = c.createStatement(); var rs = st.executeQuery(
                "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE()")) {
            while (rs.next()) tables.add(rs.getString(1));
        }
        int applied = 0;
        for (String sql : statements()) {
            Matcher m = TARGET.matcher(sql.strip());
            if (!m.find()) throw new IllegalStateException("Unexpected V40 statement: " + sql);
            boolean create = m.group(1).toUpperCase(Locale.ROOT).startsWith("CREATE");
            Set<String> needed = new HashSet<>();
            if (!create) needed.add(m.group(2));
            for (Matcher source = SOURCE.matcher(sql); source.find(); ) needed.add(source.group(1));
            if (!tables.containsAll(needed)) continue;
            try (Statement st = c.createStatement()) { st.execute(sql); }
            if (create) tables.add(m.group(2));
            applied++;
        }
        return applied;
    }

    public static List<String> statements() throws IOException {
        try (InputStream in = V40Fixture.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IOException("missing " + RESOURCE);
            return new ArrayList<>(MigrationRunner.splitStatements(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        }
    }
}

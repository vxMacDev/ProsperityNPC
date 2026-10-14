package com.prosperitysmp.npc.db;

import com.prosperitysmp.npc.ProsperityNpcPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * A tiny forward-only migrator. Each migration is an additive SQL resource
 * applied at most once and recorded in {@code npc_schema_migrations}. DDL in
 * MariaDB auto-commits, so migrations use idempotent creates or repeatable
 * widening alters; a partially applied migration can be retried.
 */
public final class MigrationRunner {

    private record Migration(int version, String description, String resource) {
    }

    // Ordered list of migrations. Add new entries; never edit an applied one.
    private static final List<Migration> MIGRATIONS = List.of(
            new Migration(1, "initial_schema", "db/migration/V1__initial_schema.sql"),
            new Migration(2, "resident_identity", "db/migration/V2__resident_identity.sql"),
            new Migration(3, "purchase_receipts", "db/migration/V3__purchase_receipts.sql"),
            new Migration(4, "transfer_journal", "db/migration/V4__transfer_journal.sql"),
            new Migration(5, "resident_route_tags", "db/migration/V5__resident_route_tags.sql"),
            new Migration(6, "incident_repairs", "db/migration/V6__incident_repairs.sql"),
            new Migration(7, "incident_rolls", "db/migration/V7__incident_rolls.sql"),
            new Migration(8, "eviction_tenancies", "db/migration/V8__eviction_tenancies.sql"),
            new Migration(9, "housing_registry", "db/migration/V9__housing_registry.sql"),
            new Migration(10, "housing_moves", "db/migration/V10__housing_moves.sql"),
            new Migration(11, "housing_history_order", "db/migration/V11__housing_history_order.sql"),
            new Migration(12, "incident_service_receipts", "db/migration/V12__incident_service_receipts.sql"),
            new Migration(13, "utility_obligations", "db/migration/V13__utility_obligations.sql"),
            new Migration(14, "utility_service_cohort", "db/migration/V14__utility_service_cohort.sql"),
            new Migration(15, "tenancy_departures", "db/migration/V15__tenancy_departures.sql"),
            new Migration(16, "rent_reservations", "db/migration/V16__rent_reservations.sql"),
            new Migration(17, "transfer_resolutions", "db/migration/V17__transfer_resolutions.sql"),
            new Migration(18, "civic_placements", "db/migration/V18__civic_placements.sql"),
            new Migration(19, "town_tasks", "db/migration/V19__town_tasks.sql"),
            new Migration(20, "training_grants", "db/migration/V20__training_grants.sql"),
            new Migration(21, "item_custody", "db/migration/V21__item_custody.sql"),
            new Migration(22, "commerce_orders", "db/migration/V22__commerce_orders.sql"),
            new Migration(23, "property_insurance", "db/migration/V23__property_insurance.sql"),
            new Migration(24, "auto_care", "db/migration/V24__auto_care.sql"),
            new Migration(25, "commerce_resolution", "db/migration/V25__commerce_resolution.sql"),
            new Migration(26, "chronicle_outbox", "db/migration/V26__chronicle_outbox.sql"),
            new Migration(27, "premium_admission_evidence", "db/migration/V27__premium_admission_evidence.sql"),
            new Migration(28, "tenant_moods", "db/migration/V28__tenant_moods.sql"),
            new Migration(29, "voluntary_departures", "db/migration/V29__voluntary_departures.sql"),
            new Migration(30, "appreciation_reservations", "db/migration/V30__appreciation_reservations.sql"),
            new Migration(31, "rent_change_delay", "db/migration/V31__rent_change_delay.sql"),
            new Migration(32, "owner_away_decisions", "db/migration/V32__owner_away_decisions.sql"),
            new Migration(33, "payout_retirements", "db/migration/V33__payout_retirements.sql"),
            new Migration(34, "linked_accounts", "db/migration/V34__linked_accounts.sql"),
            new Migration(35, "item_repairs", "db/migration/V35__item_repairs.sql"),
            new Migration(36, "town_contracts_partnerships", "db/migration/V36__town_contracts_partnerships.sql"),
            new Migration(37, "partnership_handin_terms", "db/migration/V37__partnership_handin_terms.sql"),
            new Migration(38, "item_return_staff_decision", "db/migration/V38__item_return_staff_decision.sql"));

    private final ProsperityNpcPlugin plugin;
    private final Database database;

    public MigrationRunner(ProsperityNpcPlugin plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
    }

    /** Applies any pending migrations; returns the highest applied version. */
    public int run() throws SQLException {
        try (Connection c = database.getConnection()) {
            ensureMigrationsTable(c);
            int highest = 0;
            for (Migration m : MIGRATIONS) {
                if (isApplied(c, m.version())) {
                    highest = Math.max(highest, m.version());
                    continue;
                }
                String sql = readResource(m.resource());
                int statements = applyMigration(c, m, sql);
                plugin.getLogger().info("Applied migration V" + m.version()
                        + " (" + m.description() + "): " + statements + " statements.");
                highest = Math.max(highest, m.version());
            }
            return highest;
        }
    }

    private void ensureMigrationsTable(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS npc_schema_migrations (
                      version     INT          NOT NULL,
                      description VARCHAR(128) NOT NULL,
                      applied_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                      PRIMARY KEY (version)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """);
        }
    }

    private boolean isApplied(Connection c, int version) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM npc_schema_migrations WHERE version = ?")) {
            ps.setInt(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private int applyMigration(Connection c, Migration m, String sql) throws SQLException {
        List<String> statements = splitStatements(sql);
        for (String stmt : statements) {
            try (Statement st = c.createStatement()) {
                st.execute(stmt);
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO npc_schema_migrations (version, description) VALUES (?, ?)")) {
            ps.setInt(1, m.version());
            ps.setString(2, m.description());
            ps.executeUpdate();
        }
        return statements.size();
    }

    /** Splits a plain-DDL script on statement-terminating semicolons, stripping line comments. */
    public static List<String> splitStatements(String sql) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String rawLine : sql.split("\n")) {
            String line = rawLine;
            int comment = line.indexOf("--");
            if (comment >= 0) {
                line = line.substring(0, comment);
            }
            cur.append(line).append('\n');
            if (line.stripTrailing().endsWith(";")) {
                String stmt = cur.toString().trim();
                stmt = stmt.substring(0, stmt.length() - 1).trim();
                if (!stmt.isEmpty()) {
                    out.add(stmt);
                }
                cur.setLength(0);
            }
        }
        String tail = cur.toString().trim();
        if (!tail.isEmpty()) {
            out.add(tail);
        }
        return out;
    }

    private String readResource(String path) throws SQLException {
        try (InputStream in = plugin.getResource(path)) {
            if (in == null) {
                throw new SQLException("migration resource not found: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SQLException("failed reading migration " + path, e);
        }
    }
}

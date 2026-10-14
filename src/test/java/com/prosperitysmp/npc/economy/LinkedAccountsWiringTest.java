package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.ProsperityNpcPlugin;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** linked-accounts config, copy, plugin wiring and the money guards that keep the cap in one place. */
class LinkedAccountsWiringTest {
    private static final Path MAIN = Path.of("src/main/java/com/prosperitysmp/npc");

    @Test void shipsOffAndClampsTheLookupLimit() throws Exception {
        String yaml;
        try (var input = getClass().getResourceAsStream("/config.yml")) { yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8); }
        var config = new YamlConfiguration();
        config.loadFromString(yaml);
        assertEquals(Boolean.FALSE, config.get(LinkedAccounts.ENABLED));
        assertEquals(Boolean.FALSE, config.get(LinkedAccounts.LOOKUP));
        assertEquals(50, config.get(LinkedAccounts.LOOKUP_LIMIT));
        assertNull(config.get("linked-accounts.shared-presence"), "shared presence is deferred, so it has no key");
        assertEquals(LinkedAccounts.Settings.OFF, LinkedAccounts.Settings.from(config));
        assertTrue(yaml.contains("With linked-accounts on, linked Java and Bedrock accounts share this."));
        var custom = new YamlConfiguration();
        custom.set(LinkedAccounts.ENABLED, true);
        custom.set(LinkedAccounts.LOOKUP, true);
        custom.set(LinkedAccounts.LOOKUP_LIMIT, 9999);
        assertEquals(new LinkedAccounts.Settings(true, true, 500), LinkedAccounts.Settings.from(custom));
        custom.set(LinkedAccounts.LOOKUP_LIMIT, -4);
        assertEquals(0, LinkedAccounts.Settings.from(custom).lookupDailyLimit());
        custom.set(LinkedAccounts.ENABLED, false);
        assertFalse(LinkedAccounts.Settings.from(custom).lookupOwners(), "the lookup needs the master switch");
    }

    @Test void everyNewLineIsPlainAndUsesTheConfiguredCap() {
        var lines = new ArrayList<String>(List.of(LinkedAccounts.linkedNotice(4500), LinkedAccounts.unlinkedNotice(),
                LinkedAccounts.deskLine(4500), LinkedAccounts.noLinks("Ann"), LinkedAccounts.splitSaved(LocalDate.of(2026, 10, 14)),
                LinkedAccounts.splitAlready(LocalDate.of(2026, 10, 14)), LinkedAccounts.SPLIT_REFUSED, LinkedAccounts.STAFF_OFF,
                LinkedAccounts.NO_FLOODGATE));
        for (String line : lines) {
            assertTrue(line.chars().allMatch(ch -> ch >= 0x20 && ch < 0x7f), "plain ASCII, so no em dash or emoji: " + line);
            assertFalse(line.contains("Not ") && line.contains(", not "), line);
            assertFalse(line.contains("!"), line);
        }
        assertEquals("Your Java and Bedrock accounts are linked here. Homes on both now share one daily rent limit of $45.",
                LinkedAccounts.linkedNotice(4500));
        assertEquals("Shares a $45 daily rent limit with your linked account.", LinkedAccounts.deskLine(4500));
        // The figure follows economy.benefit-unit-daily-cap-cents, never a fixed $45.
        assertTrue(LinkedAccounts.linkedNotice(9000).endsWith("of $90."));
        assertTrue(LinkedAccounts.deskLine(4550).startsWith("Shares a $45.50 daily"));
        assertEquals("Split saved. It starts on 2026-10-14. Today they still share one limit.", LinkedAccounts.splitSaved(LocalDate.of(2026, 10, 14)));
    }

    @Test void floodgateMissingOrBrokenNeverCrashesAndBuildsNothing() {
        AtomicInteger built = new AtomicInteger();
        var warnings = new ArrayList<String>();
        assertNull(ProsperityNpcPlugin.optionalFloodgate(true, false, () -> built.incrementAndGet(), warnings::add, "x"));
        assertNull(ProsperityNpcPlugin.optionalFloodgate(false, true, () -> built.incrementAndGet(), warnings::add, "x"));
        assertEquals(0, built.get(), "without Floodgate or with the flag off nothing is built");
        assertNull(ProsperityNpcPlugin.optionalFloodgate(true, true, () -> { throw new NoClassDefFoundError("org/geysermc/floodgate/api/FloodgateApi"); },
                warnings::add, "Linked account discovery"));
        assertNull(ProsperityNpcPlugin.optionalFloodgate(true, true, () -> { throw new IllegalStateException("not ready"); },
                warnings::add, "Linked account lookup"));
        assertEquals(List.of("Linked account discovery unavailable: NoClassDefFoundError", "Linked account lookup unavailable: IllegalStateException"), warnings);
        assertEquals(Integer.valueOf(1), ProsperityNpcPlugin.optionalFloodgate(true, true, () -> 1, warnings::add, "x"), "a working supplier is built");
    }

    @Test void thePluginWiresSavedLinksWithoutFloodgateAndNeverNamesAFloodgateType() throws Exception {
        String plugin = Files.readString(MAIN.resolve("ProsperityNpcPlugin.java"));
        assertFalse(plugin.contains("org.geysermc"), "Floodgate types stay in the Floodgate-only classes");
        int init = plugin.indexOf("private void initAccountLinks()");
        int initEnd = plugin.indexOf("private void initLinkDiscovery()");
        assertTrue(init > 0 && initEnd > init);
        String repository = plugin.substring(init, initEnd);
        assertTrue(repository.contains("accountLinks = new com.prosperitysmp.npc.economy.AccountLinkRepository(database);"));
        assertFalse(repository.contains("floodgate"), "saved links apply whether or not Floodgate is installed");
        // The repository exists before the rent runtime is built.
        assertTrue(plugin.indexOf("initAccountLinks();") < plugin.indexOf("initEconomy();"));
        int discovery = plugin.indexOf("private void initLinkDiscovery()");
        String register = plugin.substring(discovery, plugin.indexOf("public static <T> T optionalFloodgate"));
        assertTrue(register.indexOf("if (!linkedSettings.enabled()") < register.indexOf("registerEvents"));
        assertTrue(register.indexOf("isPluginEnabled(\"floodgate\")") < register.indexOf("registerEvents"));
        assertEquals(1, plugin.split("registerEvents\\(linkListener", -1).length - 1);
        String runtime = Files.readString(MAIN.resolve("economy/UtilityPaymentRuntime.java"));
        assertTrue(runtime.contains("plugin.accountLinks(), plugin::linkedAccountsEnabled);"));
        String admin = Files.readString(MAIN.resolve("command/NpcAdminCommand.java"));
        int split = admin.indexOf("if (sub.equals(\"split\")");
        assertTrue(split > 0 && admin.indexOf("if (!plugin.linkedAccountsEnabled())", split) > split
                && admin.indexOf("if (!plugin.linkedAccountsEnabled())", split) < admin.indexOf("links.staffSplit(", split),
                "split checks the flag before any SQL");
        assertFalse(admin.contains("staffAdd"), "there is no staff add");
        // The split's day is read inside the save itself (AccountLinkRepositoryIT), so the command passes the clock, never a day.
        String splitBlock = admin.substring(split, admin.indexOf("send(sender, NamedTextColor.GRAY, \"Usage: /npcadmin links", split));
        assertFalse(splitBlock.contains("economyToday()"), "no day captured on the main thread for the split");
        assertFalse(splitBlock.contains(".today()"), "no day captured before the save");
        int worker = splitBlock.indexOf("plugin.submitLinkWork(");
        assertTrue(worker > 0 && worker < splitBlock.indexOf("links.staffSplit(a, b, actor, reason, economyClock);"),
                "the split runs inside the worker task with the clock");
        // Health shows the linked-accounts line only while it is on, so flag-off health output has no extra line.
        assertEquals(1, count(admin, "\"Linked accounts: \" + plugin.linkedAccountsStatus()"));
        assertTrue(admin.contains("if (plugin.linkedAccountsEnabled()) send(sender, NamedTextColor.GRAY, \"Linked accounts: \" + plugin.linkedAccountsStatus());"));
    }

    @Test void theCapIsOnlyComputedThroughTheGroupedAllocator() throws Exception {
        List<Path> sources;
        try (Stream<Path> walk = Files.walk(MAIN)) { sources = walk.filter(p -> p.toString().endsWith(".java")).toList(); }
        assertTrue(sources.size() > 100, "the walk found the sources");
        int helperUses = 0, gateUses = 0;
        for (Path source : sources) {
            String code = Files.readString(source);
            assertFalse(code.contains("npc_benefit_unit"), "the unused V1 benefit-unit tables stay unused: " + source);
            if (!source.endsWith("EconomyEventRepository.java")) helperUses += count(code, "sumPaidToPublicForBenefitUnit(");
            if (!source.endsWith("PayoutGate.java")) gateUses += count(code, "PayoutGate");
        }
        // Raw-owner headroom helpers would skip the grouping. They have no main caller; keep it that way.
        assertEquals(0, helperUses);
        assertEquals(0, gateUses);
        String rent = Files.readString(MAIN.resolve("economy/RentReservationRepository.java"));
        assertEquals(2, count(rent, "var units=units(c,day);"), "rent and appreciation both read groups");
        assertEquals(2, count(rent, "spent,limits,units);"), "both allocate calls pass the same groups");
        assertEquals(1, count(rent, "ownerSpent.merge(units.unitOf(owner),"));
        // Groups and the snapshot behind spent() come after the rent mutex in the same transaction (RentLinkedCapIT
        // has the concurrency cases). Between the mutex and each units read there is no transaction start.
        String mutex = "SELECT singleton FROM npc_rent_mutex WHERE singleton=1 FOR UPDATE";
        for (int at = rent.indexOf("var units=units(c,day);"); at >= 0; at = rent.indexOf("var units=units(c,day);", at + 1)) {
            int lock = rent.lastIndexOf(mutex, at);
            assertTrue(lock > 0, "a units read with no mutex before it");
            assertFalse(rent.substring(lock, at).contains("setAutoCommit(false)"), "units read before its own transaction's mutex");
        }
        assertEquals(2, count(rent, "p.ownerUuid(),p.ownershipEpoch(),p.ownerUuid(),day.toString()"), "benefit_unit_id stays the owner");
    }

    private static int count(String text, String needle) { return text.split(java.util.regex.Pattern.quote(needle), -1).length - 1; }
}

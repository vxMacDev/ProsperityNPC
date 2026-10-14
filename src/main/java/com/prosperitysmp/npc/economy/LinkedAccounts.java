package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.util.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.configuration.ConfigurationSection;

/** Settings and every player and staff line for linked accounts (linked-accounts.*). No SQL, no Bukkit calls. */
public final class LinkedAccounts {
    public static final String ENABLED = "linked-accounts.enabled";
    public static final String LOOKUP = "linked-accounts.lookup-floodgate-owners";
    public static final String LOOKUP_LIMIT = "linked-accounts.lookup-daily-limit";
    public static final int DEFAULT_LOOKUP_LIMIT = 50, MAX_LOOKUP_LIMIT = 500;

    private LinkedAccounts() { }

    /** lookupOwners is only true while enabled is. lookupDailyLimit is clamped to 0..500. */
    public record Settings(boolean enabled, boolean lookupOwners, int lookupDailyLimit) {
        public static final Settings OFF = new Settings(false, false, DEFAULT_LOOKUP_LIMIT);
        public Settings {
            lookupOwners = enabled && lookupOwners;
            lookupDailyLimit = Math.max(0, Math.min(MAX_LOOKUP_LIMIT, lookupDailyLimit));
        }
        public static Settings from(ConfigurationSection config) {
            Objects.requireNonNull(config);
            return new Settings(config.getBoolean(ENABLED, false), config.getBoolean(LOOKUP, false),
                    config.getInt(LOOKUP_LIMIT, DEFAULT_LOOKUP_LIMIT));
        }
    }

    /** "$45" for whole dollars, "$45.50" otherwise, from benefit-unit-daily-cap-cents. */
    public static String dollars(long cents) {
        return cents % 100 == 0 ? "$" + (cents / 100) : Money.format(cents);
    }

    public static String linkedNotice(long capCents) {
        return "Your Java and Bedrock accounts are linked here. Homes on both now share one daily rent limit of "
                + dollars(capCents) + ".";
    }

    public static String unlinkedNotice() {
        return "Your accounts were unlinked in Floodgate. Here they still share one daily rent limit. Ask staff if that is wrong.";
    }

    public static String deskLine(long capCents) {
        return "Shares a " + dollars(capCents) + " daily rent limit with your linked account.";
    }

    public static String noLinks(String name) { return "No saved links for " + name + "."; }
    public static String splitSaved(LocalDate day) { return "Split saved. It starts on " + day + ". Today they still share one limit."; }
    public static String splitAlready(LocalDate day) { return "That split is already saved. It starts on " + day + "."; }
    public static final String SPLIT_REFUSED = "Those accounts are not linked.";
    public static final String STAFF_OFF = "Linked accounts are off. Turn on linked-accounts.enabled first.";
    public static final String NO_FLOODGATE = "Linked accounts: Floodgate not found, using saved links only.";

    /** Floodgate gives Bedrock accounts UUIDs whose first half is zero. */
    public static String platform(UUID account) {
        return AccountLinkRepository.isFloodgateId(account) ? "Bedrock" : "Java";
    }

    /**
     * Staff show lines: the active group first ("Linked group: Ann (Java), Ann_BE (Bedrock). Since <day>.
     * Source: Floodgate."), then one line per saved split. names turns a UUID into a player name or the UUID.
     */
    public static List<String> showLines(AccountLinkRepository.Group group, String asked, Function<UUID, String> names) {
        Objects.requireNonNull(group);
        Objects.requireNonNull(names);
        var lines = new ArrayList<String>();
        if (group.links().isEmpty()) {
            lines.add(noLinks(asked));
            return lines;
        }
        if (group.members().size() > 1) {
            var parts = new ArrayList<String>();
            for (UUID member : group.members()) parts.add(names.apply(member) + " (" + platform(member) + ")");
            LocalDate since = null;
            boolean floodgate = false, staff = false;
            for (var link : group.links()) {
                if (!group.members().contains(link.low()) || !group.members().contains(link.high())) continue;
                if (since == null || link.firstSeenDay().isBefore(since)) since = link.firstSeenDay();
                if (link.source() == AccountLinkRepository.Source.STAFF) staff = true;
                else floodgate = true;
            }
            lines.add("Linked group: " + String.join(", ", parts) + "." + (since == null ? "" : " Since " + since + ".")
                    + " Source: " + (floodgate && staff ? "Floodgate and staff" : staff ? "staff" : "Floodgate") + ".");
        } else {
            lines.add(noLinks(asked) + " Saved splits are below.");
        }
        for (var link : group.links()) {
            if (link.splitEffectiveDay() == null) continue;
            lines.add("Split: " + names.apply(link.low()) + " and " + names.apply(link.high()) + ". "
                    + "Separate from " + link.splitEffectiveDay() + ".");
        }
        return lines;
    }
}

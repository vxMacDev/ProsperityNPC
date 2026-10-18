package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.economy.AccountLinkRepository.Group;
import com.prosperitysmp.npc.economy.AccountLinkRepository.Link;
import com.prosperitysmp.npc.economy.AccountLinkRepository.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Settings clamps, the dollar text and every branch of the staff show lines. */
class LinkedAccountsTest {
    private static final UUID JAVA = new UUID(0x1000, 1);
    private static final UUID BEDROCK = new UUID(0, 0x2000);
    private static final UUID JAVA_TWO = new UUID(0x3000, 3);
    private static final UUID STRANGER = new UUID(0x4000, 4);
    private static final LocalDate FIRST = LocalDate.of(2026, 10, 1);
    private static final LocalDate LATER = LocalDate.of(2026, 10, 9);
    private static final Map<UUID, String> NAMES = Map.of(JAVA, "Ann", BEDROCK, "AnnBE", JAVA_TWO, "Bo", STRANGER, "Cy");

    private static String name(UUID id) { return NAMES.getOrDefault(id, id.toString()); }

    private static Link link(UUID a, UUID b, Source source, LocalDate seen, LocalDate split) {
        UUID low = OwnerUnits.BYTE_ORDER.compare(a, b) < 0 ? a : b;
        UUID high = low.equals(a) ? b : a;
        return new Link(low, high, AccountLinkRepository.isFloodgateId(a) ? b : a,
                AccountLinkRepository.isFloodgateId(a) ? a : b, source, seen, split);
    }

    @ParameterizedTest
    @CsvSource({"0,$0", "100,$1", "4500,$45", "4550,$45.50", "4501,$45.01", "1,$0.01", "99,$0.99", "100000,$1000"})
    void dollarsDropsTheCentsOnlyForWholeDollars(long cents, String expected) {
        assertEquals(expected, LinkedAccounts.dollars(cents));
    }

    @Test
    void platformFollowsFloodgateIds() {
        assertEquals("Bedrock", LinkedAccounts.platform(BEDROCK));
        assertEquals("Bedrock", LinkedAccounts.platform(new UUID(0, 0)));
        assertEquals("Java", LinkedAccounts.platform(JAVA));
        assertEquals("Java", LinkedAccounts.platform(new UUID(1, 0)));
        assertEquals("Java", LinkedAccounts.platform(new UUID(Long.MIN_VALUE, 0)));
    }

    @ParameterizedTest
    @CsvSource({"true,true,50,true,true,50", "true,false,50,true,false,50", "false,true,50,false,false,50",
            "true,true,500,true,true,500", "true,true,501,true,true,500", "true,true,-1,true,true,0",
            "true,true,0,true,true,0", "true,true,2147483647,true,true,500", "true,true,-2147483648,true,true,0"})
    void settingsNeedTheMasterSwitchForTheLookupAndClampTheDailyLimit(boolean enabled, boolean lookup, int limit,
                                                                      boolean wantEnabled, boolean wantLookup, int wantLimit) {
        var settings = new LinkedAccounts.Settings(enabled, lookup, limit);
        assertEquals(wantEnabled, settings.enabled());
        assertEquals(wantLookup, settings.lookupOwners());
        assertEquals(wantLimit, settings.lookupDailyLimit());
    }

    @Test
    void offSettingsAreTheDocumentedDefaults() {
        assertEquals(new LinkedAccounts.Settings(false, false, 50), LinkedAccounts.Settings.OFF);
        assertEquals(50, LinkedAccounts.DEFAULT_LOOKUP_LIMIT);
        assertEquals(500, LinkedAccounts.MAX_LOOKUP_LIMIT);
        assertThrows(NullPointerException.class, () -> LinkedAccounts.Settings.from(null));
    }

    @Test
    void anAccountWithNoSavedLinksGetsOneLine() {
        Group group = new Group(STRANGER, List.of(STRANGER), List.of());
        assertEquals(List.of("No saved links for Cy."), LinkedAccounts.showLines(group, "Cy", LinkedAccountsTest::name));
    }

    @Test
    void aFloodgateGroupNamesEveryMemberWithItsPlatformAndTheEarliestDay() {
        Group group = new Group(JAVA, List.of(BEDROCK, JAVA), List.of(link(JAVA, BEDROCK, Source.FLOODGATE_LOGIN, LATER, null)));
        assertEquals(List.of("Linked group: AnnBE (Bedrock), Ann (Java). Since 2026-10-09. Source: Floodgate."),
                LinkedAccounts.showLines(group, "Ann", LinkedAccountsTest::name));
    }

    @Test
    void aLookupLinkCountsAsFloodgateAndAStaffLinkAsStaff() {
        Group lookup = new Group(JAVA, List.of(BEDROCK, JAVA), List.of(link(JAVA, BEDROCK, Source.FLOODGATE_LOOKUP, FIRST, null)));
        assertTrue(LinkedAccounts.showLines(lookup, "Ann", LinkedAccountsTest::name).getFirst().endsWith(" Source: Floodgate."));
        Group staff = new Group(JAVA, List.of(BEDROCK, JAVA), List.of(link(JAVA, BEDROCK, Source.STAFF, FIRST, null)));
        assertEquals("Linked group: AnnBE (Bedrock), Ann (Java). Since 2026-10-01. Source: staff.",
                LinkedAccounts.showLines(staff, "Ann", LinkedAccountsTest::name).getFirst());
    }

    @Test
    void mixedSourcesAreNamedTogetherAndTheEarliestDayWins() {
        Group group = new Group(JAVA, List.of(BEDROCK, JAVA, JAVA_TWO), List.of(
                link(JAVA, BEDROCK, Source.FLOODGATE_LOGIN, LATER, null),
                link(JAVA_TWO, BEDROCK, Source.STAFF, FIRST, null)));
        assertEquals(List.of("Linked group: AnnBE (Bedrock), Ann (Java), Bo (Java). Since 2026-10-01. Source: Floodgate and staff."),
                LinkedAccounts.showLines(group, "Ann", LinkedAccountsTest::name));
    }

    @Test
    void aLinkToAnAccountOutsideTheGroupDoesNotSetItsDayOrSourceButItsSplitIsListed() {
        LocalDate splitDay = LocalDate.of(2026, 10, 12);
        Group group = new Group(JAVA, List.of(BEDROCK, JAVA), List.of(
                link(JAVA, BEDROCK, Source.FLOODGATE_LOGIN, LATER, null),
                link(JAVA, STRANGER, Source.STAFF, FIRST, splitDay)));
        List<String> lines = LinkedAccounts.showLines(group, "Ann", LinkedAccountsTest::name);
        assertEquals(List.of("Linked group: AnnBE (Bedrock), Ann (Java). Since 2026-10-09. Source: Floodgate.",
                "Split: Ann and Cy. Separate from 2026-10-12."), lines);
    }

    @Test
    void anAccountWhoseOnlyLinksAreSplitSaysSoAndListsEachSplit() {
        LocalDate splitDay = LocalDate.of(2026, 10, 5);
        Group group = new Group(JAVA, List.of(JAVA), List.of(link(JAVA, BEDROCK, Source.FLOODGATE_LOGIN, FIRST, splitDay)));
        assertEquals(List.of("No saved links for Ann. Saved splits are below.",
                "Split: AnnBE and Ann. Separate from 2026-10-05."),
                LinkedAccounts.showLines(group, "Ann", LinkedAccountsTest::name));
    }

    @Test
    void unknownNamesFallBackToWhatTheNameFunctionReturns() {
        Group group = new Group(JAVA, List.of(BEDROCK, JAVA), List.of(link(JAVA, BEDROCK, Source.FLOODGATE_LOGIN, FIRST, null)));
        assertEquals("Linked group: " + BEDROCK + " (Bedrock), " + JAVA + " (Java). Since 2026-10-01. Source: Floodgate.",
                LinkedAccounts.showLines(group, JAVA.toString(), UUID::toString).getFirst());
    }

    @Test
    void showLinesRefusesMissingArguments() {
        Group group = new Group(JAVA, List.of(JAVA), List.of());
        assertThrows(NullPointerException.class, () -> LinkedAccounts.showLines(null, "Ann", UUID::toString));
        assertThrows(NullPointerException.class, () -> LinkedAccounts.showLines(group, "Ann", null));
    }
}

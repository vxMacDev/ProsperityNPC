package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.clock.EconomyClock;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.economy.AccountLinkRepository.Group;
import com.prosperitysmp.npc.economy.AccountLinkRepository.Link;
import com.prosperitysmp.npc.economy.AccountLinkRepository.Source;
import com.prosperitysmp.npc.economy.AccountLinkRepository.SplitOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every refusal the link repository makes before it touches the database. The database here is never connected,
 * so reaching SQL would fail with SQLException instead of the refusal under test.
 */
class AccountLinkRepositoryGuardTest {
    private static final UUID JAVA = new UUID(0x7000, 1);
    private static final UUID BEDROCK = new UUID(0, 0x7001);
    private static final UUID RESERVE = new UUID(0x7002, 2);
    private static final Instant AT = Instant.parse("2026-10-14T12:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 14);
    private static final EconomyClock DAYS = new EconomyClock(Clock.fixed(AT, ZoneOffset.UTC), ZoneId.of("UTC"), 4);
    private final AccountLinkRepository links = new AccountLinkRepository(new Database());

    private static String refusal(Executable call) {
        return assertThrows(IllegalArgumentException.class, call).getMessage();
    }

    @Test
    void floodgateIdsAreThoseWithAZeroFirstHalf() {
        assertFalse(AccountLinkRepository.isFloodgateId(null));
        assertTrue(AccountLinkRepository.isFloodgateId(new UUID(0, 0)));
        assertTrue(AccountLinkRepository.isFloodgateId(new UUID(0, -1)));
        assertFalse(AccountLinkRepository.isFloodgateId(new UUID(1, 0)));
        assertFalse(AccountLinkRepository.isFloodgateId(new UUID(-1, 0)));
        assertFalse(AccountLinkRepository.isFloodgateId(new UUID(Long.MIN_VALUE, 5)));
    }

    @Test
    void aNeverSplitLinkIsActiveOnEveryDay() {
        Link link = new Link(BEDROCK, JAVA, JAVA, BEDROCK, Source.FLOODGATE_LOGIN, DAY, null);
        assertTrue(link.activeOn(LocalDate.MIN));
        assertTrue(link.activeOn(DAY));
        assertTrue(link.activeOn(LocalDate.MAX));
    }

    @Test
    void aSplitLinkIsActiveOnlyBeforeItsSplitDay() {
        Link link = new Link(BEDROCK, JAVA, JAVA, BEDROCK, Source.FLOODGATE_LOGIN, DAY, DAY.plusDays(2));
        assertTrue(link.activeOn(DAY));
        assertTrue(link.activeOn(DAY.plusDays(1)));
        assertFalse(link.activeOn(DAY.plusDays(2)), "the split day itself is already separate");
        assertFalse(link.activeOn(DAY.plusDays(3)));
    }

    @Test
    void aGroupCopiesItsListsSoLaterChangesCannotReachIt() {
        List<UUID> members = new ArrayList<>(List.of(JAVA, BEDROCK));
        List<Link> saved = new ArrayList<>(List.of(new Link(BEDROCK, JAVA, JAVA, BEDROCK, Source.STAFF, DAY, null)));
        Group group = new Group(JAVA, members, saved);
        members.clear();
        saved.clear();
        assertEquals(List.of(JAVA, BEDROCK), group.members());
        assertEquals(1, group.links().size());
        assertThrows(UnsupportedOperationException.class, () -> group.members().add(RESERVE));
        assertThrows(UnsupportedOperationException.class, () -> group.links().clear());
    }

    @Test
    void observeRefusesAStaffSource() {
        assertEquals("Staff links are not saved through observe",
                refusal(() -> links.observe(JAVA, BEDROCK, Source.STAFF, AT, DAY, RESERVE)));
    }

    @Test
    void observeRefusesTheSameAccountTwice() {
        assertEquals("Java and Bedrock accounts must differ",
                refusal(() -> links.observe(BEDROCK, BEDROCK, Source.FLOODGATE_LOGIN, AT, DAY, null)));
    }

    @Test
    void observeRefusesTheReserveOnEitherSide() {
        UUID floodgateReserve = new UUID(0, 0x7003);
        assertEquals("The reserve account cannot be linked",
                refusal(() -> links.observe(RESERVE, BEDROCK, Source.FLOODGATE_LOGIN, AT, DAY, RESERVE)));
        assertEquals("The reserve account cannot be linked",
                refusal(() -> links.observe(JAVA, floodgateReserve, Source.FLOODGATE_LOOKUP, AT, DAY, floodgateReserve)));
    }

    @Test
    void observeRefusesABedrockAccountThatIsNotAFloodgateId() {
        assertEquals("Bedrock account is not a Floodgate ID",
                refusal(() -> links.observe(JAVA, new UUID(9, 9), Source.FLOODGATE_LOGIN, AT, DAY, RESERVE)));
    }

    @Test
    void observeRefusesAJavaAccountThatIsAFloodgateId() {
        assertEquals("Java account is a Floodgate ID",
                refusal(() -> links.observe(new UUID(0, 8), BEDROCK, Source.FLOODGATE_LOGIN, AT, DAY, RESERVE)));
    }

    @Test
    void observeRefusesMissingArgumentsBeforeAnyRead() {
        assertThrows(NullPointerException.class, () -> links.observe(null, BEDROCK, Source.FLOODGATE_LOGIN, AT, DAY, null));
        assertThrows(NullPointerException.class, () -> links.observe(JAVA, null, Source.FLOODGATE_LOGIN, AT, DAY, null));
        assertThrows(NullPointerException.class, () -> links.observe(JAVA, BEDROCK, null, AT, DAY, null));
        assertThrows(NullPointerException.class, () -> links.observe(JAVA, BEDROCK, Source.FLOODGATE_LOGIN, null, DAY, null));
        assertThrows(NullPointerException.class, () -> links.observe(JAVA, BEDROCK, Source.FLOODGATE_LOGIN, AT, null, null));
    }

    @ParameterizedTest
    @EnumSource(value = Source.class, names = {"FLOODGATE_LOGIN", "FLOODGATE_LOOKUP"})
    void aValidFloodgatePairGetsPastEveryCheckToTheDatabase(Source source) {
        SQLException reached = assertThrows(SQLException.class,
                () -> links.observe(JAVA, BEDROCK, source, AT, DAY, RESERVE));
        assertEquals("database not connected", reached.getMessage());
        SQLException noReserve = assertThrows(SQLException.class, () -> links.observe(JAVA, BEDROCK, source, AT, DAY, null));
        assertEquals("database not connected", noReserve.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void staffSplitRefusesABlankActor(String actor) {
        assertEquals("Invalid split actor", refusal(() -> links.staffSplit(JAVA, BEDROCK, actor, "reason", DAYS)));
    }

    @Test
    void staffSplitRefusesAMissingOrOverlongActor() {
        assertEquals("Invalid split actor", refusal(() -> links.staffSplit(JAVA, BEDROCK, null, "reason", DAYS)));
        assertEquals("Invalid split actor", refusal(() -> links.staffSplit(JAVA, BEDROCK, "a".repeat(65), "reason", DAYS)));
        SQLException reached = assertThrows(SQLException.class,
                () -> links.staffSplit(JAVA, BEDROCK, "a".repeat(64), "reason", DAYS));
        assertEquals("database not connected", reached.getMessage());
    }

    @Test
    void staffSplitRefusesAMissingBlankOrOverlongReason() {
        assertEquals("Invalid split reason", refusal(() -> links.staffSplit(JAVA, BEDROCK, "staff", null, DAYS)));
        assertEquals("Invalid split reason", refusal(() -> links.staffSplit(JAVA, BEDROCK, "staff", "  ", DAYS)));
        assertEquals("Invalid split reason", refusal(() -> links.staffSplit(JAVA, BEDROCK, "staff", "r".repeat(161), DAYS)));
        SQLException reached = assertThrows(SQLException.class,
                () -> links.staffSplit(JAVA, BEDROCK, "staff", "r".repeat(160), DAYS));
        assertEquals("database not connected", reached.getMessage());
    }

    @Test
    void splittingAnAccountFromItselfIsNotLinkedWithoutAnyRead() throws SQLException {
        var split = links.staffSplit(JAVA, JAVA, "staff", "reason", DAYS);
        assertEquals(SplitOutcome.NOT_LINKED, split.outcome());
        assertNull(split.effectiveDay());
    }

    @Test
    void staffSplitRefusesMissingAccountsOrClock() {
        assertThrows(NullPointerException.class, () -> links.staffSplit(null, BEDROCK, "staff", "reason", DAYS));
        assertThrows(NullPointerException.class, () -> links.staffSplit(JAVA, null, "staff", "reason", DAYS));
        assertThrows(NullPointerException.class, () -> links.staffSplit(JAVA, BEDROCK, "staff", "reason", null));
    }

    @Test
    void ownerGroupsRequireTheCallersTransaction() {
        var autoCommit = new EconomyProxyConnection(true);
        SQLException refused = assertThrows(SQLException.class, () -> links.unitsFor(autoCommit.connection, DAY, true));
        assertEquals("Account links require caller-owned transaction", refused.getMessage());
        assertEquals(List.of("getAutoCommit"), autoCommit.calls, "nothing was written or read");
        assertThrows(NullPointerException.class, () -> links.unitsFor(null, DAY, false));
        assertThrows(NullPointerException.class, () -> links.unitsFor(new EconomyProxyConnection(false).connection, null, false));
    }

    @Test
    void theRepositoryNeedsADatabase() {
        assertThrows(NullPointerException.class, () -> new AccountLinkRepository(null));
        assertEquals(10_000, AccountLinkRepository.MAX_ACTIVE_LINKS);
    }
}

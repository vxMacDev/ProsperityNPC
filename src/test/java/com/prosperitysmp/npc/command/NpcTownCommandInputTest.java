package com.prosperitysmp.npc.command;

import com.prosperitysmp.npc.CoreTestSupport;
import com.prosperitysmp.npc.CoreTestSupport.Inbox;
import com.prosperitysmp.npc.command.NpcTownCommand.Actor;
import com.prosperitysmp.npc.command.NpcTownCommand.Input;
import com.prosperitysmp.npc.command.NpcTownCommand.Request;
import com.prosperitysmp.npc.command.NpcTownCommand.Verb;
import com.prosperitysmp.npc.resident.ResidentCast;
import com.prosperitysmp.npc.resident.ResidentProfile;
import com.prosperitysmp.npc.town.PartnerPerks;
import com.prosperitysmp.npc.town.Partnership;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure parts of /npctown: argument parsing, the staff gate, name lookup, reply shaping and the pending table. */
class NpcTownCommandInputTest {
    private static final String ID = "123e4567-e89b-12d3-a456-426614174000";
    private final Inbox inbox = new Inbox();

    private static String refusal(String... args) {
        return assertThrows(IllegalArgumentException.class, () -> NpcTownCommand.parse(args)).getMessage();
    }

    @Test
    void noArgumentsMeansList() {
        assertEquals(new Input(Verb.LIST, null, null), NpcTownCommand.parse(new String[0]));
    }

    @ParameterizedTest
    @ValueSource(strings = {"list", "LIST", "List"})
    void listIsMatchedInAnyCase(String word) {
        assertEquals(Verb.LIST, NpcTownCommand.parse(new String[]{word}).verb());
    }

    @ParameterizedTest
    @ValueSource(strings = {"seed", "SEED", "Seed"})
    void seedIsMatchedInAnyCase(String word) {
        assertEquals(new Input(Verb.SEED, null, null), NpcTownCommand.parse(new String[]{word}));
    }

    @Test
    void listAndSeedTakeNoFurtherArguments() {
        assertEquals("Unexpected arguments", refusal("list", "x"));
        assertEquals("Unexpected arguments", refusal("seed", "now"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"bogus", "listing", "sed", "remove", "enroll", "", "partner", "contract"})
    void anUnknownFirstWordIsRefused(String word) {
        assertEquals("Unknown town operation", refusal(word));
    }

    @ParameterizedTest
    @ValueSource(strings = {"partner_end", "contract_resolve", "PARTNER_END", "Contract_Resolve"})
    void theUnderscoreSpellingsOfTheContractVerbsAreRefused(String word) {
        assertEquals("Unknown town operation", refusal(word, "a", "b", "c"));
    }

    @Test
    void moreThanSixtySixArgumentsAreRefusedBeforeAnythingIsRead() {
        assertEquals("Too many arguments", refusal(new String[67]));
        assertEquals("Unexpected arguments", refusal(repeat("list", 66)));
    }

    private static String[] repeat(String word, int count) {
        String[] out = new String[count];
        Arrays.fill(out, word);
        return out;
    }

    @Test
    void disableTakesAResidentNameOfOneOrMoreWords() {
        assertEquals(new Input(Verb.DISABLE, "Ellis", null), NpcTownCommand.parse(new String[]{"disable", "Ellis"}));
        assertEquals(new Input(Verb.DISABLE, "Ellis Ward", null), NpcTownCommand.parse(new String[]{"disable", "Ellis", "Ward"}));
        assertEquals(new Input(Verb.DISABLE, ID, null), NpcTownCommand.parse(new String[]{"DISABLE", ID}));
    }

    @Test
    void disableWithoutAResidentIsRefused() {
        assertEquals("Missing resident", refusal("disable"));
    }

    @Test
    void placeTakesAResidentThenADistrict() {
        assertEquals(new Input(Verb.PLACE, "Ellis", "market_row"), NpcTownCommand.parse(new String[]{"place", "Ellis", "market_row"}));
        assertEquals(new Input(Verb.PLACE, "Ellis Ward", "Old-Town"), NpcTownCommand.parse(new String[]{"place", "Ellis", "Ward", "Old-Town"}));
    }

    @Test
    void placeWithoutBothAResidentAndADistrictIsRefused() {
        assertEquals("Missing resident", refusal("place"));
        assertEquals("Missing resident", refusal("place", "Ellis"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"down.town", "a b", "a/b", "disté", "a:b", "x*"})
    void aDistrictOutsideLettersDigitsUnderscoreAndDashIsRefused(String district) {
        assertEquals("Invalid district", refusal("place", "Ellis", district));
    }

    @Test
    void aDistrictOfOneToOneHundredTwentyEightCharactersIsAccepted() {
        assertEquals("d", NpcTownCommand.parse(new String[]{"place", "Ellis", "d"}).district());
        assertEquals("d".repeat(128), NpcTownCommand.parse(new String[]{"place", "Ellis", "d".repeat(128)}).district());
        assertEquals("Invalid district", refusal("place", "Ellis", "d".repeat(129)));
    }

    @Test
    void aResidentKeyOfSixtyFourCharactersIsAcceptedAndSixtyFiveIsNot() {
        assertEquals("r".repeat(64), NpcTownCommand.parse(new String[]{"disable", "r".repeat(64)}).resident());
        assertEquals("Invalid resident key", refusal("disable", "r".repeat(65)));
    }

    @Test
    void aResidentKeyWithAControlCharacterOrOnlyBlanksIsRefused() {
        assertEquals("Invalid resident key", refusal("disable", "El\u0007lis"));
        assertEquals("Invalid resident key", refusal("disable", "El\nlis"));
        assertEquals("Invalid resident key", refusal("disable", "   "));
        assertEquals("Invalid resident key", refusal("disable", ""));
    }

    @Test
    void extraSpacesInsideAMultiWordNameAreKeptAsGivenAndTheEndsAreStripped() {
        assertEquals("Ellis  Ward", NpcTownCommand.parse(new String[]{"disable", "Ellis", "", "Ward"}).resident());
        assertEquals("Ellis", NpcTownCommand.parse(new String[]{"disable", " Ellis "}).resident());
    }

    @Test
    void partnersWithNoResidentListsEverySeat() {
        assertEquals(new Input(Verb.PARTNERS, null, null), NpcTownCommand.parse(new String[]{"partners"}));
        assertEquals(new Input(Verb.PARTNERS, null, null), NpcTownCommand.parse(new String[]{"PARTNERS"}));
    }

    @Test
    void partnersCanNameAResidentInUpToTwoWords() {
        assertEquals("Ellis", NpcTownCommand.parse(new String[]{"partners", "Ellis"}).resident());
        assertEquals("Ellis Ward", NpcTownCommand.parse(new String[]{"partners", "Ellis", "Ward"}).resident());
        assertEquals("Unexpected arguments", refusal("partners", "Ellis", "Ward", "Jr"));
    }

    @Test
    void partnersRefusesABlankOrOversizedOrControlResident() {
        assertEquals("Invalid resident key", refusal("partners", ""));
        assertEquals("Invalid resident key", refusal("partners", "x".repeat(65)));
        assertEquals("Invalid resident key", refusal("partners", "a\u0000b"));
    }

    @Test
    void partnerEndNeedsAPlayerKeyAndACaseReference() {
        Input input = NpcTownCommand.parse(new String[]{"partner-end", "Alex_1", "CASE-77"});
        assertEquals(Verb.PARTNER_END, input.verb());
        assertNull(input.resident());
        assertNull(input.district());
        assertEquals(List.of("Alex_1", "CASE-77"), input.extra());
        assertEquals(Verb.PARTNER_END, NpcTownCommand.parse(new String[]{"PARTNER-END", ID, "c.1"}).verb());
    }

    @Test
    void partnerEndRefusesTheWrongCountOrABadPlayerKeyOrCaseReference() {
        assertEquals("Usage", refusal("partner-end"));
        assertEquals("Usage", refusal("partner-end", "Alex"));
        assertEquals("Usage", refusal("partner-end", "Alex", "c1", "extra"));
        assertEquals("Usage", refusal("partner-end", "Al ex", "c1"));
        assertEquals("Usage", refusal("partner-end", "x".repeat(37), "c1"));
        assertEquals("Usage", refusal("partner-end", "Alex", "bad case"));
        assertEquals("Usage", refusal("partner-end", "Alex", ""));
    }

    @Test
    void aPlayerKeyOfThirtySixCharactersIsAcceptedForPartnerEnd() {
        assertEquals(List.of("x".repeat(36), "c"), NpcTownCommand.parse(new String[]{"partner-end", "x".repeat(36), "c"}).extra());
    }

    @Test
    void contractResolveLowercasesTheContractAndTheVerdictButKeepsTheCaseReference() {
        Input input = NpcTownCommand.parse(new String[]{"contract-resolve", ID.toUpperCase(Locale.ROOT), "NOT-CONSUMED", "Case-9"});
        assertEquals(Verb.CONTRACT_RESOLVE, input.verb());
        assertEquals(List.of(ID, "not-consumed", "Case-9"), input.extra());
        assertEquals(List.of(ID, "consumed", "c"), NpcTownCommand.parse(new String[]{"contract-resolve", ID, "consumed", "c"}).extra());
    }

    @Test
    void contractResolveRefusesAnythingButACanonicalContractAndAKnownVerdict() {
        assertEquals("Usage", refusal("contract-resolve"));
        assertEquals("Usage", refusal("contract-resolve", ID, "consumed"));
        assertEquals("Usage", refusal("contract-resolve", ID, "consumed", "c", "x"));
        assertEquals("Usage", refusal("contract-resolve", "1-1-1-1-1", "consumed", "c"));
        assertEquals("Usage", refusal("contract-resolve", "Ellis", "consumed", "c"));
        assertEquals("Usage", refusal("contract-resolve", ID, "maybe", "c"));
        assertEquals("Usage", refusal("contract-resolve", ID, "not_consumed", "c"));
        assertEquals("Usage", refusal("contract-resolve", ID, "consumed", "bad case"));
    }

    @Test
    void anInputKeepsItsOwnCopyOfTheExtraArguments() {
        List<String> extra = new ArrayList<>(List.of("a", "b"));
        Input input = new Input(Verb.PARTNER_END, null, null, extra);
        extra.add("c");
        assertEquals(List.of("a", "b"), input.extra());
        assertThrows(UnsupportedOperationException.class, () -> input.extra().add("x"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"A", "abc-1", "a_b.c:d@e-f", "2026-10-18", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", "CASE_1"})
    void aCaseReferenceOfAllowedCharactersAndUpToNinetySixIsAccepted(String text) {
        assertTrue(NpcTownCommand.caseRef(text));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "a b", "a/b", "a\\b", "café", "a,b", "a;b", "a#b", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx", "a\n", "'quoted'"})
    void aCaseReferenceOutsideThatIsRefused(String text) {
        assertFalse(NpcTownCommand.caseRef(text));
    }

    @Test
    void aCanonicalUuidIsAResidentIdInEitherCase() {
        assertEquals(UUID.fromString(ID), NpcTownCommand.residentId(ID));
        assertEquals(UUID.fromString(ID), NpcTownCommand.residentId(ID.toUpperCase(Locale.ROOT)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Ellis", "1-1-1-1-1", "123e4567e89b12d3a456426614174000", "123e4567-e89b-12d3-a456-42661417400g",
            "123e4567-e89b-12d3-a456-4266141740000", "123e4567-e89b-12d3-a45-6426614174000", "{123e4567-e89b-12d3-a456-42661417400}"})
    void anythingElseIsNotAResidentId(String text) {
        assertNull(NpcTownCommand.residentId(text));
    }

    private static ResidentProfile copyOf(ResidentProfile base, UUID id, String name) {
        return new ResidentProfile(id, name, base.household(), base.generation(), base.occupation(), base.means(),
                base.lifeStage(), base.temperament(), base.preference(), base.wardrobe());
    }

    @Test
    void aFullNameMatchesWithoutRegardToCase() {
        List<ResidentProfile> cast = ResidentCast.builtIn().profiles();
        assertEquals("Ellis Ward", NpcTownCommand.resolveName(cast, "ellis ward").name());
        assertEquals("Nico Reed", NpcTownCommand.resolveName(cast, "NICO REED").name());
    }

    @Test
    void aFirstNameMatchesWhenNoFullNameDoes() {
        List<ResidentProfile> cast = ResidentCast.builtIn().profiles();
        assertEquals("Mara Vale", NpcTownCommand.resolveName(cast, "mara").name());
        assertEquals("Ellis Ward", NpcTownCommand.resolveName(cast, "ELLIS").name());
    }

    @Test
    void anUnknownNameFindsNobody() {
        List<ResidentProfile> cast = ResidentCast.builtIn().profiles();
        assertNull(NpcTownCommand.resolveName(cast, "Zed"));
        assertNull(NpcTownCommand.resolveName(cast, "Vale"));
        assertNull(NpcTownCommand.resolveName(cast, ""));
        assertNull(NpcTownCommand.resolveName(List.of(), "Mara"));
    }

    @Test
    void twoResidentsSharingAFirstNameMakeThatFirstNameAmbiguous() {
        ResidentProfile mara = ResidentCast.builtIn().profiles().get(0);
        List<ResidentProfile> both = List.of(mara, copyOf(mara, new UUID(5, 5), "Mara Stone"));
        var failure = assertThrows(IllegalArgumentException.class, () -> NpcTownCommand.resolveName(both, "Mara"));
        assertEquals("Ambiguous resident name; use UUID", failure.getMessage());
        assertEquals(mara.name(), NpcTownCommand.resolveName(both, mara.name()).name());
        assertEquals("Mara Stone", NpcTownCommand.resolveName(both, "mara stone").name());
    }

    @Test
    void aFullNameWinsOverAnAmbiguousFirstWord() {
        ResidentProfile mara = ResidentCast.builtIn().profiles().get(0);
        ResidentProfile plainMara = copyOf(mara, new UUID(6, 6), "Mara");
        ResidentProfile marian = copyOf(mara, new UUID(7, 7), "Mara Hale");
        assertEquals(plainMara.id(), NpcTownCommand.resolveName(List.of(mara, plainMara, marian), "Mara").id());
    }

    private CommandSenderBox console(boolean admin) {
        ConsoleCommandSender console = CoreTestSupport.sender(ConsoleCommandSender.class, inbox,
                node -> admin && node.equals("prosperitynpc.admin"), null, "CONSOLE");
        return new CommandSenderBox(console);
    }

    private record CommandSenderBox(ConsoleCommandSender console) { }

    private Player player(UUID id, UUID world, boolean online, boolean admin) {
        World worldProxy = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getUID")) return world;
                    throw new AssertionError("Unexpected world call: " + method);
                });
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> id;
                    case "isOnline" -> online;
                    case "getWorld" -> worldProxy;
                    case "hasPermission" -> admin && "prosperitynpc.admin".equals(args[0]);
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "player";
                    default -> throw new AssertionError("Unexpected player call: " + method);
                });
    }

    private static Actor actorOf(CommandSender login, UUID player, UUID world) {
        return new Actor(login, player, world, Set.of(), player == null ? "console" : player.toString());
    }

    @Test
    void theRealConsoleWithAdminPermissionMayDoAnyVerbWhenTownIsOn() {
        ConsoleCommandSender console = console(true).console();
        for (Verb verb : Verb.values()) {
            assertTrue(NpcTownCommand.authorized(actorOf(console, null, null), verb, false, true, id -> null, console), verb.name());
        }
    }

    @Test
    void aStoppedCommandAuthorizesNobody() {
        ConsoleCommandSender console = console(true).console();
        assertFalse(NpcTownCommand.authorized(actorOf(console, null, null), Verb.LIST, true, true, id -> null, console));
    }

    @Test
    void seedAndPlaceNeedTownEnabledButTheOtherVerbsDoNot() {
        ConsoleCommandSender console = console(true).console();
        for (Verb verb : Verb.values()) {
            boolean needsTown = verb == Verb.SEED || verb == Verb.PLACE;
            assertEquals(!needsTown, NpcTownCommand.authorized(actorOf(console, null, null), verb, false, false, id -> null, console),
                    verb.name());
        }
    }

    @Test
    void withoutTheAdminPermissionNoVerbIsAuthorized() {
        ConsoleCommandSender console = console(false).console();
        for (Verb verb : Verb.values()) {
            assertFalse(NpcTownCommand.authorized(actorOf(console, null, null), verb, false, true, id -> null, console), verb.name());
        }
    }

    @Test
    void aConsoleActorIsOnlyAuthorizedWhenItIsTheServersOwnConsole() {
        ConsoleCommandSender real = console(true).console();
        ConsoleCommandSender other = console(true).console();
        assertFalse(NpcTownCommand.authorized(actorOf(other, null, null), Verb.LIST, false, true, id -> null, real));
    }

    @Test
    void aPlayerActorWhoStillMatchesTheLiveSessionIsAuthorized() {
        UUID id = new UUID(1, 2), world = new UUID(3, 4);
        Player live = player(id, world, true, true);
        assertTrue(NpcTownCommand.authorized(actorOf(live, id, world), Verb.PLACE, false, true, who -> live, console(true).console()));
    }

    @Test
    void aReplacementSessionWithTheSameUuidIsNotTheActor() {
        UUID id = new UUID(1, 2), world = new UUID(3, 4);
        Player original = player(id, world, true, true);
        Player replacement = player(id, world, true, true);
        assertFalse(NpcTownCommand.authorized(actorOf(original, id, world), Verb.LIST, false, true, who -> replacement, console(true).console()));
    }

    @Test
    void anOfflineOrMissingPlayerIsNotAuthorized() {
        UUID id = new UUID(1, 2), world = new UUID(3, 4);
        Player gone = player(id, world, false, true);
        assertFalse(NpcTownCommand.authorized(actorOf(gone, id, world), Verb.LIST, false, true, who -> gone, console(true).console()));
        assertFalse(NpcTownCommand.authorized(actorOf(gone, id, world), Verb.LIST, false, true, who -> null, console(true).console()));
    }

    @Test
    void aPlayerWhoMovedToAnotherWorldIsNotAuthorized() {
        UUID id = new UUID(1, 2);
        Player moved = player(id, new UUID(9, 9), true, true);
        assertFalse(NpcTownCommand.authorized(actorOf(moved, id, new UUID(3, 4)), Verb.LIST, false, true, who -> moved, console(true).console()));
    }

    @Test
    void aPlayerWhoLostAdminIsNotAuthorized() {
        UUID id = new UUID(1, 2), world = new UUID(3, 4);
        Player demoted = player(id, world, true, false);
        assertFalse(NpcTownCommand.authorized(actorOf(demoted, id, world), Verb.LIST, false, true, who -> demoted, console(true).console()));
    }

    @Test
    void anActorCopiesItsDisplayWorlds() {
        Set<UUID> worlds = new HashSet<>(Set.of(new UUID(1, 1)));
        Actor actor = new Actor(console(true).console(), null, null, worlds, "k");
        worlds.add(new UUID(2, 2));
        assertEquals(Set.of(new UUID(1, 1)), actor.displayWorlds());
    }

    @Test
    void beforeAMutationTheResidentIsRecordedAsAttemptedEvenIfTheWriteLaterFails() {
        Request request = new Request(actorOf(console(true).console(), null, null), new Input(Verb.SEED, null, null));
        assertEquals(Set.of(), request.attempted().get());
        request.beforeMutation(new UUID(0, 1));
        request.beforeMutation(new UUID(0, 2));
        request.beforeMutation(new UUID(0, 1));
        assertEquals(Set.of(new UUID(0, 1), new UUID(0, 2)), request.attempted().get());
    }

    @Test
    void aRefusedRequestSaysSoAndInvalidatesWhatWasAttempted() {
        Request request = new Request(actorOf(console(true).console(), null, null), new Input(Verb.SEED, null, null));
        request.beforeMutation(new UUID(0, 3));
        var reply = NpcTownCommand.failed(request, new IllegalArgumentException("secret detail"));
        assertEquals(List.of("Town request refused. Check the resident UUID, active district anchors and current occupancy."), reply.lines());
        assertEquals(Set.of(new UUID(0, 3)), reply.invalidate());
        assertEquals(NamedTextColor.RED, reply.color());
        assertEquals("IllegalArgumentException", reply.errorType());
    }

    @Test
    void anyOtherFailureWarnsTheOutcomeMayBePartialWithoutLeakingTheMessage() {
        Request request = new Request(actorOf(console(true).console(), null, null), new Input(Verb.SEED, null, null));
        var reply = NpcTownCommand.failed(request, new SQLException("jdbc:mariadb://secret-host"));
        assertEquals(List.of("Town request failed. Check stored state before retrying; the outcome may be partial."), reply.lines());
        assertEquals("SQLException", reply.errorType());
        assertEquals(Set.of(), reply.invalidate());
        assertFalse(reply.lines().get(0).contains("secret-host"));
    }

    @Test
    void publishAlwaysInvalidatesInIdOrderButOnlyDeliversToAnAuthorizedRequester() {
        var reply = new NpcTownCommand.Reply(List.of("done"), Set.of(new UUID(0, 9), new UUID(0, 2), new UUID(0, 5)), NamedTextColor.GREEN, null);
        List<UUID> invalidated = new ArrayList<>();
        List<Object> delivered = new ArrayList<>();
        NpcTownCommand.publish(reply, invalidated::add, () -> false, delivered::add);
        assertEquals(List.of(new UUID(0, 2), new UUID(0, 5), new UUID(0, 9)), invalidated);
        assertTrue(delivered.isEmpty());
        invalidated.clear();
        NpcTownCommand.publish(reply, invalidated::add, () -> true, delivered::add);
        assertEquals(3, invalidated.size());
        assertEquals(List.of(reply), delivered);
    }

    @Test
    void publishChecksTheRequesterOnlyAfterEveryInvalidationRan() {
        var reply = new NpcTownCommand.Reply(List.of("done"), Set.of(new UUID(0, 1)), NamedTextColor.GREEN, null);
        List<String> order = new ArrayList<>();
        NpcTownCommand.publish(reply, id -> order.add("invalidate"), () -> {
            order.add("check");
            return true;
        }, r -> order.add("deliver"));
        assertEquals(List.of("invalidate", "check", "deliver"), order);
    }

    @Test
    void aReplyOwnsCopiesOfItsLinesAndInvalidations() {
        List<String> lines = new ArrayList<>(List.of("a"));
        Set<UUID> ids = new HashSet<>(Set.of(new UUID(0, 1)));
        var reply = new NpcTownCommand.Reply(lines, ids, NamedTextColor.GRAY, null);
        lines.add("b");
        ids.add(new UUID(0, 2));
        assertEquals(List.of("a"), reply.lines());
        assertEquals(Set.of(new UUID(0, 1)), reply.invalidate());
    }

    private static class RecordingFuture implements Future<Boolean> {
        private final Object outcome;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private Boolean mayInterrupt;
        private long timeoutSeen = -1;
        private TimeUnit unitSeen;

        RecordingFuture(Object outcome) {
            this.outcome = outcome;
        }

        @Override public boolean cancel(boolean interrupt) {
            cancelled.set(true);
            mayInterrupt = interrupt;
            return true;
        }
        @Override public boolean isCancelled() { return cancelled.get(); }
        @Override public boolean isDone() { return true; }
        @Override public Boolean get() { throw new AssertionError("must use the timed wait"); }
        @Override public Boolean get(long timeout, TimeUnit unit) throws ExecutionException, TimeoutException {
            timeoutSeen = timeout;
            unitSeen = unit;
            if (outcome instanceof TimeoutException timeoutFailure) throw timeoutFailure;
            if (outcome instanceof Exception failure) throw new ExecutionException(failure);
            return (Boolean) outcome;
        }
    }

    @Test
    void theAuthorizationWaitIsTwoSecondsAndIsCancelledAfterAnAnswer() throws Exception {
        RecordingFuture granted = new RecordingFuture(Boolean.TRUE);
        assertTrue(NpcTownCommand.awaitAuthorization(granted));
        assertEquals(2, granted.timeoutSeen);
        assertEquals(TimeUnit.SECONDS, granted.unitSeen);
        assertTrue(granted.cancelled.get());
        assertEquals(Boolean.FALSE, granted.mayInterrupt);
        assertFalse(NpcTownCommand.awaitAuthorization(new RecordingFuture(Boolean.FALSE)));
    }

    @Test
    void aTimedOutAuthorizationPropagatesAndStillCancelsTheRequest() {
        RecordingFuture slow = new RecordingFuture(new TimeoutException());
        assertThrows(TimeoutException.class, () -> NpcTownCommand.awaitAuthorization(slow));
        assertTrue(slow.cancelled.get());
    }

    @Test
    void aFailedAuthorizationCheckPropagatesAndStillCancelsTheRequest() {
        RecordingFuture broken = new RecordingFuture(new IllegalStateException("main thread busy"));
        assertThrows(ExecutionException.class, () -> NpcTownCommand.awaitAuthorization(broken));
        assertTrue(broken.cancelled.get());
    }

    @Test
    void aCancelledAuthorizationPropagates() {
        assertThrows(CancellationException.class, () -> NpcTownCommand.awaitAuthorization(new RecordingFuture(null) {
            @Override public Boolean get(long timeout, TimeUnit unit) {
                throw new CancellationException();
            }
        }));
    }

    private static Partnership seat(UUID owner, UUID resident, LocalDate lastDay) {
        return new Partnership(UUID.randomUUID(), owner, resident, LocalDate.of(2026, 10, 1), lastDay, new PartnerPerks(40, 0),
                Partnership.Status.ACTIVE, null, null, UUID.randomUUID(), 1);
    }

    @Test
    void noActivePartnershipsHasItsOwnLine() {
        assertEquals(List.of("No active partnerships."), NpcTownCommand.partnerLines(List.of(), id -> "x", 4));
    }

    @Test
    void eachPartnershipLineNamesOwnerResidentLastDayAndSeatsInUseOfTheMaximum() {
        UUID ellis = new UUID(0, 100), mara = new UUID(0, 200);
        var rows = List.of(seat(new UUID(0, 1), ellis, LocalDate.of(2026, 10, 20)),
                seat(new UUID(0, 2), ellis, LocalDate.of(2026, 10, 21)), seat(new UUID(0, 3), mara, LocalDate.of(2026, 10, 22)));
        Function<UUID, String> names = id -> id.equals(ellis) ? "Ellis Ward" : "Mara Vale";
        assertEquals(List.of(
                new UUID(0, 1) + " | Ellis Ward | until 2026-10-20 | seats 2/4",
                new UUID(0, 2) + " | Ellis Ward | until 2026-10-21 | seats 2/4",
                new UUID(0, 3) + " | Mara Vale | until 2026-10-22 | seats 1/4"), NpcTownCommand.partnerLines(rows, names, 4));
    }

    @Test
    void thePartnershipLinesCannotBeChangedByTheCaller() {
        var lines = NpcTownCommand.partnerLines(List.of(seat(new UUID(0, 1), new UUID(0, 2), LocalDate.of(2026, 10, 20))), id -> "n", 3);
        assertThrows(UnsupportedOperationException.class, () -> lines.add("x"));
    }

    @Test
    void thePendingTableAdmitsOneRequestPerActorAndAtMostSixteenActors() {
        NpcTownCommand.Pending pending = new NpcTownCommand.Pending();
        List<Request> requests = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            Request request = new Request(new Actor(console(true).console(), null, null, Set.of(), "actor-" + i), new Input(Verb.LIST, null, null));
            requests.add(request);
            assertTrue(pending.reserve(request), "actor " + i);
        }
        Request seventeenth = new Request(new Actor(console(true).console(), null, null, Set.of(), "actor-16"), new Input(Verb.LIST, null, null));
        assertFalse(pending.reserve(seventeenth));
        Request again = new Request(new Actor(console(true).console(), null, null, Set.of(), "actor-0"), new Input(Verb.LIST, null, null));
        assertFalse(pending.reserve(again));
        assertEquals(16, pending.snapshot().size());
        pending.release(requests.get(3));
        assertTrue(pending.reserve(seventeenth));
    }

    @Test
    void releasingADifferentRequestOfTheSameActorKeepsTheReservation() {
        NpcTownCommand.Pending pending = new NpcTownCommand.Pending();
        Actor actor = new Actor(console(true).console(), null, null, Set.of(), "k");
        Request first = new Request(actor, new Input(Verb.LIST, null, null));
        Request stranger = new Request(actor, new Input(Verb.LIST, null, null));
        assertTrue(pending.reserve(first));
        pending.release(stranger);
        assertEquals(List.of(first), pending.snapshot());
        assertSame(first, pending.snapshot().get(0));
        pending.release(first);
        assertTrue(pending.snapshot().isEmpty());
    }

    @Test
    void clearingThePendingTableFreesEveryActor() {
        NpcTownCommand.Pending pending = new NpcTownCommand.Pending();
        Actor actor = new Actor(console(true).console(), null, null, Set.of(), "k");
        assertTrue(pending.reserve(new Request(actor, new Input(Verb.LIST, null, null))));
        pending.clear();
        assertTrue(pending.snapshot().isEmpty());
        assertTrue(pending.reserve(new Request(actor, new Input(Verb.LIST, null, null))));
    }
}

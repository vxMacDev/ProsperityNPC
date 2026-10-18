package com.prosperitysmp.npc.market;

import com.prosperitysmp.npc.market.FiniteHousingPlanner.Assignment;
import com.prosperitysmp.npc.market.FiniteHousingPlanner.Market;
import com.prosperitysmp.npc.market.HousingMarketSnapshot.Home;
import com.prosperitysmp.npc.market.HousingMarketSnapshot.Participant;
import com.prosperitysmp.npc.market.HousingMarketSnapshot.Status;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import static org.junit.jupiter.api.Assertions.*;

/** The snapshot binds a quote to the facts it was read from; every eligibility branch is flipped on its own. */
class HousingMarketSnapshotTest {
    private static final LocalDate DAY = LocalDate.of(2026, 9, 7);
    private static final Instant CAPTURED = Instant.parse("2026-09-07T12:00:00Z");
    private static final UUID WORLD = id(900);
    private static final UUID OTHER_WORLD = id(901);
    private static final UUID OWNER = id(800);
    private static final UUID REQUEST = id(700);
    private static final HousingMoveRequest.Policy POLICY = new HousingMoveRequest.Policy(8, 8, 4, 7);

    private static UUID id(long n) { return new UUID(0x5150L, n); }

    private static HousingPolicy policy(String market, String classKey, HousingPolicy.Tier tier, long rent, int condition) {
        return new HousingPolicy(WORLD, market, classKey, tier, rent, condition);
    }

    private static Home home(long n, String market, String classKey, long asking, int condition, Integer... slots) {
        return new Home(id(n), OWNER, WORLD, "region-" + n, market, classKey, 2, 5L, 4, 3_000, asking, condition,
                1, 1, List.of(slots));
    }

    private static HousingRegistry.Member member(long n, HousingPolicy policy) {
        return new HousingRegistry.Member(id(n), policy, DAY.minusDays(3), 9L, true);
    }

    private static HousingMoveRepository.Receipt admission(long resident, long property, String market,
                                                           HousingMoveRequest.Policy policy) {
        var request = new HousingMoveRequest(id(5_000 + resident), id(resident), id(property), OWNER, WORLD, market,
                "flat", 2, 5L, 1L, -1L, 0, DAY);
        return new HousingMoveRepository.Receipt(request, policy, 1L, DAY.plusDays(7), CAPTURED);
    }

    /** One ready resident and one matching home; each case changes exactly one fact. */
    private static final class Scenario {
        HousingMoveRequest.Policy movePolicy = POLICY;
        HousingPolicy eligibility = policy("town", "flat", HousingPolicy.Tier.STANDARD, 3_000, 60);
        Status status = Status.READY;
        boolean enabled = true;
        LocalDate availableSince = DAY.minusDays(3);
        LocalDate cooldownUntil = DAY;
        long memberRevision = 9L;
        long tenancyRevision = -1L;
        HousingMoveRequest.Origin origin;
        Home home = home(1, "town", "flat", 3_000, 60, 0, 2);
        List<Home> extraHomes = new ArrayList<>();
        List<HousingMoveRepository.Receipt> admissions = new ArrayList<>();
        Assignment assignment = new Assignment(id(10), id(1), 2, 9L, -1L);
        boolean premiumEnabled;

        HousingMarketSnapshot snapshot() {
            var member = new HousingRegistry.Member(id(10), eligibility, availableSince, memberRevision, enabled);
            var homes = new ArrayList<Home>();
            homes.add(home);
            homes.addAll(extraHomes);
            return new HousingMarketSnapshot(DAY, CAPTURED, movePolicy, homes,
                    List.of(new Participant(member, tenancyRevision, cooldownUntil, status, origin)), admissions);
        }

        HousingMoveRequest quote() { return snapshot().quote(REQUEST, assignment, premiumEnabled); }
    }

    @Test void aMatchingResidentAndHomeQuoteExactlyTheSnapshotFacts() {
        var quote = new Scenario().quote();
        assertEquals(new HousingMoveRequest(REQUEST, id(10), id(1), OWNER, WORLD, "town", "flat", 2, 5L, 9L, -1L, 2, DAY,
                HousingPolicy.Tier.STANDARD, null), quote);
    }

    @Test void theTwoArgumentQuoteIsTheStandardQuote() {
        var scenario = new Scenario();
        assertEquals(scenario.quote(), scenario.snapshot().quote(REQUEST, scenario.assignment));
    }

    @Test void exactBoundaryValuesStillQuote() {
        var scenario = new Scenario();
        scenario.availableSince = DAY;
        scenario.cooldownUntil = DAY;
        scenario.home = home(1, "town", "flat", 3_000, 60, 2);
        scenario.eligibility = policy("town", "flat", HousingPolicy.Tier.STANDARD, 3_000, 60);
        assertEquals(2, scenario.quote().slot(), "rent equal to the cap, condition equal to the minimum, available today");
    }

    static Stream<Arguments> refusals() {
        return Stream.of(
                Arguments.of("occupied resident", (Consumer<Scenario>) s -> s.status = Status.OCCUPIED),
                Arguments.of("disabled status", (Consumer<Scenario>) s -> s.status = Status.DISABLED),
                Arguments.of("cooldown status", (Consumer<Scenario>) s -> s.status = Status.COOLDOWN),
                Arguments.of("premium held without premium", (Consumer<Scenario>) s -> s.status = Status.PREMIUM_HELD),
                Arguments.of("disabled member", (Consumer<Scenario>) s -> s.enabled = false),
                Arguments.of("available tomorrow", (Consumer<Scenario>) s -> s.availableSince = DAY.plusDays(1)),
                Arguments.of("cooling down until tomorrow", (Consumer<Scenario>) s -> s.cooldownUntil = DAY.plusDays(1)),
                Arguments.of("membership revision moved", (Consumer<Scenario>) s -> s.memberRevision = 10L),
                Arguments.of("tenancy revision moved", (Consumer<Scenario>) s -> s.tenancyRevision = 0L),
                Arguments.of("slot not free", (Consumer<Scenario>) s -> s.assignment = new Assignment(id(10), id(1), 1, 9L, -1L)),
                Arguments.of("other market", (Consumer<Scenario>) s -> s.eligibility =
                        policy("village", "flat", HousingPolicy.Tier.STANDARD, 3_000, 60)),
                Arguments.of("other class", (Consumer<Scenario>) s -> s.eligibility =
                        policy("town", "house", HousingPolicy.Tier.STANDARD, 3_000, 60)),
                Arguments.of("other world", (Consumer<Scenario>) s -> s.home = new Home(id(1), OWNER, OTHER_WORLD,
                        "region-1", "town", "flat", 2, 5L, 4, 3_000, 3_000, 60, 1, 1, List.of(0, 2))),
                Arguments.of("rent one cent over the cap", (Consumer<Scenario>) s -> s.home = home(1, "town", "flat", 3_001, 60, 0, 2)),
                Arguments.of("condition one under the minimum", (Consumer<Scenario>) s -> s.home = home(1, "town", "flat", 3_000, 59, 0, 2)),
                Arguments.of("premium tier without premium", (Consumer<Scenario>) s -> s.eligibility =
                        policy("town", "flat", HousingPolicy.Tier.PREMIUM, 3_000, 60)),
                Arguments.of("already admitted today", (Consumer<Scenario>) s -> s.admissions.add(admission(10, 2, "town", s.movePolicy))),
                Arguments.of("server quota used", (Consumer<Scenario>) s -> {
                    s.movePolicy = new HousingMoveRequest.Policy(1, 8, 4, 7);
                    s.admissions.add(admission(20, 3, "elsewhere", s.movePolicy));
                }),
                Arguments.of("market quota used", (Consumer<Scenario>) s -> {
                    s.movePolicy = new HousingMoveRequest.Policy(8, 1, 4, 7);
                    s.extraHomes.add(home(3, "town", "flat", 3_000, 60, 0));
                    s.admissions.add(admission(20, 3, "town", s.movePolicy));
                }),
                Arguments.of("property quota used", (Consumer<Scenario>) s -> {
                    s.movePolicy = new HousingMoveRequest.Policy(8, 8, 1, 7);
                    s.admissions.add(admission(20, 1, "town", s.movePolicy));
                }));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("refusals")
    void anyMismatchedFactRefusesTheQuote(String name, Consumer<Scenario> change) {
        var scenario = new Scenario();
        assertDoesNotThrow(scenario::quote, "the unchanged scenario quotes");
        change.accept(scenario);
        var refusal = assertThrows(IllegalArgumentException.class, scenario::quote, name);
        assertEquals("Assignment does not match snapshot eligibility", refusal.getMessage());
    }

    @Test void aResidentOrHomeOutsideTheSnapshotIsRefusedByName() {
        var snapshot = new Scenario().snapshot();
        var noResident = assertThrows(IllegalArgumentException.class,
                () -> snapshot.quote(REQUEST, new Assignment(id(11), id(1), 2, 9L, -1L)));
        assertEquals("Resident absent from snapshot", noResident.getMessage());
        var noHome = assertThrows(IllegalArgumentException.class,
                () -> snapshot.quote(REQUEST, new Assignment(id(10), id(2), 2, 9L, -1L)));
        assertEquals("Home absent from snapshot", noHome.getMessage());
        assertThrows(NullPointerException.class, () -> snapshot.quote(null, new Assignment(id(10), id(1), 2, 9L, -1L)));
        assertThrows(NullPointerException.class, () -> snapshot.quote(REQUEST, null));
    }

    @Test void premiumHeldResidentsQuoteOnlyWhilePremiumIsOn() {
        var scenario = new Scenario();
        scenario.status = Status.PREMIUM_HELD;
        scenario.eligibility = policy("town", "flat", HousingPolicy.Tier.PREMIUM, 3_000, 60);
        scenario.premiumEnabled = true;
        var quote = scenario.quote();
        assertEquals(HousingPolicy.Tier.PREMIUM, quote.tier());
        assertNull(quote.origin());
        scenario.premiumEnabled = false;
        assertThrows(IllegalArgumentException.class, scenario::quote);
    }

    @Test void anOccupiedPremiumResidentWithAnOriginRelocatesAndCarriesTheOrigin() {
        var origin = new HousingMoveRequest.Origin(id(50), OWNER, 1, 3L, 1, id(51));
        var scenario = new Scenario();
        scenario.status = Status.OCCUPIED;
        scenario.origin = origin;
        scenario.tenancyRevision = 4L;
        scenario.assignment = new Assignment(id(10), id(1), 2, 9L, 4L);
        scenario.eligibility = policy("town", "flat", HousingPolicy.Tier.PREMIUM, 3_000, 60);
        scenario.premiumEnabled = true;
        var quote = scenario.quote();
        assertEquals(origin, quote.origin());
        assertEquals(4L, quote.tenancyRevision());
        assertEquals(HousingPolicy.Tier.PREMIUM, quote.tier());
        scenario.premiumEnabled = false;
        assertThrows(IllegalArgumentException.class, scenario::quote, "no relocation while premium is off");
    }

    @Test void anOccupiedStandardResidentNeverRelocatesEvenWithAnOrigin() {
        var scenario = new Scenario();
        scenario.status = Status.OCCUPIED;
        scenario.origin = new HousingMoveRequest.Origin(id(50), OWNER, 1, 3L, 1, id(51));
        scenario.tenancyRevision = 4L;
        scenario.assignment = new Assignment(id(10), id(1), 2, 9L, 4L);
        scenario.premiumEnabled = true;
        assertThrows(IllegalArgumentException.class, scenario::quote);
    }

    @Test void seekersAreTheReadyParticipantsAndPremiumHeldOnlyWhenPremiumIsOn() {
        var standard = policy("town", "flat", HousingPolicy.Tier.STANDARD, 3_000, 60);
        var participants = new ArrayList<Participant>();
        long n = 20;
        for (Status status : Status.values())
            participants.add(new Participant(new HousingRegistry.Member(id(n++), standard, DAY.minusDays(1), 3L, true),
                    -1L, DAY.plusDays(2), status));
        var snapshot = new HousingMarketSnapshot(DAY, CAPTURED, POLICY, List.of(), participants, List.of());
        var ready = participants.get(Status.READY.ordinal()).member();
        var held = participants.get(Status.PREMIUM_HELD.ordinal()).member();
        var seeker = new FiniteHousingPlanner.Seeker(ready.residentId(), standard, 3L, -1L, DAY.minusDays(1), DAY.plusDays(2));
        assertEquals(List.of(seeker), snapshot.seekers());
        assertEquals(List.of(seeker), snapshot.seekers(false));
        assertEquals(List.of(seeker, new FiniteHousingPlanner.Seeker(held.residentId(), standard, 3L, -1L,
                DAY.minusDays(1), DAY.plusDays(2))), snapshot.seekers(true));
    }

    @Test void remainingStartsEveryCurrentHomeAtThePolicyAndSubtractsAdmissions() {
        var homes = List.of(home(1, "town", "flat", 3_000, 60, 0), home(2, "town", "flat", 3_000, 60, 0),
                home(3, "village", "flat", 3_000, 60, 0));
        var admissions = List.of(admission(20, 1, "town", POLICY), admission(21, 1, "town", POLICY),
                admission(22, 9, "harbor", POLICY));
        var remaining = new HousingMarketSnapshot(DAY, CAPTURED, POLICY, homes, List.of(), admissions).remaining();
        assertEquals(5, remaining.server());
        assertEquals(Map.of(new Market(WORLD, "town"), 6, new Market(WORLD, "village"), 8), remaining.markets(),
                "an admission to a home no longer in the snapshot adds no market key");
        assertEquals(Map.of(id(1), 2, id(2), 4, id(3), 4), remaining.properties());
    }

    @Test void recordedAdmissionsMayNotExceedAnyQuota() {
        var serverOne = new HousingMoveRequest.Policy(1, 8, 4, 7);
        assertThrows(IllegalArgumentException.class, () -> new HousingMarketSnapshot(DAY, CAPTURED, serverOne, List.of(),
                List.of(), List.of(admission(20, 1, "town", serverOne), admission(21, 2, "town", serverOne))));
        var marketOne = new HousingMoveRequest.Policy(8, 1, 4, 7);
        assertThrows(IllegalArgumentException.class, () -> new HousingMarketSnapshot(DAY, CAPTURED, marketOne, List.of(),
                List.of(), List.of(admission(20, 1, "town", marketOne), admission(21, 2, "town", marketOne))));
        assertDoesNotThrow(() -> new HousingMarketSnapshot(DAY, CAPTURED, marketOne, List.of(),
                List.of(), List.of(admission(20, 1, "town", marketOne), admission(21, 2, "village", marketOne))));
        var propertyOne = new HousingMoveRequest.Policy(8, 8, 1, 7);
        var refusal = assertThrows(IllegalArgumentException.class, () -> new HousingMarketSnapshot(DAY, CAPTURED, propertyOne,
                List.of(), List.of(), List.of(admission(20, 1, "town", propertyOne), admission(21, 1, "town", propertyOne))));
        assertEquals("Recorded housing quotas exceeded", refusal.getMessage());
    }

    @Test void admissionsMustBelongToThisDayAndPolicyAndNameEachResidentOnce() {
        var otherPolicy = new HousingMoveRequest.Policy(8, 8, 4, 6);
        var wrongPolicy = assertThrows(IllegalArgumentException.class, () -> new HousingMarketSnapshot(DAY, CAPTURED, POLICY,
                List.of(), List.of(), List.of(admission(20, 1, "town", otherPolicy))));
        assertEquals("Invalid snapshot admission scope", wrongPolicy.getMessage());
        var yesterday = new HousingMoveRequest(id(30), id(20), id(1), OWNER, WORLD, "town", "flat", 2, 5L, 1L, -1L, 0,
                DAY.minusDays(1));
        assertThrows(IllegalArgumentException.class, () -> new HousingMarketSnapshot(DAY, CAPTURED, POLICY, List.of(),
                List.of(), List.of(new HousingMoveRepository.Receipt(yesterday, POLICY, 1L, DAY, CAPTURED))));
        assertThrows(IllegalArgumentException.class, () -> new HousingMarketSnapshot(DAY, CAPTURED, POLICY, List.of(),
                List.of(), List.of(admission(20, 1, "town", POLICY), admission(20, 2, "town", POLICY))));
    }

    @Test void homesAndParticipantsAreUniqueAndBoundedToFiveHundredTwelve() {
        var standard = policy("town", "flat", HousingPolicy.Tier.STANDARD, 3_000, 60);
        assertEquals("Duplicate snapshot home", assertThrows(IllegalArgumentException.class, () -> new HousingMarketSnapshot(
                DAY, CAPTURED, POLICY, List.of(home(1, "town", "flat", 3_000, 60), home(1, "town", "flat", 3_000, 60)),
                List.of(), List.of())).getMessage());
        var p = new Participant(member(10, standard), -1L, DAY, Status.READY);
        assertEquals("Duplicate snapshot participant", assertThrows(IllegalArgumentException.class,
                () -> new HousingMarketSnapshot(DAY, CAPTURED, POLICY, List.of(), List.of(p, p), List.of())).getMessage());
        var many = new ArrayList<Home>();
        for (int i = 0; i < 512; i++) many.add(home(i, "town", "flat", 3_000, 60));
        assertEquals(512, new HousingMarketSnapshot(DAY, CAPTURED, POLICY, many, List.of(), List.of()).homes().size());
        many.add(home(512, "town", "flat", 3_000, 60));
        assertEquals("Housing snapshot exceeds bounds", assertThrows(IllegalArgumentException.class,
                () -> new HousingMarketSnapshot(DAY, CAPTURED, POLICY, many, List.of(), List.of())).getMessage());
    }

    @Test void theSnapshotDayIsWithinYearsOneToNineNineNineNine() {
        assertThrows(IllegalArgumentException.class, () -> new HousingMarketSnapshot(LocalDate.of(0, 12, 31), CAPTURED,
                POLICY, List.of(), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new HousingMarketSnapshot(LocalDate.of(10_000, 1, 1), CAPTURED,
                POLICY, List.of(), List.of(), List.of()));
        assertDoesNotThrow(() -> new HousingMarketSnapshot(LocalDate.of(1, 1, 1), CAPTURED, POLICY, List.of(), List.of(), List.of()));
        assertDoesNotThrow(() -> new HousingMarketSnapshot(LocalDate.of(9_999, 12, 31), CAPTURED, POLICY, List.of(), List.of(), List.of()));
        assertThrows(NullPointerException.class, () -> new HousingMarketSnapshot(null, CAPTURED, POLICY, List.of(), List.of(), List.of()));
        assertThrows(NullPointerException.class, () -> new HousingMarketSnapshot(DAY, null, POLICY, List.of(), List.of(), List.of()));
        assertThrows(NullPointerException.class, () -> new HousingMarketSnapshot(DAY, CAPTURED, null, List.of(), List.of(), List.of()));
    }

    @Test void snapshotListsAreImmutableCopies() {
        var homes = new ArrayList<>(List.of(home(1, "town", "flat", 3_000, 60, 0)));
        var snapshot = new HousingMarketSnapshot(DAY, CAPTURED, POLICY, homes, List.of(), List.of());
        homes.clear();
        assertEquals(1, snapshot.homes().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.homes().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.participants().add(null));
    }

    @Test void homeSlotsMustBeDistinctAndInsideCapacity() {
        assertEquals(List.of(3, 0), home(1, "town", "flat", 3_000, 60, 3, 0).freeSlots());
        assertThrows(IllegalArgumentException.class, () -> home(1, "town", "flat", 3_000, 60, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> home(1, "town", "flat", 3_000, 60, -1));
        assertThrows(IllegalArgumentException.class, () -> home(1, "town", "flat", 3_000, 60, 4));
        var twoRoom = (Consumer<List<Integer>>) slots -> new Home(id(1), OWNER, WORLD, "r", "town", "flat", 0, 0L, 2,
                100, 100, 50, 0, 0, slots);
        assertDoesNotThrow(() -> twoRoom.accept(List.of(0, 1)));
        assertEquals("Invalid housing snapshot slots",
                assertThrows(IllegalArgumentException.class, () -> twoRoom.accept(List.of(2))).getMessage());
        assertThrows(IllegalArgumentException.class, () -> twoRoom.accept(List.of(0, 1, 1)));
        var mutable = new ArrayList<>(List.of(0));
        var copied = new Home(id(1), OWNER, WORLD, "r", "town", "flat", 0, 0L, 2, 100, 100, 50, 0, 0, mutable);
        mutable.add(1);
        assertEquals(List.of(0), copied.freeSlots());
    }

    /** Home(capacity, reference, asking, condition, installed, effective, advertising, partner, epoch, revision, region). */
    private static Home bounded(int capacity, long reference, long asking, int condition, int installed, int effective,
                                int advertising, int partner, int epoch, long revision, String region) {
        return new Home(id(1), OWNER, WORLD, region, "town", "flat", epoch, revision, capacity, reference, asking,
                condition, installed, effective, List.of(), advertising, partner);
    }

    @Test void homeNumbersAcceptTheirExactLimits() {
        var low = bounded(1, 1, 1, 0, 0, 0, 0, 0, 0, 0L, "r");
        assertEquals(1, low.capacity());
        var high = bounded(4, Integer.MAX_VALUE, Integer.MAX_VALUE, 100, 3, 3, 3, 4, Integer.MAX_VALUE,
                Long.MAX_VALUE - 1, "r".repeat(128));
        assertEquals(4, high.partnerBonusPoints());
        var disabled = bounded(2, 100, 100, 50, 3, 0, 0, 0, 0, 0L, "r");
        assertEquals(3, disabled.installedFacilities(), "disabled facilities stay installed");
        assertEquals(0, disabled.effectiveFacilities(), "disabled facilities report zero effective");
        assertEquals(3, high.effectiveFacilities(), "enabled facilities are effective at their installed level");
        assertEquals(0, home(1, "town", "flat", 100, 50).partnerBonusPoints(), "the short constructor has no partner bonus");
        assertEquals(0, home(1, "town", "flat", 100, 50).advertisingLevel());
    }

    static Stream<Arguments> homeBounds() {
        return Stream.of(
                Arguments.of("capacity 0", (Runnable) () -> bounded(0, 100, 100, 50, 0, 0, 0, 0, 0, 0L, "r")),
                Arguments.of("capacity 5", (Runnable) () -> bounded(5, 100, 100, 50, 0, 0, 0, 0, 0, 0L, "r")),
                Arguments.of("reference 0", (Runnable) () -> bounded(1, 0, 100, 50, 0, 0, 0, 0, 0, 0L, "r")),
                Arguments.of("reference over int", (Runnable) () -> bounded(1, Integer.MAX_VALUE + 1L, 100, 50, 0, 0, 0, 0, 0, 0L, "r")),
                Arguments.of("asking 0", (Runnable) () -> bounded(1, 100, 0, 50, 0, 0, 0, 0, 0, 0L, "r")),
                Arguments.of("asking over int", (Runnable) () -> bounded(1, 100, Integer.MAX_VALUE + 1L, 50, 0, 0, 0, 0, 0, 0L, "r")),
                Arguments.of("condition -1", (Runnable) () -> bounded(1, 100, 100, -1, 0, 0, 0, 0, 0, 0L, "r")),
                Arguments.of("condition 101", (Runnable) () -> bounded(1, 100, 100, 101, 0, 0, 0, 0, 0, 0L, "r")),
                Arguments.of("installed -1", (Runnable) () -> bounded(1, 100, 100, 50, -1, 0, 0, 0, 0, 0L, "r")),
                Arguments.of("installed 4", (Runnable) () -> bounded(1, 100, 100, 50, 4, 0, 0, 0, 0, 0L, "r")),
                Arguments.of("effective differs", (Runnable) () -> bounded(1, 100, 100, 50, 2, 1, 0, 0, 0, 0L, "r")),
                Arguments.of("effective without installed", (Runnable) () -> bounded(1, 100, 100, 50, 0, 1, 0, 0, 0, 0L, "r")),
                Arguments.of("advertising -1", (Runnable) () -> bounded(1, 100, 100, 50, 0, 0, -1, 0, 0, 0L, "r")),
                Arguments.of("advertising 4", (Runnable) () -> bounded(1, 100, 100, 50, 0, 0, 4, 0, 0, 0L, "r")),
                Arguments.of("partner -1", (Runnable) () -> bounded(1, 100, 100, 50, 0, 0, 0, -1, 0, 0L, "r")),
                Arguments.of("partner 5", (Runnable) () -> bounded(1, 100, 100, 50, 0, 0, 0, 5, 0, 0L, "r")),
                Arguments.of("epoch -1", (Runnable) () -> bounded(1, 100, 100, 50, 0, 0, 0, 0, -1, 0L, "r")),
                Arguments.of("revision -1", (Runnable) () -> bounded(1, 100, 100, 50, 0, 0, 0, 0, 0, -1L, "r")),
                Arguments.of("revision max", (Runnable) () -> bounded(1, 100, 100, 50, 0, 0, 0, 0, 0, Long.MAX_VALUE, "r")),
                Arguments.of("region blank", (Runnable) () -> bounded(1, 100, 100, 50, 0, 0, 0, 0, 0, 0L, "   ")),
                Arguments.of("region empty", (Runnable) () -> bounded(1, 100, 100, 50, 0, 0, 0, 0, 0, 0L, "")),
                Arguments.of("region 129", (Runnable) () -> bounded(1, 100, 100, 50, 0, 0, 0, 0, 0, 0L, "r".repeat(129))),
                Arguments.of("region null", (Runnable) () -> bounded(1, 100, 100, 50, 0, 0, 0, 0, 0, 0L, null)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("homeBounds")
    void homeNumbersOutsideTheirLimitsAreRefused(String name, Runnable build) {
        var refusal = assertThrows(IllegalArgumentException.class, build::run, name);
        assertEquals("Invalid housing snapshot home", refusal.getMessage());
    }

    @Test void participantTenancyKeepsTheNeverHousedSentinelOnly() {
        var standard = policy("town", "flat", HousingPolicy.Tier.STANDARD, 3_000, 60);
        assertEquals(-1L, new Participant(member(1, standard), -1L, DAY, Status.READY).tenancyRevision());
        assertNull(new Participant(member(1, standard), -1L, DAY, Status.READY).origin());
        assertEquals(Long.MAX_VALUE - 1, new Participant(member(1, standard), Long.MAX_VALUE - 1, DAY, Status.READY).tenancyRevision());
        assertThrows(IllegalArgumentException.class, () -> new Participant(member(1, standard), -2L, DAY, Status.READY));
        assertThrows(IllegalArgumentException.class, () -> new Participant(member(1, standard), Long.MAX_VALUE, DAY, Status.READY));
        assertThrows(NullPointerException.class, () -> new Participant(member(1, standard), 0L, null, Status.READY));
        assertThrows(NullPointerException.class, () -> new Participant(member(1, standard), 0L, DAY, null));
        assertThrows(NullPointerException.class, () -> new Participant(null, 0L, DAY, Status.READY));
    }

    @ParameterizedTest
    @EnumSource(Status.class)
    void everyStatusOtherThanReadyIsNotASeekerWithPremiumOff(Status status) {
        var standard = policy("town", "flat", HousingPolicy.Tier.STANDARD, 3_000, 60);
        var snapshot = new HousingMarketSnapshot(DAY, CAPTURED, POLICY, List.of(),
                List.of(new Participant(member(1, standard), -1L, DAY, status)), List.of());
        assertEquals(status == Status.READY ? 1 : 0, snapshot.seekers(false).size());
        assertEquals(status == Status.READY || status == Status.PREMIUM_HELD ? 1 : 0, snapshot.seekers(true).size());
        assertEquals(Collections.emptyList(), new HousingMarketSnapshot(DAY, CAPTURED, POLICY, List.of(), List.of(),
                List.of()).seekers(true));
    }
}

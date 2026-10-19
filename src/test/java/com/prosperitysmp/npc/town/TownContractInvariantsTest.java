package com.prosperitysmp.npc.town;

import com.prosperitysmp.npc.resident.RelationshipMemory.Stage;
import com.prosperitysmp.npc.town.TownContract.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Every persisted-contract invariant, one rule per test, so a dropped check shows up by name. */
class TownContractInvariantsTest {
    private static final UUID MARA = new UUID(0, 1);
    private static final UUID NICO = new UUID(0, 2);
    private static final UUID PLAYER = new UUID(0, 3);
    private static final UUID CONTRACT = new UUID(0, 4);
    private static final UUID RUN = new UUID(0, 5);
    private static final Instant AT = Instant.parse("2026-10-14T10:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 14);
    private static final Instant EXPIRES = AT.plusSeconds(30 * 60L);
    private static final ContractDefinition SUPPLY = new ContractDefinition("mara-wall-stone",
            ContractDefinition.Kind.SUPPLY, "Wall stone", "Bring me cobblestone.", MARA, MARA, "COBBLESTONE", 64,
            Stage.STRANGER, 30);
    private static final ContractDefinition PARTNER = ContractDefinition.partnership(true, MARA, "IRON_INGOT", 16, Stage.TRUSTED);

    private static TownContract build(ContractDefinition definition, int slot, Instant accepted, Instant expires,
                                      Status status, UUID run, Instant closed, LocalDate completed) {
        return new TownContract(CONTRACT, PLAYER, definition, accepted, DAY, slot, expires, status, run, closed, completed);
    }

    private static TownContract supply(Status status, UUID run, Instant closed, LocalDate completed) {
        return build(SUPPLY, 1, AT, EXPIRES, status, run, closed, completed);
    }

    private static TownContract active() { return supply(Status.ACTIVE, null, null, null); }

    @Test void anActiveContractKeepsEveryField() {
        TownContract t = active();
        assertEquals(CONTRACT, t.contractId());
        assertEquals(PLAYER, t.playerId());
        assertSame(SUPPLY, t.definition());
        assertEquals(AT, t.acceptedAt());
        assertEquals(DAY, t.acceptedEconomyDay());
        assertEquals(1, t.daySlot());
        assertEquals(EXPIRES, t.expiresAt());
        assertEquals(Status.ACTIVE, t.status());
        assertNull(t.runId());
        assertNull(t.closedAt());
        assertNull(t.completedEconomyDay());
    }

    @Test void theOpenStatusesAreExactlyActiveHandoverIntentAndAmbiguous() {
        assertEquals(EnumSet.of(Status.ACTIVE, Status.HANDOVER_INTENT, Status.AMBIGUOUS), TownContract.OPEN);
    }

    @ParameterizedTest @EnumSource(Status.class)
    void openIsTrueOnlyForTheOpenStatuses(Status status) {
        boolean open = status == Status.ACTIVE || status == Status.HANDOVER_INTENT || status == Status.AMBIGUOUS;
        TownContract t = switch (status) {
            case ACTIVE -> supply(status, null, null, null);
            case HANDOVER_INTENT, AMBIGUOUS -> supply(status, RUN, null, null);
            case COMPLETED, RESOLVED_CONSUMED -> supply(status, RUN, AT.plusSeconds(60), DAY);
            case RESOLVED_NOT_CONSUMED -> supply(status, RUN, AT.plusSeconds(60), null);
            case CANCELLED -> supply(status, null, AT.plusSeconds(60), null);
            case EXPIRED -> supply(status, null, EXPIRES, null);
        };
        assertEquals(open, t.open(), status.name());
    }

    @Test void theExpiryMustBeTheAcceptanceTimePlusTheDurationSnapshot() {
        assertDoesNotThrow(() -> build(SUPPLY, 1, AT, AT.plusSeconds(1800), Status.ACTIVE, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> build(SUPPLY, 1, AT, AT.plusSeconds(1800).plusMillis(1), Status.ACTIVE, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> build(SUPPLY, 1, AT, AT.plusSeconds(1800).minusMillis(1), Status.ACTIVE, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> build(SUPPLY, 1, AT, AT.plusSeconds(1799), Status.ACTIVE, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> build(SUPPLY, 1, AT, AT, Status.ACTIVE, null, null, null));
    }

    @Test void theDurationSnapshotIsTheDefinitionsNotTheCallers() {
        ContractDefinition day = new ContractDefinition("mara-wall-stone", ContractDefinition.Kind.SUPPLY, "Wall stone",
                "Bring me cobblestone.", MARA, MARA, "COBBLESTONE", 64, Stage.STRANGER, 1440);
        assertDoesNotThrow(() -> build(day, 1, AT, AT.plusSeconds(86_400), Status.ACTIVE, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> build(day, 1, AT, EXPIRES, Status.ACTIVE, null, null, null));
    }

    @Test void subMillisecondNanosAreTruncatedNotRounded() {
        Instant accepted = Instant.parse("2026-10-14T10:00:00.123999999Z");
        TownContract t = build(SUPPLY, 1, accepted, accepted.plusSeconds(1800), Status.ACTIVE, null, null, null);
        assertEquals(Instant.parse("2026-10-14T10:00:00.123Z"), t.acceptedAt());
        assertEquals(Instant.parse("2026-10-14T10:30:00.123Z"), t.expiresAt());
    }

    @Test void twoContractsThatDifferOnlyBelowAMillisecondAreEqual() {
        Instant a = Instant.parse("2026-10-14T10:00:00.1230001Z");
        Instant b = Instant.parse("2026-10-14T10:00:00.1239999Z");
        assertEquals(build(SUPPLY, 1, a, a.plusSeconds(1800), Status.ACTIVE, null, null, null),
                build(SUPPLY, 1, b, b.plusSeconds(1800), Status.ACTIVE, null, null, null));
    }

    @Test void aClosingTimeIsTruncatedToTheMillisecondToo() {
        Instant closed = AT.plusSeconds(60).plusNanos(999_999);
        assertEquals(AT.plusSeconds(60), supply(Status.CANCELLED, null, closed, null).closedAt());
    }

    @Test void anAcceptanceTimeBeyondTheEpochMillisecondRangeIsRefusedAsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> build(SUPPLY, 1, Instant.MAX, Instant.MAX, Status.ACTIVE, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> build(SUPPLY, 1, Instant.MIN, Instant.MIN, Status.ACTIVE, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> build(SUPPLY, 1, AT, Instant.MAX, Status.ACTIVE, null, null, null));
    }

    @Test void aClosingTimeBeyondTheEpochMillisecondRangeIsRefusedAsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> supply(Status.CANCELLED, null, Instant.MAX, null));
    }

    @ParameterizedTest @ValueSource(ints = {-1, 4, 5, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void aDaySlotOutsideZeroToThreeIsRefused(int slot) {
        assertThrows(IllegalArgumentException.class, () -> build(SUPPLY, slot, AT, EXPIRES, Status.ACTIVE, null, null, null));
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 3})
    void anOrdinaryContractTakesSlotOneToThree(int slot) {
        assertEquals(slot, build(SUPPLY, slot, AT, EXPIRES, Status.ACTIVE, null, null, null).daySlot());
    }

    @Test void anOrdinaryContractCannotUseTheNoSlotValue() {
        assertThrows(IllegalArgumentException.class, () -> build(SUPPLY, 0, AT, EXPIRES, Status.ACTIVE, null, null, null));
        ContractDefinition deliver = new ContractDefinition("nico-coal-run", ContractDefinition.Kind.DELIVER, "Coal run",
                "Take coal to Nico.", MARA, NICO, "COAL", 16, Stage.STRANGER, 30);
        assertThrows(IllegalArgumentException.class, () -> build(deliver, 0, AT, EXPIRES, Status.ACTIVE, null, null, null));
        assertEquals(2, build(deliver, 2, AT, EXPIRES, Status.ACTIVE, null, null, null).daySlot());
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 3, -1, 4})
    void aPartnershipHandOverTakesNoDailySlot(int slot) {
        Instant expires = AT.plusSeconds(10 * 60L);
        assertThrows(IllegalArgumentException.class, () -> build(PARTNER, slot, AT, expires, Status.ACTIVE, null, null, null));
    }

    @Test void aPartnershipHandOverWithSlotZeroIsAccepted() {
        TownContract t = build(PARTNER, 0, AT, AT.plusSeconds(600), Status.ACTIVE, null, null, null);
        assertEquals(0, t.daySlot());
        assertTrue(t.definition().partnerStart());
    }

    @ParameterizedTest @EnumSource(value = Status.class, names = {"HANDOVER_INTENT", "COMPLETED", "AMBIGUOUS",
            "RESOLVED_CONSUMED", "RESOLVED_NOT_CONSUMED"})
    void aStatusThatCarriesARunIdRefusesAMissingOne(Status status) {
        assertThrows(IllegalArgumentException.class, () -> supply(status, null,
                TownContract.OPEN.contains(status) ? null : AT.plusSeconds(60),
                status == Status.COMPLETED || status == Status.RESOLVED_CONSUMED ? DAY : null));
    }

    @ParameterizedTest @EnumSource(value = Status.class, names = {"ACTIVE", "CANCELLED", "EXPIRED"})
    void aStatusWithoutAHandOverRefusesARunId(Status status) {
        Instant closed = switch (status) {
            case ACTIVE -> null;
            case EXPIRED -> EXPIRES;
            default -> AT.plusSeconds(60);
        };
        assertThrows(IllegalArgumentException.class, () -> supply(status, RUN, closed, null));
    }

    @ParameterizedTest @EnumSource(value = Status.class, names = {"ACTIVE", "HANDOVER_INTENT", "AMBIGUOUS"})
    void anOpenContractCarriesNoClosingTime(Status status) {
        UUID run = status == Status.ACTIVE ? null : RUN;
        assertThrows(IllegalArgumentException.class, () -> supply(status, run, AT.plusSeconds(1), null));
        assertDoesNotThrow(() -> supply(status, run, null, null));
    }

    @ParameterizedTest @EnumSource(value = Status.class, names = {"ACTIVE", "HANDOVER_INTENT", "AMBIGUOUS"})
    void anOpenContractCarriesNoCompletionDay(Status status) {
        UUID run = status == Status.ACTIVE ? null : RUN;
        assertThrows(IllegalArgumentException.class, () -> supply(status, run, null, DAY));
    }

    @ParameterizedTest @EnumSource(value = Status.class, names = {"COMPLETED", "CANCELLED", "EXPIRED", "RESOLVED_CONSUMED",
            "RESOLVED_NOT_CONSUMED"})
    void aClosedContractNeedsAClosingTime(Status status) {
        UUID run = status == Status.CANCELLED || status == Status.EXPIRED ? null : RUN;
        LocalDate completed = status == Status.COMPLETED || status == Status.RESOLVED_CONSUMED ? DAY : null;
        assertThrows(NullPointerException.class, () -> supply(status, run, null, completed));
    }

    @Test void aContractCannotCloseBeforeItWasAccepted() {
        assertThrows(IllegalArgumentException.class, () -> supply(Status.CANCELLED, null, AT.minusMillis(1), null));
        assertThrows(IllegalArgumentException.class, () -> supply(Status.COMPLETED, RUN, AT.minusSeconds(1), DAY));
    }

    @Test void aContractMayCloseInTheSameMillisecondItWasAccepted() {
        assertEquals(AT, supply(Status.CANCELLED, null, AT, null).closedAt());
    }

    @Test void anExpiredContractClosesExactlyAtItsDeadline() {
        assertEquals(EXPIRES, supply(Status.EXPIRED, null, EXPIRES, null).closedAt());
        assertThrows(IllegalArgumentException.class, () -> supply(Status.EXPIRED, null, EXPIRES.minusMillis(1), null));
        assertThrows(IllegalArgumentException.class, () -> supply(Status.EXPIRED, null, EXPIRES.plusMillis(1), null));
    }

    @Test void aCancelledContractMayCloseAfterItsDeadlineToo() {
        assertEquals(EXPIRES.plusSeconds(5), supply(Status.CANCELLED, null, EXPIRES.plusSeconds(5), null).closedAt());
    }

    @ParameterizedTest @EnumSource(value = Status.class, names = {"COMPLETED", "RESOLVED_CONSUMED"})
    void aFinishedContractNeedsACompletionDay(Status status) {
        assertThrows(IllegalArgumentException.class, () -> supply(status, RUN, AT.plusSeconds(60), null));
    }

    @ParameterizedTest @EnumSource(value = Status.class, names = {"COMPLETED", "RESOLVED_CONSUMED"})
    void aCompletionDayBeforeTheAcceptanceDayIsRefused(Status status) {
        assertThrows(IllegalArgumentException.class, () -> supply(status, RUN, AT.plusSeconds(60), DAY.minusDays(1)));
    }

    @ParameterizedTest @EnumSource(value = Status.class, names = {"COMPLETED", "RESOLVED_CONSUMED"})
    void aCompletionDayOnOrAfterTheAcceptanceDayIsKept(Status status) {
        assertEquals(DAY, supply(status, RUN, AT.plusSeconds(60), DAY).completedEconomyDay());
        assertEquals(DAY.plusDays(1), supply(status, RUN, AT.plusSeconds(60), DAY.plusDays(1)).completedEconomyDay());
    }

    @ParameterizedTest @EnumSource(value = Status.class, names = {"CANCELLED", "EXPIRED", "RESOLVED_NOT_CONSUMED"})
    void anUnfinishedClosedContractCarriesNoCompletionDay(Status status) {
        UUID run = status == Status.RESOLVED_NOT_CONSUMED ? RUN : null;
        Instant closed = status == Status.EXPIRED ? EXPIRES : AT.plusSeconds(60);
        assertThrows(IllegalArgumentException.class, () -> supply(status, run, closed, DAY));
        assertNull(supply(status, run, closed, null).completedEconomyDay());
    }

    @Test void aMissingIdentityFieldIsRefused() {
        assertThrows(NullPointerException.class, () -> new TownContract(null, PLAYER, SUPPLY, AT, DAY, 1, EXPIRES, Status.ACTIVE, null, null, null));
        assertThrows(NullPointerException.class, () -> new TownContract(CONTRACT, null, SUPPLY, AT, DAY, 1, EXPIRES, Status.ACTIVE, null, null, null));
        assertThrows(NullPointerException.class, () -> new TownContract(CONTRACT, PLAYER, null, AT, DAY, 1, EXPIRES, Status.ACTIVE, null, null, null));
        assertThrows(NullPointerException.class, () -> new TownContract(CONTRACT, PLAYER, SUPPLY, AT, null, 1, EXPIRES, Status.ACTIVE, null, null, null));
        assertThrows(NullPointerException.class, () -> new TownContract(CONTRACT, PLAYER, SUPPLY, AT, DAY, 1, EXPIRES, null, null, null, null));
        assertThrows(NullPointerException.class, () -> new TownContract(CONTRACT, PLAYER, SUPPLY, null, DAY, 1, EXPIRES, Status.ACTIVE, null, null, null));
        assertThrows(NullPointerException.class, () -> new TownContract(CONTRACT, PLAYER, SUPPLY, AT, DAY, 1, null, Status.ACTIVE, null, null, null));
    }

    @Test void withMovesTheStatusAndKeepsEveryOtherField() {
        TownContract moved = active().with(Status.COMPLETED, RUN, AT.plusSeconds(90), DAY.plusDays(1));
        assertEquals(Status.COMPLETED, moved.status());
        assertEquals(RUN, moved.runId());
        assertEquals(AT.plusSeconds(90), moved.closedAt());
        assertEquals(DAY.plusDays(1), moved.completedEconomyDay());
        assertEquals(CONTRACT, moved.contractId());
        assertEquals(PLAYER, moved.playerId());
        assertSame(SUPPLY, moved.definition());
        assertEquals(AT, moved.acceptedAt());
        assertEquals(DAY, moved.acceptedEconomyDay());
        assertEquals(1, moved.daySlot());
        assertEquals(EXPIRES, moved.expiresAt());
    }

    @Test void withStillRunsTheInvariants() {
        TownContract t = active();
        assertThrows(IllegalArgumentException.class, () -> t.with(Status.COMPLETED, null, AT.plusSeconds(90), DAY));
        assertThrows(IllegalArgumentException.class, () -> t.with(Status.EXPIRED, null, EXPIRES.minusSeconds(1), null));
        assertThrows(IllegalArgumentException.class, () -> t.with(Status.HANDOVER_INTENT, RUN, AT.plusSeconds(1), null));
    }

    @Test void withAHandOverIntentStaysOpenAndKeepsTheRun() {
        TownContract intent = active().with(Status.HANDOVER_INTENT, RUN, null, null);
        assertTrue(intent.open());
        assertEquals(RUN, intent.runId());
        assertNull(intent.closedAt());
    }

    @Test void normalizeKeepsTheMillisecondAndDropsTheRest() {
        assertEquals(Instant.parse("2026-10-14T10:00:00.001Z"),
                TownContract.normalize(Instant.parse("2026-10-14T10:00:00.001999999Z")));
        assertEquals(Instant.EPOCH, TownContract.normalize(Instant.EPOCH));
        assertEquals(Instant.parse("1969-12-31T23:59:59.999Z"),
                TownContract.normalize(Instant.parse("1969-12-31T23:59:59.9999999Z")));
        assertThrows(NullPointerException.class, () -> TownContract.normalize(null));
    }
}

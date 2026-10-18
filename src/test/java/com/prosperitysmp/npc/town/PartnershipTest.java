package com.prosperitysmp.npc.town;

import com.prosperitysmp.npc.town.Partnership.EndReason;
import com.prosperitysmp.npc.town.Partnership.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The stored partnership record: its day arithmetic and the rules that tie status, reason and end time together. */
class PartnershipTest {
    private static final UUID ID = new UUID(0, 1);
    private static final UUID OWNER = new UUID(0, 2);
    private static final UUID RESIDENT = new UUID(0, 3);
    private static final UUID CONTRACT = new UUID(0, 4);
    private static final LocalDate START = LocalDate.of(2026, 10, 1);
    private static final LocalDate LAST = LocalDate.of(2026, 10, 7);
    private static final PartnerPerks PERKS = new PartnerPerks(20, 1);
    private static final Instant ENDED = Instant.parse("2026-10-04T12:00:00Z");

    private static Partnership active(LocalDate started, LocalDate last) {
        return new Partnership(ID, OWNER, RESIDENT, started, last, PERKS, Status.ACTIVE, null, null, CONTRACT, 1);
    }

    private static Partnership active() { return active(START, LAST); }

    private static Partnership ended(EndReason reason) {
        return new Partnership(ID, OWNER, RESIDENT, START, LAST, PERKS, Status.ENDED, reason, ENDED, CONTRACT, 2);
    }

    @Test void anActivePartnershipKeepsEveryField() {
        Partnership p = active();
        assertEquals(ID, p.partnershipId());
        assertEquals(OWNER, p.ownerId());
        assertEquals(RESIDENT, p.residentId());
        assertEquals(START, p.startedDay());
        assertEquals(LAST, p.lastActiveDay());
        assertEquals(PERKS, p.perks());
        assertEquals(Status.ACTIVE, p.status());
        assertNull(p.endReason());
        assertNull(p.endedAt());
        assertEquals(CONTRACT, p.startContractId());
        assertEquals(1, p.revision());
    }

    @Test void aOneDayTermIsAllowed() {
        assertDoesNotThrow(() -> active(START, START));
        assertTrue(active(START, START).activeOn(START));
        assertFalse(active(START, START).activeOn(START.plusDays(1)));
    }

    @Test void theLastDayCannotPrecedeTheFirst() {
        assertThrows(IllegalArgumentException.class, () -> active(START, START.minusDays(1)));
    }

    @ParameterizedTest @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void aRevisionBelowOneIsRefused(long revision) {
        assertThrows(IllegalArgumentException.class, () -> new Partnership(ID, OWNER, RESIDENT, START, LAST, PERKS,
                Status.ACTIVE, null, null, CONTRACT, revision));
    }

    @Test void aLargeRevisionIsAllowed() {
        assertEquals(Long.MAX_VALUE, new Partnership(ID, OWNER, RESIDENT, START, LAST, PERKS, Status.ACTIVE, null, null,
                CONTRACT, Long.MAX_VALUE).revision());
    }

    @Test void aPartnershipMustCarryAtLeastOnePerk() {
        assertThrows(IllegalArgumentException.class, () -> new Partnership(ID, OWNER, RESIDENT, START, LAST,
                PartnerPerks.NONE, Status.ACTIVE, null, null, CONTRACT, 1));
        assertDoesNotThrow(() -> new Partnership(ID, OWNER, RESIDENT, START, LAST, new PartnerPerks(0, 1),
                Status.ACTIVE, null, null, CONTRACT, 1));
        assertDoesNotThrow(() -> new Partnership(ID, OWNER, RESIDENT, START, LAST, new PartnerPerks(1, 0),
                Status.ACTIVE, null, null, CONTRACT, 1));
    }

    @ParameterizedTest @EnumSource(EndReason.class)
    void anActivePartnershipHasNoEndReason(EndReason reason) {
        assertThrows(IllegalArgumentException.class, () -> new Partnership(ID, OWNER, RESIDENT, START, LAST, PERKS,
                Status.ACTIVE, reason, null, CONTRACT, 1));
        assertThrows(IllegalArgumentException.class, () -> new Partnership(ID, OWNER, RESIDENT, START, LAST, PERKS,
                Status.ACTIVE, reason, ENDED, CONTRACT, 1));
    }

    @Test void anActivePartnershipHasNoEndTime() {
        assertThrows(IllegalArgumentException.class, () -> new Partnership(ID, OWNER, RESIDENT, START, LAST, PERKS,
                Status.ACTIVE, null, ENDED, CONTRACT, 1));
    }

    @ParameterizedTest @EnumSource(EndReason.class)
    void anEndedPartnershipNeedsItsReasonAndItsEndTime(EndReason reason) {
        assertEquals(reason, ended(reason).endReason());
        assertEquals(ENDED, ended(reason).endedAt());
        assertThrows(IllegalArgumentException.class, () -> new Partnership(ID, OWNER, RESIDENT, START, LAST, PERKS,
                Status.ENDED, reason, null, CONTRACT, 2));
    }

    @Test void anEndedPartnershipWithoutAReasonIsRefusedWithOrWithoutAnEndTime() {
        assertThrows(IllegalArgumentException.class, () -> new Partnership(ID, OWNER, RESIDENT, START, LAST, PERKS,
                Status.ENDED, null, ENDED, CONTRACT, 2));
        assertThrows(IllegalArgumentException.class, () -> new Partnership(ID, OWNER, RESIDENT, START, LAST, PERKS,
                Status.ENDED, null, null, CONTRACT, 2));
    }

    @Test void aMissingRequiredFieldIsRefused() {
        assertThrows(NullPointerException.class, () -> new Partnership(null, OWNER, RESIDENT, START, LAST, PERKS, Status.ACTIVE, null, null, CONTRACT, 1));
        assertThrows(NullPointerException.class, () -> new Partnership(ID, null, RESIDENT, START, LAST, PERKS, Status.ACTIVE, null, null, CONTRACT, 1));
        assertThrows(NullPointerException.class, () -> new Partnership(ID, OWNER, null, START, LAST, PERKS, Status.ACTIVE, null, null, CONTRACT, 1));
        assertThrows(NullPointerException.class, () -> new Partnership(ID, OWNER, RESIDENT, null, LAST, PERKS, Status.ACTIVE, null, null, CONTRACT, 1));
        assertThrows(NullPointerException.class, () -> new Partnership(ID, OWNER, RESIDENT, START, null, PERKS, Status.ACTIVE, null, null, CONTRACT, 1));
        assertThrows(NullPointerException.class, () -> new Partnership(ID, OWNER, RESIDENT, START, LAST, null, Status.ACTIVE, null, null, CONTRACT, 1));
        assertThrows(NullPointerException.class, () -> new Partnership(ID, OWNER, RESIDENT, START, LAST, PERKS, null, null, null, CONTRACT, 1));
        assertThrows(NullPointerException.class, () -> new Partnership(ID, OWNER, RESIDENT, START, LAST, PERKS, Status.ACTIVE, null, null, null, 1));
    }

    @Test void activeThroughTheLastDayAndNotAfter() {
        Partnership p = active();
        assertTrue(p.activeOn(LAST));
        assertTrue(p.activeOn(LAST.minusDays(1)));
        assertFalse(p.activeOn(LAST.plusDays(1)));
        assertFalse(p.activeOn(LocalDate.MAX));
    }

    @Test void activeOnDoesNotLookAtTheStartDay() {
        assertTrue(active().activeOn(START.minusDays(1)));
        assertTrue(active().activeOn(LocalDate.MIN));
    }

    @ParameterizedTest @EnumSource(EndReason.class)
    void anEndedPartnershipIsNeverActive(EndReason reason) {
        assertFalse(ended(reason).activeOn(START));
        assertFalse(ended(reason).activeOn(LAST));
        assertFalse(ended(reason).activeOn(ENDED.atZone(java.time.ZoneOffset.UTC).toLocalDate()));
    }

    @Test void renewableInsideTheLastThreeDaysOfATermWithAThreeDayWindow() {
        Partnership p = active();
        assertFalse(p.renewableOn(LAST.minusDays(3), 3));
        assertTrue(p.renewableOn(LAST.minusDays(2), 3));
        assertTrue(p.renewableOn(LAST.minusDays(1), 3));
        assertTrue(p.renewableOn(LAST, 3));
        assertFalse(p.renewableOn(LAST.plusDays(1), 3));
    }

    @Test void aOneDayWindowIsOnlyTheLastDay() {
        Partnership p = active();
        assertFalse(p.renewableOn(LAST.minusDays(1), 1));
        assertTrue(p.renewableOn(LAST, 1));
        assertFalse(p.renewableOn(LAST.plusDays(1), 1));
    }

    @Test void aWindowOfZeroOrLessNeverOpensBecauseItStartsAfterTheLastDay() {
        Partnership p = active();
        for (int window : new int[] {0, -1, -7, Integer.MIN_VALUE + 1}) {
            assertFalse(p.renewableOn(LAST, window), "window " + window);
            assertFalse(p.renewableOn(LAST.minusDays(1), window), "window " + window);
        }
    }

    @Test void aWindowLongerThanTheTermCoversEveryActiveDay() {
        Partnership p = active();
        assertTrue(p.renewableOn(START, 30));
        assertTrue(p.renewableOn(LAST, 30));
        assertFalse(p.renewableOn(LAST.plusDays(1), 30));
    }

    @Test void aWindowEqualToTheTermLengthStartsOnTheFirstDay() {
        Partnership p = active();
        assertTrue(p.renewableOn(START, 7));
        assertFalse(active(START.plusDays(1), LAST).renewableOn(START, 6));
        assertTrue(active(START.plusDays(1), LAST).renewableOn(START.plusDays(1), 6));
    }

    @ParameterizedTest @EnumSource(EndReason.class)
    void anEndedPartnershipIsNeverRenewable(EndReason reason) {
        assertFalse(ended(reason).renewableOn(LAST, 3));
        assertFalse(ended(reason).renewableOn(LAST.minusDays(1), 30));
    }
}

package com.prosperitysmp.npc.api.charge;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The home and period a meter is asked about: a period may be empty but never runs backwards. */
class MeterQueryTest {
    private static final UUID WORLD = new UUID(5, 6);
    private static final UUID OWNER = new UUID(7, 8);
    private static final Instant START = Instant.parse("2026-10-18T09:00:00Z");

    @Test void aQueryHandsBackTheSameObjectsItWasBuiltFrom() {
        Instant end = START.plusSeconds(3600);
        var query = MeterQuery.of(WORLD, "home-7", OWNER, START, end);
        assertSame(WORLD, query.world());
        assertEquals("home-7", query.regionId());
        assertSame(OWNER, query.owner());
        assertSame(START, query.periodStart());
        assertSame(end, query.periodEnd());
    }

    @Test void aPeriodEndingOneNanosecondBeforeItStartsIsRefused() {
        var thrown = assertThrows(IllegalArgumentException.class,
                () -> MeterQuery.of(WORLD, "home-7", OWNER, START, START.minusNanos(1)));
        assertEquals("period ends before it starts", thrown.getMessage());
    }

    @Test void aPeriodEndingOneNanosecondAfterItStartsIsAccepted() {
        assertEquals(START.plusNanos(1), MeterQuery.of(WORLD, "home-7", OWNER, START, START.plusNanos(1)).periodEnd());
    }

    @Test void anEmptyPeriodIsAccepted() {
        var query = MeterQuery.of(WORLD, "home-7", OWNER, START, START);
        assertEquals(query.periodStart(), query.periodEnd());
    }

    @Test void theSameMomentWrittenTwoWaysIsAnEmptyPeriodNotABackwardOne() {
        var query = MeterQuery.of(WORLD, "home-7", OWNER, Instant.ofEpochSecond(5), Instant.ofEpochMilli(5000));
        assertEquals(5000, query.periodEnd().toEpochMilli());
    }

    @Test void theWidestPeriodIsAcceptedAndItsReverseIsNot() {
        var widest = MeterQuery.of(WORLD, "home-7", OWNER, Instant.MIN, Instant.MAX);
        assertEquals(Instant.MIN, widest.periodStart());
        assertEquals(Instant.MAX, widest.periodEnd());
        assertThrows(IllegalArgumentException.class, () -> MeterQuery.of(WORLD, "home-7", OWNER, Instant.MAX, Instant.MIN));
    }

    @Test void aPeriodBeforeTheEpochIsJudgedByOrderNotBySign() {
        Instant earlier = Instant.parse("1969-12-31T23:59:58Z"), later = Instant.parse("1969-12-31T23:59:59Z");
        assertEquals(later, MeterQuery.of(WORLD, "home-7", OWNER, earlier, later).periodEnd());
        assertThrows(IllegalArgumentException.class, () -> MeterQuery.of(WORLD, "home-7", OWNER, later, earlier));
    }

    @Test void aLongRegionIdIsKeptWhole() {
        String id = "r".repeat(128);
        assertEquals(id, MeterQuery.of(WORLD, id, OWNER, START, START).regionId());
    }

    @Test void aMissingPartIsRefusedBeforeThePeriodIsCompared() {
        assertThrows(NullPointerException.class, () -> MeterQuery.of(WORLD, "home-7", OWNER, START.plusSeconds(1), null));
        assertThrows(NullPointerException.class, () -> MeterQuery.of(WORLD, "home-7", OWNER, null, START));
    }

    @Test void twoQueriesAreIndependent() {
        var a = MeterQuery.of(WORLD, "a", OWNER, START, START.plusSeconds(1));
        var b = MeterQuery.of(new UUID(9, 9), "b", new UUID(10, 10), START.plusSeconds(5), START.plusSeconds(9));
        assertEquals("a", a.regionId());
        assertEquals(WORLD, a.world());
        assertEquals("b", b.regionId());
        assertEquals(new UUID(10, 10), b.owner());
        assertEquals(START.plusSeconds(9), b.periodEnd());
    }
}

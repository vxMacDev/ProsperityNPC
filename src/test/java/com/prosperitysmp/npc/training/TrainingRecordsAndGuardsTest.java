package com.prosperitysmp.npc.training;

import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.training.TrainingRepository.Code;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** What the training repository and its result records refuse before any SQL, and the shape of its answers. */
class TrainingRecordsAndGuardsTest {
    private static final UUID PLAYER = new UUID(0, 1);
    private static final UUID TRAINER = new UUID(0, 2);
    private static final UUID SESSION = new UUID(0, 3);
    private static final Instant AT = Instant.parse("2026-10-14T15:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 14);

    private final AtomicInteger opened = new AtomicInteger();
    private final TrainingRepository down = new TrainingRepository(() -> {
        opened.incrementAndGet();
        throw new SQLException("database down");
    });

    @Test void theCodesAreInTheDocumentedOrder() {
        assertEquals("[GRANTED, ALREADY_ACTIVE, ACTIVE_CONFLICT, DAILY_LIMIT, UNKNOWN_TRAINER, RELATIONSHIP_TOO_LOW, TIME_REGRESSION, "
                + "DISABLED, UNAVAILABLE, NOT_EMPLOYED, UNAUTHORIZED, BUSY, UNCERTAIN]", Arrays.toString(Code.values()));
    }

    @Test void theRuntimeStatusesAreInTheDocumentedOrder() {
        assertEquals("[UNKNOWN, LOADING, NONE, ACTIVE, EXPIRED, INELIGIBLE, DISABLED, UNAVAILABLE, UNCERTAIN]",
                Arrays.toString(TrainingRuntime.Status.values()));
    }

    @ParameterizedTest @EnumSource(Code.class)
    void aDeniedResultCarriesItsCodeAndNoGrant(Code code) {
        TrainingRepository.Result result = TrainingRepository.Result.denied(code);
        assertEquals(code, result.code());
        assertEquals(Optional.empty(), result.grant());
    }

    @Test void aResultNeedsItsCodeAndItsGrantOption() {
        assertThrows(NullPointerException.class, () -> new TrainingRepository.Result(null, Optional.empty()));
        assertThrows(NullPointerException.class, () -> new TrainingRepository.Result(Code.GRANTED, null));
        assertThrows(NullPointerException.class, () -> TrainingRepository.Result.denied(null));
    }

    @Test void aRuntimeViewNeedsItsStatusAndItsGrantOption() {
        assertThrows(NullPointerException.class, () -> new TrainingRuntime.View(null, Optional.empty()));
        assertThrows(NullPointerException.class, () -> new TrainingRuntime.View(TrainingRuntime.Status.NONE, null));
        assertEquals(Optional.empty(), new TrainingRuntime.View(TrainingRuntime.Status.NONE, Optional.empty()).grant());
    }

    @Test void theCachedPlayerLimitIsTwoFiftySix() {
        assertEquals(256, TrainingRuntime.MAX_CACHED_PLAYERS);
    }

    @Test void aRequestWithAnyMissingArgumentIsRefusedBeforeTheDatabaseIsTouched() {
        assertThrows(NullPointerException.class, () -> down.request(null, TRAINER, "miner", SESSION, AT, DAY));
        assertThrows(NullPointerException.class, () -> down.request(PLAYER, null, "miner", SESSION, AT, DAY));
        assertThrows(NullPointerException.class, () -> down.request(PLAYER, TRAINER, "miner", null, AT, DAY));
        assertThrows(NullPointerException.class, () -> down.request(PLAYER, TRAINER, "miner", SESSION, AT, null));
        assertThrows(NullPointerException.class, () -> down.request(PLAYER, TRAINER, null, SESSION, AT, DAY));
        assertThrows(NullPointerException.class, () -> down.request(PLAYER, TRAINER, "miner", SESSION, null, DAY));
        assertEquals(0, opened.get());
    }

    @Test void anInvalidJobNameIsRefusedBeforeTheDatabaseIsTouched() {
        for (String bad : new String[] {"", "bad job", "1abc", "miner;op", "a".repeat(49)})
            assertThrows(IllegalArgumentException.class, () -> down.request(PLAYER, TRAINER, bad, SESSION, AT, DAY), bad);
        assertEquals(0, opened.get());
    }

    @Test void aValidRequestOnAnUnreachableDatabaseFailsWithTheDatabaseError() {
        SQLException failure = assertThrows(SQLException.class, () -> down.request(PLAYER, TRAINER, "Miner", SESSION, AT, DAY));
        assertEquals("database down", failure.getMessage());
        assertEquals(1, opened.get());
    }

    @Test void readingTheCurrentGrantOnAnUnreachableDatabaseFailsWithTheDatabaseError() {
        SQLException failure = assertThrows(SQLException.class, () -> down.currentForPlayer(PLAYER));
        assertEquals("database down", failure.getMessage());
        assertThrows(NullPointerException.class, () -> down.currentForPlayer(null));
    }

    @Test void theRepositoryNeedsAConnectionSourceOrADatabase() {
        assertThrows(NullPointerException.class, () -> new TrainingRepository((Database) null));
    }
}

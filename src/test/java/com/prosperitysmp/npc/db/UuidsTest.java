package com.prosperitysmp.npc.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UuidsTest {
    @Test
    void theBytesAreTheMostSignificantHalfThenTheLeastBothBigEndian() {
        byte[] expected = {0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 2};
        assertArrayEquals(expected, Uuids.toBytes(new UUID(1, 2)));
    }

    @Test
    void theNilUuidIsSixteenZeroBytes() {
        assertArrayEquals(new byte[16], Uuids.toBytes(new UUID(0, 0)));
        assertEquals(new UUID(0, 0), Uuids.fromBytes(new byte[16]));
    }

    @Test
    void extremeHalvesKeepTheirSignBitsAndRoundTrip() {
        UUID extreme = new UUID(Long.MIN_VALUE, Long.MAX_VALUE);
        byte[] bytes = Uuids.toBytes(extreme);
        assertEquals((byte) 0x80, bytes[0]);
        assertEquals((byte) 0x7f, bytes[8]);
        assertEquals((byte) 0xff, bytes[15]);
        assertEquals(extreme, Uuids.fromBytes(bytes));
    }

    @Test
    void aTextUuidMatchesItsKnownByteLayout() {
        UUID id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        byte[] expected = {0x12, 0x3e, 0x45, 0x67, (byte) 0xe8, (byte) 0x9b, 0x12, (byte) 0xd3,
                (byte) 0xa4, 0x56, 0x42, 0x66, 0x14, 0x17, 0x40, 0x00};
        assertArrayEquals(expected, Uuids.toBytes(id));
        assertEquals(id, Uuids.fromBytes(expected));
    }

    @Test
    void manySeededUuidsRoundTrip() {
        Random random = new Random(20261018L);
        for (int i = 0; i < 500; i++) {
            UUID id = new UUID(random.nextLong(), random.nextLong());
            assertEquals(id, Uuids.fromBytes(Uuids.toBytes(id)));
        }
    }

    @Test
    void everyCallReturnsAFreshArray() {
        UUID id = new UUID(5, 6);
        byte[] first = Uuids.toBytes(id);
        first[0] = 99;
        assertNotSame(first, Uuids.toBytes(id));
        assertEquals(0, Uuids.toBytes(id)[0]);
    }

    @Test
    void readingDoesNotKeepTheCallersArray() {
        byte[] bytes = Uuids.toBytes(new UUID(5, 6));
        UUID read = Uuids.fromBytes(bytes);
        bytes[7] = 0;
        assertEquals(new UUID(5, 6), read);
    }

    @Test
    void nullBytesAreRefusedWithTheExactMessage() {
        var failure = assertThrows(IllegalArgumentException.class, () -> Uuids.fromBytes(null));
        assertEquals("expected 16 bytes, got null", failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 15, 17, 32})
    void bytesOfAnyOtherLengthAreRefusedWithTheExactMessage(int length) {
        var failure = assertThrows(IllegalArgumentException.class, () -> Uuids.fromBytes(new byte[length]));
        assertEquals("expected 16 bytes, got " + length, failure.getMessage());
    }

    @Test
    void aNullUuidIsRefused() {
        assertThrows(NullPointerException.class, () -> Uuids.toBytes(null));
    }
}

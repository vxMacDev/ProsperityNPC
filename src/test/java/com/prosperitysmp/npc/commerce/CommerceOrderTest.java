package com.prosperitysmp.npc.commerce;

import com.prosperitysmp.npc.commerce.ItemCustody.Source;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** A server-issued order is bounded, its id is derived from the issuance and its request hash binds every field. */
class CommerceOrderTest {
    private static final UUID NONCE = new UUID(1, 2);
    private static final UUID PLAYER = new UUID(3, 4);
    private static final UUID RESIDENT = new UUID(5, 6);
    private static final LocalDate DAY = LocalDate.of(2026, 10, 18);
    private static final Instant ISSUED = Instant.parse("2026-10-18T09:30:00.123Z");

    private static CommerceOrder order(Source source, String catalogue, String material, int quantity, long cost,
                                       LocalDate day, Instant issued, int delay) {
        return new CommerceOrder(CommerceOrder.idFor(NONCE, PLAYER, source, day), NONCE, PLAYER, RESIDENT, source,
                catalogue, material, quantity, cost, day, issued, delay);
    }

    private static CommerceOrder goods() {
        return order(Source.MERCHANT_GOODS, "supplies-stone", "STONE", 32, 1_444, DAY, ISSUED, 0);
    }

    @Test void theOrderIdIsTheFixedNameUuidOfTheIssuance() {
        assertEquals(UUID.fromString("1c07d2fa-cd13-3622-b7b1-f76e8a944c14"),
                CommerceOrder.idFor(NONCE, PLAYER, Source.MERCHANT_GOODS, DAY));
        assertEquals(UUID.fromString("97211d4e-4257-3bb5-9ad8-d88752bf7330"),
                CommerceOrder.idFor(NONCE, PLAYER, Source.WORKER_OUTPUT, DAY));
        assertEquals(3, CommerceOrder.idFor(NONCE, PLAYER, Source.MERCHANT_GOODS, DAY).version());
    }

    @Test void everyIssuanceInputChangesTheOrderId() {
        var ids = new HashSet<UUID>();
        ids.add(CommerceOrder.idFor(NONCE, PLAYER, Source.MERCHANT_GOODS, DAY));
        ids.add(CommerceOrder.idFor(new UUID(1, 3), PLAYER, Source.MERCHANT_GOODS, DAY));
        ids.add(CommerceOrder.idFor(NONCE, new UUID(3, 5), Source.MERCHANT_GOODS, DAY));
        ids.add(CommerceOrder.idFor(NONCE, PLAYER, Source.WORKER_OUTPUT, DAY));
        ids.add(CommerceOrder.idFor(NONCE, PLAYER, Source.MERCHANT_GOODS, DAY.plusDays(1)));
        ids.add(CommerceOrder.idFor(PLAYER, NONCE, Source.MERCHANT_GOODS, DAY));
        assertEquals(6, ids.size());
    }

    @Test void idForRequiresEveryInput() {
        assertThrows(NullPointerException.class, () -> CommerceOrder.idFor(null, PLAYER, Source.MERCHANT_GOODS, DAY));
        assertThrows(NullPointerException.class, () -> CommerceOrder.idFor(NONCE, null, Source.MERCHANT_GOODS, DAY));
        assertThrows(NullPointerException.class, () -> CommerceOrder.idFor(NONCE, PLAYER, null, DAY));
        assertThrows(NullPointerException.class, () -> CommerceOrder.idFor(NONCE, PLAYER, Source.MERCHANT_GOODS, null));
    }

    @Test void theRequestHashIsAFixedSha256OfEveryField() {
        assertEquals("e9917c35a7f4875a56c309c77c5bc02f4728506686a4982c7bbc18b01ab19557", goods().requestSha256());
    }

    @Test void everyOrderFieldChangesTheRequestHash() {
        var hashes = new HashSet<String>();
        hashes.add(goods().requestSha256());
        hashes.add(order(Source.MERCHANT_GOODS, "supplies-coal", "STONE", 32, 1_444, DAY, ISSUED, 0).requestSha256());
        hashes.add(order(Source.MERCHANT_GOODS, "supplies-stone", "COAL", 32, 1_444, DAY, ISSUED, 0).requestSha256());
        hashes.add(order(Source.MERCHANT_GOODS, "supplies-stone", "STONE", 31, 1_444, DAY, ISSUED, 0).requestSha256());
        hashes.add(order(Source.MERCHANT_GOODS, "supplies-stone", "STONE", 32, 1_445, DAY, ISSUED, 0).requestSha256());
        hashes.add(order(Source.MERCHANT_GOODS, "supplies-stone", "STONE", 32, 1_444, DAY.plusDays(1), ISSUED, 0).requestSha256());
        hashes.add(order(Source.MERCHANT_GOODS, "supplies-stone", "STONE", 32, 1_444, DAY, ISSUED.plusMillis(1), 0).requestSha256());
        hashes.add(order(Source.WORKER_OUTPUT, "supplies-stone", "STONE", 32, 1_444, DAY, ISSUED, 300).requestSha256());
        hashes.add(order(Source.WORKER_OUTPUT, "supplies-stone", "STONE", 32, 1_444, DAY, ISSUED, 301).requestSha256());
        hashes.add(new CommerceOrder(CommerceOrder.idFor(NONCE, PLAYER, Source.MERCHANT_GOODS, DAY), NONCE, PLAYER,
                new UUID(5, 7), Source.MERCHANT_GOODS, "supplies-stone", "STONE", 32, 1_444, DAY, ISSUED, 0).requestSha256());
        assertEquals(10, hashes.size());
    }

    @Test void issuedAtIsTruncatedToTheMillisecond() {
        var precise = order(Source.MERCHANT_GOODS, "supplies-stone", "STONE", 32, 1_444, DAY,
                Instant.parse("2026-10-18T09:30:00.123999999Z"), 0);
        assertEquals(ISSUED, precise.issuedAt());
        assertEquals(goods(), precise);
        assertEquals(goods().requestSha256(), precise.requestSha256());
    }

    @Test void merchantGoodsHaveNoDelayAndWorkerOutputAlwaysHasOne() {
        assertEquals(0, goods().outputDelaySeconds());
        assertThrows(IllegalArgumentException.class,
                () -> order(Source.MERCHANT_GOODS, "supplies-stone", "STONE", 32, 1_444, DAY, ISSUED, 1));
        assertThrows(IllegalArgumentException.class,
                () -> order(Source.WORKER_OUTPUT, "work-stone", "STONE", 32, 1_444, DAY, ISSUED, 0));
        assertEquals(1, order(Source.WORKER_OUTPUT, "work-stone", "STONE", 32, 1_444, DAY, ISSUED, 1).outputDelaySeconds());
        assertEquals(86_400, order(Source.WORKER_OUTPUT, "work-stone", "STONE", 32, 1_444, DAY, ISSUED, 86_400).outputDelaySeconds());
        assertThrows(IllegalArgumentException.class,
                () -> order(Source.WORKER_OUTPUT, "work-stone", "STONE", 32, 1_444, DAY, ISSUED, 86_401));
        assertThrows(IllegalArgumentException.class,
                () -> order(Source.WORKER_OUTPUT, "work-stone", "STONE", 32, 1_444, DAY, ISSUED, -1));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 300})
    void aRepairReturnIsNeverAnOrder(int delay) {
        var refusal = assertThrows(IllegalArgumentException.class,
                () -> order(Source.REPAIR_RETURN, "repair-return", "STONE", 1, 1, DAY, ISSUED, delay));
        assertEquals("Invalid bounded commerce order", refusal.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "supplies-stone", "a.b:c_d-e", "z0", "abcdefghijklmnopqrstuvwxyz0123456789abcdefghijklmnopqrstuvwxyz01"})
    void catalogueIdsInTheGrammarAreAccepted(String catalogue) {
        assertEquals(catalogue, order(Source.MERCHANT_GOODS, catalogue, "STONE", 1, 1, DAY, ISSUED, 0).catalogueId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "A", "Supplies", "1a", "-a", ".a", "a b", "a/b", "a\n",
            "abcdefghijklmnopqrstuvwxyz0123456789abcdefghijklmnopqrstuvwxyz012"})
    void catalogueIdsOutsideTheGrammarAreRefused(String catalogue) {
        assertThrows(IllegalArgumentException.class,
                () -> order(Source.MERCHANT_GOODS, catalogue, "STONE", 1, 1, DAY, ISSUED, 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "stone", "Stone", "1STONE", "_STONE", "OAK-LOG", "OAK LOG", "minecraft:stone",
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ012"})
    void materialsOutsideTheGrammarAreRefused(String material) {
        assertThrows(IllegalArgumentException.class,
                () -> order(Source.MERCHANT_GOODS, "supplies", material, 1, 1, DAY, ISSUED, 0));
    }

    @Test void nullCatalogueOrMaterialIsRefusedAsInvalid() {
        assertThrows(IllegalArgumentException.class, () -> order(Source.MERCHANT_GOODS, null, "STONE", 1, 1, DAY, ISSUED, 0));
        assertThrows(IllegalArgumentException.class, () -> order(Source.MERCHANT_GOODS, "supplies", null, 1, 1, DAY, ISSUED, 0));
        assertEquals("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ01", order(Source.MERCHANT_GOODS,
                "supplies", "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ01", 1, 1, DAY, ISSUED, 0).material());
    }

    @ParameterizedTest
    @CsvSource({"1, true", "64, true", "0, false", "65, false", "-1, false"})
    void quantityIsOneToSixtyFour(int quantity, boolean accepted) {
        if (accepted) assertEquals(quantity, order(Source.MERCHANT_GOODS, "s", "STONE", quantity, 1, DAY, ISSUED, 0).quantity());
        else assertThrows(IllegalArgumentException.class, () -> order(Source.MERCHANT_GOODS, "s", "STONE", quantity, 1, DAY, ISSUED, 0));
    }

    @ParameterizedTest
    @CsvSource({"1, true", "100000000, true", "0, false", "100000001, false", "-1, false", "-9223372036854775808, false"})
    void costIsOneCentToOneMillionDollars(long cost, boolean accepted) {
        if (accepted) assertEquals(cost, order(Source.MERCHANT_GOODS, "s", "STONE", 1, cost, DAY, ISSUED, 0).costCents());
        else assertThrows(IllegalArgumentException.class, () -> order(Source.MERCHANT_GOODS, "s", "STONE", 1, cost, DAY, ISSUED, 0));
    }

    @ParameterizedTest
    @CsvSource({"1970-01-01, true", "9999-12-31, true", "1969-12-31, false", "+10000-01-01, false", "0001-01-01, false"})
    void theEconomyDayIsBetween1970And9999(String day, boolean accepted) {
        LocalDate date = LocalDate.parse(day);
        if (accepted) assertEquals(date, order(Source.MERCHANT_GOODS, "s", "STONE", 1, 1, date, ISSUED, 0).economyDay());
        else assertThrows(IllegalArgumentException.class, () -> order(Source.MERCHANT_GOODS, "s", "STONE", 1, 1, date, ISSUED, 0));
    }

    @Test void issuedAtBeforeTheEpochIsRefused() {
        assertEquals(Instant.EPOCH, order(Source.MERCHANT_GOODS, "s", "STONE", 1, 1, DAY, Instant.EPOCH, 0).issuedAt());
        assertThrows(IllegalArgumentException.class,
                () -> order(Source.MERCHANT_GOODS, "s", "STONE", 1, 1, DAY, Instant.EPOCH.minusMillis(1), 0));
        assertThrows(NullPointerException.class, () -> order(Source.MERCHANT_GOODS, "s", "STONE", 1, 1, DAY, null, 0));
    }

    @Test void anOrderIdThatIsNotDerivedFromTheIssuanceIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new CommerceOrder(new UUID(9, 9), NONCE, PLAYER, RESIDENT,
                Source.MERCHANT_GOODS, "s", "STONE", 1, 1, DAY, ISSUED, 0));
        assertThrows(IllegalArgumentException.class, () -> new CommerceOrder(
                CommerceOrder.idFor(NONCE, PLAYER, Source.WORKER_OUTPUT, DAY), NONCE, PLAYER, RESIDENT,
                Source.MERCHANT_GOODS, "s", "STONE", 1, 1, DAY, ISSUED, 0), "the id of another source");
        assertThrows(IllegalArgumentException.class, () -> new CommerceOrder(
                CommerceOrder.idFor(NONCE, PLAYER, Source.MERCHANT_GOODS, DAY.minusDays(1)), NONCE, PLAYER, RESIDENT,
                Source.MERCHANT_GOODS, "s", "STONE", 1, 1, DAY, ISSUED, 0), "the id of another day");
    }

    @Test void everyIdentityIsRequired() {
        UUID id = CommerceOrder.idFor(NONCE, PLAYER, Source.MERCHANT_GOODS, DAY);
        assertThrows(NullPointerException.class, () -> new CommerceOrder(null, NONCE, PLAYER, RESIDENT,
                Source.MERCHANT_GOODS, "s", "STONE", 1, 1, DAY, ISSUED, 0));
        assertThrows(NullPointerException.class, () -> new CommerceOrder(id, null, PLAYER, RESIDENT,
                Source.MERCHANT_GOODS, "s", "STONE", 1, 1, DAY, ISSUED, 0));
        assertThrows(NullPointerException.class, () -> new CommerceOrder(id, NONCE, null, RESIDENT,
                Source.MERCHANT_GOODS, "s", "STONE", 1, 1, DAY, ISSUED, 0));
        assertThrows(NullPointerException.class, () -> new CommerceOrder(id, NONCE, PLAYER, null,
                Source.MERCHANT_GOODS, "s", "STONE", 1, 1, DAY, ISSUED, 0));
        assertThrows(NullPointerException.class, () -> new CommerceOrder(id, NONCE, PLAYER, RESIDENT,
                null, "s", "STONE", 1, 1, DAY, ISSUED, 0));
        assertThrows(NullPointerException.class, () -> new CommerceOrder(id, NONCE, PLAYER, RESIDENT,
                Source.MERCHANT_GOODS, "s", "STONE", 1, 1, null, ISSUED, 0));
    }

    @Test void theBoundsConstantsAreTheDocumentedLimits() {
        assertEquals(64, CommerceOrder.MAX_QUANTITY);
        assertEquals(100_000_000L, CommerceOrder.MAX_COST_CENTS);
        assertEquals(86_400, CommerceOrder.MAX_OUTPUT_DELAY_SECONDS);
    }
}

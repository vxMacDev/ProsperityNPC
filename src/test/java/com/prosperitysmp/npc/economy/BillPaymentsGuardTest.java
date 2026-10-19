package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.db.Database;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The bill-payment checks that run before any database work. The database is never connected, so a call that got
 * past its checks fails with "database not connected" instead.
 */
class BillPaymentsGuardTest {
    private static final UUID RESERVE = new UUID(0x51, 1);
    private static final UUID OWNER = new UUID(0x52, 2);
    private static final UUID REQUEST = UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100");
    private static final UUID INVOICE = new UUID(0x53, 3);
    private static final LocalDate DAY = LocalDate.of(2026, 10, 14);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), ZoneOffset.UTC);
    private final BillPayments payments = new BillPayments(new Database(), RESERVE, CLOCK);

    @Test
    void theLedgerKeyIsTheBillsPrefixAndTheRequestId() {
        assertEquals("bills|0f0e0d0c-0b0a-4908-8706-050403020100", BillPayments.eventKey(REQUEST));
        assertEquals(BillPayments.eventKey(REQUEST), BillPayments.eventKey(UUID.fromString(REQUEST.toString())));
        assertNotEquals(BillPayments.eventKey(REQUEST), BillPayments.eventKey(OWNER));
    }

    @Test
    void theLongestLedgerKeyFitsTheIdempotencyColumn() throws Exception {
        assertEquals(42, BillPayments.eventKey(new UUID(-1, -1)).length());
        String schema;
        try (var in = BillPayments.class.getClassLoader().getResourceAsStream("db/migration/V1__initial_schema.sql")) {
            schema = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        var table = schema.substring(schema.indexOf("CREATE TABLE IF NOT EXISTS npc_economy_events"));
        var column = Pattern.compile("idempotency_key\\s+VARCHAR\\((\\d+)\\)").matcher(table);
        assertTrue(column.find(), "the events table declares its idempotency column");
        int width = Integer.parseInt(column.group(1));
        assertEquals(160, width);
        assertTrue(BillPayments.eventKey(new UUID(-1, -1)).length() <= width);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void aNonPositiveMaximumIsRefusedBeforeAnyRead(long maximum) {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> payments.preparePayment(REQUEST, INVOICE, OWNER, 0, DAY, maximum, 10_000));
        assertEquals("Invalid bill maximum", refused.getMessage());
    }

    @Test
    void aNegativeOwnershipEpochIsRefused() {
        assertEquals("Invalid bill payer", assertThrows(IllegalArgumentException.class,
                () -> payments.preparePayment(REQUEST, INVOICE, OWNER, -1, DAY, 100, 100)).getMessage());
    }

    @Test
    void theReserveCannotPayABill() {
        assertEquals("Invalid bill payer", assertThrows(IllegalArgumentException.class,
                () -> payments.preparePayment(REQUEST, INVOICE, RESERVE, 0, DAY, 100, 100)).getMessage());
    }

    @Test
    void theMaximumIsCheckedBeforeThePayer() {
        assertEquals("Invalid bill maximum", assertThrows(IllegalArgumentException.class,
                () -> payments.preparePayment(REQUEST, INVOICE, RESERVE, -1, DAY, 0, 100)).getMessage());
    }

    @Test
    void validArgumentsGoOnToTheDatabase() {
        SQLException reached = assertThrows(SQLException.class,
                () -> payments.preparePayment(REQUEST, INVOICE, OWNER, 0, DAY, 1, 0));
        assertEquals("database not connected", reached.getMessage());
        SQLException withProperty = assertThrows(SQLException.class,
                () -> payments.preparePayment(REQUEST, INVOICE, OWNER, 0, DAY, Long.MAX_VALUE, Long.MAX_VALUE, new UUID(9, 9)));
        assertEquals("database not connected", withProperty.getMessage());
    }

    @Test
    void missingIdsOrDayAreRefused() {
        assertThrows(NullPointerException.class, () -> payments.preparePayment(null, INVOICE, OWNER, 0, DAY, 1, 1));
        assertThrows(NullPointerException.class, () -> payments.preparePayment(REQUEST, null, OWNER, 0, DAY, 1, 1));
        assertThrows(NullPointerException.class, () -> payments.preparePayment(REQUEST, INVOICE, null, 0, DAY, 1, 1));
        assertThrows(NullPointerException.class, () -> payments.preparePayment(REQUEST, INVOICE, OWNER, 0, null, 1, 1));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void reconcilingANonPositiveEventFindsNothingWithoutARead(long eventId) throws SQLException {
        assertEquals(Optional.empty(), payments.reconcile(eventId));
    }

    @Test
    void reconcilingARealEventIdReadsTheDatabase() {
        assertEquals("database not connected", assertThrows(SQLException.class, () -> payments.reconcile(1)).getMessage());
    }

    @Test
    void historyAndReceiptsRefuseMissingIds() {
        assertThrows(NullPointerException.class, () -> payments.history(null));
        assertThrows(NullPointerException.class, () -> BillPayments.findByRequest(null, REQUEST, OWNER));
        assertThrows(NullPointerException.class, () -> BillPayments.findByRequest(new Database(), null, OWNER));
        assertThrows(NullPointerException.class, () -> BillPayments.findByRequest(new Database(), REQUEST, null));
    }

    @Test
    void theConstructorNeedsADatabaseReserveAndClock() {
        assertThrows(NullPointerException.class, () -> new BillPayments(null, RESERVE, CLOCK));
        assertThrows(NullPointerException.class, () -> new BillPayments(new Database(), null, CLOCK));
        assertThrows(NullPointerException.class, () -> new BillPayments(new Database(), RESERVE, null));
    }

    @Test
    void anInvoiceAllowsSixteenAttempts() {
        assertEquals(16, BillPayments.MAX_ATTEMPTS);
    }

    @Test
    void refusalsCarryPlainMessages() throws Exception {
        assertEquals("Invoice is not payable", construct(BillPayments.InvoiceUnavailableException.class).getMessage());
        assertEquals("Invoice has an unresolved payment or reached its retry limit",
                construct(BillPayments.InvoiceHeldException.class).getMessage());
        assertEquals("Invoice exceeds authorized maximum", construct(BillPayments.MaximumExceededException.class).getMessage());
        assertEquals("Invoice exceeds available funds", construct(BillPayments.InsufficientFundsException.class).getMessage());
    }

    private static SQLException construct(Class<? extends SQLException> type) throws Exception {
        var constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }
}

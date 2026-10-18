package com.prosperitysmp.npc.charge;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChargeInvoiceIdentityTest {
    @Test void replayKeepsItsIdentityAndDifferentDaysOrOwnershipsStaySeparate() {
        UUID property = new UUID(0, 1), owner = new UUID(0, 2);
        LocalDate day = LocalDate.of(2026, 10, 1);
        UUID invoice = ChargeInvoiceRepository.invoiceId(property, owner, 0, day);

        assertEquals(invoice, ChargeInvoiceRepository.invoiceId(property, owner, 0, day));
        assertNotEquals(invoice, ChargeInvoiceRepository.invoiceId(property, owner, 0, day.plusDays(1)));
        assertNotEquals(invoice, ChargeInvoiceRepository.invoiceId(new UUID(0, 3), owner, 0, day));
        assertNotEquals(invoice, ChargeInvoiceRepository.invoiceId(property, new UUID(0, 3), 0, day));
        assertNotEquals(invoice, ChargeInvoiceRepository.invoiceId(property, owner, 1, day));
    }
}

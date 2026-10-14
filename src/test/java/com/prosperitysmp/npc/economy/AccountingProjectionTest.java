package com.prosperitysmp.npc.economy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AccountingProjectionTest {
    private static final Instant AT = Instant.parse("2026-09-08T16:00:00Z");
    private static AccountingProjection.Snapshot snapshot(long generation, long cents, String day) {
        return new AccountingProjection.Snapshot(generation, AT.plusSeconds(generation), "UTC",
                List.of(new AccountingProjection.Group(day, "xfer-to-public",
                        List.of(new AccountingProjection.SourceTotal("npc-rent", cents)))), List.of());
    }

    @Test void identicalFreshDataRetainsAcknowledgement() {
        assertTrue(AccountingProjection.canRetainAcknowledgement(
                snapshot(1,25,"2026-09-08"),1,snapshot(2,25,"2026-09-08")));
    }

    @Test void invalidationFailedReadOrUnacknowledgedDataCannotInherit() {
        var old=snapshot(1,25,"2026-09-08"); var next=snapshot(2,25,"2026-09-08");
        assertFalse(AccountingProjection.canRetainAcknowledgement(null,1,next));
        assertFalse(AccountingProjection.canRetainAcknowledgement(old,1,null));
        assertFalse(AccountingProjection.canRetainAcknowledgement(old,-1,next));
        assertFalse(AccountingProjection.canRetainAcknowledgement(next,2,old));
    }

    @Test void ChangedTotalsDayZoneOrReceiptsRequireApplication() {
        var old=snapshot(1,25,"2026-09-08");
        assertFalse(AccountingProjection.canRetainAcknowledgement(old,1,snapshot(2,26,"2026-09-08")));
        assertFalse(AccountingProjection.canRetainAcknowledgement(old,1,snapshot(2,25,"2026-09-09")));
        assertFalse(AccountingProjection.canRetainAcknowledgement(old,1,
                new AccountingProjection.Snapshot(2,AT.plusSeconds(2),"America/Chicago",old.groups(),List.of())));
        var item=new AccountingProjection.Item(1,UUID.randomUUID(),UUID.randomUUID(),"npc-rent",25,AT);
        assertFalse(AccountingProjection.canRetainAcknowledgement(old,1,
                new AccountingProjection.Snapshot(2,AT.plusSeconds(2),"UTC",old.groups(),List.of(item))));
    }

    @Test void legacyConstructorDefaultsCommerceAndSnapshotCopiesItsSixthField() {
        var legacy=snapshot(1,25,"2026-09-08");
        assertEquals(List.of(),legacy.commerceReceipts()); assertEquals(List.of(),legacy.getCommerceReceipts());
        var commerce=new ArrayList<AccountingProjection.CommerceReceipt>();
        var receipt=new AccountingProjection.CommerceReceipt(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"npc-worker-wages",100,AT);
        commerce.add(receipt);
        var current=new AccountingProjection.Snapshot(2,AT.plusSeconds(2),"UTC",legacy.groups(),legacy.receipts(),commerce);
        commerce.clear(); assertEquals(List.of(receipt),current.commerceReceipts());
        assertThrows(UnsupportedOperationException.class,() -> current.getCommerceReceipts().clear());
        assertEquals(receipt.orderId().toString(),receipt.getOrderId()); assertEquals(receipt.payer().toString(),receipt.getPayer());
        assertEquals(receipt.reserve().toString(),receipt.getReserve()); assertEquals("npc-worker-wages",receipt.getSource());
        assertEquals(100,receipt.getCents()); assertEquals(AT.toString(),receipt.getFundedAt());
    }

    @Test void commerceIdentityChangeRequiresAcknowledgementEvenWhenTotalsStayEqual() {
        var base=snapshot(1,25,"2026-09-08"); UUID payer=UUID.randomUUID(),reserve=UUID.randomUUID();
        var first=new AccountingProjection.CommerceReceipt(UUID.randomUUID(),payer,reserve,"npc-merchant-goods",100,AT);
        var old=new AccountingProjection.Snapshot(1,AT.plusSeconds(1),"UTC",base.groups(),base.receipts(),List.of(first));
        var same=new AccountingProjection.Snapshot(2,AT.plusSeconds(2),"UTC",base.groups(),base.receipts(),List.of(first));
        assertTrue(AccountingProjection.canRetainAcknowledgement(old,1,same));
        var changed=new AccountingProjection.CommerceReceipt(UUID.randomUUID(),payer,reserve,"npc-merchant-goods",100,AT);
        assertFalse(AccountingProjection.canRetainAcknowledgement(old,1,
                new AccountingProjection.Snapshot(2,AT.plusSeconds(2),"UTC",base.groups(),base.receipts(),List.of(changed))));
        assertFalse(AccountingProjection.canRetainAcknowledgement(old,1,snapshot(2,25,"2026-09-08")));
    }

    @Test void commerceReceiptCannotMasqueradeAsPropertyOrSelfTransfer() {
        UUID payer=UUID.randomUUID(),reserve=UUID.randomUUID(),order=UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,() -> new AccountingProjection.CommerceReceipt(order,payer,reserve,EventSource.RENT.key(),100,AT));
        assertThrows(IllegalArgumentException.class,() -> new AccountingProjection.CommerceReceipt(order,payer,payer,"npc-worker-wages",100,AT));
        assertThrows(IllegalArgumentException.class,() -> new AccountingProjection.CommerceReceipt(order,payer,reserve,"npc-worker-wages",0,AT));
        assertEquals(7,EventSource.values().length);
    }
}

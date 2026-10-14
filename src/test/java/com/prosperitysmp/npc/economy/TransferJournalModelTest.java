package com.prosperitysmp.npc.economy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static com.prosperitysmp.npc.economy.TransferJournal.*;

class TransferJournalModelTest {
    private static final UUID ATTEMPT = new UUID(0,1), RUN = new UUID(0,2), PAYER = new UUID(0,3), PAYEE = new UUID(0,4);
    private static final String HASH = "0123456789abcdef".repeat(4);
    private static Handle handle() { return new Handle(ATTEMPT,1,RUN,HASH); }

    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE,-1,0})
    void eventIdentityMustBePositive(long id) {
        assertThrows(IllegalArgumentException.class, () -> new Handle(ATTEMPT,id,RUN,HASH));
    }
    @Test void handleRequiresBothIncarnationsAndAnExactProviderFingerprint() {
        assertThrows(NullPointerException.class, () -> new Handle(null,1,RUN,HASH));
        assertThrows(NullPointerException.class, () -> new Handle(ATTEMPT,1,null,HASH));
        for (String invalid : new String[]{null,"",HASH.substring(1),HASH+"0",HASH.toUpperCase(),"z".repeat(64)})
            assertThrows(IllegalArgumentException.class, () -> new Handle(ATTEMPT,1,RUN,invalid));
        assertEquals(HASH,handle().providerSha256());
    }
    @ParameterizedTest
    @EnumSource(State.class)
    void creditIntentPresenceAgreesWithTheDurablePhase(State state) {
        boolean started = state == State.CREDIT_INTENT || state == State.PAID;
        assertNotNull(new Receipt(handle(),HASH,PAYER,PAYEE,1,state,null,started));
        if (state == State.AMBIGUOUS) {
            assertNotNull(new Receipt(handle(),HASH,PAYER,PAYEE,1,state,"INTERRUPTED_RUN",true));
        } else {
            assertThrows(IllegalArgumentException.class,
                    () -> new Receipt(handle(),HASH,PAYER,PAYEE,1,state,null,!started));
        }
    }
    @ParameterizedTest
    @ValueSource(longs = {Long.MIN_VALUE,-1,0})
    void invalidQuotedAmountIsNotAReceipt(long cents) {
        assertThrows(IllegalArgumentException.class,
                () -> new Receipt(handle(),HASH,PAYER,PAYEE,cents,State.DEBIT_INTENT,null,false));
    }
    @Test void sameAccountAndMissingRequestFingerprintAreNotReceipts() {
        assertThrows(IllegalArgumentException.class,
                () -> new Receipt(handle(),HASH,PAYER,PAYER,1,State.DEBIT_INTENT,null,false));
        for (String invalid : new String[]{null,"",HASH.substring(1),"z".repeat(64)})
            assertThrows(IllegalArgumentException.class,
                    () -> new Receipt(handle(),invalid,PAYER,PAYEE,1,State.DEBIT_INTENT,null,false));
    }
}

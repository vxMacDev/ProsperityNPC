package com.prosperitysmp.npc.interaction;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OwnerDeskSessionsTest {
    private static final UUID PLAYER = new UUID(0, 1), WORLD = new UUID(0, 2);
    @Test void submenuRotationRevokesOldActionsAndCloseWithoutExtendingTheSession() {
        var s = new OwnerDeskSessions();
        var old = s.open(PLAYER, WORLD, "c001", 0).orElseThrow();
        var next = s.rotate(old, 500).orElseThrow();
        assertNotEquals(old.token(), next.token());
        assertFalse(s.valid(old, 501));
        assertFalse(s.remove(old));
        assertTrue(s.valid(next, 501));
        assertEquals(old.openedMs(), next.openedMs());
        assertTrue(s.rotate(old, 502).isEmpty());
        assertTrue(s.rotate(next, 60000).isEmpty());
    }
    @Test void pausedViewIsNotRetiredByTheReadWindowSweepButStillExpiresAtViewLimit() {
        var s = new OwnerDeskSessions();
        var t = s.open(PLAYER, WORLD, "c001", 0).orElseThrow();
        long late = OwnerDeskSessions.READ_MS + 1;
        assertFalse(s.mayDeliver(t, late));
        assertTrue(OwnerDeskSessions.undelivered(false, false, s.mayDeliver(t, late)));
        assertFalse(OwnerDeskSessions.undelivered(false, true, s.mayDeliver(t, late)));
        assertFalse(OwnerDeskSessions.undelivered(true, false, s.mayDeliver(t, late)));
        assertTrue(s.valid(t, late));
        assertFalse(s.valid(t, OwnerDeskSessions.VIEW_MS));
    }
    @Test void deniedOwnershipStillConsumesAnAttemptBeforeExpensiveResolution() {
        var s=new OwnerDeskSessions();
        assertTrue(s.attempt(PLAYER,0));
        // No admit follows: ARM returned no unique owned property.
        assertFalse(s.attempt(PLAYER,1));assertFalse(s.attempt(PLAYER,1999));
        assertTrue(s.attempt(PLAYER,2000));
        assertTrue(s.admit(PLAYER,WORLD,"c001",2000).isPresent());
        assertTrue(s.admit(new UUID(0,99),WORLD,"c001",2000).isEmpty());
    }
    @Test void admissionIsBoundedAndRateLimited() {
        var s = new OwnerDeskSessions();
        assertTrue(s.open(PLAYER, WORLD, "c001", 0).isPresent());
        assertTrue(s.open(PLAYER, WORLD, "c002", 1999).isEmpty());
        assertTrue(s.open(PLAYER, WORLD, "c002", 2000).isPresent());
        for (int i=2;i<=32;i++) assertTrue(s.open(new UUID(0,i), WORLD, "c001", 2000).isPresent());
        assertTrue(s.open(new UUID(0,33), WORLD, "c001", 2000).isEmpty());
        assertEquals(32,s.all().size());
    }
    @Test void replacementRevokesOldResponseAndOldCloseCannotClearNew() {
        var s = new OwnerDeskSessions();
        var old = s.open(PLAYER, WORLD, "c001", 0).orElseThrow();
        var next = s.open(PLAYER, WORLD, "c002", 2000).orElseThrow();
        assertFalse(s.mayDeliver(old, 2001));assertFalse(s.remove(old));
        assertTrue(s.mayDeliver(next, 2001));assertSame(next,s.current(PLAYER).orElseThrow());
    }
    @Test void equalReconstructedTicketDoesNotOwnTheSession() {
        var s = new OwnerDeskSessions();var t=s.open(PLAYER,WORLD,"c001",0).orElseThrow();
        var forged=new OwnerDeskSessions.Ticket(t.token(),t.actor(),t.world(),t.region(),t.openedMs());
        assertEquals(t,forged);assertFalse(s.valid(forged,1));assertFalse(s.remove(forged));
    }
    @Test void delayedReadAndExpiredViewHaveSeparateExactDeadlines() {
        var s=new OwnerDeskSessions();var t=s.open(PLAYER,WORLD,"c001",0).orElseThrow();
        assertTrue(s.mayDeliver(t,9999));assertFalse(s.mayDeliver(t,10000));
        assertTrue(s.valid(t,59999));assertFalse(s.valid(t,60000));assertFalse(s.valid(t,-1));
    }
    @Test void quitAndShutdownRevokePendingReplies() {
        var s=new OwnerDeskSessions();var old=s.open(PLAYER,WORLD,"c001",0).orElseThrow();
        s.quit(PLAYER);var next=s.open(PLAYER,WORLD,"c001",1).orElseThrow();
        assertFalse(s.mayDeliver(old,2));assertTrue(s.mayDeliver(next,2));
        s.clear();assertFalse(s.valid(next,3));assertTrue(s.all().isEmpty());
    }
    @Test void removedViewDoesNotClearCooldown() {
        var s=new OwnerDeskSessions();var t=s.open(PLAYER,WORLD,"c001",0).orElseThrow();
        assertTrue(s.remove(t));assertTrue(s.open(PLAYER,WORLD,"c001",1).isEmpty());
        assertTrue(s.open(PLAYER,WORLD,"c001",2000).isPresent());
    }
    @Test void inactiveAccountsCannotGrowControlStateUnbounded() {
        var s=new OwnerDeskSessions();
        for(int i=0;i<256;i++) {
            var t=s.open(new UUID(1,i),WORLD,"c001",0).orElseThrow();s.remove(t);
        }
        assertTrue(s.open(PLAYER,WORLD,"c001",1).isEmpty());
        assertTrue(s.open(PLAYER,WORLD,"c001",2000).isPresent());
    }
}

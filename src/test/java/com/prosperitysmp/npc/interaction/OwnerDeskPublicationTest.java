package com.prosperitysmp.npc.interaction;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class OwnerDeskPublicationTest {
    @Test void opensEmptyThenReauthorizesBeforePopulating() {
        List<String> calls=new ArrayList<>();
        assertTrue(OwnerDeskPublication.publish(()->{calls.add("open");return true;},
                ()->{calls.add("authorize");return true;},()->calls.add("private data"),()->calls.add("discard")));
        assertEquals(List.of("open","authorize","private data"),calls);
    }
    @Test void openListenerRevocationNeverSeesPrivateItemsAndStillCleansUntrackedView() {
        AtomicBoolean allowed=new AtomicBoolean(true);
        List<String> calls=new ArrayList<>();
        assertFalse(OwnerDeskPublication.publish(()->{allowed.set(false);calls.add("open revoked");return true;},
                allowed::get,()->fail("private contents must not be installed"),()->calls.add("discard")));
        assertEquals(List.of("open revoked","discard"),calls);
    }
    @Test void canceledOpenDoesNotAuthorizeOrPopulate() {
        List<String> calls=new ArrayList<>();
        assertFalse(OwnerDeskPublication.publish(()->false,()->{fail("canceled");return false;},
                ()->fail("canceled"),()->calls.add("discard")));
        assertEquals(List.of("discard"),calls);
    }
    @Test void crossingReadDeadlineDuringOpenDiscardsEvenWhenViewLifetimeRemainsValid() {
        var sessions=new OwnerDeskSessions();
        var ticket=sessions.open(new java.util.UUID(0,1),new java.util.UUID(0,2),"fixture",0).orElseThrow();
        var now=new java.util.concurrent.atomic.AtomicLong(9999);
        assertTrue(sessions.mayDeliver(ticket,now.get()));
        var discarded=new AtomicBoolean();
        assertFalse(OwnerDeskPublication.publish(()->{now.set(10000);return true;},
                ()->sessions.mayDeliver(ticket,now.get()) && sessions.valid(ticket,now.get()),
                ()->fail("expired read must not publish"),()->discarded.set(true)));
        assertTrue(discarded.get());
        assertTrue(sessions.valid(ticket,now.get()),"view age alone would incorrectly allow publication");
    }
    @Test void failuresAtEachPublicationStageDiscardExactlyOnce() {
        for(int at=0;at<3;at++) {
            final int step=at;
            List<String> calls=new ArrayList<>();
            assertThrows(IllegalStateException.class,()->OwnerDeskPublication.publish(
                    ()->{if(step==0)throw new IllegalStateException();return true;},
                    ()->{if(step==1)throw new IllegalStateException();return true;},
                    ()->{throw new IllegalStateException();},()->calls.add("discard")));
            assertEquals(List.of("discard"),calls);
        }
    }
}

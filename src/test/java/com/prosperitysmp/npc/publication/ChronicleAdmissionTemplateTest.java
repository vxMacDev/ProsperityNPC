package com.prosperitysmp.npc.publication;

import com.prosperitysmp.npc.market.HousingMoveRepository.Receipt;
import com.prosperitysmp.npc.market.HousingMoveRequest;
import com.prosperitysmp.npc.market.HousingMoveRequest.Policy;
import com.prosperitysmp.npc.publication.PublicOccupancySnapshot.Approval;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChronicleAdmissionTemplateTest {
    private static final UUID REQUEST=new UUID(0,1), RESIDENT=new UUID(0,2), PROPERTY=new UUID(0,3),
            OWNER=new UUID(0,4), WORLD=new UUID(0,5);
    private static final ZoneId ZONE=ZoneId.of("America/Chicago");
    private static final Approval MAPPING=new Approval(PROPERTY,WORLD,"c001","main");

    // Pure formatting fixture, not evidence of a real server event.
    private Receipt receipt(UUID requestId) {
        var day=LocalDate.of(2026,9,7);
        var request=new HousingMoveRequest(requestId,RESIDENT,PROPERTY,OWNER,WORLD,"downtown","room",3,11,0,-1,0,day);
        return new Receipt(request,new Policy(10,5,2,7),0,day.plusDays(7),Instant.parse("2026-09-08T07:00:00Z"));
    }

    @Test void historicalCalendarDateDoesNotReuseEconomyDayOrDisclosePrivateEvidence() {
        var preview=ChronicleAdmissionTemplate.render(receipt(REQUEST),MAPPING,ZONE);
        assertEquals(LocalDate.of(2026,9,8),preview.moveDate());
        assertEquals("A resident moved into c001 on 2026-09-08.",preview.text());
        assertEquals("A new arrival at c001",preview.title());
        assertEquals("main",preview.world());assertEquals("c001",preview.region());
        assertEquals("npc-admission-story-v1",preview.templateVersion());
        assertEquals("7f6b21704745692bdcce659d1a7265c34bf318f3028890a8283f0a41c5c059f0",preview.eventKey());
        String serialized=preview.toString();
        for(UUID privateId:new UUID[]{REQUEST,RESIDENT,PROPERTY,OWNER,WORLD})
            assertFalse(serialized.contains(privateId.toString()));
        assertFalse(serialized.contains("premium"));assertFalse(serialized.contains("downtown"));
    }

    @Test void retriesAndMappingChangesKeepEventIdentityButOtherMovesDiffer() {
        var first=ChronicleAdmissionTemplate.render(receipt(REQUEST),MAPPING,ZONE);
        assertEquals(first,ChronicleAdmissionTemplate.render(receipt(REQUEST),MAPPING,ZONE));
        var renamed=ChronicleAdmissionTemplate.render(receipt(REQUEST),new Approval(PROPERTY,WORLD,"c006","overworld"),ZONE);
        assertEquals(first.eventKey(),renamed.eventKey());assertNotEquals(first.text(),renamed.text());
        assertNotEquals(first.eventKey(),ChronicleAdmissionTemplate.render(receipt(new UUID(0,6)),MAPPING,ZONE).eventKey());
    }

    @Test void wrongPropertyOrWorldCannotBorrowApprovedListing() {
        assertThrows(IllegalArgumentException.class,()->ChronicleAdmissionTemplate.render(receipt(REQUEST),
                new Approval(new UUID(0,9),WORLD,"c001","main"),ZONE));
        assertThrows(IllegalArgumentException.class,()->ChronicleAdmissionTemplate.render(receipt(REQUEST),
                new Approval(PROPERTY,new UUID(0,9),"c001","main"),ZONE));
    }
}

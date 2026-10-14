package com.prosperitysmp.npc.interaction;

import java.util.ArrayList;
import java.util.List;
import org.geysermc.cumulus.form.impl.simple.SimpleFormDefinition;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Installed Cumulus codec, not a native client or Bukkit scheduler test. */
class BedrockNpcUiTest {
    @Test void insuranceRetainsEveryMutationAtTheTwelveButtonOwnerLimit() {
        var id=new java.util.UUID(0,1);var day=java.time.LocalDate.of(2026,9,8);
        var at=java.time.Instant.parse("2026-09-08T18:00:00Z");
        var property=new com.prosperitysmp.npc.property.Property(id,id,"fixture","test","room",4,
                1000,1200,id,3,65,true);
        var residents=java.util.stream.IntStream.range(0,4).mapToObj(slot->
                new com.prosperitysmp.npc.property.OwnerDeskData.Resident(new java.util.UUID(0,slot+2),
                        "Resident "+slot,slot,"STANDARD","NEUTRAL",50)).toList();
        var data=new com.prosperitysmp.npc.property.OwnerDeskData(property,2,at,residents,
                List.of(new com.prosperitysmp.npc.property.OwnerDeskData.Issue(1,"PLUMBING",1234,at)),
                List.of(),new com.prosperitysmp.npc.property.OwnerDeskData.Statement(day.minusDays(6),day,0,0,0,0));
        var buttons=BedrockNpcUi.ownerButtons(data);
        assertEquals(12,buttons.labels().size());assertEquals(12,buttons.slots().size());
        assertTrue(buttons.slots().containsAll(List.of(40,42,22,24,26,28,30,34,10,12,14,16)));
        assertFalse(buttons.slots().contains(38));
        assertFalse(buttons.slots().contains(36));
        assertFalse(buttons.slots().contains(33));
        assertEquals("Auto-care settings",buttons.labels().get(buttons.slots().indexOf(34)));
        assertEquals("Buy seven-day insurance for $20.00",buttons.labels().get(buttons.slots().indexOf(30)));
        assertTrue(BedrockNpcUi.ownerBody(data).contains("Existing incidents are not covered."));
        assertTrue(BedrockNpcUi.ownerBody(data).contains("Prepaid and nonrefundable; no cash payout."));
        assertThrows(UnsupportedOperationException.class,()->buttons.slots().clear());
    }

    @Test void pauseControlsAddOneButtonWithinTheTwelveButtonLimitAndStayOffByDefault() {
        var id=new java.util.UUID(0,1);var day=java.time.LocalDate.of(2026,9,8);
        var at=java.time.Instant.parse("2026-09-08T18:00:00Z");
        var property=new com.prosperitysmp.npc.property.Property(id,id,"fixture","test","room",4,
                1000,1200,id,3,65,true);
        var residents=java.util.stream.IntStream.range(0,4).mapToObj(slot->
                new com.prosperitysmp.npc.property.OwnerDeskData.Resident(new java.util.UUID(0,slot+2),
                        "Resident "+slot,slot,"STANDARD","NEUTRAL",50)).toList();
        var data=new com.prosperitysmp.npc.property.OwnerDeskData(property,2,at,residents,
                List.of(new com.prosperitysmp.npc.property.OwnerDeskData.Issue(1,"PLUMBING",1234,at)),
                List.of(),new com.prosperitysmp.npc.property.OwnerDeskData.Statement(day.minusDays(6),day,0,0,0,0));
        assertEquals(BedrockNpcUi.ownerButtons(data).slots(),BedrockNpcUi.ownerButtons(data,false).slots());
        assertFalse(BedrockNpcUi.ownerBody(data).contains("Asking rent:"));
        var full=BedrockNpcUi.ownerButtons(data,true);
        assertEquals(12,full.slots().size());
        assertTrue(full.slots().containsAll(List.of(22,24,26,28,30,32,34,42,10,12,14,16)));
        assertEquals(OwnerDeskMenu.PAUSE_LABEL,full.labels().get(full.slots().indexOf(32)));
        String body=BedrockNpcUi.ownerBody(data,true);
        assertTrue(body.contains("Asking rent: $12.00 / resident / day."));
        assertTrue(body.contains("/property rent <dollars-per-day>"));
        var roomy=new com.prosperitysmp.npc.property.OwnerDeskData(property,2,at,List.of(),List.of(),List.of(),
                new com.prosperitysmp.npc.property.OwnerDeskData.Statement(day.minusDays(6),day,0,0,0,0));
        assertTrue(BedrockNpcUi.ownerButtons(roomy,true).slots().contains(32));
        assertTrue(BedrockNpcUi.ownerButtons(roomy,true).slots().contains(40));
        assertTrue(OwnerDeskMenu.pausedBody("fixture").contains("/property resume"));
    }

    @Test void ownerFormShowsIssueQuoteDeadlineAndExplicitPaymentLimit() {
        var id=new java.util.UUID(0,1);
        var day=java.time.LocalDate.of(2026,9,8);
        var due=java.time.Instant.parse("2026-09-08T18:00:00Z");
        var property=new com.prosperitysmp.npc.property.Property(id,id,"fixture","test","room",1,
                1000,1200,id,3,65,true);
        var data=new com.prosperitysmp.npc.property.OwnerDeskData(property,2,due,List.of(),
                List.of(new com.prosperitysmp.npc.property.OwnerDeskData.Issue(1,"PLUMBING",1234,due)),
                List.of(),new com.prosperitysmp.npc.property.OwnerDeskData.Statement(day.minusDays(6),day,0,0,0,0));
        String body=BedrockNpcUi.ownerBody(data);
        assertTrue(body.contains("Plumbing | Repair quote $12.34"));
        assertTrue(body.contains("Due Sep 8 18:00:00 UTC"));
        assertTrue(body.contains("up to the button's maximum"));
        assertTrue(body.contains("Repair buttons authorize only the named incident"));
        assertFalse(body.contains("Upgrades remain held"));
        assertTrue(body.contains("/property rent <dollars-per-day>"));
        assertTrue(body.contains("Service record unavailable"));
        assertTrue(body.length()<4096);
    }
    @Test void ownerBodySharesBoundedHousingFactsWithoutAddingCallbacks() {
        for(boolean qualifies:List.of(false,true)) {
            var data=OwnerDeskMenuTest.housingData(qualifies);
            var withoutHousing=new com.prosperitysmp.npc.property.OwnerDeskData(data.property(),data.revision(),
                    data.capturedAt(),data.residents(),data.issues(),data.upgrades(),data.statement(),data.insurance());
            String housing=String.join("\n",OwnerDeskMenu.housingLines(data));
            String body=BedrockNpcUi.ownerBody(data);
            assertTrue(body.contains(housing));
            assertTrue(housing.length()<=700);
            assertTrue(body.length()<4096);
            var before=BedrockNpcUi.ownerButtons(withoutHousing);
            var after=BedrockNpcUi.ownerButtons(data);
            assertEquals(before,after);
            assertEquals(12,after.slots().size());
            assertFalse(after.slots().contains(33));
        }
    }
    @Test void sixteenIssuesAndFourFullWidthNamesKeepTheOwnerBodyWithinItsLimit() {
        var base=OwnerDeskMenuTest.housingData(false);
        var residents=base.residents().stream().map(r->new com.prosperitysmp.npc.property.OwnerDeskData.Resident(
                r.id(),"N".repeat(64),r.slot(),r.tier(),r.mood(),r.satisfaction())).toList();
        var issues=java.util.stream.IntStream.range(0,16).mapToObj(index->
                new com.prosperitysmp.npc.property.OwnerDeskData.Issue(Long.MAX_VALUE-index,"I".repeat(24),
                        Long.MAX_VALUE,base.capturedAt())).toList();
        var view=base.housing().orElseThrow();
        var premium=new com.prosperitysmp.npc.market.PremiumHousingEligibility.Result(false,
                com.prosperitysmp.npc.market.PremiumHousingEligibility.Reason.PREMIUM_THRESHOLDS_NOT_MET,
                Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,
                view.premium().operatorScore(),view.premium().propertyScore());
        var housing=new com.prosperitysmp.npc.market.OwnerHousingView(view.propertyId(),view.ownerId(),view.capturedAt(),
                view.windowFrom(),view.rating(),premium);
        var statement=new com.prosperitysmp.npc.property.OwnerDeskData.Statement(base.statement().fromDay(),
                base.statement().throughDay(),Long.MAX_VALUE,0,Integer.MAX_VALUE,Integer.MAX_VALUE);
        var insurance=new com.prosperitysmp.npc.property.OwnerDeskData.Insurance(2000,
                statement.throughDay().minusDays(6),statement.throughDay().plusDays(1),false,true,true);
        var data=new com.prosperitysmp.npc.property.OwnerDeskData(base.property(),base.revision(),base.capturedAt(),
                residents,issues,base.upgrades(),statement,insurance,java.util.Optional.of(housing));
        assertTrue(String.join("\n",OwnerDeskMenu.housingLines(data)).length()<=700);
        String body=BedrockNpcUi.ownerBody(data);
        assertTrue(body.length()<=4096,()->"Owner body length: "+body.length());
        assertEquals(12,BedrockNpcUi.ownerButtons(data).slots().size());
    }
    @Test void installedCodecRoutesActualButtonAndClosedResponses() throws Exception {
        var results=new ArrayList<Integer>();
        var form=BedrockNpcUi.form("Mara | NPC","A recorded service memory.",List.of("Our history","Goodbye"),results::add);
        assertEquals("Mara | NPC",form.title());
        assertEquals(2,form.buttons().size());
        SimpleFormDefinition.instance().handleFormResponse(form,"0");
        SimpleFormDefinition.instance().handleFormResponse(form,"1");
        SimpleFormDefinition.instance().handleFormResponse(form,"null");
        assertEquals(List.of(0,1,-1),results);
    }
    @Test void outOfRangeResponseClosesInsteadOfDispatchingAnAction() throws Exception {
        var results=new ArrayList<Integer>();
        var form=BedrockNpcUi.form("Property","Private owner record.",List.of("Refresh","Close"),results::add);
        SimpleFormDefinition.instance().handleFormResponse(form,"8");
        assertEquals(List.of(-1),results);
    }
}

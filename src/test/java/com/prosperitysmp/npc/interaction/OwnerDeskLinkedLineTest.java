package com.prosperitysmp.npc.interaction;

import com.prosperitysmp.npc.property.OwnerDeskData;
import com.prosperitysmp.npc.property.Property;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** linked-accounts desk line: one extra line on the chest, /property status and the Bedrock form, nothing else. */
class OwnerDeskLinkedLineTest {
    private static final LocalDate END = LocalDate.of(2026, 10, 13);
    private static final String LINE = "Shares a $45 daily rent limit with your linked account.";

    private static OwnerDeskData data(Optional<OwnerDeskData.Linked> linked) {
        var property = new Property(new UUID(0, 1), new UUID(0, 2), "desk", "test", "room", 4, 1000, 1200,
                UUID.fromString("7a1b2c3d-0000-4000-8000-0000000000a1"), 3, 65, true);
        var base = new OwnerDeskData(property, 2, Instant.parse("2026-10-13T05:00:00Z"), List.of(), List.of(), List.of(),
                new OwnerDeskData.Statement(END.minusDays(6), END, 321, 400, 2, 1));
        return new OwnerDeskData(base.property(), base.revision(), base.capturedAt(), base.residents(), base.issues(), base.upgrades(),
                base.statement(), base.insurance(), base.housing(), base.pendingRent(), linked);
    }

    @Test void theLineShowsOnlyForAGroupOfTwoOrMore() {
        var alone = data(Optional.empty());
        var linked = data(Optional.of(new OwnerDeskData.Linked(2, 4500)));
        assertEquals(OwnerDeskMenu.statementLines(alone), OwnerDeskMenu.statementAndLinkLines(alone), "alone: unchanged");
        assertEquals(OwnerDeskMenu.statementLines(linked).size() + 1, OwnerDeskMenu.statementAndLinkLines(linked).size());
        assertEquals(LINE, OwnerDeskMenu.statementAndLinkLines(linked).getLast());
        assertFalse(BedrockNpcUi.ownerBody(alone).contains("linked account"));
        assertTrue(BedrockNpcUi.ownerBody(linked).contains(LINE));
        assertEquals(BedrockNpcUi.ownerBody(alone).length() + LINE.length() + 1, BedrockNpcUi.ownerBody(linked).length(),
                "the Bedrock form gets exactly one more content line");
        assertThrows(IllegalArgumentException.class, () -> new OwnerDeskData.Linked(1, 4500), "a group of one has no line");
    }

    @Test void theOldConstructorsLeaveTheLineOut() {
        var base = data(Optional.empty());
        var tenArg = new OwnerDeskData(base.property(), base.revision(), base.capturedAt(), base.residents(), base.issues(), base.upgrades(),
                base.statement(), base.insurance(), base.housing(), base.pendingRent());
        assertTrue(tenArg.linked().isEmpty());
        assertEquals(base, tenArg);
    }
}

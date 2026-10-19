package com.prosperitysmp.npc.command;

import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The text helpers and tab completion of /npcnav and its waypoint tools. */
class NavCommandTextTest {
    @Test
    void aLineOfNinetyCharactersOrLessIsKeptWhole() {
        assertEquals("", NpcNavCommand.clip(""));
        assertEquals("short", NpcNavCommand.clip("short"));
        assertEquals("a".repeat(90), NpcNavCommand.clip("a".repeat(90)));
    }

    @Test
    void aLongerLineIsCutToEightySevenCharactersAndThreeDots() {
        String clipped = NpcNavCommand.clip("a".repeat(91));
        assertEquals(90, clipped.length());
        assertEquals("a".repeat(87) + "...", clipped);
        assertEquals("b".repeat(87) + "...", NpcNavCommand.clip("b".repeat(500)));
    }

    @Test
    void clippingKeepsTheStartOfTheLine() {
        String text = "0123456789".repeat(10);
        assertEquals(text.substring(0, 87) + "...", NpcNavCommand.clip(text));
    }

    @Test
    void noDetailsAddNoLines() {
        List<Line> lines = new ArrayList<>();
        NpcNavCommand.addDetails(lines, List.of());
        assertEquals(List.of(), lines);
    }

    @Test
    void eachDetailBecomesAnIndentedGrayLine() {
        List<Line> lines = new ArrayList<>();
        NpcNavCommand.addDetails(lines, List.of("node 4 floats", "node 9 blocked"));
        assertEquals(List.of(new Line(NamedTextColor.GRAY, "  node 4 floats"), new Line(NamedTextColor.GRAY, "  node 9 blocked")), lines);
    }

    @Test
    void twentyDetailsAreAllShownWithNoOmissionNote() {
        List<Line> lines = new ArrayList<>();
        List<String> details = new ArrayList<>();
        for (int i = 0; i < 20; i++) details.add("d" + i);
        NpcNavCommand.addDetails(lines, details);
        assertEquals(20, lines.size());
        assertEquals("  d19", lines.get(19).text());
    }

    @ParameterizedTest
    @ValueSource(ints = {21, 22, 40, 500})
    void detailsBeyondTwentyAreCountedInOneFinalNote(int count) {
        List<Line> lines = new ArrayList<>();
        List<String> details = new ArrayList<>();
        for (int i = 0; i < count; i++) details.add("d" + i);
        NpcNavCommand.addDetails(lines, details);
        assertEquals(21, lines.size());
        assertEquals("  d19", lines.get(19).text());
        assertEquals(new Line(NamedTextColor.GRAY, "  (" + (count - 20) + " more omitted)"), lines.get(20));
    }

    @Test
    void aLongDetailIsClippedAfterItsIndent() {
        List<Line> lines = new ArrayList<>();
        NpcNavCommand.addDetails(lines, List.of("x".repeat(200)));
        assertEquals("  " + "x".repeat(85) + "...", lines.get(0).text());
        assertEquals(90, lines.get(0).text().length());
    }

    @Test
    void addingDetailsKeepsEarlierLines() {
        List<Line> lines = new ArrayList<>(List.of(new Line(NamedTextColor.GREEN, "head")));
        NpcNavCommand.addDetails(lines, List.of("one"));
        assertEquals(2, lines.size());
        assertEquals("head", lines.get(0).text());
    }

    @Test
    void aReplyHasOneColouredLineAndNothingToInvalidateOrRunNext() {
        Reply reply = NpcNavCommand.reply(NamedTextColor.RED, "No graph x");
        assertEquals(List.of(new Line(NamedTextColor.RED, "No graph x")), reply.lines());
        assertNull(reply.invalidate());
        assertNull(reply.next());
    }

    @Test
    void aReplyOwnsACopyOfItsLines() {
        List<Line> lines = new ArrayList<>(List.of(new Line(NamedTextColor.GRAY, "a")));
        Reply reply = new Reply(lines, null);
        lines.add(new Line(NamedTextColor.GRAY, "b"));
        assertEquals(1, reply.lines().size());
        assertThrows(UnsupportedOperationException.class, () -> reply.lines().add(new Line(NamedTextColor.GRAY, "c")));
    }

    @Test
    void waypointToolsOfferTheirElevenOperationsAfterTheRegionIsNamed() {
        List<String> palettes = List.of("default", "alt");
        assertEquals(List.of("add", "move", "remove", "link", "unlink", "tag", "list", "check", "build", "export", "import"),
                NavWaypointCommands.complete(new String[]{"wp", ""}, palettes));
        assertEquals(11, NavWaypointCommands.complete(new String[]{"wp", "anything"}, palettes).size());
    }

    @Test
    void addOffersItsKindsThenLink() {
        assertEquals(List.of("sidewalk", "access", "link"), NavWaypointCommands.complete(new String[]{"wp", "add", "r", "n", ""}, List.of()));
        assertEquals(List.of("link"), NavWaypointCommands.complete(new String[]{"wp", "ADD", "r", "n", "access", ""}, List.of()));
        assertEquals(List.of(), NavWaypointCommands.complete(new String[]{"wp", "add", "r", ""}, List.of()));
        assertEquals(List.of(), NavWaypointCommands.complete(new String[]{"wp", "add", "r", "n", "access", "link", ""}, List.of()));
    }

    @Test
    void linkOffersTheWalkingKindsAtTheSixthWord() {
        assertEquals(List.of("walk", "cross", "crosswalk"),
                NavWaypointCommands.complete(new String[]{"wp", "link", "r", "a", "b", ""}, List.of()));
        assertEquals(List.of(), NavWaypointCommands.complete(new String[]{"wp", "link", "r", "a", ""}, List.of()));
    }

    @Test
    void tagOffersTheFiveTagsThenRemove() {
        assertEquals(List.of("home", "work", "market", "leisure", "spawn"),
                NavWaypointCommands.complete(new String[]{"wp", "tag", "r", "n", ""}, List.of()));
        assertEquals(List.of("remove"), NavWaypointCommands.complete(new String[]{"wp", "tag", "r", "n", "home", ""}, List.of()));
    }

    @Test
    void buildOffersThePalettesThenLoad() {
        List<String> palettes = List.of("default", "alt");
        assertEquals(palettes, NavWaypointCommands.complete(new String[]{"wp", "build", "r", ""}, palettes));
        assertEquals(List.of("load"), NavWaypointCommands.complete(new String[]{"wp", "build", "r", "alt", ""}, palettes));
        assertEquals(List.of(), NavWaypointCommands.complete(new String[]{"wp", "build", "r", "alt", "load", ""}, palettes));
    }

    @ParameterizedTest
    @ValueSource(strings = {"move", "remove", "unlink", "list", "check", "export", "import", "bogus"})
    void theOtherOperationsOfferNothingAfterTheirName(String op) {
        for (int length = 3; length <= 7; length++) {
            String[] args = new String[length];
            java.util.Arrays.fill(args, "x");
            args[1] = op;
            assertEquals(List.of(), NavWaypointCommands.complete(args, List.of("p")), op + " with " + length + " words");
        }
    }
}

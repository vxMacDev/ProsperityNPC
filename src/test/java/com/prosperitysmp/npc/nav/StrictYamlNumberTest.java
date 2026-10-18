package com.prosperitysmp.npc.nav;

import com.prosperitysmp.npc.nav.waypoint.WaypointCodec;
import com.prosperitysmp.npc.nav.waypoint.WaypointSet.EditRefused;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Whole numbers in route and waypoint files are plain decimal only: YAML 1.1 reads 010 as 8 and 0x10 as 16. */
class StrictYamlNumberTest {

    private static final List<String> ODD = List.of("010", "-010", "00", "0x10", "0xFF", "0b11", "1_000", "-1_0", "+5",
            "1:30", "0o7");

    /** One node and no edges, so the file reads whatever the number turns out to be, unless its form is refused. */
    private static String routeFile(String x) {
        return "format: prosperity-nav-graph/1\nnodes:\n  - {id: 1, at: [" + x + ", 64, 0], surface: sidewalk}\nedges: []\n";
    }

    @Test
    void oddNumberFormsAreRefusedInRouteFiles() {
        for (String form : ODD) {
            assertThrows(FileGraphCodec.Refused.class, () -> FileGraphCodec.read(routeFile(form), StreetWorld.RULES, 1,
                    FileGraphCodec.Limits.DEFAULT), form);
        }
        // An id is a number too.
        String hexId = routeFile("0").replace("{id: 1,", "{id: 0x1,");
        assertThrows(FileGraphCodec.Refused.class, () -> FileGraphCodec.read(hexId, StreetWorld.RULES, 1,
                FileGraphCodec.Limits.DEFAULT));
        // Positive control: plain decimal, negative and zero read, and keep their values.
        for (String plain : List.of("0", "-128", "416", "2147483647")) {
            NavGraph graph = FileGraphCodec.read(routeFile(plain), StreetWorld.RULES, 1, FileGraphCodec.Limits.DEFAULT);
            assertEquals(Integer.parseInt(plain), graph.node(1).x(), plain);
        }
    }

    @Test
    void oddNumberFormsAreRefusedInWaypointFiles() {
        for (String form : ODD) {
            String text = "format: prosperity-nav-waypoints/1\npoints: [{name: a, at: [" + form + ", 64, 0]}]\n";
            assertEquals("WP_FORMAT", assertThrows(EditRefused.class, () -> WaypointCodec.parse(text)).code(), form);
        }
        assertEquals(-8, WaypointCodec.parse("format: prosperity-nav-waypoints/1\npoints: [{name: a, at: [-8, 64, 0]}]\n")
                .point("a").x());
    }

    @Test
    void onlyUnquotedNumbersAreLookedAt() {
        assertTrue(StrictYaml.plainDecimalOrNotANumber("72"));
        assertTrue(StrictYaml.plainDecimalOrNotANumber("-128"));
        assertTrue(StrictYaml.plainDecimalOrNotANumber("sidewalk"));
        assertTrue(StrictYaml.plainDecimalOrNotANumber("prosperity-nav-graph/1"));
        assertTrue(StrictYaml.plainDecimalOrNotANumber("1.5"));
        for (String form : ODD) {
            // 0o7 is not a number at all in YAML 1.1: it reads as text, which every reader refuses as a coordinate.
            if (!form.equals("0o7")) assertEquals(false, StrictYaml.plainDecimalOrNotANumber(form), form);
        }
        // A quoted number is text: it is not refused here, and the reader refuses it as not a number.
        StrictYaml.load("format: prosperity-nav-graph/1\nname: '010'\n");
    }
}

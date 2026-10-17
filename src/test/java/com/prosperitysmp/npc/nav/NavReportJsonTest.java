package com.prosperitysmp.npc.nav;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class NavReportJsonTest {
    @Test
    void fileNamesAreKindGraphAndUtcTimeWithOnlySafeCharacters() {
        Instant at = Instant.parse("2026-10-14T08:09:10Z");
        assertEquals("audit-e1ac6272-ac24-4195-8c6f-68e47161515d-20261014080910.json",
                NavReportJson.fileName("audit", "e1ac6272-ac24-4195-8c6f-68e47161515d", at));
        assertEquals("re_val-___etc-20261014080910.json", NavReportJson.fileName("re/val", "../etc", at));
        assertFalse(NavReportJson.fileName("a b", "c:d", at).contains(" "));
    }

    @Test
    void jsonKeepsOrderAndEscapesText() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("graph", "a\"b\\c\nd");
        body.put("n", 3);
        body.put("ratio", 0.5);
        body.put("nan", Double.NaN);
        body.put("ok", true);
        body.put("none", null);
        body.put("list", List.of("x", 1));
        body.put("box", new int[]{1, -2});
        body.put("ctl", "\u0001");
        assertEquals("{\"graph\":\"a\\\"b\\\\c\\nd\",\"n\":3,\"ratio\":0.5,\"nan\":null,\"ok\":true,\"none\":null,"
                + "\"list\":[\"x\",1],\"box\":[1,-2],\"ctl\":\"\\u0001\"}", NavReportJson.toJson(body));
    }
}

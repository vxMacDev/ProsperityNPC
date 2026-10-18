package com.prosperitysmp.npc.nav.origin;

import com.prosperitysmp.npc.nav.NavGraphRepository;
import com.prosperitysmp.npc.nav.StreetRules;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OriginFingerprintTest {
    private static final String DOC = "a".repeat(64);
    private static final String PALETTE = "b".repeat(64);
    private static final String RULES = DocumentOrigin.rules(new StreetRules(12, 16, 0.4), 1);

    private static NavGraphRepository.GraphInfo info(String source) {
        return new NavGraphRepository.GraphInfo(new UUID(0, 1), new UUID(0, 2), "town", "city", 1, "DRAFT",
                5, 8, "c".repeat(64), source, DOC, null);
    }

    @Test void aDocumentOriginUsesItsOwnRetainedDocument() throws Exception {
        var reads = new ArrayList<String>();
        var graph = info("waypoints");
        String actual = DocumentOrigin.WAYPOINTS.fingerprintFor(graph, PALETTE, RULES, sha -> {
            reads.add(sha);
            return "retained document";
        });
        assertEquals(List.of(DOC), reads);
        assertEquals(DocumentOrigin.WAYPOINTS.fingerprint(DOC, PALETTE, RULES), actual);
        assertNull(DocumentOrigin.WAYPOINTS.fingerprintFor(graph, PALETTE, RULES, sha -> null));
        assertNull(DocumentOrigin.WAYPOINTS.fingerprintFor(graph, null, RULES, sha -> "present"));
        assertNull(DocumentOrigin.WAYPOINTS.fingerprintFor(graph, PALETTE, null, sha -> "present"));
    }

    @Test void theDocumentKindAndEveryBuildInputBindTheFingerprint() {
        var origin = DocumentOrigin.WAYPOINTS;
        String hash = origin.fingerprint(DOC, PALETTE, RULES);
        assertNotEquals(hash, DocumentOrigin.FILE.fingerprint(DOC, PALETTE, RULES));
        assertNotEquals(hash, origin.fingerprint("d".repeat(64), PALETTE, RULES));
        assertNotEquals(hash, origin.fingerprint(DOC, "e".repeat(64), RULES));
        for (String rules : List.of(DocumentOrigin.rules(new StreetRules(13, 16, 0.4), 1),
                DocumentOrigin.rules(new StreetRules(12, 17, 0.4), 1),
                DocumentOrigin.rules(new StreetRules(12, 16, 0.5), 1),
                DocumentOrigin.rules(new StreetRules(12, 16, 0.4), 2))) {
            assertNotEquals(hash, origin.fingerprint(DOC, PALETTE, rules));
        }
    }

    @Test void scansKeepTheirPaletteHashAndAuthoredOriginsAlwaysGate() throws Exception {
        var scan = OriginRegistry.of("scan");
        assertSame(scan, OriginRegistry.of(null));
        assertEquals(PALETTE, scan.fingerprintFor(info("scan"), PALETTE, RULES,
                sha -> { fail("scans do not read route documents"); return null; }));
        assertFalse(scan.alwaysGate());
        assertTrue(OriginRegistry.of("waypoints").alwaysGate());
        assertTrue(OriginRegistry.of("file").alwaysGate());
        assertNull(OriginRegistry.of("future-format"));
    }

    @Test void fingerprintTextDoesNotDependOnTheServersLocale() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertEquals(RULES, DocumentOrigin.rules(new StreetRules(12, 16, 0.4), 1));
        } finally {
            Locale.setDefault(before);
        }
    }
}

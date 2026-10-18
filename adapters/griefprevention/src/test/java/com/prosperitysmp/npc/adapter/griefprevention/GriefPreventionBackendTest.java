package com.prosperitysmp.npc.adapter.griefprevention;

import com.google.gson.JsonParser;
import com.prosperitysmp.npc.api.GuardMode;
import com.prosperitysmp.npc.api.Presence;
import com.prosperitysmp.npc.api.contract.RecordingHooks;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GriefPreventionBackendTest {
    @Test void shapeChangesBumpBothParentAndSubdivisionWithoutChangingThePayer() {
        var subject = new GpSubject();
        var hooks = new RecordingHooks();
        assertTrue(subject.install(hooks).isInstalled());
        var before = subject.backend.ownerReadRegion(GpSubject.WORLD, GpSubject.CHILD, GpSubject.OWNER).orElseThrow();
        hooks.hold(GpSubject.WORLD, GpSubject.CHILD);
        subject.shapeChanged();
        assertEquals(1, hooks.bumpsOf(GpSubject.WORLD, GpSubject.PARENT));
        assertEquals(1, hooks.bumpsOf(GpSubject.WORLD, GpSubject.CHILD));
        var after = subject.backend.ownerReadRegion(GpSubject.WORLD, GpSubject.CHILD, GpSubject.OWNER).orElseThrow();
        assertTrue(before.sameIncarnation(after));
    }

    @Test void aPresenceChangeHoldsTheInstalledGuardUntilRestart() {
        for (boolean initiallyPresent : List.of(false,true)) {
            var subject = new GpSubject();
            subject.realEstate = initiallyPresent;
            assertTrue(subject.install(new RecordingHooks()).isInstalled());
            assertTrue(subject.backend.available());
            subject.realEstate = !initiallyPresent;
            assertFalse(subject.backend.available(), "the installed guard no longer matches the add-on state");
            assertEquals(Presence.UNREADABLE, subject.backend.observe(GpSubject.WORLD, GpSubject.PARENT));
            assertTrue(subject.backend.regionAt(subject.at(10,10)).isEmpty());
            assertTrue(subject.backend.ownerReadRegion(GpSubject.WORLD, GpSubject.PARENT, GpSubject.OWNER).isEmpty());
            assertTrue(subject.backend.findRegion("gp-test", GpSubject.PARENT).isEmpty());
            assertTrue(subject.backend.describe().stream().anyMatch(line -> line.contains("restart")));
            subject.realEstate = initiallyPresent;
            assertFalse(subject.backend.available(), "a round trip must not reopen the old guard");
            assertFalse(subject.install(new RecordingHooks()).isInstalled());
        }
    }

    @Test void aHeldSubdivisionVetoesItsParentTransfer() {
        var subject = new GpSubject();
        var hooks = new RecordingHooks();
        assertEquals(GuardMode.VETO_EVENTS, subject.install(hooks).mode().orElseThrow());
        hooks.hold(GpSubject.WORLD, GpSubject.CHILD);
        assertFalse(subject.changeOwner(GpSubject.PARENT, new UUID(0,7), "default"));
        assertTrue(subject.backend.ownerReadRegion(GpSubject.WORLD, GpSubject.CHILD, GpSubject.OWNER).isPresent());
        assertTrue(hooks.bumps().isEmpty());
    }

    @Test void subdivisionsUseTheInnermostIdAndTheParentsPrincipal() {
        var subject = new GpSubject();
        var ref = subject.backend.regionAt(subject.at(13,13)).orElseThrow();
        assertEquals(GpSubject.CHILD, ref.regionId());
        assertEquals(GpSubject.OWNER, subject.backend.ownerReadAt(subject.at(13,13), GpSubject.OWNER).orElseThrow().ownerId());
        assertEquals(GpSubject.OWNER, subject.backend.findRegion("gp-test", GpSubject.CHILD).orElseThrow().ownerUuid().orElseThrow());
        assertTrue(subject.backend.findRegion("other-world", GpSubject.CHILD).isEmpty());
        assertTrue(subject.backend.ownerReadRegion(new UUID(9,9), GpSubject.CHILD, GpSubject.OWNER).isEmpty());
    }

    @Test void adminClaimsRemainPresentWithoutAPayingOwner() {
        var subject = new GpSubject();
        subject.setOwner(GpSubject.PARENT, null);
        assertEquals(Presence.PRESENT, subject.backend.observe(GpSubject.WORLD, GpSubject.PARENT));
        assertTrue(subject.backend.findRegion("gp-test", GpSubject.PARENT).orElseThrow().ownerUuid().isEmpty());
        assertTrue(subject.backend.ownerReadRegion(GpSubject.WORLD, GpSubject.PARENT, null).isEmpty());
        assertTrue(subject.backend.ownerReadRegion(GpSubject.WORLD, GpSubject.PARENT, new UUID(0,0)).isEmpty());
    }

    @Test void nonCanonicalIdsNeverReachTheClaimLookup() {
        var subject = new GpSubject();
        for (String id : List.of("", " 10", "10 ", "010", "+10", "-0", "-1", "1.0", "0x10", "9223372036854775808")) {
            assertTrue(subject.backend.ownerReadRegion(GpSubject.WORLD, id, GpSubject.OWNER).isEmpty(), id);
        }
        assertEquals(0, subject.lookups);
        assertTrue(subject.backend.ownerReadRegion(GpSubject.WORLD, GpSubject.PARENT, GpSubject.OWNER).isPresent());
        assertEquals(1, subject.lookups);
    }

    @Test void realEstatePresenceInstallsNoneAndNeverCancelsAHeldTransfer() {
        var subject = new GpSubject();
        subject.realEstate = true;
        var hooks = new RecordingHooks();
        assertEquals(GuardMode.NONE, subject.install(hooks).mode().orElseThrow());
        hooks.hold(GpSubject.WORLD, GpSubject.PARENT);
        assertTrue(subject.changeOwner(GpSubject.PARENT, new UUID(0,8), "default"));
        assertEquals(1, hooks.bumpsOf(GpSubject.WORLD, GpSubject.PARENT));
        assertEquals(1, hooks.bumpsOf(GpSubject.WORLD, GpSubject.CHILD));
    }

    @Test void recordingKeepsTheObservedTransferOrderAndCancellation() throws Exception {
        try (var input = getClass().getResourceAsStream("/recorded/16.18.7/events.json")) {
            assertNotNull(input);
            var record = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("16.18.7", record.get("version").getAsString());
            assertEquals("45e9907c61222e559ed2e099ffee0ede706a21243a31d7db549baf678708fdf0", record.get("vendorSha256").getAsString());
            var transfers = record.getAsJsonArray("events").asList().stream().map(e -> e.getAsJsonObject())
                    .filter(e -> e.get("stage").getAsString().equals("transfer-unheld")).toList();
            assertEquals(2, transfers.size());
            assertEquals(List.of("LOWEST", "MONITOR"), transfers.stream().map(e -> e.get("priority").getAsString()).toList());
            assertTrue(transfers.stream().allMatch(e -> e.get("main").getAsBoolean() && !e.get("cancelled").getAsBoolean()));
            assertNotEquals(transfers.getLast().get("owner"), transfers.getLast().get("target"));
            var held = record.getAsJsonArray("events").asList().stream().map(e -> e.getAsJsonObject())
                    .filter(e -> e.get("stage").getAsString().equals("transfer-held-child") && e.get("priority").getAsString().equals("MONITOR")).findFirst().orElseThrow();
            assertTrue(held.get("cancelled").getAsBoolean());
        }
    }

    @Test void statusTextClaimsOnlyTheLimitedLabRecordings() throws Exception {
        var subject = new GpSubject();
        assertEquals("unverified", subject.backend.label());
        assertEquals("lab-recorded on GriefPrevention 16.18.7 (limited scope); not release-verified",
                GriefPreventionBackend.VERIFICATION);
        assertTrue(subject.backend.describe().getLast().endsWith("; " + GriefPreventionBackend.VERIFICATION));
        try (var input = getClass().getResourceAsStream("/plugin.yml")) {
            assertNotNull(input);
            var text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(text.startsWith("name: ProsperityNPC-GriefPrevention\n"), text);
            assertTrue(text.contains("Unverified."), text);
            assertFalse(text.toLowerCase(Locale.ROOT).contains("tested"), text);
        }
    }

    @Test void aVetoLogsOneLineThatDoesNotAssumeAPayment() {
        var subject = new GpSubject();
        var hooks = new RecordingHooks();
        assertTrue(subject.install(hooks).isInstalled());
        hooks.hold(GpSubject.WORLD, GpSubject.CHILD);
        assertFalse(subject.changeOwner(GpSubject.PARENT, new UUID(0,9), "default"));
        assertEquals(List.of("ProsperityNPC held a claim transfer on 10: an ownership operation was in progress."),
                subject.logged);
    }
}

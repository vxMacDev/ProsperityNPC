package com.prosperitysmp.npc.publication;

import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.publication.bootstrap.ChronicleBootstrap;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The startup-agent bookkeeping and the provisioning entry points that need neither a server nor a credential. */
class ChronicleBootstrapStateTest {
    /*
     * premain records its phase in JVM-wide static state that has no reset, so this test only holds on the first premain
     * call in a test JVM. Nothing else in the suite calls premain.
     */
    @Test
    void aStartupThatCannotProveItselfLeavesNoEvidenceAndASecondStartupIsHeld() {
        assertEquals("NO_PREMAIN", ChronicleBootstrap.diagnosticStatus());
        assertArrayEquals(new String[0], ChronicleBootstrap.evidence());

        ChronicleBootstrap.premain("unexpected-option", null);
        assertEquals("HELD_STARTUP_CLASSLOADER", ChronicleBootstrap.diagnosticStatus());
        assertArrayEquals(new String[0], ChronicleBootstrap.evidence());

        ChronicleBootstrap.premain(null, null);
        assertEquals("HELD_REPEATED_PREMAIN", ChronicleBootstrap.diagnosticStatus());
        assertArrayEquals(new String[0], ChronicleBootstrap.evidence());
    }

    @Test
    void withoutAnAgentStartupThereIsNoBootstrapStatusToApprove() {
        assertTrue(ChronicleProvisioning.bootstrapStatus().isEmpty());
    }

    @Test
    void theDiagnosticStatusIsAlwaysOneRedactedPhaseWord() {
        assertTrue(ChronicleProvisioning.bootstrapDiagnosticStatus()
                .matches("READY|NO_PREMAIN|HELD_[A-Z_]{1,48}|UNAVAILABLE_SYSTEM_AGENT"), ChronicleProvisioning.bootstrapDiagnosticStatus());
    }

    @Test
    void aDisabledRuntimeStaysDisabledWhateverTheDataFolderHolds(@TempDir Path data) throws Exception {
        var runtime = new ChronicleRuntime(new Database(), Clock.systemUTC(), ZoneOffset.UTC, 4, ChronicleRuntime.Settings.disabled());
        try {
            assertEquals(ChronicleRuntime.Status.DISABLED, runtime.status());
            assertEquals(ChronicleProvisioning.Result.DISABLED, ChronicleProvisioning.tryEnable(runtime, data));
            Files.writeString(data.resolve(ChronicleProvisioning.APPROVAL_FILE), "{}");
            assertEquals(ChronicleProvisioning.Result.DISABLED, ChronicleProvisioning.tryEnable(runtime, data));
        } finally {
            runtime.shutdown().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void provisioningNeedsARuntimeAndAFolder(@TempDir Path data) throws Exception {
        assertThrows(NullPointerException.class, () -> ChronicleProvisioning.tryEnable(null, data));
        var runtime = new ChronicleRuntime(new Database(), Clock.systemUTC(), ZoneOffset.UTC, 4, ChronicleRuntime.Settings.disabled());
        try {
            assertThrows(NullPointerException.class, () -> ChronicleProvisioning.tryEnable(runtime, null));
        } finally {
            runtime.shutdown().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void theApprovalFileHasItsDocumentedName() {
        assertEquals("chronicle-approval.json", ChronicleProvisioning.APPROVAL_FILE);
    }
}

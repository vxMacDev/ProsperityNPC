package com.prosperitysmp.npc.economy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** The allowlist's root and service-name grammar, and its strict UTF-8 reading. */
class RecoveryServersRootTest {

    @ParameterizedTest
    @ValueSource(strings = {"/opt", "/opt/minecraft", "/data/mc/server-1", "/opt/.hidden", "/opt/..x", "/opt/x..",
            "/opt/señor", "/a/b/c/d/e/f"})
    void plainAbsolutePathsAreValidRoots(String root) {
        assertTrue(RecoveryServers.validRoot(root), root);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/", "//", "opt", "/opt/", "/opt//x", "/./opt", "/opt/..", "/opt/x y", "/opt/x\u00a0y",
            "/opt/x\u2003y", "/opt/x\u0007", "/opt/x\u007f", "/opt/x\ny", "\\opt\\x"})
    void anythingElseIsNotAValidRoot(String root) {
        assertFalse(RecoveryServers.validRoot(root), root);
    }

    @Test
    void serviceNamesMayUseAtSignsUnderscoresAndDashesUpToSixtyFourCharacters() {
        assertEquals("/opt/a", RecoveryServers.rootFor("minecraft@a=/opt/a\n".getBytes(StandardCharsets.UTF_8), "minecraft@a"));
        assertEquals("/opt/b", RecoveryServers.rootFor("mc_dev-2=/opt/b\n".getBytes(StandardCharsets.UTF_8), "mc_dev-2"));
        String longest = "m" + "x".repeat(63);
        assertEquals("/opt/c", RecoveryServers.rootFor((longest + "=/opt/c\n").getBytes(StandardCharsets.UTF_8), longest));
        String tooLong = longest + "x";
        assertEquals("Unsupported service", assertThrows(IllegalArgumentException.class,
                () -> RecoveryServers.rootFor((tooLong + "=/opt/c\n").getBytes(StandardCharsets.UTF_8), tooLong)).getMessage());
    }

    @Test
    void aMissingServiceIsUnsupported() {
        assertEquals("Unsupported service", assertThrows(IllegalArgumentException.class,
                () -> RecoveryServers.rootFor("a=/opt/a\n".getBytes(StandardCharsets.UTF_8), null)).getMessage());
    }

    @Test
    void refusalsNameTheRuleThatFailed() {
        assertEquals("Recovery allowlist repeats service a", assertThrows(IllegalArgumentException.class,
                () -> RecoveryServers.rootFor("a=/opt/a\na=/opt/b\n".getBytes(StandardCharsets.UTF_8), "a")).getMessage());
        assertEquals("Recovery allowlist roots must be distinct and not nested", assertThrows(IllegalArgumentException.class,
                () -> RecoveryServers.rootFor("a=/opt/a\nb=/opt/a/b\n".getBytes(StandardCharsets.UTF_8), "a")).getMessage());
        assertEquals("Recovery allowlist lines must be service=/absolute/server/root", assertThrows(IllegalArgumentException.class,
                () -> RecoveryServers.rootFor("a=/opt/a/\n".getBytes(StandardCharsets.UTF_8), "a")).getMessage());
    }

    @Test
    void aLaterBrokenLineRefusesEvenAfterTheServiceWasFound() {
        assertThrows(IllegalArgumentException.class,
                () -> RecoveryServers.rootFor("a=/opt/a\n=/opt/b\n".getBytes(StandardCharsets.UTF_8), "a"));
    }

    @Test
    void strictUtf8DecodesValidTextAndRefusesMalformedBytesNamingTheLabel() {
        assertEquals("señor", RecoveryServers.utf8("señor".getBytes(StandardCharsets.UTF_8), "File"));
        assertEquals("", RecoveryServers.utf8(new byte[0], "File"));
        for (byte[] bad : new byte[][]{{(byte) 0xc0, (byte) 0xaf}, {(byte) 0xed, (byte) 0xa0, (byte) 0x80}, {(byte) 0xff},
                {'a', (byte) 0xe2, (byte) 0x82}}) {
            assertEquals("Evidence must be UTF-8",
                    assertThrows(IllegalArgumentException.class, () -> RecoveryServers.utf8(bad, "Evidence")).getMessage());
        }
    }
}

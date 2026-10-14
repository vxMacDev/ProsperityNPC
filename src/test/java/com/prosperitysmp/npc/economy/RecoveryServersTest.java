package com.prosperitysmp.npc.economy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecoveryServersTest {
    private static String rootFor(String file, String service) {
        return RecoveryServers.rootFor(file.getBytes(StandardCharsets.UTF_8), service);
    }

    @Test
    void listedServiceReturnsItsRoot() {
        String file = "# servers\nminecraft=/opt/minecraft/server\r\n\nminecraft-dev=/opt/minecraft/dev\n";
        assertEquals("/opt/minecraft/server", rootFor(file, "minecraft"));
        assertEquals("/opt/minecraft/dev", rootFor(file, "minecraft-dev"));
    }

    @Test
    void unlistedServiceOrEmptyFileRefuses() {
        assertThrows(IllegalArgumentException.class, () -> rootFor("", "minecraft"));
        assertThrows(IllegalArgumentException.class, () -> rootFor("# only a comment\n", "minecraft"));
        assertThrows(IllegalArgumentException.class, () -> rootFor("minecraft=/opt/minecraft\n", "minecraft-dev"));
    }

    @Test
    void duplicateServiceRefusesEvenWhenBothRootsAgree() {
        assertThrows(IllegalArgumentException.class,
                () -> rootFor("minecraft=/opt/a\nminecraft=/opt/b\n", "minecraft"));
        assertThrows(IllegalArgumentException.class,
                () -> rootFor("minecraft=/opt/a\nminecraft=/opt/a\n", "minecraft"));
    }

    @Test
    void sharedOrNestedRootsRefuse() {
        assertThrows(IllegalArgumentException.class, () -> rootFor("a=/opt/x\nb=/opt/x\n", "a"));
        assertThrows(IllegalArgumentException.class, () -> rootFor("a=/opt/x\nb=/opt/x/sub\n", "a"));
        assertThrows(IllegalArgumentException.class, () -> rootFor("a=/opt/x/sub\nb=/opt/x\n", "b"));
        assertEquals("/opt/x", rootFor("a=/opt/x\nb=/opt/xy\n", "a"));
    }

    @Test
    void anyMalformedLineRefusesTheWholeFile() {
        assertThrows(IllegalArgumentException.class,
                () -> rootFor("minecraft=/opt/minecraft\nthis is not an entry\n", "minecraft"));
        assertThrows(IllegalArgumentException.class,
                () -> rootFor("minecraft=/opt/minecraft\nother:/opt/other\n", "minecraft"));
        assertThrows(IllegalArgumentException.class,
                () -> RecoveryServers.rootFor(new byte[]{'m', '=', '/', (byte) 0xff}, "m"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-minecraft", "mine craft", "mine/craft", "a/../b", "minecraft.service", "minecraft.socket"})
    void malformedServiceNamesRefuse(String service) {
        assertThrows(IllegalArgumentException.class, () -> rootFor(service + "=/opt/x\n", service));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/", "relative/root", "/opt//minecraft", "/opt/minecraft/", "/opt/./minecraft",
            "/opt/../etc", "/opt/minecraft/..", "/opt/minecraft ", " /opt/minecraft", "/opt/mine craft",
            "/opt/mine\tcraft", "/opt/mine craft", "/opt/mine craft"})
    void unsafeRootsRefuse(String root) {
        assertThrows(IllegalArgumentException.class, () -> rootFor("minecraft=" + root + "\n", "minecraft"));
    }

    @Test
    void spacesAroundTheSeparatorRefuse() {
        assertThrows(IllegalArgumentException.class, () -> rootFor("minecraft =/opt/minecraft\n", "minecraft"));
        assertThrows(IllegalArgumentException.class, () -> rootFor("minecraft= /opt/minecraft\n", "minecraft"));
    }
}

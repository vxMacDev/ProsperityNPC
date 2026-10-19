package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.api.Capabilities;
import com.prosperitysmp.npc.api.LegResponse;
import com.prosperitysmp.npc.api.MoneySource;
import org.bukkit.OfflinePlayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/** The diagnose line's provider hash: the first eight hex characters, or "unavailable" for anything it cannot read. */
class MoneySourcesArtifactTest {
    private static final String ABC_SHA8 = "ba7816bf";

    private static MoneySource source(Supplier<Optional<MoneySource.Pin>> pin) {
        return new MoneySource() {
            @Override public String id() { return "test"; }
            @Override public int apiMajor() { return 1; }
            @Override public int apiMinor() { return 0; }
            @Override public Capabilities capabilities() { return Capabilities.builder().build(); }
            @Override public Optional<Pin> pin() { return pin.get(); }
        };
    }

    private static MoneySource.Pin pin(Supplier<Path> artifact) {
        return new MoneySource.Pin() {
            @Override public boolean stillCurrent() { return true; }
            @Override public boolean enabled() { return true; }
            @Override public Path artifact() { return artifact.get(); }
            @Override public double balanceDollars(OfflinePlayer account) { throw new AssertionError("no balance read"); }
            @Override public LegResponse leg(OfflinePlayer account, long cents, MoneySource.Leg leg) { throw new AssertionError("no money call"); }
        };
    }

    @Test
    void aReadableProviderJarGivesTheFirstEightHexCharactersOfItsSha256(@TempDir Path dir) throws Exception {
        Path jar = Files.write(dir.resolve("economy.jar"), "abc".getBytes(StandardCharsets.US_ASCII));
        assertEquals(ABC_SHA8, MoneySources.artifactSha8(source(() -> Optional.of(pin(() -> jar)))));
    }

    @Test
    void noSourceOrNoPinIsUnavailable() {
        assertEquals("unavailable", MoneySources.artifactSha8(null));
        assertEquals("unavailable", MoneySources.artifactSha8(source(Optional::empty)));
        assertEquals("unavailable", MoneySources.artifactSha8(new UnavailableMoneySource("vault", "Vault is not installed")));
    }

    @Test
    void aPinOrJarThatCannotBeReadIsUnavailableNotThrown(@TempDir Path dir) throws Exception {
        assertEquals("unavailable", MoneySources.artifactSha8(source(() -> { throw new IllegalStateException("pin"); })));
        assertEquals("unavailable", MoneySources.artifactSha8(source(() -> { throw new NoClassDefFoundError("vault"); })));
        assertEquals("unavailable", MoneySources.artifactSha8(source(() -> Optional.of(pin(() -> {
            throw new IllegalStateException("Provider jar unavailable");
        })))));
        Path missing = dir.resolve("missing.jar");
        assertEquals("unavailable", MoneySources.artifactSha8(source(() -> Optional.of(pin(() -> missing)))));
        Path empty = Files.createFile(dir.resolve("empty.jar"));
        assertEquals("unavailable", MoneySources.artifactSha8(source(() -> Optional.of(pin(() -> empty)))));
    }
}

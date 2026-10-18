package com.prosperitysmp.npc.economy;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The reserve account is read through one resolver, live on every call, and the key appears nowhere else. */
class ReserveReadsTest {

    @Test void theKeyIsReadOnlyInReserveAccount() throws Exception {
        var found = NoVaultOutsideVaultPackageTest.filesMentioning(Path.of("src/main/java"), "\"centralbank.uuid\"");
        assertEquals(List.of("com/prosperitysmp/npc/economy/ReserveAccount.java"), found);
    }

    @Test void liveTextFollowsAReloadAtOnce() {
        var config = new YamlConfiguration();
        assertEquals("", ReserveAccount.liveText(config));
        config.set(ReserveAccount.PATH, "00000000-0000-0000-0000-000000000001");
        assertEquals("00000000-0000-0000-0000-000000000001", ReserveAccount.liveText(config));
        config.set(ReserveAccount.PATH, "00000000-0000-0000-0000-000000000002");
        assertEquals("00000000-0000-0000-0000-000000000002", ReserveAccount.liveText(config), "never cached");
        var other = new YamlConfiguration();
        assertEquals("", ReserveAccount.liveText(other), "nothing carried over from another config");
    }

    @Test void theResolverKeepsNoState() {
        for (Field field : ReserveAccount.class.getDeclaredFields())
            assertTrue(Modifier.isStatic(field.getModifiers()) && Modifier.isFinal(field.getModifiers())
                    && field.getType() == String.class, "unexpected field " + field.getName());
    }
}

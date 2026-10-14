package com.prosperitysmp.npc.publication;

import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OccupancyPublicationSettingsTest {
    @Test void shippedYamlIsDisabledAndRequiresExplicitScopesBeforeEnabling() throws Exception {
        try(var input=getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(input);
            var config=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                    new java.io.InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8));
            var section=config.getConfigurationSection("publication");assertNotNull(section);
            assertEquals(Boolean.FALSE,section.get("enabled"));
            assertEquals(List.of(),section.getList("approved-scopes"));
            assertTrue(OccupancyPublicationSettings.read(section).isEmpty());
            section.set("enabled",true);
            assertThrows(IllegalArgumentException.class,()->OccupancyPublicationSettings.read(section));
            section.set("approved-scopes",valid().getList("approved-scopes"));
            assertEquals(1,OccupancyPublicationSettings.read(section).orElseThrow().approvals().size());
        }
    }
    MemoryConfiguration valid() {
        var config=new MemoryConfiguration();config.set("enabled",true);
        config.set("refresh-seconds",60);config.set("max-age-seconds",300);
        config.set("recovery-age-seconds",3600);config.set("retention-age-seconds",86400);
        config.set("http-enabled",false);config.set("http-port",0);
        config.set("approved-scopes",List.of(Map.of("property-id",new UUID(0,1).toString(),
                "world-id",new UUID(0,2).toString(),"region-id","c001","world-key","main")));
        return config;
    }
    @Test void onlyExplicitBooleanTrueCanEnable() {
        assertTrue(OccupancyPublicationSettings.read(null).isEmpty());
        var c=valid();c.set("enabled",false);assertTrue(OccupancyPublicationSettings.read(c).isEmpty());
        for(Object invalid:List.of("true",1,"false")) {
            c.set("enabled",invalid);assertThrows(IllegalArgumentException.class,()->OccupancyPublicationSettings.read(c));
        }
        c.set("enabled",null);assertThrows(IllegalArgumentException.class,()->OccupancyPublicationSettings.read(c));
    }
    @Test void validPolicyAndScopeAreImmutable() {
        var c=valid();var settings=OccupancyPublicationSettings.read(c).orElseThrow();
        assertEquals(60,settings.interval().toSeconds());assertEquals("c001",settings.approvals().getFirst().regionId());
        assertThrows(UnsupportedOperationException.class,()->settings.approvals().clear());
        c.set("approved-scopes",List.of());assertEquals(1,settings.approvals().size());
    }
    @Test void transportNeedsExplicitBooleanAndUsablePort() {
        var c=valid();assertEquals(0,OccupancyPublicationSettings.read(c).orElseThrow().httpPort());
        c.set("http-enabled",true);assertThrows(IllegalArgumentException.class,()->OccupancyPublicationSettings.read(c));
        c.set("http-port",18099);assertEquals(18099,OccupancyPublicationSettings.read(c).orElseThrow().httpPort());
        for(Object invalid:List.of(80,65536,-1,"18099",18099.5)) {
            c.set("http-port",invalid);assertThrows(IllegalArgumentException.class,()->OccupancyPublicationSettings.read(c));
        }
        c.set("http-port",18099);c.set("http-enabled","true");
        assertThrows(IllegalArgumentException.class,()->OccupancyPublicationSettings.read(c));
    }
    @Test void rejectMissingUnknownAndNonIntegerPolicies() {
        for(Object invalid:List.of("60",1.5,0,-1,Long.MAX_VALUE)) {
            var c=valid();c.set("refresh-seconds",invalid);
            assertThrows(IllegalArgumentException.class,()->OccupancyPublicationSettings.read(c));
        }
        var equal=valid();equal.set("max-age-seconds",60);assertThrows(IllegalArgumentException.class,()->OccupancyPublicationSettings.read(equal));
        var missing=valid();missing.set("max-age-seconds",null);
        assertThrows(IllegalArgumentException.class,()->OccupancyPublicationSettings.read(missing));
        var unknown=valid();unknown.set("typo",1);
        assertThrows(IllegalArgumentException.class,()->OccupancyPublicationSettings.read(unknown));
    }
    @Test void rejectUnboundedDuplicateOrMalformedScopes() {
        var base=valid().getList("approved-scopes").getFirst();
        for(Object bad:List.of(List.of(),Collections.nCopies(513,base),List.of(base,base),
                List.of("c001"),List.of(Map.of("property-id","1-1-1-1-1","world-id",new UUID(0,2).toString(),"region-id","c001","world-key","main")))) {
            var c=valid();c.set("approved-scopes",bad);
            assertThrows(IllegalArgumentException.class,()->OccupancyPublicationSettings.read(c));
        }
    }
}

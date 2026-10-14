package com.prosperitysmp.npc.publication;

import org.bukkit.configuration.ConfigurationSection;
import java.time.Duration;
import java.util.*;
import com.prosperitysmp.npc.publication.PublicOccupancySnapshot.Approval;

/** Strict operator configuration, not proof that public disclosure has been approved. */
public record OccupancyPublicationSettings(List<Approval> approvals, Duration interval, Duration maxAge,
                                          Duration recoveryAge, Duration retentionAge, int httpPort) {
    public OccupancyPublicationSettings {
        approvals=OccupancyRefreshPump.checkedScope(approvals);
        if(approvals.isEmpty())throw new IllegalArgumentException("Publication requires explicit scopes");
        for(Duration value:Arrays.asList(interval,maxAge,recoveryAge,retentionAge))
            if(value==null || value.isNegative() || value.isZero() || value.compareTo(Duration.ofDays(30))>0)
                throw new IllegalArgumentException("Invalid publication duration");
        if(interval.compareTo(maxAge)>=0)throw new IllegalArgumentException("Refresh must precede expiry");
        if(httpPort!=0 && (httpPort<1024 || httpPort>65535))throw new IllegalArgumentException("Invalid publication port");
    }

    public static Optional<OccupancyPublicationSettings> read(ConfigurationSection section) {
        if(section==null)return Optional.empty();
        Object enabled=section.get("enabled");
        if(Boolean.FALSE.equals(enabled))return Optional.empty();
        if(!Boolean.TRUE.equals(enabled))throw new IllegalArgumentException("Publication enabled must be boolean");
        if(!section.getKeys(false).equals(Set.of("enabled","refresh-seconds","max-age-seconds",
                "recovery-age-seconds","retention-age-seconds","approved-scopes","http-enabled","http-port")))
            throw new IllegalArgumentException("Unknown or missing publication setting");
        Object raw=section.get("approved-scopes");
        if(!(raw instanceof List<?> rows) || rows.isEmpty() || rows.size()>512)
            throw new IllegalArgumentException("Publication scopes missing or excessive");
        List<Approval> approvals=new ArrayList<>();
        for(Object value:rows) {
            if(!(value instanceof Map<?,?> row) || !row.keySet().equals(Set.of("property-id","world-id","region-id","world-key")))
                throw new IllegalArgumentException("Invalid publication scope fields");
            approvals.add(new Approval(uuid(row,"property-id"),uuid(row,"world-id"),text(row,"region-id"),text(row,"world-key")));
        }
        Object httpEnabled=section.get("http-enabled"),port=section.get("http-port");
        if(!(httpEnabled instanceof Boolean enabledHttp) || !(port instanceof Integer)
                || (Integer)port<0 || (Integer)port>65535)
            throw new IllegalArgumentException("Invalid publication transport settings");
        if(enabledHttp && (Integer)port<1024)throw new IllegalArgumentException("Explicit nonprivileged port required");
        return Optional.of(new OccupancyPublicationSettings(approvals,seconds(section,"refresh-seconds"),
                seconds(section,"max-age-seconds"),seconds(section,"recovery-age-seconds"),seconds(section,"retention-age-seconds"),
                enabledHttp ? (Integer)port : 0));
    }
    private static Duration seconds(ConfigurationSection section,String key) {
        Object value=section.get(key);
        if(!(value instanceof Integer || value instanceof Long))throw new IllegalArgumentException("Publication duration must be integer");
        return Duration.ofSeconds(((Number)value).longValue());
    }
    private static String text(Map<?,?> row,String key) {
        if(!(row.get(key) instanceof String text))throw new IllegalArgumentException("Publication scope must be text");
        return text;
    }
    private static UUID uuid(Map<?,?> row,String key) {
        String text=text(row,key);UUID value=UUID.fromString(text);
        if(!value.toString().equals(text))throw new IllegalArgumentException("Publication UUID must be canonical");
        return value;
    }
}

package com.prosperitysmp.npc.publication;

import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.ReadSnapshot;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.publication.PublicOccupancySnapshot.Approval;
import java.sql.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Read-only candidate IDs, not verified events. Repeat completed cycles; never keep a permanent watermark. */
public final class ChronicleDiscoveryRepository {
    public record Cursor(UUID generation,Approval scope,UUID after,UUID through) {
        public Cursor {
            Objects.requireNonNull(generation);Objects.requireNonNull(scope);
            Objects.requireNonNull(after);Objects.requireNonNull(through);
            if(compare(after,through)>=0)throw new IllegalArgumentException("Invalid Chronicle scan cursor");
        }
    }
    /** Null continuation means this finite cycle ended, not that discovery is permanently complete. */
    public record Page(Instant capturedAt,List<UUID> requestIds,Cursor continuation) {
        public Page {Objects.requireNonNull(capturedAt);requestIds=List.copyOf(requestIds);}
    }
    private final Database database;
    private final Clock clock;
    public ChronicleDiscoveryRepository(Database database,Clock clock) {
        this.database=Objects.requireNonNull(database);this.clock=Objects.requireNonNull(clock);
    }

    /** One property per page; a host must schedule approved properties fairly and persist progress separately. */
    public Page page(Approval scope,UUID generation,Cursor cursor,int limit) throws SQLException {
        Objects.requireNonNull(scope);Objects.requireNonNull(generation);
        if(limit<1 || limit>128)throw new IllegalArgumentException("Invalid Chronicle scan limit");
        if(cursor!=null && (!generation.equals(cursor.generation()) || !scope.equals(cursor.scope())))
            throw new IllegalArgumentException("Chronicle scan scope changed");
        try(ReadSnapshot snapshot=ReadSnapshot.open(database,clock)) {
            Connection c=snapshot.connection(database);
            UUID through=cursor==null ? upperBound(c,scope) : cursor.through();
            if(through==null)return new Page(snapshot.capturedAt(),List.of(),null);
            String sql="SELECT request_id FROM npc_housing_moves WHERE property_id=? AND request_id<=?"
                    +(cursor==null ? "" : " AND request_id>?")+" ORDER BY request_id LIMIT ?";
            var ids=new ArrayList<UUID>();
            try(PreparedStatement ps=c.prepareStatement(sql)) {
                ps.setQueryTimeout(5);ps.setBytes(1,Uuids.toBytes(scope.propertyId()));ps.setBytes(2,Uuids.toBytes(through));
                int index=3;if(cursor!=null)ps.setBytes(index++,Uuids.toBytes(cursor.after()));ps.setInt(index,limit+1);
                warnings(c,ps,null);
                try(ResultSet rs=ps.executeQuery()) {
                    warnings(c,ps,rs);
                    UUID prior=cursor==null ? null : cursor.after();
                    while(rs.next()) {
                        UUID id=id(rs);warnings(c,ps,rs);
                        if(ids.size()>limit || compare(id,through)>0 || prior!=null && compare(prior,id)>=0)
                            throw new SQLException("Invalid Chronicle scan order");
                        ids.add(id);prior=id;
                    }
                    warnings(c,ps,rs);
                }
            }
            Cursor next=null;
            if(ids.size()>limit) {
                ids.removeLast();next=new Cursor(generation,scope,ids.getLast(),through);
            }
            return new Page(snapshot.capturedAt(),ids,next);
        }
    }

    private static UUID upperBound(Connection c,Approval scope) throws SQLException {
        try(PreparedStatement ps=c.prepareStatement("SELECT request_id FROM npc_housing_moves WHERE property_id=? ORDER BY request_id DESC LIMIT 1")) {
            ps.setQueryTimeout(5);ps.setBytes(1,Uuids.toBytes(scope.propertyId()));warnings(c,ps,null);
            try(ResultSet rs=ps.executeQuery()) {
                warnings(c,ps,rs);UUID result=rs.next() ? id(rs) : null;
                if(rs.next())throw new SQLException("Excess Chronicle scan bounds");warnings(c,ps,rs);return result;
            }
        }
    }
    private static UUID id(ResultSet rs) throws SQLException {
        byte[] bytes=rs.getBytes("request_id");
        if(bytes==null || bytes.length!=16)throw new SQLException("Invalid Chronicle source identity");
        return Uuids.fromBytes(bytes);
    }
    private static int compare(UUID left,UUID right) {
        return Arrays.compareUnsigned(Uuids.toBytes(left),Uuids.toBytes(right));
    }
    private static void warnings(Connection c,PreparedStatement ps,ResultSet rs) throws SQLException {
        if(c.getWarnings()!=null || ps.getWarnings()!=null || rs!=null && rs.getWarnings()!=null)
            throw new SQLException("Incomplete Chronicle discovery");
    }
}

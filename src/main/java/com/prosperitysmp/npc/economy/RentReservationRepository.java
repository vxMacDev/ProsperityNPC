package com.prosperitysmp.npc.economy;

import com.prosperitysmp.npc.clock.EconomyClock;
import com.prosperitysmp.npc.config.EconomyLimits;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.formulas.PropertyFormulas;
import com.prosperitysmp.npc.formulas.UpgradeType;
import com.prosperitysmp.npc.market.HousingDecisionPlan;
import com.prosperitysmp.npc.market.HousingMoveRepository;
import com.prosperitysmp.npc.market.HousingMoveRequest;
import com.prosperitysmp.npc.market.HousingPolicy;
import com.prosperitysmp.npc.market.OwnerHousingView;
import com.prosperitysmp.npc.property.OwnerDeskData;
import com.prosperitysmp.npc.property.Property;
import com.prosperitysmp.npc.property.ServiceAssessmentRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** SQL reservation only. Caller holds current ARM leases; the dispatcher must still check the reserve floor. */
public final class RentReservationRepository {
    public record Reserved(Property property, long eventId, long cents) { }
    private record Candidate(Property property, long utility, List<UUID> residents, long requested) { }
    private final Database database;
    private final TransferJournal transfers;
    private final UUID reserve;
    private final Clock clock;
    private final ZoneId zone;
    private final int resetHour;
    private final boolean premiumEnabled;
    // Null: every owner is its own benefit unit and no link table is read (the old constructors).
    private final AccountLinkRepository links;
    private final java.util.function.BooleanSupplier linkedEnabled;

    public RentReservationRepository(Database database, UUID reserve) {
        this(database,reserve,Clock.systemUTC(),ZoneId.of("America/Chicago"),4,false);
    }

    public RentReservationRepository(Database database, UUID reserve, Clock clock, ZoneId zone,
                                     int resetHour, boolean premiumEnabled) {
        this(database,reserve,clock,zone,resetHour,premiumEnabled,null,()->false);
    }

    /**
     * linked-accounts: links groups linked accounts for the benefit-unit cap only. linkedEnabled is read once per
     * batch on the batch's thread (linked-accounts.enabled). Events still name each home's own ARM owner as
     * counterparty and benefit_unit_id, so every proof and journal key is unchanged.
     */
    public RentReservationRepository(Database database, UUID reserve, Clock clock, ZoneId zone,
                                     int resetHour, boolean premiumEnabled, AccountLinkRepository links,
                                     java.util.function.BooleanSupplier linkedEnabled) {
        this.links=links;
        this.linkedEnabled=Objects.requireNonNull(linkedEnabled);
        this.database=Objects.requireNonNull(database);
        this.reserve=Objects.requireNonNull(reserve);
        this.transfers=new TransferJournal(database,reserve);
        this.clock=Objects.requireNonNull(clock);this.zone=Objects.requireNonNull(zone);
        if(resetHour<0 || resetHour>23)throw new IllegalArgumentException("Invalid rent reset hour");
        this.resetHour=resetHour;this.premiumEnabled=premiumEnabled;
    }

    /** Only current-day queued reservations, bound again to the leased owner snapshot. */
    public List<Reserved> pending(LocalDate day,List<Property> owned)throws SQLException {
        return pending(day,owned,"npc_rent_reservations",EventSource.RENT,"rent");
    }

    /** Queued appreciation for the given (closed) economy day only, bound again to the leased owner snapshot. */
    public List<Reserved> pendingAppreciation(LocalDate day,List<Property> owned)throws SQLException {
        return pending(day,owned,"npc_appreciation_reservations",EventSource.APPRECIATION,"appreciation");
    }

    private List<Reserved> pending(LocalDate day,List<Property> owned,String table,EventSource source,String noun)throws SQLException {
        if(owned.size()>512)throw new IllegalArgumentException("Rent scope");
        try(Connection c=database.getConnection()) { return pending(c,day,owned,table,source,noun); }
    }

    private static List<Reserved> pending(Connection c,LocalDate day,List<Property> owned,String table,EventSource source,String noun)throws SQLException {
        if(owned.size()>512)throw new IllegalArgumentException("Rent scope");
        var result=new ArrayList<Reserved>();
        {
            for(var p:owned) {
                try(var ps=sql(c,"SELECT e.event_id,e.requested_amount_cents,e.counterparty_uuid,e.ownership_epoch,e.source,e.direction,e.property_id,e.benefit_unit_id,e.economy_day "
                        +"FROM "+table+" r JOIN npc_economy_events e ON e.event_id=r.event_id "
                        +"WHERE r.property_id=? AND r.economy_day=? AND e.state='PENDING'")) {
                    id(ps,1,p.propertyId());ps.setString(2,day.toString());
                    try(var rs=ps.executeQuery()) {
                        if(!rs.next())continue;
                        if(!p.ownerUuid().equals(uuid(rs,"counterparty_uuid")) || p.ownershipEpoch()!=rs.getInt("ownership_epoch")
                                || !p.propertyId().equals(uuid(rs,"property_id")) || !p.ownerUuid().equals(uuid(rs,"benefit_unit_id"))
                                || !day.toString().equals(rs.getString("economy_day"))
                                || !source.key().equals(rs.getString("source"))
                                || !source.direction().moneyFlowKey().equals(rs.getString("direction"))
                                || rs.getLong("requested_amount_cents")<=0)throw new SQLException("Queued "+noun+" ownership mismatch");
                        result.add(new Reserved(p,rs.getLong("event_id"),rs.getLong("requested_amount_cents")));
                        if(rs.next())throw new SQLException("Duplicate daily "+noun+" reservation");
                    }
                }
            }
        }
        return List.copyOf(result);
    }

    /** Bounded batch, sorted identities, owner then server pro-rata. Replays cannot reserve a second event. */
    public List<Reserved> reserve(LocalDate day, List<Property> owned, EconomyLimits limits) throws SQLException {
        return reserve(day, owned, limits, false);
    }

    /**
     * holdDueRentChanges (housing.rent-change-delay-days in use): under the property's row lock, a home whose
     * pending asking-rent change is due on or before day gets no reservation, so it is never paid today's rent at
     * the old rate. It waits for the rent-change step; with no reservation written, the next batch after that step
     * pays it at the new rate. Until then the ledger rent hold treats that home's rent as outstanding
     * (heldForDueRentChange), so appreciation cannot spend the reserve headroom it needs. False: exactly the
     * plain batch, and the pending columns are not read.
     */
    public List<Reserved> reserve(LocalDate day, List<Property> owned, EconomyLimits limits, boolean holdDueRentChanges)
            throws SQLException {
        return reserveCounting(day, owned, limits, holdDueRentChanges).reserved();
    }

    /**
     * reserve's result plus heldForDueChange: the homes in owned that this call skipped for a due, unapplied
     * asking-rent change and that have no rent reservation for day (the heldForDueRentChange rule, counted inside
     * the same transaction). The change can be applied right after this commits, so a later SQL check would no
     * longer see it; the caller holds this batch's appreciation whenever the count is above zero.
     * Always 0 when holdDueRentChanges is false (the pending columns are not read).
     */
    public record Reservation(List<Reserved> reserved, int heldForDueChange) {
        public Reservation {
            reserved = List.copyOf(reserved);
            if (heldForDueChange < 0) throw new IllegalArgumentException("Negative due-change count");
        }
    }

    /** reserve(day, owned, limits, holdDueRentChanges), also reporting heldForDueChange (see Reservation). */
    public Reservation reserveCounting(LocalDate day, List<Property> owned, EconomyLimits limits, boolean holdDueRentChanges)
            throws SQLException {
        Objects.requireNonNull(day); Objects.requireNonNull(limits);
        if(owned.size()>512 || day.getYear()<1 || day.getYear()>9999)throw new IllegalArgumentException("Rent scope");
        var sorted=owned.stream().sorted(Comparator.comparing(p->p.propertyId().toString())).toList();
        if(sorted.stream().map(Property::propertyId).distinct().count()!=sorted.size())throw new IllegalArgumentException("Duplicate property");
        try(Connection c=database.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setAutoCommit(false);
            try {
                try(var ps=sql(c,"SELECT singleton FROM npc_rent_mutex WHERE singleton=1 FOR UPDATE");var rs=ps.executeQuery()) {
                    if(!rs.next())throw new SQLException("Rent mutex unavailable");
                }
                var receipts=new HousingMoveRepository(database,Clock.fixed(clock.instant(),zone),zone,resetHour);
                var units=units(c,day);
                var spent=spent(c,day,units);
                var ownerSpent=spent.owner(); var propertySpent=spent.property();
                var pendingLifetime=spent.pendingLifetime(); long serverSpent=spent.server();
                var candidates=new ArrayList<Candidate>();
                int heldForDueChange=0;
                for(var property:sorted) {
                    if(!property.enabled() || property.ownerUuid()==null)continue;
                    verifyProperty(c,property);
                    if(holdDueRentChanges && dueRentChange(c,property,day)) {
                        // Still owed today's rent: no reservation yet, and none is written for it in this batch.
                        if(!exists(c,"SELECT 1 FROM npc_rent_reservations WHERE property_id=? AND economy_day=?",property.propertyId(),day))
                            heldForDueChange++;
                        continue;
                    }
                    if(exists(c,"SELECT 1 FROM npc_rent_reservations WHERE property_id=? AND economy_day=?",property.propertyId(),day))continue;
                    long utility=paidUtility(c,property,day);
                    if(utility==0)continue;
                    var residents=new ArrayList<UUID>(); long rent=0;
                    try(var ps=sql(c,"SELECT t.tenant_id,t.tier,t.slot,t.row_version FROM npc_tenants t JOIN npc_resident_profiles r ON r.resident_id=t.tenant_id "
                            +"WHERE t.property_id=? ORDER BY t.tenant_id LIMIT 5 FOR UPDATE")) {
                        id(ps,1,property.propertyId());
                        try(var rs=ps.executeQuery()) {
                            while(rs.next()) {
                                if(residents.size()>=property.capacity())throw new SQLException("Rent occupancy exceeds capacity");
                                UUID resident=uuid(rs,"tenant_id");
                                int slot=rs.getInt("slot");
                                if(rs.wasNull())throw new SQLException("Missing rent tenant slot");
                                long revision=rs.getLong("row_version");
                                if(rs.wasNull())throw new SQLException("Missing rent tenancy revision");
                                int tierPercent=tenantTierPercent(c,property,resident,rs.getString("tier"),slot,revision,receipts);
                                if(exists(c,"SELECT 1 FROM npc_rent_cohort WHERE resident_id=? AND economy_day=?",resident,day))continue;
                                residents.add(resident);
                                rent=Math.addExact(rent,PropertyFormulas.tenantRentCents(property.askingRentCents(),property.referenceRentCents(),tierPercent,limits.tenantDailyCapCents()));
                            }
                        }
                    }
                    long funding=funding(c,property,limits,pendingLifetime);
                    rent=Math.min(rent,Math.min(funding,remaining(limits.propertyDailyCapCents(),propertySpent.getOrDefault(property.propertyId(),0L))));
                    if(rent>0)candidates.add(new Candidate(property,utility,List.copyOf(residents),rent));
                }
                long[] amounts=allocate(candidates.stream().mapToLong(Candidate::requested).toArray(),
                        candidates.stream().map(x->x.property().ownerUuid()).toList(),spent,limits,units);
                var result=new ArrayList<Reserved>();
                for(int i=0;i<candidates.size();i++) {
                    if(amounts[i]==0)continue;
                    var candidate=candidates.get(i); var p=candidate.property();
                    long event=EconomyEventRepository.insertPending(c,EconomyEvent.pending("rent-v1|"+day+"|"+p.propertyId(),amounts[i],
                            EventSource.RENT,p.propertyId(),p.ownerUuid(),p.ownershipEpoch(),p.ownerUuid(),day.toString()));
                    if(event<=0)throw new SQLException("Rent event exists without its reservation");
                    try(var ps=sql(c,"INSERT INTO npc_rent_reservations(property_id,economy_day,event_id,utility_event_id) VALUES (?,?,?,?)")) {
                        id(ps,1,p.propertyId());ps.setString(2,day.toString());ps.setLong(3,event);ps.setLong(4,candidate.utility());ps.executeUpdate();
                    }
                    for(UUID resident:candidate.residents()) {
                        try(var ps=sql(c,"INSERT INTO npc_rent_cohort(resident_id,economy_day,event_id) VALUES (?,?,?)")) {
                            id(ps,1,resident);ps.setString(2,day.toString());ps.setLong(3,event);ps.executeUpdate();
                        }
                    }
                    result.add(new Reserved(p,event,amounts[i]));
                }
                c.commit();return new Reservation(result,heldForDueChange);
            } catch(SQLException|RuntimeException failure) {
                try{c.rollback();}catch(SQLException rollback){failure.addSuppressed(rollback);}throw failure;
            }
        }
    }

    /** Why a payout reservation was retired. Stored verbatim in npc_payout_retirements.reason. */
    public enum RetirementReason { EXPIRED, OWNER_CHANGED }
    /** One retired payout: the event, its kind (RENT or APPRECIATION) and why. */
    public record Retirement(long eventId, EventSource source, UUID propertyId, LocalDate day, RetirementReason reason) { }
    private record RetireCandidate(long eventId, EventSource source, String table) { }

    /**
     * Retires payout reservations that can never be paid and were never attempted, so they stop holding
     * lifetime funding and stop filling the outstanding-ledger scan. A candidate is a PENDING rent row from
     * a day before today, a PENDING appreciation row from a day before the closed day (today - 1), or any
     * PENDING rent or appreciation row whose owner or ownership epoch no longer matches the property.
     * "Never attempted" means no npc_transfer_attempts row exists for the event: beginDebit writes that row
     * in the same commit that claims the event, so without it no debit was ever dispatched. Any attempt at
     * all leaves the row for staff, untouched. Each retirement records why and moves the event PENDING to
     * FAILED with paid 0 in one transaction under the rent mutex, so beginDebit refuses it forever. Bounded
     * by limit; a replay finds nothing new. Returns what this call retired.
     */
    public List<Retirement> retireStale(LocalDate today,int limit)throws SQLException {
        Objects.requireNonNull(today);
        if(limit<1 || limit>500 || today.getYear()<2 || today.getYear()>9999)throw new IllegalArgumentException("Retirement scope");
        LocalDate closed=today.minusDays(1);
        try(Connection c=database.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setAutoCommit(false);
            try {
                try(var ps=sql(c,"SELECT singleton FROM npc_rent_mutex WHERE singleton=1 FOR UPDATE");var rs=ps.executeQuery()) {
                    if(!rs.next())throw new SQLException("Rent mutex unavailable");
                }
                var candidates=new ArrayList<RetireCandidate>();
                var result=retireCandidates(c,null,today,closed,limit);
                c.commit();return result;
            } catch(SQLException|RuntimeException failure) {
                try{c.rollback();}catch(SQLException rollback){failure.addSuppressed(rollback);}throw failure;
            }
        }
    }

    /**
     * Same retirement as retireStale, limited to one property's rows, inside the CALLER's open transaction
     * (autocommit off; the caller commits or rolls back). It takes the rent mutex first, as retireStale does,
     * so the caller must not already hold that property's row lock or any event lock. Resume uses it so a
     * paused home's own never-attempted, unpayable reservations stop refusing the resume even when no rent
     * batch can run. A row with any transfer attempt, or a current-day row still bound to the current owner
     * and epoch, is never retired here either.
     */
    public static List<Retirement> retireStaleFor(Connection c,UUID propertyId,LocalDate today,int limit)throws SQLException {
        Objects.requireNonNull(c);Objects.requireNonNull(propertyId);Objects.requireNonNull(today);
        if(limit<1 || limit>500 || today.getYear()<2 || today.getYear()>9999)throw new IllegalArgumentException("Retirement scope");
        lockMutex(c);
        return retireCandidates(c,propertyId,today,today.minusDays(1),limit);
    }

    /**
     * Takes the rent mutex inside the CALLER's open transaction (autocommit off). The mutex comes first in the
     * global lock order, so the caller must not yet hold any property row or event lock. Resume calls it before
     * its property lock, then retireHeldFor after its owner write.
     */
    public static void lockMutex(Connection c)throws SQLException {
        Objects.requireNonNull(c);
        if(c.getAutoCommit())throw new IllegalStateException("Retirement requires the caller's transaction");
        try(var ps=sql(c,"SELECT singleton FROM npc_rent_mutex WHERE singleton=1 FOR UPDATE");var rs=ps.executeQuery()) {
            if(!rs.next())throw new SQLException("Rent mutex unavailable");
        }
    }

    /**
     * retireStaleFor for a caller that already took the rent mutex with lockMutex in this same transaction and
     * may now hold the property's row lock (re-entrant here). Resume runs it after writing the current ARM owner
     * and epoch + 1, so both the discovery query and retireOne's proof compare against that new owner: an old
     * owner's never-attempted reservation, even for today, retires as OWNER_CHANGED and can never be paid once
     * the resume commits. Rows with any transfer attempt are still left for staff. Re-locking the mutex is a
     * no-op when it is already held and is kept only as a guard that it exists.
     */
    public static List<Retirement> retireHeldFor(Connection c,UUID propertyId,LocalDate today,int limit)throws SQLException {
        Objects.requireNonNull(c);Objects.requireNonNull(propertyId);Objects.requireNonNull(today);
        if(limit<1 || limit>500 || today.getYear()<2 || today.getYear()>9999)throw new IllegalArgumentException("Retirement scope");
        lockMutex(c);
        return retireCandidates(c,propertyId,today,today.minusDays(1),limit);
    }

    /** Caller holds the rent mutex in an open transaction. Null property: every property, as retireStale. */
    private static List<Retirement> retireCandidates(Connection c,UUID propertyId,LocalDate today,LocalDate closed,int limit)throws SQLException {
        var candidates=new ArrayList<RetireCandidate>();
        candidates(c,"npc_rent_reservations",EventSource.RENT,today,propertyId,limit,candidates);
        candidates(c,"npc_appreciation_reservations",EventSource.APPRECIATION,closed,propertyId,limit,candidates);
        candidates.sort(Comparator.comparingLong(RetireCandidate::eventId));
        var result=new ArrayList<Retirement>();
        for(var candidate:candidates.subList(0,Math.min(limit,candidates.size()))) {
            var retired=retireOne(c,candidate,today,closed);
            if(retired!=null)result.add(retired);
        }
        return List.copyOf(result);
    }

    /** Unlocked discovery only; every candidate is proved again under row locks in retireOne. */
    private static void candidates(Connection c,String table,EventSource source,LocalDate lastLive,UUID propertyId,int limit,
                                   List<RetireCandidate> into)throws SQLException {
        try(var ps=sql(c,"SELECT e.event_id FROM "+table+" r JOIN npc_economy_events e ON e.event_id=r.event_id "
                +"JOIN npc_properties p ON p.property_id=r.property_id "
                +"WHERE e.state='PENDING' AND e.claimed_at IS NULL AND e.paid_amount_cents IS NULL AND e.ownership_epoch IS NOT NULL "
                +"AND e.source=? AND e.direction=? AND e.economy_day=r.economy_day AND e.property_id=r.property_id "
                +"AND (r.economy_day<? OR NOT (p.owner_uuid <=> e.counterparty_uuid) OR NOT (p.ownership_epoch <=> e.ownership_epoch)) "
                +"AND NOT EXISTS (SELECT 1 FROM npc_transfer_attempts a WHERE a.event_id=e.event_id) "
                +(propertyId==null?"":"AND r.property_id=? ")
                +"ORDER BY e.event_id LIMIT "+limit)) {
            ps.setString(1,source.key());ps.setString(2,source.direction().moneyFlowKey());ps.setString(3,lastLive.toString());
            if(propertyId!=null)id(ps,4,propertyId);
            try(var rs=ps.executeQuery()) {
                while(rs.next())into.add(new RetireCandidate(rs.getLong(1),source,table));
            }
        }
    }

    /** Same lock order as TransferJournal.lockEvent: property, then event, then the attempt proof. Null means left alone. */
    private static Retirement retireOne(Connection c,RetireCandidate candidate,LocalDate today,LocalDate closed)throws SQLException {
        UUID propertyId;LocalDate day;
        try(var ps=sql(c,"SELECT property_id,economy_day FROM "+candidate.table()+" WHERE event_id=?")) {
            ps.setLong(1,candidate.eventId());
            try(var rs=ps.executeQuery()) {
                if(!rs.next())return null;
                propertyId=uuid(rs,"property_id");day=LocalDate.parse(rs.getString("economy_day"));
            }
        }
        UUID currentOwner;int currentEpoch;
        try(var ps=sql(c,"SELECT owner_uuid,ownership_epoch FROM npc_properties WHERE property_id=? FOR UPDATE")) {
            id(ps,1,propertyId);
            try(var rs=ps.executeQuery()) {
                if(!rs.next())return null;
                byte[] owner=rs.getBytes("owner_uuid");
                currentOwner=owner==null?null:Uuids.fromBytes(owner);
                currentEpoch=rs.getInt("ownership_epoch");
                if(rs.wasNull())return null;
            }
        }
        UUID boundOwner;int boundEpoch;
        try(var ps=sql(c,"SELECT * FROM npc_economy_events WHERE event_id=? FOR UPDATE")) {
            ps.setLong(1,candidate.eventId());
            try(var rs=ps.executeQuery()) {
                if(!rs.next())return null;
                if(!"PENDING".equals(rs.getString("state")) || rs.getObject("paid_amount_cents")!=null || rs.getObject("claimed_at")!=null
                        || !candidate.source().key().equals(rs.getString("source"))
                        || !candidate.source().direction().moneyFlowKey().equals(rs.getString("direction"))
                        || !day.toString().equals(rs.getString("economy_day"))
                        || !propertyId.equals(uuid(rs,"property_id")) || rs.getObject("ownership_epoch")==null)return null;
                boundOwner=uuid(rs,"counterparty_uuid");boundEpoch=rs.getInt("ownership_epoch");
            }
        }
        // Locking read: sees any committed beginDebit, which needs the event lock this transaction now holds.
        try(var ps=sql(c,"SELECT attempt_id FROM npc_transfer_attempts WHERE event_id=? FOR UPDATE")) {
            ps.setLong(1,candidate.eventId());
            try(var rs=ps.executeQuery()){if(rs.next())return null;}
        }
        LocalDate lastLive=candidate.source()==EventSource.RENT?today:closed;
        boolean ownerChanged=!boundOwner.equals(currentOwner) || boundEpoch!=currentEpoch;
        RetirementReason reason;
        if(ownerChanged)reason=RetirementReason.OWNER_CHANGED;
        else if(day.isBefore(lastLive))reason=RetirementReason.EXPIRED;
        else return null;
        try(var ps=sql(c,"INSERT INTO npc_payout_retirements(event_id,kind,property_id,economy_day,reason,retired_owner,retired_epoch,"
                +"current_owner,current_epoch,retired_on) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            ps.setLong(1,candidate.eventId());ps.setString(2,candidate.source()==EventSource.RENT?"RENT":"APPRECIATION");
            id(ps,3,propertyId);ps.setString(4,day.toString());ps.setString(5,reason.name());
            id(ps,6,boundOwner);ps.setInt(7,boundEpoch);
            if(currentOwner==null)ps.setNull(8,Types.BINARY);else id(ps,8,currentOwner);
            ps.setInt(9,currentEpoch);ps.setString(10,today.toString());
            if(ps.executeUpdate()!=1)throw new SQLException("Payout retirement not recorded");
        }
        try(var ps=sql(c,"UPDATE npc_economy_events SET state='FAILED',paid_amount_cents=0,claimed_at=CURRENT_TIMESTAMP(3),"
                +"finalized_at=CURRENT_TIMESTAMP(3) WHERE event_id=? AND state='PENDING' AND claimed_at IS NULL AND paid_amount_cents IS NULL")) {
            ps.setLong(1,candidate.eventId());
            if(ps.executeUpdate()!=1)throw new SQLException("Payout retirement ledger update lost");
        }
        return new Retirement(candidate.eventId(),candidate.source(),propertyId,day,reason);
    }

    /** Read inside the reservation transaction. Empty means no recorded rating, so no appreciation. */
    @FunctionalInterface public interface AppreciationScore {
        OptionalDouble score(Connection c,Property property,Instant at)throws SQLException;
    }

    /**
     * Cash appreciation for the economy day that just closed, never the open one. No rent can be
     * reserved for a closed day, so all of that day's rent has already taken its cap share and
     * appreciation only gets what is left; a later rent reservation is never reduced by it. A
     * candidate's own rent for that day must be journal-proved PAID to the current owner and epoch;
     * held, deferred, declined or ambiguous rent gives nothing. Same mutex, ledger, lifetime funding
     * rule and owner/server pro-rata as rent. One row per property and day: a replay reserves
     * nothing. The caller must still check the reserve floor before dispatch.
     */
    public List<Reserved> reserveAppreciation(LocalDate day,List<Property> owned,EconomyLimits limits,
                                              AppreciationScore scorer)throws SQLException {
        return reserveAppreciation(null,day,owned,limits,scorer);
    }

    /**
     * Rent first across the whole ledger (economy.appreciation-ledger-rent-hold-enabled). Under the rent mutex,
     * before anything is reserved: if ANY property still has rent for rentDay (the open economy day) that is
     * not final (PENDING, deferred or never attempted, CLAIMED in flight, or AMBIGUOUS), nothing is reserved and
     * the result is empty, so no appreciation (new or queued by an earlier batch) is returned for dispatch.
     * This sees rent the current batch never listed (isolation-held, paused or owner-away after reserving).
     * It also holds while any home's rent for rentDay is held for a due rent change that is not applied yet
     * (heldForDueRentChange): that home has no ledger event, but its rent is reserved later the same day.
     * Otherwise it reserves exactly as reserveAppreciation and returns the queued appreciation for owned, read
     * in the same transaction, so no rent reservation can commit between the check and the returned rows.
     */
    public Optional<List<Reserved>> reserveAppreciationUnlessRentOutstanding(LocalDate rentDay,LocalDate day,List<Property> owned,
                                                                         EconomyLimits limits,AppreciationScore scorer)throws SQLException {
        Objects.requireNonNull(rentDay);
        return Optional.ofNullable(reserveAppreciation(rentDay,day,owned,limits,scorer));
    }

    /**
     * True if any rent reservation for the economy day, on any property, has a ledger event that is not final.
     * Read from the event ledger by day and source only (no direction or reservation join), so a narrower
     * filter can never hide a row: a rent event exists only with its reservation.
     */
    static boolean outstandingRent(Connection c,LocalDate day)throws SQLException {
        try(var ps=sql(c,"SELECT 1 FROM npc_economy_events WHERE economy_day=? AND source=? AND state NOT IN ('PAID','FAILED') LIMIT 1")) {
            ps.setString(1,day.toString());ps.setString(2,EventSource.RENT.key());
            try(var rs=ps.executeQuery()) { return rs.next(); }
        }
    }

    /** Read-only probe of outstandingRent for staff tooling and tests. Payment decisions use the mutex path. */
    public boolean outstandingRent(LocalDate day)throws SQLException {
        Objects.requireNonNull(day);
        try(Connection c=database.getConnection()) { return outstandingRent(c,day); }
    }

    /**
     * True if any enabled, owned home has an asking-rent change due on or before day that is not applied yet and
     * has no rent reservation for day. reserve(..., true) skips such a home before writing any event (the
     * dueRentChange rule, the same one applyDueRentChanges uses), and the next batch after the rent-change step
     * reserves its rent later the same day, so for the ledger rent hold that rent is outstanding even though the
     * event ledger has no row for it. It reads only the pending columns, so with no pending change anywhere
     * (rent-change delay unused) it finds nothing. A home whose lease stays busy keeps appreciation held until
     * its change applies: rent first, fail closed.
     */
    static boolean heldForDueRentChange(Connection c,LocalDate day)throws SQLException {
        try(var ps=sql(c,"SELECT 1 FROM npc_properties p WHERE p.enabled=TRUE AND p.owner_uuid IS NOT NULL "
                +"AND p.pending_rent_change_id IS NOT NULL AND p.pending_rent_effective_day<=? "
                +"AND NOT EXISTS (SELECT 1 FROM npc_rent_reservations r WHERE r.property_id=p.property_id AND r.economy_day=?) LIMIT 1")) {
            ps.setString(1,day.toString());ps.setString(2,day.toString());
            try(var rs=ps.executeQuery()) { return rs.next(); }
        }
    }

    /** Read-only probe of heldForDueRentChange for staff tooling and tests. Payment decisions use the mutex path. */
    public boolean heldForDueRentChange(LocalDate day)throws SQLException {
        Objects.requireNonNull(day);
        try(Connection c=database.getConnection()) { return heldForDueRentChange(c,day); }
    }

    /** rentDay null: reserveAppreciation exactly as before. Non-null: the ledger rent hold; a null result means held. */
    private List<Reserved> reserveAppreciation(LocalDate rentDay,LocalDate day,List<Property> owned,EconomyLimits limits,
                                               AppreciationScore scorer)throws SQLException {
        Objects.requireNonNull(day);Objects.requireNonNull(limits);Objects.requireNonNull(scorer);
        if(owned.size()>512 || day.getYear()<1 || day.getYear()>9999)throw new IllegalArgumentException("Appreciation scope");
        var sorted=owned.stream().sorted(Comparator.comparing(p->p.propertyId().toString())).toList();
        if(sorted.stream().map(Property::propertyId).distinct().count()!=sorted.size())throw new IllegalArgumentException("Duplicate property");
        Instant at=clock.instant().truncatedTo(ChronoUnit.MILLIS);
        LocalDate open=new EconomyClock(Clock.fixed(at,zone),zone,resetHour).today();
        if(!day.equals(open.minusDays(1)))
            throw new IllegalArgumentException("Appreciation day is not the closed economy day");
        if(rentDay!=null && !rentDay.equals(open))
            throw new IllegalArgumentException("Rent hold day is not the open economy day");
        try(Connection c=database.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setAutoCommit(false);
            try {
                try(var ps=sql(c,"SELECT singleton FROM npc_rent_mutex WHERE singleton=1 FOR UPDATE");var rs=ps.executeQuery()) {
                    if(!rs.next())throw new SQLException("Rent mutex unavailable");
                }
                // Rent reservations take the same mutex, so none can commit between this check and our commit.
                // A home skipped for a due, unapplied rent change has no event yet but is still owed rent today.
                if(rentDay!=null && (outstandingRent(c,rentDay) || heldForDueRentChange(c,rentDay))) { c.rollback(); return null; }
                // The allocated (closed) day, never the open one: its pin and its active links decide the groups.
                var units=units(c,day);
                var spent=spent(c,day,units);
                var candidates=new ArrayList<Appreciation>();
                for(var property:sorted) {
                    if(!property.enabled() || property.ownerUuid()==null || reserve.equals(property.ownerUuid()))continue;
                    verifyProperty(c,property);
                    if(exists(c,"SELECT 1 FROM npc_appreciation_reservations WHERE property_id=? AND economy_day=?",property.propertyId(),day))continue;
                    long rent=paidRent(c,property,day);
                    if(rent==0)continue;
                    OptionalDouble score=scorer.score(c,property,at);
                    if(score==null || score.isEmpty())continue;
                    double value=score.getAsDouble();
                    if(!Double.isFinite(value) || value<0 || value>100)throw new SQLException("Invalid appreciation score");
                    long entitlement=PropertyFormulas.appreciationCents(value,limits.appreciationDailyCapCents());
                    if(entitlement<=0)continue;
                    long amount=Math.min(entitlement,Math.min(funding(c,property,limits,spent.pendingLifetime()),
                            remaining(limits.propertyDailyCapCents(),spent.property().getOrDefault(property.propertyId(),0L))));
                    if(amount>0)candidates.add(new Appreciation(property,rent,(int)Math.floor(value*10.0),entitlement,amount));
                }
                long[] amounts=allocate(candidates.stream().mapToLong(Appreciation::requested).toArray(),
                        candidates.stream().map(x->x.property().ownerUuid()).toList(),spent,limits,units);
                var result=new ArrayList<Reserved>();
                for(int i=0;i<candidates.size();i++) {
                    if(amounts[i]==0)continue;
                    var candidate=candidates.get(i); var p=candidate.property();
                    long event=EconomyEventRepository.insertPending(c,EconomyEvent.pending("appreciation-v1|"+day+"|"+p.propertyId(),amounts[i],
                            EventSource.APPRECIATION,p.propertyId(),p.ownerUuid(),p.ownershipEpoch(),p.ownerUuid(),day.toString()));
                    if(event<=0)throw new SQLException("Appreciation event exists without its reservation");
                    try(var ps=sql(c,"INSERT INTO npc_appreciation_reservations(property_id,economy_day,event_id,rent_event_id,score_tenths,entitlement_cents) VALUES (?,?,?,?,?,?)")) {
                        id(ps,1,p.propertyId());ps.setString(2,day.toString());ps.setLong(3,event);ps.setLong(4,candidate.rentEvent());
                        ps.setInt(5,candidate.scoreTenths());ps.setLong(6,candidate.entitlement());ps.executeUpdate();
                    }
                    result.add(new Reserved(p,event,amounts[i]));
                }
                List<Reserved> out=rentDay==null?List.copyOf(result)
                        :pending(c,day,owned,"npc_appreciation_reservations",EventSource.APPRECIATION,"appreciation");
                c.commit();return out;
            } catch(SQLException|RuntimeException failure) {
                try{c.rollback();}catch(SQLException rollback){failure.addSuppressed(rollback);}throw failure;
            }
        }
    }

    /**
     * The public rating's own rule: only a fully RATED landlord has a score (same 29-day window and
     * 7-day retention horizon as the owner desk). Unrated, held or under-review evidence pays nothing.
     */
    public AppreciationScore ratedScore() {
        var services=new ServiceAssessmentRepository(database,clock,zone,resetHour);
        return (c,p,at)->{
            var assessment=services.readInTransaction(c,at,p.ownerUuid(),at.minus(Duration.ofDays(29)),7);
            Property current; long revision;
            try(var ps=sql(c,"SELECT row_version,condition_score FROM npc_properties WHERE property_id=?")) {
                id(ps,1,p.propertyId());
                try(var rs=ps.executeQuery()) {
                    if(!rs.next())throw new SQLException("Appreciation property missing");
                    revision=rs.getLong(1);
                    current=new Property(p.propertyId(),p.worldUuid(),p.regionId(),p.marketId(),p.classKey(),p.capacity(),
                            p.referenceRentCents(),p.askingRentCents(),p.ownerUuid(),p.ownershipEpoch(),rs.getInt(2),p.enabled());
                }
            }
            var upgrades=new ArrayList<OwnerDeskData.Upgrade>();
            try(var ps=sql(c,"SELECT upgrade_type,level,disabled_incident_id FROM npc_upgrades WHERE property_id=? ORDER BY upgrade_type LIMIT "
                    +(UpgradeType.values().length+1))) {
                id(ps,1,p.propertyId());
                try(var rs=ps.executeQuery()) {
                    while(rs.next()) {
                        if(upgrades.size()>=UpgradeType.values().length)throw new SQLException("Invalid appreciation upgrades");
                        upgrades.add(new OwnerDeskData.Upgrade(rs.getString(1),rs.getInt(2),rs.getObject(3)!=null));
                    }
                }
            }
            try {
                var rank=OwnerHousingView.from(current,revision,upgrades,assessment,zone,resetHour).rating();
                return rank.status()==HousingDecisionPlan.RankStatus.RATED ? rank.rankingScore() : OptionalDouble.empty();
            } catch(RuntimeException invalid) { throw new SQLException("Invalid appreciation rating evidence",invalid); }
        };
    }

    /**
     * Read-only: payout cents counted against the server cap for the day (paid and unresolved).
     * spent() proves FAILED rows with locking journal reads, so this is a rollback-only REPEATABLE READ
     * transaction (the AccountingProjection pattern), not SQL READ ONLY. It issues no writes and does
     * not take the rent mutex: the alert is advisory and must not serialize against reserve().
     */
    public long serverSpentCents(LocalDate day)throws SQLException {
        Objects.requireNonNull(day);
        try(Connection c=database.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setAutoCommit(false);
            // Only the server total is read, so no owner groups are needed (and no day is pinned).
            try { return spent(c,day,OwnerUnits.identity()).server(); }
            finally { c.rollback(); }
        }
    }

    private record Appreciation(Property property,long rentEvent,int scoreTenths,long entitlement,long requested) { }
    private record Spent(Map<UUID,Long> owner,Map<UUID,Long> property,Map<UUID,Long> pendingLifetime,long server) { }

    /**
     * Owner groups for the benefit-unit cap of day, read in the caller's transaction right after the rent mutex.
     * No link repository: identity, nothing read. Otherwise AccountLinkRepository.unitsFor decides (day pin first).
     */
    private OwnerUnits units(Connection c,LocalDate day)throws SQLException {
        if(links==null)return OwnerUnits.identity();
        return links.unitsFor(c,day,linkedEnabled.getAsBoolean());
    }

    /**
     * FAILED releases capacity only with proved no movement. Ambiguity retains it. Every payout source counts.
     * A retired payout (npc_payout_retirements row, FAILED with paid 0 and no transfer attempt at all) is
     * proved no-movement: it releases lifetime funding and, from an earlier day, leaves the scan entirely. On
     * its own day it still counts against that day's caps. Keyed on the retirement row, never on the flag,
     * so turning the flag off cannot bring retired funding back. With no retirement rows this is unchanged.
     * ownerSpent is keyed by the owner's group (units.unitOf); with identity units that is the owner itself.
     */
    private Spent spent(Connection c,LocalDate day)throws SQLException {
        // Every owner its own group: the pre-linked-accounts read, kept for tools and tests that probe the ledger.
        return spent(c,day,OwnerUnits.identity());
    }

    private Spent spent(Connection c,LocalDate day,OwnerUnits units)throws SQLException {
        // Fail fast: the FAILED-row proof reader needs a caller-owned transaction, so an autocommit
        // reader would otherwise work until the first FAILED payout and then throw forever.
        if(c.getAutoCommit())throw new SQLException("Rent ledger read requires caller-owned transaction");
        var ownerSpent=new HashMap<UUID,Long>(); var propertySpent=new HashMap<UUID,Long>();
        var pendingLifetime=new HashMap<UUID,Long>(); long serverSpent=0;
        try(var ps=sql(c,"SELECT e.event_id,e.property_id,e.benefit_unit_id,e.economy_day,e.state,e.requested_amount_cents,"
                +"x.event_id AS retired_event FROM npc_economy_events e LEFT JOIN npc_payout_retirements x ON x.event_id=e.event_id "
                +"AND e.state='FAILED' AND e.paid_amount_cents=0 "
                +"AND NOT EXISTS (SELECT 1 FROM npc_transfer_attempts a WHERE a.event_id=e.event_id) "
                +"WHERE e.direction='TRANSFER_TO_PUBLIC' AND (e.economy_day=? OR (e.state<>'PAID' AND x.event_id IS NULL)) "
                +"ORDER BY e.event_id LIMIT 10001")) {
            ps.setString(1,day.toString());
            try(var rs=ps.executeQuery()) {
                int seen=0;
                while(rs.next()) {
                    if(++seen>10000)throw new SQLException("Rent outstanding ledger requires bounded maintenance");
                    long amount=rs.getLong("requested_amount_cents");
                    if(amount<=0)throw new SQLException("Invalid prior payout amount");
                    String state=rs.getString("state");
                    rs.getLong("retired_event");
                    boolean retired=!rs.wasNull(); // The join itself proved FAILED, paid 0 and no attempt.
                    if("FAILED".equals(state) && !retired && transfers.confirmedDeclined(c,rs.getLong("event_id")))continue;
                    UUID property=uuid(rs,"property_id"),owner=uuid(rs,"benefit_unit_id");
                    if(!"PAID".equals(state) && !retired)pendingLifetime.merge(property,amount,Math::addExact);
                    if(day.toString().equals(rs.getString("economy_day"))) {
                        serverSpent=Math.addExact(serverSpent,amount);
                        ownerSpent.merge(units.unitOf(owner),amount,Math::addExact);
                        propertySpent.merge(property,amount,Math::addExact);
                    }
                }
            }
        }
        return new Spent(ownerSpent,propertySpent,pendingLifetime,serverSpent);
    }

    /** $50 seed + floor(ratio x successful sinks), less paid and every unresolved payout. Locks the funding row. */
    private long funding(Connection c,Property property,EconomyLimits limits,Map<UUID,Long> pendingLifetime)throws SQLException {
        try(var ps=sql(c,"SELECT one_time_seed_cents,successful_cash_sinks_cents,successful_cash_payouts_cents FROM npc_property_funding WHERE property_id=? FOR UPDATE")) {
            id(ps,1,property.propertyId());
            try(var rs=ps.executeQuery()) {
                if(!rs.next())throw new SQLException("Missing property funding");
                long seed=rs.getLong(1),sinks=rs.getLong(2),paid=rs.getLong(3);
                if(seed<0 || sinks<0 || paid<0)throw new SQLException("Negative funding state");
                long funded=Math.addExact(seed,BigDecimal.valueOf(sinks).multiply(BigDecimal.valueOf(limits.lifetimeSinkFundingRatio()))
                        .setScale(0,RoundingMode.FLOOR).longValueExact());
                return remaining(funded,Math.addExact(paid,pendingLifetime.getOrDefault(property.propertyId(),0L)));
            }
        }
    }

    /**
     * Benefit-unit pro-rata per owner group (units.unitOf; one owner per group without linked accounts), then
     * server pro-rata, against what today's ledger already holds for that group.
     */
    private static long[] allocate(long[] requested,List<UUID> owners,Spent spent,EconomyLimits limits,OwnerUnits units) {
        long[] amounts=requested.clone();
        var grouped=new LinkedHashMap<UUID,List<Integer>>();
        for(int i=0;i<owners.size();i++)grouped.computeIfAbsent(units.unitOf(owners.get(i)),ignored->new ArrayList<>()).add(i);
        for(var entry:grouped.entrySet()) {
            long[] requests=new long[entry.getValue().size()];
            for(int j=0;j<requests.length;j++)requests[j]=amounts[entry.getValue().get(j)];
            long[] allocated=CapAllocator.allocate(requests,remaining(limits.benefitUnitDailyCapCents(),spent.owner().getOrDefault(entry.getKey(),0L)));
            for(int j=0;j<allocated.length;j++)amounts[entry.getValue().get(j)]=allocated[j];
        }
        return CapAllocator.allocate(amounts,remaining(limits.serverDailyCapCents(),spent.server()));
    }

    /** That day's rent event id when it is journal-proved PAID to this owner and epoch; otherwise 0. */
    private long paidRent(Connection c,Property p,LocalDate day)throws SQLException {
        try(var ps=sql(c,"SELECT e.event_id,e.state,e.source,e.counterparty_uuid,e.ownership_epoch FROM npc_rent_reservations r "
                +"JOIN npc_economy_events e ON e.event_id=r.event_id WHERE r.property_id=? AND r.economy_day=?")) {
            id(ps,1,p.propertyId());ps.setString(2,day.toString());
            try(var rs=ps.executeQuery()) {
                if(!rs.next())return 0;
                long event=rs.getLong(1);
                if(!"PAID".equals(rs.getString("state")) || !EventSource.RENT.key().equals(rs.getString("source"))
                        || !p.ownerUuid().equals(uuid(rs,"counterparty_uuid")) || p.ownershipEpoch()!=rs.getInt("ownership_epoch"))return 0;
                if(!transfers.confirmedPaid(c,event) || rs.next())throw new SQLException("Rent proof conflicts with appreciation authority");
                return event;
            }
        }
    }

    /** Admission proof, not a fresh service rating. The caller supplies the batch's frozen actual clock. */
    int tenantTierPercent(Connection c,Property property,UUID resident,String tier,int slot,long revision,
                        HousingMoveRepository receipts)throws SQLException {
        if("STANDARD".equals(tier))return PropertyFormulas.TIER_STANDARD_PCT;
        if(!"PREMIUM".equals(tier))throw new SQLException("Unsupported rent tenant tier");
        if(!premiumEnabled)throw new SQLException("Premium rent policy is not enabled");
        if(slot<0 || slot>=property.capacity() || revision<0)throw new SQLException("Invalid premium rent tenancy");
        // LEFT JOIN prevents a missing latest day policy from falling back to an older admission.
        try(var ps=sql(c,"SELECT m.*,d.server_limit,d.market_limit,d.property_limit,d.cooldown_days "
                +"FROM npc_housing_moves m LEFT JOIN npc_housing_days d ON d.economy_day=m.economy_day "
                +"WHERE m.resident_id=? ORDER BY m.tenancy_revision_after DESC,m.request_id LIMIT 2")) {
            id(ps,1,resident);
            try(var rs=ps.executeQuery()) {
                if(!proofNext(c,ps,rs))throw new SQLException("Premium rent admission proof missing");
                var policy=new HousingMoveRequest.Policy(Math.toIntExact(proofNumber(rs,"server_limit")),
                        Math.toIntExact(proofNumber(rs,"market_limit")),Math.toIntExact(proofNumber(rs,"property_limit")),
                        Math.toIntExact(proofNumber(rs,"cooldown_days")));
                var admission=receipts.readReceipt(rs,policy);
                var request=admission.request();
                if(request.tier()!=HousingPolicy.Tier.PREMIUM || !resident.equals(request.residentId())
                        || !property.propertyId().equals(request.propertyId()) || !property.ownerUuid().equals(request.ownerId())
                        || property.ownershipEpoch()!=request.ownershipEpoch() || slot!=request.slot()
                        || revision!=admission.tenancyRevisionAfter())throw new SQLException("Premium rent admission proof does not match tenancy");
                if(proofNext(c,ps,rs) && proofNumber(rs,"tenancy_revision_after")>=admission.tenancyRevisionAfter())
                    throw new SQLException("Latest premium rent admission proof is ambiguous");
                proofWarnings(c,ps,rs);
                return PropertyFormulas.TIER_PREMIUM_PCT;
            }
        } catch(RuntimeException invalid) {
            throw new SQLException("Invalid premium rent admission proof",invalid);
        }
    }

    private static long proofNumber(ResultSet rs,String column)throws SQLException {
        long value=rs.getLong(column);
        if(rs.wasNull())throw new SQLException("Missing premium rent proof number");
        return value;
    }
    private static boolean proofNext(Connection c,PreparedStatement ps,ResultSet rs)throws SQLException {
        proofWarnings(c,ps,rs);boolean next=rs.next();proofWarnings(c,ps,rs);return next;
    }
    private static void proofWarnings(Connection c,PreparedStatement ps,ResultSet rs)throws SQLException {
        if(c.getWarnings()!=null || ps.getWarnings()!=null || rs.getWarnings()!=null)
            throw new SQLException("Incomplete premium rent admission proof read");
    }

    private long paidUtility(Connection c,Property p,LocalDate day)throws SQLException {
        try(var ps=sql(c,"SELECT u.event_id,b.owner_uuid,b.ownership_epoch FROM npc_utility_payments u "
                +"JOIN npc_utility_bills b ON b.property_id=u.property_id AND b.economy_day=u.economy_day "
                +"WHERE u.property_id=? AND u.economy_day=? AND u.state='PAID'")) {
            id(ps,1,p.propertyId());ps.setString(2,day.toString());
            try(var rs=ps.executeQuery()) {
                if(!rs.next())return 0;
                long event=rs.getLong(1);
                if(!p.ownerUuid().equals(uuid(rs,"owner_uuid")) || p.ownershipEpoch()!=rs.getInt("ownership_epoch")
                        || !transfers.confirmedPaid(c,event) || rs.next())throw new SQLException("Utility proof conflicts with rent authority");
                return event;
            }
        }
    }
    private static void verifyProperty(Connection c,Property p)throws SQLException {
        try(var ps=sql(c,"SELECT owner_uuid,ownership_epoch,enabled,capacity,asking_rent_cents,reference_rent_cents FROM npc_properties WHERE property_id=? FOR UPDATE")) {
            id(ps,1,p.propertyId());
            try(var rs=ps.executeQuery()) {
                if(!rs.next() || !p.ownerUuid().equals(uuid(rs,"owner_uuid")) || p.ownershipEpoch()!=rs.getInt("ownership_epoch")
                        || !rs.getBoolean("enabled") || p.capacity()!=rs.getInt("capacity") || p.capacity()<1 || p.capacity()>4
                        || p.askingRentCents()!=rs.getInt("asking_rent_cents") || p.referenceRentCents()!=rs.getInt("reference_rent_cents")
                        || p.askingRentCents()<=0 || p.referenceRentCents()<=0)throw new SQLException("Rent property snapshot stale");
            }
        }
    }
    /** The row is already locked by verifyProperty in this transaction. Same due rule as applyDueRentChanges. */
    private static boolean dueRentChange(Connection c,Property p,LocalDate day)throws SQLException {
        return exists(c,"SELECT 1 FROM npc_properties WHERE property_id=? AND pending_rent_change_id IS NOT NULL "
                +"AND pending_rent_effective_day<=?",p.propertyId(),day);
    }
    private static boolean exists(Connection c,String query,UUID id,LocalDate day)throws SQLException {
        try(var ps=sql(c,query)){id(ps,1,id);ps.setString(2,day.toString());try(var rs=ps.executeQuery()){return rs.next();}}
    }
    private static long remaining(long cap,long used){return used>=cap?0:Math.subtractExact(cap,used);}
    private static UUID uuid(ResultSet rs,String column)throws SQLException {
        byte[] value=rs.getBytes(column);if(value==null || value.length!=16)throw new SQLException("Missing rent identity");
        return Uuids.fromBytes(value);
    }
    private static void id(PreparedStatement ps,int index,UUID id)throws SQLException {ps.setBytes(index,Uuids.toBytes(id));}
    private static PreparedStatement sql(Connection c,String query)throws SQLException {
        var ps=c.prepareStatement(query);ps.setQueryTimeout(15);return ps;
    }
}

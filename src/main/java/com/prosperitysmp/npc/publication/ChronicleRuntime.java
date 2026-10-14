package com.prosperitysmp.npc.publication;

import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.ReadSnapshot;
import com.prosperitysmp.npc.db.Uuids;
import com.prosperitysmp.npc.publication.ChronicleOutboxRepository.Held;
import com.prosperitysmp.npc.publication.ChronicleWebhookProtocol.Destination;
import com.prosperitysmp.npc.publication.PublicOccupancySnapshot.Approval;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Owner-ticked, optional Chronicle sidecar. SQL is off-owner; default construction never reads credentials
 * or constructs HTTP. Parent owns main-thread ticks, approval provisioning, and awaiting shutdown before DB close.
 * A verified deployment bootstrap must supply clean-start diagnostics; this plugin cannot certify cached JDK state.
 */
public final class ChronicleRuntime {
    public static final int PAGE_SIZE=16;
    public static final int MAX_STORY_APPROVALS=128;
    public enum Status { DISABLED, STARTING, HELD_ONLY, READY, HELD_READ, HELD_RECOVERY,
        HELD_AUTHORIZATION, HELD_CREDENTIAL, HELD_DISPATCH, STOPPING, STOPPED, STOP_FAILED }
    public record Settings(boolean enabled,UUID generation,List<Approval> scopes,Duration interval,boolean receiptRevisionEvidence) {
        public Settings(boolean enabled,UUID generation,List<Approval> scopes,Duration interval) { this(enabled,generation,scopes,interval,false); }
        public Settings {
            scopes=List.copyOf(scopes); Objects.requireNonNull(interval);
            if (scopes.size()>512 || interval.compareTo(Duration.ofSeconds(1))<0 || interval.compareTo(Duration.ofHours(1))>0
                    || (enabled && (generation==null || scopes.isEmpty())) || (!enabled && !scopes.isEmpty()))
                throw new IllegalArgumentException("Invalid Chronicle runtime settings");
            Set<UUID> properties=new HashSet<>(); Set<List<String>> sources=new HashSet<>(), publicKeys=new HashSet<>();
            Map<UUID,String> publicWorlds=new HashMap<>(); Map<String,UUID> worlds=new HashMap<>();
            for (Approval scope:scopes) {
                String oldPublic=publicWorlds.putIfAbsent(scope.worldId(),scope.publicWorldKey());
                UUID oldWorld=worlds.putIfAbsent(scope.publicWorldKey(),scope.worldId());
                if (!properties.add(scope.propertyId()) || !sources.add(List.of(scope.worldId().toString(),scope.regionId()))
                        || !publicKeys.add(List.of(scope.publicWorldKey(),scope.regionId()))
                        || (oldPublic!=null && !oldPublic.equals(scope.publicWorldKey())) || (oldWorld!=null && !oldWorld.equals(scope.worldId())))
                    throw new IllegalArgumentException("Conflicting Chronicle scope");
            }
        }
        public static Settings disabled() { return new Settings(false,null,List.of(),Duration.ofSeconds(5)); }
    }
    /** Private operator preview: source identity plus the immutable public-only authored preview. */
    public record HeldAdmission(UUID requestId,Approval scope,Held held,ChronicleAdmissionTemplate.Preview preview) { }
    /** Explicit owner approval of exact immutable admissions, never inferred from discovery or property tier. */
    public record StoryApproval(UUID requestId,Approval scope,Held held) {
        public StoryApproval { Objects.requireNonNull(requestId); Objects.requireNonNull(scope); validHeld(held); }
    }
    public record DeliveryApproval(String approvalRef,UUID generation,Destination destination,List<StoryApproval> stories) {
        public DeliveryApproval {
            if (approvalRef==null || !approvalRef.matches("[A-Za-z0-9_.:@-]{1,96}")) throw new IllegalArgumentException("Invalid story approval reference");
            Objects.requireNonNull(generation); Objects.requireNonNull(destination); stories=List.copyOf(stories);
            if (stories.isEmpty() || stories.size()>MAX_STORY_APPROVALS) throw new IllegalArgumentException("Story approval exceeds bounds");
            Set<UUID> requests=new HashSet<>(); Set<String> events=new HashSet<>();
            for (StoryApproval story:stories) if (!requests.add(story.requestId()) || !events.add(story.held().eventKey()))
                throw new IllegalArgumentException("Duplicate story approval");
        }
    }
    public record ProcessIdentity(long pid,Instant startedAt) {
        public ProcessIdentity { if(pid<1) throw new IllegalArgumentException("Invalid process identity"); Objects.requireNonNull(startedAt); }
    }
    public record CleanStartEvidence(ProcessIdentity process,String evidenceSha256) {
        public CleanStartEvidence { Objects.requireNonNull(process); hashValue(evidenceSha256); }
    }
    @FunctionalInterface public interface CleanStartDiagnostics {
        /**
         * Trusted, thread-safe bootstrap adapter only. Verify JVM options/environment/net.properties and
         * HTTP diagnostic initialization order for this exact process. A current property/config boolean is NOT proof.
         * Empty, failure, changed evidence, or another process revokes permission before credential read/each send.
         */
        Optional<CleanStartEvidence> verify(ProcessIdentity process);
    }
    @FunctionalInterface interface CredentialReader { String read(Path dataDirectory) throws IOException; }
    @FunctionalInterface interface TransportFactory { ChronicleDispatcher.Transport create(Destination destination,String token); }
    private record Raw(UUID request,String event,String payloadHash,String sourceHash,String payload,Instant capturedAt) { }
    private record ReadRequest(Approval scope,UUID after) { }
    private record ReadResult(ReadRequest request,List<HeldAdmission> admissions,UUID next,boolean failed) { }
    private record Authority(DeliveryApproval approval,Map<String,StoryApproval> stories,CleanStartDiagnostics diagnostics,CleanStartEvidence evidence) { }

    private final Thread owner=Thread.currentThread();
    private final Database database;
    private final Clock clock;
    private final ZoneId zone;
    private final Settings settings;
    private final ChronicleAdmissionRepository admissions;
    private final ChronicleRecoveryHost recovery;
    private final LongSupplier monotonic;
    private final CredentialReader credentials;
    private final TransportFactory transports;
    private final ProcessIdentity process;
    private final ThreadPoolExecutor reader;
    private final CompletableFuture<Void> readerDrained=new CompletableFuture<>();
    private final AtomicBoolean reading=new AtomicBoolean(),closed=new AtomicBoolean();
    private final AtomicReference<ReadResult> readResult=new AtomicReference<>();
    private final Map<UUID,UUID> cursors=new HashMap<>();
    private volatile Authority authority;
    private ChronicleDispatchHost dispatch;
    private List<HeldAdmission> held=List.of();
    private ChronicleDispatchHost.Outcome lastOutcome;
    private CompletableFuture<Void> cleanup=CompletableFuture.completedFuture(null),shutdown;
    private Status dispatchStatus=Status.HELD_ONLY;
    private boolean firstRead,readFailed,readDispatched,dispatchAttempted;
    private volatile boolean readerFatal;
    private int nextScope;
    private long lastRead;

    public ChronicleRuntime(Database database,Clock clock,ZoneId zone,int resetHour,Settings settings) {
        this(database,clock,zone,resetHour,settings,System::nanoTime,ChronicleRuntime::readWebhookToken,ChronicleHttpTransport::new);
    }
    // Package-only seams use disposable databases and synthetic transport/credentials, never production bypass flags.
    ChronicleRuntime(Database database,Clock clock,ZoneId zone,int resetHour,Settings settings,LongSupplier monotonic,
                     CredentialReader credentials,TransportFactory transports) {
        this.database=Objects.requireNonNull(database); this.clock=Objects.requireNonNull(clock); this.zone=Objects.requireNonNull(zone);
        this.settings=Objects.requireNonNull(settings); this.monotonic=Objects.requireNonNull(monotonic);
        this.credentials=Objects.requireNonNull(credentials); this.transports=Objects.requireNonNull(transports);
        admissions=new ChronicleAdmissionRepository(database,clock,zone,resetHour);
        process=new ProcessIdentity(ProcessHandle.current().pid(),ProcessHandle.current().info().startInstant().orElseThrow());
        if (!settings.enabled()) { recovery=null; reader=null; readerDrained.complete(null); return; }
        var discovery=new ChronicleDiscoveryRepository(database,clock);
        var outbox=new ChronicleOutboxRepository(database,clock,zone,resetHour,settings.receiptRevisionEvidence());
        recovery=new ChronicleRecoveryHost(new ChronicleRecoveryWorker(discovery::page,outbox::stage,new ChronicleRecoveryJournal(database)),
                settings.scopes(),settings.generation(),settings.interval(),PAGE_SIZE,monotonic);
        reader=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(2),task -> {
            Thread thread=new Thread(task,"prosperity-npc-chronicle-reader"); thread.setDaemon(true); return thread;
        },new ThreadPoolExecutor.AbortPolicy()) {
            @Override protected void terminated() { readerDrained.complete(null); }
        };
    }

    /** Main/owner only. Already-HELD rows are read before starting admission recovery; no busy tick backlog. */
    public void tick() {
        main(); if (!settings.enabled() || closed.get()) return;
        ReadResult result=readResult.getAndSet(null);
        if (result!=null) {
            readFailed=result.failed(); held=result.admissions();
            if (!result.failed()) { firstRead=true; cursors.put(result.request().scope().propertyId(),result.next()); }
        }
        if (firstRead) {
            recovery.tick();
            if (dispatch!=null) {
                dispatch.poll().ifPresent(outcome -> {
                    lastOutcome=outcome;
                    if(outcome.failure()!=ChronicleDispatchHost.Failure.NONE || outcome.result()==ChronicleDispatcher.Result.UNCONFIRMED
                            || outcome.result()==ChronicleDispatcher.Result.ALREADY_RESERVED) dispatchStatus=Status.HELD_DISPATCH;
                    else if(outcome.result()==ChronicleDispatcher.Result.NOT_AUTHORIZED || outcome.result()==ChronicleDispatcher.Result.HELD_BEFORE_POST)
                        dispatchStatus=Status.HELD_AUTHORIZATION;
                });
                if (dispatchStatus==Status.READY && !readFailed && !readerFatal && !dispatch.running() && !dispatch.held() && authority!=null) {
                    for (HeldAdmission candidate:held) {
                        if (matches(candidate,authority) && dispatch.trySend(candidate.held(),settings.generation(),authority.approval().destination())) {
                            held=held.stream().filter(row -> !row.requestId().equals(candidate.requestId())).toList(); break;
                        }
                    }
                }
            }
        }
        startRead();
    }
    private void startRead() {
        if (readerFatal || !reading.compareAndSet(false,true)) return;
        if (readResult.get()!=null) { reading.set(false); return; }
        long now=monotonic.getAsLong();
        if (readDispatched && now-lastRead<settings.interval().toNanos()) { reading.set(false); return; }
        Approval scope=settings.scopes().get(nextScope); ReadRequest request=new ReadRequest(scope,cursors.get(scope.propertyId()));
        try {
            reader.execute(() -> {
                try { readResult.set(readPage(request)); }
                catch(SQLException | RuntimeException failure) { readResult.set(new ReadResult(request,List.of(),null,true)); }
                catch(Error fatal) { readerFatal=true; readResult.set(new ReadResult(request,List.of(),null,true)); throw fatal; }
                finally { reading.set(false); }
            });
            nextScope=(nextScope+1)%settings.scopes().size(); lastRead=now; readDispatched=true;
        } catch(RuntimeException rejected) { reading.set(false); readerFatal=true; readFailed=true; }
    }

    /** Enable once per runtime, only after independent exact-story approval and actual clean-start verification. */
    public boolean enableDispatch(Path dataDirectory,DeliveryApproval approval,CleanStartDiagnostics diagnostics) {
        return enableDispatch(dataDirectory,approval,diagnostics,null);
    }
    /** Provisioned approval additionally pins the normalized credential actually handed to the transport. */
    public boolean enableDispatch(Path dataDirectory,DeliveryApproval approval,CleanStartDiagnostics diagnostics,String expectedTokenSha256) {
        main(); Objects.requireNonNull(dataDirectory); Objects.requireNonNull(approval); Objects.requireNonNull(diagnostics);
        if(expectedTokenSha256!=null) hashValue(expectedTokenSha256);
        if (!settings.enabled() || closed.get() || dispatchAttempted || !firstRead || readFailed || readerFatal) return false;
        if (!settings.generation().equals(approval.generation()) || approval.stories().stream().anyMatch(story -> !settings.scopes().contains(story.scope())))
            throw new IllegalArgumentException("Chronicle approval does not match runtime policy");
        Optional<CleanStartEvidence> proof=verified(diagnostics);
        if (proof.isEmpty()) { dispatchStatus=Status.HELD_AUTHORIZATION; return false; }
        Map<String,StoryApproval> stories=new HashMap<>(); approval.stories().forEach(story -> stories.put(story.held().eventKey(),story));
        Authority proposed=new Authority(approval,Map.copyOf(stories),diagnostics,proof.get());
        dispatchAttempted=true;
        ChronicleDispatcher.Transport transport=null;
        try {
            String token=credentials.read(dataDirectory);
            if(expectedTokenSha256!=null && !sha(token).equals(expectedTokenSha256)) { dispatchStatus=Status.HELD_CREDENTIAL; return false; }
            if (verified(diagnostics).filter(proof.get()::equals).isEmpty()) { dispatchStatus=Status.HELD_AUTHORIZATION; return false; }
            transport=transports.create(approval.destination(),token);
            if (!(transport instanceof AutoCloseable closeable)) throw new IllegalArgumentException("Chronicle transport must drain");
            dispatch=new ChronicleDispatchHost(new ChronicleDispatcher(new ChronicleSendIntentRepository(database,clock),
                    (event,generation,destination) -> permits(proposed,event,generation,destination),transport),closeable);
            authority=proposed; dispatchStatus=Status.READY; return true;
        } catch(IOException failure) { dispatchStatus=Status.HELD_CREDENTIAL; return false; }
        catch(RuntimeException failure) {
            dispatchStatus=Status.HELD_DISPATCH;
            if (transport instanceof AutoCloseable resource) cleanup=CompletableFuture.runAsync(() -> {
                try { resource.close(); } catch(Exception error) { throw new java.util.concurrent.CompletionException(error); }
            },reader);
            return false;
        }
    }
    private Optional<CleanStartEvidence> verified(CleanStartDiagnostics diagnostics) {
        if (!System.getProperty("jdk.httpclient.HttpClient.log","").isBlank()) return Optional.empty();
        try { return Objects.requireNonNull(diagnostics.verify(process)).filter(evidence -> process.equals(evidence.process())); }
        catch(RuntimeException failure) { return Optional.empty(); }
    }
    private boolean permits(Authority policy,Held event,UUID generation,Destination destination) {
        if (closed.get() || authority!=policy || !policy.approval().generation().equals(generation)
                || !policy.approval().destination().equals(destination) || verified(policy.diagnostics()).filter(policy.evidence()::equals).isEmpty()) return false;
        StoryApproval approved=policy.stories().get(event.eventKey());
        if (approved==null || !approved.held().equals(event)) return false;
        try (ReadSnapshot snapshot=ReadSnapshot.open(database,clock)) {
            Raw raw=raw(snapshot.connection(database),approved.requestId()).orElseThrow(() -> new SQLException("Held event missing"));
            HeldAdmission current=verify(snapshot,raw,approved.scope());
            return !closed.get() && matches(current,policy) && verified(policy.diagnostics()).filter(policy.evidence()::equals).isPresent();
        } catch(SQLException | RuntimeException failure) { return false; }
    }
    private static boolean matches(HeldAdmission candidate,Authority policy) {
        StoryApproval approved=policy.stories().get(candidate.held().eventKey());
        return approved!=null && approved.requestId().equals(candidate.requestId()) && approved.scope().equals(candidate.scope())
                && approved.held().equals(candidate.held());
    }

    private ReadResult readPage(ReadRequest request) throws SQLException {
        try (ReadSnapshot snapshot=ReadSnapshot.open(database,clock)) {
            Connection c=snapshot.connection(database); List<Raw> rows=new ArrayList<>();
            String sql="SELECT o.* FROM npc_chronicle_outbox o JOIN npc_housing_moves m ON m.request_id=o.request_id"
                    +" WHERE o.state='HELD' AND o.authorization_generation=? AND m.property_id=?"
                    +" AND NOT EXISTS (SELECT 1 FROM npc_chronicle_send_intents s WHERE s.event_key=o.event_key)"
                    +(request.after()==null?"":" AND o.request_id>?")+" ORDER BY o.request_id LIMIT ?";
            try(PreparedStatement ps=c.prepareStatement(sql)) {
                ps.setQueryTimeout(5); ps.setBytes(1,Uuids.toBytes(settings.generation())); ps.setBytes(2,Uuids.toBytes(request.scope().propertyId()));
                int index=3; if(request.after()!=null) ps.setBytes(index++,Uuids.toBytes(request.after())); ps.setInt(index,PAGE_SIZE+1);
                try(ResultSet rs=ps.executeQuery()) {
                    while(rs.next()) { if(rows.size()>PAGE_SIZE) throw new SQLException("Held reader exceeds bound"); rows.add(raw(rs)); warnings(c,ps,rs); }
                    warnings(c,ps,rs);
                }
            }
            UUID next=null; if(rows.size()>PAGE_SIZE) { rows.removeLast(); next=rows.getLast().request(); }
            Map<UUID,com.prosperitysmp.npc.market.HousingMoveRepository.Receipt> receipts=
                    admissions.read(snapshot,rows.stream().map(Raw::request).toList()).receipts();
            List<HeldAdmission> page=new ArrayList<>();
            for(Raw row:rows) page.add(verify(snapshot,row,request.scope(),receipts.get(row.request())));
            return new ReadResult(request,List.copyOf(page),next,false);
        }
    }
    private Optional<Raw> raw(Connection c,UUID request) throws SQLException {
        try(PreparedStatement ps=c.prepareStatement("SELECT * FROM npc_chronicle_outbox WHERE request_id=?")) {
            ps.setQueryTimeout(5); ps.setBytes(1,Uuids.toBytes(request));
            try(ResultSet rs=ps.executeQuery()) { Optional<Raw> row=rs.next()?Optional.of(raw(rs)):Optional.empty(); warnings(c,ps,rs); return row; }
        }
    }
    private Raw raw(ResultSet rs) throws SQLException {
        if (!"HELD".equals(rs.getString("state")) || !Arrays.equals(Uuids.toBytes(settings.generation()),rs.getBytes("authorization_generation")))
            throw new SQLException("Held generation conflicts");
        var captured=rs.getTimestamp("captured_at",java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")));
        if(captured==null) throw new SQLException("Held capture missing");
        return new Raw(Uuids.fromBytes(rs.getBytes("request_id")),rs.getString("event_key"),rs.getString("payload_sha256"),
                rs.getString("source_sha256"),rs.getString("public_payload"),captured.toInstant());
    }
    private HeldAdmission verify(ReadSnapshot snapshot,Raw raw,Approval scope) throws SQLException {
        return verify(snapshot,raw,scope,admissions.read(snapshot,List.of(raw.request())).receipts().get(raw.request()));
    }
    private HeldAdmission verify(ReadSnapshot snapshot,Raw raw,Approval scope,com.prosperitysmp.npc.market.HousingMoveRepository.Receipt receipt) throws SQLException {
        try {
            var preview=ChronicleAdmissionTemplate.render(receipt,scope,zone);
            var message=ChronicleWebhookProtocol.fromHeldPayload(raw.payload(),raw.payloadHash());
            String source=sha(receipt.request().fingerprint(receipt.policy())+"|"+receipt.tenancyRevisionAfter()+"|"+receipt.cooldownUntil()+"|"+receipt.appliedAt());
            if (!source.equals(raw.sourceHash()) || !preview.eventKey().equals(raw.event()) || !message.eventKey().equals(raw.event())
                    || !ChronicleWebhookProtocol.message(preview).json().equals(message.json())
                    || raw.capturedAt().isBefore(receipt.appliedAt()) || raw.capturedAt().isAfter(snapshot.capturedAt()))
                throw new SQLException("Held admission binding conflicts");
            return new HeldAdmission(raw.request(),scope,new Held(raw.event(),raw.payloadHash()),preview);
        } catch(IllegalArgumentException | NullPointerException invalid) { throw new SQLException("Invalid held admission",invalid); }
    }

    public List<HeldAdmission> heldAdmissions() { main(); return held; }
    public List<ChronicleRecoveryHost.Health> recoveryHealth() { main(); return recovery==null?List.of():recovery.health(); }
    public Optional<ChronicleDispatchHost.Outcome> lastDispatchOutcome() { main(); return Optional.ofNullable(lastOutcome); }
    public Status status() {
        main();
        if(shutdown!=null) return !shutdown.isDone()?Status.STOPPING:shutdown.isCompletedExceptionally()?Status.STOP_FAILED:Status.STOPPED;
        if(!settings.enabled()) return Status.DISABLED;
        if(readerFatal || readFailed) return Status.HELD_READ;
        if(!firstRead) return Status.STARTING;
        if(dispatch!=null && dispatch.held()) return Status.HELD_DISPATCH;
        if(recovery.held() || recovery.health().stream().anyMatch(row -> row.disposition()==ChronicleRecoveryHost.Disposition.FAILED
                || row.disposition()==ChronicleRecoveryHost.Disposition.UNRESOLVED)) return Status.HELD_RECOVERY;
        return dispatchStatus;
    }
    /** Revoke first, then acknowledge all SQL/HTTP drains. Never close the shared database here. */
    public CompletionStage<Void> shutdown() {
        main(); if(shutdown!=null) return shutdown.minimalCompletionStage();
        closed.set(true); authority=null; held=List.of();
        CompletionStage<Void> recoveryDrain=recovery==null?CompletableFuture.completedFuture(null):recovery.shutdown();
        CompletionStage<Void> dispatchDrain=dispatch==null?CompletableFuture.completedFuture(null):dispatch.shutdown();
        if(reader!=null) reader.shutdown();
        shutdown=CompletableFuture.allOf(readerDrained,recoveryDrain.toCompletableFuture(),dispatchDrain.toCompletableFuture(),cleanup);
        return shutdown.minimalCompletionStage();
    }
    private void main() { if(Thread.currentThread()!=owner) throw new IllegalStateException("Chronicle runtime owner required"); }
    private static void validHeld(Held held) { Objects.requireNonNull(held); hashValue(held.eventKey()); hashValue(held.payloadSha256()); }
    private static void hashValue(String value) { if(value==null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid Chronicle hash"); }
    private static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void warnings(Connection c,PreparedStatement ps,ResultSet rs) throws SQLException {
        if(c.getWarnings()!=null || ps.getWarnings()!=null || rs.getWarnings()!=null) throw new SQLException("Incomplete held evidence");
    }
    private static String readWebhookToken(Path dataDirectory) throws IOException {
        byte[] bytes=null;
        try {
            Path directory=dataDirectory.toAbsolutePath().normalize();
            if(!directory.equals(directory.toRealPath()) || !Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)) throw new IOException();
            Path file=directory.resolve("chronicle-token.txt"); PosixFileAttributes before=tokenAttributes(file);
            try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) { bytes=input.readNBytes(258); }
            PosixFileAttributes after=tokenAttributes(file);
            if(bytes.length!=before.size() || !Objects.equals(before.fileKey(),after.fileKey()) || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || before.size()!=after.size() || !before.permissions().equals(after.permissions()) || !before.owner().equals(after.owner())) throw new IOException();
            int length=bytes.length; if(length>0 && bytes[length-1]=='\n') length--;
            if(length<1 || length>256) throw new IOException();
            for(int i=0;i<length;i++) {
                int b=bytes[i]&255;
                if(!(b>='A'&&b<='Z' || b>='a'&&b<='z' || b>='0'&&b<='9' || b=='.' || b=='_' || b=='-')) throw new IOException();
            }
            return new String(bytes,0,length,StandardCharsets.US_ASCII);
        } catch(IOException | RuntimeException invalid) { throw new IOException("Chronicle credential unavailable"); }
        finally { if(bytes!=null) Arrays.fill(bytes,(byte)0); }
    }
    static String credentialFingerprint(Path dataDirectory) throws IOException { return sha(readWebhookToken(dataDirectory)); }
    private static PosixFileAttributes tokenAttributes(Path file) throws IOException {
        PosixFileAttributes attributes=Files.readAttributes(file,PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!attributes.isRegularFile() || attributes.fileKey()==null || attributes.size()<1 || attributes.size()>257
                || !(attributes.permissions().equals(Set.of(PosixFilePermission.OWNER_READ))
                || attributes.permissions().equals(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE)))) throw new IOException();
        return attributes;
    }
}

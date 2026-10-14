package com.prosperitysmp.npc.publication;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.prosperitysmp.npc.publication.ChronicleRuntime.CleanStartEvidence;
import com.prosperitysmp.npc.publication.ChronicleRuntime.DeliveryApproval;
import com.prosperitysmp.npc.publication.ChronicleRuntime.ProcessIdentity;
import com.prosperitysmp.npc.publication.ChronicleRuntime.StoryApproval;
import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Exact private-file owner approval plus actual startup-agent evidence; no config boolean or permissive fallback. */
public final class ChronicleProvisioning {
    public static final String APPROVAL_FILE="chronicle-approval.json";
    private static final String BOOTSTRAP="com.prosperitysmp.npc.publication.bootstrap.ChronicleBootstrap";
    private static final int MAX_BYTES=65536;
    public enum Result { DISABLED, WAITING_FOR_HELD_READ, NO_APPROVAL, HELD_APPROVAL, HELD_BOOTSTRAP, HELD_CREDENTIAL, ENABLED }
    /** Redacted inspection only. Artifact/JDK/startup hashes must be independently approved in the private file. */
    public record BootstrapStatus(ProcessIdentity process,String artifactSha256,String javaVersion,String startupInputsSha256,
                                  String httpLogSha256,String httpUtilsSha256,String httpDebugSha256,String sslLoggerSha256,String evidenceSha256) { }
    private record Approval(DeliveryApproval delivery,Instant expiresAt,String tokenSha256,String artifactSha256,String javaVersion,
                            String startupInputsSha256,String httpLogSha256,String httpUtilsSha256,String httpDebugSha256,String sslLoggerSha256) { }
    private record Snapshot(Path path,PosixFileAttributes attributes,byte[] bytes,String sha256) { }
    private ChronicleProvisioning() { }

    /** Owner/main only after initial HELD read. Bounded private startup-file I/O, no SQL or HTTP request here. */
    public static Result tryEnable(ChronicleRuntime runtime,Path dataDirectory) {
        Objects.requireNonNull(runtime); Objects.requireNonNull(dataDirectory);
        if(runtime.status()==ChronicleRuntime.Status.DISABLED) return Result.DISABLED;
        if(runtime.status()==ChronicleRuntime.Status.STARTING) return Result.WAITING_FOR_HELD_READ;
        if(runtime.status()==ChronicleRuntime.Status.READY) return Result.ENABLED;
        Snapshot snapshot; Approval approval;
        try { snapshot=read(dataDirectory); approval=decode(snapshot.bytes()); }
        catch(NoSuchFileException absent) { return Result.NO_APPROVAL; }
        catch(IOException | RuntimeException refused) { return Result.HELD_APPROVAL; }
        Optional<BootstrapStatus> boot=bootstrapStatus();
        if(boot.isEmpty() || !matches(approval,boot.get())) return Result.HELD_BOOTSTRAP;
        if(!Clock.systemUTC().instant().isBefore(approval.expiresAt())) return Result.HELD_APPROVAL;
        try {
            if(!ChronicleRuntime.credentialFingerprint(dataDirectory).equals(approval.tokenSha256())) return Result.HELD_CREDENTIAL;
        } catch(IOException | RuntimeException refused) { return Result.HELD_CREDENTIAL; }
        try {
            boolean enabled=runtime.enableDispatch(dataDirectory,approval.delivery(),process -> {
                try {
                    Optional<BootstrapStatus> verified=bootstrapStatus().filter(actual -> actual.process().equals(process)
                            && actual.equals(boot.get()) && matches(approval,actual));
                    if(verified.isEmpty()) return Optional.empty();
                    Snapshot current=read(dataDirectory);
                    if(!same(snapshot,current) || !Clock.systemUTC().instant().isBefore(approval.expiresAt())
                            || !ChronicleRuntime.credentialFingerprint(dataDirectory).equals(approval.tokenSha256())) return Optional.empty();
                    return bootstrapStatus().filter(verified.get()::equals)
                            .map(actual -> new CleanStartEvidence(process,actual.evidenceSha256()));
                } catch(IOException | RuntimeException revoked) { return Optional.empty(); }
            },approval.tokenSha256());
            if(enabled) return Result.ENABLED;
            return runtime.status()==ChronicleRuntime.Status.HELD_CREDENTIAL?Result.HELD_CREDENTIAL:Result.HELD_BOOTSTRAP;
        } catch(RuntimeException refused) { return Result.HELD_APPROVAL; }
    }

    /** Explicit system-loader bridge: plugin child-loader bootstrap copies cannot mint or provide proof. */
    public static String bootstrapDiagnosticStatus() {
        try {
            ClassLoader system=ClassLoader.getSystemClassLoader(); Class<?> type=Class.forName(BOOTSTRAP,false,system);
            if(type.getClassLoader()!=system || type.getModule().isNamed()) return "UNAVAILABLE_SYSTEM_AGENT";
            Object value=type.getMethod("diagnosticStatus").invoke(null);
            if(value instanceof String status && status.matches("(?:READY|NO_PREMAIN|HELD_[A-Z_]{1,48})")) return status;
        } catch(Exception | LinkageError held) { }
        return "UNAVAILABLE_SYSTEM_AGENT";
    }

    /** Explicit system-loader bridge: plugin child-loader bootstrap copies cannot mint or provide proof. */
    public static Optional<BootstrapStatus> bootstrapStatus() {
        try {
            ClassLoader system=ClassLoader.getSystemClassLoader(); Class<?> type=Class.forName(BOOTSTRAP,false,system);
            if(type.getClassLoader()!=system || type.getModule().isNamed()) return Optional.empty();
            Object returned=type.getMethod("evidence").invoke(null);
            if(!(returned instanceof String[] fields) || fields.length!=11 || !fields[0].equals("npc-chronicle-bootstrap-v1")) return Optional.empty();
            for(int index:new int[]{3,5,6,7,8,9,10}) hash(fields[index]);
            ProcessIdentity process=new ProcessIdentity(Long.parseLong(fields[1]),Instant.parse(fields[2]));
            if(process.pid()!=ProcessHandle.current().pid() || !process.startedAt().equals(ProcessHandle.current().info().startInstant().orElseThrow())
                    || !fields[1].equals(Long.toString(process.pid())) || !fields[2].equals(process.startedAt().toString())) return Optional.empty();
            Path artifact=Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toRealPath();
            if(!Files.isRegularFile(artifact,LinkOption.NOFOLLOW_LINKS) || Files.size(artifact)>1_048_576) return Optional.empty();
            byte[] bytes; try(var stream=Files.newInputStream(artifact,LinkOption.NOFOLLOW_LINKS)) { bytes=stream.readNBytes(1_048_577); }
            if(bytes.length>1_048_576 || !sha(bytes).equals(fields[3]) || !Runtime.version().toString().equals(fields[4])) return Optional.empty();
            return Optional.of(new BootstrapStatus(process,fields[3],fields[4],fields[5],fields[6],fields[7],fields[8],fields[9],fields[10]));
        } catch(Exception | LinkageError held) { return Optional.empty(); }
    }
    private static boolean matches(Approval approval,BootstrapStatus boot) {
        return approval.artifactSha256().equals(boot.artifactSha256()) && approval.javaVersion().equals(boot.javaVersion())
                && approval.startupInputsSha256().equals(boot.startupInputsSha256()) && approval.httpLogSha256().equals(boot.httpLogSha256())
                && approval.httpUtilsSha256().equals(boot.httpUtilsSha256()) && approval.httpDebugSha256().equals(boot.httpDebugSha256())
                && approval.sslLoggerSha256().equals(boot.sslLoggerSha256());
    }
    /** Fixed field order rejects duplicate/unknown fields without an unbounded general-purpose JSON tree. */
    private static Approval decode(byte[] bytes) throws IOException {
        if(bytes.length<1 || bytes.length>MAX_BYTES) throw invalid();
        try {
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            try(JsonReader r=new JsonReader(new StringReader(text))) {
                r.setStrictness(Strictness.STRICT); r.beginObject();
                name(r,"version"); if(!string(r,40).equals("npc-chronicle-approval-v1")) throw invalid();
                name(r,"approvalRef"); String reference=string(r,96);
                name(r,"generation"); UUID generation=uuid(r);
                name(r,"expiresAt"); String expires=string(r,32); Instant expiresAt=Instant.parse(expires);
                if(!expires.equals(expiresAt.toString())) throw invalid();
                name(r,"webhookId"); String webhook=string(r,20); name(r,"channelId"); String channel=string(r,20);
                name(r,"tokenSha256"); String token=hashString(r);
                name(r,"bootstrapJarSha256"); String artifact=hashString(r);
                name(r,"javaRuntimeVersion"); String java=string(r,96);
                name(r,"startupInputsSha256"); String startup=hashString(r);
                name(r,"httpLogSha256"); String log=hashString(r); name(r,"httpUtilsSha256"); String utils=hashString(r);
                name(r,"httpDebugSha256"); String debug=hashString(r); name(r,"sslLoggerSha256"); String ssl=hashString(r);
                name(r,"stories"); r.beginArray(); List<StoryApproval> stories=new ArrayList<>();
                while(r.peek()!=JsonToken.END_ARRAY) {
                    if(stories.size()>=ChronicleRuntime.MAX_STORY_APPROVALS) throw invalid();
                    r.beginObject(); name(r,"requestId"); UUID request=uuid(r); name(r,"propertyId"); UUID property=uuid(r);
                    name(r,"worldId"); UUID world=uuid(r); name(r,"regionId"); String region=string(r,128);
                    name(r,"publicWorldKey"); String publicWorld=string(r,64); name(r,"eventKey"); String event=hashString(r);
                    name(r,"payloadSha256"); String payload=hashString(r); r.endObject();
                    stories.add(new StoryApproval(request,new PublicOccupancySnapshot.Approval(property,world,region,publicWorld),
                            new ChronicleOutboxRepository.Held(event,payload)));
                }
                r.endArray(); r.endObject(); if(r.peek()!=JsonToken.END_DOCUMENT) throw invalid();
                return new Approval(new DeliveryApproval(reference,generation,new ChronicleWebhookProtocol.Destination(webhook,channel),stories),
                        expiresAt,token,artifact,java,startup,log,utils,debug,ssl);
            }
        } catch(RuntimeException malformed) { throw invalid(); }
    }
    static DeliveryApproval decodeForTest(byte[] bytes) throws IOException { return decode(bytes).delivery(); }
    private static UUID uuid(JsonReader r) throws IOException {
        String value=string(r,36); UUID uuid=UUID.fromString(value); if(!uuid.toString().equals(value)) throw invalid(); return uuid;
    }
    private static void name(JsonReader r,String expected) throws IOException {
        if(r.peek()!=JsonToken.NAME || !r.nextName().equals(expected)) throw invalid();
    }
    private static String string(JsonReader r,int bound) throws IOException {
        if(r.peek()!=JsonToken.STRING) throw invalid(); String value=r.nextString(); if(value.length()>bound) throw invalid(); return value;
    }
    private static String hashString(JsonReader r) throws IOException { String value=string(r,64); hash(value); return value; }
    private static void hash(String value) { if(value==null || !value.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid approval hash"); }
    private static Snapshot read(Path dataDirectory) throws IOException {
        Path directory=dataDirectory.toAbsolutePath().normalize();
        if(!directory.equals(directory.toRealPath()) || !Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)) throw invalid();
        Path file=directory.resolve(APPROVAL_FILE); PosixFileAttributes before=attributes(file); byte[] bytes;
        try(var stream=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) { bytes=stream.readNBytes(MAX_BYTES+1); }
        PosixFileAttributes after=attributes(file);
        if(bytes.length!=before.size() || !sameAttributes(before,after)) throw invalid();
        return new Snapshot(file,before,bytes,sha(bytes));
    }
    private static PosixFileAttributes attributes(Path file) throws IOException {
        PosixFileAttributes attributes=Files.readAttributes(file,PosixFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(!attributes.isRegularFile() || attributes.fileKey()==null || attributes.size()<1 || attributes.size()>MAX_BYTES
                || !(attributes.permissions().equals(Set.of(PosixFilePermission.OWNER_READ))
                || attributes.permissions().equals(Set.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE)))) throw invalid();
        return attributes;
    }
    private static boolean sameAttributes(PosixFileAttributes a,PosixFileAttributes b) {
        return Objects.equals(a.fileKey(),b.fileKey()) && a.size()==b.size() && a.lastModifiedTime().equals(b.lastModifiedTime())
                && a.owner().equals(b.owner()) && a.permissions().equals(b.permissions());
    }
    private static boolean same(Snapshot a,Snapshot b) { return a.path().equals(b.path()) && sameAttributes(a.attributes(),b.attributes()) && a.sha256().equals(b.sha256()); }
    private static String sha(byte[] bytes) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IOException("SHA-256 unavailable"); }
    }
    private static IOException invalid() { return new IOException("Private Chronicle approval unavailable or invalid"); }
}

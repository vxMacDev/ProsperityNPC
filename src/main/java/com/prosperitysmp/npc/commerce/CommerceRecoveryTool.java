package com.prosperitysmp.npc.commerce;

import com.prosperitysmp.npc.commerce.CommerceOrderRepository.Handle;
import com.prosperitysmp.npc.commerce.CommerceOrderRepository.PaymentDecision;
import com.prosperitysmp.npc.commerce.CommerceOrderRepository.PaymentResolution;
import com.prosperitysmp.npc.db.Database;
import com.prosperitysmp.npc.db.DatabaseSettings;
import com.prosperitysmp.npc.economy.RecoveryServers;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Root-only, stopped/runtime-masked server adjudication; never Vault, item delivery, or migrations.
 * Profile: service, server_root, database, user, reserve_uuid; service and server_root must match
 * {@link RecoveryServers#FILE}. Manifest binds the same service/root/database,
 * order_id, payer_uuid, reserve_uuid, run_id, provider_sha256, request_sha256, cost_cents, resolution_id,
 * operator, case_ref, notes, decision (VERIFIED_PAID, VERIFIED_NO_MOVEMENT or VERIFIED_REFUNDED_DEBIT),
 * resolved_at, payment_observation_sha256. Adjacent private
 * payment-observed.json is independently reviewed evidence, not a substitute for persisted observations.
 * Refund decisions additionally require refund_completed=true, unchanged reserve_before/reserve_after,
 * refund_before_bits/refund_after_bits and hashes of refund-intent.json/refund-observed.json.
 * Cleanup (--cancel-unstarted) requires the same service/server_root/database/reserve_uuid binding plus
 * cutoff, limit, operator, case_ref, notes in its private manifest.
 * Usage: --reconcile-payment|--cancel-unstarted &lt;profile&gt; &lt;evidence&gt;. Connects to MariaDB on 127.0.0.1:3306.
 */
public final class CommerceRecoveryTool {
    private CommerceRecoveryTool() { }

    public static void main(String[] args) throws Exception {
        if (args.length!=3 || (!args[0].equals("--reconcile-payment") && !args[0].equals("--cancel-unstarted")))
            throw new IllegalArgumentException("Expected --reconcile-payment|--cancel-unstarted <private profile.properties> <private evidence.properties>");
        if (!command("id","-u").equals("0")) throw new IllegalStateException("Root operator required");
        Properties config=properties(privateBytes(Path.of(args[1]).toAbsolutePath(),16384));
        String service=required(config,"service");
        String root=RecoveryServers.rootFor(service);
        if (!root.equals(required(config,"server_root"))) throw new IllegalArgumentException("Service/root mismatch");
        offline(service);
        Path manifest=Path.of(args[2]).toAbsolutePath();
        byte[] manifestBytes=privateBytes(manifest,16384); Properties evidence=properties(manifestBytes);
        for (String key:new String[]{"service","server_root","database","reserve_uuid"})
            if (!required(config,key).equals(required(evidence,key))) throw new IllegalArgumentException("Evidence profile binding conflicts: "+key);
        for (String key:new String[]{"database","user"})
            if (!required(config,key).matches("[A-Za-z0-9_]{1,64}")) throw new IllegalArgumentException("Invalid connection identifier");
        UUID reserve=UUID.fromString(required(config,"reserve_uuid"));
        Path password=Path.of(root,"plugins/ProsperityNPC/db-password.txt");
        if (!password.equals(password.toRealPath()) || !Files.isRegularFile(password,LinkOption.NOFOLLOW_LINKS)
                || Files.size(password)>4096 || !privateMode(password)) throw new IllegalArgumentException("Invalid password custody");
        String operator=required(evidence,"operator"), caseRef=required(evidence,"case_ref"), notes=required(evidence,"notes");
        if (!operator.matches("[A-Za-z0-9_.:@-]{1,96}") || !caseRef.matches("[A-Za-z0-9_.:@-]{1,96}") || notes.length()>2048)
            throw new IllegalArgumentException("Invalid operator case notes");
        try (Database db=new Database()) {
            db.connect(new DatabaseSettings("127.0.0.1",3306,required(config,"database"),required(config,"user"),
                    Files.readString(password).trim(),1,5000));
            CommerceOrderRepository repository=new CommerceOrderRepository(db,reserve);
            if (args[0].equals("--cancel-unstarted")) {
                Instant cutoff=Instant.parse(required(evidence,"cutoff")); int limit=Integer.parseInt(required(evidence,"limit"));
                if (limit<0 || limit>CommerceOrderRepository.MAX_SWEEP) throw new IllegalArgumentException("Cleanup limit exceeds bounds");
                offline(service);
                int cancelled=repository.cancelUnstartedBefore(cutoff,limit);
                System.out.println("Untouched reservations cancelled: "+cancelled+"; actor="+operator+"; case="+caseRef
                        +"; manifest_sha256="+hash(manifestBytes));
                return;
            }
            UUID payer=UUID.fromString(required(evidence,"payer_uuid"));
            UUID orderId=UUID.fromString(required(evidence,"order_id"));
            Handle handle=new Handle(orderId,UUID.fromString(required(evidence,"run_id")),payer,reserve,required(evidence,"provider_sha256"));
            CommerceOrder order=repository.find(payer,orderId).orElseThrow(() -> new IllegalStateException("Unknown commerce order")).order();
            if (!order.requestSha256().equals(required(evidence,"request_sha256"))
                    || order.costCents()!=Long.parseLong(required(evidence,"cost_cents")))
                throw new IllegalArgumentException("Order hash/cost evidence conflicts");
            PaymentDecision decision=PaymentDecision.valueOf(required(evidence,"decision"));
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try (DataOutputStream bundle=new DataOutputStream(bytes)) {
                part(bundle,manifestBytes);
                part(bundle,artifact(manifest,"payment-observed.json",required(evidence,"payment_observation_sha256")));
                if (decision==PaymentDecision.VERIFIED_REFUNDED_DEBIT) {
                    if (!required(evidence,"refund_completed").equals("true")
                            || !required(evidence,"reserve_before").equals(required(evidence,"reserve_after")))
                        throw new IllegalArgumentException("Independently completed refund with unchanged reserve required");
                    part(bundle,artifact(manifest,"refund-intent.json",required(evidence,"intent_sha256")));
                    part(bundle,artifact(manifest,"refund-observed.json",required(evidence,"observation_sha256")));
                } else if (evidence.containsKey("refund_before_bits") || evidence.containsKey("refund_after_bits"))
                    throw new IllegalArgumentException("Refund bits without refund decision");
            }
            boolean refund=decision==PaymentDecision.VERIFIED_REFUNDED_DEBIT;
            PaymentResolution resolution=new PaymentResolution(UUID.fromString(required(evidence,"resolution_id")),operator,caseRef,notes,
                    order.requestSha256(),decision,new CustodyBytes(bytes.toByteArray()),
                    refund?Long.valueOf(required(evidence,"refund_before_bits")):null,
                    refund?Long.valueOf(required(evidence,"refund_after_bits")):null,Instant.parse(required(evidence,"resolved_at")));
            offline(service);
            var receipt=repository.reconcilePayment(order,handle,resolution);
            System.out.println("Commerce order "+orderId+": "+receipt.state()+"; resolution="+resolution.resolutionId());
        }
    }

    private static void part(DataOutputStream stream,byte[] bytes) throws Exception { stream.writeInt(bytes.length); stream.write(bytes); }
    private static byte[] artifact(Path manifest,String name,String expectedHash) throws Exception {
        ItemDeliveryEvidence.requireHash(expectedHash);
        byte[] bytes=privateBytes(manifest.getParent().resolve(name),16384);
        if (!hash(bytes).equals(expectedHash)) throw new IllegalArgumentException("Case artifact changed: "+name);
        return bytes;
    }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static byte[] privateBytes(Path path,int limit) throws Exception {
        if (!path.equals(path.toRealPath()) || !Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)
                || !Files.getOwner(path).getName().equals("root") || Files.size(path)<1 || Files.size(path)>limit || !privateMode(path))
            throw new IllegalArgumentException("Evidence/profile must be a bounded, private root-owned regular file");
        try (var stream=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes=stream.readNBytes(limit+1);
            if (bytes.length<1 || bytes.length>limit) throw new IllegalArgumentException("Evidence/profile size changed");
            return bytes;
        }
    }
    private static boolean privateMode(Path path) throws Exception {
        return Files.getPosixFilePermissions(path).stream()
                .noneMatch(p -> p!=PosixFilePermission.OWNER_READ && p!=PosixFilePermission.OWNER_WRITE);
    }
    private static Properties properties(byte[] bytes) throws Exception {
        Properties properties=new Properties(); properties.load(new ByteArrayInputStream(bytes)); return properties;
    }
    private static void offline(String service) throws Exception {
        if (!command("systemctl","show",service,"-p","LoadState","--value").equals("masked")
                || !command("systemctl","show",service,"-p","ActiveState","--value").equals("inactive"))
            throw new IllegalStateException("Server must be stopped and runtime-masked throughout recovery");
    }
    private static String command(String... args) throws Exception {
        Process process=new ProcessBuilder(args).start();
        if (!process.waitFor(5,TimeUnit.SECONDS)) {
            process.destroyForcibly(); throw new IllegalStateException("Recovery environment check timed out");
        }
        if (process.exitValue()!=0) throw new IllegalStateException("Recovery environment check failed");
        return new String(process.getInputStream().readNBytes(4096)).trim();
    }
    private static String required(Properties properties,String key) {
        String value=properties.getProperty(key);
        if (value==null || value.isBlank()) throw new IllegalArgumentException("Missing evidence field: "+key);
        return value;
    }
}

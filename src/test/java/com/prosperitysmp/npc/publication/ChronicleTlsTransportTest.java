package com.prosperitysmp.npc.publication;

import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.LocalDate;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class ChronicleTlsTransportTest {
    // Disposable test-only keys. No production trust store or default SSL context is changed.
    private static final char[] PASSWORD="fixture-only".toCharArray();
    private static final ChronicleWebhookProtocol.Destination DEST=new ChronicleWebhookProtocol.Destination("123","456");
    @TempDir static Path directory;
    private static KeyStore matching,wrongHost;
    @BeforeAll static void certificates() throws Exception {
        matching=generate("matching","IP:127.0.0.1");wrongHost=generate("wrong-host","DNS:wrong.invalid");
    }
    private static KeyStore generate(String name,String san) throws Exception {
        var file=directory.resolve(name+".p12");var log=directory.resolve(name+".log");
        String exe=System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",exe).toString(),
                "-genkeypair","-alias","fixture","-keyalg","RSA","-keysize","2048","-validity","2",
                "-dname","CN=fixture.invalid","-ext","SAN="+san,"-storetype","PKCS12",
                "-keystore",file.toString(),"-storepass",new String(PASSWORD),"-noprompt")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{assertTrue(process.waitFor(15,TimeUnit.SECONDS));assertEquals(0,process.exitValue(),Files.readString(log));}
        finally{if(process.isAlive()){process.destroyForcibly();assertTrue(process.waitFor(3,TimeUnit.SECONDS));}}
        var store=KeyStore.getInstance("PKCS12");try(var in=Files.newInputStream(file)){store.load(in,PASSWORD);}return store;
    }
    private static SSLContext serverContext(KeyStore keys) throws Exception {
        var manager=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());manager.init(keys,PASSWORD);
        var context=SSLContext.getInstance("TLS");context.init(manager.getKeyManagers(),null,null);return context;
    }
    private static SSLContext clientContext(KeyStore trusted) throws Exception {
        var trust=KeyStore.getInstance("PKCS12");trust.load(null,null);trust.setCertificateEntry("fixture",trusted.getCertificate("fixture"));
        var manager=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());manager.init(trust);
        var context=SSLContext.getInstance("TLS");context.init(null,manager.getTrustManagers(),null);return context;
    }
    private static ChronicleWebhookProtocol.Message message(){return ChronicleWebhookProtocol.message(new ChronicleAdmissionTemplate.Preview(
            "a".repeat(64),"npc-admission-story-v1","main","c001",LocalDate.of(2026,9,8),
            "A new arrival at c001","A resident moved into c001 on 2026-09-08."));}
    private static void exercise(KeyStore serving,KeyStore trusted,boolean accepted) throws Exception {
        var server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext(serving)));
        var received=new AtomicInteger();var entered=new AtomicInteger();var byteCount=new AtomicInteger();
        var observed=new AtomicReference<Throwable>();var calls=Executors.newSingleThreadExecutor();
        var handlers=Executors.newCachedThreadPool(r->{var t=new Thread(r,"tls-fixture");t.setDaemon(true);return t;});
        server.setExecutor(handlers);server.createContext("/",e->{try(e){
            entered.incrementAndGet();byte[] body=e.getRequestBody().readAllBytes();byteCount.addAndGet(body.length);
            if(message().json().equals(new String(body,StandardCharsets.UTF_8)))received.incrementAndGet();
            e.sendResponseHeaders(200,2);e.getResponseBody().write(new byte[]{'{','}'});
        }});
        ChronicleHttpTransport transport=null;
        try {
            server.start();transport=ChronicleHttpTransport.loopbackTlsFixture(DEST,server.getAddress().getPort(),clientContext(trusted),observed::set);
            var active=transport;var result=calls.submit(()->active.post(DEST,message()));
            if(accepted){assertEquals(200,result.get(5,TimeUnit.SECONDS).status());assertEquals(1,received.get());assertEquals(1,entered.get());assertTrue(byteCount.get()>0);assertNull(observed.get());}
            else {
                var failure=assertThrows(ExecutionException.class,()->result.get(5,TimeUnit.SECONDS));
                assertEquals("Chronicle HTTP result unconfirmed",failure.getCause().getMessage());
                assertNull(failure.getCause().getCause());assertEquals(0,received.get());assertEquals(0,entered.get());assertEquals(0,byteCount.get());
                boolean handshake=false,reason=false;
                for(Throwable t=observed.get();t!=null;t=t.getCause()) {
                    handshake|=t instanceof SSLHandshakeException;
                    if(serving==trusted)reason|=t instanceof java.security.cert.CertificateException && t.getMessage()!=null && t.getMessage().contains("127.0.0.1");
                    else reason|=t instanceof java.security.cert.CertPathBuilderException || t instanceof java.security.cert.CertPathValidatorException;
                }
                assertTrue(handshake,"Expected TLS handshake rejection, not timeout");assertTrue(reason,"Expected specific certificate/hostname cause");
            }
        }finally {
            var closing=transport;
            try{if(closing!=null)calls.submit(()->{closing.close();return null;}).get(4,TimeUnit.SECONDS);}
            finally{server.stop(0);calls.shutdownNow();handlers.shutdownNow();assertTrue(calls.awaitTermination(2,TimeUnit.SECONDS));assertTrue(handlers.awaitTermination(2,TimeUnit.SECONDS));}
        }
    }
    @Test void trustedMatchingHostnameDelivers() throws Exception {exercise(matching,matching,true);}
    @Test void untrustedCertificateNeverDeliversBody() throws Exception {exercise(matching,wrongHost,false);}
    @Test void trustedWrongHostnameNeverDeliversBody() throws Exception {exercise(wrongHost,wrongHost,false);}
}

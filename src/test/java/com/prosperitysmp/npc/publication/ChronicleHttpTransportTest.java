package com.prosperitysmp.npc.publication;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class ChronicleHttpTransportTest {
    private static final ChronicleWebhookProtocol.Destination DEST=new ChronicleWebhookProtocol.Destination("123","456");
    private static ChronicleWebhookProtocol.Message message(){return ChronicleWebhookProtocol.message(new ChronicleAdmissionTemplate.Preview(
            "a".repeat(64),"npc-admission-story-v1","main","c001",LocalDate.of(2026,9,8),"A new arrival at c001","A resident moved into c001 on 2026-09-08."));}
    private static final class Fixture implements AutoCloseable {
        final HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        final ExecutorService handlers=Executors.newCachedThreadPool(r->{var t=new Thread(r,"http-transport-fixture");t.setDaemon(true);return t;});
        final ExecutorService caller=Executors.newSingleThreadExecutor();final ChronicleHttpTransport transport;
        Fixture(HttpHandler handler,Duration deadline) throws IOException {
            server.setExecutor(handlers);server.createContext("/",e->{try(e){handler.handle(e);}catch(IOException ignored){}});server.start();
            transport=ChronicleHttpTransport.loopbackFixture(DEST,server.getAddress().getPort(),deadline);
        }
        ChronicleDispatcher.Reply post() throws Exception{return caller.submit(()->transport.post(DEST,message())).get(5,TimeUnit.SECONDS);}
        public void close() throws Exception {
            try{caller.submit(()->{transport.close();return null;}).get(5,TimeUnit.SECONDS);}
            finally{server.stop(0);caller.shutdownNow();handlers.shutdownNow();assertTrue(caller.awaitTermination(2,TimeUnit.SECONDS));assertTrue(handlers.awaitTermination(2,TimeUnit.SECONDS));}
        }
    }
    @Test void exactWireBodyAndWaitQueryUseRealSocketOffOwner() throws Exception {
        var body=new AtomicReference<String>();var query=new AtomicReference<String>();var calls=new AtomicInteger();
        try(var f=new Fixture(e->{calls.incrementAndGet();query.set(e.getRequestURI().getRawQuery());
            body.set(new String(e.getRequestBody().readNBytes(8193),StandardCharsets.UTF_8));
            e.sendResponseHeaders(200,2);e.getResponseBody().write(new byte[]{'{','}'});
        },Duration.ofSeconds(2))) {
            assertThrows(IllegalStateException.class,()->f.transport.post(DEST,message()));
            var reply=f.post();assertEquals(200,reply.status());assertArrayEquals(new byte[]{'{','}'},reply.body());
            var copied=reply.body();copied[0]=0;assertEquals('{',reply.body()[0]);
            assertEquals(1,calls.get());assertEquals("wait=true",query.get());assertEquals(message().json(),body.get());
            var failure=assertThrows(ExecutionException.class,()->f.caller.submit(()->f.transport.post(new ChronicleWebhookProtocol.Destination("999","456"),message())).get());
            assertEquals("Chronicle destination mismatch",failure.getCause().getMessage());assertEquals(1,calls.get());
        }
    }
    @Test void redirectIsReturnedWithoutFollowingLocation() throws Exception {
        var calls=new AtomicInteger();
        try(var f=new Fixture(e->{calls.incrementAndGet();e.getRequestBody().readAllBytes();e.getResponseHeaders().add("Location","http://127.0.0.1:1/secret");e.sendResponseHeaders(307,-1);},Duration.ofSeconds(2))) {
            var reply=f.post();assertEquals(307,reply.status());assertEquals(0,reply.body().length);assertEquals(1,calls.get());
        }
    }
    @Test void oversizedBodyIsCancelledWithoutReturningAReply() throws Exception {
        try(var f=new Fixture(e->{e.getRequestBody().readAllBytes();e.sendResponseHeaders(200,32769);e.getResponseBody().write(new byte[32769]);},Duration.ofSeconds(2))) {
            var failure=assertThrows(ExecutionException.class,f::post);assertEquals("Chronicle HTTP result unconfirmed",failure.getCause().getMessage());
        }
    }
    @Test void deadlineIncludesAStalledBodyAfterHeadersArrive() throws Exception {
        var headers=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var f=new Fixture(e->{e.getRequestBody().readAllBytes();e.sendResponseHeaders(200,10);e.getResponseBody().write(1);e.getResponseBody().flush();headers.countDown();
            try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        },Duration.ofMillis(250))) {
            try {
                var result=f.caller.submit(()->f.transport.post(DEST,message()));assertTrue(headers.await(2,TimeUnit.SECONDS));
                var failure=assertThrows(ExecutionException.class,()->result.get(2,TimeUnit.SECONDS));
                assertEquals("Chronicle HTTP result unconfirmed",failure.getCause().getMessage());
            }finally{release.countDown();}
        }
    }
    @Test void unsafeCredentialTextRejectsBeforeCreatingClient() {
        for(String token:new String[]{"","secret?x=1","secret/path","secret\nheader","x".repeat(257)})
            assertThrows(IllegalArgumentException.class,()->new ChronicleHttpTransport(DEST,token));
    }
    @Test void overlappingPostIsRejectedAndClosedTransportCannotSendAgain() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        try(var f=new Fixture(e->{calls.incrementAndGet();e.getRequestBody().readAllBytes();entered.countDown();
            try{release.await(4,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            e.sendResponseHeaders(204,-1);
        },Duration.ofSeconds(5))) {
            var other=Executors.newSingleThreadExecutor();
            try {
                var first=f.caller.submit(()->f.transport.post(DEST,message()));assertTrue(entered.await(2,TimeUnit.SECONDS));
                var failure=assertThrows(ExecutionException.class,()->other.submit(()->f.transport.post(DEST,message())).get(2,TimeUnit.SECONDS));
                assertEquals("Chronicle transport unavailable",failure.getCause().getMessage());assertEquals(1,calls.get());
                release.countDown();assertEquals(204,first.get(2,TimeUnit.SECONDS).status());
                f.caller.submit(()->{f.transport.close();return null;}).get(4,TimeUnit.SECONDS);
                var closed=assertThrows(ExecutionException.class,f::post);
                assertEquals("Chronicle transport unavailable",closed.getCause().getMessage());assertEquals(1,calls.get());
            }finally{release.countDown();other.shutdownNow();assertTrue(other.awaitTermination(2,TimeUnit.SECONDS));}
        }
    }
    @Test void closeCancelsStalledExchangeButDoesNotPretendItWasDelivered() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try(var f=new Fixture(e->{e.getRequestBody().readAllBytes();e.sendResponseHeaders(200,10);e.getResponseBody().write(1);e.getResponseBody().flush();entered.countDown();
            try{release.await(6,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
        },Duration.ofSeconds(8))) {
            var closer=Executors.newSingleThreadExecutor();
            try {
                var first=f.caller.submit(()->f.transport.post(DEST,message()));assertTrue(entered.await(2,TimeUnit.SECONDS));
                closer.submit(()->{f.transport.close();return null;}).get(4,TimeUnit.SECONDS);
                var failure=assertThrows(ExecutionException.class,()->first.get(2,TimeUnit.SECONDS));
                assertEquals("Chronicle HTTP result unconfirmed",failure.getCause().getMessage());assertNull(failure.getCause().getCause());
            }finally{release.countDown();closer.shutdownNow();assertTrue(closer.awaitTermination(2,TimeUnit.SECONDS));}
        }
    }
}

package com.prosperitysmp.npc.publication;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class OccupancyHttpServerTest {
    @TempDir java.nio.file.Path root;
    static byte[] token() {byte[] value=new byte[32];new java.security.SecureRandom().nextBytes(value);return value;}
    static InetSocketAddress bind() throws Exception {return new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}),0);}
    static String bearer(byte[] token) {return "Bearer "+Base64.getUrlEncoder().withoutPadding().encodeToString(token);}
    static HttpRequest.Builder request(OccupancyHttpServer server,String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.address().getPort()+path)).timeout(Duration.ofSeconds(3));
    }
    static HttpResponse<String> send(HttpClient client,HttpRequest.Builder request) throws Exception {
        return client.send(request.build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void authorizationRoutingAndMethodsFailBeforeReadingState() throws Exception {
        byte[] token=token();var reads=new AtomicInteger();
        try(var server=new OccupancyHttpServer(bind(),token,()->{reads.incrementAndGet();return Optional.empty();});
            var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            var denied=send(client,request(server,"/v1/occupancy"));
            assertEquals(401,denied.statusCode());
            assertEquals("no-store",denied.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("Bearer",denied.headers().firstValue("WWW-Authenticate").orElseThrow());
            assertEquals("nosniff",denied.headers().firstValue("X-Content-Type-Options").orElseThrow());
            assertEquals(401,send(client,request(server,"/v1/occupancy").header("Authorization",bearer(token())).GET()).statusCode());
            assertEquals(401,send(client,request(server,"/v1/occupancy").header("Authorization",bearer(token)).header("Authorization",bearer(token))).statusCode());
            for(String path:List.of("/v1/occupancy/extra","/v1/occupancy?x=1","/v1/%6fccupancy","/other"))
                assertEquals(404,send(client,request(server,path).header("Authorization",bearer(token))).statusCode());
            for(String method:List.of("POST","HEAD","DELETE")) {
                var response=send(client,request(server,"/v1/occupancy").header("Authorization",bearer(token)).method(method,HttpRequest.BodyPublishers.noBody()));
                assertEquals(405,response.statusCode());assertEquals("GET",response.headers().firstValue("Allow").orElseThrow());
            }
            assertEquals(400,send(client,request(server,"/v1/occupancy").header("Authorization",bearer(token))
                    .method("GET",HttpRequest.BodyPublishers.ofString("body"))).statusCode());
            assertEquals(400,send(client,request(server,"/v1/occupancy").header("Authorization",bearer(token))
                    .method("GET",HttpRequest.BodyPublishers.ofInputStream(()->new java.io.ByteArrayInputStream(new byte[]{1})))).statusCode());
            assertEquals(0,reads.get());
        }
    }
    @Test void realResponsesContainOnlyFreshPublicProjectionAndNeverUseConditionalCache() throws Exception {
        byte[] token=token();String auth=bearer(token);var reads=new AtomicInteger();
        var value=new AtomicReference<>(Optional.of(OccupancyPublicationSessionTest.evidence(OccupancyPublicationSessionTest.NOW,true).snapshot()));
        try(var server=new OccupancyHttpServer(bind(),token,()->{reads.incrementAndGet();return value.get();});
            var client=HttpClient.newHttpClient()) {
            Arrays.fill(token,(byte)0); // caller changes cannot change the server's token
            var response=send(client,request(server,"/v1/occupancy").header("Authorization",auth));
            assertEquals(200,response.statusCode());assertEquals(value.get().orElseThrow().json(),response.body());
            assertFalse(response.body().contains(OccupancyPublicationSessionTest.PROPERTY.toString()));
            assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("nosniff",response.headers().firstValue("X-Content-Type-Options").orElseThrow());
            assertTrue(response.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
            value.set(Optional.empty());
            var revoked=send(client,request(server,"/v1/occupancy").header("Authorization",auth).header("If-None-Match","*"));
            assertEquals(503,revoked.statusCode());assertEquals("{\"error\":\"unavailable\"}",revoked.body());
            assertEquals(2,reads.get());
        }
    }
    @Test void failuresDoNotExposeExceptionContentsAndNonLoopbackIsRejected() throws Exception {
        byte[] token=token();
        assertThrows(IllegalArgumentException.class,()->new OccupancyHttpServer(new InetSocketAddress("0.0.0.0",0),token,Optional::empty));
        assertThrows(IllegalArgumentException.class,()->new OccupancyHttpServer(InetSocketAddress.createUnresolved("localhost",0),token,Optional::empty));
        assertThrows(IllegalArgumentException.class,()->new OccupancyHttpServer(bind(),new byte[31],Optional::empty));
        try(var server=new OccupancyHttpServer(bind(),token,()->{throw new IllegalStateException("PRIVATE TEST SENTINEL");});
            var client=HttpClient.newHttpClient()) {
            var response=send(client,request(server,"/v1/occupancy").header("Authorization",bearer(token)));
            assertEquals(503,response.statusCode());assertFalse(response.body().contains("SENTINEL"));
            assertEquals("no-store",response.headers().firstValue("Cache-Control").orElseThrow());
        }
    }
    @Test void actualHostExpiryRevocationAndShutdownNeverFallBackToDisk() throws Exception {
        byte[] token=token();var mono=new AtomicLong();
        var host=new OccupancyPublicationHost(root,s->OccupancyPublicationSessionTest.evidence(OccupancyPublicationSessionTest.NOW,true),
                java.time.Clock.fixed(OccupancyPublicationSessionTest.NOW,java.time.ZoneOffset.UTC),mono::get,
                Duration.ofSeconds(1),Duration.ofSeconds(10),Duration.ofHours(1),Duration.ofDays(1));
        try(var server=new OccupancyHttpServer(bind(),token,host::readPublic);var client=HttpClient.newHttpClient()) {
            assertEquals(503,send(client,request(server,"/v1/occupancy").header("Authorization",bearer(token))).statusCode());
            host.configure(OccupancyPublicationSessionTest.SCOPE);
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while(host.current().isEmpty() && System.nanoTime()<deadline){host.tick();Thread.sleep(1);}
            assertTrue(host.current().isPresent());
            assertEquals(200,send(client,request(server,"/v1/occupancy").header("Authorization",bearer(token))).statusCode());
            mono.set(10_000_000_000L);
            assertEquals(503,send(client,request(server,"/v1/occupancy").header("Authorization",bearer(token))).statusCode());
            mono.set(0); // No owner ticks: the HTTP reader itself must latch expiry.
            assertEquals(503,send(client,request(server,"/v1/occupancy").header("Authorization",bearer(token))).statusCode());
            assertTrue(java.nio.file.Files.exists(root.resolve(".selection.json")));
            host.configure(List.of());
            assertEquals(503,send(client,request(server,"/v1/occupancy").header("Authorization",bearer(token))).statusCode());
            host.shutdown().toCompletableFuture().get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(503,send(client,request(server,"/v1/occupancy").header("Authorization",bearer(token))).statusCode());
        } finally {host.shutdown().toCompletableFuture().get(5,java.util.concurrent.TimeUnit.SECONDS);}
    }
    @Test void stopClosesActiveExchangeAndReleasesListeningPort() throws Exception {
        byte[] token=token();var entered=new java.util.concurrent.CountDownLatch(1);
        var exited=new java.util.concurrent.CountDownLatch(1);
        try(var server=new OccupancyHttpServer(bind(),token,()->{
            entered.countDown();
            try {new java.util.concurrent.CountDownLatch(1).await();throw new AssertionError("unexpected release");}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();return Optional.empty();}
            finally {exited.countDown();}
        });var client=HttpClient.newHttpClient()) {
            var pending=client.sendAsync(request(server,"/v1/occupancy").header("Authorization",bearer(token)).build(),HttpResponse.BodyHandlers.ofString());
            assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));
            var address=server.address();server.close();
            assertTrue(exited.await(3,java.util.concurrent.TimeUnit.SECONDS));
            try {assertNotEquals(200,pending.get(3,java.util.concurrent.TimeUnit.SECONDS).statusCode());}
            catch(java.util.concurrent.ExecutionException failure){assertInstanceOf(java.io.IOException.class,failure.getCause());}
            try(var replacement=new OccupancyHttpServer(address,token,Optional::empty)) {
                assertEquals(address.getPort(),replacement.address().getPort());
            }
        }
    }
}

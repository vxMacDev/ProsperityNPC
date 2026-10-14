package com.prosperitysmp.npc.publication;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class SingleUseRequestBodyTest {
    private static final class Sink implements Flow.Subscriber<ByteBuffer> {
        final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        final CompletableFuture<Void> done=new CompletableFuture<>();
        final boolean cancel;
        Sink(){this(false);}Sink(boolean cancel){this.cancel=cancel;}
        public void onSubscribe(Flow.Subscription subscription){if(cancel){subscription.cancel();done.complete(null);}else subscription.request(Long.MAX_VALUE);}
        public void onNext(ByteBuffer b){byte[] part=new byte[b.remaining()];b.get(part);bytes.writeBytes(part);}
        public void onError(Throwable failure){done.completeExceptionally(failure);}
        public void onComplete(){done.complete(null);}
    }
    @Test void immutableFirstPayloadAndSecondSubscriptionFailsBeforeAnyBytes() throws Exception {
        byte[] bytes="payload".getBytes(StandardCharsets.UTF_8);var body=new SingleUseRequestBody(bytes);bytes[0]='X';
        var first=new Sink();body.subscribe(first);first.done.get(2,TimeUnit.SECONDS);
        assertEquals("payload",first.bytes.toString(StandardCharsets.UTF_8));assertEquals(7,body.contentLength());
        var second=new Sink();body.subscribe(second);
        assertEquals("Chronicle request body replay refused",assertThrows(ExecutionException.class,()->second.done.get(2,TimeUnit.SECONDS)).getCause().getMessage());
        assertEquals(0,second.bytes.size());
    }
    @Test void concurrentSubscribersCannotBothReceivePayload() throws Exception {
        var body=new SingleUseRequestBody(new byte[]{1,2,3});var first=new Sink();var second=new Sink();var start=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->{start.await();body.subscribe(first);return null;});
            var b=pool.submit(()->{start.await();body.subscribe(second);return null;});start.countDown();a.get();b.get();
        }
        assertEquals(3,first.bytes.size()+second.bytes.size());assertNotEquals(first.done.isCompletedExceptionally(),second.done.isCompletedExceptionally());
    }
    @Test void cancelledFirstSubscriptionStillConsumesTheAttempt(){
        var body=new SingleUseRequestBody(new byte[]{1});body.subscribe(new Sink(true));var second=new Sink();body.subscribe(second);
        assertTrue(second.done.isCompletedExceptionally());assertEquals(0,second.bytes.size());
    }
    @Test void realHttpClientCannotReuseTheBodyForSecondCompletedPost() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var complete=new AtomicInteger();
        var handlers=Executors.newCachedThreadPool(task->{var t=new Thread(task,"chronicle-http-fixture");t.setDaemon(true);return t;});server.setExecutor(handlers);
        server.createContext("/",exchange->{
            try(exchange) {
                byte[] received=exchange.getRequestBody().readNBytes(9);
                if(new String(received,StandardCharsets.UTF_8).equals("fixture"))complete.incrementAndGet();
                exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write("ok".getBytes(StandardCharsets.UTF_8));
            }catch(IOException ignored){/* Deliberate aborted second request. */}
        });server.start();
        try(var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
                .proxy(java.net.ProxySelector.of(null)).connectTimeout(Duration.ofSeconds(2)).build()) {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"))
                    .timeout(Duration.ofSeconds(2)).POST(new SingleUseRequestBody("fixture".getBytes(StandardCharsets.UTF_8))).build();
            assertEquals(200,client.send(request,HttpResponse.BodyHandlers.discarding()).statusCode());
            var failure=assertThrows(IOException.class,()->client.send(request,HttpResponse.BodyHandlers.discarding()));
            boolean replayRefused=false;
            for(Throwable cause=failure;cause!=null;cause=cause.getCause())
                replayRefused|="Chronicle request body replay refused".equals(cause.getMessage());
            assertTrue(replayRefused,"second request failed for an unrelated reason");assertEquals(1,complete.get());
        }finally{server.stop(0);handlers.shutdownNow();assertTrue(handlers.awaitTermination(3,TimeUnit.SECONDS));}
    }
}

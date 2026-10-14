package com.prosperitysmp.npc.publication;

import com.prosperitysmp.npc.publication.ChronicleWebhookProtocol.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fixed Discord HTTPS transport. Caller owns authorization and verified clean-start
 * HTTP diagnostic policy. This adapter emits no URI/body logs; the shared JDK may.
 * Not safe to supply credentials until that process-level policy is verified.
 */
public final class ChronicleHttpTransport implements ChronicleDispatcher.Transport,AutoCloseable {
    private final Thread owner=Thread.currentThread();
    private final Destination destination;
    private final URI endpoint;
    private final HttpClient client;
    private final Duration deadline;
    private final java.util.function.Consumer<Throwable> fixtureFailure;
    private final AtomicBoolean busy=new AtomicBoolean(),closed=new AtomicBoolean();

    public ChronicleHttpTransport(Destination destination,String token) {
        this(destination,discordEndpoint(destination,token),Duration.ofSeconds(8));
    }
    private ChronicleHttpTransport(Destination destination,URI endpoint,Duration deadline) {
        this(destination,endpoint,deadline,null,null);
    }
    private ChronicleHttpTransport(Destination destination,URI endpoint,Duration deadline,javax.net.ssl.SSLContext fixtureTls,java.util.function.Consumer<Throwable> fixtureFailure) {
        this.destination=Objects.requireNonNull(destination);this.endpoint=endpoint;this.deadline=deadline;
        this.fixtureFailure=fixtureFailure;
        // Additional rejection only: JDK logging can already be cached or configured in net.properties.
        if(!System.getProperty("jdk.httpclient.HttpClient.log","").isBlank())throw new IllegalStateException("HTTP diagnostics must be disabled");
        var tls=new javax.net.ssl.SSLParameters();tls.setEndpointIdentificationAlgorithm("HTTPS");
        var builder=HttpClient.newBuilder().sslParameters(tls).version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
                .proxy(ProxySelector.of(null)).connectTimeout(Duration.ofSeconds(3));
        if(fixtureTls!=null)builder.sslContext(fixtureTls);
        client=builder.build();
    }
    private static URI discordEndpoint(Destination destination,String token) {
        Objects.requireNonNull(destination);
        if(token==null || !token.matches("[A-Za-z0-9._-]{1,256}"))throw new IllegalArgumentException("Invalid Chronicle webhook credential");
        return URI.create("https://discord.com/api/v10/webhooks/"+destination.webhookId()+"/"+token+"?"+ChronicleWebhookProtocol.EXECUTE_QUERY);
    }
    /** Package-only fixture seam cannot target arbitrary hosts or carry a real Discord credential. */
    static ChronicleHttpTransport loopbackFixture(Destination destination,int port,Duration deadline) {
        if(port<1 || port>65535 || deadline.compareTo(Duration.ofMillis(100))<0 || deadline.compareTo(Duration.ofSeconds(8))>0)
            throw new IllegalArgumentException("Invalid fixture bounds");
        return new ChronicleHttpTransport(destination,URI.create("http://127.0.0.1:"+port+"/?wait=true"),deadline);
    }
    /** Test trust context is accepted only for this fixed loopback HTTPS address. */
    static ChronicleHttpTransport loopbackTlsFixture(Destination destination,int port,javax.net.ssl.SSLContext tls,java.util.function.Consumer<Throwable> failureObserver) {
        if(port<1 || port>65535)throw new IllegalArgumentException("Invalid fixture port");
        return new ChronicleHttpTransport(destination,URI.create("https://127.0.0.1:"+port+"/?wait=true"),Duration.ofSeconds(3),Objects.requireNonNull(tls),Objects.requireNonNull(failureObserver));
    }
    @Override public ChronicleDispatcher.Reply post(Destination target,Message message) throws IOException,InterruptedException {
        offOwner();Objects.requireNonNull(message);
        if(!destination.equals(target))throw new IOException("Chronicle destination mismatch");
        if(closed.get() || !busy.compareAndSet(false,true))throw new IOException("Chronicle transport unavailable");
        CompletableFuture<HttpResponse<byte[]>> future=null;
        try {
            if(closed.get())throw new IOException("Chronicle transport closed");
            var request=HttpRequest.newBuilder(endpoint).timeout(deadline).header("Content-Type","application/json")
                    .header("Accept","application/json").header("User-Agent","ProsperityNPC-Chronicle/1")
                    .POST(new SingleUseRequestBody(message.json().getBytes(StandardCharsets.UTF_8))).build();
            future=client.sendAsync(request,info->new BoundedBody());
            var response=future.get(deadline.toMillis(),TimeUnit.MILLISECONDS);
            return new ChronicleDispatcher.Reply(response.statusCode(),response.body());
        }catch(InterruptedException interrupted){if(future!=null)future.cancel(true);throw interrupted;}
        catch(ExecutionException | TimeoutException | RuntimeException failure){
            if(future!=null)future.cancel(true);
            if(fixtureFailure!=null)fixtureFailure.accept(failure);
            throw new IOException("Chronicle HTTP result unconfirmed");
        }finally{busy.set(false);}
    }
    /** Worker-only HTTP shutdown. Host must ALSO drain dispatch/receipt writes before closing the database. */
    @Override public void close() throws IOException,InterruptedException {
        offOwner();closed.set(true);client.shutdownNow();
        if(!client.awaitTermination(Duration.ofSeconds(3)))throw new IOException("Chronicle HTTP shutdown incomplete");
    }
    private void offOwner(){if(Thread.currentThread()==owner)throw new IllegalStateException("Chronicle HTTP must run off owner");}

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> done=new CompletableFuture<>();
        private final byte[] bytes=new byte[32768];private int used;
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return done;}
        public void onSubscribe(Flow.Subscription s){if(subscription!=null){s.cancel();return;}subscription=s;s.request(1);}
        public void onNext(List<ByteBuffer> buffers) {
            if(done.isDone())return;
            for(ByteBuffer buffer:buffers) {
                int n=buffer.remaining();if(n>bytes.length-used){subscription.cancel();done.completeExceptionally(new IOException("Chronicle response too large"));return;}
                buffer.get(bytes,used,n);used+=n;
            }
            subscription.request(1);
        }
        public void onError(Throwable failure){done.completeExceptionally(new IOException("Chronicle body unconfirmed"));}
        public void onComplete(){done.complete(Arrays.copyOf(bytes,used));}
    }
}

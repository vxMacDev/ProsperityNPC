package com.prosperitysmp.npc.publication;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

class ChronicleAutomaticRetryTest {
    @Test @Timeout(30) void lostResponseActuallyRetriesControlButCannotReplayGuardedBody() throws Exception {
        assertEquals("bodies=2 subscriptions=2 success=true replayRefused=false",child("control"));
        assertEquals("bodies=1 subscriptions=2 success=false replayRefused=true",child("guard"));
    }
    private static String child(String mode) throws Exception {
        String executable=System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        String cp=String.join(File.pathSeparator,List.of(location(Child.class),location(SingleUseRequestBody.class)));
        var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",executable).toString(),
                "-Djdk.httpclient.enableAllMethodRetry=true","-cp",cp,Child.class.getName(),mode).redirectErrorStream(true).start();
        try {
            assertTrue(process.waitFor(10,TimeUnit.SECONDS),"HTTP retry child timed out");
            String output=new String(process.getInputStream().readNBytes(8193),StandardCharsets.UTF_8).trim();
            assertTrue(output.length()<=8192);assertEquals(0,process.exitValue(),output);return output;
        }finally{if(process.isAlive()){process.destroyForcibly();assertTrue(process.waitFor(3,TimeUnit.SECONDS));}}
    }
    private static String location(Class<?> c) throws Exception {return Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();}

    /** Fresh JVM sets retry policy before JDK HTTP classes initialize. Local HTTP only, no real credentials. */
    public static final class Child {
        public static void main(String[] args) throws Exception {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            var handlers=Executors.newCachedThreadPool(r->{var t=new Thread(r,"retry-fixture");t.setDaemon(true);return t;});server.setExecutor(handlers);
            var bodies=new AtomicInteger();var subscriptions=new AtomicInteger();
            server.createContext("/",e->{
                try(e) {
                    if(e.getRequestMethod().equals("POST")) {
                        byte[] body=e.getRequestBody().readNBytes(8);
                        if(!new String(body,StandardCharsets.UTF_8).equals("fixture"))return;
                        if(bodies.incrementAndGet()==1)return; // Consume the request, then lose its entire response.
                    }
                    e.sendResponseHeaders(200,2);e.getResponseBody().write(new byte[]{'o','k'});
                }catch(IOException ignored){/* The guarded retried request can abort before its body. */}
            });server.start();
            try(var client=HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
                    .proxy(ProxySelector.of(null)).connectTimeout(Duration.ofSeconds(2)).build()) {
                URI uri=URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/");
                if(client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()!=200)
                    throw new IllegalStateException("Warmup failed");
                byte[] bytes="fixture".getBytes(StandardCharsets.UTF_8);
                HttpRequest.BodyPublisher delegate=args[0].equals("guard") ? new SingleUseRequestBody(bytes) : HttpRequest.BodyPublishers.ofByteArray(bytes);
                HttpRequest.BodyPublisher observed=new HttpRequest.BodyPublisher() {
                    public long contentLength(){return delegate.contentLength();}
                    public void subscribe(Flow.Subscriber<? super ByteBuffer> s){subscriptions.incrementAndGet();delegate.subscribe(s);}
                };
                boolean success=false,replayRefused=false;
                try {success=client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(3)).POST(observed).build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200;}
                catch(IOException failure){for(Throwable t=failure;t!=null;t=t.getCause())replayRefused|="Chronicle request body replay refused".equals(t.getMessage());}
                System.out.println("bodies="+bodies.get()+" subscriptions="+subscriptions.get()+" success="+success+" replayRefused="+replayRefused);
            }finally{server.stop(0);handlers.shutdownNow();if(!handlers.awaitTermination(3,TimeUnit.SECONDS))throw new IllegalStateException("Fixture handlers stuck");}
        }
    }
}

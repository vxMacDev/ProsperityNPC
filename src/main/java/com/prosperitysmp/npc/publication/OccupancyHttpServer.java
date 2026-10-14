package com.prosperitysmp.npc.publication;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/**
 * Private loopback transport, never a public listener. Start/stop off the Minecraft owner thread.
 * Deployment must supply a private 32-byte token and proxy request/time/rate limits. No file lookup,
 * database query or Bukkit scheduling belongs in the supplied thread-safe current-view reader.
 */
public final class OccupancyHttpServer implements AutoCloseable {
    private final HttpServer server;
    private final ThreadPoolExecutor workers;
    private final byte[] authorization;
    private final Supplier<Optional<PublicOccupancySnapshot>> current;

    public OccupancyHttpServer(InetSocketAddress bind, byte[] token,
            Supplier<Optional<PublicOccupancySnapshot>> current) throws IOException {
        Objects.requireNonNull(bind);
        Objects.requireNonNull(token);
        this.current = Objects.requireNonNull(current);
        if (bind.isUnresolved() || !bind.getAddress().isLoopbackAddress())
            throw new IllegalArgumentException("Occupancy listener must bind a resolved loopback address");
        if (token.length != 32) throw new IllegalArgumentException("Occupancy transport requires a 32-byte token");
        authorization = ("Bearer " + Base64.getUrlEncoder().withoutPadding().encodeToString(token)).getBytes(StandardCharsets.US_ASCII);
        workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8), task -> {
            Thread t = new Thread(task, "prosperity-npc-occupancy-http");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());
        HttpServer opened = null;
        try {
            opened = HttpServer.create(bind, 8);
            opened.setExecutor(workers);
            opened.createContext("/", this::handle);
            opened.start();
            server = opened;
        } catch (IOException | RuntimeException | Error failure) {
            if (opened != null) opened.stop(0);
            workers.shutdownNow();
            throw failure;
        }
    }

    public InetSocketAddress address() { return server.getAddress(); }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            var headers = exchange.getResponseHeaders();
            headers.set("Cache-Control", "no-store");
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("Content-Type", "application/json; charset=utf-8");
            if (!authorized(exchange)) {
                headers.set("WWW-Authenticate", "Bearer");
                reply(exchange, 401, "{\"error\":\"unauthorized\"}");
                return;
            }
            if (!exchange.getRequestURI().getRawPath().equals("/v1/occupancy") || exchange.getRequestURI().getRawQuery() != null) {
                reply(exchange, 404, "{\"error\":\"not_found\"}");
                return;
            }
            if (!exchange.getRequestMethod().equals("GET")) {
                headers.set("Allow", "GET");
                reply(exchange, 405, "{\"error\":\"method_not_allowed\"}");
                return;
            }
            // Reject bodies without reading them: this transport has no input or mutation endpoint.
            var lengths = exchange.getRequestHeaders().get("Content-Length");
            if (exchange.getRequestHeaders().containsKey("Transfer-Encoding")
                    || (lengths != null && (lengths.size() != 1 || !lengths.getFirst().equals("0")))) {
                reply(exchange, 400, "{\"error\":\"body_not_allowed\"}");
                return;
            }
            Optional<PublicOccupancySnapshot> snapshot;
            try { snapshot = Objects.requireNonNull(current.get()); }
            catch (RuntimeException failure) {
                reply(exchange, 503, "{\"error\":\"unavailable\"}");
                return;
            }
            if (snapshot.isEmpty()) {
                reply(exchange, 503, "{\"error\":\"unavailable\"}");
                return;
            }
            reply(exchange, 200, snapshot.orElseThrow().json());
        }
    }

    private boolean authorized(HttpExchange exchange) {
        List<String> values = exchange.getRequestHeaders().get("Authorization");
        if (values == null || values.size() != 1 || values.getFirst().length() != authorization.length) return false;
        String value = values.getFirst();
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) > 127) return false;
        return MessageDigest.isEqual(authorization, value.getBytes(StandardCharsets.US_ASCII));
    }
    private static void reply(HttpExchange exchange, int code, String json) throws IOException {
        if (exchange.getRequestMethod().equals("HEAD")) {
            exchange.sendResponseHeaders(code, -1);
            return;
        }
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 128 * 1024) throw new IOException("Occupancy response exceeds bound");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
    @Override public void close() {
        server.stop(0);
        workers.shutdownNow();
    }
}

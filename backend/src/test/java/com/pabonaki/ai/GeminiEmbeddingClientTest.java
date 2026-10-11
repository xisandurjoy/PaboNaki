package com.pabonaki.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Runs against a local fake of the Gemini endpoint, so it needs no credentials and no network. */
class GeminiEmbeddingClientTest {
    private HttpServer server;

    @AfterEach void stop() { if (server != null) server.stop(0); }

    private AiSettings settings(String key) {
        AiSettings s = new AiSettings();
        s.apiKey = key; s.dimensions = 3; s.maxRetries = 1; s.timeoutMs = 2000;
        s.embeddingBaseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta";
        return s;
    }

    private void serve(AtomicInteger calls, AtomicReference<String> key, AtomicReference<String> body, int firstStatus) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            int n = calls.incrementAndGet();
            key.set(ex.getRequestHeaders().getFirst("x-goog-api-key"));
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] out = "{\"embedding\":{\"values\":[3,4,0]}}".getBytes(StandardCharsets.UTF_8);
            int status = n == 1 ? firstStatus : 200;
            ex.sendResponseHeaders(status, status == 200 ? out.length : -1);
            if (status == 200) ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @Test void sendsKeyInHeaderNotUrlAndReturnsUnitVector() throws Exception {
        AtomicInteger calls = new AtomicInteger(); AtomicReference<String> key = new AtomicReference<>(), body = new AtomicReference<>();
        serve(calls, key, body, 200);
        double[] v = new GeminiEmbeddingClient(settings("secret-key"), new ObjectMapper()).embedQuery("black shoes");
        assertEquals("secret-key", key.get());
        assertTrue(body.get().contains("task: search result | query: black shoes"));
        assertTrue(body.get().contains("gemini-embedding-2"));
        assertEquals(0.6, v[0], 1e-9);
        assertEquals(0.8, v[1], 1e-9);
    }

    @Test void retriesOnceOnRateLimit() throws Exception {
        AtomicInteger calls = new AtomicInteger(); AtomicReference<String> key = new AtomicReference<>(), body = new AtomicReference<>();
        serve(calls, key, body, 429);
        double[] v = new GeminiEmbeddingClient(settings("k"), new ObjectMapper()).embedDocument("Mug", "Ceramic mug");
        assertEquals(2, calls.get());
        assertEquals(3, v.length);
    }

    @Test void providerOutageSurfacesAsTransientException() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> { ex.sendResponseHeaders(503, -1); ex.close(); });
        server.start();
        EmbeddingException e = assertThrows(EmbeddingException.class, () -> new GeminiEmbeddingClient(settings("k"), new ObjectMapper()).embedQuery("x"));
        assertTrue(e.isTransient());
    }

    @Test void imageRequestUsesInlineData() throws Exception {
        AtomicInteger calls = new AtomicInteger(); AtomicReference<String> key = new AtomicReference<>(), body = new AtomicReference<>();
        serve(calls, key, body, 200);
        new GeminiEmbeddingClient(settings("k"), new ObjectMapper()).embedImage(new byte[]{1, 2, 3}, "image/jpeg");
        assertTrue(body.get().contains("inline_data"));
        assertTrue(body.get().contains("image/jpeg"));
        assertFalse(body.get().contains("task:"));
    }

    @Test void notConfiguredWithoutKeyAndBudgetIsEnforced() throws Exception {
        serve(new AtomicInteger(), new AtomicReference<>(), new AtomicReference<>(), 200);
        GeminiEmbeddingClient none = new GeminiEmbeddingClient(settings(""), new ObjectMapper());
        assertFalse(none.isAvailable());
        assertEquals(EmbeddingException.Kind.NOT_CONFIGURED, assertThrows(EmbeddingException.class, () -> none.embedQuery("x")).kind());

        AiSettings s = settings("k"); s.dailyBudget = 1;
        GeminiEmbeddingClient capped = new GeminiEmbeddingClient(s, new ObjectMapper());
        capped.embedQuery("one");
        assertEquals(EmbeddingException.Kind.BUDGET_EXCEEDED, assertThrows(EmbeddingException.class, () -> capped.embedQuery("two")).kind());
    }

    @Test void wrongDimensionsAreRejected() throws Exception {
        serve(new AtomicInteger(), new AtomicReference<>(), new AtomicReference<>(), 200);
        AiSettings s = settings("k"); s.dimensions = 5;
        assertThrows(EmbeddingException.class, () -> new GeminiEmbeddingClient(s, new ObjectMapper()).embedQuery("x"));
    }
}

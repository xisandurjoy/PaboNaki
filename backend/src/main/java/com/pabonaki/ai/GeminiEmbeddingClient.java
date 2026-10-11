package com.pabonaki.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Calls the Gemini API {@code embedContent} method (model gemini-embedding-2: text and images share one vector space).
 * The API key is sent in the {@code x-goog-api-key} header, never in the URL, and is never logged.
 * Request bodies (user text / image bytes) are never logged either.
 */
public class GeminiEmbeddingClient implements EmbeddingProvider {
    private final AiSettings cfg;
    private final ObjectMapper json;
    private final HttpClient http;
    private final AtomicInteger used = new AtomicInteger();
    private volatile LocalDate usedDay = LocalDate.now(ZoneOffset.UTC);

    public GeminiEmbeddingClient(AiSettings cfg, ObjectMapper json) {
        this(cfg, json, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    public GeminiEmbeddingClient(AiSettings cfg, ObjectMapper json, HttpClient http) {
        this.cfg = cfg; this.json = json; this.http = http;
    }

    @Override public boolean isAvailable() { return cfg.enabled && cfg.apiKey != null && !cfg.apiKey.isBlank(); }

    @Override public String fingerprint() { return cfg.embeddingModel + ":" + cfg.dimensions; }

    @Override public double[] embedQuery(String text) {
        return embed(List.of(Map.of("text", "task: search result | query: " + text)));
    }

    @Override public double[] embedDocument(String title, String text) {
        String t = title == null || title.isBlank() ? "none" : title;
        return embed(List.of(Map.of("text", "title: " + t + " | text: " + text)));
    }

    @Override public double[] embedImage(byte[] bytes, String mimeType) {
        // The multimodal docs advise against task prefixes for image input.
        Map<String, Object> inline = new LinkedHashMap<>();
        inline.put("mime_type", mimeType);
        inline.put("data", Base64.getEncoder().encodeToString(bytes));
        return embed(List.of(Map.of("inline_data", inline)));
    }

    private void spendBudget() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (!today.equals(usedDay)) { synchronized (this) { if (!today.equals(usedDay)) { usedDay = today; used.set(0); } } }
        if (cfg.dailyBudget > 0 && used.incrementAndGet() > cfg.dailyBudget)
            throw new EmbeddingException(EmbeddingException.Kind.BUDGET_EXCEEDED, "Daily AI embedding budget reached. It resets at 00:00 UTC.");
    }

    /** Calls used so far today (UTC). */
    @Override public int usedToday() { return used.get(); }

    double[] embed(List<Map<String, Object>> parts) {
        if (!isAvailable()) throw new EmbeddingException(EmbeddingException.Kind.NOT_CONFIGURED, "AI embeddings are not configured. Set GEMINI_API_KEY.");
        spendBudget();
        String body;
        try {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("model", "models/" + cfg.embeddingModel);
            req.put("content", Map.of("parts", parts));
            req.put("output_dimensionality", cfg.dimensions);
            body = json.writeValueAsString(req);
        } catch (IOException e) {
            throw new EmbeddingException(EmbeddingException.Kind.BAD_INPUT, "Could not build embedding request.", e);
        }
        URI uri = URI.create(cfg.embeddingBaseUrl + "/models/" + cfg.embeddingModel + ":embedContent");
        int attempts = Math.max(0, cfg.maxRetries) + 1;
        EmbeddingException last = null;
        for (int i = 0; i < attempts; i++) {
            if (i > 0) sleep(400L * (1L << (i - 1)));
            try {
                HttpRequest r = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(cfg.timeoutMs))
                        .header("Content-Type", "application/json").header("x-goog-api-key", cfg.apiKey)
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                HttpResponse<String> res = http.send(r, HttpResponse.BodyHandlers.ofString());
                int s = res.statusCode();
                if (s == 200) return parse(res.body());
                if (s == 429) last = new EmbeddingException(EmbeddingException.Kind.RATE_LIMITED, "The AI provider is rate-limiting requests. Please try again shortly.");
                else if (s >= 500) last = new EmbeddingException(EmbeddingException.Kind.PROVIDER_ERROR, "The AI provider is temporarily unavailable (HTTP " + s + ").");
                else if (s == 400) throw new EmbeddingException(EmbeddingException.Kind.BAD_INPUT, "The AI provider rejected this input (HTTP 400).");
                else if (s == 401 || s == 403) throw new EmbeddingException(EmbeddingException.Kind.NOT_CONFIGURED, "The AI provider rejected the API key (HTTP " + s + "). Check GEMINI_API_KEY and that the Generative Language API is enabled.");
                else throw new EmbeddingException(EmbeddingException.Kind.PROVIDER_ERROR, "Unexpected AI provider response (HTTP " + s + ").");
            } catch (HttpTimeoutException e) {
                last = new EmbeddingException(EmbeddingException.Kind.TIMEOUT, "The AI provider timed out.", e);
            } catch (IOException e) {
                last = new EmbeddingException(EmbeddingException.Kind.PROVIDER_ERROR, "Could not reach the AI provider.", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new EmbeddingException(EmbeddingException.Kind.PROVIDER_ERROR, "Interrupted while calling the AI provider.", e);
            }
        }
        throw last;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** Accepts both {"embedding":{"values":[..]}} and {"embeddings":[{"values":[..]}]} response shapes. */
    double[] parse(String body) {
        try {
            JsonNode root = json.readTree(body);
            JsonNode values = root.path("embedding").path("values");
            if (!values.isArray()) values = root.path("embeddings").path(0).path("values");
            if (!values.isArray() || values.isEmpty()) throw new EmbeddingException(EmbeddingException.Kind.PROVIDER_ERROR, "The AI provider returned no embedding.");
            double[] v = new double[values.size()];
            for (int i = 0; i < v.length; i++) v[i] = values.get(i).asDouble();
            if (v.length != cfg.dimensions)
                throw new EmbeddingException(EmbeddingException.Kind.PROVIDER_ERROR, "Embedding has " + v.length + " dimensions, expected " + cfg.dimensions + ".");
            double[] n = VectorMath.normalize(v);
            if (n == null) throw new EmbeddingException(EmbeddingException.Kind.PROVIDER_ERROR, "The AI provider returned an empty embedding.");
            return n;
        } catch (IOException e) {
            throw new EmbeddingException(EmbeddingException.Kind.PROVIDER_ERROR, "Could not read the AI provider response.", e);
        }
    }
}

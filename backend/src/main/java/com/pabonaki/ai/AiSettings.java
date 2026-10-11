package com.pabonaki.ai;

/** All tunables for the AI features, bound from application.properties / environment variables in {@link AiConfig}. */
public class AiSettings {
    public boolean enabled = true;
    public String apiKey = "";
    public String embeddingModel = "gemini-embedding-2";
    public String embeddingBaseUrl = "https://generativelanguage.googleapis.com/v1beta";
    public int dimensions = 768;
    public int timeoutMs = 15000;
    public int maxRetries = 2;
    public int dailyBudget = 2000;

    public long visualMaxBytes = 4L * 1024 * 1024;
    public double visualMinScore = 0.35;
    public int visualLimit = 12;

    public boolean indexAuto = true;
    public int indexBatch = 20;
    public long imageMaxBytes = 5L * 1024 * 1024;

    public String vectorMode = "auto";               // auto | atlas | local
    public String textIndexName = "product_text_vector_index";
    public String imageIndexName = "product_image_vector_index";
    public int localMaxDocs = 5000;

    public int visualPerMinute = 10;
    public int semanticPerMinute = 30;
    public int activityPerMinute = 120;
    public int recommendPerMinute = 120;

    public String publicBaseUrl = "http://localhost:8080";
}

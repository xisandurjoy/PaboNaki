package com.pabonaki.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pabonaki.ai.reco.PopularityService;
import com.pabonaki.ai.reco.RecommendationService;
import com.pabonaki.repo.OrderRepository;
import com.pabonaki.repo.ProductRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Wires the AI search and recommendation beans. Everything is configurable from application.properties / environment variables. */
@Configuration
@EnableScheduling
public class AiConfig {

    @Bean
    AiSettings aiSettings(
            @Value("${app.ai.enabled:true}") boolean enabled,
            @Value("${app.integrations.gemini-key:}") String apiKey,
            @Value("${app.ai.embedding-model:gemini-embedding-2}") String model,
            @Value("${app.ai.embedding-base-url:https://generativelanguage.googleapis.com/v1beta}") String baseUrl,
            @Value("${app.ai.embedding-dimensions:768}") int dimensions,
            @Value("${app.ai.timeout-ms:15000}") int timeoutMs,
            @Value("${app.ai.max-retries:2}") int maxRetries,
            @Value("${app.ai.daily-embedding-budget:2000}") int dailyBudget,
            @Value("${app.ai.visual.max-bytes:4194304}") long visualMaxBytes,
            @Value("${app.ai.visual.min-score:0.35}") double visualMinScore,
            @Value("${app.ai.visual.limit:12}") int visualLimit,
            @Value("${app.ai.index.auto:true}") boolean indexAuto,
            @Value("${app.ai.index.batch:20}") int indexBatch,
            @Value("${app.ai.index.image-max-bytes:5242880}") long imageMaxBytes,
            @Value("${app.ai.vector.mode:auto}") String vectorMode,
            @Value("${app.ai.vector.text-index:product_text_vector_index}") String textIndex,
            @Value("${app.ai.vector.image-index:product_image_vector_index}") String imageIndex,
            @Value("${app.ai.vector.local-max-docs:5000}") int localMaxDocs,
            @Value("${app.ai.rate.visual-per-minute:10}") int visualPerMinute,
            @Value("${app.ai.rate.semantic-per-minute:30}") int semanticPerMinute,
            @Value("${app.ai.rate.activity-per-minute:120}") int activityPerMinute,
            @Value("${app.ai.rate.recommend-per-minute:120}") int recommendPerMinute,
            @Value("${app.public-base-url:http://localhost:8080}") String publicBaseUrl) {
        AiSettings s = new AiSettings();
        s.enabled = enabled; s.apiKey = apiKey == null ? "" : apiKey.trim(); s.embeddingModel = model; s.embeddingBaseUrl = baseUrl;
        s.dimensions = dimensions; s.timeoutMs = timeoutMs; s.maxRetries = maxRetries; s.dailyBudget = dailyBudget;
        s.visualMaxBytes = visualMaxBytes; s.visualMinScore = visualMinScore; s.visualLimit = visualLimit;
        s.indexAuto = indexAuto; s.indexBatch = indexBatch; s.imageMaxBytes = imageMaxBytes;
        s.vectorMode = vectorMode; s.textIndexName = textIndex; s.imageIndexName = imageIndex; s.localMaxDocs = localMaxDocs;
        s.visualPerMinute = visualPerMinute; s.semanticPerMinute = semanticPerMinute; s.activityPerMinute = activityPerMinute; s.recommendPerMinute = recommendPerMinute;
        s.publicBaseUrl = publicBaseUrl;
        return s;
    }

    @Bean RateLimiter aiRateLimiter() { return new RateLimiter(); }
    @Bean EmbeddingProvider embeddingProvider(AiSettings s, ObjectMapper om) { return new GeminiEmbeddingClient(s, om); }
    @Bean ImageFetcher imageFetcher(AiSettings s) { return new ImageFetcher(s.imageMaxBytes); }
    @Bean VectorSearcher vectorSearcher(MongoTemplate mongo, AiSettings s) { return new VectorSearcher(mongo, s); }

    @Bean ProductIndexer productIndexer(ProductRepository products, MongoTemplate mongo, EmbeddingProvider emb, ImageFetcher fetcher, VectorSearcher searcher, AiSettings s) {
        return new ProductIndexer(products, mongo, emb, fetcher, searcher, s);
    }

    @Bean SemanticSearchService semanticSearchService(ProductRepository products, VectorSearcher searcher, EmbeddingProvider emb, ObjectMapper om) {
        return new SemanticSearchService(products, searcher, emb, om);
    }

    @Bean VisualSearchService visualSearchService(ProductRepository products, VectorSearcher searcher, EmbeddingProvider emb, MongoTemplate mongo, AiSettings s, ObjectMapper om) {
        return new VisualSearchService(products, searcher, emb, mongo, s, om);
    }

    @Bean PopularityService popularityService(MongoTemplate mongo) { return new PopularityService(mongo); }

    @Bean RecommendationService recommendationService(ProductRepository products, OrderRepository orders, MongoTemplate mongo, VectorSearcher searcher, PopularityService pop) {
        return new RecommendationService(products, orders, mongo, searcher, pop);
    }
}

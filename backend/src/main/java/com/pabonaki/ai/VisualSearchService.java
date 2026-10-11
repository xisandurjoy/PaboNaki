package com.pabonaki.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pabonaki.model.Product;
import com.pabonaki.repo.ProductRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.*;

/** Embeds an uploaded photo and returns the visually closest in-stock products from the real catalogue. */
public class VisualSearchService {
    private final ProductRepository products;
    private final VectorSearcher searcher;
    private final EmbeddingProvider emb;
    private final MongoTemplate mongo;
    private final AiSettings cfg;
    private final ObjectMapper om;

    public VisualSearchService(ProductRepository products, VectorSearcher searcher, EmbeddingProvider emb, MongoTemplate mongo, AiSettings cfg, ObjectMapper om) {
        this.products = products; this.searcher = searcher; this.emb = emb; this.mongo = mongo; this.cfg = cfg; this.om = om;
    }

    /** @throws IllegalArgumentException for unacceptable uploads; @throws EmbeddingException for provider problems. */
    public Map<String, Object> search(byte[] bytes) {
        String mime = ImageSupport.validate(bytes, cfg.visualMaxBytes);
        if (!emb.isAvailable()) throw new EmbeddingException(EmbeddingException.Kind.NOT_CONFIGURED, "Visual search is not available right now.");

        long indexed = mongo.count(Query.query(Criteria.where("active").is(true).and("imageVector").exists(true)), ProductEmbedding.class);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("indexedProducts", indexed);
        if (indexed == 0) {
            out.put("results", List.of()); out.put("count", 0); out.put("indexReady", false);
            out.put("message", "Visual search is still being prepared: no product images have been indexed yet. Please try again later.");
            return out;
        }

        double[] qv = emb.embedImage(bytes, mime);
        VectorSearcher.Outcome o = searcher.search(VectorSearcher.Field.IMAGE, qv, Math.max(cfg.visualLimit * 3, 24));
        List<VectorSearcher.Hit> hits = o.hits().stream().filter(h -> h.score() >= cfg.visualMinScore).toList();
        Map<String, Product> live = new HashMap<>();
        for (Product p : products.findAllById(hits.stream().map(VectorSearcher.Hit::id).toList())) live.put(p.getId(), p);

        List<Map<String, Object>> results = new ArrayList<>();
        for (VectorSearcher.Hit h : hits) {
            Product p = live.get(h.id());
            if (p == null || !"ACTIVE".equals(p.getStatus()) || p.getStock() <= 0) continue;   // never offer unavailable items
            Map<String, Object> m = om.convertValue(p, new TypeReference<LinkedHashMap<String, Object>>() {});
            m.put("score", Math.round(h.score() * 1000.0) / 1000.0);
            m.put("similarity", (int) Math.round(Math.max(0, Math.min(1, h.score())) * 100));
            results.add(m);
            if (results.size() >= cfg.visualLimit) break;
        }
        out.put("results", results); out.put("count", results.size()); out.put("indexReady", true);
        out.put("engine", o.engine()); out.put("minScore", cfg.visualMinScore);
        return out;
    }
}

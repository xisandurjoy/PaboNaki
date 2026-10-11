package com.pabonaki.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pabonaki.model.Product;
import com.pabonaki.repo.ProductRepository;

import java.util.*;

/**
 * Hybrid product search: keyword relevance + semantic (embedding) similarity, fused with Reciprocal Rank Fusion
 * (no score calibration needed), then constrained by category, price and availability from the live product records.
 * If embeddings are unavailable the result is still useful (keyword-only, flagged in the response).
 */
public class SemanticSearchService {
    public record Request(String q, String category, Double minPrice, Double maxPrice, boolean inStockOnly, String sort, int page, int size) {}

    static final int RRF_K = 60;
    /** Semantic hits further than this below the best hit are treated as noise. Tunable. */
    static final double RELATIVE_CUTOFF = 0.12;
    static final int SEMANTIC_CANDIDATES = 60;
    static final int SEMANTIC_KEEP = 40;

    private record Cached(double[] vector, long at) {}

    private final ProductRepository products;
    private final VectorSearcher searcher;
    private final EmbeddingProvider emb;
    private final ObjectMapper om;
    private final Map<String, Cached> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Cached> e) { return size() > 300; }
    };

    public SemanticSearchService(ProductRepository products, VectorSearcher searcher, EmbeddingProvider emb, ObjectMapper om) {
        this.products = products; this.searcher = searcher; this.emb = emb; this.om = om;
    }

    private double[] queryVector(String text) {
        String key = text.toLowerCase(Locale.ROOT);
        synchronized (cache) {
            Cached c = cache.get(key);
            if (c != null && System.currentTimeMillis() - c.at() < 3_600_000L) return c.vector();
        }
        double[] v = emb.embedQuery(text);
        synchronized (cache) { cache.put(key, new Cached(v, System.currentTimeMillis())); }
        return v;
    }

    /** 0..1: how well the product matches the query words (name counts most, then category, then description). */
    static double keywordScore(Product p, List<String> tokens) {
        if (tokens.isEmpty()) return 0;
        String name = Objects.toString(p.getName(), "").toLowerCase(Locale.ROOT);
        String cat = Objects.toString(p.getCategory(), "").toLowerCase(Locale.ROOT);
        String desc = Objects.toString(p.getDescription(), "").toLowerCase(Locale.ROOT);
        double sum = 0;
        for (String t : tokens) sum += name.contains(t) ? 1.0 : cat.contains(t) ? 0.7 : desc.contains(t) ? 0.4 : 0;
        return sum / tokens.size();
    }

    static double finalPrice(Product p) { return p.getFinalPrice(); }

    public Map<String, Object> search(Request r) {
        QueryParser.Parsed parsed = QueryParser.parse(r.q());
        if (parsed.text().length() < 2) throw new IllegalArgumentException("Type at least 2 characters to search.");
        Double min = r.minPrice() != null ? r.minPrice() : parsed.minPrice();
        Double max = r.maxPrice() != null ? r.maxPrice() : parsed.maxPrice();
        String cat = r.category() == null ? "" : r.category().trim();

        // Source of truth: live ACTIVE products, filtered by the same business rules the storefront uses.
        List<Product> pool = new ArrayList<>();
        for (Product p : products.findByStatusOrderByCreatedAtDesc("ACTIVE")) {
            if (!cat.isEmpty() && !cat.equalsIgnoreCase(Objects.toString(p.getCategory(), "").trim())) continue;
            double price = finalPrice(p);
            if (min != null && price < min) continue;
            if (max != null && price > max) continue;
            if (r.inStockOnly() && p.getStock() <= 0) continue;
            pool.add(p);
        }
        Map<String, Product> byId = new HashMap<>();
        for (Product p : pool) byId.put(p.getId(), p);

        // 1) keyword ranking
        List<Map.Entry<Product, Double>> kw = new ArrayList<>();
        for (Product p : pool) { double s = keywordScore(p, parsed.tokens()); if (s > 0) kw.add(Map.entry(p, s)); }
        kw.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        // 2) semantic ranking
        List<VectorSearcher.Hit> sem = List.of();
        String mode = "hybrid", notice = null, engine = null;
        if (!emb.isAvailable()) {
            mode = "keyword"; notice = "Smart search is not set up yet, so these are keyword matches.";
        } else {
            try {
                VectorSearcher.Outcome o = searcher.search(VectorSearcher.Field.TEXT, queryVector(parsed.text()), SEMANTIC_CANDIDATES);
                engine = o.engine();
                List<VectorSearcher.Hit> inPool = o.hits().stream().filter(h -> byId.containsKey(h.id())).toList();
                if (!inPool.isEmpty()) {
                    double top = inPool.get(0).score();
                    sem = inPool.stream().filter(h -> h.score() >= top - RELATIVE_CUTOFF).limit(SEMANTIC_KEEP).toList();
                }
                if (o.hits().isEmpty()) { mode = "keyword"; notice = "Smart search index is still being built, so these are keyword matches."; }
            } catch (EmbeddingException ex) {
                mode = "keyword"; notice = "Smart search is temporarily unavailable, so these are keyword matches.";
            }
        }

        // 3) reciprocal rank fusion
        Map<String, Double> fused = new HashMap<>();
        Map<String, Set<String>> by = new HashMap<>();
        for (int i = 0; i < kw.size(); i++) {
            String id = kw.get(i).getKey().getId();
            fused.merge(id, 1.0 / (RRF_K + i + 1), Double::sum);
            by.computeIfAbsent(id, k -> new LinkedHashSet<>()).add("keyword");
        }
        for (int i = 0; i < sem.size(); i++) {
            String id = sem.get(i).id();
            fused.merge(id, 1.0 / (RRF_K + i + 1), Double::sum);
            by.computeIfAbsent(id, k -> new LinkedHashSet<>()).add("semantic");
        }
        if (parsed.wantsCheap() && fused.size() > 1) {   // "affordable" nudges cheaper items up without hard-filtering
            List<String> ids = new ArrayList<>(fused.keySet());
            ids.sort(Comparator.comparingDouble(id -> finalPrice(byId.get(id))));
            for (int i = 0; i < ids.size(); i++) {
                final double pos = (double) i / (ids.size() - 1);
                fused.computeIfPresent(ids.get(i), (k, v) -> v * (1 + 0.5 * (1 - pos)));
            }
        }

        List<Product> ranked = new ArrayList<>();
        for (String id : fused.keySet()) ranked.add(byId.get(id));
        Comparator<Product> byRelevance = (a, b) -> Double.compare(fused.get(b.getId()), fused.get(a.getId()));
        Comparator<Product> byPrice = (a, b) -> Double.compare(finalPrice(a), finalPrice(b));
        Comparator<Product> byDiscount = (a, b) -> Double.compare(b.getDiscountPercent(), a.getDiscountPercent());
        Comparator<Product> byName = (a, b) -> Objects.toString(a.getName(), "").compareToIgnoreCase(Objects.toString(b.getName(), ""));
        String sort = r.sort() == null ? "relevance" : r.sort();
        Comparator<Product> order;
        switch (sort) {
            case "price_asc" -> order = byPrice.thenComparing(byRelevance);
            case "price_desc" -> order = byPrice.reversed().thenComparing(byRelevance);
            case "discount" -> order = byDiscount.thenComparing(byRelevance);
            case "name" -> order = byName.thenComparing(byRelevance);
            default -> order = byRelevance;
        }
        ranked.sort(order);

        int size = Math.max(1, Math.min(60, r.size())), page = Math.max(0, r.page());
        int from = Math.min(ranked.size(), page * size), to = Math.min(ranked.size(), from + size);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Product p : ranked.subList(from, to)) {
            Map<String, Object> m = om.convertValue(p, new TypeReference<LinkedHashMap<String, Object>>() {});
            m.put("score", Math.round(fused.get(p.getId()) * 10000.0) / 10000.0);
            m.put("matchedBy", by.getOrDefault(p.getId(), Set.of()));
            items.add(m);
        }
        Map<String, Object> interpreted = new LinkedHashMap<>();
        interpreted.put("keywords", parsed.tokens());
        interpreted.put("minPrice", min);
        interpreted.put("maxPrice", max);
        interpreted.put("affordable", parsed.wantsCheap());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("query", parsed.text());
        out.put("interpreted", interpreted);
        out.put("mode", mode);
        out.put("engine", engine);
        out.put("notice", notice);
        out.put("total", ranked.size());
        out.put("page", page);
        out.put("size", size);
        out.put("results", items);
        return out;
    }
}

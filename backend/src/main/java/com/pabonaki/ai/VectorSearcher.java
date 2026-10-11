package com.pabonaki.ai;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nearest-neighbour search over product_embeddings.
 * Preferred engine: MongoDB Atlas Vector Search ($vectorSearch). If it is unavailable (local MongoDB, index missing or
 * still building) the same query is answered by an exact in-memory cosine scan, which is fine for catalogues of a few thousand products.
 */
public class VectorSearcher {
    private static final Logger log = LoggerFactory.getLogger(VectorSearcher.class);

    public enum Field {
        TEXT("textVector"), IMAGE("imageVector");
        public final String path;
        Field(String path) { this.path = path; }
    }

    public record Hit(String id, double score) {}
    public record Outcome(List<Hit> hits, String engine) {}
    private record Snapshot(long at, List<ProductEmbedding> docs) {}

    private final MongoTemplate mongo;
    private final AiSettings cfg;
    private final Map<Field, Snapshot> snapshots = new ConcurrentHashMap<>();
    private volatile long atlasDownUntil = 0;

    public VectorSearcher(MongoTemplate mongo, AiSettings cfg) { this.mongo = mongo; this.cfg = cfg; }

    /** Called by the indexer after it writes vectors so the local snapshot is rebuilt on next use. */
    public void invalidate() { snapshots.clear(); }

    public Outcome search(Field field, double[] query, int limit) {
        String mode = cfg.vectorMode == null ? "auto" : cfg.vectorMode.toLowerCase(Locale.ROOT);
        if (!mode.equals("local") && System.currentTimeMillis() >= atlasDownUntil) {
            try {
                List<Hit> hits = atlas(field, query, limit);
                if (!hits.isEmpty() || mode.equals("atlas")) return new Outcome(hits, "atlas-vector-search");
                // Empty from Atlas usually means the index is still building: answer exactly from memory instead.
            } catch (RuntimeException e) {
                if (mode.equals("atlas")) throw e;
                atlasDownUntil = System.currentTimeMillis() + 10 * 60_000L;
                log.warn("Atlas $vectorSearch unavailable for {} ({}); using local cosine fallback for 10 minutes.", field.path, e.getClass().getSimpleName());
            }
        }
        return new Outcome(local(field, query, limit), "local-cosine");
    }

    private List<Hit> atlas(Field field, double[] q, int limit) {
        List<Double> vec = new ArrayList<>(q.length);
        for (double d : q) vec.add(d);
        String index = field == Field.TEXT ? cfg.textIndexName : cfg.imageIndexName;
        Document vs = new Document("index", index).append("path", field.path).append("queryVector", vec)
                .append("numCandidates", Math.max(limit * 20, 100)).append("limit", limit)
                .append("filter", new Document("active", true));
        List<Document> pipeline = List.of(new Document("$vectorSearch", vs),
                new Document("$project", new Document("_id", 1).append("score", new Document("$meta", "vectorSearchScore"))));
        List<Hit> hits = new ArrayList<>();
        for (Document d : mongo.getCollection("product_embeddings").aggregate(pipeline)) {
            Object s = d.get("score");
            hits.add(new Hit(String.valueOf(d.get("_id")), s instanceof Number n ? n.doubleValue() : 0));
        }
        return hits;
    }

    private List<Hit> local(Field field, double[] q, int limit) {
        Snapshot s = snapshots.get(field);
        if (s == null || System.currentTimeMillis() - s.at() > 60_000) {
            Query query = Query.query(Criteria.where("active").is(true).and(field.path).exists(true)).limit(cfg.localMaxDocs);
            s = new Snapshot(System.currentTimeMillis(), mongo.find(query, ProductEmbedding.class));
            snapshots.put(field, s);
        }
        PriorityQueue<Hit> best = new PriorityQueue<>(Comparator.comparingDouble(Hit::score));
        for (ProductEmbedding e : s.docs()) {
            double[] v = field == Field.TEXT ? e.textVector : e.imageVector;
            if (v == null || v.length != q.length) continue;
            double score = VectorMath.cosine(q, v);
            if (best.size() < limit) best.add(new Hit(e.id, score));
            else if (score > best.peek().score()) { best.poll(); best.add(new Hit(e.id, score)); }
        }
        List<Hit> out = new ArrayList<>(best);
        out.sort(Comparator.comparingDouble(Hit::score).reversed());
        return out;
    }

    /** Loads text vectors for specific products (used for similarity and recommendations). One query, no N+1. */
    public Map<String, double[]> textVectors(Collection<String> ids) {
        Map<String, double[]> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        Query q = Query.query(Criteria.where("_id").in(ids).and("textVector").exists(true));
        q.fields().include("textVector");
        for (ProductEmbedding e : mongo.find(q, ProductEmbedding.class)) if (e.textVector != null) out.put(e.id, e.textVector);
        return out;
    }
}

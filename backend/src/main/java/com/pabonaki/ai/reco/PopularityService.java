package com.pabonaki.ai.reco;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Aggregate (anonymous) popularity per product from recent orders and activity counts, cached for 10 minutes so listing
 * pages never trigger the aggregation pipelines on every request.
 */
public class PopularityService {
    public static final List<String> VALID_ORDER_STATUSES = List.of("CONFIRMED", "PROCESSING", "SHIPPED", "DELIVERED");
    private static final long TTL_MS = 10 * 60_000L;

    public record Snapshot(long at, Map<String, Double> raw, double max) {
        public double normalized(String id) { return max <= 0 ? 0 : raw.getOrDefault(id, 0.0) / max; }
    }

    private final MongoTemplate mongo;
    private volatile Snapshot snapshot;

    public PopularityService(MongoTemplate mongo) { this.mongo = mongo; }

    public Snapshot get() {
        Snapshot s = snapshot;
        if (s == null || System.currentTimeMillis() - s.at() > TTL_MS) s = refresh();
        return s;
    }

    public synchronized Snapshot refresh() {
        Map<String, double[]> acc = new HashMap<>();   // [orderQty, carts, wishes, views]
        Instant since60 = Instant.now().minus(60, ChronoUnit.DAYS), since30 = Instant.now().minus(30, ChronoUnit.DAYS);
        try {
            Aggregation orders = Aggregation.newAggregation(
                    Aggregation.match(Criteria.where("status").in(VALID_ORDER_STATUSES).and("createdAt").gte(since60)),
                    Aggregation.unwind("items"),
                    Aggregation.group("items.productId").sum("items.quantity").as("qty"));
            AggregationResults<Document> r = mongo.aggregate(orders, "orders", Document.class);
            for (Document d : r.getMappedResults()) {
                Object id = d.get("_id");
                Object q = d.get("qty");
                if (id != null && q instanceof Number n) acc.computeIfAbsent(String.valueOf(id), k -> new double[4])[0] += n.doubleValue();
            }
            Aggregation act = Aggregation.newAggregation(
                    Aggregation.match(Criteria.where("createdAt").gte(since30).and("productId").ne(null)),
                    Aggregation.group("productId", "type").count().as("n"));
            for (Document d : mongo.aggregate(act, "user_activity", Document.class).getMappedResults()) {
                String pid = null, type = null;
                if (d.get("_id") instanceof Document idDoc) { pid = idDoc.getString("productId"); type = idDoc.getString("type"); }
                if (pid == null) { pid = d.getString("productId"); type = d.getString("type"); }
                Object n = d.get("n");
                if (pid == null || type == null || !(n instanceof Number num)) continue;
                double[] a = acc.computeIfAbsent(pid, k -> new double[4]);
                switch (type) {
                    case "CART_ADD" -> a[1] += num.doubleValue();
                    case "WISHLIST_ADD" -> a[2] += num.doubleValue();
                    case "VIEW" -> a[3] += num.doubleValue();
                    default -> { }
                }
            }
        } catch (RuntimeException e) {
            // keep serving the previous snapshot (or an empty one) rather than failing the page
            if (snapshot != null) return snapshot;
        }
        Map<String, Double> raw = new HashMap<>();
        double max = 0;
        for (Map.Entry<String, double[]> e : acc.entrySet()) {
            double[] a = e.getValue();
            double v = RecoMath.popularityRaw(a[0], a[1], a[2], a[3]);
            raw.put(e.getKey(), v);
            max = Math.max(max, v);
        }
        snapshot = new Snapshot(System.currentTimeMillis(), raw, max);
        return snapshot;
    }
}

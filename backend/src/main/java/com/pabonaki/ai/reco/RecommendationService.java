package com.pabonaki.ai.reco;

import com.pabonaki.ai.QueryParser;
import com.pabonaki.ai.VectorMath;
import com.pabonaki.ai.VectorSearcher;
import com.pabonaki.model.Order;
import com.pabonaki.model.Product;
import com.pabonaki.repo.OrderRepository;
import com.pabonaki.repo.ProductRepository;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Hybrid recommender: content similarity (text embeddings, when available) + category affinity + price fit + aggregate popularity.
 * Everything comes from the live Mongo data. Only ACTIVE, in-stock products are ever returned.
 * Replace or wrap this class to plug in a trained model later; the controller only depends on the Result shape.
 */
public class RecommendationService {
    public record Result(String strategy, boolean personalized, List<Product> items, Product basedOn) {}

    private static final class Profile {
        final Map<String, Double> categoryWeight = new HashMap<>();
        final Map<String, Double> productWeight = new HashMap<>();
        final Set<String> purchased = new HashSet<>();
        double priceSum, priceWeight;
        boolean isEmpty() { return categoryWeight.isEmpty() && productWeight.isEmpty(); }
        double avgPrice() { return priceWeight > 0 ? priceSum / priceWeight : 0; }
    }

    private final ProductRepository products;
    private final OrderRepository orders;
    private final MongoTemplate mongo;
    private final VectorSearcher vectors;
    private final PopularityService popularity;

    public RecommendationService(ProductRepository products, OrderRepository orders, MongoTemplate mongo, VectorSearcher vectors, PopularityService popularity) {
        this.products = products; this.orders = orders; this.mongo = mongo; this.vectors = vectors; this.popularity = popularity;
    }

    /** ACTIVE and in stock: the only products that may be offered as recommendations. */
    private List<Product> catalogue() {
        return products.findByStatusOrderByCreatedAtDesc("ACTIVE").stream().filter(p -> p.getStock() > 0).collect(Collectors.toList());
    }

    private static double ageDays(Instant t) { return t == null ? 365 : Duration.between(t, Instant.now()).toMinutes() / 1440.0; }

    // ---------- trending ----------
    public Result trending(int n) {
        List<Product> cat = catalogue();
        PopularityService.Snapshot pop = popularity.get();
        List<Product> ranked = cat.stream().filter(p -> pop.raw().containsKey(p.getId()))
                .sorted((a, b) -> Double.compare(pop.normalized(b.getId()), pop.normalized(a.getId()))).limit(n).collect(Collectors.toList());
        if (ranked.size() < n) {                         // new store or little data: top up with the newest in-stock products
            Set<String> have = ranked.stream().map(Product::getId).collect(Collectors.toSet());
            for (Product p : cat) { if (ranked.size() >= n) break; if (have.add(p.getId())) ranked.add(p); }
            return new Result(pop.raw().isEmpty() ? "newest" : "trending+newest", false, ranked, null);
        }
        return new Result("trending", false, ranked, null);
    }

    // ---------- similar ----------
    public Result similar(Product base, int n) {
        List<Product> cands = catalogue().stream().filter(p -> !p.getId().equals(base.getId())).collect(Collectors.toList());
        List<String> ids = cands.stream().map(Product::getId).collect(Collectors.toList());
        ids.add(base.getId());
        Map<String, double[]> vec = vectors.textVectors(ids);
        double[] bv = vec.get(base.getId());
        PopularityService.Snapshot pop = popularity.get();
        boolean hasVectors = bv != null;
        List<Map.Entry<Product, Double>> scored = new ArrayList<>();
        for (Product p : cands) {
            double[] cv = vec.get(p.getId());
            double content = hasVectors && cv != null ? Math.max(0, VectorMath.cosine(bv, cv)) : 0;
            boolean same = Objects.equals(p.getCategory(), base.getCategory());
            double s = RecoMath.similarScore(content, same, RecoMath.pricePreference(p.getFinalPrice(), base.getFinalPrice()), pop.normalized(p.getId()), hasVectors && cv != null);
            scored.add(Map.entry(p, s));
        }
        scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        List<Product> out = scored.stream().limit(n).map(Map.Entry::getKey).collect(Collectors.toList());
        return new Result(hasVectors ? "content+category" : "category+popularity", false, out, base);
    }

    // ---------- personalised ----------
    private List<UserActivity> loadActivity(String userId, String sessionId) {
        Criteria since = Criteria.where("createdAt").gte(Instant.now().minus(Duration.ofDays(90)));
        Criteria who;
        if (userId != null && sessionId != null)
            who = new Criteria().orOperator(Criteria.where("userId").is(userId),
                    new Criteria().andOperator(Criteria.where("sessionId").is(sessionId), Criteria.where("userId").is(null)));
        else if (userId != null) who = Criteria.where("userId").is(userId);
        else if (sessionId != null) who = new Criteria().andOperator(Criteria.where("sessionId").is(sessionId), Criteria.where("userId").is(null));
        else return List.of();
        Query q = Query.query(new Criteria().andOperator(since, who)).with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(300);
        return mongo.find(q, UserActivity.class);
    }

    private Profile buildProfile(List<UserActivity> acts, String userId, Map<String, Product> live) {
        Profile pr = new Profile();
        for (UserActivity a : acts) {
            double w = RecoMath.weight(a.type) * RecoMath.decay(ageDays(a.createdAt));
            if (w <= 0) continue;
            if ("SEARCH".equals(a.type)) {
                List<String> tokens = QueryParser.parse(a.query).tokens();
                for (String cat : live.values().stream().map(Product::getCategory).filter(Objects::nonNull).collect(Collectors.toSet()))
                    for (String t : tokens) if (cat.toLowerCase(Locale.ROOT).contains(t)) { pr.categoryWeight.merge(cat, w, Double::sum); break; }
                continue;
            }
            if (a.productId == null) continue;
            Product p = live.get(a.productId);
            String cat = p != null ? p.getCategory() : a.category;
            if (cat != null) pr.categoryWeight.merge(cat, w, Double::sum);
            if (p != null) {
                pr.productWeight.merge(p.getId(), w, Double::sum);
                pr.priceSum += p.getFinalPrice() * w; pr.priceWeight += w;
            }
        }
        if (userId != null) {
            for (Order o : orders.findByUserIdOrderByCreatedAtDesc(userId).stream().limit(50).toList()) {
                if (!PopularityService.VALID_ORDER_STATUSES.contains(o.getStatus()) || o.getItems() == null) continue;
                double decay = RecoMath.decay(ageDays(o.getCreatedAt()));
                for (Order.Item it : o.getItems()) {
                    if (it.getProductId() == null) continue;
                    pr.purchased.add(it.getProductId());
                    Product p = live.get(it.getProductId());
                    if (p == null) continue;
                    double w = RecoMath.weight("PURCHASE") * decay * Math.min(3, Math.max(1, it.getQuantity()));
                    if (p.getCategory() != null) pr.categoryWeight.merge(p.getCategory(), w, Double::sum);
                    pr.productWeight.merge(p.getId(), w, Double::sum);
                    pr.priceSum += p.getFinalPrice() * w; pr.priceWeight += w;
                }
            }
        }
        return pr;
    }

    /** Personalised for a signed-in user and/or a guest session; falls back to trending when there is no history yet. */
    public Result forShopper(String userId, String sessionId, int n) {
        List<Product> cat = catalogue();
        Map<String, Product> live = cat.stream().collect(Collectors.toMap(Product::getId, p -> p, (a, b) -> a));
        Profile pr = buildProfile(loadActivity(userId, sessionId), userId, live);
        if (pr.isEmpty()) return trending(n);

        List<Product> cands = cat.stream().filter(p -> !pr.purchased.contains(p.getId())).collect(Collectors.toList());
        Set<String> need = new HashSet<>(pr.productWeight.keySet());
        cands.forEach(p -> need.add(p.getId()));
        Map<String, double[]> vec = vectors.textVectors(need);

        double[] profileVec = null;
        for (Map.Entry<String, Double> e : pr.productWeight.entrySet()) {
            double[] v = vec.get(e.getKey());
            if (v == null) continue;
            if (profileVec == null) profileVec = new double[v.length];
            if (profileVec.length != v.length) continue;
            for (int i = 0; i < v.length; i++) profileVec[i] += v[i] * e.getValue();
        }
        profileVec = VectorMath.normalize(profileVec);
        double maxCat = pr.categoryWeight.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        PopularityService.Snapshot pop = popularity.get();

        List<Map.Entry<Product, Double>> scored = new ArrayList<>();
        for (Product p : cands) {
            double[] cv = vec.get(p.getId());
            boolean useVec = profileVec != null && cv != null;
            double content = useVec ? Math.max(0, VectorMath.cosine(profileVec, cv)) : 0;
            double catAff = maxCat > 0 ? pr.categoryWeight.getOrDefault(p.getCategory(), 0.0) / maxCat : 0;
            double price = pr.avgPrice() > 0 ? RecoMath.pricePreference(p.getFinalPrice(), pr.avgPrice()) : 0.5;
            scored.add(Map.entry(p, RecoMath.personalScore(content, catAff, pop.normalized(p.getId()), price, useVec)));
        }
        scored.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        // Light diversity: no single category takes more than ~60% of the shelf unless nothing else is left.
        int cap = Math.max(2, (int) Math.ceil(n * 0.6));
        Map<String, Integer> perCat = new HashMap<>();
        List<Product> out = new ArrayList<>(), skipped = new ArrayList<>();
        for (Map.Entry<Product, Double> e : scored) {
            if (out.size() >= n) break;
            String c = Objects.toString(e.getKey().getCategory(), "");
            if (perCat.getOrDefault(c, 0) >= cap) { skipped.add(e.getKey()); continue; }
            perCat.merge(c, 1, Integer::sum); out.add(e.getKey());
        }
        for (Product p : skipped) { if (out.size() >= n) break; out.add(p); }
        return new Result(userId != null ? "personalized" : "session", true, out, null);
    }

    // ---------- because you viewed ----------
    public Result becauseYouViewed(String userId, String sessionId, int n) {
        Instant since = Instant.now().minus(Duration.ofDays(30));
        for (UserActivity a : loadActivity(userId, sessionId)) {
            if (!"VIEW".equals(a.type) || a.productId == null || a.createdAt == null || a.createdAt.isBefore(since)) continue;
            Optional<Product> p = products.findById(a.productId).filter(x -> "ACTIVE".equals(x.getStatus()));
            if (p.isPresent()) return similar(p.get(), n);   // newest valid view first (list is sorted newest first)
        }
        return new Result("none", true, List.of(), null);
    }

    // ---------- frequently bought together ----------
    public List<Product> frequentlyBoughtTogether(String productId, int n) {
        Query q = Query.query(Criteria.where("items.productId").is(productId).and("status").in(PopularityService.VALID_ORDER_STATUSES))
                .with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(500);
        Map<String, Integer> counts = new HashMap<>();
        for (Order o : mongo.find(q, Order.class)) {
            if (o.getItems() == null) continue;
            Set<String> seen = new HashSet<>();
            for (Order.Item it : o.getItems()) if (it.getProductId() != null && !it.getProductId().equals(productId) && seen.add(it.getProductId())) counts.merge(it.getProductId(), 1, Integer::sum);
        }
        if (counts.isEmpty()) return List.of();
        Map<String, Product> live = new HashMap<>();
        for (Product p : products.findAllById(counts.keySet())) if ("ACTIVE".equals(p.getStatus()) && p.getStock() > 0) live.put(p.getId(), p);
        return counts.entrySet().stream().filter(e -> live.containsKey(e.getKey()))
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue())).limit(n).map(e -> live.get(e.getKey())).collect(Collectors.toList());
    }
}

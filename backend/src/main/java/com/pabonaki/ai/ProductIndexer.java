package com.pabonaki.ai;

import com.pabonaki.model.Product;
import com.pabonaki.repo.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps product_embeddings in step with the products collection without ever writing to products.
 * - A Mongo save listener enqueues new/edited products (see {@link ProductIndexListener}).
 * - A scheduled sweep backfills existing products in small batches and re-embeds anything whose name,
 *   description, category, image URL or embedding model changed (detected through content hashes).
 * - Provider failures never crash the app; transient errors stop the batch and are retried on the next run.
 */
public class ProductIndexer {
    private static final Logger log = LoggerFactory.getLogger(ProductIndexer.class);

    public record SweepResult(int scanned, int needingWork, int processed, int failed, String stoppedBecause) {}

    private final ProductRepository products;
    private final MongoTemplate mongo;
    private final EmbeddingProvider emb;
    private final ImageFetcher fetcher;
    private final VectorSearcher searcher;
    private final AiSettings cfg;
    private final Set<String> queue = ConcurrentHashMap.newKeySet();

    public ProductIndexer(ProductRepository products, MongoTemplate mongo, EmbeddingProvider emb, ImageFetcher fetcher, VectorSearcher searcher, AiSettings cfg) {
        this.products = products; this.mongo = mongo; this.emb = emb; this.fetcher = fetcher; this.searcher = searcher; this.cfg = cfg;
    }

    public void enqueue(String productId) { if (productId != null && cfg.enabled) queue.add(productId); }

    // ---- hashing ----
    static String sha(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    static String textBody(Product p) {
        String desc = p.getDescription() == null ? "" : p.getDescription().trim();
        String s = (p.getCategory() == null ? "" : "Category: " + p.getCategory().trim() + ". ") + desc;
        return s.length() > 1500 ? s.substring(0, 1500) : s;
    }

    String textHash(Product p) { return sha(emb.fingerprint() + "|" + Objects.toString(p.getName(), "") + "|" + textBody(p)); }
    String imageHash(Product p) { return sha(emb.fingerprint() + "|" + Objects.toString(p.getImageUrl(), "")); }
    static boolean isActive(Product p) { return "ACTIVE".equals(p.getStatus()); }

    boolean needsWork(Product p, ProductEmbedding e, boolean force) {
        if (e == null || force) return true;
        if (e.active != isActive(p) || !Objects.equals(e.category, p.getCategory())) return true;
        if (!emb.isAvailable()) return false;
        if (e.textHash == null || !textHash(p).equals(e.textHash)) return true;
        boolean noImage = p.getImageUrl() == null || p.getImageUrl().isBlank();
        if (noImage) return !ProductEmbedding.IMG_NO_IMAGE.equals(e.imageStatus);
        if (!imageHash(p).equals(e.imageHash)) return true;
        return ProductEmbedding.IMG_FAILED.equals(e.imageStatus) && e.imageFailures < 3
                && e.imageUpdatedAt != null && e.imageUpdatedAt.isBefore(Instant.now().minus(1, ChronoUnit.HOURS));
    }

    /** Indexes one product. Throws EmbeddingException only for transient provider problems. */
    public void indexProduct(Product p, boolean force) {
        ProductEmbedding e = mongo.findById(p.getId(), ProductEmbedding.class);
        if (e == null) { e = new ProductEmbedding(); e.id = p.getId(); }
        Instant now = Instant.now();
        boolean changed = false;
        if (e.updatedAt == null || e.active != isActive(p) || !Objects.equals(e.category, p.getCategory())) {
            e.active = isActive(p); e.category = p.getCategory(); e.updatedAt = now; mongo.save(e); changed = true;
        }
        if (!emb.isAvailable()) { if (changed) searcher.invalidate(); return; }

        String fp = emb.fingerprint();
        String th = textHash(p);
        if (force || e.textVector == null || !th.equals(e.textHash)) {
            e.textVector = emb.embedDocument(p.getName(), textBody(p));
            e.textHash = th; e.textUpdatedAt = now; e.model = fp; changed = true;
        }

        String url = p.getImageUrl();
        if (url == null || url.isBlank()) {
            if (!ProductEmbedding.IMG_NO_IMAGE.equals(e.imageStatus) || e.imageVector != null) {
                e.imageVector = null; e.imageHash = null; e.imageStatus = ProductEmbedding.IMG_NO_IMAGE; e.imageError = null; e.imageFailures = 0; changed = true;
            }
        } else {
            String ih = imageHash(p);
            boolean stale = force || !ih.equals(e.imageHash);
            boolean retry = ProductEmbedding.IMG_FAILED.equals(e.imageStatus) && ih.equals(e.imageHash) && e.imageFailures < 3
                    && e.imageUpdatedAt != null && e.imageUpdatedAt.isBefore(now.minus(1, ChronoUnit.HOURS));
            if (stale || retry) {
                try {
                    ImageFetcher.Fetched f = fetcher.fetch(url);
                    e.imageVector = emb.embedImage(f.bytes(), f.mime());
                    e.imageStatus = ProductEmbedding.IMG_OK; e.imageError = null; e.imageFailures = 0;
                } catch (ImageFetcher.FetchException fe) {
                    e.imageVector = null;
                    e.imageStatus = fe.unsupported ? ProductEmbedding.IMG_UNSUPPORTED : ProductEmbedding.IMG_FAILED;
                    e.imageError = fe.getMessage();
                    e.imageFailures = fe.unsupported ? 0 : e.imageFailures + 1;
                } catch (EmbeddingException ee) {
                    if (ee.isTransient()) { e.updatedAt = now; mongo.save(e); searcher.invalidate(); throw ee; }
                    e.imageVector = null; e.imageStatus = ProductEmbedding.IMG_FAILED; e.imageError = ee.getMessage(); e.imageFailures++;
                }
                e.imageHash = ih; e.imageUpdatedAt = now; changed = true;
            }
        }
        if (changed) { e.updatedAt = now; mongo.save(e); searcher.invalidate(); }
    }

    /** Processes up to maxProducts products that need work. Safe to call repeatedly; it resumes where hashes say work remains. */
    public synchronized SweepResult sweep(boolean force, int maxProducts) {
        List<Product> all = products.findAll();
        Query q = new Query();
        q.fields().include("textHash", "imageHash", "imageStatus", "imageFailures", "imageUpdatedAt", "active", "category");
        Map<String, ProductEmbedding> existing = new HashMap<>();
        for (ProductEmbedding e : mongo.find(q, ProductEmbedding.class)) existing.put(e.id, e);

        Set<String> productIds = new HashSet<>();
        List<Product> todo = new ArrayList<>();
        for (Product p : all) {
            productIds.add(p.getId());
            if (needsWork(p, existing.get(p.getId()), force)) todo.add(p);
        }
        // Active products first so customers see them sooner.
        todo.sort(Comparator.comparing((Product p) -> !isActive(p)));

        Set<String> orphans = new HashSet<>(existing.keySet());
        orphans.removeAll(productIds);
        if (!orphans.isEmpty()) { mongo.remove(Query.query(Criteria.where("_id").in(orphans)), ProductEmbedding.class); searcher.invalidate(); }

        int processed = 0, failed = 0;
        String stopped = null;
        for (Product p : todo) {
            if (processed >= maxProducts) { stopped = "batch limit reached; run again to continue"; break; }
            try {
                indexProduct(p, force);
                processed++;
            } catch (EmbeddingException ex) {
                if (ex.isTransient()) { stopped = ex.kind().name().toLowerCase(Locale.ROOT).replace('_', ' ') + "; will retry later"; break; }
                failed++; processed++;
                log.warn("Embedding failed for product {}: {}", p.getId(), ex.kind());
            } catch (RuntimeException ex) {
                failed++; processed++;
                log.warn("Indexing failed for product {}: {}", p.getId(), ex.getClass().getSimpleName());
            }
        }
        return new SweepResult(all.size(), todo.size(), processed, failed, stopped);
    }

    @Scheduled(fixedDelayString = "${app.ai.index.interval-ms:300000}", initialDelayString = "${app.ai.index.initial-delay-ms:30000}")
    public void scheduledSweep() {
        if (!cfg.enabled || !cfg.indexAuto) return;
        try {
            SweepResult r = sweep(false, cfg.indexBatch);
            if (r.processed() > 0 || r.stoppedBecause() != null) log.info("AI index sweep: scanned={} needing={} processed={} failed={} stopped={}", r.scanned(), r.needingWork(), r.processed(), r.failed(), r.stoppedBecause());
        } catch (RuntimeException e) {
            log.warn("AI index sweep failed: {}", e.getClass().getSimpleName());
        }
    }

    /** Picks up products saved a moment ago (new or edited in the admin dashboard). */
    @Scheduled(fixedDelay = 5000, initialDelay = 15000)
    public void drainQueue() {
        if (!cfg.enabled || queue.isEmpty()) return;
        int n = 0;
        for (Iterator<String> it = queue.iterator(); it.hasNext() && n < cfg.indexBatch; n++) {
            String id = it.next();
            it.remove();
            try {
                products.findById(id).ifPresent(p -> indexProduct(p, false));
            } catch (EmbeddingException ex) {
                if (ex.isTransient()) { queue.add(id); return; }       // the periodic sweep will also catch it
                log.warn("Embedding failed for product {}: {}", id, ex.kind());
            } catch (RuntimeException ex) {
                log.warn("Indexing failed for product {}: {}", id, ex.getClass().getSimpleName());
            }
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", cfg.enabled);
        m.put("providerConfigured", emb.isAvailable());
        m.put("model", emb.fingerprint());
        m.put("embeddingCallsToday", emb.usedToday());
        m.put("dailyBudget", cfg.dailyBudget);
        m.put("vectorMode", cfg.vectorMode);
        m.put("products", mongo.count(new Query(), Product.class));
        m.put("activeProducts", mongo.count(Query.query(Criteria.where("status").is("ACTIVE")), Product.class));
        m.put("textIndexed", mongo.count(Query.query(Criteria.where("textVector").exists(true)), ProductEmbedding.class));
        m.put("imageIndexed", mongo.count(Query.query(Criteria.where("imageVector").exists(true)), ProductEmbedding.class));
        m.put("imageNoImage", mongo.count(Query.query(Criteria.where("imageStatus").is(ProductEmbedding.IMG_NO_IMAGE)), ProductEmbedding.class));
        m.put("imageUnsupported", mongo.count(Query.query(Criteria.where("imageStatus").is(ProductEmbedding.IMG_UNSUPPORTED)), ProductEmbedding.class));
        m.put("imageFailed", mongo.count(Query.query(Criteria.where("imageStatus").is(ProductEmbedding.IMG_FAILED)), ProductEmbedding.class));
        m.put("queued", queue.size());
        return m;
    }
}

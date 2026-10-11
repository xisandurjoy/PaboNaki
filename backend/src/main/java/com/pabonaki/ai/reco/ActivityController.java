package com.pabonaki.ai.reco;

import com.pabonaki.ai.AiSettings;
import com.pabonaki.ai.RateLimiter;
import com.pabonaki.model.Product;
import com.pabonaki.repo.ProductRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Receives browsing signals from the storefront. Works for guests (random session id) and signed-in users (identity taken from the JWT only). */
@RestController
@RequestMapping("/api/v1/activity")
public class ActivityController {
    public record ActivityReq(String type, String productId, String query, String sessionId) {}

    static final Set<String> TYPES = Set.of("VIEW", "SEARCH", "WISHLIST_ADD", "CART_ADD");
    static final Pattern SESSION = Pattern.compile("^[A-Za-z0-9-]{16,64}$");
    static final Pattern PRODUCT_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final MongoTemplate mongo;
    private final ProductRepository products;
    private final RateLimiter limiter;
    private final AiSettings cfg;

    public ActivityController(MongoTemplate mongo, ProductRepository products, RateLimiter limiter, AiSettings cfg) {
        this.mongo = mongo; this.products = products; this.limiter = limiter; this.cfg = cfg;
    }

    static String userId(Authentication a) {
        return a != null && a.isAuthenticated() && !(a instanceof AnonymousAuthenticationToken) && a.getPrincipal() instanceof String id ? id : null;
    }

    @PostMapping
    public Map<String, Object> record(Authentication auth, HttpServletRequest http, @RequestBody ActivityReq req) {
        String uid = userId(auth);
        String key = uid != null ? "u:" + uid : "ip:" + http.getRemoteAddr();
        if (!limiter.tryAcquire("act:" + key, cfg.activityPerMinute, 60_000)) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests.");
        String type = req.type() == null ? "" : req.type().trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown activity type.");
        String sid = req.sessionId();
        if (sid != null && !SESSION.matcher(sid).matches()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid session id.");
        if (uid == null && sid == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A session id is required.");

        UserActivity a = new UserActivity();
        a.userId = uid; a.sessionId = sid; a.type = type;
        if (type.equals("SEARCH")) {
            String q = com.pabonaki.ai.QueryParser.sanitize(req.query());
            if (q.length() < 2) return Map.of("recorded", false);
            a.query = q.length() > 100 ? q.substring(0, 100) : q;
        } else {
            String pid = req.productId();
            if (pid == null || !PRODUCT_ID.matcher(pid).matches()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid product id.");
            Product p = products.findById(pid).filter(x -> "ACTIVE".equals(x.getStatus())).orElse(null);
            if (p == null) return Map.of("recorded", false);
            a.productId = p.getId(); a.category = p.getCategory();
            if (type.equals("VIEW")) {   // ignore refresh-spam so popularity cannot be inflated by one visitor
                Criteria who = uid != null ? Criteria.where("userId").is(uid) : Criteria.where("sessionId").is(sid);
                Query dup = Query.query(new Criteria().andOperator(who, Criteria.where("productId").is(p.getId()), Criteria.where("type").is("VIEW"),
                        Criteria.where("createdAt").gte(Instant.now().minusSeconds(120))));
                if (mongo.exists(dup, UserActivity.class)) return Map.of("recorded", false);
            }
        }
        mongo.insert(a);
        return Map.of("recorded", true);
    }
}

package com.pabonaki.ai.reco;

import com.pabonaki.ai.AiSettings;
import com.pabonaki.ai.RateLimiter;
import com.pabonaki.model.Product;
import com.pabonaki.repo.ProductRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * /api/v1/recommendations/* . Only /for-you requires sign-in (enforced in SecurityConfig and re-checked here).
 * Responses contain product data only: never another customer's activity. Personalised responses are marked no-store.
 * NOTE: the older GET /api/v1/recommendations (no suffix) from AdvancedFeaturesController is untouched.
 */
@RestController
@RequestMapping("/api/v1/recommendations")
public class RecommendationController {
    private final RecommendationService reco;
    private final ProductRepository products;
    private final RateLimiter limiter;
    private final AiSettings cfg;

    public RecommendationController(RecommendationService reco, ProductRepository products, RateLimiter limiter, AiSettings cfg) {
        this.reco = reco; this.products = products; this.limiter = limiter; this.cfg = cfg;
    }

    private void guard(Authentication auth, HttpServletRequest http) {
        String uid = ActivityController.userId(auth);
        String key = uid != null ? "u:" + uid : "ip:" + http.getRemoteAddr();
        if (!limiter.tryAcquire("rec:" + key, cfg.recommendPerMinute, 60_000)) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests.");
    }

    private static int limit(int n) { return Math.max(1, Math.min(n, 24)); }

    private static String session(String s) {
        if (s == null || s.isBlank()) return null;
        if (!ActivityController.SESSION.matcher(s).matches()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid session id.");
        return s;
    }

    private static Map<String, Object> body(String section, RecommendationService.Result r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("section", section);
        m.put("strategy", r.strategy());
        m.put("personalized", r.personalized());
        if (r.basedOn() != null) m.put("basedOn", Map.of("id", r.basedOn().getId(), "name", String.valueOf(r.basedOn().getName())));
        m.put("items", r.items());
        return m;
    }

    private Product activeProduct(String id) {
        if (!ActivityController.PRODUCT_ID.matcher(id).matches()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid product id.");
        return products.findById(id).filter(p -> "ACTIVE".equals(p.getStatus())).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
    }

    /** Signed-in customers only: built from their own purchases and activity. */
    @GetMapping("/for-you")
    public Map<String, Object> forYou(Authentication auth, HttpServletRequest http, HttpServletResponse res,
                                      @RequestParam(value = "sessionId", required = false) String sessionId,
                                      @RequestParam(value = "limit", defaultValue = "8") int limit) {
        String uid = ActivityController.userId(auth);
        if (uid == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please log in to continue.");
        guard(auth, http);
        res.setHeader("Cache-Control", "private, no-store");
        return body("for-you", reco.forShopper(uid, session(sessionId), limit(limit)));
    }

    /** Guests (session-based) and anyone else: personalised from the browser session if it has activity, otherwise trending. */
    @GetMapping("/discover")
    public Map<String, Object> discover(Authentication auth, HttpServletRequest http, HttpServletResponse res,
                                        @RequestParam(value = "sessionId", required = false) String sessionId,
                                        @RequestParam(value = "limit", defaultValue = "8") int limit) {
        guard(auth, http);
        res.setHeader("Cache-Control", "private, no-store");
        return body("discover", reco.forShopper(ActivityController.userId(auth), session(sessionId), limit(limit)));
    }

    @GetMapping("/trending")
    public Map<String, Object> trending(Authentication auth, HttpServletRequest http, @RequestParam(value = "limit", defaultValue = "8") int limit) {
        guard(auth, http);
        return body("trending", reco.trending(limit(limit)));
    }

    @GetMapping("/similar/{productId}")
    public Map<String, Object> similar(Authentication auth, HttpServletRequest http, @PathVariable String productId, @RequestParam(value = "limit", defaultValue = "8") int limit) {
        guard(auth, http);
        return body("similar", reco.similar(activeProduct(productId), limit(limit)));
    }

    @GetMapping("/frequently-bought-together/{productId}")
    public Map<String, Object> fbt(Authentication auth, HttpServletRequest http, @PathVariable String productId, @RequestParam(value = "limit", defaultValue = "4") int limit) {
        guard(auth, http);
        Product base = activeProduct(productId);
        List<Product> items = reco.frequentlyBoughtTogether(base.getId(), limit(limit));
        return body("frequently-bought-together", new RecommendationService.Result("co-purchase", false, items, base));
    }

    @GetMapping("/because-you-viewed")
    public Map<String, Object> becauseYouViewed(Authentication auth, HttpServletRequest http, HttpServletResponse res,
                                                @RequestParam(value = "sessionId", required = false) String sessionId,
                                                @RequestParam(value = "limit", defaultValue = "8") int limit) {
        guard(auth, http);
        res.setHeader("Cache-Control", "private, no-store");
        return body("because-you-viewed", reco.becauseYouViewed(ActivityController.userId(auth), session(sessionId), limit(limit)));
    }
}

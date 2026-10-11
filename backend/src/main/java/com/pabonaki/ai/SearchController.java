package com.pabonaki.ai;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** /api/v1/search/* : hybrid semantic search and visual search. Public (like the product list) but rate limited. */
@RestController
@RequestMapping("/api/v1/search")
public class SearchController {
    private static final Logger log = LoggerFactory.getLogger(SearchController.class);
    private static final Set<String> SORTS = Set.of("relevance", "price_asc", "price_desc", "discount", "name");

    private final SemanticSearchService semantic;
    private final VisualSearchService visual;
    private final EmbeddingProvider emb;
    private final RateLimiter limiter;
    private final AiSettings cfg;

    public SearchController(SemanticSearchService semantic, VisualSearchService visual, EmbeddingProvider emb, RateLimiter limiter, AiSettings cfg) {
        this.semantic = semantic; this.visual = visual; this.emb = emb; this.limiter = limiter; this.cfg = cfg;
    }

    /** Lets the storefront show the camera / smart-search UI only when it can really work. */
    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        boolean on = cfg.enabled && emb.isAvailable();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("semantic", on);
        m.put("visual", on);
        m.put("maxImageBytes", cfg.visualMaxBytes);
        return m;
    }

    @GetMapping("/semantic")
    public Map<String, Object> semanticSearch(Authentication auth, HttpServletRequest http,
                                              @RequestParam("q") String q,
                                              @RequestParam(value = "category", required = false) String category,
                                              @RequestParam(value = "minPrice", required = false) Double minPrice,
                                              @RequestParam(value = "maxPrice", required = false) Double maxPrice,
                                              @RequestParam(value = "inStock", defaultValue = "true") boolean inStock,
                                              @RequestParam(value = "sort", defaultValue = "relevance") String sort,
                                              @RequestParam(value = "page", defaultValue = "0") int page,
                                              @RequestParam(value = "size", defaultValue = "24") int size) {
        if (!cfg.enabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Smart search is turned off.");
        if (!limiter.tryAcquire("sem:" + clientKey(auth, http), cfg.semanticPerMinute, 60_000))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "You are searching very quickly. Please wait a moment.");
        if (category != null && category.length() > 80) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Category is too long.");
        if (bad(minPrice) || bad(maxPrice)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Price filters must be positive numbers.");
        if (!SORTS.contains(sort)) sort = "relevance";
        page = Math.max(0, Math.min(page, 200));
        size = Math.max(1, Math.min(size, 60));
        try {
            return semantic.search(new SemanticSearchService.Request(q, category, minPrice, maxPrice, inStock, sort, page, size));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping(value = "/visual", consumes = "multipart/form-data")
    public Map<String, Object> visualSearch(Authentication auth, HttpServletRequest http, @RequestParam("image") MultipartFile image) {
        if (!cfg.enabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Visual search is turned off.");
        if (!limiter.tryAcquire("vis:" + clientKey(auth, http), cfg.visualPerMinute, 60_000))
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many image searches. Please wait a minute and try again.");
        if (image == null || image.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose an image first.");
        if (image.getSize() > cfg.visualMaxBytes)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Image must be " + (cfg.visualMaxBytes / (1024 * 1024)) + " MB or smaller.");
        try {
            return visual.search(image.getBytes());            // bytes are held in memory only for this request; nothing is written to disk
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read that image.");
        } catch (EmbeddingException e) {
            log.warn("Visual search provider problem: {}", e.kind());
            throw switch (e.kind()) {
                case NOT_CONFIGURED -> new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Visual search is not available right now.");
                case RATE_LIMITED, BUDGET_EXCEEDED -> new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Visual search is busy right now. Please try again in a little while.");
                case BAD_INPUT -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "We could not use that image. Try a clearer JPG or PNG photo.");
                default -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Visual search could not be completed. Please try again.");
            };
        }
    }

    private static boolean bad(Double d) { return d != null && (d.isNaN() || d.isInfinite() || d < 0); }

    /** Signed-in users are limited per account, everyone else per IP address. */
    static String clientKey(Authentication a, HttpServletRequest http) {
        if (a != null && a.isAuthenticated() && !(a instanceof AnonymousAuthenticationToken) && a.getPrincipal() instanceof String id) return "u:" + id;
        return "ip:" + http.getRemoteAddr();
    }
}

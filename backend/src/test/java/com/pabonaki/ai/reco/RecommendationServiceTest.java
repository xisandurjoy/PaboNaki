package com.pabonaki.ai.reco;

import com.pabonaki.ai.VectorSearcher;
import com.pabonaki.model.Product;
import com.pabonaki.repo.OrderRepository;
import com.pabonaki.repo.ProductRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RecommendationServiceTest {
    private static Product p(String id, String cat, double price, int stock, String status) {
        Product x = new Product();
        x.setId(id); x.setName("P" + id); x.setCategory(cat); x.setPrice(price); x.setStock(stock); x.setStatus(status);
        return x;
    }

    private final ProductRepository products = mock(ProductRepository.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final VectorSearcher vectors = mock(VectorSearcher.class);
    private final PopularityService pop = mock(PopularityService.class);
    private final RecommendationService svc = new RecommendationService(products, orders, mongo, vectors, pop);

    @Test void similarNeverReturnsTheViewedProductOrUnavailableOnes() {
        Product base = p("1", "Bags", 1000, 5, "ACTIVE");
        when(products.findByStatusOrderByCreatedAtDesc("ACTIVE")).thenReturn(List.of(base, p("2", "Bags", 1100, 5, "ACTIVE"), p("3", "Bags", 900, 0, "ACTIVE"), p("4", "Home", 1000, 5, "ACTIVE")));
        when(vectors.textVectors(any())).thenReturn(new HashMap<>());
        when(pop.get()).thenReturn(new PopularityService.Snapshot(System.currentTimeMillis(), Map.of(), 0));
        List<String> ids = svc.similar(base, 8).items().stream().map(Product::getId).toList();
        assertFalse(ids.contains("1"));
        assertFalse(ids.contains("3"));       // sold out
        assertEquals("2", ids.get(0));        // same category and close price ranks first
    }

    @Test void newVisitorsGetTrendingNotPersonalised() {
        when(products.findByStatusOrderByCreatedAtDesc("ACTIVE")).thenReturn(List.of(p("1", "Bags", 1000, 5, "ACTIVE"), p("2", "Home", 500, 5, "ACTIVE")));
        when(pop.get()).thenReturn(new PopularityService.Snapshot(System.currentTimeMillis(), Map.of("2", 3.0), 3.0));
        when(mongo.find(any(org.springframework.data.mongodb.core.query.Query.class), org.mockito.ArgumentMatchers.eq(UserActivity.class))).thenReturn(List.of());
        RecommendationService.Result r = svc.forShopper(null, "123e4567-e89b-12d3-a456-426614174000", 2);
        assertFalse(r.personalized());
        assertEquals("2", r.items().get(0).getId());     // popular first, newest tops up
    }

    @Test void inactiveProductsNeverAppearInTrending() {
        when(products.findByStatusOrderByCreatedAtDesc("ACTIVE")).thenReturn(List.of(p("1", "Bags", 1000, 5, "ACTIVE")));
        when(pop.get()).thenReturn(new PopularityService.Snapshot(System.currentTimeMillis(), Map.of("1", 1.0, "gone", 9.0), 9.0));
        assertEquals(List.of("1"), svc.trending(5).items().stream().map(Product::getId).toList());
    }
}

package com.pabonaki.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pabonaki.model.Product;
import com.pabonaki.repo.ProductRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class SemanticSearchServiceTest {
    private static Product p(String id, String name, String cat, double price, int stock) {
        Product x = new Product();
        x.setId(id); x.setName(name); x.setCategory(cat); x.setPrice(price); x.setStock(stock); x.setDescription(name + " description");
        return x;
    }

    private final ProductRepository repo = mock(ProductRepository.class);
    private final VectorSearcher searcher = mock(VectorSearcher.class);
    private final EmbeddingProvider emb = mock(EmbeddingProvider.class);
    private final SemanticSearchService svc = new SemanticSearchService(repo, searcher, emb, new ObjectMapper());

    private SemanticSearchService.Request req(String q, boolean inStock) {
        return new SemanticSearchService.Request(q, null, null, null, inStock, "relevance", 0, 24);
    }

    @SuppressWarnings("unchecked")
    private List<String> ids(Map<String, Object> res) {
        return ((List<Map<String, Object>>) res.get("results")).stream().map(m -> (String) m.get("id")).toList();
    }

    @Test void keywordOnlyWhenEmbeddingsUnavailableAndBudgetIsApplied() {
        when(emb.isAvailable()).thenReturn(false);
        when(repo.findByStatusOrderByCreatedAtDesc("ACTIVE")).thenReturn(List.of(
                p("1", "Gaming Mouse", "Electronics", 1500, 5), p("2", "Gaming Headset", "Electronics", 4500, 5), p("3", "Gaming Chair", "Home", 2500, 0)));
        Map<String, Object> res = svc.search(req("gift for someone who likes gaming under 3000 BDT", true));
        assertEquals("keyword", res.get("mode"));
        assertEquals(List.of("1"), ids(res));          // 2 is over budget, 3 is out of stock
    }

    @Test void hybridUsesSemanticHitsButOnlyFromLiveActiveInStockPool() {
        when(emb.isAvailable()).thenReturn(true);
        when(emb.embedQuery(any())).thenReturn(new double[]{1, 0});
        when(repo.findByStatusOrderByCreatedAtDesc("ACTIVE")).thenReturn(List.of(p("a", "Oxford Shoes", "Fashion", 3000, 4), p("b", "Desk Lamp", "Home", 900, 4)));
        when(searcher.search(any(), any(), anyInt())).thenReturn(new VectorSearcher.Outcome(List.of(
                new VectorSearcher.Hit("a", 0.82), new VectorSearcher.Hit("ghost-deleted", 0.80), new VectorSearcher.Hit("b", 0.30)), "local-cosine"));
        Map<String, Object> res = svc.search(req("black shoes suitable for office", true));
        assertEquals("hybrid", res.get("mode"));
        assertEquals(List.of("a"), ids(res));          // ghost is not a live product; b is below the relative cutoff
    }

    @Test void providerFailureFallsBackToKeywordInsteadOfFailing() {
        when(emb.isAvailable()).thenReturn(true);
        when(emb.embedQuery(any())).thenThrow(new EmbeddingException(EmbeddingException.Kind.TIMEOUT, "slow"));
        when(repo.findByStatusOrderByCreatedAtDesc("ACTIVE")).thenReturn(List.of(p("a", "Leather Backpack", "Bags", 2000, 3)));
        Map<String, Object> res = svc.search(req("backpack", true));
        assertEquals("keyword", res.get("mode"));
        assertEquals(List.of("a"), ids(res));
    }

    @Test void emptyResultsAndTooShortQueries() {
        when(emb.isAvailable()).thenReturn(false);
        when(repo.findByStatusOrderByCreatedAtDesc("ACTIVE")).thenReturn(List.of(p("a", "Mug", "Home", 100, 3)));
        assertEquals(0, svc.search(req("zzzzqq", true)).get("total"));
        assertThrows(IllegalArgumentException.class, () -> svc.search(req(" a ", true)));
    }
}

package com.pabonaki.ai;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QueryParserTest {
    @Test void extractsBudgetFromGiftQuery() {
        var p = QueryParser.parse("A gift for someone who likes gaming under 3000 BDT");
        assertEquals(3000.0, p.maxPrice());
        assertEquals(java.util.List.of("gaming"), p.tokens());
    }
    @Test void detectsAffordableAndStemsPlurals() {
        var p = QueryParser.parse("Affordable wireless earbuds with good battery life");
        assertTrue(p.wantsCheap());
        assertNull(p.maxPrice());
        assertEquals(java.util.List.of("wireless", "earbud", "battery", "life"), p.tokens());
    }
    @Test void handlesTakaSignCommasAndK() {
        assertEquals(2500.0, QueryParser.parse("backpack below ৳2,500").maxPrice());
        var r = QueryParser.parse("watch between 1k and 5k");
        assertEquals(1000.0, r.minPrice());
        assertEquals(5000.0, r.maxPrice());
        assertEquals(2000.0, QueryParser.parse("headphones over 2000 tk").minPrice());
        assertEquals(1500.0, QueryParser.parse("shirt 1500 tk").maxPrice());
    }
    @Test void sanitizesAndCapsInput() {
        assertEquals(QueryParser.MAX_QUERY_CHARS, QueryParser.sanitize("x".repeat(500)).length());
        assertEquals("a b c", QueryParser.sanitize("a\u0000b\n\t c"));
        assertEquals("", QueryParser.sanitize(null));
    }
    @Test void blackShoesForOffice() {
        assertEquals(java.util.List.of("black", "shoe", "office"), QueryParser.parse("Black shoes suitable for office").tokens());
    }
}

package com.pabonaki.ai;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {
    @Test void blocksAfterLimitAndResetsAfterWindow() {
        long[] now = {0};
        RateLimiter r = new RateLimiter(() -> now[0]);
        assertTrue(r.tryAcquire("a", 2, 1000));
        assertTrue(r.tryAcquire("a", 2, 1000));
        assertFalse(r.tryAcquire("a", 2, 1000));
        assertTrue(r.tryAcquire("b", 2, 1000));      // other callers are independent
        now[0] = 1500;
        assertTrue(r.tryAcquire("a", 2, 1000));
    }
}

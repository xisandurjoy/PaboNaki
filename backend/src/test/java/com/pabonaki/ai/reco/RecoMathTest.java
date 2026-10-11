package com.pabonaki.ai.reco;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RecoMathTest {
    @Test void purchasesOutweighCartsOutweighViews() {
        assertTrue(RecoMath.weight("PURCHASE") > RecoMath.weight("CART_ADD"));
        assertTrue(RecoMath.weight("CART_ADD") > RecoMath.weight("VIEW"));
        assertEquals(0.0, RecoMath.weight("bogus"), 1e-9);
    }
    @Test void recentActivityCountsMore() {
        assertEquals(0.5, RecoMath.decay(RecoMath.HALF_LIFE_DAYS), 1e-9);
        assertEquals(1.0, RecoMath.decay(0), 1e-9);
        assertTrue(RecoMath.decay(2) > RecoMath.decay(30));
    }
    @Test void pricePreferenceIsSymmetricAndBounded() {
        assertEquals(1.0, RecoMath.pricePreference(1000, 1000), 1e-9);
        assertEquals(RecoMath.pricePreference(500, 1000), RecoMath.pricePreference(2000, 1000), 1e-9);
        assertEquals(0.0, RecoMath.pricePreference(8000, 1000), 1e-9);
        assertEquals(0.5, RecoMath.pricePreference(0, 1000), 1e-9);
    }
    @Test void scoresRankSensibly() {
        assertTrue(RecoMath.personalScore(0, 1, 0, 0, false) > RecoMath.personalScore(0, 0, 1, 0, false));
        assertTrue(RecoMath.similarScore(0.9, false, 0.5, 0.5, true) > RecoMath.similarScore(0.2, true, 0.5, 0.5, true));
        assertTrue(RecoMath.popularityRaw(3, 0, 0, 0) > RecoMath.popularityRaw(0, 0, 0, 3));
    }
}

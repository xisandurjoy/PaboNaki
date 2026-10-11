package com.pabonaki.ai.reco;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ActivityValidationTest {
    @Test void sessionIdsMustBeRandomLookingTokens() {
        assertTrue(ActivityController.SESSION.matcher("123e4567-e89b-12d3-a456-426614174000").matches());
        assertFalse(ActivityController.SESSION.matcher("short").matches());
        assertFalse(ActivityController.SESSION.matcher("../../etc/passwd-aaaaaaaaaaaa").matches());
        assertFalse(ActivityController.SESSION.matcher("{\"$ne\":null}aaaaaaaaaaaaaaaa").matches());
    }
    @Test void productIdsCannotCarryOperators() {
        assertTrue(ActivityController.PRODUCT_ID.matcher("66f1a2b3c4d5e6f708192a3b").matches());
        assertFalse(ActivityController.PRODUCT_ID.matcher("{\"$gt\":\"\"}").matches());
        assertFalse(ActivityController.PRODUCT_ID.matcher("a b").matches());
    }
    @Test void onlyKnownActivityTypes() {
        assertTrue(ActivityController.TYPES.contains("CART_ADD"));
        assertFalse(ActivityController.TYPES.contains("PURCHASE"));   // purchases come from real orders, never from the browser
    }
}

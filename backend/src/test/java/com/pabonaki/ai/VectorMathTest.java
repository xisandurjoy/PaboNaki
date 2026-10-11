package com.pabonaki.ai;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VectorMathTest {
    @Test void cosine() {
        assertEquals(1.0, VectorMath.cosine(new double[]{1, 0}, new double[]{2, 0}), 1e-9);
        assertEquals(0.0, VectorMath.cosine(new double[]{1, 0}, new double[]{0, 1}), 1e-9);
        assertEquals(0.0, VectorMath.cosine(new double[]{1, 0}, new double[]{1, 0, 0}), 1e-9);   // dimension mismatch is never a match
    }
    @Test void normalize() {
        assertEquals(1.0, VectorMath.norm(VectorMath.normalize(new double[]{3, 4})), 1e-9);
        assertNull(VectorMath.normalize(new double[]{0, 0}));
    }
}

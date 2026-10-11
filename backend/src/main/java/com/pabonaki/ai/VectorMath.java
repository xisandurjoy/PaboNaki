package com.pabonaki.ai;

/** Small vector helpers. */
public final class VectorMath {
    private VectorMath() {}

    public static double dot(double[] a, double[] b) {
        int n = Math.min(a.length, b.length);
        double s = 0;
        for (int i = 0; i < n; i++) s += a[i] * b[i];
        return s;
    }

    public static double norm(double[] a) { return Math.sqrt(dot(a, a)); }

    public static double cosine(double[] a, double[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) return 0;
        double na = norm(a), nb = norm(b);
        return na == 0 || nb == 0 ? 0 : dot(a, b) / (na * nb);
    }

    /** Returns a unit-length copy, or null when the vector is empty or all zeros. */
    public static double[] normalize(double[] a) {
        if (a == null || a.length == 0) return null;
        double n = norm(a);
        if (n == 0 || Double.isNaN(n) || Double.isInfinite(n)) return null;
        double[] out = new double[a.length];
        for (int i = 0; i < a.length; i++) out[i] = a[i] / n;
        return out;
    }
}

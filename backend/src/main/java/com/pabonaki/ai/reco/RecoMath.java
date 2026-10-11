package com.pabonaki.ai.reco;

import java.util.Locale;

/** Pure scoring functions for the recommendation engine (no Spring, no database) so they can be unit tested and later swapped for an ML model. */
public final class RecoMath {
    private RecoMath() {}

    public static final double HALF_LIFE_DAYS = 21.0;

    /** How much one interaction counts. Purchases are the strongest signal, page views the weakest. */
    public static double weight(String type) {
        if (type == null) return 0;
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "PURCHASE" -> 5.0;
            case "CART_ADD" -> 3.0;
            case "WISHLIST_ADD" -> 3.0;
            case "VIEW" -> 1.0;
            case "SEARCH" -> 0.8;
            default -> 0;
        };
    }

    /** Exponential recency decay: an interaction loses half its weight every HALF_LIFE_DAYS days. */
    public static double decay(double ageDays) {
        return Math.pow(0.5, Math.max(0, ageDays) / HALF_LIFE_DAYS);
    }

    /** 1 when prices match, falling towards 0 as the ratio grows (symmetric on a log scale). */
    public static double pricePreference(double price, double reference) {
        if (price <= 0 || reference <= 0) return 0.5;
        return Math.max(0, 1 - Math.min(1, Math.abs(Math.log(price / reference)) / Math.log(4)));
    }

    /** Personalised ranking score. All inputs are in 0..1. */
    public static double personalScore(double content, double category, double popularity, double price, boolean hasVectors) {
        return hasVectors
                ? 0.45 * content + 0.30 * category + 0.15 * popularity + 0.10 * price
                : 0.55 * category + 0.25 * popularity + 0.20 * price;
    }

    /** "Similar products" ranking score. All inputs are in 0..1. */
    public static double similarScore(double content, boolean sameCategory, double price, double popularity, boolean hasVectors) {
        double cat = sameCategory ? 1 : 0;
        return hasVectors
                ? 0.55 * content + 0.20 * cat + 0.15 * price + 0.10 * popularity
                : 0.50 * cat + 0.25 * price + 0.25 * popularity;
    }

    /** log-scaled popularity from weighted interaction counts, later divided by the maximum to land in 0..1. */
    public static double popularityRaw(double orderQty, double carts, double wishes, double views) {
        return Math.log1p(5 * orderQty + 3 * carts + 3 * wishes + views);
    }
}

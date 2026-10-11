package com.pabonaki.ai;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Derived search data for one product, stored in its own collection so the products collection is never modified.
 * The document _id equals the product id. Safe to delete: the indexer rebuilds it.
 */
@Document("product_embeddings")
public class ProductEmbedding {
    public static final String IMG_OK = "OK", IMG_NO_IMAGE = "NO_IMAGE", IMG_UNSUPPORTED = "UNSUPPORTED", IMG_FAILED = "FAILED";

    @Id public String id;

    public String textHash;
    public double[] textVector;
    public Instant textUpdatedAt;

    public String imageHash;
    public double[] imageVector;
    public String imageStatus;
    public String imageError;
    public int imageFailures;
    public Instant imageUpdatedAt;

    /** Mirrors the product status so Atlas $vectorSearch can pre-filter. The live product is always re-checked after a search. */
    public boolean active;
    public String category;
    public String model;
    public Instant updatedAt;
}

package com.pabonaki.ai;

/** Abstraction over the embedding service so it can be swapped (self-hosted model, another vendor) or mocked in tests. */
public interface EmbeddingProvider {
    /** False when the feature is disabled or no API key is configured. */
    boolean isAvailable();

    /** Embeds a search query using the provider's query format. */
    double[] embedQuery(String text);

    /** Embeds catalogue text (title + description) using the provider's document format. */
    double[] embedDocument(String title, String text);

    /** Embeds a JPEG/PNG image. */
    double[] embedImage(byte[] bytes, String mimeType);

    /** Identifies model + dimensions; stored with each vector so a model change triggers a re-index. */
    String fingerprint();

    /** Embedding calls made today (UTC), or -1 when the provider does not track usage. */
    default int usedToday() { return -1; }
}

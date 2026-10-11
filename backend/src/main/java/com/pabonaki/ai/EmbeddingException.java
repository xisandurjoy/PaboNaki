package com.pabonaki.ai;

public class EmbeddingException extends RuntimeException {
    public enum Kind { NOT_CONFIGURED, BUDGET_EXCEEDED, RATE_LIMITED, TIMEOUT, BAD_INPUT, PROVIDER_ERROR }

    private final Kind kind;

    public EmbeddingException(Kind kind, String message) { super(message); this.kind = kind; }
    public EmbeddingException(Kind kind, String message, Throwable cause) { super(message, cause); this.kind = kind; }

    public Kind kind() { return kind; }

    /** True when retrying later may succeed (so an indexer should stop its batch rather than mark the product failed). */
    public boolean isTransient() { return kind == Kind.RATE_LIMITED || kind == Kind.TIMEOUT || kind == Kind.BUDGET_EXCEEDED || kind == Kind.PROVIDER_ERROR; }
}

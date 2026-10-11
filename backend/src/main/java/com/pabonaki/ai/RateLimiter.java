package com.pabonaki.ai;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Fixed-window, in-memory rate limiter. Single-instance only; use a shared store if you scale horizontally. */
public class RateLimiter {
    private static final class Window { long start; int count; }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final LongSupplier clock;

    public RateLimiter() { this(System::currentTimeMillis); }
    public RateLimiter(LongSupplier clock) { this.clock = clock; }

    /** @return true when the call is allowed, false when the caller exceeded {@code limit} calls per {@code windowMs}. */
    public boolean tryAcquire(String key, int limit, long windowMs) {
        long now = clock.getAsLong();
        if (windows.size() > 20_000) windows.entrySet().removeIf(e -> now - e.getValue().start > windowMs * 2);
        Window w = windows.computeIfAbsent(key, k -> { Window n = new Window(); n.start = now; return n; });
        synchronized (w) {
            if (now - w.start >= windowMs) { w.start = now; w.count = 0; }
            if (w.count >= limit) return false;
            w.count++;
            return true;
        }
    }
}

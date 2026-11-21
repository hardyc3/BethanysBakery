package com.bethanysbakery.api.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Sliding-window in-memory limiter. Single-instance only; multi-instance scaling is a non-goal. */
@Component
public class RateLimiter {
    private final ConcurrentHashMap<String, Deque<Instant>> buckets = new ConcurrentHashMap<>();
    private final Clock clock;

    public RateLimiter(Clock clock) {
        this.clock = clock;
    }

    public Result tryAcquire(String key, int limit, Duration window) {
        Instant now = clock.instant();
        Instant cutoff = now.minus(window);
        Deque<Instant> bucket = buckets.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (bucket) {
            while (!bucket.isEmpty() && bucket.peekFirst().isBefore(cutoff)) {
                bucket.pollFirst();
            }
            if (bucket.size() >= limit) {
                Instant oldest = bucket.peekFirst();
                long retryAfter = Math.max(1, Duration.between(now, oldest.plus(window)).getSeconds());
                if (bucket.isEmpty()) {
                    buckets.remove(key, bucket);
                }
                return new Result(false, retryAfter);
            }
            bucket.addLast(now);
            return new Result(true, 0);
        }
    }

    /** Removes all windows. Intended for test isolation between integration cases. */
    public void clear() {
        buckets.clear();
    }

    public record Result(boolean allowed, long retryAfterSeconds) {}
}

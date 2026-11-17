package com.bethanysbakery.api.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class RateLimiter {
    private final ConcurrentHashMap<String, Deque<Instant>> buckets = new ConcurrentHashMap<>();

    public Result tryAcquire(String key, int limit, Duration window) {
        Instant now = Instant.now();
        Instant cutoff = now.minus(window);
        Deque<Instant> bucket = buckets.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (bucket) {
            while (!bucket.isEmpty() && bucket.peekFirst().isBefore(cutoff)) {
                bucket.pollFirst();
            }
            if (bucket.size() >= limit) {
                Instant oldest = bucket.peekFirst();
                long retryAfter = Math.max(1, Duration.between(now, oldest.plus(window)).getSeconds());
                return new Result(false, retryAfter);
            }
            bucket.addLast(now);
            return new Result(true, 0);
        }
    }

    public record Result(boolean allowed, long retryAfterSeconds) {}
}

package com.bethanysbakery.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private final RateLimiter limiter = new RateLimiter();

    @Test
    void allowsRequestsUpToTheLimitThenRejectsWithRetryAfter() {
        Duration window = Duration.ofMinutes(15);

        assertThat(limiter.tryAcquire("ip:1", 3, window).allowed()).isTrue();
        assertThat(limiter.tryAcquire("ip:1", 3, window).allowed()).isTrue();
        assertThat(limiter.tryAcquire("ip:1", 3, window).allowed()).isTrue();

        RateLimiter.Result rejected = limiter.tryAcquire("ip:1", 3, window);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.retryAfterSeconds()).isPositive().isLessThanOrEqualTo(window.toSeconds());
    }

    @Test
    void scopesLimitsPerKey() {
        Duration window = Duration.ofHours(1);

        assertThat(limiter.tryAcquire("otp:ip:1", 1, window).allowed()).isTrue();
        assertThat(limiter.tryAcquire("otp:ip:1", 1, window).allowed()).isFalse();
        assertThat(limiter.tryAcquire("otp:ip:2", 1, window).allowed()).isTrue();
    }
}

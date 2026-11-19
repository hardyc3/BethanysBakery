package com.bethanysbakery.api.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private static final Instant START = Instant.parse("2026-11-20T12:00:00Z");

    @Test
    void slidingWindowUsesInjectedClockAndReportsRetryAfter() {
        MutableClock clock = new MutableClock(START);
        RateLimiter limiter = new RateLimiter(clock);
        Duration window = Duration.ofMinutes(15);
        assertThat(limiter.tryAcquire("otp:ip:1", 3, window).allowed()).isTrue();
        assertThat(limiter.tryAcquire("otp:ip:1", 3, window).allowed()).isTrue();
        assertThat(limiter.tryAcquire("otp:ip:1", 3, window).allowed()).isTrue();
        RateLimiter.Result blocked = limiter.tryAcquire("otp:ip:1", 3, window);
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.retryAfterSeconds()).isEqualTo(900);

        clock.advance(Duration.ofSeconds(901));
        assertThat(limiter.tryAcquire("otp:ip:1", 3, window).allowed()).isTrue();
    }

    @Test
    void unrelatedKeysHaveIndependentBudgets() {
        RateLimiter limiter = new RateLimiter(new MutableClock(START));
        Duration window = Duration.ofHours(1);
        assertThat(limiter.tryAcquire("a", 1, window).allowed()).isTrue();
        assertThat(limiter.tryAcquire("a", 1, window).allowed()).isFalse();
        assertThat(limiter.tryAcquire("b", 1, window).allowed()).isTrue();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}

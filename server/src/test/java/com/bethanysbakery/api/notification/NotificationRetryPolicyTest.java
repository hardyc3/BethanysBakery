package com.bethanysbakery.api.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class NotificationRetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");

    @Test
    void backoffsFollowSpecifiedSequence() {
        assertThat(NotificationRetryPolicy.nextAttempt(1, NOW)).isEqualTo(NOW.plus(Duration.ofMinutes(1)));
        assertThat(NotificationRetryPolicy.nextAttempt(2, NOW)).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(NotificationRetryPolicy.nextAttempt(3, NOW)).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(NotificationRetryPolicy.nextAttempt(4, NOW)).isEqualTo(NOW.plus(Duration.ofHours(1)));
        assertThat(NotificationRetryPolicy.nextAttempt(5, NOW)).isEqualTo(NOW.plus(Duration.ofHours(6)));
    }

    @Test
    void sixthFailureLeavesPermanentlyFailed() {
        assertThat(NotificationRetryPolicy.nextAttempt(6, NOW)).isNull();
        assertThat(NotificationRetryPolicy.nextAttempt(99, NOW)).isNull();
    }
}

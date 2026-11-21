package com.bethanysbakery.api.notification;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Exponential backoff for failed provider calls; no next attempt after the last retry. */
public final class NotificationRetryPolicy {

    private static final List<Duration> BACKOFFS = List.of(
        Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15),
        Duration.ofHours(1), Duration.ofHours(6));

    private NotificationRetryPolicy() {}

    /** @param failures number of failures so far (1-based); null result means FAILED permanently. */
    public static Instant nextAttempt(int failures, Instant failedAt) {
        if (failures < 1 || failures > BACKOFFS.size()) {
            return null;
        }
        return failedAt.plus(BACKOFFS.get(failures - 1));
    }
}

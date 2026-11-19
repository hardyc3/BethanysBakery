package com.bethanysbakery.api.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class SmsWindowTest {

    private static final ZoneId DENVER = ZoneId.of("America/Denver");

    @Test
    void promotionalSmsAllowedBetweenNineAndEightDenver() {
        assertThat(SmsWindow.allowed(Instant.parse("2026-11-18T16:00:00Z"), DENVER)).isTrue(); // 09:00 MST
        assertThat(SmsWindow.allowed(Instant.parse("2026-11-19T02:59:00Z"), DENVER)).isTrue(); // 19:59 MST
        assertThat(SmsWindow.allowed(Instant.parse("2026-11-18T15:59:00Z"), DENVER)).isFalse(); // 08:59 MST
        assertThat(SmsWindow.allowed(Instant.parse("2026-11-19T03:00:00Z"), DENVER)).isFalse(); // 20:00 MST
    }

    @Test
    void outsideWindowDefersToNextNineDenver() {
        Instant before = Instant.parse("2026-11-18T15:00:00Z"); // 08:00 MST
        assertThat(SmsWindow.nextAllowed(before, DENVER)).isEqualTo(Instant.parse("2026-11-18T16:00:00Z"));

        Instant after = Instant.parse("2026-11-19T04:00:00Z"); // 21:00 MST
        assertThat(SmsWindow.nextAllowed(after, DENVER)).isEqualTo(Instant.parse("2026-11-19T16:00:00Z"));
    }

    @Test
    void nextAllowedKeepsNineLocalAcrossDstTransitions() {
        assertThat(SmsWindow.nextAllowed(Instant.parse("2026-03-08T04:00:00Z"), DENVER))
            .isEqualTo(Instant.parse("2026-03-08T15:00:00Z")); // spring forward
        assertThat(SmsWindow.nextAllowed(Instant.parse("2026-11-01T03:00:00Z"), DENVER))
            .isEqualTo(Instant.parse("2026-11-01T16:00:00Z")); // fall back
    }

    @Test
    void alreadyAllowedReturnsSameInstant() {
        Instant inside = Instant.parse("2026-11-18T18:00:00Z");
        assertThat(SmsWindow.nextAllowed(inside, DENVER)).isEqualTo(inside);
    }

    @Test
    void suppressionDetectsPassedBakeDate() {
        assertThat(SmsWindow.bakeDatePassed(LocalDate.of(2026, 11, 18),
            Instant.parse("2026-11-19T15:00:00Z"), DENVER)).isTrue();
        assertThat(SmsWindow.bakeDatePassed(LocalDate.of(2026, 11, 19),
            Instant.parse("2026-11-19T15:00:00Z"), DENVER)).isFalse();
    }
}

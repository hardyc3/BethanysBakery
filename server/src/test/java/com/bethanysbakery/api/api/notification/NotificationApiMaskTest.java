package com.bethanysbakery.api.api.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NotificationApiMaskTest {

    @Test
    void masksEmailAndPhoneWithoutLeavingFullDestinations() {
        assertThat(NotificationApi.Mask.email("bethany@example.com")).isEqualTo("be***@example.com");
        assertThat(NotificationApi.Mask.email("b@example.com")).isEqualTo("b***@example.com");
        assertThat(NotificationApi.Mask.phone("+15551234567")).isEqualTo("***4567");
        assertThat(NotificationApi.Mask.phone("1234")).isEqualTo("***");
        assertThat(NotificationApi.Mask.destination("EMAIL", "bethany@example.com")).isEqualTo("be***@example.com");
        assertThat(NotificationApi.Mask.destination("SMS", "+15551234567")).isEqualTo("***4567");
    }

    @Test
    void masksMissingAndUnknownDestinations() {
        assertThat(NotificationApi.Mask.email(null)).isEqualTo("***");
        assertThat(NotificationApi.Mask.phone("")).isEqualTo("***");
        assertThat(NotificationApi.Mask.destination("WEBHOOK", "secret-token-value")).isEqualTo("***alue");
        assertThat(NotificationApi.Mask.destination("EMAIL", null)).isEqualTo("***");
    }
}

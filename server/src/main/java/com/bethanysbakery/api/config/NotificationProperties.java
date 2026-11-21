package com.bethanysbakery.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Provider credentials and scheduling switch for S6. All secrets come from env. */
@Validated
@ConfigurationProperties(prefix = "bakery.notifications")
public record NotificationProperties(
    boolean schedulingEnabled,
    String adminAlertEmail,
    Mailgun mailgun,
    Textbee textbee,
    Fcm fcm
) {

    public record Mailgun(String apiKey, String domain, String apiBase, String from) {
        public String apiBase() {
            return apiBase == null || apiBase.isBlank() ? "https://api.mailgun.net/v3" : apiBase;
        }
    }

    public record Textbee(String baseUrl, String apiKey, String deviceId, String webhookSecret) {}

    public record Fcm(String projectId, String serviceAccountJson) {}

    public boolean mailgunConfigured() {
        return mailgun != null && has(mailgun.apiKey()) && has(mailgun.domain()) && has(mailgun.from());
    }

    public boolean textbeeConfigured() {
        return textbee != null && has(textbee.baseUrl()) && has(textbee.apiKey()) && has(textbee.deviceId());
    }

    public boolean fcmConfigured() {
        return fcm != null && has(fcm.projectId()) && has(fcm.serviceAccountJson());
    }

    public boolean webhookSecretConfigured() {
        return textbee != null && has(textbee.webhookSecret());
    }

    private static boolean has(String value) {
        return value != null && !value.isBlank();
    }
}

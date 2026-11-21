package com.bethanysbakery.api.api.notification;

import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.domain.notification.NotificationStatus;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** S6 request/response DTOs for preferences, webhook, admin queue, and payment/delay. */
public final class NotificationApi {

    private NotificationApi() {}

    public record NotificationPreferenceView(String eventType, boolean emailEnabled, boolean smsEnabled) {}

    public record NotificationPreferencesView(boolean smsConsent, boolean smsOptedOut,
            List<NotificationPreferenceView> preferences) {}

    public record NotificationPreferenceWrite(String eventType,
            @NotNull Boolean emailEnabled, @NotNull Boolean smsEnabled) {}

    public record NotificationPreferencesWrite(@NotNull Boolean smsConsent,
            List<NotificationPreferenceWrite> preferences) {}

    public record NotificationDeliveryView(String channel, String destinationMasked, String status,
            String providerMessageId, int attempts, String lastError, Instant sentAt) {}

    public record NotificationView(UUID id, String recipientType, String eventType, String entityType,
            UUID entityId, String status, Instant createdAt, Instant scheduledAt, Instant sentAt,
            int attempts, String lastError, List<NotificationDeliveryView> deliveries) {}

    public record NotificationList(List<NotificationView> items, long total) {}

    public record PaymentUpdateRequest(@NotNull Boolean paid, String method) {}

    public record DelayOrderRequest(String reason) {}

    public record TextbeeWebhook(String from, String message, String providerMessageId, Instant receivedAt) {}

    public record PushTokenRequest(String token, String appVersion) {}

    public static NotificationView view(com.bethanysbakery.api.domain.notification.Notification notification) {
        List<NotificationDeliveryView> deliveries = notification.getDeliveries().stream()
            .map(delivery -> new NotificationDeliveryView(
                delivery.getChannel().name(),
                Mask.destination(delivery.getChannel().name(), delivery.getDestination()),
                delivery.getStatus().name(),
                delivery.getProviderMessageId(),
                delivery.getAttempts(),
                delivery.getLastError(),
                delivery.getSentAt()))
            .toList();
        return new NotificationView(
            notification.getId(),
            notification.getRecipientType().name(),
            notification.getEventType().name(),
            notification.getEntityType(),
            notification.getEntityId(),
            notification.getStatus().name(),
            notification.getCreatedAt(),
            notification.getScheduledAt(),
            notification.getSentAt(),
            notification.getAttempts(),
            notification.getLastError(),
            deliveries);
    }

    public static NotificationStatus status(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return NotificationStatus.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw AuthException.badRequest("VALIDATION_ERROR", "Invalid notification status");
        }
    }

    /** Masks destinations in admin API responses and logs. */
    public static final class Mask {
        private Mask() {}

        public static String destination(String channel, String value) {
            if (value == null || value.isBlank()) {
                return "***";
            }
            return switch (channel) {
                case "EMAIL" -> email(value);
                case "SMS" -> phone(value);
                default -> token(value);
            };
        }

        public static String email(String value) {
            int at = value.indexOf('@');
            if (at <= 0) {
                return "***";
            }
            String name = value.substring(0, at);
            String prefix = name.substring(0, Math.min(2, name.length()));
            return prefix + "***" + value.substring(at);
        }

        public static String phone(String value) {
            return value.length() <= 4 ? "***" : "***" + value.substring(value.length() - 4);
        }

        public static String token(String value) {
            return value.length() <= 4 ? "***" : "***" + value.substring(value.length() - 4);
        }
    }
}

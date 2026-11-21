package com.bethanysbakery.api.notification;

import java.util.Map;

public final class NotificationMessages {

    private NotificationMessages() {}

    public record EmailMessage(String to, String subject, String htmlBody, String textBody) {}

    public record SmsMessage(String to, String body) {}

    public record PushMessage(String token, String title, String body, Map<String, String> data) {
        public PushMessage(String token, String title, String body) {
            this(token, title, body, Map.of());
        }
    }

    public record DeliveryResult(boolean success, String providerMessageId, String error) {
        public static DeliveryResult sent(String id) { return new DeliveryResult(true, id, null); }
        public static DeliveryResult failed(String error) { return new DeliveryResult(false, null, error); }
    }
}

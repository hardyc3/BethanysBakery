package com.bethanysbakery.api.notification;

public final class NotificationMessages {

    private NotificationMessages() {}

    public record EmailMessage(String to, String subject, String htmlBody, String textBody) {}

    public record SmsMessage(String to, String body) {}

    public record DeliveryResult(boolean success, String providerMessageId, String error) {
        public static DeliveryResult sent(String id) { return new DeliveryResult(true, id, null); }
        public static DeliveryResult failed(String error) { return new DeliveryResult(false, null, error); }
    }
}

package com.bethanysbakery.api.notification;

import java.util.UUID;

/** Single source of truth for S6-owned notification idempotency keys (at-least-once delivery). */
public final class NotificationKeys {

    private NotificationKeys() {}

    // S5's "order:{orderId}:created" key is owned by order.OutboxWriter and reused verbatim.

    public static String menuPublished(UUID menuId) {
        return "MENU_PUBLISHED:" + menuId;
    }

    public static String reminder(UUID menuDateId, UUID customerId) {
        return "ORDER_REMINDER:" + menuDateId + ":" + customerId;
    }

    public static String orderEvent(String eventType, UUID orderId) {
        return eventType + ":" + orderId;
    }

    public static String paid(UUID orderId) {
        return "order:" + orderId + ":paid";
    }

    public static String menuCancelled(UUID menuIdOrDateId, UUID customerId) {
        return "MENU_CANCELLED:" + menuIdOrDateId + ":" + customerId;
    }

    public static String adminNewOrderEmail(UUID orderId) {
        return "ADMIN_NEW_ORDER_EMAIL:" + orderId;
    }

    public static String adminNewOrderSms(UUID orderId) {
        return "ADMIN_NEW_ORDER_SMS:" + orderId;
    }
}

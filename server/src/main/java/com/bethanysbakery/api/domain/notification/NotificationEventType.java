package com.bethanysbakery.api.domain.notification;

/** Logical message types. ORDER_PAID is admin-only and has no customer preference row. */
public enum NotificationEventType {
    MENU_PUBLISHED, ORDER_REMINDER, ORDER_ACCEPTED, ORDER_READY, ORDER_DELAYED, ORDER_REJECTED,
    ORDER_CANCELLED, ORDER_EXPIRED, MENU_CANCELLED, ORDER_PAID, ADMIN_NEW_ORDER
}

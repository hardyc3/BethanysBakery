package com.bethanysbakery.api.notification;

import com.bethanysbakery.api.domain.notification.NotificationEventType;
import com.bethanysbakery.api.domain.order.OrderStatus;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/** Shared predicates used by both queueing and dispatch so their rules cannot drift. */
final class NotificationRules {

    private NotificationRules() {}

    static final List<OrderStatus> ACTIVE_ORDER_STATUSES =
        List.of(OrderStatus.PENDING, OrderStatus.ACCEPTED, OrderStatus.READY, OrderStatus.COMPLETED);

    static boolean promotional(NotificationEventType type) {
        return type == NotificationEventType.MENU_PUBLISHED || type == NotificationEventType.ORDER_REMINDER;
    }

    static LocalDate bakeDate(Map<String, Object> payload) {
        Object value = payload.get("bakeDate");
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value.toString());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    static ZoneId zone(String timezone) {
        return ZoneId.of(timezone);
    }
}

package com.bethanysbakery.api.notification;

import com.bethanysbakery.api.domain.notification.NotificationEventType;
import com.bethanysbakery.api.domain.notification.NotificationPreference;
import com.bethanysbakery.api.domain.notification.NotificationPreferenceRepository;
import com.bethanysbakery.api.domain.order.OrderStatus;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Shared predicates used by queueing, preview, and dispatch so their rules cannot drift. */
final class NotificationRules {

    private NotificationRules() {}

    static final List<OrderStatus> ACTIVE_ORDER_STATUSES =
        List.of(OrderStatus.PENDING, OrderStatus.ACCEPTED, OrderStatus.READY, OrderStatus.COMPLETED);

    private static final List<NotificationEventType> CUSTOMER_EVENTS = List.of(
        NotificationEventType.MENU_PUBLISHED, NotificationEventType.ORDER_REMINDER,
        NotificationEventType.ORDER_ACCEPTED, NotificationEventType.ORDER_READY,
        NotificationEventType.ORDER_DELAYED, NotificationEventType.ORDER_REJECTED,
        NotificationEventType.ORDER_CANCELLED, NotificationEventType.ORDER_EXPIRED,
        NotificationEventType.MENU_CANCELLED);
    private static final Map<NotificationEventType, boolean[]> DEFAULTS = defaults();

    static List<NotificationEventType> customerEvents() {
        return CUSTOMER_EVENTS;
    }

    static boolean[] preference(NotificationPreferenceRepository preferences, UUID customerId,
            NotificationEventType type) {
        return preferences.findByCustomerIdAndEventType(customerId, type)
            .map(pref -> new boolean[] {pref.isEmailEnabled(), pref.isSmsEnabled()})
            .orElseGet(() -> DEFAULTS.getOrDefault(type, new boolean[] {true, false}));
    }

    static boolean isOptedIn(NotificationPreferenceRepository preferences, UUID customerId,
            NotificationEventType type) {
        boolean[] value = preference(preferences, customerId, type);
        return value[0] || value[1];
    }

    /** Explicit all-off PUT: every customer event must be stored with both channels disabled. */
    static boolean hasAllNotificationsDisabled(NotificationPreferenceRepository preferences, UUID customerId) {
        Map<NotificationEventType, NotificationPreference> stored = new EnumMap<>(NotificationEventType.class);
        preferences.findAllByCustomerId(customerId).forEach(pref -> stored.put(pref.getEventType(), pref));
        for (NotificationEventType type : customerEvents()) {
            NotificationPreference pref = stored.get(type);
            if (pref == null || pref.isEmailEnabled() || pref.isSmsEnabled()) {
                return false;
            }
        }
        return true;
    }

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

    private static Map<NotificationEventType, boolean[]> defaults() {
        Map<NotificationEventType, boolean[]> values = new EnumMap<>(NotificationEventType.class);
        values.put(NotificationEventType.MENU_PUBLISHED, new boolean[] {true, false});
        values.put(NotificationEventType.ORDER_REMINDER, new boolean[] {true, false});
        values.put(NotificationEventType.ORDER_ACCEPTED, new boolean[] {true, true});
        values.put(NotificationEventType.ORDER_READY, new boolean[] {true, true});
        values.put(NotificationEventType.ORDER_DELAYED, new boolean[] {true, true});
        values.put(NotificationEventType.ORDER_REJECTED, new boolean[] {true, true});
        values.put(NotificationEventType.ORDER_CANCELLED, new boolean[] {true, true});
        values.put(NotificationEventType.ORDER_EXPIRED, new boolean[] {true, false});
        values.put(NotificationEventType.MENU_CANCELLED, new boolean[] {true, true});
        return Collections.unmodifiableMap(values);
    }
}

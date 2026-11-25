package com.bethanysbakery.api.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.bethanysbakery.api.domain.notification.NotificationEventType;
import com.bethanysbakery.api.domain.order.OrderStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationRulesTest {

    @Test
    void customerEventsAreStableAndExcludeAdminEvents() {
        assertThat(NotificationRules.customerEvents()).containsExactly(
            NotificationEventType.MENU_PUBLISHED, NotificationEventType.ORDER_REMINDER,
            NotificationEventType.ORDER_ACCEPTED, NotificationEventType.ORDER_READY,
            NotificationEventType.ORDER_DELAYED, NotificationEventType.ORDER_REJECTED,
            NotificationEventType.ORDER_CANCELLED, NotificationEventType.ORDER_EXPIRED,
            NotificationEventType.MENU_CANCELLED);
        assertThat(NotificationRules.customerEvents()).doesNotContain(
            NotificationEventType.ORDER_PAID, NotificationEventType.ADMIN_NEW_ORDER);
    }

    @Test
    void activeOrderStatusesIncludeCompletedForCancellationAudience() {
        assertThat(NotificationRules.ACTIVE_ORDER_STATUSES).isEqualTo(List.of(
            OrderStatus.PENDING, OrderStatus.ACCEPTED, OrderStatus.READY, OrderStatus.COMPLETED));
    }
}

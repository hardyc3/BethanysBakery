package com.bethanysbakery.api.notification;

import com.bethanysbakery.api.config.BakeryProperties;
import com.bethanysbakery.api.domain.menu.MenuDate;
import com.bethanysbakery.api.domain.notification.NotificationEventType;
import com.bethanysbakery.api.domain.order.Actor;
import com.bethanysbakery.api.domain.order.Order;
import com.bethanysbakery.api.order.OutboxWriter;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** S6 replacement for the S5 logging publisher: enqueue durable notifications in-transaction. */
@Component
public class NotificationOrderEventPublisher implements com.bethanysbakery.api.order.OrderEventPublisher {

    private final NotificationService notifications;
    private final OutboxWriter outbox;
    private final NotificationAudienceResolver audience;
    private final BakeryProperties bakery;
    private final Clock clock;

    public NotificationOrderEventPublisher(NotificationService notifications, OutboxWriter outbox,
            NotificationAudienceResolver audience, BakeryProperties bakery, Clock clock) {
        this.notifications = notifications;
        this.outbox = outbox;
        this.audience = audience;
        this.bakery = bakery;
        this.clock = clock;
    }

    @Override
    public void orderCreated(Order order) {
        // S5 already wrote order:{id}:created in the order transaction; the outbox processor handles it.
    }

    @Override
    public void orderAccepted(Order order) {
        customerEvent(order, NotificationEventType.ORDER_ACCEPTED);
    }

    @Override
    public void orderRejected(Order order, String reason) {
        customerEvent(order, NotificationEventType.ORDER_REJECTED);
    }

    @Override
    public void orderReady(Order order) {
        customerEvent(order, NotificationEventType.ORDER_READY);
    }

    @Override
    public void orderCompleted(Order order) {
        // No completion message in the contract; the order state is visible in the app.
    }

    @Override
    public void orderCancelled(Order order, String reason, Actor actor) {
        customerEvent(order, NotificationEventType.ORDER_CANCELLED);
    }

    @Override
    public void orderExpired(Order order) {
        customerEvent(order, NotificationEventType.ORDER_EXPIRED);
    }

    @Override
    public void menuDateCancelled(MenuDate date, String reason) {
        Instant now = clock.instant();
        Map<String, Object> payload = NotificationPayloads.forMenuCancelled("MENU_DATE", date.getId(),
            date.getBakeDate(), baseUrl());
        for (NotificationAudienceResolver.Recipient recipient
                : audience.resolve(NotificationEventType.MENU_CANCELLED, "MENU_DATE", date.getId(), payload)) {
            UUID customerId = recipient.customerId();
            String key = NotificationKeys.menuCancelled(date.getId(), customerId);
            outbox.write("MENU_DATE", date.getId(), "MENU_CANCELLED",
                Map.of("menuDateId", date.getId().toString(), "customerId", customerId.toString()),
                key, now);
            notifications.enqueueCustomer(customerId, NotificationEventType.MENU_CANCELLED, "MENU_DATE",
                date.getId(), key, payload, now, recipient.bypassPreferences());
        }
    }

    private void customerEvent(Order order, NotificationEventType type) {
        Instant now = clock.instant();
        String key = NotificationKeys.orderEvent(type.name(), order.getId());
        outbox.write("ORDER", order.getId(), type.name(), Map.of("orderId", order.getId().toString()), key, now);
        notifications.enqueueCustomer(order.getCustomerId(), type, "ORDER", order.getId(), key,
            NotificationPayloads.forOrder(order, baseUrl(), bakery.venmo().handle(),
                bakery.venmo().displayName()), now);
    }

    private String baseUrl() {
        return bakery.auth().appPublicBaseUrl();
    }
}

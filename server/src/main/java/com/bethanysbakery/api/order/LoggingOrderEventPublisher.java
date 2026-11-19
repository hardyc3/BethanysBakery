package com.bethanysbakery.api.order;

import com.bethanysbakery.api.domain.menu.MenuDate;
import com.bethanysbakery.api.domain.order.Actor;
import com.bethanysbakery.api.domain.order.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** S5 local publisher: logs only. S6 replaces this with notification/outbox handlers. */
@Component
public class LoggingOrderEventPublisher implements OrderEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingOrderEventPublisher.class);

    @Override
    public void orderCreated(Order order) {
        log.info("OrderCreated orderId={} orderNumber={}", order.getId(), order.getOrderNumber());
    }

    @Override
    public void orderAccepted(Order order) {
        log.info("OrderAccepted orderId={} orderNumber={}", order.getId(), order.getOrderNumber());
    }

    @Override
    public void orderRejected(Order order, String reason) {
        // Reason is intentionally not logged: free text may carry PII. S6 receives it via the event.
        log.info("OrderRejected orderId={} orderNumber={}", order.getId(), order.getOrderNumber());
    }

    @Override
    public void orderReady(Order order) {
        log.info("OrderReady orderId={} orderNumber={}", order.getId(), order.getOrderNumber());
    }

    @Override
    public void orderCompleted(Order order) {
        log.info("OrderCompleted orderId={} orderNumber={}", order.getId(), order.getOrderNumber());
    }

    @Override
    public void orderCancelled(Order order, String reason, Actor actor) {
        log.info("OrderCancelled orderId={} orderNumber={} actor={}",
            order.getId(), order.getOrderNumber(), actor);
    }

    @Override
    public void orderExpired(Order order) {
        log.info("OrderExpired orderId={} orderNumber={}", order.getId(), order.getOrderNumber());
    }

    @Override
    public void menuDateCancelled(MenuDate date, String reason) {
        log.info("MenuDateCancelled menuDateId={} bakeDate={}", date.getId(), date.getBakeDate());
    }
}

package com.bethanysbakery.api.order;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Expires unaccepted orders whose cutoff has passed. Conditional updates make reruns safe. */
@Component
public class OrderExpiryJob {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiryJob.class);

    private final OrderService orders;
    private final Clock clock;

    public OrderExpiryJob(OrderService orders, Clock clock) {
        this.orders = orders;
        this.clock = clock;
    }

    /** Invoked directly by tests and by the gated OrderExpiryScheduler. */
    public void expireDueOrders() {
        List<UUID> due = orders.findExpiredPendingOrderIds(clock.instant());
        for (UUID orderId : due) {
            try {
                orders.expire(orderId);
            } catch (RuntimeException ex) {
                log.warn("Order expiry failed for {}", orderId, ex);
            }
        }
    }
}

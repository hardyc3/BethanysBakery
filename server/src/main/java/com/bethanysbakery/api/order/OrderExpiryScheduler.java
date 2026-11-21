package com.bethanysbakery.api.order;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scheduled trigger only; the job bean stays available in tests with scheduling disabled. */
@Component
@ConditionalOnProperty(prefix = "bakery.notifications", name = "scheduling-enabled",
    havingValue = "true", matchIfMissing = false)
public class OrderExpiryScheduler {

    private final OrderExpiryJob job;

    public OrderExpiryScheduler(OrderExpiryJob job) {
        this.job = job;
    }

    @Scheduled(fixedDelayString = "${bakery.notifications.order-expiry-interval-ms:60000}",
        initialDelayString = "${bakery.notifications.order-expiry-interval-ms:60000}")
    public void run() {
        job.expireDueOrders();
    }
}

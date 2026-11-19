package com.bethanysbakery.api.notification;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** All notification timers are disabled by config in tests; services are invoked directly there. */
@Component
@ConditionalOnProperty(prefix = "bakery.notifications", name = "scheduling-enabled",
    havingValue = "true", matchIfMissing = false)
public class NotificationSchedulingRunner {

    private final OutboxProcessor outbox;
    private final NotificationDispatchService dispatcher;
    private final MenuNotificationService menus;

    public NotificationSchedulingRunner(OutboxProcessor outbox, NotificationDispatchService dispatcher,
            MenuNotificationService menus) {
        this.outbox = outbox;
        this.dispatcher = dispatcher;
        this.menus = menus;
    }

    @Scheduled(fixedDelayString = "${bakery.notifications.outbox-interval-ms:15000}",
        initialDelayString = "${bakery.notifications.outbox-interval-ms:15000}")
    public void processOutbox() {
        outbox.processDue();
    }

    @Scheduled(fixedDelayString = "${bakery.notifications.dispatch-interval-ms:30000}",
        initialDelayString = "${bakery.notifications.dispatch-interval-ms:30000}")
    public void dispatchDue() {
        dispatcher.dispatchDue();
    }

    @Scheduled(fixedDelayString = "${bakery.notifications.menu-interval-ms:60000}",
        initialDelayString = "${bakery.notifications.menu-interval-ms:60000}")
    public void queueMenuNotifications() {
        menus.queueDueMenuNotifications();
    }

    @Scheduled(fixedDelayString = "${bakery.notifications.menu-interval-ms:60000}",
        initialDelayString = "${bakery.notifications.menu-interval-ms:60000}")
    public void queueReminders() {
        menus.queueDueReminders();
    }
}

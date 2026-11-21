package com.bethanysbakery.api.notification;

import com.bethanysbakery.api.auth.audit.AuditService;
import com.bethanysbakery.api.config.BakeryProperties;
import com.bethanysbakery.api.domain.audit.ActorType;
import com.bethanysbakery.api.domain.auth.Customer;
import com.bethanysbakery.api.domain.auth.CustomerRepository;
import com.bethanysbakery.api.domain.auth.CustomerStatus;
import com.bethanysbakery.api.domain.menu.Menu;
import com.bethanysbakery.api.domain.menu.MenuDate;
import com.bethanysbakery.api.domain.menu.MenuDateRepository;
import com.bethanysbakery.api.domain.menu.MenuDateStatus;
import com.bethanysbakery.api.domain.menu.MenuRepository;
import com.bethanysbakery.api.domain.menu.MenuStatus;
import com.bethanysbakery.api.domain.notification.NotificationEventType;
import com.bethanysbakery.api.domain.order.OrderRepository;
import java.time.Clock;
import java.util.List;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Queues MENU_PUBLISHED and ORDER_REMINDER notifications; callable by scheduler or admin endpoint. */
@Service
public class MenuNotificationService {

    private final MenuRepository menus;
    private final MenuDateRepository dates;
    private final CustomerRepository customers;
    private final OrderRepository orders;
    private final NotificationService notifications;
    private final AuditService audit;
    private final BakeryProperties bakery;
    private final Clock clock;

    public MenuNotificationService(MenuRepository menus, MenuDateRepository dates,
            CustomerRepository customers, OrderRepository orders, NotificationService notifications,
            AuditService audit, BakeryProperties bakery, Clock clock) {
        this.menus = menus;
        this.dates = dates;
        this.customers = customers;
        this.orders = orders;
        this.notifications = notifications;
        this.audit = audit;
        this.bakery = bakery;
        this.clock = clock;
    }

    @Transactional
    public int queueDueMenuNotifications() {
        Instant now = clock.instant();
        List<Menu> due = menus.findAllByStatusAndNotificationEnabledTrueAndNotifyAtLessThanEqualAndNotifySentAtIsNull(
            MenuStatus.PUBLISHED, now);
        List<Customer> recipients = activeCustomers();
        for (Menu menu : due) {
            Map<String, Object> payload = NotificationPayloads.forMenuPublished(menu.getId(), menu.getTitle(),
                appBaseUrl());
            notifications.enqueueMenuPublished(menu.getId(), recipients, payload);
            menu.markNotifySent(now);
            audit.record(ActorType.SYSTEM, null, "MENU_NOTIFICATION_QUEUED", "MENU", menu.getId(),
                Map.of(), Map.of("notifySentAt", now.toString()));
        }
        return due.size();
    }

    @Transactional
    public int queueDueReminders() {
        Instant now = clock.instant();
        int queued = 0;
        for (MenuDate date : dates.findAllByStatus(MenuDateStatus.PUBLISHED)) {
            if (reminderDue(date, now) && queueReminder(date)) {
                queued++;
            }
        }
        return queued;
    }

    @Transactional
    public boolean sendReminderNow(UUID menuDateId, UUID adminId) {
        MenuDate date = dates.findById(menuDateId).orElseThrow();
        return queueReminder(date, adminId);
    }

    private boolean queueReminder(MenuDate date) {
        return queueReminder(date, null);
    }

    private boolean queueReminder(MenuDate date, UUID adminId) {
        Instant now = clock.instant();
        Set<UUID> alreadyOrdered = orders
            .findAllByMenuDate_IdAndStatusIn(date.getId(), NotificationRules.ACTIVE_ORDER_STATUSES).stream()
            .map(order -> order.getCustomerId())
            .collect(Collectors.toSet());
        Map<String, Object> payload = NotificationPayloads.forReminder(date.getId(), date.getBakeDate(), appBaseUrl());
        int count = 0;
        for (Customer customer : activeCustomers()) {
            if (alreadyOrdered.contains(customer.getId())
                    || !notifications.isOptedIn(customer.getId(), NotificationEventType.ORDER_REMINDER)) {
                continue;
            }
            notifications.enqueueCustomer(customer.getId(), NotificationEventType.ORDER_REMINDER,
                "MENU_DATE", date.getId(), NotificationKeys.reminder(date.getId(), customer.getId()), payload, now);
            count++;
        }
        date.markReminderSent(now);
        audit.record(adminId == null ? ActorType.SYSTEM : ActorType.ADMIN, adminId,
            "ORDER_REMINDER_QUEUED", "MENU_DATE", date.getId(),
            Map.of(), Map.of("reminderSentAt", now.toString(), "recipients", count));
        return true;
    }

    private boolean reminderDue(MenuDate date, Instant now) {
        if (date.getReminderSentAt() != null) {
            return false;
        }
        ZoneId zone = ZoneId.of(bakery.timezone());
        Instant bakeStart = date.getBakeDate().atStartOfDay(zone).toInstant();
        Instant reminderAt = date.getBakeDate().minusDays(1).atTime(9, 0).atZone(zone).toInstant();
        return !now.isBefore(reminderAt) && now.isBefore(bakeStart);
    }

    private List<Customer> activeCustomers() {
        return customers.findAllByStatus(CustomerStatus.ACTIVE);
    }

    private String appBaseUrl() {
        return bakery.auth().appPublicBaseUrl();
    }
}

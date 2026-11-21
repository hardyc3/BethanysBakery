package com.bethanysbakery.api.notification;

import com.bethanysbakery.api.config.BakeryProperties;
import com.bethanysbakery.api.domain.auth.Customer;
import com.bethanysbakery.api.domain.auth.CustomerRepository;
import com.bethanysbakery.api.domain.notification.Notification;
import com.bethanysbakery.api.domain.notification.NotificationChannel;
import com.bethanysbakery.api.domain.notification.NotificationDelivery;
import com.bethanysbakery.api.domain.notification.NotificationRepository;
import com.bethanysbakery.api.domain.notification.NotificationStatus;
import com.bethanysbakery.api.domain.notification.RecipientType;
import com.bethanysbakery.api.notification.NotificationMessages.DeliveryResult;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Sends due deliveries through providers and applies suppression/retry rules. */
@Service
public class NotificationDispatchService {

    private final NotificationRepository notifications;
    private final CustomerRepository customers;
    private final EmailSender emailSender;
    private final SmsSender smsSender;
    private final NotificationTemplates templates;
    private final BakeryProperties bakery;
    private final Clock clock;

    public NotificationDispatchService(NotificationRepository notifications, CustomerRepository customers,
            EmailSender emailSender, SmsSender smsSender, NotificationTemplates templates,
            BakeryProperties bakery, Clock clock) {
        this.notifications = notifications;
        this.customers = customers;
        this.emailSender = emailSender;
        this.smsSender = smsSender;
        this.templates = templates;
        this.bakery = bakery;
        this.clock = clock;
    }

    @Transactional
    public int dispatchDue() {
        Instant now = clock.instant();
        List<Notification> due = notifications
            .findByStatusAndScheduledAtLessThanEqualOrderByScheduledAtAsc(NotificationStatus.QUEUED, now);
        for (Notification notification : due) {
            dispatch(notification, now);
        }
        return due.size();
    }

    private void dispatch(Notification notification, Instant now) {
        notification.markSending();
        List<NotificationDelivery> failures = new ArrayList<>();
        String lastError = null;
        for (NotificationDelivery delivery : notification.getDeliveries()) {
            if (delivery.getStatus() != NotificationStatus.QUEUED) {
                continue;
            }
            if (delivery.getChannel() == NotificationChannel.SMS && smsSuppressed(notification, delivery)) {
                continue;
            }
            if (delivery.getChannel() == NotificationChannel.SMS
                    && NotificationRules.promotional(notification.getEventType())) {
                LocalDate bakeDate = NotificationRules.bakeDate(notification.getPayload());
                if (bakeDate != null && SmsWindow.bakeDatePassed(bakeDate, now, zone())) {
                    delivery.markSuppressed("BAKE_DATE_PASSED");
                    continue;
                }
                Instant allowed = SmsWindow.nextAllowed(now, zone());
                if (allowed.isAfter(now)) {
                    notification.setScheduledAt(allowed);
                    continue;
                }
            }
            delivery.markSending();
            DeliveryResult result;
            try {
                result = send(notification, delivery);
            } catch (ProviderNotConfiguredException ex) {
                delivery.fail("PROVIDER_NOT_CONFIGURED");
                failures.add(delivery);
                notification.fail(ex.getMessage(), null);
                return;
            }
            if (result.success()) {
                delivery.markSent(result.providerMessageId(), now);
            } else {
                delivery.fail(result.error());
                failures.add(delivery);
                lastError = result.error();
            }
        }

        boolean allDone = notification.getDeliveries().stream().allMatch(delivery ->
            delivery.getStatus() == NotificationStatus.SENT
                || delivery.getStatus() == NotificationStatus.SUPPRESSED);
        if (allDone) {
            notification.markSent(now);
        } else if (!failures.isEmpty()) {
            Instant next = NotificationRetryPolicy.nextAttempt(notification.getAttempts() + 1, now);
            if (next == null) {
                notification.fail(lastError, null);
            } else {
                notification.fail(lastError, next);
                failures.forEach(NotificationDelivery::requeue);
            }
        } else {
            notification.markQueued();
        }
        notifications.save(notification);
    }

    /** Re-check consent at send time so STOP applies to already-queued SMS. */
    private boolean smsSuppressed(Notification notification, NotificationDelivery delivery) {
        if (notification.getRecipientType() != RecipientType.CUSTOMER) {
            return false;
        }
        // Fan-out notifications (e.g. MENU_PUBLISHED) have no customerId; resolve by destination.
        Customer customer = notification.getCustomerId() != null
            ? customers.findById(notification.getCustomerId()).orElse(null)
            : customers.findByPhoneE164(delivery.getDestination()).orElse(null);
        if (customer == null || !customer.hasSmsConsent()) {
            delivery.markSuppressed(customer != null && customer.getSmsOptOutAt() != null
                ? "SMS_OPTED_OUT" : "SMS_CONSENT_REVOKED");
            return true;
        }
        if (customer.getPhoneE164() == null || !customer.getPhoneE164().equals(delivery.getDestination())) {
            delivery.markSuppressed("SMS_DESTINATION_STALE");
            return true;
        }
        return false;
    }

    private DeliveryResult send(Notification notification, NotificationDelivery delivery) {
        Map<String, Object> payload = notification.getPayload();
        return switch (delivery.getChannel()) {
            case EMAIL -> emailSender.send(templates.renderEmail(notification.getEventType(), payload,
                delivery.getDestination()));
            case SMS -> smsSender.send(templates.renderSms(notification.getEventType(), payload,
                delivery.getDestination()));
        };
    }

    private ZoneId zone() {
        return NotificationRules.zone(bakery.timezone());
    }
}

package com.bethanysbakery.api.notification;

import com.bethanysbakery.api.api.notification.NotificationApi;
import com.bethanysbakery.api.api.notification.NotificationApi.NotificationPreferenceView;
import com.bethanysbakery.api.api.notification.NotificationApi.NotificationPreferencesView;
import com.bethanysbakery.api.api.notification.NotificationApi.NotificationPreferencesWrite;
import com.bethanysbakery.api.api.notification.NotificationApi.NotificationList;
import com.bethanysbakery.api.api.notification.NotificationApi.TextbeeWebhook;
import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.auth.audit.AuditService;
import com.bethanysbakery.api.config.BakeryProperties;
import com.bethanysbakery.api.config.NotificationProperties;
import com.bethanysbakery.api.domain.audit.ActorType;
import com.bethanysbakery.api.domain.auth.Customer;
import com.bethanysbakery.api.domain.auth.CustomerRepository;
import com.bethanysbakery.api.domain.notification.Notification;
import com.bethanysbakery.api.domain.notification.NotificationChannel;
import com.bethanysbakery.api.domain.notification.NotificationDelivery;
import com.bethanysbakery.api.domain.notification.NotificationEventType;
import com.bethanysbakery.api.domain.notification.NotificationPreference;
import com.bethanysbakery.api.domain.notification.NotificationPreferenceRepository;
import com.bethanysbakery.api.domain.notification.NotificationRepository;
import com.bethanysbakery.api.domain.notification.NotificationStatus;
import com.bethanysbakery.api.domain.notification.RecipientType;
import com.bethanysbakery.api.domain.order.Order;
import com.bethanysbakery.api.domain.order.OrderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Queueing, preferences, webhook consent, and the admin notification queue. */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final Map<NotificationEventType, boolean[]> DEFAULTS = defaults();

    private final NotificationRepository notifications;
    private final NotificationPreferenceRepository preferences;
    private final CustomerRepository customers;
    private final OrderRepository orders;
    private final AuditService audit;
    private final BakeryProperties bakery;
    private final NotificationProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Environment environment;

    public NotificationService(NotificationRepository notifications,
            NotificationPreferenceRepository preferences, CustomerRepository customers, OrderRepository orders,
            AuditService audit, BakeryProperties bakery, NotificationProperties properties, ObjectMapper mapper,
            Clock clock, Environment environment) {
        this.notifications = notifications;
        this.preferences = preferences;
        this.customers = customers;
        this.orders = orders;
        this.audit = audit;
        this.bakery = bakery;
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
        this.environment = environment;
    }

    @Transactional(readOnly = true)
    public NotificationPreferencesView preferences(UUID customerId) {
        Customer customer = findCustomer(customerId);
        return new NotificationPreferencesView(customer.hasSmsConsent(),
            customer.getSmsOptOutAt() != null && !customer.hasSmsConsent(),
            customerPreferences(customerId));
    }

    @Transactional
    public NotificationPreferencesView updatePreferences(UUID customerId, NotificationPreferencesWrite request) {
        Customer customer = findCustomer(customerId);
        List<NotificationApi.NotificationPreferenceWrite> writes = request.preferences();
        if (writes == null || writes.isEmpty()) {
            throw AuthException.badRequest("VALIDATION_ERROR", "preferences must not be empty");
        }
        Map<NotificationEventType, boolean[]> parsed = new EnumMap<>(NotificationEventType.class);
        for (NotificationApi.NotificationPreferenceWrite write : writes) {
            NotificationEventType type = parseEventType(write.eventType());
            parsed.put(type, new boolean[] {
                Boolean.TRUE.equals(write.emailEnabled()), Boolean.TRUE.equals(write.smsEnabled())});
        }
        boolean smsConsent = Boolean.TRUE.equals(request.smsConsent());
        if (parsed.values().stream().anyMatch(value -> value[1]) && !smsConsent) {
            throw AuthException.badRequest("SMS_CONSENT_REQUIRED", "SMS requires smsConsent=true");
        }

        Instant now = clock.instant();
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("smsConsent", customer.hasSmsConsent());
        before.put("preferences", customerPreferences(customerId));
        if (smsConsent) {
            customer.grantSmsConsent(now);
        } else {
            customer.revokeSmsConsent(now);
        }
        customers.save(customer);

        for (Map.Entry<NotificationEventType, boolean[]> entry : parsed.entrySet()) {
            NotificationPreference existing = preferences.findByCustomerIdAndEventType(customerId, entry.getKey())
                .orElse(null);
            if (existing == null) {
                preferences.save(new NotificationPreference(customerId, entry.getKey(),
                    entry.getValue()[0], entry.getValue()[1], now));
            } else {
                existing.update(entry.getValue()[0], entry.getValue()[1], now);
            }
        }
        audit.record(ActorType.CUSTOMER, customerId, "NOTIFICATION_PREFERENCES_UPDATED", "CUSTOMER",
            customerId, before, Map.of("smsConsent", smsConsent, "preferences", customerPreferences(customerId)));
        return preferences(customerId);
    }

    @Transactional
    public Notification enqueueCustomer(UUID customerId, NotificationEventType eventType, String entityType,
            UUID entityId, String key, Map<String, Object> payload, Instant scheduledAt) {
        return enqueueCustomer(customerId, eventType, entityType, entityId, key, payload, scheduledAt, false);
    }

    /** Active-order cancellations bypass the event preference unless the customer opted out of everything. */
    @Transactional
    public Notification enqueueCustomer(UUID customerId, NotificationEventType eventType, String entityType,
            UUID entityId, String key, Map<String, Object> payload, Instant scheduledAt,
            boolean bypassPreferences) {
        Optional<Notification> existing = notifications.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return existing.get();
        }
        Instant now = clock.instant();
        Notification notification = new Notification(RecipientType.CUSTOMER,
            customerId, null, eventType, entityType, entityId, key, payload, now,
            scheduledAt == null ? now : scheduledAt);
        if (hasAllNotificationsDisabled(customerId)) {
            // An explicit all-off PUT suppresses the row itself, including transactional notices.
            notification.markSuppressed("ALL_NOTIFICATIONS_DISABLED");
            return notification;
        }
        Customer customer = customers.findById(customerId).orElse(null);

        boolean[] pref = preference(customerId, eventType);
        if (bypassPreferences) {
            pref = new boolean[] {true, true};
        }
        if (pref[0] && customer != null && customer.isEmailVerified() && customer.getEmail() != null) {
            notification.addDelivery(new NotificationDelivery(notification, NotificationChannel.EMAIL, customer.getEmail()));
        }
        if (pref[1] && customer != null && customer.hasSmsConsent() && customer.getPhoneE164() != null) {
            LocalDate bakeDate = NotificationRules.promotional(eventType)
                ? NotificationRules.bakeDate(payload) : null;
            if (bakeDate == null || !SmsWindow.bakeDatePassed(bakeDate, now, zone())) {
                // Quiet hours are enforced by the dispatcher so email is not delayed with SMS.
                notification.addDelivery(new NotificationDelivery(notification, NotificationChannel.SMS,
                    customer.getPhoneE164()));
            }
        }
        if (notification.getDeliveries().isEmpty()) {
            notification.markSuppressed("NO_ENABLED_CHANNEL");
        }
        return notifications.save(notification);
    }

    /** One logical MENU_PUBLISHED message fans out to many customer deliveries. */
    @Transactional
    public Notification enqueueMenuPublished(UUID menuId, List<Customer> recipients, Map<String, Object> payload) {
        String key = NotificationKeys.menuPublished(menuId);
        Optional<Notification> existing = notifications.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return existing.get();
        }
        Instant now = clock.instant();
        Notification notification = new Notification(RecipientType.CUSTOMER,
            null, null, NotificationEventType.MENU_PUBLISHED, "MENU", menuId, key, payload, now, now);
        for (Customer customer : recipients) {
            boolean[] pref = preference(customer.getId(), NotificationEventType.MENU_PUBLISHED);
            if (pref[0] && customer.isEmailVerified() && customer.getEmail() != null) {
                notification.addDelivery(new NotificationDelivery(notification, NotificationChannel.EMAIL,
                    customer.getEmail()));
            }
            if (pref[1] && customer.hasSmsConsent() && customer.getPhoneE164() != null) {
                notification.addDelivery(new NotificationDelivery(notification, NotificationChannel.SMS,
                    customer.getPhoneE164()));
            }
        }
        if (notification.getDeliveries().isEmpty()) {
            notification.markSuppressed("NO_ENABLED_CHANNEL");
        }
        return notifications.save(notification);
    }

    @Transactional
    public Notification enqueueAdminEmail(NotificationEventType eventType, String entityType, UUID entityId,
            String key, Map<String, Object> payload) {
        Optional<Notification> existing = notifications.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return existing.get();
        }
        String destination = properties.adminAlertEmail();
        Instant now = clock.instant();
        Notification notification = new Notification(RecipientType.ADMIN,
            null, null, eventType, entityType, entityId, key, payload, now, now);
        if (destination == null || destination.isBlank()) {
            notification.markSuppressed("ADMIN_EMAIL_NOT_CONFIGURED");
        } else {
            notification.addDelivery(new NotificationDelivery(notification, NotificationChannel.EMAIL, destination));
        }
        return notifications.save(notification);
    }

    /** Admin alert is email always and SMS only when ADMIN_ALERT_PHONE is configured. */
    @Transactional
    public Notification enqueueAdminNewOrder(Order order) {
        Order attached = orders.findById(order.getId()).orElse(order);
        Map<String, Object> payload = NotificationPayloads.forOrder(attached, appBaseUrl(),
            bakery.venmo().handle(), bakery.venmo().displayName());
        Notification email = enqueueAdminEmail(NotificationEventType.ADMIN_NEW_ORDER, "ORDER", order.getId(),
            NotificationKeys.adminNewOrderEmail(order.getId()), payload);
        if (properties.adminAlertPhoneConfigured()) {
            enqueueAdminSms(NotificationEventType.ADMIN_NEW_ORDER, "ORDER", order.getId(),
                NotificationKeys.adminNewOrderSms(order.getId()), payload);
        }
        return email;
    }

    @Transactional
    public Notification enqueueAdminSms(NotificationEventType eventType, String entityType, UUID entityId,
            String key, Map<String, Object> payload) {
        Optional<Notification> existing = notifications.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            return existing.get();
        }
        Instant now = clock.instant();
        Notification notification = new Notification(RecipientType.ADMIN,
            null, null, eventType, entityType, entityId, key, payload, now, now);
        String destination = properties.adminAlertPhone();
        if (destination == null || destination.isBlank()) {
            notification.markSuppressed("ADMIN_SMS_NOT_CONFIGURED");
        } else {
            notification.addDelivery(new NotificationDelivery(notification, NotificationChannel.SMS, destination));
        }
        return notifications.save(notification);
    }

    @Transactional(readOnly = true)
    public NotificationList list(NotificationStatus status, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        PageRequest pageable = PageRequest.of(safePage, safeSize);
        Page<Notification> result = status == null
            ? notifications.findAllByOrderByCreatedAtDesc(pageable)
            : notifications.findByStatusOrderByCreatedAtDesc(status, pageable);
        return new NotificationList(result.getContent().stream().map(NotificationApi::view).toList(),
            result.getTotalElements());
    }

    @Transactional
    public Notification retry(UUID notificationId, UUID adminId) {
        Notification notification = notifications.findById(notificationId)
            .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Notification not found"));
        if (notification.getStatus() != NotificationStatus.FAILED
                && notification.getStatus() != NotificationStatus.SUPPRESSED) {
            throw new AuthException(HttpStatus.CONFLICT, "NOT_RETRYABLE", "Notification is not retryable");
        }
        String previousStatus = notification.getStatus().name();
        verifyProvidersConfigured(notification);
        notification.requeue(clock.instant());
        audit.record(ActorType.ADMIN, adminId, "NOTIFICATION_RETRY", "NOTIFICATION", notificationId,
            Map.of("status", previousStatus),
            Map.of("status", notification.getStatus().name(), "attempts", notification.getAttempts()));
        return notifications.save(notification);
    }

    @Transactional
    public void handleTextbeeWebhook(byte[] rawBody, String signature) {
        if (!properties.webhookSecretConfigured()) {
            throw AuthException.notConfigured("TEXTBEE_WEBHOOK_SECRET is not configured");
        }
        String expected = hmac(rawBody, properties.textbee().webhookSecret());
        String provided = signature == null ? "" : signature.trim().replaceFirst("(?i)^sha256=", "");
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8))) {
            throw new AuthException(HttpStatus.UNAUTHORIZED, "INVALID_SIGNATURE", "Invalid webhook signature");
        }
        TextbeeWebhook event;
        try {
            event = mapper.readValue(rawBody, TextbeeWebhook.class);
        } catch (Exception ex) {
            // Signature was valid: acknowledge so Textbee does not retry an unusable payload.
            log.warn("Ignoring malformed Textbee webhook payload", ex);
            return;
        }
        if (event.from() == null || event.message() == null) {
            return;
        }
        String message = event.message().trim().toUpperCase(Locale.ROOT);
        boolean stop = List.of("STOP", "STOPALL", "UNSUBSCRIBE", "STOP ALL").contains(message);
        boolean start = List.of("START", "UNSTOP").contains(message);
        if (!stop && !start) {
            return;
        }
        Instant now = clock.instant();
        Customer customer = customers.findByPhoneE164(event.from()).orElse(null);
        if (customer == null) {
            return;
        }
        boolean changed;
        if (stop) {
            Instant current = customer.getSmsOptOutAt();
            changed = current == null || current.isBefore(now);
            if (changed) {
                customer.markSmsOptOut(now);
            }
        } else {
            changed = customer.clearSmsOptOut(now);
        }
        if (changed) {
            customers.save(customer);
            audit.record(ActorType.CUSTOMER, customer.getId(), stop ? "SMS_OPT_OUT" : "SMS_OPT_IN",
                "CUSTOMER", customer.getId(), Map.of("smsOptedOut", !stop), Map.of("smsOptedOut", stop));
        }
    }

    /** Admin-triggered retries must fail fast when a required provider is not configured. */
    private void verifyProvidersConfigured(Notification notification) {
        if (environment.acceptsProfiles(Profiles.of("local", "test"))) {
            return;
        }
        Set<NotificationChannel> channels = notification.getDeliveries().stream()
            .map(NotificationDelivery::getChannel)
            .collect(Collectors.toSet());
        if (channels.contains(NotificationChannel.EMAIL) && !properties.mailgunConfigured()) {
            throw AuthException.notConfigured("MAILGUN is not configured");
        }
        if (channels.contains(NotificationChannel.SMS) && !properties.textbeeConfigured()) {
            throw AuthException.notConfigured("TEXTBEE is not configured");
        }
    }

    private List<NotificationPreferenceView> customerPreferences(UUID customerId) {
        Map<NotificationEventType, NotificationPreference> stored = new LinkedHashMap<>();
        preferences.findAllByCustomerId(customerId)
            .forEach(pref -> stored.put(pref.getEventType(), pref));
        List<NotificationPreferenceView> result = new ArrayList<>();
        for (NotificationEventType type : customerEvents()) {
            NotificationPreference pref = stored.get(type);
            boolean[] value = pref == null ? DEFAULTS.get(type)
                : new boolean[] {pref.isEmailEnabled(), pref.isSmsEnabled()};
            result.add(new NotificationPreferenceView(type.name(), value[0], value[1]));
        }
        return result;
    }

    /** True when the customer has email or SMS enabled for this event (explicit or default). */
    public boolean isOptedIn(UUID customerId, NotificationEventType type) {
        boolean[] value = preference(customerId, type);
        return value[0] || value[1];
    }

    /** Explicit all-off PUT: every customer event must be stored with both channels disabled. */
    @Transactional(readOnly = true)
    public boolean hasAllNotificationsDisabled(UUID customerId) {
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

    private boolean[] preference(UUID customerId, NotificationEventType type) {
        return preferences.findByCustomerIdAndEventType(customerId, type)
            .map(pref -> new boolean[] {pref.isEmailEnabled(), pref.isSmsEnabled()})
            .orElseGet(() -> DEFAULTS.getOrDefault(type, new boolean[] {true, false}));
    }

    private ZoneId zone() {
        return NotificationRules.zone(bakery.timezone());
    }

    private String appBaseUrl() {
        return bakery.auth().appPublicBaseUrl();
    }

    private Customer findCustomer(UUID customerId) {
        return customers.findById(customerId)
            .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Customer not found"));
    }

    private static NotificationEventType parseEventType(String value) {
        try {
            NotificationEventType type = NotificationEventType.valueOf(value);
            if (!customerEvents().contains(type)) {
                throw new IllegalArgumentException("Not a customer event");
            }
            return type;
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw AuthException.badRequest("VALIDATION_ERROR", "Invalid notification event type");
        }
    }

    public static List<NotificationEventType> customerEvents() {
        return List.of(NotificationEventType.MENU_PUBLISHED, NotificationEventType.ORDER_REMINDER,
            NotificationEventType.ORDER_ACCEPTED, NotificationEventType.ORDER_READY,
            NotificationEventType.ORDER_DELAYED, NotificationEventType.ORDER_REJECTED,
            NotificationEventType.ORDER_CANCELLED, NotificationEventType.ORDER_EXPIRED,
            NotificationEventType.MENU_CANCELLED);
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
        return Map.copyOf(values);
    }

    private static String hmac(byte[] body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception ex) {
            throw new IllegalStateException("Could not compute webhook signature", ex);
        }
    }
}

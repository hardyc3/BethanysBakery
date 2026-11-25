package com.bethanysbakery.api.notification;

import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.config.BakeryProperties;
import com.bethanysbakery.api.domain.auth.Customer;
import com.bethanysbakery.api.domain.auth.CustomerRepository;
import com.bethanysbakery.api.domain.auth.CustomerStatus;
import com.bethanysbakery.api.domain.menu.MenuDate;
import com.bethanysbakery.api.domain.menu.MenuDateRepository;
import com.bethanysbakery.api.domain.menu.MenuRepository;
import com.bethanysbakery.api.domain.notification.NotificationChannel;
import com.bethanysbakery.api.domain.notification.NotificationEventType;
import com.bethanysbakery.api.domain.notification.NotificationPreferenceRepository;
import com.bethanysbakery.api.domain.order.Order;
import com.bethanysbakery.api.domain.order.OrderRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Single source of truth for customer-event recipients, shared by enqueue and preview. */
@Component
public class NotificationAudienceResolver {

    /** Internal recipient: raw contact details never leave the server. */
    public record Recipient(UUID customerId, String name, String email, String phone,
            List<NotificationChannel> channels, List<String> reasons, boolean bypassPreferences) {}

    private final NotificationPreferenceRepository preferences;
    private final CustomerRepository customers;
    private final OrderRepository orders;
    private final MenuRepository menus;
    private final MenuDateRepository dates;
    private final BakeryProperties bakery;
    private final Clock clock;

    public NotificationAudienceResolver(NotificationPreferenceRepository preferences,
            CustomerRepository customers, OrderRepository orders, MenuRepository menus,
            MenuDateRepository dates, BakeryProperties bakery, Clock clock) {
        this.preferences = preferences;
        this.customers = customers;
        this.orders = orders;
        this.menus = menus;
        this.dates = dates;
        this.bakery = bakery;
        this.clock = clock;
    }

    /** Resolves the audience exactly as the enqueue path would at this instant. */
    public List<Recipient> resolve(NotificationEventType type, String entityType, UUID entityId,
            Map<String, Object> payload) {
        validate(type, entityType);
        requireEntity(entityType, entityId);
        Instant now = clock.instant();
        if (payload == null) {
            payload = previewPayload(type, entityId);
        }
        return switch (type) {
            case MENU_PUBLISHED -> menuPublished(entityId, now);
            case ORDER_REMINDER -> reminder(entityId, payload, now);
            case MENU_CANCELLED -> menuCancelled(entityType, entityId, now);
            case ORDER_ACCEPTED, ORDER_READY, ORDER_DELAYED, ORDER_REJECTED, ORDER_CANCELLED,
                    ORDER_EXPIRED -> orderEvent(entityId, type, now);
            default -> throw invalid();
        };
    }

    /** Channel decision for one already-selected customer; used by the existing enqueue overload. */
    public List<NotificationChannel> channels(UUID customerId, NotificationEventType type,
            Map<String, Object> payload, boolean bypassPreferences) {
        Customer customer = customers.findById(customerId).orElse(null);
        if (customer == null) {
            return List.of();
        }
        boolean[] pref = bypassPreferences ? new boolean[] {true, true}
            : NotificationRules.preference(preferences, customerId, type);
        return channels(customer, pref, smsBakeDate(type, payload), clock.instant());
    }

    public boolean isOptedIn(UUID customerId, NotificationEventType type) {
        return NotificationRules.isOptedIn(preferences, customerId, type);
    }

    public boolean hasAllNotificationsDisabled(UUID customerId) {
        return NotificationRules.hasAllNotificationsDisabled(preferences, customerId);
    }

    private Map<String, Object> previewPayload(NotificationEventType type, UUID entityId) {
        if (type == NotificationEventType.ORDER_REMINDER) {
            MenuDate date = dates.findById(entityId)
                .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Menu date not found"));
            return Map.of("bakeDate", date.getBakeDate().toString());
        }
        return Map.of();
    }

    private List<Recipient> menuPublished(UUID menuId, Instant now) {
        List<Recipient> result = new ArrayList<>();
        for (Customer customer : activeCustomers()) {
            boolean[] pref = NotificationRules.preference(preferences, customer.getId(),
                NotificationEventType.MENU_PUBLISHED);
            List<NotificationChannel> channels = channels(customer, pref, null, now);
            if (!channels.isEmpty()) {
                result.add(recipient(customer, channels, List.of("ACTIVE", "MENU_PUBLISHED_PREFERENCE"), false));
            }
        }
        return sorted(result);
    }

    private List<Recipient> reminder(UUID menuDateId, Map<String, Object> payload, Instant now) {
        Set<UUID> alreadyOrdered = new HashSet<>();
        orders.findAllByMenuDate_IdAndStatusIn(menuDateId, NotificationRules.ACTIVE_ORDER_STATUSES)
            .forEach(order -> alreadyOrdered.add(order.getCustomerId()));
        LocalDate bakeDate = NotificationRules.bakeDate(payload);
        List<Recipient> result = new ArrayList<>();
        for (Customer customer : activeCustomers()) {
            if (alreadyOrdered.contains(customer.getId())
                    || !isOptedIn(customer.getId(), NotificationEventType.ORDER_REMINDER)) {
                continue;
            }
            boolean[] pref = NotificationRules.preference(preferences, customer.getId(),
                NotificationEventType.ORDER_REMINDER);
            List<NotificationChannel> channels = channels(customer, pref, bakeDate, now);
            if (!channels.isEmpty()) {
                result.add(recipient(customer, channels,
                    List.of("ACTIVE", "NO_ORDER_FOR_DATE", "REMINDER_PREFERENCE"), false));
            }
        }
        return sorted(result);
    }

    private List<Recipient> orderEvent(UUID orderId, NotificationEventType type, Instant now) {
        Order order = orders.findById(orderId)
            .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Order not found"));
        Customer customer = customers.findById(order.getCustomerId()).orElse(null);
        if (customer == null) {
            return List.of();
        }
        boolean[] pref = NotificationRules.preference(preferences, customer.getId(), type);
        List<NotificationChannel> channels = channels(customer, pref, null, now);
        if (channels.isEmpty()) {
            return List.of();
        }
        return List.of(recipient(customer, channels, List.of("ORDER_CUSTOMER", type.name() + "_PREFERENCE"), false));
    }

    private List<Recipient> menuCancelled(String entityType, UUID entityId, Instant now) {
        List<MenuDate> affected = "MENU".equals(entityType)
            ? dates.findAllByMenu_IdOrderByBakeDateAsc(entityId)
            : List.of(dates.findById(entityId).orElseThrow(() ->
                new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Menu date not found")));
        Set<UUID> activeOrderCustomers = new HashSet<>();
        for (MenuDate date : affected) {
            orders.findAllByMenuDate_IdAndStatusIn(date.getId(), NotificationRules.ACTIVE_ORDER_STATUSES)
                .forEach(order -> activeOrderCustomers.add(order.getCustomerId()));
        }
        Set<UUID> optedIn = new HashSet<>();
        for (Customer customer : activeCustomers()) {
            if (isOptedIn(customer.getId(), NotificationEventType.MENU_CANCELLED)) {
                optedIn.add(customer.getId());
            }
        }
        Set<UUID> recipients = new LinkedHashSet<>(activeOrderCustomers);
        recipients.addAll(optedIn);
        List<Recipient> result = new ArrayList<>();
        for (UUID customerId : recipients) {
            if (hasAllNotificationsDisabled(customerId)) {
                continue;
            }
            Customer customer = customers.findById(customerId).orElse(null);
            if (customer == null) {
                continue;
            }
            boolean activeOrder = activeOrderCustomers.contains(customerId);
            boolean[] pref = activeOrder ? new boolean[] {true, true}
                : NotificationRules.preference(preferences, customerId, NotificationEventType.MENU_CANCELLED);
            List<NotificationChannel> channels = channels(customer, pref, null, now);
            if (!channels.isEmpty()) {
                result.add(recipient(customer, channels,
                    activeOrder ? List.of("ACTIVE_ORDER") : List.of("MENU_CANCELLED_PREFERENCE"), activeOrder));
            }
        }
        return sorted(result);
    }

    private List<NotificationChannel> channels(Customer customer, boolean[] pref, LocalDate bakeDate, Instant now) {
        List<NotificationChannel> channels = new ArrayList<>();
        if (pref[0] && customer.isEmailVerified() && customer.getEmail() != null) {
            channels.add(NotificationChannel.EMAIL);
        }
        if (pref[1] && customer.hasSmsConsent() && customer.getPhoneE164() != null
                && (bakeDate == null || !SmsWindow.bakeDatePassed(bakeDate, now, zone()))) {
            channels.add(NotificationChannel.SMS);
        }
        return List.copyOf(channels);
    }

    private LocalDate smsBakeDate(NotificationEventType type, Map<String, Object> payload) {
        return NotificationRules.promotional(type) ? NotificationRules.bakeDate(payload) : null;
    }

    private List<Customer> activeCustomers() {
        return customers.findAllByStatus(CustomerStatus.ACTIVE);
    }

    private Recipient recipient(Customer customer, List<NotificationChannel> channels, List<String> reasons,
            boolean bypassPreferences) {
        return new Recipient(customer.getId(), customer.getName(), customer.getEmail(), customer.getPhoneE164(),
            channels, reasons, bypassPreferences);
    }

    private java.time.ZoneId zone() {
        return NotificationRules.zone(bakery.timezone());
    }

    private static List<Recipient> sorted(List<Recipient> recipients) {
        return recipients.stream()
            .sorted(Comparator.comparing(Recipient::name, Comparator.nullsLast(String::compareTo))
                .thenComparing(Recipient::customerId))
            .toList();
    }

    private static void validate(NotificationEventType type, String entityType) {
        if (type == null || !NotificationRules.customerEvents().contains(type)) {
            throw invalid();
        }
        String expected = switch (type) {
            case MENU_PUBLISHED, MENU_CANCELLED -> "MENU";
            case ORDER_REMINDER -> "MENU_DATE";
            default -> "ORDER";
        };
        if (type == NotificationEventType.MENU_CANCELLED) {
            if (!"MENU".equals(entityType) && !"MENU_DATE".equals(entityType)) {
                throw invalid();
            }
            return;
        }
        if (!expected.equals(entityType)) {
            throw invalid();
        }
    }

    private void requireEntity(String entityType, UUID entityId) {
        if (entityId == null) {
            throw invalid();
        }
        boolean exists = switch (entityType) {
            case "MENU" -> menus.existsById(entityId);
            case "MENU_DATE" -> dates.existsById(entityId);
            case "ORDER" -> orders.existsById(entityId);
            default -> false;
        };
        if (!exists) {
            throw new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Entity not found");
        }
    }

    private static AuthException invalid() {
        return AuthException.badRequest("VALIDATION_ERROR", "Invalid event or entity combination");
    }
}

package com.bethanysbakery.api.notification;

import com.bethanysbakery.api.api.order.OrderApi;
import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.auth.audit.AuditService;
import com.bethanysbakery.api.config.BakeryProperties;
import com.bethanysbakery.api.config.NotificationProperties;
import com.bethanysbakery.api.domain.audit.ActorType;
import com.bethanysbakery.api.domain.notification.NotificationEventType;
import com.bethanysbakery.api.domain.order.Order;
import com.bethanysbakery.api.domain.order.OrderRepository;
import com.bethanysbakery.api.order.OrderMetrics;
import com.bethanysbakery.api.order.OrderViews;
import com.bethanysbakery.api.order.OutboxWriter;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Manual payment tracking and admin delay notices. No automated Venmo verification. */
@Service
public class PaymentService {

    private static final List<String> METHODS = List.of("VENMO", "CASH", "OTHER");

    private final OrderRepository orders;
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final NotificationService notifications;
    private final OutboxWriter outbox;
    private final OrderViews views;
    private final BakeryProperties bakery;
    private final NotificationProperties properties;
    private final EntityManager entityManager;
    private final Clock clock;
    private final OrderMetrics metrics;

    public PaymentService(OrderRepository orders, JdbcTemplate jdbc, AuditService audit,
            NotificationService notifications, OutboxWriter outbox, OrderViews views,
            BakeryProperties bakery, NotificationProperties properties, EntityManager entityManager, Clock clock,
            OrderMetrics metrics) {
        this.orders = orders;
        this.jdbc = jdbc;
        this.audit = audit;
        this.notifications = notifications;
        this.outbox = outbox;
        this.views = views;
        this.bakery = bakery;
        this.properties = properties;
        this.entityManager = entityManager;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Transactional
    public OrderApi.Order updatePayment(UUID orderId, boolean paid, String requestedMethod, UUID adminId) {
        Order order = find(orderId);
        if (!NotificationRules.ACTIVE_ORDER_STATUSES.contains(order.getStatus())) {
            throw new AuthException(HttpStatus.CONFLICT, "ORDER_NOT_PAYABLE", "Order is not payable");
        }
        String method = paid ? method(requestedMethod) : null;
        if (paid && (properties.adminAlertEmail() == null || properties.adminAlertEmail().isBlank())) {
            throw AuthException.notConfigured("ADMIN_ALERT_EMAIL is not configured");
        }
        boolean alreadyPaid = order.getPaymentStatus() == com.bethanysbakery.api.domain.order.PaymentStatus.PAID;
        if (paid == alreadyPaid) {
            return views.order(order);
        }
        Instant now = clock.instant();
        Map<String, Object> before = Map.of("paymentStatus", order.getPaymentStatus().name(),
            "paymentMethod", String.valueOf(order.getPaymentMethod()), "paidAt", String.valueOf(order.getPaidAt()));
        clear();

        if (paid) {
            jdbc.update("""
                UPDATE orders SET payment_status = 'PAID', paid_at = ?, payment_method = ?, updated_at = ?
                WHERE id = ?::uuid
                """, Timestamp.from(now), method, Timestamp.from(now), orderId.toString());
            outbox.write("ORDER", orderId, "ORDER_PAID", Map.of("orderId", orderId.toString(),
                "paidAt", now.toString(), "method", method), NotificationKeys.paid(orderId), now);
            clear();
            Order refreshed = orders.findById(orderId).orElseThrow();
            notifications.enqueueAdminEmail(NotificationEventType.ORDER_PAID, "ORDER", orderId,
                NotificationKeys.paid(orderId), NotificationPayloads.forOrder(refreshed, appBaseUrl(),
                    bakery.venmo().handle(), bakery.venmo().displayName()));
            audit.record(ActorType.ADMIN, adminId, "ORDER_PAID", "ORDER", orderId, before,
                Map.of("paymentStatus", "PAID", "paymentMethod", method, "paidAt", now.toString()));
            metrics.paid();
        } else {
            jdbc.update("""
                UPDATE orders SET payment_status = 'UNPAID', paid_at = NULL, payment_method = NULL, updated_at = ?
                WHERE id = ?::uuid
                """, Timestamp.from(now), orderId.toString());
            Map<String, Object> after = new java.util.LinkedHashMap<>();
            after.put("paymentStatus", "UNPAID");
            after.put("paymentMethod", null);
            after.put("paidAt", null);
            audit.record(ActorType.ADMIN, adminId, "ORDER_PAYMENT_CORRECTED", "ORDER", orderId, before, after);
        }
        return views.order(orders.findById(orderId).orElseThrow());
    }

    @Transactional
    public void notifyDelayed(UUID orderId, String reason, UUID adminId) {
        Order order = find(orderId);
        if (!order.getStatus().isActive()) {
            throw new AuthException(HttpStatus.CONFLICT, "ORDER_NOT_ACTIVE", "Order is not active");
        }
        String detail = reason == null || reason.isBlank() ? "Preparation delayed" : reason.trim();
        if (detail.length() > 500) {
            throw AuthException.badRequest("VALIDATION_ERROR", "reason must be at most 500 characters");
        }
        Instant now = clock.instant();
        clear();
        jdbc.update("""
            UPDATE orders SET delay_notified_at = ?, delay_reason = ?, updated_at = ? WHERE id = ?::uuid
            """, Timestamp.from(now), detail, Timestamp.from(now), orderId.toString());
        Order refreshed = orders.findById(orderId).orElseThrow();
        String key = NotificationKeys.orderEvent("ORDER_DELAYED", orderId);
        outbox.write("ORDER", orderId, "ORDER_DELAYED", Map.of("orderId", orderId.toString(), "reason", detail),
            key, now);
        Map<String, Object> payload = NotificationPayloads.forOrder(refreshed, appBaseUrl(),
            bakery.venmo().handle(), bakery.venmo().displayName());
        payload.put("delayReason", detail);
        notifications.enqueueCustomer(order.getCustomerId(), NotificationEventType.ORDER_DELAYED, "ORDER",
            orderId, key, payload, now);
        audit.record(ActorType.ADMIN, adminId, "ORDER_DELAY_NOTIFIED", "ORDER", orderId,
            Map.of("delayNotifiedAt", String.valueOf(order.getDelayNotifiedAt())),
            Map.of("delayNotifiedAt", now.toString(), "delayReason", detail));
        metrics.delayed();
    }

    private void clear() {
        entityManager.flush();
        entityManager.clear();
    }

    private Order find(UUID orderId) {
        return orders.findById(orderId)
            .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Order not found"));
    }

    private static String method(String requested) {
        if (requested == null) {
            return "VENMO";
        }
        if (!METHODS.contains(requested)) {
            throw AuthException.badRequest("VALIDATION_ERROR", "Invalid payment method");
        }
        return requested;
    }

    private String appBaseUrl() {
        return bakery.auth().appPublicBaseUrl();
    }
}

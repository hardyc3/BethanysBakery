package com.bethanysbakery.api.order;

import com.bethanysbakery.api.auth.audit.AuditService;
import com.bethanysbakery.api.domain.audit.ActorType;
import com.bethanysbakery.api.domain.order.Order;
import com.bethanysbakery.api.domain.order.OrderLine;
import com.bethanysbakery.api.domain.order.OrderRepository;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Central, idempotent inventory release for cancelled, rejected, and expired orders. */
@Service
public class InventoryService {

    private final JdbcTemplate jdbc;
    private final OrderRepository orders;
    private final AuditService audit;
    private final Clock clock;
    private final EntityManager entityManager;

    public InventoryService(JdbcTemplate jdbc, OrderRepository orders, AuditService audit, Clock clock,
            EntityManager entityManager) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.audit = audit;
        this.clock = clock;
        this.entityManager = entityManager;
    }

    /** Claims the release with a conditional update; only one caller decrements counters. */
    @Transactional
    public boolean release(UUID orderId) {
        // Flush managed JPA changes first so a later commit cannot overwrite JDBC counter decrements.
        entityManager.flush();
        Instant now = clock.instant();
        int claimed = jdbc.update("""
            UPDATE orders SET inventory_released_at = ?, updated_at = ?
            WHERE id = ?::uuid AND inventory_released_at IS NULL
            """, Timestamp.from(now), Timestamp.from(now), orderId.toString());
        if (claimed == 0) {
            return false;
        }

        Order order = orders.findById(orderId).orElseThrow();
        jdbc.update("""
            UPDATE pickup_window
            SET reserved_orders = GREATEST(0, reserved_orders - 1), updated_at = ?
            WHERE id = ?::uuid
            """, Timestamp.from(now), order.getPickupWindow().getId().toString());

        Map<UUID, Integer> quantities = new LinkedHashMap<>();
        for (OrderLine line : order.getLines()) {
            quantities.merge(line.getOfferingId(), line.getQuantity(), Integer::sum);
        }
        quantities.forEach((offeringId, quantity) -> jdbc.update("""
            UPDATE offering
            SET reserved_count = GREATEST(0, reserved_count - ?), updated_at = ?
            WHERE id = ?::uuid
            """, quantity, Timestamp.from(now), offeringId.toString()));

        audit.record(ActorType.SYSTEM, null, "ORDER_INVENTORY_RELEASED", "ORDER", orderId,
            null, Map.of("orderNumber", order.getOrderNumber(), "at", now.toString()));
        return true;
    }
}

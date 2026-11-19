package com.bethanysbakery.api.notification;

import com.bethanysbakery.api.domain.order.Order;
import com.bethanysbakery.api.domain.order.OrderRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Consumes the V5 outbox table; notification rows are the durable fan-out of each event. */
@Service
public class OutboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(OutboxProcessor.class);

    private final JdbcTemplate jdbc;
    private final NotificationService notifications;
    private final OrderRepository orders;
    private final Clock clock;

    public OutboxProcessor(JdbcTemplate jdbc, NotificationService notifications,
            OrderRepository orders, Clock clock) {
        this.jdbc = jdbc;
        this.notifications = notifications;
        this.orders = orders;
        this.clock = clock;
    }

    @Transactional
    public int processDue() {
        Instant now = clock.instant();
        List<Row> rows = jdbc.query("""
                SELECT id, aggregate_id, event_type, attempts FROM outbox_event
                WHERE status = 'PENDING' AND available_at <= ?
                ORDER BY available_at, id LIMIT 50 FOR UPDATE SKIP LOCKED
                """,
            (rs, index) -> new Row(UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("aggregate_id")), rs.getString("event_type"), rs.getInt("attempts")),
            Timestamp.from(now));
        for (Row row : rows) {
            try {
                if ("OrderCreated".equals(row.eventType())) {
                    Order order = orders.findById(row.aggregateId()).orElseThrow();
                    notifications.enqueueAdminNewOrder(order);
                }
                // Order-status events already have their notification rows written in the business
                // transaction; every drained row must still reach a terminal state.
                jdbc.update("UPDATE outbox_event SET status = 'PROCESSED', processed_at = ? WHERE id = ?::uuid",
                    Timestamp.from(now), row.id().toString());
            } catch (RuntimeException ex) {
                int failures = row.attempts() + 1;
                String error = ex.getClass().getSimpleName();
                Instant next = NotificationRetryPolicy.nextAttempt(failures, now);
                if (next == null) {
                    jdbc.update("""
                        UPDATE outbox_event SET status = 'FAILED', attempts = ?, last_error = ?, processed_at = ?
                        WHERE id = ?::uuid
                        """, failures, error, Timestamp.from(now), row.id().toString());
                } else {
                    jdbc.update("""
                        UPDATE outbox_event SET attempts = ?, available_at = ?, last_error = ? WHERE id = ?::uuid
                        """, failures, Timestamp.from(next), error, row.id().toString());
                }
                log.warn("Outbox event {} failed ({})", row.id(), row.eventType(), ex);
            }
        }
        return rows.size();
    }

    private record Row(UUID id, UUID aggregateId, String eventType, int attempts) {}
}

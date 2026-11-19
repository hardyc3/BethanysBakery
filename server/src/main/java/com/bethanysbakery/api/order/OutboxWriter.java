package com.bethanysbakery.api.order;

import com.bethanysbakery.api.common.Uuids;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Writes durable outbox rows inside the caller's transaction. */
@Component
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public OutboxWriter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void orderCreated(UUID orderId, String orderNumber, Instant now) {
        write("ORDER", orderId, "OrderCreated",
            Map.of("orderId", orderId.toString(), "orderNumber", orderNumber),
            "order:" + orderId + ":created", now);
    }

    private void write(String aggregateType, UUID aggregateId, String eventType,
            Map<String, Object> payload, String idempotencyKey, Instant now) {
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Could not serialize outbox payload", ex);
        }
        jdbc.update("""
            INSERT INTO outbox_event (id, aggregate_type, aggregate_id, event_type, payload,
                status, attempts, available_at, idempotency_key, created_at)
            VALUES (?::uuid, ?, ?::uuid, ?, ?::jsonb, 'PENDING', 0, ?, ?, ?)
            ON CONFLICT (idempotency_key) WHERE idempotency_key IS NOT NULL DO NOTHING
            """,
            Uuids.v7().toString(), aggregateType, aggregateId.toString(), eventType, json,
            Timestamp.from(now), idempotencyKey, Timestamp.from(now));
    }
}

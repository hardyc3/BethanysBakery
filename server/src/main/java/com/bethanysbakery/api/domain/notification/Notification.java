package com.bethanysbakery.api.domain.notification;

import com.bethanysbakery.api.common.Uuids;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "notification")
public class Notification {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "recipient_type", nullable = false)
    private RecipientType recipientType;

    @Column(name = "customer_id")
    private UUID customerId;

    @Column(name = "admin_user_id")
    private UUID adminUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false)
    private NotificationEventType eventType;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private NotificationStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @OneToMany(mappedBy = "notification", cascade = CascadeType.ALL, orphanRemoval = true,
        fetch = FetchType.LAZY)
    private List<NotificationDelivery> deliveries = new ArrayList<>();

    protected Notification() {}

    public Notification(RecipientType recipientType, UUID customerId, UUID adminUserId,
            NotificationEventType eventType, String entityType, UUID entityId, String idempotencyKey,
            Map<String, Object> payload, Instant createdAt, Instant scheduledAt) {
        this.id = Uuids.v7();
        this.recipientType = recipientType;
        this.customerId = customerId;
        this.adminUserId = adminUserId;
        this.eventType = eventType;
        this.entityType = entityType;
        this.entityId = entityId;
        this.idempotencyKey = idempotencyKey;
        this.payload = payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
        this.status = NotificationStatus.QUEUED;
        this.attempts = 0;
        this.createdAt = createdAt;
        this.scheduledAt = scheduledAt;
    }

    public UUID getId() { return id; }
    public RecipientType getRecipientType() { return recipientType; }
    public UUID getCustomerId() { return customerId; }
    public UUID getAdminUserId() { return adminUserId; }
    public NotificationEventType getEventType() { return eventType; }
    public String getEntityType() { return entityType; }
    public UUID getEntityId() { return entityId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Map<String, Object> getPayload() { return payload; }
    public NotificationStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getScheduledAt() { return scheduledAt; }
    public Instant getSentAt() { return sentAt; }
    public List<NotificationDelivery> getDeliveries() { return deliveries; }

    public void addDelivery(NotificationDelivery delivery) {
        deliveries.add(delivery);
    }

    public void setScheduledAt(Instant scheduledAt) { this.scheduledAt = scheduledAt; }

    public void markSending() { this.status = NotificationStatus.SENDING; }

    public void markQueued() { this.status = NotificationStatus.QUEUED; }

    public void markSent(Instant now) {
        this.status = NotificationStatus.SENT;
        this.sentAt = now;
        this.lastError = null;
    }

    public void markSuppressed(String reason) {
        this.status = NotificationStatus.SUPPRESSED;
        this.lastError = reason;
    }

    public void fail(String error, Instant nextAttempt) {
        this.attempts++;
        this.lastError = error;
        if (nextAttempt == null) {
            this.status = NotificationStatus.FAILED;
        } else {
            this.status = NotificationStatus.QUEUED;
            this.scheduledAt = nextAttempt;
        }
    }

    public void requeue(Instant now) {
        this.status = NotificationStatus.QUEUED;
        this.attempts = 0;
        this.lastError = null;
        this.scheduledAt = now;
        this.sentAt = null;
        for (NotificationDelivery delivery : deliveries) {
            delivery.requeue();
        }
    }
}

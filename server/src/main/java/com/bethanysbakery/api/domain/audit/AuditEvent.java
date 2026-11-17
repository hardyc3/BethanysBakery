package com.bethanysbakery.api.domain.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "audit_event")
public class AuditEvent {
    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false)
    private ActorType actorType;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(nullable = false)
    private String action;

    @Column(name = "entity_type", nullable = false)
    private String entityType;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_json", columnDefinition = "jsonb")
    private Map<String, Object> beforeJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_json", columnDefinition = "jsonb")
    private Map<String, Object> afterJson;

    @Column(name = "request_id")
    private String requestId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuditEvent() {}

    public AuditEvent(UUID id, ActorType actorType, UUID actorId, String action, String entityType, UUID entityId,
            Map<String, Object> beforeJson, Map<String, Object> afterJson, String requestId, Instant createdAt) {
        this.id = id;
        this.actorType = actorType;
        this.actorId = actorId;
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.beforeJson = beforeJson;
        this.afterJson = afterJson;
        this.requestId = requestId;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public ActorType getActorType() { return actorType; }
    public UUID getActorId() { return actorId; }
    public String getAction() { return action; }
    public String getEntityType() { return entityType; }
    public UUID getEntityId() { return entityId; }
    public Map<String, Object> getBeforeJson() { return beforeJson; }
    public Map<String, Object> getAfterJson() { return afterJson; }
    public String getRequestId() { return requestId; }
    public Instant getCreatedAt() { return createdAt; }
}

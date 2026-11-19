package com.bethanysbakery.api.domain.menu;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "menu")
public class Menu {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String title;

    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MenuStatus status;

    @Column(name = "notification_enabled", nullable = false)
    private boolean notificationEnabled;

    @Column(name = "notify_at")
    private Instant notifyAt;

    @Column(name = "notify_sent_at")
    private Instant notifySentAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancellation_reason")
    private String cancellationReason;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Menu() {}

    public Menu(UUID id, String title, String notes, UUID createdBy, Instant createdAt) {
        this.id = id;
        this.title = title;
        this.notes = notes;
        this.status = MenuStatus.DRAFT;
        this.notificationEnabled = true;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getTitle() { return title; }
    public String getNotes() { return notes; }
    public MenuStatus getStatus() { return status; }
    public boolean isNotificationEnabled() { return notificationEnabled; }
    public Instant getNotifyAt() { return notifyAt; }
    public Instant getNotifySentAt() { return notifySentAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public String getCancellationReason() { return cancellationReason; }
    public int getVersion() { return version; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void update(String title, String notes, Instant now) {
        this.title = title;
        this.notes = notes;
        this.updatedAt = now;
    }

    public void publish(boolean notificationEnabled, Instant notifyAt, Instant now) {
        this.status = MenuStatus.PUBLISHED;
        this.notificationEnabled = notificationEnabled;
        this.notifyAt = notifyAt;
        this.publishedAt = now;
        this.updatedAt = now;
    }

    public void markNotifySent(Instant now) {
        this.notifySentAt = now;
        this.updatedAt = now;
    }

    public void cancel(String reason, Instant now) {
        this.status = MenuStatus.CANCELLED;
        this.cancelledAt = now;
        this.cancellationReason = reason;
        this.updatedAt = now;
    }
}

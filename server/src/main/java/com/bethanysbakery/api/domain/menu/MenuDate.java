package com.bethanysbakery.api.domain.menu;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "menu_date")
public class MenuDate {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "menu_id", nullable = false)
    private Menu menu;

    @Column(name = "bake_date", nullable = false)
    private LocalDate bakeDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MenuDateStatus status;

    @Column(name = "reminder_sent_at")
    private Instant reminderSentAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancellation_reason")
    private String cancellationReason;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MenuDate() {}

    public MenuDate(UUID id, Menu menu, LocalDate bakeDate, Instant createdAt) {
        this.id = id;
        this.menu = menu;
        this.bakeDate = bakeDate;
        this.status = MenuDateStatus.DRAFT;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public UUID getId() { return id; }
    public Menu getMenu() { return menu; }
    public UUID getMenuId() { return menu.getId(); }
    public LocalDate getBakeDate() { return bakeDate; }
    public MenuDateStatus getStatus() { return status; }
    public Instant getReminderSentAt() { return reminderSentAt; }
    public Instant getCancelledAt() { return cancelledAt; }
    public String getCancellationReason() { return cancellationReason; }
    public int getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void update(LocalDate bakeDate, Instant now) {
        this.bakeDate = bakeDate;
        this.updatedAt = now;
    }

    public void publish(Instant now) {
        this.status = MenuDateStatus.PUBLISHED;
        this.updatedAt = now;
    }

    public void cancel(String reason, Instant now) {
        this.status = MenuDateStatus.CANCELLED;
        this.cancelledAt = now;
        this.cancellationReason = reason;
        this.updatedAt = now;
    }

    public boolean isActive() {
        return status != MenuDateStatus.CANCELLED;
    }
}

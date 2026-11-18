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
import java.util.UUID;

@Entity
@Table(name = "pickup_window")
public class PickupWindow {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "menu_date_id", nullable = false)
    private MenuDate menuDate;

    @Column(nullable = false)
    private String label;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Column(name = "max_orders", nullable = false)
    private int maxOrders;

    @Column(name = "reserved_orders", nullable = false)
    private int reservedOrders;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ActiveStatus status;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PickupWindow() {}

    public PickupWindow(UUID id, MenuDate menuDate, String label, Instant startAt, Instant endAt,
            int maxOrders, Instant createdAt) {
        this.id = id;
        this.menuDate = menuDate;
        this.label = label;
        this.startAt = startAt;
        this.endAt = endAt;
        this.maxOrders = maxOrders;
        this.reservedOrders = 0;
        this.status = ActiveStatus.ACTIVE;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public UUID getId() { return id; }
    public MenuDate getMenuDate() { return menuDate; }
    public UUID getMenuDateId() { return menuDate.getId(); }
    public String getLabel() { return label; }
    public Instant getStartAt() { return startAt; }
    public Instant getEndAt() { return endAt; }
    public int getMaxOrders() { return maxOrders; }
    public int getReservedOrders() { return reservedOrders; }
    public ActiveStatus getStatus() { return status; }
    public int getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public int availableCapacity() { return Math.max(0, maxOrders - reservedOrders); }
    public boolean isActive() { return status == ActiveStatus.ACTIVE; }

    public void update(String label, Instant startAt, Instant endAt, int maxOrders, Instant now) {
        this.label = label;
        this.startAt = startAt;
        this.endAt = endAt;
        this.maxOrders = maxOrders;
        this.updatedAt = now;
    }

    public void cancel(Instant now) {
        this.status = ActiveStatus.CANCELLED;
        this.updatedAt = now;
    }
}

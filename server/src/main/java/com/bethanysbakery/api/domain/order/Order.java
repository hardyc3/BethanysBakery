package com.bethanysbakery.api.domain.order;

import com.bethanysbakery.api.domain.menu.MenuDate;
import com.bethanysbakery.api.domain.menu.PickupWindow;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    private UUID id;

    @Column(name = "order_number", nullable = false, unique = true)
    private String orderNumber;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "menu_date_id", nullable = false)
    private MenuDate menuDate;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "pickup_window_id", nullable = false)
    private PickupWindow pickupWindow;

    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false)
    private PaymentStatus paymentStatus;

    @Column(name = "payment_method")
    private String paymentMethod;

    @Column(name = "subtotal_cents", nullable = false)
    private int subtotalCents;

    @Column(name = "total_cents", nullable = false)
    private int totalCents;

    @Column(name = "customer_name", nullable = false)
    private String customerName;

    @Column(name = "customer_email", columnDefinition = "citext")
    private String customerEmail;

    @Column(name = "customer_phone")
    private String customerPhone;

    private String notes;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "cancellation_reason")
    private String cancellationReason;

    @Column(name = "cancelled_by")
    @Enumerated(EnumType.STRING)
    private CancelledBy cancelledBy;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "ready_at")
    private Instant readyAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "delay_notified_at")
    private Instant delayNotifiedAt;

    @Column(name = "delay_reason")
    private String delayReason;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "inventory_released_at")
    private Instant inventoryReleasedAt;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("id ASC")
    private List<OrderLine> lines = new ArrayList<>();

    protected Order() {}

    public Order(UUID id, String orderNumber, UUID customerId, MenuDate menuDate, PickupWindow pickupWindow,
            String idempotencyKey, OrderStatus status, PaymentStatus paymentStatus, int subtotalCents,
            int totalCents, String customerName, String customerEmail, String customerPhone, String notes,
            Instant expiresAt, Instant createdAt) {
        this.id = id;
        this.orderNumber = orderNumber;
        this.customerId = customerId;
        this.menuDate = menuDate;
        this.pickupWindow = pickupWindow;
        this.idempotencyKey = idempotencyKey;
        this.status = status;
        this.paymentStatus = paymentStatus;
        this.subtotalCents = subtotalCents;
        this.totalCents = totalCents;
        this.customerName = customerName;
        this.customerEmail = customerEmail;
        this.customerPhone = customerPhone;
        this.notes = notes;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getOrderNumber() { return orderNumber; }
    public UUID getCustomerId() { return customerId; }
    public MenuDate getMenuDate() { return menuDate; }
    public PickupWindow getPickupWindow() { return pickupWindow; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public OrderStatus getStatus() { return status; }
    public PaymentStatus getPaymentStatus() { return paymentStatus; }
    public String getPaymentMethod() { return paymentMethod; }
    public int getSubtotalCents() { return subtotalCents; }
    public int getTotalCents() { return totalCents; }
    public String getCustomerName() { return customerName; }
    public String getCustomerEmail() { return customerEmail; }
    public String getCustomerPhone() { return customerPhone; }
    public String getNotes() { return notes; }
    public String getRejectionReason() { return rejectionReason; }
    public String getCancellationReason() { return cancellationReason; }
    public CancelledBy getCancelledBy() { return cancelledBy; }
    public Instant getAcceptedAt() { return acceptedAt; }
    public Instant getReadyAt() { return readyAt; }
    public Instant getCompletedAt() { return completedAt; }
    public Instant getPaidAt() { return paidAt; }
    public Instant getDelayNotifiedAt() { return delayNotifiedAt; }
    public String getDelayReason() { return delayReason; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getInventoryReleasedAt() { return inventoryReleasedAt; }
    public int getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<OrderLine> getLines() { return lines; }
}

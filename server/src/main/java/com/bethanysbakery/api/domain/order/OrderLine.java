package com.bethanysbakery.api.domain.order;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "order_line")
public class OrderLine {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "offering_id", nullable = false)
    private UUID offeringId;

    @Column(name = "item_name", nullable = false)
    private String itemName;

    @Column(name = "unit_price_cents", nullable = false)
    private int unitPriceCents;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "line_total_cents", nullable = false)
    private int lineTotalCents;

    private String notes;

    @OneToMany(mappedBy = "line", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("id ASC")
    private List<OrderLineOption> options = new ArrayList<>();

    protected OrderLine() {}

    public OrderLine(UUID id, Order order, UUID offeringId, String itemName, int unitPriceCents,
            int quantity, int lineTotalCents, String notes) {
        this.id = id;
        this.order = order;
        this.offeringId = offeringId;
        this.itemName = itemName;
        this.unitPriceCents = unitPriceCents;
        this.quantity = quantity;
        this.lineTotalCents = lineTotalCents;
        this.notes = notes;
    }

    public UUID getId() { return id; }
    public Order getOrder() { return order; }
    public UUID getOfferingId() { return offeringId; }
    public String getItemName() { return itemName; }
    public int getUnitPriceCents() { return unitPriceCents; }
    public int getQuantity() { return quantity; }
    public int getLineTotalCents() { return lineTotalCents; }
    public String getNotes() { return notes; }
    public List<OrderLineOption> getOptions() { return options; }
}

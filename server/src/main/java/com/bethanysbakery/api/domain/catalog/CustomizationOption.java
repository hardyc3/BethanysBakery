package com.bethanysbakery.api.domain.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "customization_option")
public class CustomizationOption {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private CustomizationGroup group;

    @Column(nullable = false)
    private String name;

    @Column(name = "price_delta_cents", nullable = false)
    private int priceDeltaCents;

    @Column(nullable = false)
    private boolean available;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected CustomizationOption() {}

    public CustomizationOption(UUID id, CustomizationGroup group, String name, int priceDeltaCents,
            boolean available, int sortOrder) {
        this.id = id;
        this.group = group;
        this.name = name;
        this.priceDeltaCents = priceDeltaCents;
        this.available = available;
        this.sortOrder = sortOrder;
    }

    public UUID getId() { return id; }
    public CustomizationGroup getGroup() { return group; }
    public String getName() { return name; }
    public int getPriceDeltaCents() { return priceDeltaCents; }
    public boolean isAvailable() { return available; }
    public int getSortOrder() { return sortOrder; }

    public void update(String name, int priceDeltaCents, boolean available, int sortOrder) {
        this.name = name;
        this.priceDeltaCents = priceDeltaCents;
        this.available = available;
        this.sortOrder = sortOrder;
    }
}

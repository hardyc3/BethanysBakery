package com.bethanysbakery.api.domain.menu;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "offering_option")
public class OfferingOption {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "group_id", nullable = false)
    private OfferingOptionGroup group;

    @Column(name = "source_option_id")
    private UUID sourceOptionId;

    @Column(nullable = false)
    private String name;

    @Column(name = "price_delta_cents", nullable = false)
    private int priceDeltaCents;

    @Column(nullable = false)
    private boolean available;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected OfferingOption() {}

    public OfferingOption(UUID id, OfferingOptionGroup group, UUID sourceOptionId, String name,
            int priceDeltaCents, boolean available, int sortOrder) {
        this.id = id;
        this.group = group;
        this.sourceOptionId = sourceOptionId;
        this.name = name;
        this.priceDeltaCents = priceDeltaCents;
        this.available = available;
        this.sortOrder = sortOrder;
    }

    public UUID getId() { return id; }
    public OfferingOptionGroup getGroup() { return group; }
    public UUID getSourceOptionId() { return sourceOptionId; }
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

    public void attachTo(OfferingOptionGroup group) {
        this.group = group;
    }
}

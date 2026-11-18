package com.bethanysbakery.api.domain.menu;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "offering_window")
public class OfferingWindow {

    @EmbeddedId
    private OfferingWindowId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("offeringId")
    @JoinColumn(name = "offering_id", nullable = false)
    private Offering offering;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("pickupWindowId")
    @JoinColumn(name = "pickup_window_id", nullable = false)
    private PickupWindow pickupWindow;

    protected OfferingWindow() {}

    public OfferingWindow(Offering offering, PickupWindow pickupWindow) {
        this.offering = offering;
        this.pickupWindow = pickupWindow;
        this.id = new OfferingWindowId(offering.getId(), pickupWindow.getId());
    }

    public UUID getPickupWindowId() { return pickupWindow.getId(); }
}

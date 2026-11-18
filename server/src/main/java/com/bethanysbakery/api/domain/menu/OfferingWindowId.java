package com.bethanysbakery.api.domain.menu;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class OfferingWindowId implements Serializable {

    private UUID offeringId;
    private UUID pickupWindowId;

    protected OfferingWindowId() {}

    public OfferingWindowId(UUID offeringId, UUID pickupWindowId) {
        this.offeringId = offeringId;
        this.pickupWindowId = pickupWindowId;
    }

    public UUID getOfferingId() { return offeringId; }
    public UUID getPickupWindowId() { return pickupWindowId; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof OfferingWindowId that)) return false;
        return Objects.equals(offeringId, that.offeringId)
            && Objects.equals(pickupWindowId, that.pickupWindowId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(offeringId, pickupWindowId);
    }
}

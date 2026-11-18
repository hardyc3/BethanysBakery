package com.bethanysbakery.api.domain.menu;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OfferingWindowRepository extends JpaRepository<OfferingWindow, OfferingWindowId> {

    boolean existsByPickupWindow_Id(UUID pickupWindowId);
}

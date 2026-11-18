package com.bethanysbakery.api.domain.menu;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PickupWindowRepository extends JpaRepository<PickupWindow, UUID> {

    List<PickupWindow> findAllByMenuDate_IdOrderByStartAtAsc(UUID menuDateId);
}

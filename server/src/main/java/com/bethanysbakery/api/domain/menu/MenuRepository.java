package com.bethanysbakery.api.domain.menu;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MenuRepository extends JpaRepository<Menu, UUID> {

    List<Menu> findAllByOrderByCreatedAtDesc();

    List<Menu> findAllByStatusOrderByCreatedAtDesc(MenuStatus status);

    List<Menu> findAllByStatusAndNotificationEnabledTrueAndNotifyAtLessThanEqualAndNotifySentAtIsNull(
        MenuStatus status, Instant now);
}

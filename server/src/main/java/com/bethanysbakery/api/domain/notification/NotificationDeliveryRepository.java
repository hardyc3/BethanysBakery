package com.bethanysbakery.api.domain.notification;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, UUID> {
    List<NotificationDelivery> findByNotificationIdOrderByIdAsc(UUID notificationId);
}

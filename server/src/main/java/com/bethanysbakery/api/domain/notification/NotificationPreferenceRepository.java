package com.bethanysbakery.api.domain.notification;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, UUID> {

    Optional<NotificationPreference> findByCustomerIdAndEventType(UUID customerId, NotificationEventType eventType);

    List<NotificationPreference> findAllByCustomerId(UUID customerId);

    List<NotificationPreference> findAllByEventType(NotificationEventType eventType);
}

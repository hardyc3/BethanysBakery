package com.bethanysbakery.api.domain.order;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface OrderRepository extends JpaRepository<Order, UUID>, JpaSpecificationExecutor<Order> {

    Optional<Order> findByIdAndCustomerId(UUID id, UUID customerId);

    Optional<Order> findByCustomerIdAndIdempotencyKey(UUID customerId, String idempotencyKey);

    List<Order> findAllByMenuDate_IdAndStatusIn(UUID menuDateId, List<OrderStatus> statuses);
}

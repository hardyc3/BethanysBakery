package com.bethanysbakery.api.domain.order;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderLineRepository extends JpaRepository<OrderLine, UUID> {}

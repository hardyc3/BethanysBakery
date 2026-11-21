package com.bethanysbakery.api.domain.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, UUID> {
    Optional<Customer> findByEmail(String email);
    Optional<Customer> findByPhoneE164(String phoneE164);
    boolean existsByEmailAndIdNotAndStatus(String email, UUID id, CustomerStatus status);
    boolean existsByPhoneE164AndIdNotAndStatus(String phoneE164, UUID id, CustomerStatus status);
    List<Customer> findAllByStatus(CustomerStatus status);
}

package com.bethanysbakery.api.domain.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminUserRepository extends JpaRepository<AdminUser, UUID> {
    Optional<AdminUser> findByEmail(String email);
    Optional<AdminUser> findFirstByStatus(AdminUserStatus status);
    boolean existsByStatus(AdminUserStatus status);
}

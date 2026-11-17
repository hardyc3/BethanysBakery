package com.bethanysbakery.api.domain.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminDeviceRepository extends JpaRepository<AdminDevice, UUID> {
    Optional<AdminDevice> findByApiKeyHash(String apiKeyHash);
    Optional<AdminDevice> findByCertFingerprintSha256(String certFingerprintSha256);
    List<AdminDevice> findAllByOrderByPairedAtDesc();
    long countByStatus(AdminDeviceStatus status);
}

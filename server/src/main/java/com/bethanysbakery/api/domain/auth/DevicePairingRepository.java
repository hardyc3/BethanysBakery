package com.bethanysbakery.api.domain.auth;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DevicePairingRepository extends JpaRepository<DevicePairing, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from DevicePairing p where p.codeHash = :codeHash")
    Optional<DevicePairing> findByCodeHashForUpdate(@Param("codeHash") String codeHash);
}

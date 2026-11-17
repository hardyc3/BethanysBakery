package com.bethanysbakery.api.domain.auth;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OtpChallengeRepository extends JpaRepository<OtpChallenge, UUID> {
    long countByDestinationHashAndCreatedAtAfter(String destinationHash, Instant after);
    Optional<OtpChallenge> findFirstByDestinationHashOrderByCreatedAtDesc(String destinationHash);
    Optional<OtpChallenge> findFirstByDestinationHashAndCreatedAtAfterOrderByCreatedAtAsc(String destinationHash, Instant after);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OtpChallenge o where o.id = :id")
    Optional<OtpChallenge> findByIdForUpdate(@Param("id") UUID id);
}

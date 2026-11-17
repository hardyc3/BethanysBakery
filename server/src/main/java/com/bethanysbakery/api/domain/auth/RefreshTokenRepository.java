package com.bethanysbakery.api.domain.auth;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefreshToken r where r.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken r set r.revokedAt = :now where r.familyId = :familyId and r.revokedAt is null")
    int revokeFamily(@Param("familyId") UUID familyId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update RefreshToken r set r.revokedAt = :now
        where r.subjectType = :subjectType and r.subjectId = :subjectId
          and r.familyId <> :keepFamilyId and r.revokedAt is null
        """)
    int revokeOtherFamilies(@Param("subjectType") SubjectType subjectType, @Param("subjectId") UUID subjectId,
            @Param("keepFamilyId") UUID keepFamilyId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RefreshToken r set r.revokedAt = :now where r.deviceId = :deviceId and r.revokedAt is null")
    int revokeDevice(@Param("deviceId") UUID deviceId, @Param("now") Instant now);
}

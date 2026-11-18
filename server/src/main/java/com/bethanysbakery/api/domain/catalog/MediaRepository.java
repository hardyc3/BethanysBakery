package com.bethanysbakery.api.domain.catalog;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MediaRepository extends JpaRepository<Media, UUID> {

    Optional<Media> findByIdAndStatus(UUID id, MediaStatus status);

    /** Pessimistic lock used to make MEDIA_IN_USE checks atomic against reference creation. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from Media m where m.id = :id")
    Optional<Media> findByIdForUpdate(@Param("id") UUID id);
}

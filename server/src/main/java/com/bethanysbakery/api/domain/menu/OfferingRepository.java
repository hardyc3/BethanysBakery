package com.bethanysbakery.api.domain.menu;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OfferingRepository extends JpaRepository<Offering, UUID> {

    List<Offering> findAllByMenuDate_IdOrderByNameAsc(UUID menuDateId);

    boolean existsByPhotoMediaId(UUID photoMediaId);

    boolean existsByMenuDate_IdAndItemTemplateId(UUID menuDateId, UUID itemTemplateId);

    @Query("""
        select o from Offering o join o.menuDate d join d.menu m
        where o.id = :id
          and o.status = com.bethanysbakery.api.domain.menu.ActiveStatus.ACTIVE
          and d.status = com.bethanysbakery.api.domain.menu.MenuDateStatus.PUBLISHED
          and m.status = com.bethanysbakery.api.domain.menu.MenuStatus.PUBLISHED
        """)
    Optional<Offering> findPublicById(@Param("id") UUID id);
}

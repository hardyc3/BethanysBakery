package com.bethanysbakery.api.domain.menu;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MenuDateRepository extends JpaRepository<MenuDate, UUID> {

    List<MenuDate> findAllByMenu_IdOrderByBakeDateAsc(UUID menuId);

    List<MenuDate> findAllByStatus(MenuDateStatus status);

    @Query("""
        select d from MenuDate d join d.menu m
        where m.status = com.bethanysbakery.api.domain.menu.MenuStatus.PUBLISHED
          and d.status = com.bethanysbakery.api.domain.menu.MenuDateStatus.PUBLISHED
          and d.bakeDate between :from and :to
        order by d.bakeDate asc, d.id asc
        """)
    List<MenuDate> findPublicBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("""
        select d from MenuDate d join d.menu m
        where d.id = :id
          and m.status = com.bethanysbakery.api.domain.menu.MenuStatus.PUBLISHED
          and d.status = com.bethanysbakery.api.domain.menu.MenuDateStatus.PUBLISHED
        """)
    Optional<MenuDate> findPublicById(@Param("id") UUID id);
}

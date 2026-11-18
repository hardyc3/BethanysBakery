package com.bethanysbakery.api.domain.menu;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MenuRepository extends JpaRepository<Menu, UUID> {

    List<Menu> findAllByOrderByCreatedAtDesc();

    List<Menu> findAllByStatusOrderByCreatedAtDesc(MenuStatus status);
}

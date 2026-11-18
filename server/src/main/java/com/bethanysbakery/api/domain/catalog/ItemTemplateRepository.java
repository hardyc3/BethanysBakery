package com.bethanysbakery.api.domain.catalog;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemTemplateRepository extends JpaRepository<ItemTemplate, UUID> {

    List<ItemTemplate> findAllByOrderByNameAsc();

    List<ItemTemplate> findAllByActiveOrderByNameAsc(boolean active);

    boolean existsByPhotoMediaId(UUID photoMediaId);
}

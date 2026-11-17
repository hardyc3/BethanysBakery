package com.bethanysbakery.api.auth.audit;

import com.bethanysbakery.api.api.auth.AuthApi;
import com.bethanysbakery.api.domain.audit.AuditEvent;
import com.bethanysbakery.api.domain.audit.AuditEventRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditQueryService {
    private final AuditEventRepository repository;

    public AuditQueryService(AuditEventRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<AuthApi.AuditEventView> list(String entityType, UUID entityId, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(100, Math.max(1, size));
        Pageable pageable = PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<AuditEvent> events;
        if (entityType != null && !entityType.isBlank() && entityId != null) {
            events = repository.findByEntityTypeAndEntityId(entityType, entityId, pageable);
        } else if (entityType != null && !entityType.isBlank()) {
            events = repository.findByEntityType(entityType, pageable);
        } else {
            events = repository.findAll(pageable);
        }
        return events.map(this::toView).getContent();
    }

    private AuthApi.AuditEventView toView(AuditEvent event) {
        return new AuthApi.AuditEventView(
            event.getId(),
            event.getActorType().name(),
            event.getActorId(),
            event.getAction(),
            event.getEntityType(),
            event.getEntityId(),
            event.getBeforeJson(),
            event.getAfterJson(),
            event.getRequestId(),
            event.getCreatedAt()
        );
    }
}

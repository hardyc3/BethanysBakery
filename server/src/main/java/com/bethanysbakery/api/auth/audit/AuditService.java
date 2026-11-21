package com.bethanysbakery.api.auth.audit;

import com.bethanysbakery.api.common.Uuids;
import com.bethanysbakery.api.domain.audit.ActorType;
import com.bethanysbakery.api.domain.audit.AuditEvent;
import com.bethanysbakery.api.domain.audit.AuditEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {
    private static final Pattern SENSITIVE = Pattern.compile(
        "(?i).*(password|passwd|token|secret|otp|code|apikey|api_key|authorization|credential).*"
    );
    private static final Pattern FREE_TEXT_PII = Pattern.compile(
        "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"
            + "|\\+[1-9][0-9]{7,14}"
            + "|(?i)\\b(api[_-]?key|password|token|secret|otp|authorization)\\b\\s*[:=]?\\s*[A-Za-z0-9+/=_-]{6,}"
    );

    public static String scrubFreeText(String value) {
        return value == null ? null : FREE_TEXT_PII.matcher(value).replaceAll("[REDACTED]");
    }

    private final AuditEventRepository repository;
    private final ObjectMapper mapper;

    public AuditService(AuditEventRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional
    public void record(ActorType actorType, UUID actorId, String action, String entityType, UUID entityId,
            Object before, Object after) {
        repository.save(new AuditEvent(
            Uuids.v7(), actorType, actorId, action, entityType, entityId,
            toMap(before), toMap(after), MDC.get("requestId"), Instant.now()
        ));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object value) {
        if (value == null) return null;
        Object redacted = redact(value, null);
        if (redacted instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of("value", redacted);
    }

    private Object redact(Object value, String key) {
        if (key != null && SENSITIVE.matcher(key).matches()) {
            return "[REDACTED]";
        }
        if (value instanceof String text
                && (key == null || key.toLowerCase(java.util.Locale.ROOT).contains("reason")
                    || key.toLowerCase(java.util.Locale.ROOT).contains("notes"))) {
            return scrubFreeText(text);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), redact(v, String.valueOf(k))));
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) {
                result.add(redact(item, null));
            }
            return result;
        }
        if (value instanceof UUID || value instanceof Number || value instanceof Boolean || value instanceof String) {
            return value;
        }
        return mapper.convertValue(value, Object.class);
    }
}

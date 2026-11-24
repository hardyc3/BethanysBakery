package com.bethanysbakery.api.domain.audit;

/** Audit action names shared by order and auth slices. */
public final class AuditActions {

    public static final String ORDER_IDEMPOTENCY_REPLAY = "ORDER_IDEMPOTENCY_REPLAY";

    private AuditActions() {}
}

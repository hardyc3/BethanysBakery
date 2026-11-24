package com.bethanysbakery.api.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.bethanysbakery.api.domain.audit.AuditActions;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for the idempotency replay audit payload shape. */
class OrderReplayAuditPayloadTest {

    private static final Instant CLIENT = Instant.parse("2026-11-19T08:15:30.123Z");
    private static final Instant SERVER = Instant.parse("2026-11-19T09:00:00.456Z");

    @Test
    void payloadContainsOnlyDiagnosticTimestampsAndChangeFlag() {
        Map<String, Object> payload = OrderService.replayAuditPayload(true, CLIENT, SERVER);

        assertThat(AuditActions.ORDER_IDEMPOTENCY_REPLAY).isEqualTo("ORDER_IDEMPOTENCY_REPLAY");
        assertThat(payload).containsEntry("payloadChanged", true)
            .containsEntry("clientSubmittedAt", CLIENT.toString())
            .containsEntry("serverReceivedAt", SERVER.toString());
        assertThat(payload.keySet()).containsExactlyInAnyOrder(
            "payloadChanged", "clientSubmittedAt", "serverReceivedAt");
        assertThat(String.valueOf(payload)).doesNotContain("email", "phone", "notes", "reason", "secret", "token");
    }

    @Test
    void payloadHandlesMissingClientTimestamp() {
        Map<String, Object> payload = OrderService.replayAuditPayload(false, null, SERVER);

        assertThat(payload).containsEntry("payloadChanged", false)
            .containsEntry("clientSubmittedAt", null)
            .containsEntry("serverReceivedAt", SERVER.toString());
    }
}

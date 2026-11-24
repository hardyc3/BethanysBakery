package com.bethanysbakery.api.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bethanysbakery.api.common.Uuids;
import com.bethanysbakery.api.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** S7 rows with channel=PUSH must remain readable on an upgraded V6 schema without a live sender. */
class LegacyPushNotificationIntegrationTest extends IntegrationTestBase {

    private static final String PASSWORD = "correct horse battery staple";
    private static final String EMAIL = "legacy-push@example.com";

    @Autowired
    NotificationDispatchService dispatcher;

    @Test
    void historicalPushDeliveryIsListedAndRetrySuppressesWithoutSending() throws Exception {
        var admin = seedAdmin(EMAIL, PASSWORD, List.of());
        var device = seedDevice(admin.id(), "legacy-push-device-key", "Legacy Device");
        String accessToken = login(admin.totpSecret(), device.apiKey());
        HttpHeaders headers = adminHeaders(accessToken, device.apiKey());
        UUID notificationId = insertHistoricalPushDelivery(admin.id());

        mvc.perform(get("/admin/v1/notifications").headers(headers))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].deliveries[0].channel").value("PUSH"))
            .andExpect(jsonPath("$.items[0].deliveries[0].destinationMasked").value("***oken"))
            .andExpect(jsonPath("$.items[0].deliveries[0].lastError").value("FCM_INVALID_TOKEN"));

        mvc.perform(post("/admin/v1/notifications/{notificationId}/retry", notificationId).headers(headers))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("QUEUED"))
            .andExpect(jsonPath("$.deliveries[0].channel").value("PUSH"));

        dispatcher.dispatchDue();

        assertThat(jdbc.queryForObject(
            "select status from notification_delivery where notification_id = ?::uuid",
            String.class, notificationId.toString())).isEqualTo("SUPPRESSED");
        assertThat(jdbc.queryForObject(
            "select last_error from notification_delivery where notification_id = ?::uuid",
            String.class, notificationId.toString())).isEqualTo("PUSH_RETIRED");
        assertThat(jdbc.queryForObject(
            "select status from notification where id = ?::uuid", String.class, notificationId.toString()))
            .isEqualTo("SENT");
        assertThat(jdbc.queryForObject(
            "select count(*) from notification_delivery where notification_id = ?::uuid and channel <> 'PUSH'",
            Integer.class, notificationId.toString())).isZero();
        assertThat(jdbc.queryForObject(
            "select fcm_token from admin_device where id = ?::uuid", String.class, device.id().toString())).isNull();
        assertThatThrownBy(() -> Class.forName("com.bethanysbakery.api.notification.PushSender"))
            .isInstanceOf(ClassNotFoundException.class);
    }

    private String login(String totpSecret, String deviceKey) throws Exception {
        MvcResult result = mvc.perform(post("/admin/v1/auth/login")
                .header("X-Device-Key", deviceKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "email", EMAIL,
                    "password", PASSWORD,
                    "totpCode", currentTotpCode(totpSecret)))))
            .andExpect(status().isOk())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private static HttpHeaders adminHeaders(String accessToken, String deviceKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.set("X-Device-Key", deviceKey);
        return headers;
    }

    private UUID insertHistoricalPushDelivery(UUID adminId) {
        UUID notificationId = Uuids.v7();
        jdbc.update("""
            INSERT INTO notification (id, recipient_type, customer_id, admin_user_id, event_type,
                entity_type, entity_id, idempotency_key, payload, status, attempts, last_error,
                created_at, scheduled_at, sent_at)
            VALUES (?::uuid, 'ADMIN', NULL, ?::uuid, 'ADMIN_NEW_ORDER', 'ORDER', ?::uuid, ?,
                '{}'::jsonb, 'FAILED', 1, 'FCM_INVALID_TOKEN', now(), now() - interval '1 minute', NULL)
            """, notificationId.toString(), adminId.toString(), Uuids.v7().toString(),
            "legacy-push:" + notificationId);
        jdbc.update("""
            INSERT INTO notification_delivery (id, notification_id, channel, destination, status,
                provider_message_id, attempts, last_error, sent_at)
            VALUES (?::uuid, ?::uuid, 'PUSH', 'legacy-fcm-token', 'FAILED', NULL, 1, 'FCM_INVALID_TOKEN', NULL)
            """, Uuids.v7().toString(), notificationId.toString());
        return notificationId;
    }
}

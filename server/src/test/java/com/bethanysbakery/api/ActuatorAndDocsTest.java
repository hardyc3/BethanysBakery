package com.bethanysbakery.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("local")
@TestPropertySource(properties = "bakery.notifications.scheduling-enabled=false")
class ActuatorAndDocsTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void healthAndInfoAndDocsAvailableInLocal() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/info")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    @Test
    void s6OperationIdsMatchContract() throws Exception {
        JsonNode docs = objectMapper.readTree(mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("get-/api/v1/me/notification-preferences", "getNotificationPreferences");
        expected.put("put-/api/v1/me/notification-preferences", "updateNotificationPreferences");
        expected.put("put-/admin/v1/orders/{orderId}/payment", "updateOrderPayment");
        expected.put("post-/admin/v1/orders/{orderId}/delay", "notifyOrderDelayed");
        expected.put("get-/admin/v1/notifications", "listNotifications");
        expected.put("post-/admin/v1/notifications/{notificationId}/retry", "retryNotification");
        expected.put("post-/api/v1/webhooks/textbee", "receiveTextbeeWebhook");
        expected.put("put-/admin/v1/devices/{deviceId}/push-token", "updateAdminPushToken");
        expected.put("post-/admin/v1/menu-dates/{menuDateId}/remind", "sendMenuDateReminder");
        expected.forEach((key, operationId) -> {
            String[] parts = key.split("-", 2);
            assertThat(docs.at("/paths/" + jsonPointer(parts[1]) + "/" + parts[0] + "/operationId").asText())
                .as(key).isEqualTo(operationId);
        });
    }

    private static String jsonPointer(String path) {
        return path.replace("/", "~1");
    }
}

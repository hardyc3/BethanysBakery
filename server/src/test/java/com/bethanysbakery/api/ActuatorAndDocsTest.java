package com.bethanysbakery.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"bakery.notifications.scheduling-enabled=false", "management.server.port=0"})
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

    @LocalManagementPort
    int managementPort;

    @Test
    void healthAndInfoAndDocsAvailableInLocal() throws Exception {
        assertThat(managementGet("/actuator/health/liveness").statusCode()).isEqualTo(200);
        assertThat(managementGet("/actuator/health/readiness").statusCode()).isEqualTo(200);
        assertThat(managementGet("/actuator/info").statusCode()).isEqualTo(200);
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    private HttpResponse<String> managementGet(String path) throws Exception {
        return HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + path)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
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
        expected.put("post-/admin/v1/menu-dates/{menuDateId}/remind", "sendMenuDateReminder");
        expected.put("post-/admin/v1/notifications/recipient-preview", "previewNotificationRecipients");
        expected.forEach((key, operationId) -> {
            String[] parts = key.split("-", 2);
            assertThat(docs.at("/paths/" + jsonPointer(parts[1]) + "/" + parts[0] + "/operationId").asText())
                .as(key).isEqualTo(operationId);
        });
        List<String> operationIds = new ArrayList<>();
        docs.path("paths").fields().forEachRemaining(path -> path.getValue().fields().forEachRemaining(operation -> {
            JsonNode id = operation.getValue().get("operationId");
            if (id != null && !id.isNull()) {
                operationIds.add(id.asText());
            }
        }));
        assertThat(operationIds).doesNotHaveDuplicates();
    }

    @Test
    void fcmEndpointsAndSchemasAreAbsent() throws Exception {
        JsonNode docs = objectMapper.readTree(mvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(docs.at("/paths/~1admin~1v1~1devices~1{deviceId}~1push-token").isMissingNode()).isTrue();
        assertThat(docs.at("/components/schemas/PushTokenRequest").isMissingNode()).isTrue();
        assertThat(docs.toString().toLowerCase(Locale.ROOT)).doesNotContain("fcm", "firebase");
    }

    private static String jsonPointer(String path) {
        return path.replace("/", "~1");
    }
}

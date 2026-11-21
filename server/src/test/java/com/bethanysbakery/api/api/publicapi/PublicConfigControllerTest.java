package com.bethanysbakery.api.api.publicapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
class PublicConfigControllerTest {

    // Independent copy of docs/specs/server/S8-review-followups.md:77; never derive from the server constant.
    private static final String EXPECTED_NONE_SELECTED_MESSAGE =
        "If you turn off all notifications, you will not receive any email or text message about: new weekly menus, "
            + "reminders before baking day, order accepted, order ready, order delayed, order rejected, order cancelled, "
            + "order expired, or a cancelled menu/date. You can still open the app any time to check your orders, "
            + "payment status, and pickup details. Text messages also require phone verification and are stopped if "
            + "you reply STOP.";

    private static final List<String> EXPECTED_EVENT_TYPES = List.of(
        "MENU_PUBLISHED", "ORDER_REMINDER", "ORDER_ACCEPTED", "ORDER_READY", "ORDER_DELAYED",
        "ORDER_REJECTED", "ORDER_CANCELLED", "ORDER_EXPIRED", "MENU_CANCELLED");

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("bakery.name", () -> "Bethany's Bakery");
        r.add("bakery.timezone", () -> "America/Denver");
        r.add("bakery.pickup-address", () -> "Lehi, Utah");
        r.add("bakery.maps-url", () -> "https://maps.google.com/?q=Lehi");
        r.add("bakery.support-email", () -> "hi@example.com");
        r.add("bakery.order-cutoff-hours", () -> "12");
        r.add("bakery.notifications.mailgun.api-key", () -> "key");
        r.add("bakery.notifications.mailgun.domain", () -> "mg.example.com");
        r.add("bakery.notifications.mailgun.from", () -> "bakery@example.com");
        r.add("bakery.notifications.textbee.base-url", () -> "https://sms.example.com");
        r.add("bakery.notifications.textbee.api-key", () -> "tb-key");
        r.add("bakery.notifications.textbee.device-id", () -> "device-1");
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Test
    void returnsConfigAndRequestId() throws Exception {
        mvc.perform(get("/api/v1/config"))
            .andExpect(status().isOk())
            .andExpect(header().exists("X-Request-Id"))
            .andExpect(jsonPath("$.bakeryName").value("Bethany's Bakery"))
            .andExpect(jsonPath("$.timezone").value("America/Denver"))
            .andExpect(jsonPath("$.pickupAddress").value("Lehi, Utah"))
            .andExpect(jsonPath("$.mapsUrl").value("https://maps.google.com/?q=Lehi"))
            .andExpect(jsonPath("$.supportEmail").value("hi@example.com"))
            .andExpect(jsonPath("$.orderCutoffHours").value(12));
    }

    @Test
    void exposesNineNotificationOptionsAndExactOptOutCopy() throws Exception {
        String response = mvc.perform(get("/api/v1/config"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.notificationOptions.events.length()").value(9))
            .andReturn().getResponse().getContentAsString();

        JsonNode config = objectMapper.readTree(response);
        JsonNode options = config.at("/notificationOptions/events");
        assertThat(options).extracting(node -> node.get("eventType").asText())
            .containsExactlyElementsOf(EXPECTED_EVENT_TYPES);
        assertThat(options).allSatisfy(node -> {
            assertThat(node.get("emailAvailable").asBoolean()).isTrue();
            assertThat(node.get("smsAvailable").asBoolean()).isTrue();
        });
        assertThat(config.at("/notificationOptions/noneSelectedMessage").asText())
            .isEqualTo(EXPECTED_NONE_SELECTED_MESSAGE);
    }
}

package com.bethanysbakery.api.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bethanysbakery.api.auth.session.SessionService;
import com.bethanysbakery.api.common.Uuids;
import com.bethanysbakery.api.domain.auth.Customer;
import com.bethanysbakery.api.domain.auth.CustomerStatus;
import com.bethanysbakery.api.domain.auth.SubjectType;
import com.bethanysbakery.api.domain.menu.Menu;
import com.bethanysbakery.api.domain.menu.MenuDate;
import com.bethanysbakery.api.domain.menu.PickupWindow;
import com.bethanysbakery.api.domain.notification.NotificationEventType;
import com.bethanysbakery.api.domain.notification.NotificationPreference;
import com.bethanysbakery.api.domain.notification.NotificationPreferenceRepository;
import com.bethanysbakery.api.domain.notification.NotificationRepository;
import com.bethanysbakery.api.domain.order.Order;
import com.bethanysbakery.api.domain.order.OrderRepository;
import com.bethanysbakery.api.domain.order.OrderStatus;
import com.bethanysbakery.api.domain.order.PaymentStatus;
import com.bethanysbakery.api.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** S9 read-only recipient preview: audience parity, masking, paging, and authorization. */
@SpringBootTest
class NotificationPreviewIntegrationTest extends IntegrationTestBase {

    private static final String PASSWORD = "correct horse battery staple";

    @Autowired SessionService sessions;
    @Autowired com.bethanysbakery.api.domain.menu.MenuRepository menus;
    @Autowired com.bethanysbakery.api.domain.menu.MenuDateRepository dates;
    @Autowired com.bethanysbakery.api.domain.menu.PickupWindowRepository windows;
    @Autowired OrderRepository orders;
    @Autowired NotificationPreferenceRepository preferences;
    @Autowired NotificationRepository notifications;

    private String accessToken;
    private String deviceKey;
    private String menuId;
    private String dateId;
    private String orderId;
    private String alphaId;
    private String betaId;
    private String gammaId;
    private Cookie buyerCookie;

    @BeforeEach
    void setUp() throws Exception {
        authenticateAdmin();
        Instant now = Instant.now();
        Menu menu = menus.save(new Menu(Uuids.v7(), "Preview menu", null, null, now));
        menuId = menu.getId().toString();
        MenuDate date = dates.save(new MenuDate(Uuids.v7(), menu, LocalDate.now().plusDays(10), now));
        dateId = date.getId().toString();
        PickupWindow window = windows.save(new PickupWindow(Uuids.v7(), date, "Morning",
            now.plusSeconds(3600), now.plusSeconds(7200), 50, now));

        Customer alpha = customer("Alpha", "alpha@preview.test", "+15551235678", true, true);
        Customer beta = customer("Beta", "beta@preview.test", null, true, false);
        Customer gamma = customer("Gamma", "gamma@preview.test", "+15557654321", false, true);
        Customer delta = customer("Delta", "delta@preview.test", "+15551112222", true, true);
        alphaId = alpha.getId().toString();
        betaId = beta.getId().toString();
        gammaId = gamma.getId().toString();

        Order order = orders.save(new Order(Uuids.v7(), "PREVIEW-1", alpha.getId(), date, window, null,
            OrderStatus.PENDING, PaymentStatus.UNPAID, 400, 400, alpha.getName(), alpha.getEmail(),
            alpha.getPhoneE164(), null, now.plusSeconds(3600), now));
        orderId = order.getId().toString();
        orders.save(new Order(Uuids.v7(), "PREVIEW-2", delta.getId(), date, window, null,
            OrderStatus.PENDING, PaymentStatus.UNPAID, 400, 400, delta.getName(), delta.getEmail(),
            delta.getPhoneE164(), null, now.plusSeconds(3600), now));

        for (NotificationEventType type : NotificationService.customerEvents()) {
            preferences.save(new NotificationPreference(delta.getId(), type, false, false, now));
        }

        var tokens = sessions.create(SubjectType.CUSTOMER, alpha.getId(), null, "test", "10.0.0.9");
        buyerCookie = new Cookie("bb_access", tokens.accessToken());
    }

    @Test
    void allNineCustomerEventsResolveAudience() throws Exception {
        assertPreview("MENU_PUBLISHED", "MENU", menuId, 2, 2, 0);
        assertPreview("ORDER_REMINDER", "MENU_DATE", dateId, 1, 1, 0);
        for (String event : List.of("ORDER_ACCEPTED", "ORDER_READY", "ORDER_DELAYED", "ORDER_REJECTED",
                "ORDER_CANCELLED", "ORDER_EXPIRED")) {
            assertPreview(event, "ORDER", orderId, 1,
                event.equals("ORDER_EXPIRED") ? 1 : 1,
                event.equals("ORDER_EXPIRED") ? 0 : 1);
        }
        assertPreview("MENU_CANCELLED", "MENU_DATE", dateId, 3, 2, 2);
        assertPreview("MENU_CANCELLED", "MENU", menuId, 3, 2, 2);
    }

    @Test
    void previewMasksContactsAndSortsByThenCustomerId() throws Exception {
        JsonNode body = preview("MENU_CANCELLED", "MENU_DATE", dateId, 0, 1);
        assertThat(body.get("total").asInt()).isEqualTo(3);
        assertThat(body.get("page").asInt()).isZero();
        assertThat(body.get("size").asInt()).isEqualTo(1);
        assertThat(body.get("items")).hasSize(1);
        String raw = body.toString();
        assertThat(raw).doesNotContain("alpha@preview.test", "beta@preview.test", "gamma@preview.test",
            "+15551235678", "+15557654321");
        JsonNode first = body.get("items").get(0);
        assertThat(first.get("name").asText()).isEqualTo("Alpha");
        assertThat(first.get("emailMasked").asText()).isEqualTo("al***@preview.test");
        assertThat(first.get("phoneMasked").asText()).isEqualTo("***5678");
        assertThat(first.get("channels").toString()).contains("EMAIL", "SMS");
        assertThat(first.get("reasons").toString()).contains("ACTIVE_ORDER");

        JsonNode secondPage = preview("MENU_CANCELLED", "MENU_DATE", dateId, 1, 1);
        assertThat(secondPage.get("items").get(0).get("name").asText()).isEqualTo("Beta");
    }

    @Test
    void zeroRecipientPreviewReturnsEmptyPage() throws Exception {
        preferences.save(new NotificationPreference(UUID.fromString(alphaId), NotificationEventType.ORDER_ACCEPTED,
            false, false, Instant.now()));
        assertPreview("ORDER_ACCEPTED", "ORDER", orderId, 0, 0, 0);
    }

    @Test
    void activeOrderAudienceBypassesDisabledEventPreference() throws Exception {
        preferences.save(new NotificationPreference(UUID.fromString(alphaId), NotificationEventType.MENU_CANCELLED,
            false, false, Instant.now()));
        JsonNode body = preview("MENU_CANCELLED", "MENU_DATE", dateId, 0, 20);
        assertThat(body.get("items").findValuesAsText("customerId")).contains(alphaId);
        assertThat(body.get("items").get(0).get("reasons").toString()).contains("ACTIVE_ORDER");
    }

    @Test
    void previewWritesNothing() throws Exception {
        long notificationsBefore = count("notification");
        long auditsBefore = count("audit_event");
        long outboxBefore = count("outbox_event");
        preview("MENU_CANCELLED", "MENU_DATE", dateId, 0, 20);
        assertThat(count("notification")).isEqualTo(notificationsBefore);
        assertThat(count("audit_event")).isEqualTo(auditsBefore);
        assertThat(count("outbox_event")).isEqualTo(outboxBefore);
    }

    @Test
    void adminOnly() throws Exception {
        mvc.perform(post("/admin/v1/notifications/recipient-preview")
                .contentType(MediaType.APPLICATION_JSON)
                .content(request("MENU_CANCELLED", "MENU_DATE", dateId)))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/v1/notifications/recipient-preview")
                .cookie(buyerCookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request("MENU_CANCELLED", "MENU_DATE", dateId)))
            .andExpect(status().isForbidden());
    }

    @Test
    void invalidEventEntityAndMissingEntityRejected() throws Exception {
        mvc.perform(post("/admin/v1/notifications/recipient-preview").headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON)
                .content(request("ORDER_PAID", "ORDER", orderId)))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/admin/v1/notifications/recipient-preview").headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON)
                .content(request("MENU_PUBLISHED", "MENU_DATE", dateId)))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/admin/v1/notifications/recipient-preview").headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON)
                .content(request("MENU_PUBLISHED", "MENU", Uuids.v7().toString())))
            .andExpect(status().isNotFound());
    }

    @Test
    void smsStopAndUnverifiedContactsSuppressSmsOnly() throws Exception {
        JsonNode reminder = preview("ORDER_REMINDER", "MENU_DATE", dateId, 0, 20);
        assertThat(reminder.get("items")).singleElement()
            .satisfies(item -> assertThat(item.get("customerId").asText()).isEqualTo(betaId));
        JsonNode cancellation = preview("MENU_CANCELLED", "MENU_DATE", dateId, 0, 20);
        List<String> ids = cancellation.get("items").findValuesAsText("customerId");
        assertThat(ids).contains(alphaId, betaId, gammaId);
        assertThat(cancellation.get("channelCounts").get("sms").asInt()).isEqualTo(2);

        Customer gamma = customers.findById(UUID.fromString(gammaId)).orElseThrow();
        gamma.markSmsOptOut(Instant.now());
        customers.save(gamma);
        JsonNode afterStop = preview("MENU_CANCELLED", "MENU_DATE", dateId, 0, 20);
        assertThat(afterStop.get("channelCounts").get("sms").asInt()).isEqualTo(1);
        assertThat(afterStop.get("channelCounts").get("email").asInt()).isEqualTo(2);
    }

    private void assertPreview(String event, String entityType, String entityId,
            int customers, int email, int sms) throws Exception {
        JsonNode body = preview(event, entityType, entityId, 0, 20);
        assertThat(body.get("eventType").asText()).isEqualTo(event);
        assertThat(body.get("customerCount").asInt()).as(event + " customers").isEqualTo(customers);
        assertThat(body.get("channelCounts").get("email").asInt()).as(event + " email").isEqualTo(email);
        assertThat(body.get("channelCounts").get("sms").asInt()).as(event + " sms").isEqualTo(sms);
        assertThat(body.get("total").asInt()).isEqualTo(customers);
    }

    private JsonNode preview(String event, String entityType, String entityId, int page, int size) throws Exception {
        MvcResult result = mvc.perform(post("/admin/v1/notifications/recipient-preview")
                .headers(adminHeaders())
                .param("page", String.valueOf(page))
                .param("size", String.valueOf(size))
                .contentType(MediaType.APPLICATION_JSON)
                .content(request(event, entityType, entityId)))
            .andExpect(status().isOk())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String request(String event, String entityType, String entityId) {
        return """
            {"eventType":"%s","entityType":"%s","entityId":"%s"}
            """.formatted(event, entityType, entityId);
    }

    private Customer customer(String name, String email, String phone, boolean emailVerified, boolean smsConsent) {
        Customer customer = new Customer(Uuids.v7(), name, email, phone, CustomerStatus.ACTIVE, Instant.now());
        if (emailVerified) {
            customer.markEmailVerified(Instant.now());
        }
        if (smsConsent) {
            customer.grantSmsConsent(Instant.now());
        }
        return customers.save(customer);
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    private void authenticateAdmin() throws Exception {
        var admin = seedAdmin("preview@example.com", PASSWORD, List.of());
        var device = seedDevice(admin.id(), "preview-device-key", "Pixel");
        deviceKey = device.apiKey();
        MvcResult login = mvc.perform(post("/admin/v1/auth/login")
                .header("X-Device-Key", deviceKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"preview@example.com","password":"%s","totpCode":"%s"}
                    """.formatted(PASSWORD, currentTotpCode(admin.totpSecret()))))
            .andExpect(status().isOk())
            .andReturn();
        accessToken = objectMapper.readTree(login.getResponse().getContentAsString())
            .get("accessToken").asText();
    }

    private HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        headers.set("X-Device-Key", deviceKey);
        return headers;
    }
}

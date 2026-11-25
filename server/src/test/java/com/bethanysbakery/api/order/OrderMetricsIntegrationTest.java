package com.bethanysbakery.api.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bethanysbakery.api.auth.session.SessionService;
import com.bethanysbakery.api.common.Uuids;
import com.bethanysbakery.api.domain.auth.Customer;
import com.bethanysbakery.api.domain.auth.CustomerStatus;
import com.bethanysbakery.api.domain.auth.SubjectType;
import com.bethanysbakery.api.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** S9 counters count committed server transitions only, observed through the management scrape. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.server.port=0")
class OrderMetricsIntegrationTest extends IntegrationTestBase {

    private static final String PASSWORD = "correct horse battery staple";
    private static final ZoneId ZONE = ZoneId.of("America/Denver");
    private static final List<String> METRICS = List.of(
        "bakery_orders_created_total", "bakery_orders_paid_total", "bakery_orders_cancelled_total",
        "bakery_orders_ready_total", "bakery_orders_delayed_total", "bakery_orders_rejected_total",
        "bakery_orders_expired_total");

    @Autowired SessionService sessions;
    @Autowired OrderExpiryJob expiryJob;

    @LocalManagementPort int managementPort;

    private String accessToken;
    private String deviceKey;
    private LocalDate bakeDate;
    private String dateId;
    private String windowId;
    private String offeringId;
    private Cookie buyer;
    private final Map<String, String> csrf = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        authenticateAdmin();
        setupMenu();
        Customer customer = new Customer(Uuids.v7(), "Buyer", "buyer@metrics.test", null,
            CustomerStatus.ACTIVE, Instant.now());
        customer.markEmailVerified(Instant.now());
        customers.save(customer);
        var tokens = sessions.create(SubjectType.CUSTOMER, customer.getId(), null, "test", "10.0.0.9");
        buyer = new Cookie("bb_access", tokens.accessToken());
        MvcResult result = mvc.perform(get("/api/v1/auth/csrf").cookie(buyer))
            .andExpect(status().isOk()).andReturn();
        String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
        csrf.put("token", token);
        csrf.put("cookie", cookieValue(result, "bb_csrf").getValue());
    }

    @Test
    void countersIncrementOncePerCommittedTransitionAndIgnoreReplaysOrConflicts() throws Exception {
        Map<String, Double> before = scrapeCounters();

        String first = createOrder("metrics-created-key", 201);
        createOrder("metrics-created-key", 200); // replay: HTTP 200, no second order, no counter
        adminPost(first, "/accept").andExpect(status().isOk());
        pay(first, true).andExpect(status().isOk());
        pay(first, true).andExpect(status().isOk()); // no-op
        adminPost(first, "/ready").andExpect(status().isOk());
        adminPost(first, "/ready").andExpect(status().isConflict());
        adminPost(first, "/delay").andExpect(status().isAccepted());

        String cancelled = createOrder(null, 201);
        mvc.perform(post("/api/v1/orders/" + cancelled + "/cancel")
                .cookie(buyer, new Cookie("bb_csrf", csrf.get("cookie")))
                .header("X-CSRF-Token", csrf.get("token"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"changed mind\"}"))
            .andExpect(status().isOk());
        mvc.perform(post("/api/v1/orders/" + cancelled + "/cancel")
                .cookie(buyer, new Cookie("bb_csrf", csrf.get("cookie")))
                .header("X-CSRF-Token", csrf.get("token"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"again\"}"))
            .andExpect(status().isConflict());

        String rejected = createOrder(null, 201);
        mvc.perform(post("/admin/v1/orders/" + rejected + "/reject").headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"quality\"}"))
            .andExpect(status().isOk());
        mvc.perform(post("/admin/v1/orders/" + rejected + "/reject").headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"again\"}"))
            .andExpect(status().isConflict());

        String expired = createOrder(null, 201);
        jdbc.update("update orders set expires_at = ? where id = ?::uuid",
            java.sql.Timestamp.from(Instant.now().minusSeconds(60)), expired);
        expiryJob.expireDueOrders();
        expiryJob.expireDueOrders();

        Map<String, Double> after = scrapeCounters();
        assertThat(after.get("bakery_orders_created_total") - before.get("bakery_orders_created_total"))
            .isEqualTo(4.0);
        METRICS.stream().filter(name -> !name.equals("bakery_orders_created_total"))
            .forEach(name -> assertThat(after.get(name) - before.get(name)).as(name).isEqualTo(1.0));
    }

    private Map<String, Double> scrapeCounters() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + "/actuator/prometheus"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        Map<String, Double> values = new HashMap<>();
        for (String name : METRICS) {
            values.put(name, 0.0);
        }
        for (String line : response.body().split("\n")) {
            for (String name : METRICS) {
                if (line.startsWith(name + " ")) {
                    values.put(name, Double.parseDouble(line.substring((name + " ").length()).trim()));
                }
            }
        }
        return values;
    }

    private String createOrder(String idempotencyKey, int expectedStatus) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("menuDateId", dateId);
        body.put("pickupWindowId", windowId);
        if (idempotencyKey != null) body.put("idempotency_key", idempotencyKey);
        ArrayNode items = body.putArray("items");
        ObjectNode item = items.addObject();
        item.put("offeringId", offeringId);
        item.put("quantity", 1);
        item.putArray("optionIds");
        MvcResult result = mvc.perform(post("/api/v1/orders")
                .cookie(buyer, new Cookie("bb_csrf", csrf.get("cookie")))
                .header("X-CSRF-Token", csrf.get("token"))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
            .andExpect(status().is(expectedStatus)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private org.springframework.test.web.servlet.ResultActions adminPost(String orderId, String suffix)
            throws Exception {
        return mvc.perform(post("/admin/v1/orders/" + orderId + suffix).headers(adminHeaders()));
    }

    private org.springframework.test.web.servlet.ResultActions pay(String orderId, boolean paid)
            throws Exception {
        return mvc.perform(put("/admin/v1/orders/" + orderId + "/payment").headers(adminHeaders())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("paid", paid, "method", "VENMO"))));
    }

    private void authenticateAdmin() throws Exception {
        var admin = seedAdmin("metrics@example.com", PASSWORD, List.of());
        var device = seedDevice(admin.id(), "metrics-device-key", "Pixel");
        deviceKey = device.apiKey();
        MvcResult login = mvc.perform(post("/admin/v1/auth/login")
                .header("X-Device-Key", deviceKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"metrics@example.com","password":"%s","totpCode":"%s"}
                    """.formatted(PASSWORD, currentTotpCode(admin.totpSecret()))))
            .andExpect(status().isOk()).andReturn();
        accessToken = objectMapper.readTree(login.getResponse().getContentAsString())
            .get("accessToken").asText();
    }

    private void setupMenu() throws Exception {
        ObjectNode template = objectMapper.createObjectNode();
        template.put("name", "Metrics Roll");
        template.put("active", true);
        MvcResult templateResult = mvc.perform(post("/admin/v1/item-templates").headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(template)))
            .andExpect(status().isCreated()).andReturn();
        String templateId = objectMapper.readTree(templateResult.getResponse().getContentAsString())
            .get("id").asText();

        bakeDate = LocalDate.now(ZONE).plusDays(20);
        MvcResult menuResult = mvc.perform(post("/admin/v1/menus").headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"Metrics menu\"}"))
            .andExpect(status().isCreated()).andReturn();
        String menuId = objectMapper.readTree(menuResult.getResponse().getContentAsString()).get("id").asText();

        MvcResult dateResult = mvc.perform(post("/admin/v1/menus/" + menuId + "/dates").headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bakeDate\":\"" + bakeDate + "\"}"))
            .andExpect(status().isCreated()).andReturn();
        dateId = objectMapper.readTree(dateResult.getResponse().getContentAsString()).get("id").asText();

        String start = bakeDate.atTime(12, 0).atZone(ZONE).toInstant().toString();
        String end = bakeDate.atTime(14, 0).atZone(ZONE).toInstant().toString();
        MvcResult windowResult = mvc.perform(post("/admin/v1/menu-dates/" + dateId + "/pickup-windows")
                .headers(adminHeaders()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\":\"Morning\",\"startAt\":\"" + start + "\",\"endAt\":\"" + end
                    + "\",\"maxOrders\":50}"))
            .andExpect(status().isCreated()).andReturn();
        windowId = objectMapper.readTree(windowResult.getResponse().getContentAsString()).get("id").asText();

        ObjectNode offering = objectMapper.createObjectNode();
        offering.put("itemTemplateId", templateId);
        offering.put("priceCents", 400);
        offering.put("quantityLimit", 50);
        offering.putArray("windowIds").add(windowId);
        MvcResult offeringResult = mvc.perform(post("/admin/v1/menu-dates/" + dateId + "/offerings")
                .headers(adminHeaders()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(offering)))
            .andExpect(status().isCreated()).andReturn();
        offeringId = objectMapper.readTree(offeringResult.getResponse().getContentAsString()).get("id").asText();

        mvc.perform(post("/admin/v1/menus/" + menuId + "/publish").headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON).content("{\"notificationEnabled\":true}"))
            .andExpect(status().isOk());
    }

    private HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        headers.set("X-Device-Key", deviceKey);
        return headers;
    }
}

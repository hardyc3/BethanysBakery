package com.bethanysbakery.api.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** S5 order creation, concurrency, lifecycle, expiry, cancellation, and admin aggregates. */
class OrderIntegrationTest extends IntegrationTestBase {

    private static final String PASSWORD = "correct horse battery staple";
    private static final ZoneId ZONE = ZoneId.of("America/Denver");

    @Autowired SessionService sessions;
    @Autowired OrderExpiryJob expiryJob;

    private String accessToken;
    private String deviceKey;
    private LocalDate bakeDate;
    private String menuId;
    private String dateId;
    private String windowId;
    private String plainOfferingId;
    private String customOfferingId;
    private String optionId;
    private Cookie buyer;
    private Cookie otherBuyer;
    private final Map<String, CsrfToken> csrfByCookie = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        authenticateAdmin();
        setupMenu();
        buyer = customerCookie("buyer@example.com", "Buyer One");
        otherBuyer = customerCookie("other@example.com", "Buyer Two");
        csrfByCookie.put(buyer.getValue(), csrf(buyer));
        csrfByCookie.put(otherBuyer.getValue(), csrf(otherBuyer));
    }

    @Test
    void createOrderPricedFromSnapshotSetsCountersNumberAuditAndOutbox() throws Exception {
        MvcResult result = createOrder(buyer, customOfferingId, 2, List.of(optionId), null)
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.orderNumber").value("BB-" + bakeDate.format(
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")) + "-0001"))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.paymentStatus").value("UNPAID"))
            .andExpect(jsonPath("$.lines[0].lineTotalCents").value(1000))
            .andExpect(jsonPath("$.totalCents").value(1000))
            .andExpect(jsonPath("$.lines[0].options[0].priceDeltaCents").value(100))
            .andExpect(jsonPath("$.customer.name").value("Buyer One"))
            .andExpect(jsonPath("$.paymentInstructions.amountCents").value(1000))
            .andReturn();
        String orderId = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();

        assertThat(jdbc.queryForObject("select reserved_count from offering where id = ?::uuid",
            Integer.class, customOfferingId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select reserved_orders from pickup_window where id = ?::uuid",
            Integer.class, windowId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where aggregate_id = ?::uuid "
            + "and event_type = 'OrderCreated' and status = 'PENDING'", Integer.class, orderId)).isEqualTo(1);
        assertThat(countAudit("ORDER_CREATED")).isEqualTo(1);
    }

    @Test
    void duplicateIdempotencyKeyReturnsExistingOrderWithoutDuplicateEffects() throws Exception {
        MvcResult first = createOrder(buyer, plainOfferingId, 1, List.of(), "retry-key")
            .andExpect(status().isCreated()).andReturn();
        JsonNode created = objectMapper.readTree(first.getResponse().getContentAsString());
        MvcResult second = createOrder(buyer, plainOfferingId, 1, List.of(), "retry-key")
            .andExpect(status().isOk()).andReturn();
        JsonNode replayed = objectMapper.readTree(second.getResponse().getContentAsString());

        assertThat(replayed.get("id").asText()).isEqualTo(created.get("id").asText());
        assertThat(jdbc.queryForObject("select count(*) from orders", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select reserved_count from offering where id = ?::uuid",
            Integer.class, plainOfferingId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from outbox_event", Integer.class)).isEqualTo(1);
        assertThat(countAudit("ORDER_CREATED")).isEqualTo(1);
    }

    @Test
    void idempotencyKeyIsScopedToCustomer() throws Exception {
        MvcResult first = createOrder(buyer, plainOfferingId, 1, List.of(), "shared-key")
            .andExpect(status().isCreated()).andReturn();
        MvcResult second = createOrder(otherBuyer, plainOfferingId, 1, List.of(), "shared-key")
            .andExpect(status().isCreated()).andReturn();

        String firstId = objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asText();
        String secondId = objectMapper.readTree(second.getResponse().getContentAsString()).get("id").asText();
        assertThat(secondId).isNotEqualTo(firstId);
        assertThat(jdbc.queryForObject("select count(*) from orders", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select reserved_count from offering where id = ?::uuid",
            Integer.class, plainOfferingId)).isEqualTo(2);
    }

    @Test
    void customizationsMustBelongToOfferingAndRequiredGroupsMustBeSelected() throws Exception {
        createOrder(buyer, customOfferingId, 1, List.of(), null)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_CUSTOMIZATION"));
        createOrder(buyer, plainOfferingId, 1, List.of(optionId), null)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_CUSTOMIZATION"));
        createOrder(buyer, customOfferingId, 1, List.of(optionId, optionId), null)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_CUSTOMIZATION"));
        assertThat(jdbc.queryForObject("select count(*) from orders", Integer.class)).isZero();
    }

    @Test
    void orderAfterCutoffIsRejected() throws Exception {
        jdbc.update("update pickup_window set start_at = ? where id = ?::uuid",
            java.sql.Timestamp.from(Instant.now().plusSeconds(3600)), windowId);
        createOrder(buyer, plainOfferingId, 1, List.of(), null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CUTOFF_PASSED"));
    }

    @Test
    void concurrentOrdersForLastUnitSellOutExactlyOnce() throws Exception {
        jdbc.update("update offering set quantity_limit = 1 where id = ?::uuid", plainOfferingId);
        MvcResult[] results = parallelOrders(List.of(buyer, otherBuyer), plainOfferingId, 1);
        assertThat(List.of(results[0].getResponse().getStatus(), results[1].getResponse().getStatus()))
            .containsExactlyInAnyOrder(201, 409);
        String body = failedBody(results);
        assertThat(body).contains("SOLD_OUT");
        assertThat(jdbc.queryForObject("select reserved_count from offering where id = ?::uuid",
            Integer.class, plainOfferingId)).isEqualTo(1);
    }

    @Test
    void concurrentOrdersForLastWindowSlotFillExactlyOnce() throws Exception {
        jdbc.update("update pickup_window set max_orders = 1 where id = ?::uuid", windowId);
        MvcResult[] results = parallelOrders(List.of(buyer, otherBuyer), plainOfferingId, 1);
        assertThat(List.of(results[0].getResponse().getStatus(), results[1].getResponse().getStatus()))
            .containsExactlyInAnyOrder(201, 409);
        assertThat(failedBody(results)).contains("WINDOW_FULL");
        assertThat(jdbc.queryForObject("select reserved_orders from pickup_window where id = ?::uuid",
            Integer.class, windowId)).isEqualTo(1);
    }

    @Test
    void concurrentOrdersForSameCustomerRespectPerCustomerLimit() throws Exception {
        jdbc.update("update offering set max_per_customer = 3 where id = ?::uuid", plainOfferingId);
        MvcResult[] results = parallelOrders(List.of(buyer, buyer), plainOfferingId, 2);
        assertThat(List.of(results[0].getResponse().getStatus(), results[1].getResponse().getStatus()))
            .containsExactlyInAnyOrder(201, 409);
        assertThat(failedBody(results)).contains("CUSTOMER_LIMIT_EXCEEDED");
        assertThat(jdbc.queryForObject("select reserved_count from offering where id = ?::uuid",
            Integer.class, plainOfferingId)).isEqualTo(2);
    }

    @Test
    void lifecycleTransitionsReleaseInventoryOnlyOnTerminalTransitions() throws Exception {
        String first = orderId(createOrder(buyer, plainOfferingId, 2, List.of(), null));
        String second = orderId(createOrder(otherBuyer, plainOfferingId, 2, List.of(), null));

        adminPost("/accept").apply(first);
        assertEquals(first, "ACCEPTED");
        adminPost("/ready").apply(first);
        assertEquals(first, "READY");
        adminPost("/completed").apply(first);
        assertEquals(first, "COMPLETED");
        assertThat(jdbc.queryForObject("select reserved_count from offering where id = ?::uuid",
            Integer.class, plainOfferingId)).isEqualTo(4);

        mvc.perform(post("/admin/v1/orders/" + second + "/reject").headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"quality\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        assertThat(jdbc.queryForObject("select reserved_count from offering where id = ?::uuid",
            Integer.class, plainOfferingId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select reserved_orders from pickup_window where id = ?::uuid",
            Integer.class, windowId)).isEqualTo(1);

        mvc.perform(post("/admin/v1/orders/" + second + "/reject").headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"again\"}"))
            .andExpect(status().isConflict());
        assertThat(countAudit("ORDER_INVENTORY_RELEASED")).isEqualTo(1);
    }

    @Test
    void expiryReleasesInventoryOnceAndIsIdempotent() throws Exception {
        String orderId = orderId(createOrder(buyer, plainOfferingId, 1, List.of(), null));
        jdbc.update("update orders set expires_at = ? where id = ?::uuid",
            java.sql.Timestamp.from(Instant.now().minusSeconds(60)), orderId);

        expiryJob.expireDueOrders();
        assertEquals(orderId, "EXPIRED");
        expiryJob.expireDueOrders();
        assertEquals(orderId, "EXPIRED");

        assertThat(jdbc.queryForObject("select reserved_count from offering where id = ?::uuid",
            Integer.class, plainOfferingId)).isZero();
        assertThat(countAudit("ORDER_EXPIRED")).isEqualTo(1);
        assertThat(countAudit("ORDER_INVENTORY_RELEASED")).isEqualTo(1);
    }

    @Test
    void customerCancelBeforeBakeDayReleasesAndOnBakeDayIsClosed() throws Exception {
        String before = orderId(createOrder(buyer, plainOfferingId, 1, List.of(), null));
        cancelOrder(buyer, before, "changed mind")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.cancelledBy").value("CUSTOMER"));
        assertThat(jdbc.queryForObject("select reserved_count from offering where id = ?::uuid",
            Integer.class, plainOfferingId)).isZero();

        String onDay = orderId(createOrder(buyer, plainOfferingId, 1, List.of(), null));
        jdbc.update("update menu_date set bake_date = ? where id = ?::uuid",
            java.sql.Date.valueOf(LocalDate.now(ZONE)), dateId);
        cancelOrder(buyer, onDay, null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CANCELLATION_CLOSED"));
    }

    @Test
    void cancellingBakeDateCancelsActiveOrdersAndReleasesInventory() throws Exception {
        String pending = orderId(createOrder(buyer, plainOfferingId, 1, List.of(), null));
        String accepted = orderId(createOrder(buyer, plainOfferingId, 1, List.of(), null));
        String ready = orderId(createOrder(otherBuyer, plainOfferingId, 1, List.of(), null));
        adminPost("/accept").apply(accepted);
        adminPost("/accept").apply(ready);
        adminPost("/ready").apply(ready);

        mvc.perform(post("/admin/v1/menu-dates/" + dateId + "/cancel").headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"sold out\"}"))
            .andExpect(status().isOk());
        for (String id : List.of(pending, accepted, ready)) {
            assertEquals(id, "CANCELLED");
            mvc.perform(get("/admin/v1/orders/" + id).headers(adminHeaders()))
                .andExpect(jsonPath("$.cancelledBy").value("SYSTEM"))
                .andExpect(jsonPath("$.cancellationReason").value("sold out"));
        }
        assertThat(jdbc.queryForObject("select reserved_count from offering where id = ?::uuid",
            Integer.class, plainOfferingId)).isZero();
        assertThat(jdbc.queryForObject("select reserved_orders from pickup_window where id = ?::uuid",
            Integer.class, windowId)).isZero();
        assertThat(countAudit("ORDER_CANCELLED_BY_ADMIN")).isEqualTo(3);
    }

    @Test
    void bakeListAndSummaryIncludeOnlyFulfillmentOrders() throws Exception {
        orderId(createOrder(buyer, plainOfferingId, 3, List.of(), null)); // PENDING — excluded
        String accepted = orderId(createOrder(otherBuyer, plainOfferingId, 2, List.of(), null));
        String ready = orderId(createOrder(buyer, customOfferingId, 1, List.of(optionId), null));
        String completed = orderId(createOrder(otherBuyer, customOfferingId, 2, List.of(optionId), null));
        adminPost("/accept").apply(accepted);
        adminPost("/accept").apply(ready);
        adminPost("/ready").apply(ready);
        adminPost("/accept").apply(completed);
        adminPost("/ready").apply(completed);
        adminPost("/completed").apply(completed);
        String cancelled = orderId(createOrder(buyer, plainOfferingId, 5, List.of(), null)); // CANCELLED — excluded
        cancelOrder(buyer, cancelled, "never mind").andExpect(status().isOk());

        JsonNode bakeList = objectMapper.readTree(mvc.perform(get("/admin/v1/bake-list")
                .param("date", bakeDate.toString()).headers(adminHeaders()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(bakeList.get("orderCount").asInt()).isEqualTo(3);
        JsonNode custom = null;
        JsonNode plain = null;
        for (JsonNode item : bakeList.get("items")) {
            if (item.get("itemName").asText().equals("Custom Roll")) custom = item;
            if (item.get("itemName").asText().equals("Plain Roll")) plain = item;
        }
        assertThat(custom).isNotNull();
        assertThat(custom.at("/totalQuantity").asInt()).isEqualTo(3);
        assertThat(custom.at("/options/0/groupName").asText()).isEqualTo("Toppings");
        assertThat(custom.at("/options/0/optionName").asText()).isEqualTo("Icing");
        assertThat(custom.at("/options/0/count").asInt()).isEqualTo(3);
        assertThat(plain).isNotNull();
        assertThat(plain.at("/totalQuantity").asInt()).isEqualTo(2);
        assertThat(plain.get("options")).isEmpty();

        JsonNode summary = objectMapper.readTree(mvc.perform(get("/admin/v1/summary")
                .param("date", bakeDate.toString()).headers(adminHeaders()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(summary.get("pending").asInt()).isEqualTo(1);
        assertThat(summary.get("accepted").asInt()).isEqualTo(1);
        assertThat(summary.get("ready").asInt()).isEqualTo(1);
        assertThat(summary.get("completed").asInt()).isEqualTo(1);
        assertThat(summary.get("unpaid").asInt()).isEqualTo(4);
    }

    @Test
    void customerFiltersAndOwnershipAreEnforced() throws Exception {
        String completed = orderId(createOrder(buyer, plainOfferingId, 1, List.of(), null));
        adminPost("/accept").apply(completed);
        adminPost("/ready").apply(completed);
        adminPost("/completed").apply(completed);
        String active = orderId(createOrder(buyer, plainOfferingId, 1, List.of(), null));

        mvc.perform(get("/api/v1/orders/" + completed).cookie(otherBuyer))
            .andExpect(status().isNotFound());
        cancelOrder(otherBuyer, active, "steal")
            .andExpect(status().isNotFound());

        // All three terminal statuses must be grouped by the CANCELLED filter.
        String rejected = orderId(createOrder(buyer, plainOfferingId, 1, List.of(), null));
        mvc.perform(post("/admin/v1/orders/" + rejected + "/reject").headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"quality\"}"))
            .andExpect(status().isOk());
        String cancelled = orderId(createOrder(buyer, plainOfferingId, 1, List.of(), null));
        cancelOrder(buyer, cancelled, "changed mind").andExpect(status().isOk());
        String expired = orderId(createOrder(buyer, plainOfferingId, 1, List.of(), null));
        jdbc.update("update orders set expires_at = ? where id = ?::uuid",
            java.sql.Timestamp.from(Instant.now().minusSeconds(60)), expired);
        expiryJob.expireDueOrders();
        assertEquals(expired, "EXPIRED");

        // Only the completed + active orders still hold inventory; the three terminal ones released it.
        assertThat(jdbc.queryForObject("select reserved_count from offering where id = ?::uuid",
            Integer.class, plainOfferingId)).isEqualTo(2);
        mvc.perform(get("/api/v1/me/orders").param("filter", "ACTIVE").cookie(buyer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/v1/me/orders").param("filter", "COMPLETED").cookie(buyer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1));
        mvc.perform(get("/api/v1/me/orders").param("filter", "CANCELLED").cookie(buyer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(3));
        mvc.perform(get("/api/v1/me/orders").cookie(buyer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(5));
        mvc.perform(get("/api/v1/me/orders").cookie(otherBuyer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void sequentialOrdersRespectMaxPerCustomer() throws Exception {
        jdbc.update("update offering set max_per_customer = 1 where id = ?::uuid", plainOfferingId);
        createOrder(buyer, plainOfferingId, 1, List.of(), null)
            .andExpect(status().isCreated());
        createOrder(buyer, plainOfferingId, 1, List.of(), null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CUSTOMER_LIMIT_EXCEEDED"));
        createOrder(otherBuyer, plainOfferingId, 1, List.of(), null)
            .andExpect(status().isCreated());
    }

    @Test
    void perCustomerLimitSumsQuantitiesAcrossExistingOrders() throws Exception {
        jdbc.update("update offering set max_per_customer = 3 where id = ?::uuid", plainOfferingId);
        orderId(createOrder(buyer, plainOfferingId, 1, List.of(), null));
        orderId(createOrder(buyer, plainOfferingId, 1, List.of(), null));

        createOrder(buyer, plainOfferingId, 2, List.of(), null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("CUSTOMER_LIMIT_EXCEEDED"));
        assertThat(jdbc.queryForObject("select count(*) from orders", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select reserved_count from offering where id = ?::uuid",
            Integer.class, plainOfferingId)).isEqualTo(2);
    }

    @Test
    void reducingQuantityLimitBelowReservedKeepsExistingOrdersAndBlocksNewOnes() throws Exception {
        String existing = orderId(createOrder(buyer, plainOfferingId, 2, List.of(), null));
        // quantity_limit must stay >= 1 (ck_offering_quantity), so reserved (2) now exceeds the limit (1).
        jdbc.update("update offering set quantity_limit = 1 where id = ?::uuid", plainOfferingId);

        createOrder(otherBuyer, plainOfferingId, 1, List.of(), null)
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("SOLD_OUT"));
        assertEquals(existing, "PENDING");
        assertThat(jdbc.queryForObject("select count(*) from orders", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select reserved_count from offering where id = ?::uuid",
            Integer.class, plainOfferingId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select reserved_orders from pickup_window where id = ?::uuid",
            Integer.class, windowId)).isEqualTo(1);
    }

    @Test
    void quantityOutsideOneToNinetyNineIsRejected() throws Exception {
        createOrder(buyer, plainOfferingId, 0, List.of(), null)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        createOrder(buyer, plainOfferingId, 100, List.of(), null)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertThat(jdbc.queryForObject("select count(*) from orders", Integer.class)).isZero();
    }

    @Test
    void adminCancelReleasesInventoryAcrossActiveStatusesOnlyOnce() throws Exception {
        String pending = orderId(createOrder(buyer, plainOfferingId, 1, List.of(), null));
        String accepted = orderId(createOrder(buyer, plainOfferingId, 1, List.of(), null));
        String ready = orderId(createOrder(buyer, plainOfferingId, 1, List.of(), null));
        String completed = orderId(createOrder(buyer, plainOfferingId, 1, List.of(), null));
        adminPost("/accept").apply(accepted);
        adminPost("/accept").apply(ready);
        adminPost("/ready").apply(ready);
        adminPost("/accept").apply(completed);
        adminPost("/ready").apply(completed);
        adminPost("/completed").apply(completed);

        for (String id : List.of(pending, accepted, ready)) {
            mvc.perform(post("/admin/v1/orders/" + id + "/cancel").headers(adminHeaders())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"admin cancel\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledBy").value("ADMIN"));
        }
        mvc.perform(post("/admin/v1/orders/" + completed + "/cancel").headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"too late\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        mvc.perform(post("/admin/v1/orders/" + pending + "/cancel").headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"again\"}"))
            .andExpect(status().isConflict());

        // Three active orders cancelled; the completed one still holds its unit.
        assertThat(jdbc.queryForObject("select reserved_count from offering where id = ?::uuid",
            Integer.class, plainOfferingId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select reserved_orders from pickup_window where id = ?::uuid",
            Integer.class, windowId)).isEqualTo(1);
        assertThat(countAudit("ORDER_INVENTORY_RELEASED")).isEqualTo(3);
    }

    @Test
    void adminLifecycleEnforcesStateGuardsAndRequiresReason() throws Exception {
        String order = orderId(createOrder(buyer, plainOfferingId, 1, List.of(), null));
        mvc.perform(post("/admin/v1/orders/" + order + "/ready").headers(adminHeaders()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        mvc.perform(post("/admin/v1/orders/" + order + "/completed").headers(adminHeaders()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        mvc.perform(post("/admin/v1/orders/" + order + "/reject").headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"  \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertEquals(order, "PENDING");

        adminPost("/accept").apply(order);
        mvc.perform(post("/admin/v1/orders/" + order + "/accept").headers(adminHeaders()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INVALID_STATE"));
        mvc.perform(post("/admin/v1/orders/" + order + "/reject").headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"too late\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INVALID_STATE"));
    }

    @Test
    void adminOrderEndpointsRequireDeviceAndBearer() throws Exception {
        mvc.perform(get("/admin/v1/orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/v1/summary").param("date", bakeDate.toString()))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/admin/v1/bake-list").param("date", bakeDate.toString()))
            .andExpect(status().isUnauthorized());
        // Valid device key alone must not grant admin access; bearer is also required.
        mvc.perform(get("/admin/v1/orders").header("X-Device-Key", deviceKey))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void customerOrderEndpointsRequireCustomerSession() throws Exception {
        mvc.perform(get("/api/v1/me/orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/orders/" + Uuids.v7())).andExpect(status().isUnauthorized());
    }

    @Test
    void orderMutationsRequireCsrfToken() throws Exception {
        mvc.perform(post("/api/v1/orders").cookie(buyer)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        String orderId = orderId(createOrder(buyer, plainOfferingId, 1, List.of(), null));
        mvc.perform(post("/api/v1/orders/" + orderId + "/cancel").cookie(buyer)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    // -- helpers ----------------------------------------------------------

    private MvcResult[] parallelOrders(List<Cookie> cookies, String offeringId, int quantity) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        try {
            List<CompletableFuture<MvcResult>> futures = new ArrayList<>();
            for (int i = 0; i < cookies.size(); i++) {
                Cookie cookie = cookies.get(i);
                String idempotencyKey = "concurrent-" + i;
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return createOrder(cookie, offeringId, quantity, List.of(), idempotencyKey).andReturn();
                    } catch (Exception ex) {
                        throw new IllegalStateException(ex);
                    }
                }, executor));
            }
            return futures.stream().map(CompletableFuture::join).toArray(MvcResult[]::new);
        } finally {
            executor.shutdownNow();
        }
    }

    private static String failedBody(MvcResult[] results) throws Exception {
        for (MvcResult result : results) {
            if (result.getResponse().getStatus() == 409) {
                return result.getResponse().getContentAsString();
            }
        }
        throw new AssertionError("No conflict response");
    }

    private void assertEquals(String orderId, String status) throws Exception {
        mvc.perform(get("/admin/v1/orders/" + orderId).headers(adminHeaders()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(status));
    }

    private interface AdminAction {
        org.springframework.test.web.servlet.ResultActions apply(String orderId) throws Exception;
    }

    private AdminAction adminPost(String suffix) {
        return orderId -> mvc.perform(post("/admin/v1/orders/" + orderId + suffix).headers(adminHeaders()))
            .andExpect(status().isOk());
    }

    private String orderId(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    private org.springframework.test.web.servlet.ResultActions cancelOrder(Cookie customer, String orderId,
            String reason) throws Exception {
        CsrfToken csrf = csrfByCookie.get(customer.getValue());
        ObjectNode body = objectMapper.createObjectNode();
        if (reason != null) body.put("reason", reason);
        return mvc.perform(post("/api/v1/orders/" + orderId + "/cancel").cookie(customer, csrf.cookie())
            .header("X-CSRF-Token", csrf.token())
            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private CsrfToken csrf(Cookie access) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/auth/csrf").cookie(access))
            .andExpect(status().isOk())
            .andReturn();
        String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
        return new CsrfToken(cookieValue(result, "bb_csrf"), token);
    }

    private org.springframework.test.web.servlet.ResultActions createOrder(Cookie customer,
            String offeringId, int quantity, List<String> optionIds, String idempotencyKey) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("menuDateId", dateId);
        body.put("pickupWindowId", windowId);
        if (idempotencyKey != null) body.put("idempotency_key", idempotencyKey);
        ArrayNode items = body.putArray("items");
        ObjectNode item = items.addObject();
        item.put("offeringId", offeringId);
        item.put("quantity", quantity);
        ArrayNode options = item.putArray("optionIds");
        optionIds.forEach(options::add);
        return mvc.perform(post("/api/v1/orders").cookie(customer, csrfByCookie.get(customer.getValue()).cookie())
            .header("X-CSRF-Token", csrfByCookie.get(customer.getValue()).token())
            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private void authenticateAdmin() throws Exception {
        var admin = seedAdmin("orders@example.com", PASSWORD, List.of());
        var device = seedDevice(admin.id(), "orders-device-key", "Pixel");
        deviceKey = device.apiKey();
        MvcResult login = mvc.perform(post("/admin/v1/auth/login")
                .header("X-Device-Key", deviceKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"orders@example.com","password":"%s","totpCode":"%s"}
                    """.formatted(PASSWORD, currentTotpCode(admin.totpSecret()))))
            .andExpect(status().isOk())
            .andReturn();
        accessToken = objectMapper.readTree(login.getResponse().getContentAsString())
            .get("accessToken").asText();
    }

    private Cookie customerCookie(String email, String name) {
        Customer customer = new Customer(Uuids.v7(), name, email, null, CustomerStatus.ACTIVE, Instant.now());
        customer.markEmailVerified(Instant.now());
        customers.save(customer);
        var tokens = sessions.create(SubjectType.CUSTOMER, customer.getId(), null, "test", "10.0.0.9");
        return new Cookie("bb_access", tokens.accessToken());
    }

    private void setupMenu() throws Exception {
        String plainTemplate = createTemplate(false);
        String customTemplate = createTemplate(true);
        bakeDate = LocalDate.now(ZONE).plusDays(20);
        menuId = createMenu("Order menu");
        dateId = addDate(menuId, bakeDate);
        windowId = createWindow(dateId, bakeDate, 12, 14, 50);
        plainOfferingId = createOffering(dateId, plainTemplate, 400, 10, null);
        customOfferingId = createOffering(dateId, customTemplate, 400, 10, null);
        publish(menuId);

        JsonNode publicOffering = objectMapper.readTree(mvc.perform(get("/api/v1/offerings/" + customOfferingId))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        optionId = publicOffering.at("/customizationGroups/0/options/0/id").asText();
    }

    private String createTemplate(boolean withGroup) throws Exception {
        ObjectNode template = objectMapper.createObjectNode();
        template.put("name", withGroup ? "Custom Roll" : "Plain Roll");
        template.put("active", true);
        if (withGroup) {
            ArrayNode groups = template.putArray("customizationGroups");
            ObjectNode group = groups.addObject();
            group.put("name", "Toppings");
            group.put("selectionType", "SINGLE");
            group.put("required", true);
            group.put("minSelections", 1);
            group.put("maxSelections", 1);
            ArrayNode options = group.putArray("options");
            ObjectNode choice = options.addObject();
            choice.put("name", "Icing");
            choice.put("priceDeltaCents", 100);
        }
        MvcResult result = mvc.perform(post("/admin/v1/item-templates").headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(template)))
            .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createMenu(String title) throws Exception {
        MvcResult result = mvc.perform(post("/admin/v1/menus").headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"" + title + "\"}"))
            .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String addDate(String menu, LocalDate date) throws Exception {
        MvcResult result = mvc.perform(post("/admin/v1/menus/" + menu + "/dates").headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON).content("{\"bakeDate\":\"" + date + "\"}"))
            .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createWindow(String date, LocalDate bake, int from, int to, int maxOrders) throws Exception {
        String start = bake.atTime(from, 0).atZone(ZONE).toInstant().toString();
        String end = bake.atTime(to, 0).atZone(ZONE).toInstant().toString();
        MvcResult result = mvc.perform(post("/admin/v1/menu-dates/" + date + "/pickup-windows")
                .headers(adminHeaders()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\":\"Morning\",\"startAt\":\"" + start + "\",\"endAt\":\"" + end
                    + "\",\"maxOrders\":" + maxOrders + "}"))
            .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String createOffering(String date, String template, int price, int quantity,
            Integer maxPerCustomer) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("itemTemplateId", template);
        body.put("priceCents", price);
        body.put("quantityLimit", quantity);
        if (maxPerCustomer != null) body.put("maxPerCustomer", maxPerCustomer);
        ArrayNode windows = body.putArray("windowIds");
        windows.add(windowId);
        MvcResult result = mvc.perform(post("/admin/v1/menu-dates/" + date + "/offerings")
                .headers(adminHeaders()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private void publish(String menu) throws Exception {
        mvc.perform(post("/admin/v1/menus/" + menu + "/publish").headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON).content("{\"notificationEnabled\":true}"))
            .andExpect(status().isOk());
    }

    private HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        headers.set("X-Device-Key", deviceKey);
        return headers;
    }

    private int countAudit(String action) {
        return jdbc.queryForObject("select count(*) from audit_event where action = ?", Integer.class, action);
    }

    private record CsrfToken(Cookie cookie, String token) {}
}

package com.bethanysbakery.api.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bethanysbakery.api.auth.session.SessionService;
import com.bethanysbakery.api.common.Uuids;
import com.bethanysbakery.api.domain.audit.AuditActions;
import com.bethanysbakery.api.domain.auth.Customer;
import com.bethanysbakery.api.domain.auth.CustomerStatus;
import com.bethanysbakery.api.domain.auth.SubjectType;
import com.bethanysbakery.api.domain.order.Order;
import com.bethanysbakery.api.domain.order.OrderRepository;
import com.bethanysbakery.api.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** S8 submission tracking: replay diagnosis, bounded timestamps, fallback and isolation. */
@ExtendWith(OutputCaptureExtension.class)
class OrderReplayIntegrationTest extends IntegrationTestBase {

    private static final ZoneId ZONE = ZoneId.of("America/Denver");
    private static final Instant SERVER_RECEIVED_AT = Instant.parse("2026-11-20T12:00:00Z");

    @Autowired SessionService sessions;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoSpyBean OrderRepository orderRepository;

    private String dateId;
    private String windowId;
    private String offeringId;
    private Buyer buyer;
    private Buyer otherBuyer;

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock submissionTestClock() {
            return Clock.fixed(SERVER_RECEIVED_AT, ZoneOffset.UTC);
        }
    }

    @BeforeEach
    void setUpOrderData() throws Exception {
        Timestamp now = Timestamp.from(Instant.now());
        UUID menuId = Uuids.v7();
        UUID templateId = Uuids.v7();
        UUID date = Uuids.v7();
        UUID window = Uuids.v7();
        UUID offering = Uuids.v7();
        LocalDate bakeDate = LocalDate.now(ZONE).plusDays(20);
        dateId = date.toString();
        windowId = window.toString();
        offeringId = offering.toString();

        jdbc.update("""
            INSERT INTO item_template (id, name, active, created_at, updated_at)
            VALUES (?::uuid, 'Plain Roll', true, ?, ?)
            """, templateId.toString(), now, now);
        jdbc.update("""
            INSERT INTO menu (id, title, status, notification_enabled, created_at, updated_at)
            VALUES (?::uuid, 'Replay menu', 'PUBLISHED', false, ?, ?)
            """, menuId.toString(), now, now);
        jdbc.update("""
            INSERT INTO menu_date (id, menu_id, bake_date, status, created_at, updated_at)
            VALUES (?::uuid, ?::uuid, ?, 'PUBLISHED', ?, ?)
            """, date.toString(), menuId.toString(), bakeDate, now, now);
        jdbc.update("""
            INSERT INTO pickup_window (id, menu_date_id, label, start_at, end_at, max_orders, status,
                created_at, updated_at)
            VALUES (?::uuid, ?::uuid, 'Morning', ?, ?, 50, 'ACTIVE', ?, ?)
            """, window.toString(), date.toString(),
            Timestamp.from(bakeDate.atTime(12, 0).atZone(ZONE).toInstant()),
            Timestamp.from(bakeDate.atTime(14, 0).atZone(ZONE).toInstant()), now, now);
        jdbc.update("""
            INSERT INTO offering (id, menu_date_id, item_template_id, name, price_cents, quantity_limit,
                status, created_at, updated_at)
            VALUES (?::uuid, ?::uuid, ?::uuid, 'Plain Roll', 400, 20, 'ACTIVE', ?, ?)
            """, offering.toString(), date.toString(), templateId.toString(), now, now);
        jdbc.update("""
            INSERT INTO offering_window (offering_id, pickup_window_id) VALUES (?::uuid, ?::uuid)
            """, offering.toString(), window.toString());

        buyer = customer("buyer@example.com", "Buyer One");
        otherBuyer = customer("other@example.com", "Buyer Two");
    }

    @Test
    void sameKeySameFingerprintReplaysWithOneDiagnosticAuditRow() throws Exception {
        Instant clientFirst = Instant.parse("2026-11-18T10:00:00Z");
        Instant clientReplay = Instant.parse("2026-11-19T10:00:00Z");
        MvcResult first = postOrder(buyer.cookie(), body("same-key", 1, "hold", clientFirst))
            .andExpect(status().isCreated()).andReturn();
        String orderId = id(first);
        String fingerprint = jdbc.queryForObject("select request_fingerprint from orders where id = ?::uuid",
            String.class, orderId);
        assertThat(fingerprint).matches("[0-9a-f]{64}");
        assertThat(timestamp(orderId, "client_submitted_at")).isEqualTo(clientFirst);

        MvcResult replay = postOrder(buyer.cookie(), body("same-key", 1, "hold", clientReplay))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(orderId))
            .andReturn();

        assertThat(id(replay)).isEqualTo(orderId);
        assertThat(countOrders()).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(1);
        assertThat(countAudit("ORDER_CREATED")).isEqualTo(1);
        assertThat(countAudit(AuditActions.ORDER_IDEMPOTENCY_REPLAY)).isEqualTo(1);
        assertThat(reserved()).isEqualTo(1);
        JsonNode audit = auditPayload();
        assertThat(audit.get("payloadChanged").asBoolean()).isFalse();
        assertThat(audit.get("clientSubmittedAt").asText()).isEqualTo(clientReplay.toString());
        assertThat(Instant.parse(audit.get("serverReceivedAt").asText())).isEqualTo(SERVER_RECEIVED_AT);
        assertThat(timestamp(orderId, "client_submitted_at")).isEqualTo(clientFirst);
    }

    @Test
    void firstCreateAuditsAndLogsSubmissionTimestampsWithoutPii(CapturedOutput output) throws Exception {
        Instant client = Instant.parse("2026-11-18T10:00:00.123456789Z");
        String key = "pii-idempotency-key";
        String lineNotes = "buyer@example.com +15551234567 private note";

        MvcResult first = postOrder(buyer.cookie(), body(key, 1, lineNotes, client))
            .andExpect(status().isCreated())
            .andReturn();
        String orderId = id(first);

        JsonNode audit = auditPayload("ORDER_CREATED");
        assertThat(audit.get("orderNumber").asText()).startsWith("BB-");
        assertThat(audit.get("status").asText()).isEqualTo("PENDING");
        assertThat(audit.get("totalCents").asInt()).isEqualTo(400);
        assertThat(audit.get("clientSubmittedAt").asText()).isEqualTo(client.toString());
        assertThat(audit.get("serverReceivedAt").asText()).isEqualTo(SERVER_RECEIVED_AT.toString());
        assertThat(audit.toString())
            .doesNotContain("buyer@example.com", "+15551234567", lineNotes, key, "Buyer One");

        String logLine = output.getAll().lines()
            .filter(line -> line.contains("order_submission_timestamps") && line.contains(client.toString()))
            .findFirst()
            .orElseThrow();
        assertThat(logLine)
            .contains("clientSubmittedAt=" + client,
                "serverReceivedAt=" + SERVER_RECEIVED_AT, orderId)
            .doesNotContain("buyer@example.com", "+15551234567", lineNotes, key, "Buyer One");
    }

    @Test
    void sameKeyDifferentFingerprintReturnsStoredOrderWithoutSideEffects() throws Exception {
        Instant clientFirst = Instant.parse("2026-11-19T10:00:00Z");
        Instant clientReplay = Instant.parse("2026-11-20T10:00:00Z");
        MvcResult first = postOrder(buyer.cookie(), body("changed-key", 1, null, clientFirst))
            .andExpect(status().isCreated()).andReturn();
        String orderId = id(first);
        String originalFingerprint = jdbc.queryForObject(
            "select request_fingerprint from orders where id = ?::uuid", String.class, orderId);

        MvcResult replay = postOrder(buyer.cookie(), body("changed-key", 2, "different line notes", clientReplay))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(orderId))
            .andExpect(jsonPath("$.lines[0].quantity").value(1))
            .andReturn();

        assertThat(id(replay)).isEqualTo(orderId);
        assertThat(countOrders()).isEqualTo(1);
        assertThat(reserved()).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(1);
        assertThat(countAudit("ORDER_CREATED")).isEqualTo(1);
        assertThat(countAudit(AuditActions.ORDER_IDEMPOTENCY_REPLAY)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select request_fingerprint from orders where id = ?::uuid",
            String.class, orderId)).isEqualTo(originalFingerprint);
        assertThat(timestamp(orderId, "client_submitted_at")).isEqualTo(clientFirst);
        JsonNode audit = auditPayload();
        assertThat(audit.get("payloadChanged").asBoolean()).isTrue();
        assertThat(audit.get("clientSubmittedAt").asText()).isEqualTo(clientReplay.toString());
        assertThat(Instant.parse(audit.get("serverReceivedAt").asText())).isEqualTo(SERVER_RECEIVED_AT);
    }

    @Test
    void replaySucceedsWhenMenuNoLongerAllowsNewOrders() throws Exception {
        MvcResult first = postOrder(buyer.cookie(), body("stale-key", 1, null, null))
            .andExpect(status().isCreated()).andReturn();
        String orderId = id(first);
        jdbc.update("update menu_date set status = 'CANCELLED' where id = ?::uuid", dateId);

        postOrder(buyer.cookie(), body("stale-key", 1, null, null))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(orderId));
        assertThat(countOrders()).isEqualTo(1);
    }

    @Test
    void skewedClientTimestampsAreStoredVerbatimAndDoNotChangeValidation() throws Exception {
        Instant farPast = Instant.parse("2001-01-01T00:00:00Z");
        Instant farFuture = Instant.parse("2099-12-31T23:59:59Z");

        MvcResult past = postOrder(buyer.cookie(), body("past-key", 1, null, farPast))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andReturn();
        MvcResult future = postOrder(buyer.cookie(), body("future-key", 1, null, farFuture))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andReturn();

        JsonNode pastBody = objectMapper.readTree(past.getResponse().getContentAsString());
        JsonNode futureBody = objectMapper.readTree(future.getResponse().getContentAsString());
        assertThat(timestamp(id(past), "client_submitted_at")).isEqualTo(farPast);
        assertThat(timestamp(id(future), "client_submitted_at")).isEqualTo(farFuture);
        assertThat(pastBody.get("orderNumber").asText()).matches("BB-\\d{8}-0001");
        assertThat(futureBody.get("orderNumber").asText()).matches("BB-\\d{8}-0002");
        assertThat(pastBody.get("expiresAt").asText()).isEqualTo(futureBody.get("expiresAt").asText());
        assertThat(pastBody.get("createdAt").asText()).isEqualTo(SERVER_RECEIVED_AT.toString());
        assertThat(futureBody.get("createdAt").asText()).isEqualTo(SERVER_RECEIVED_AT.toString());
        assertThat(countOrders()).isEqualTo(2);
    }

    @Test
    void clientSubmittedAtAliasIsStoredVerbatim() throws Exception {
        Instant client = Instant.parse("2026-11-17T06:07:08.123Z");
        ObjectNode body = body("alias-key", 1, null, null);
        body.put("clientSubmittedAt", client.toString());

        MvcResult created = postOrder(buyer.cookie(), body)
            .andExpect(status().isCreated())
            .andReturn();

        assertThat(timestamp(id(created), "client_submitted_at")).isEqualTo(client);
    }

    @Test
    void concurrentSameKeyCreatesProduceOneOrderAndOneReplay() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            Future<MvcResult> first = executor.submit(() -> {
                start.await();
                return postOrder(buyer.cookie(), body("race-key", 1, null, null)).andReturn();
            });
            Future<MvcResult> second = executor.submit(() -> {
                start.await();
                return postOrder(buyer.cookie(), body("race-key", 1, null, null)).andReturn();
            });
            start.countDown();
            MvcResult a = first.get();
            MvcResult b = second.get();

            assertThat(List.of(a.getResponse().getStatus(), b.getResponse().getStatus()))
                .containsExactlyInAnyOrder(200, 201);
            assertThat(id(a)).isEqualTo(id(b));
            assertThat(countOrders()).isEqualTo(1);
            assertThat(reserved()).isEqualTo(1);
            assertThat(count("outbox_event")).isEqualTo(1);
            assertThat(count("daily_order_sequence")).isEqualTo(1);
            assertThat(countAudit("ORDER_CREATED")).isEqualTo(1);
            assertThat(countAudit(AuditActions.ORDER_IDEMPOTENCY_REPLAY)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void uniqueIndexViolationFallbackReplaysWithoutServerError() throws Exception {
        Instant client = Instant.parse("2026-11-19T10:00:00Z");
        MvcResult first = postOrder(buyer.cookie(), body("unique-key", 1, null, client))
            .andExpect(status().isCreated()).andReturn();
        String orderId = id(first);
        int sequenceBefore = jdbc.queryForObject("select next_value from daily_order_sequence", Integer.class);

        // Force the fallback: lookup misses once, then the insert hits the partial unique index.
        // Preload the whole view graph so the replay response can be built from the stubbed entity.
        Order existing = new TransactionTemplate(transactionManager).execute(status -> {
            Order loaded = orderRepository.findByCustomerIdAndIdempotencyKey(buyer.id(), "unique-key")
                .orElseThrow();
            Hibernate.initialize(loaded.getMenuDate());
            Hibernate.initialize(loaded.getPickupWindow());
            Hibernate.initialize(loaded.getLines());
            loaded.getLines().forEach(line -> Hibernate.initialize(line.getOptions()));
            return loaded;
        });
        doReturn(Optional.empty(), Optional.of(existing)).when(orderRepository)
            .findByCustomerIdAndIdempotencyKey(buyer.id(), "unique-key");

        postOrder(buyer.cookie(), body("unique-key", 1, null, client))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(orderId));

        assertThat(countOrders()).isEqualTo(1);
        assertThat(reserved()).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(1);
        assertThat(count("daily_order_sequence")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select next_value from daily_order_sequence", Integer.class))
            .isEqualTo(sequenceBefore);
        assertThat(countAudit("ORDER_CREATED")).isEqualTo(1);
        assertThat(countAudit(AuditActions.ORDER_IDEMPOTENCY_REPLAY)).isEqualTo(1);
        JsonNode audit = auditPayload();
        assertThat(audit.get("payloadChanged").asBoolean()).isFalse();
        assertThat(audit.get("clientSubmittedAt").asText()).isEqualTo(client.toString());
        assertThat(Instant.parse(audit.get("serverReceivedAt").asText())).isEqualTo(SERVER_RECEIVED_AT);
    }

    @Test
    void crossCustomerOrderEndpointsReturnNotFoundWithoutSideEffects() throws Exception {
        MvcResult created = postOrder(buyer.cookie(), body("owned-key", 1, null, null))
            .andExpect(status().isCreated()).andReturn();
        String orderId = id(created);
        int reservedBefore = reserved();
        int windowReservedBefore = jdbc.queryForObject("select reserved_orders from pickup_window where id = ?::uuid",
            Integer.class, windowId);

        mvc.perform(get("/api/v1/orders/" + orderId).cookie(otherBuyer.cookie()))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mvc.perform(get("/api/v1/me/orders").cookie(otherBuyer.cookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(0));
        mvc.perform(post("/api/v1/orders/" + orderId + "/cancel")
                .cookie(otherBuyer.cookie(), csrf(otherBuyer.cookie()))
                .header("X-CSRF-Token", csrfToken(otherBuyer.cookie()))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"steal\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(reserved()).isEqualTo(reservedBefore);
        assertThat(jdbc.queryForObject("select reserved_orders from pickup_window where id = ?::uuid",
            Integer.class, windowId)).isEqualTo(windowReservedBefore);
        assertThat(count("outbox_event")).isEqualTo(1);
        assertThat(count("daily_order_sequence")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from orders where id = ?::uuid", String.class, orderId))
            .isEqualTo("PENDING");
        assertThat(countAudit("ORDER_CANCELLED_BY_CUSTOMER")).isZero();
    }

    // -- helpers ----------------------------------------------------------

    private ObjectNode body(String key, int quantity, String lineNotes, Instant clientSubmittedAt) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("menuDateId", dateId);
        body.put("pickupWindowId", windowId);
        if (key != null) body.put("idempotency_key", key);
        if (clientSubmittedAt != null) body.put("client_submitted_at", clientSubmittedAt.toString());
        ArrayNode items = body.putArray("items");
        ObjectNode item = items.addObject();
        item.put("offeringId", offeringId);
        item.put("quantity", quantity);
        item.putArray("optionIds");
        if (lineNotes != null) item.put("notes", lineNotes);
        return body;
    }

    private org.springframework.test.web.servlet.ResultActions postOrder(Cookie access, ObjectNode body)
            throws Exception {
        return mvc.perform(post("/api/v1/orders").cookie(access, csrf(access))
            .header("X-CSRF-Token", csrfToken(access))
            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private Buyer customer(String email, String name) throws Exception {
        Customer customer = new Customer(Uuids.v7(), name, email, null, CustomerStatus.ACTIVE, Instant.now());
        customer.markEmailVerified(Instant.now());
        customers.save(customer);
        var tokens = sessions.create(SubjectType.CUSTOMER, customer.getId(), null, "test", "10.0.0.9");
        Cookie cookie = new Cookie("bb_access", tokens.accessToken());
        ensureCsrf(cookie);
        return new Buyer(customer.getId(), cookie);
    }

    private Cookie csrf(Cookie access) {
        return mvcResultCsrfCookie.get(access.getValue());
    }

    private String csrfToken(Cookie access) {
        return csrfTokenByCookie.get(access.getValue());
    }

    // Populated lazily because MockMvc calls throw checked exceptions.
    private final java.util.Map<String, Cookie> mvcResultCsrfCookie = new java.util.HashMap<>();
    private final java.util.Map<String, String> csrfTokenByCookie = new java.util.HashMap<>();

    private String ensureCsrf(Cookie access) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/auth/csrf").cookie(access))
            .andExpect(status().isOk()).andReturn();
        String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
        csrfTokenByCookie.put(access.getValue(), token);
        mvcResultCsrfCookie.put(access.getValue(), result.getResponse().getCookie("bb_csrf"));
        return token;
    }

    private String id(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private Instant timestamp(String orderId, String column) {
        Timestamp value = jdbc.queryForObject(
            "select " + column + " from orders where id = ?::uuid", Timestamp.class, orderId);
        return value == null ? null : value.toInstant();
    }

    private JsonNode auditPayload() throws Exception {
        return auditPayload(AuditActions.ORDER_IDEMPOTENCY_REPLAY);
    }

    private JsonNode auditPayload(String action) throws Exception {
        String json = jdbc.queryForObject("select after_json::text from audit_event where action = ?",
            String.class, action);
        return objectMapper.readTree(json);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private int countOrders() {
        return count("orders");
    }

    private int reserved() {
        return jdbc.queryForObject("select reserved_count from offering where id = ?::uuid",
            Integer.class, offeringId);
    }

    private int countAudit(String action) {
        return jdbc.queryForObject("select count(*) from audit_event where action = ?", Integer.class, action);
    }

    private record Buyer(UUID id, Cookie cookie) {}
}

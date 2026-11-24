package com.bethanysbakery.api.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bethanysbakery.api.auth.session.SessionService;
import com.bethanysbakery.api.common.Uuids;
import com.bethanysbakery.api.domain.auth.Customer;
import com.bethanysbakery.api.domain.auth.CustomerStatus;
import com.bethanysbakery.api.domain.auth.SubjectType;
import com.bethanysbakery.api.domain.notification.NotificationEventType;
import com.bethanysbakery.api.domain.notification.NotificationRepository;
import com.bethanysbakery.api.domain.notification.NotificationStatus;
import com.bethanysbakery.api.domain.order.OrderRepository;
import com.bethanysbakery.api.domain.order.OrderStatus;
import com.bethanysbakery.api.notification.NotificationMessages.EmailMessage;
import com.bethanysbakery.api.notification.NotificationMessages.SmsMessage;
import com.bethanysbakery.api.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** S6 payment, preference, webhook, scheduler, dispatch, retry, and admin-queue integration coverage. */
@Import(NotificationTestConfig.class)
@TestPropertySource(properties = "bakery.notifications.admin-alert-phone=+15551234567")
class NotificationIntegrationTest extends IntegrationTestBase {

    private static final String PASSWORD = "correct horse battery staple";
    private static final ZoneId ZONE = ZoneId.of("America/Denver");
    private static final Instant BASE_NOW = Instant.parse("2026-11-18T16:30:00Z");
    private static final String WEBHOOK_SECRET = "test-webhook-secret";

    // Independent copy of the nine customer events from docs/specs/server/S8-review-followups.md:74,111.
    private static final List<String> CUSTOMER_EVENT_TYPES = List.of(
        "MENU_PUBLISHED", "ORDER_REMINDER", "ORDER_ACCEPTED", "ORDER_READY", "ORDER_DELAYED",
        "ORDER_REJECTED", "ORDER_CANCELLED", "ORDER_EXPIRED", "MENU_CANCELLED");

    @Autowired SessionService sessions;
    @Autowired OutboxProcessor outbox;
    @Autowired NotificationDispatchService dispatcher;
    @Autowired MenuNotificationService menuNotifications;
    @Autowired NotificationRepository notifications;
    @Autowired NotificationService notificationService;
    @Autowired OrderRepository orders;

    private String accessToken;
    private String deviceKey;
    private UUID deviceId;
    private UUID adminId;
    private String adminTotpSecret;
    private LocalDate bakeDate;
    private String menuId;
    private String dateId;
    private String windowId;
    private String plainOfferingId;
    private String customOfferingId;
    private String optionId;
    private CustomerFixture buyer;
    private CustomerFixture otherBuyer;

    @BeforeEach
    void setUp() throws Exception {
        NotificationTestConfig.reset(BASE_NOW);
        authenticateAdmin();
        setupMenu();
        buyer = customer("buyer@example.com", "Buyer One", "+15555550101");
        otherBuyer = customer("other@example.com", "Buyer Two", "+15555550102");
    }

    @Test
    void paidOrderQueuesFullAdminEmailAndDeduplicatesRetriesAndCorrections() throws Exception {
        ResultActions created = createOrder(buyer, 1, true, "Leave at door", "Extra crust", null);
        String orderId = orderId(created);
        String orderNumber = objectMapper.readTree(created.andReturn().getResponse().getContentAsString())
            .get("orderNumber").asText();

        MvcResult paidResult = pay(orderId, true, "VENMO")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paymentStatus").value("PAID"))
            .andExpect(jsonPath("$.paymentMethod").value("VENMO"))
            .andExpect(jsonPath("$.paidAt").value(BASE_NOW.toString()))
            .andExpect(jsonPath("$.paymentInstructions.venmoDeepLink")
                .value("https://public.example.com/orders/" + orderId + "/pay"))
            .andReturn();
        JsonNode paid = objectMapper.readTree(paidResult.getResponse().getContentAsString());

        assertThat(paid.get("paymentStatus").asText()).isEqualTo("PAID");
        assertThat(auditCount("ORDER_PAID")).isEqualTo(1);
        assertThat(countNotifications("ORDER_PAID")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from notification where idempotency_key = ?",
            Integer.class, NotificationKeys.paid(UUID.fromString(orderId)))).isEqualTo(1);

        dispatcher.dispatchDue();
        EmailMessage email = email("Paid order " + orderNumber);
        assertThat(email.to()).isEqualTo("admin@example.com");
        assertThat(email.textBody()).contains(orderNumber, bakeDate.toString(), "Morning", "Buyer One",
            "buyer@example.com", "+15555550101", "Leave at door", "Custom Roll", "Icing", "Extra crust",
            "$5.00", "VENMO", BASE_NOW.toString());
        assertThat(email.htmlBody()).contains(orderNumber, bakeDate.toString(), "$5.00", "VENMO");

        pay(orderId, true, "VENMO").andExpect(status().isOk());
        assertThat(auditCount("ORDER_PAID")).isEqualTo(1);
        assertThat(countNotifications("ORDER_PAID")).isEqualTo(1);

        pay(orderId, false, null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paymentStatus").value("UNPAID"))
            .andExpect(jsonPath("$.paidAt").doesNotExist())
            .andExpect(jsonPath("$.paymentMethod").doesNotExist());
        assertThat(auditCount("ORDER_PAYMENT_CORRECTED")).isEqualTo(1);
        assertThat(countNotifications("ORDER_PAID")).isEqualTo(1);
        dispatcher.dispatchDue();
        assertThat(countEmails("Paid order " + orderNumber)).isEqualTo(1);
    }

    @Test
    void paymentMethodDefaultsToVenmoAcceptsCashAndOtherAndRejectsInvalidValues() throws Exception {
        String orderId = orderId(createOrder(buyer, 1, false, null, null, "pay-methods"));

        pay(orderId, true, null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paymentMethod").value("VENMO"));
        pay(orderId, false, null).andExpect(status().isOk());
        pay(orderId, true, "CASH")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paymentMethod").value("CASH"));
        pay(orderId, true, "cash")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        pay(orderId, false, null).andExpect(status().isOk());
        pay(orderId, true, "OTHER")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paymentMethod").value("OTHER"));
        pay(orderId, false, null).andExpect(status().isOk());
        pay(orderId, true, "BITCOIN")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        String rejectedId = orderId(createOrder(buyer, 1, false, null, null, "pay-rejected"));
        mvc.perform(post("/admin/v1/orders/" + rejectedId + "/reject").headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"quality issue\"}"))
            .andExpect(status().isOk());
        pay(rejectedId, true, "VENMO")
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ORDER_NOT_PAYABLE"));
    }

    @Test
    void preferenceDefaultsAndSmsConsentValidationAreEnforced() throws Exception {
        JsonNode defaults = preferences(buyer);
        assertThat(defaults.at("/smsConsent").asBoolean()).isFalse();
        assertThat(defaults.at("/smsOptedOut").asBoolean()).isFalse();
        assertThat(defaults.get("preferences").size()).isEqualTo(9);
        assertThat(preference(defaults, "MENU_PUBLISHED"))
            .containsExactly(true, false);
        assertThat(preference(defaults, "ORDER_READY"))
            .containsExactly(true, true);

        writePreferences(buyer, false, "ORDER_READY", true, true)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("SMS_CONSENT_REQUIRED"));

        JsonNode updated = objectMapper.readTree(writePreferences(buyer, true, "ORDER_READY", true, true)
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(updated.at("/smsConsent").asBoolean()).isTrue();
        assertThat(preference(updated, "ORDER_READY")).containsExactly(true, true);
        assertThat(auditCount("NOTIFICATION_PREFERENCES_UPDATED")).isEqualTo(1);
    }

    @Test
    void orderEventsSendVenmoAcceptedMapsReadyAndRejectionReason() throws Exception {
        enableSms(buyer, "ORDER_ACCEPTED", "ORDER_READY", "ORDER_REJECTED");
        ResultActions acceptedOrder = createOrder(buyer, 1, true, null, null, null);
        String acceptedId = orderId(acceptedOrder);
        String acceptedNumber = orderNumber(acceptedOrder);

        adminPost(acceptedId, "/accept").andExpect(status().isOk());
        processOutbox();
        dispatcher.dispatchDue();
        EmailMessage acceptedEmail = email("Order " + acceptedNumber + " accepted");
        assertThat(acceptedEmail.to()).isEqualTo("buyer@example.com");
        assertThat(acceptedEmail.textBody()).contains("@bethanysbakery", "$5.00");
        assertThat(sms("Order " + acceptedNumber + " accepted").body()).contains("@bethanysbakery", "$5.00");

        adminPost(acceptedId, "/ready").andExpect(status().isOk());
        dispatcher.dispatchDue();
        assertThat(email("Order " + acceptedNumber + " ready for pickup").textBody())
            .contains("https://public.example.com/orders/");
        assertThat(sms("Order " + acceptedNumber + " is ready").body())
            .contains("https://public.example.com/orders/");

        ResultActions rejected = createOrder(buyer, 1, false, null, null, "reject-me");
        String rejectedId = orderId(rejected);
        mvc.perform(post("/admin/v1/orders/" + rejectedId + "/reject").headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"quality issue\"}"))
            .andExpect(status().isOk());
        dispatcher.dispatchDue();
        assertThat(email("Order " + orderNumber(rejected) + " update").textBody()).contains("quality issue");
        assertThat(sms("Order " + orderNumber(rejected) + " was rejected").body()).contains("quality issue");
    }

    @Test
    void textbeeWebhookSignatureGovernsStopStartAndReconsent() throws Exception {
        enableSms(buyer, "ORDER_ACCEPTED", "ORDER_READY");
        String stop = "{\"from\":\"" + buyer.phone() + "\",\"message\":\"STOP\"}";

        webhook(stop, "bad-signature").andExpect(status().isUnauthorized());
        assertThat(preferences(buyer).at("/smsOptedOut").asBoolean()).isFalse();
        assertThat(auditCount("SMS_OPT_OUT")).isZero();

        String queuedOrderId = orderId(createOrder(buyer, 1, false, null, null, "queued-stop"));
        adminPost(queuedOrderId, "/accept").andExpect(status().isOk());
        processOutbox();
        UUID queuedNotificationId = notificationId(
            NotificationKeys.orderEvent("ORDER_ACCEPTED", UUID.fromString(queuedOrderId)));
        assertThat(deliveryStatus(queuedNotificationId, "SMS")).isEqualTo("QUEUED");

        webhook(stop, hmac(stop)).andExpect(status().isNoContent());
        assertThat(preferences(buyer).at("/smsOptedOut").asBoolean()).isTrue();
        assertThat(auditCount("SMS_OPT_OUT")).isEqualTo(1);

        dispatcher.dispatchDue();
        assertThat(countSms("accepted")).isZero();
        assertThat(deliveryStatus(queuedNotificationId, "SMS")).isEqualTo("SUPPRESSED");

        String start = "{\"from\":\"" + buyer.phone() + "\",\"message\":\"START\"}";
        webhook(start, hmac(start)).andExpect(status().isNoContent());
        assertThat(preferences(buyer).at("/smsOptedOut").asBoolean()).isFalse();
        assertThat(auditCount("SMS_OPT_IN")).isEqualTo(1);

        webhook(stop, hmac(stop)).andExpect(status().isNoContent());
        writePreferences(buyer, true, "ORDER_READY", true, true).andExpect(status().isOk());
        assertThat(preferences(buyer).at("/smsOptedOut").asBoolean()).isFalse();

        String orderId = orderId(createOrder(buyer, 1, false, null, null, "after-stop"));
        adminPost(orderId, "/accept").andExpect(status().isOk());
        adminPost(orderId, "/ready").andExpect(status().isOk());
        dispatcher.dispatchDue();
        assertThat(countSms("is ready")).isPositive();
    }

    @Test
    void menuSchedulerQueuesOneNotificationAndSetsNotifySentAt() throws Exception {
        jdbc.update("update menu set notify_at = ?, notify_sent_at = null where id = ?::uuid",
            java.sql.Timestamp.from(BASE_NOW.minusSeconds(60)), menuId);

        assertThat(menuNotifications.queueDueMenuNotifications()).isEqualTo(1);
        assertThat(countNotifications("MENU_PUBLISHED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from notification where idempotency_key = ?",
            Integer.class, NotificationKeys.menuPublished(UUID.fromString(menuId)))).isEqualTo(1);
        assertThat(jdbc.queryForObject("select notify_sent_at from menu where id = ?::uuid",
            java.sql.Timestamp.class, menuId)).isNotNull();
        assertThat(auditCount("MENU_NOTIFICATION_QUEUED")).isEqualTo(1);

        dispatcher.dispatchDue();
        assertThat(countEmails("New menu from")).isEqualTo(2);
        assertThat(email("New menu from").textBody()).contains("https://public.example.com/menu/");

        assertThat(menuNotifications.queueDueMenuNotifications()).isZero();
        dispatcher.dispatchDue();
        assertThat(countNotifications("MENU_PUBLISHED")).isEqualTo(1);
        assertThat(countEmails("New menu from")).isEqualTo(2);
    }

    @Test
    void promotionalSmsQuietHoursDeferOnlySms() throws Exception {
        enableSms(buyer, "MENU_PUBLISHED");
        Instant early = Instant.parse("2026-11-18T14:00:00Z"); // 08:00 MDT
        NotificationTestConfig.NOW.set(early);
        jdbc.update("update menu set notify_at = ?, notify_sent_at = null where id = ?::uuid",
            java.sql.Timestamp.from(early), menuId);

        assertThat(menuNotifications.queueDueMenuNotifications()).isEqualTo(1);
        UUID notification = notificationId(NotificationKeys.menuPublished(UUID.fromString(menuId)));
        dispatcher.dispatchDue();
        assertThat(countEmails("New menu from")).isEqualTo(2);
        assertThat(countSms("New menu:")).isZero();
        assertThat(statusOf(notification)).isEqualTo(NotificationStatus.QUEUED);
        assertThat(deliveryStatus(notification, "SMS")).isEqualTo(NotificationStatus.QUEUED.name());

        NotificationTestConfig.NOW.set(Instant.parse("2026-11-18T16:00:00Z")); // 09:00 MDT
        dispatcher.dispatchDue();
        assertThat(countSms("New menu:")).isEqualTo(1);
        assertThat(statusOf(notification)).isEqualTo(NotificationStatus.SENT);
    }

    @Test
    void stopSuppressesFanOutMenuPublishedSmsDeferredToMorning() throws Exception {
        Instant early = Instant.parse("2026-11-18T14:00:00Z"); // 08:00 MDT
        NotificationTestConfig.NOW.set(early);
        enableSms(buyer, "MENU_PUBLISHED");
        jdbc.update("update menu set notify_at = ?, notify_sent_at = null where id = ?::uuid",
            java.sql.Timestamp.from(early), menuId);

        assertThat(menuNotifications.queueDueMenuNotifications()).isEqualTo(1);
        UUID notification = notificationId(NotificationKeys.menuPublished(UUID.fromString(menuId)));
        dispatcher.dispatchDue();
        assertThat(deliveryStatus(notification, "SMS")).isEqualTo(NotificationStatus.QUEUED.name());

        NotificationTestConfig.NOW.set(early.plusSeconds(1));
        String stop = "{\"from\":\"" + buyer.phone() + "\",\"message\":\"STOP\"}";
        webhook(stop, hmac(stop)).andExpect(status().isNoContent());

        NotificationTestConfig.NOW.set(Instant.parse("2026-11-18T16:00:00Z")); // 09:00 MDT
        dispatcher.dispatchDue();
        assertThat(countSms("New menu:")).isZero();
        assertThat(deliveryStatus(notification, "SMS")).isEqualTo("SUPPRESSED");
    }

    @Test
    void reminderSchedulerTargetsOnlyCustomersWithoutActiveOrders() throws Exception {
        createOrder(buyer, 1, false, null, null, "reminder-exclusion");
        NotificationTestConfig.reset(Instant.parse("2026-12-07T16:00:00Z"));

        assertThat(menuNotifications.queueDueReminders()).isEqualTo(1);
        assertThat(countNotifications("ORDER_REMINDER")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select customer_id::text from notification where event_type = 'ORDER_REMINDER'",
            String.class)).isEqualTo(otherBuyer.id().toString());
        assertThat(jdbc.queryForObject("select reminder_sent_at from menu_date where id = ?::uuid",
            java.sql.Timestamp.class, dateId)).isNotNull();
        assertThat(auditCount("ORDER_REMINDER_QUEUED")).isEqualTo(1);

        dispatcher.dispatchDue();
        assertThat(email("Order reminder").textBody()).contains(bakeDate.toString());

        assertThat(menuNotifications.queueDueReminders()).isZero();
        assertThat(countNotifications("ORDER_REMINDER")).isEqualTo(1);
    }

    @Test
    void failedPaidEmailRetriesWithBackoffAndAdminCanRetryAfterFailure() throws Exception {
        String orderId = orderId(createOrder(buyer, 1, false, null, null, null));
        pay(orderId, true, "VENMO").andExpect(status().isOk());
        UUID notificationId = notificationId(NotificationKeys.paid(UUID.fromString(orderId)));
        NotificationTestConfig.PAID_EMAIL_FAILURES.set(10);

        Instant next = BASE_NOW;
        for (Duration backoff : List.of(Duration.ofMinutes(1), Duration.ofMinutes(5),
                Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofHours(6))) {
            next = next.plus(backoff).plusSeconds(1);
            NotificationTestConfig.NOW.set(next);
            dispatcher.dispatchDue();
            assertThat(statusOf(notificationId)).isNotEqualTo(NotificationStatus.FAILED);
        }
        NotificationTestConfig.NOW.set(next.plus(Duration.ofHours(6)).plusSeconds(1));
        dispatcher.dispatchDue();
        assertThat(statusOf(notificationId)).isEqualTo(NotificationStatus.FAILED);
        assertThat(attemptsOf(notificationId)).isEqualTo(6);

        mvc.perform(post("/admin/v1/notifications/" + notificationId + "/retry"))
            .andExpect(status().isUnauthorized());
        NotificationTestConfig.PAID_EMAIL_FAILURES.set(0);
        reauthenticateAdmin();
        mvc.perform(post("/admin/v1/notifications/" + notificationId + "/retry").headers(adminHeaders()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("QUEUED"));
        assertThat(auditCount("NOTIFICATION_RETRY")).isEqualTo(1);

        NotificationTestConfig.NOW.set(NotificationTestConfig.NOW.get().plusSeconds(1));
        dispatcher.dispatchDue();
        assertThat(statusOf(notificationId)).isEqualTo(NotificationStatus.SENT);
        assertThat(NotificationTestConfig.EMAIL.sent).isNotEmpty();
        assertThat(NotificationTestConfig.EMAIL.sent.get(NotificationTestConfig.EMAIL.sent.size() - 1).subject())
            .contains("Paid order");
    }

    @Test
    void newOrderQueuesAdminEmailAndSmsWithDistinctKeysAndMasksDestinations() throws Exception {
        ResultActions created = createOrder(buyer, 1, false, "private note", null, "admin-alert-order");
        String orderId = orderId(created);
        String number = orderNumber(created);
        processOutbox();

        UUID emailNotification = notificationId(NotificationKeys.adminNewOrderEmail(UUID.fromString(orderId)));
        UUID smsNotification = notificationId(NotificationKeys.adminNewOrderSms(UUID.fromString(orderId)));
        assertThat(statusOf(emailNotification)).isEqualTo(NotificationStatus.QUEUED);
        assertThat(statusOf(smsNotification)).isEqualTo(NotificationStatus.QUEUED);
        assertThat(jdbc.queryForObject("select count(*) from notification where idempotency_key like 'ADMIN_NEW_ORDER:%'",
            Integer.class)).isZero();

        // Idempotency: a repeated enqueue must not duplicate either admin alert.
        notificationService.enqueueAdminNewOrder(orders.findById(UUID.fromString(orderId)).orElseThrow());
        assertThat(countAdminAlerts()).isEqualTo(2);

        dispatcher.dispatchDue();
        EmailMessage email = email("New order " + number);
        assertThat(email.to()).isEqualTo("admin@example.com");
        assertThat(email.textBody()).contains(number, bakeDate.toString());
        SmsMessage sms = sms(number);
        assertThat(sms.to()).isEqualTo("+15551234567");

        JsonNode list = objectMapper.readTree(mvc.perform(get("/admin/v1/notifications").headers(adminHeaders()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(list.get("total").asInt()).isEqualTo(2);
        assertThat(list.get("items").toString())
            .doesNotContain("admin@example.com", "+15551234567", "private note")
            .contains("ad***@example.com", "***4567");
    }

    @Test
    void failedAdminEmailAndSmsKeepBackoffAndAdminRetry() throws Exception {
        ResultActions created = createOrder(buyer, 1, false, null, null, "admin-alert-failure-order");
        UUID order = UUID.fromString(orderId(created));
        processOutbox();
        UUID emailNotification = notificationId(NotificationKeys.adminNewOrderEmail(order));
        UUID smsNotification = notificationId(NotificationKeys.adminNewOrderSms(order));
        NotificationTestConfig.ADMIN_EMAIL_FAILURES.set(10);
        NotificationTestConfig.ADMIN_SMS_FAILURES.set(10);

        Instant next = BASE_NOW;
        for (Duration backoff : List.of(Duration.ofMinutes(1), Duration.ofMinutes(5),
                Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofHours(6))) {
            next = next.plus(backoff).plusSeconds(1);
            NotificationTestConfig.NOW.set(next);
            dispatcher.dispatchDue();
            assertThat(statusOf(emailNotification)).isNotEqualTo(NotificationStatus.FAILED);
            assertThat(statusOf(smsNotification)).isNotEqualTo(NotificationStatus.FAILED);
        }
        NotificationTestConfig.NOW.set(next.plus(Duration.ofHours(6)).plusSeconds(1));
        dispatcher.dispatchDue();
        assertThat(statusOf(emailNotification)).isEqualTo(NotificationStatus.FAILED);
        assertThat(statusOf(smsNotification)).isEqualTo(NotificationStatus.FAILED);
        assertThat(attemptsOf(emailNotification)).isEqualTo(6);
        assertThat(attemptsOf(smsNotification)).isEqualTo(6);

        NotificationTestConfig.ADMIN_EMAIL_FAILURES.set(0);
        NotificationTestConfig.ADMIN_SMS_FAILURES.set(0);
        mvc.perform(post("/admin/v1/notifications/" + emailNotification + "/retry").headers(adminHeaders()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("QUEUED"));
        mvc.perform(post("/admin/v1/notifications/" + smsNotification + "/retry").headers(adminHeaders()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("QUEUED"));
        NotificationTestConfig.NOW.set(NotificationTestConfig.NOW.get().plusSeconds(1));
        dispatcher.dispatchDue();
        assertThat(statusOf(emailNotification)).isEqualTo(NotificationStatus.SENT);
        assertThat(statusOf(smsNotification)).isEqualTo(NotificationStatus.SENT);
        assertThat(auditCount("NOTIFICATION_RETRY")).isEqualTo(2);
    }

    @Test
    void allOffPreferencesSuppressNineCustomerEventsAndActiveOrderCancellationButNotAdminPaidEmail()
            throws Exception {
        assertThat(NotificationService.customerEvents()).extracting(NotificationEventType::name)
            .containsExactlyElementsOf(CUSTOMER_EVENT_TYPES);
        writeAllOff(buyer);
        String orderId = orderId(createOrder(buyer, 1, false, null, null, "all-off-order"));
        pay(orderId, true, "VENMO").andExpect(status().isOk());
        assertThat(countNotifications("ORDER_PAID")).isEqualTo(1);
        UUID order = UUID.fromString(orderId);

        for (String type : CUSTOMER_EVENT_TYPES) {
            notificationService.enqueueCustomer(buyer.id(), NotificationEventType.valueOf(type), "ORDER", order,
                "all-off:" + type, Map.of("orderNumber", "BB-ALL-OFF"), BASE_NOW);
        }

        mvc.perform(post("/admin/v1/menu-dates/" + dateId + "/cancel").headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"sold out\"}"))
            .andExpect(status().isOk());

        Integer customerDeliveries = jdbc.queryForObject("""
            select count(*) from notification_delivery d
            join notification n on n.id = d.notification_id
            where n.customer_id = ?::uuid and n.event_type in
                ('MENU_PUBLISHED','ORDER_REMINDER','ORDER_ACCEPTED','ORDER_READY','ORDER_DELAYED',
                 'ORDER_REJECTED','ORDER_CANCELLED','ORDER_EXPIRED','MENU_CANCELLED')
            """, Integer.class, buyer.id().toString());
        Integer customerNotifications = jdbc.queryForObject("""
            select count(*) from notification
            where customer_id = ?::uuid and event_type in
                ('MENU_PUBLISHED','ORDER_REMINDER','ORDER_ACCEPTED','ORDER_READY','ORDER_DELAYED',
                 'ORDER_REJECTED','ORDER_CANCELLED','ORDER_EXPIRED','MENU_CANCELLED')
            """, Integer.class, buyer.id().toString());
        assertThat(customerNotifications).isZero();
        assertThat(customerDeliveries).isZero();
        assertThat(jdbc.queryForObject("select count(*) from notification where customer_id = ?::uuid "
            + "and event_type = 'MENU_CANCELLED'", Integer.class, buyer.id().toString())).isZero();
        assertThat(deliveryStatus(notificationId(NotificationKeys.paid(order)), "EMAIL"))
            .isEqualTo(NotificationStatus.QUEUED.name());
    }

    @Test
    void menuDateCancellationNotifiesOptedInAndActiveOrderCustomers() throws Exception {
        writePreferences(buyer, false, "MENU_CANCELLED", false, false).andExpect(status().isOk());
        ResultActions created = createOrder(buyer, 1, false, null, null, "cancel-date-order");
        mvc.perform(post("/admin/v1/menu-dates/" + dateId + "/cancel").headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"sold out\"}"))
            .andExpect(status().isOk());
        assertOrderStatus(orderId(created), OrderStatus.CANCELLED);

        assertThat(countNotifications("MENU_CANCELLED")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from notification where event_type = 'MENU_CANCELLED' "
            + "and customer_id = ?::uuid", Integer.class, buyer.id().toString())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from notification where event_type = 'MENU_CANCELLED' "
            + "and customer_id = ?::uuid", Integer.class, otherBuyer.id().toString())).isEqualTo(1);

        UUID activeOrderNotification = notificationId(
            NotificationKeys.menuCancelled(UUID.fromString(dateId), buyer.id()));
        assertThat(statusOf(activeOrderNotification)).isNotEqualTo(NotificationStatus.SUPPRESSED);
        assertThat(deliveryStatus(activeOrderNotification, "EMAIL")).isEqualTo(NotificationStatus.QUEUED.name());

        dispatcher.dispatchDue();
        assertThat(countEmails("Menu update")).isEqualTo(2);
        assertThat(countEmailsTo(buyer.email())).isEqualTo(1);
    }

    @Test
    void delayEndpointPersistsReasonAndQueuesCustomerNotice() throws Exception {
        String orderId = orderId(createOrder(buyer, 1, false, null, null, "delay-order"));
        adminPost(orderId, "/accept").andExpect(status().isOk());

        mvc.perform(post("/admin/v1/orders/" + orderId + "/delay").headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"oven repair\"}"))
            .andExpect(status().isAccepted());

        assertThat(jdbc.queryForObject("select delay_reason from orders where id = ?::uuid",
            String.class, orderId)).isEqualTo("oven repair");
        assertThat(jdbc.queryForObject("select delay_notified_at from orders where id = ?::uuid",
            java.sql.Timestamp.class, orderId)).isNotNull();
        assertThat(countNotifications("ORDER_DELAYED")).isEqualTo(1);
        assertThat(auditCount("ORDER_DELAY_NOTIFIED")).isEqualTo(1);

        dispatcher.dispatchDue();
        assertThat(email("Update on order").textBody()).contains("oven repair");
    }

    @Test
    void invalidRequestsAreRejectedAndValidMalformedWebhookIsAcknowledged() throws Exception {
        String orderId = orderId(createOrder(buyer, 1, false, null, null, null));
        mvc.perform(put("/admin/v1/orders/" + orderId + "/payment").headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mvc.perform(get("/admin/v1/notifications").param("status", "BOGUS").headers(adminHeaders()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        String malformed = "{not json";
        webhook(malformed, hmac(malformed)).andExpect(status().isNoContent());
        assertThat(auditCount("SMS_OPT_OUT")).isZero();
    }

    @Test
    void adminReminderEndpointAuditsAdminActor() throws Exception {
        mvc.perform(post("/admin/v1/menu-dates/" + dateId + "/remind").headers(adminHeaders()))
            .andExpect(status().isAccepted());
        assertThat(jdbc.queryForObject("select count(*) from audit_event "
            + "where action = 'ORDER_REMINDER_QUEUED' and actor_type = 'ADMIN'", Integer.class)).isEqualTo(1);
    }

    @Test
    void outboxRowsReachProcessedStateAndAdminListMasksDestinations() throws Exception {
        String orderId = orderId(createOrder(buyer, 1, false, null, null, null));
        assertOutboxStatus(orderId, "PENDING");
        processOutbox();
        assertOutboxStatus(orderId, "PROCESSED");
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where status = 'PENDING'",
            Integer.class)).isZero();

        pay(orderId, true, "VENMO").andExpect(status().isOk());
        JsonNode list = objectMapper.readTree(mvc.perform(get("/admin/v1/notifications").headers(adminHeaders()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(list.get("total").asInt()).isEqualTo(3);
        assertThat(list.get("items").toString()).doesNotContain("admin@example.com")
            .contains("ad***@example.com");
    }

    // -- helpers ----------------------------------------------------------

    private void authenticateAdmin() throws Exception {
        var admin = seedAdmin("notifications@example.com", PASSWORD, List.of());
        adminId = admin.id();
        adminTotpSecret = admin.totpSecret();
        var device = seedDevice(admin.id(), "notifications-device-key", "Pixel");
        deviceId = device.id();
        deviceKey = device.apiKey();
        loginAdmin();
        assertThat(adminId).isNotNull();
    }

    private void reauthenticateAdmin() throws Exception {
        loginAdmin();
    }

    private void loginAdmin() throws Exception {
        MvcResult login = mvc.perform(post("/admin/v1/auth/login")
                .header("X-Device-Key", deviceKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"email":"notifications@example.com","password":"%s","totpCode":"%s"}
                    """.formatted(PASSWORD, currentTotpCode(adminTotpSecret))))
            .andExpect(status().isOk())
            .andReturn();
        accessToken = objectMapper.readTree(login.getResponse().getContentAsString())
            .get("accessToken").asText();
    }

    private void setupMenu() throws Exception {
        String plainTemplate = template("Plain Roll", false);
        String customTemplate = template("Custom Roll", true);
        bakeDate = BASE_NOW.atZone(ZONE).toLocalDate().plusDays(20);
        menuId = menu("Notification menu");
        dateId = date(menuId, bakeDate);
        windowId = window(dateId, 12, 14, 50);
        plainOfferingId = offering(dateId, plainTemplate, 400, 10);
        customOfferingId = offering(dateId, customTemplate, 400, 10);
        mvc.perform(post("/admin/v1/menus/" + menuId + "/publish").headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON).content("{\"notificationEnabled\":true}"))
            .andExpect(status().isOk());
        JsonNode publicOffering = objectMapper.readTree(mvc.perform(get("/api/v1/offerings/" + customOfferingId))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        optionId = publicOffering.at("/customizationGroups/0/options/0/id").asText();
    }

    private String template(String name, boolean withGroup) throws Exception {
        ObjectNode template = objectMapper.createObjectNode();
        template.put("name", name);
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

    private String menu(String title) throws Exception {
        MvcResult result = mvc.perform(post("/admin/v1/menus").headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"" + title + "\"}"))
            .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String date(String menu, LocalDate value) throws Exception {
        MvcResult result = mvc.perform(post("/admin/v1/menus/" + menu + "/dates").headers(adminHeaders())
                .contentType(MediaType.APPLICATION_JSON).content("{\"bakeDate\":\"" + value + "\"}"))
            .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String window(String menuDate, int from, int to, int maxOrders) throws Exception {
        String start = bakeDate.atTime(from, 0).atZone(ZONE).toInstant().toString();
        String end = bakeDate.atTime(to, 0).atZone(ZONE).toInstant().toString();
        MvcResult result = mvc.perform(post("/admin/v1/menu-dates/" + menuDate + "/pickup-windows")
                .headers(adminHeaders()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\":\"Morning\",\"startAt\":\"" + start + "\",\"endAt\":\"" + end
                    + "\",\"maxOrders\":" + maxOrders + "}"))
            .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private String offering(String menuDate, String itemTemplate, int price, int quantity) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("itemTemplateId", itemTemplate);
        body.put("priceCents", price);
        body.put("quantityLimit", quantity);
        ArrayNode windows = body.putArray("windowIds");
        windows.add(windowId);
        MvcResult result = mvc.perform(post("/admin/v1/menu-dates/" + menuDate + "/offerings")
                .headers(adminHeaders()).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private CustomerFixture customer(String email, String name, String phone) {
        Customer customer = new Customer(Uuids.v7(), name, email, phone, CustomerStatus.ACTIVE,
            NotificationTestConfig.NOW.get());
        customer.markEmailVerified(NotificationTestConfig.NOW.get());
        customer.markPhoneVerified(NotificationTestConfig.NOW.get());
        customers.save(customer);
        var tokens = sessions.create(SubjectType.CUSTOMER, customer.getId(), null, "test", "10.0.0.9");
        return new CustomerFixture(new Cookie("bb_access", tokens.accessToken()), customer.getId(), email, phone);
    }

    private ResultActions createOrder(CustomerFixture fixture, int quantity, boolean custom, String orderNotes,
            String lineNotes, String idempotencyKey) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("menuDateId", dateId);
        body.put("pickupWindowId", windowId);
        if (idempotencyKey != null) body.put("idempotency_key", idempotencyKey);
        if (orderNotes != null) body.put("notes", orderNotes);
        ArrayNode items = body.putArray("items");
        ObjectNode item = items.addObject();
        item.put("offeringId", custom ? customOfferingId : plainOfferingId);
        item.put("quantity", quantity);
        if (lineNotes != null) item.put("notes", lineNotes);
        ArrayNode options = item.putArray("optionIds");
        if (custom) options.add(optionId);
        Csrf csrf = csrf(fixture);
        return mvc.perform(post("/api/v1/orders").cookie(fixture.cookie(), csrf.cookie())
                .header("X-CSRF-Token", csrf.token())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isCreated());
    }

    private Csrf csrf(CustomerFixture fixture) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/auth/csrf").cookie(fixture.cookie()))
            .andExpect(status().isOk()).andReturn();
        String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
        return new Csrf(cookieValue(result, "bb_csrf"), token);
    }

    private ResultActions pay(String orderId, boolean paid, String method) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("paid", paid);
        if (method != null) body.put("method", method);
        return mvc.perform(put("/admin/v1/orders/" + orderId + "/payment").headers(adminHeaders())
            .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions adminPost(String orderId, String suffix) throws Exception {
        return mvc.perform(post("/admin/v1/orders/" + orderId + suffix).headers(adminHeaders()));
    }

    private JsonNode preferences(CustomerFixture fixture) throws Exception {
        return objectMapper.readTree(mvc.perform(get("/api/v1/me/notification-preferences")
                .cookie(fixture.cookie()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private ResultActions writePreferences(CustomerFixture fixture, boolean smsConsent, String eventType,
            boolean emailEnabled, boolean smsEnabled) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("smsConsent", smsConsent);
        ArrayNode preferences = body.putArray("preferences");
        ObjectNode pref = preferences.addObject();
        pref.put("eventType", eventType);
        pref.put("emailEnabled", emailEnabled);
        pref.put("smsEnabled", smsEnabled);
        Csrf csrf = csrf(fixture);
        return mvc.perform(put("/api/v1/me/notification-preferences")
                .cookie(fixture.cookie(), csrf.cookie())
                .header("X-CSRF-Token", csrf.token())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions writeAllOff(CustomerFixture fixture) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("smsConsent", false);
        ArrayNode preferences = body.putArray("preferences");
        for (String type : CUSTOMER_EVENT_TYPES) {
            ObjectNode pref = preferences.addObject();
            pref.put("eventType", type);
            pref.put("emailEnabled", false);
            pref.put("smsEnabled", false);
        }
        Csrf csrf = csrf(fixture);
        return mvc.perform(put("/api/v1/me/notification-preferences")
                .cookie(fixture.cookie(), csrf.cookie())
                .header("X-CSRF-Token", csrf.token())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk());
    }

    private void enableSms(CustomerFixture fixture, String... eventTypes) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("smsConsent", true);
        ArrayNode preferences = body.putArray("preferences");
        Arrays.stream(eventTypes).forEach(eventType -> {
            ObjectNode pref = preferences.addObject();
            pref.put("eventType", eventType);
            pref.put("emailEnabled", true);
            pref.put("smsEnabled", true);
        });
        Csrf csrf = csrf(fixture);
        mvc.perform(put("/api/v1/me/notification-preferences")
                .cookie(fixture.cookie(), csrf.cookie())
                .header("X-CSRF-Token", csrf.token())
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
            .andExpect(status().isOk());
    }

    private ResultActions webhook(String body, String signature) throws Exception {
        return mvc.perform(post("/api/v1/webhooks/textbee")
            .header("X-Textbee-Signature", signature)
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void processOutbox() {
        outbox.processDue();
    }

    private static List<Boolean> preference(JsonNode view, String eventType) {
        for (JsonNode pref : view.get("preferences")) {
            if (pref.get("eventType").asText().equals(eventType)) {
                return List.of(pref.get("emailEnabled").asBoolean(), pref.get("smsEnabled").asBoolean());
            }
        }
        throw new AssertionError("Missing preference " + eventType);
    }

    private String orderId(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    private String orderNumber(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString())
            .get("orderNumber").asText();
    }

    private void assertOrderStatus(String orderId, OrderStatus expected) throws Exception {
        mvc.perform(get("/admin/v1/orders/" + orderId).headers(adminHeaders()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(expected.name()));
    }

    private int countNotifications(String eventType) {
        return jdbc.queryForObject("select count(*) from notification where event_type = ?",
            Integer.class, eventType);
    }

    private int countAdminAlerts() {
        return jdbc.queryForObject(
            "select count(*) from notification where idempotency_key like 'ADMIN_NEW_ORDER_EMAIL:%' "
                + "or idempotency_key like 'ADMIN_NEW_ORDER_SMS:%'",
            Integer.class);
    }

    private UUID notificationId(String key) {
        return UUID.fromString(jdbc.queryForObject("select id from notification where idempotency_key = ?",
            String.class, key));
    }

    private NotificationStatus statusOf(UUID id) {
        return NotificationStatus.valueOf(jdbc.queryForObject("select status from notification where id = ?::uuid",
            String.class, id.toString()));
    }

    private int attemptsOf(UUID id) {
        return jdbc.queryForObject("select attempts from notification where id = ?::uuid",
            Integer.class, id.toString());
    }

    private String deliveryStatus(UUID notificationId, String channel) {
        return jdbc.queryForObject("select status from notification_delivery "
            + "where notification_id = ?::uuid and channel = ?", String.class, notificationId.toString(), channel);
    }

    private int auditCount(String action) {
        return jdbc.queryForObject("select count(*) from audit_event where action = ?", Integer.class, action);
    }

    private EmailMessage email(String subjectFragment) {
        return NotificationTestConfig.EMAIL.sent.stream()
            .filter(message -> message.subject().contains(subjectFragment))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No email with subject containing " + subjectFragment));
    }

    private int countEmails(String subjectFragment) {
        return (int) NotificationTestConfig.EMAIL.sent.stream()
            .filter(message -> message.subject().contains(subjectFragment)).count();
    }

    private int countEmailsTo(String to) {
        return (int) NotificationTestConfig.EMAIL.sent.stream()
            .filter(message -> to.equals(message.to())).count();
    }

    private SmsMessage sms(String bodyFragment) {
        return NotificationTestConfig.SMS.sent.stream()
            .filter(message -> message.body().contains(bodyFragment))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No SMS with body containing " + bodyFragment));
    }

    private int countSms(String bodyFragment) {
        return (int) NotificationTestConfig.SMS.sent.stream()
            .filter(message -> message.body().contains(bodyFragment)).count();
    }

    private String hmac(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private void assertOutboxStatus(String orderId, String expected) {
        assertThat(jdbc.queryForObject("select status from outbox_event where aggregate_id = ?::uuid "
            + "and event_type = 'OrderCreated'", String.class, orderId)).isEqualTo(expected);
    }

    private HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
        headers.set("X-Device-Key", deviceKey);
        return headers;
    }

    private record CustomerFixture(Cookie cookie, UUID id, String email, String phone) {}

    private record Csrf(Cookie cookie, String token) {}
}

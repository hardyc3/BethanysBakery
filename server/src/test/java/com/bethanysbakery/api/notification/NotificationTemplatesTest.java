package com.bethanysbakery.api.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.bethanysbakery.api.config.NotificationTemplateConfig;
import com.bethanysbakery.api.domain.notification.NotificationEventType;
import com.bethanysbakery.api.notification.NotificationMessages.EmailMessage;
import com.bethanysbakery.api.notification.NotificationMessages.PushMessage;
import com.bethanysbakery.api.notification.NotificationMessages.SmsMessage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringTemplateEngine;

class NotificationTemplatesTest {

    private static final SpringTemplateEngine HTML = new NotificationTemplateConfig().notificationHtmlEngine();
    private static final SpringTemplateEngine TEXT = new NotificationTemplateConfig().notificationTextEngine();
    private final NotificationTemplates templates = new NotificationTemplates(HTML, TEXT);

    @Test
    void htmlIsEscapedButTextIsNot() {
        EmailMessage message = templates.renderEmail(NotificationEventType.ORDER_ACCEPTED,
            Map.of("orderNumber", "BB-1", "customerName", "<script>alert('x')</script>"));

        assertThat(message.htmlBody()).contains("&lt;script&gt;").doesNotContain("<script>");
        assertThat(message.textBody()).contains("<script>alert('x')</script>");
    }

    @Test
    void paidEmailIncludesFullFulfillmentPayload() {
        Map<String, Object> payload = Map.ofEntries(
            Map.entry("orderNumber", "BB-20261120-0001"),
            Map.entry("bakeDate", "2026-11-20"),
            Map.entry("pickupWindow", "10:00-11:00"),
            Map.entry("customerName", "Alice Smith"),
            Map.entry("customerEmail", "alice@example.com"),
            Map.entry("customerPhone", "+15555550123"),
            Map.entry("totalCents", 1250),
            Map.entry("paymentMethod", "VENMO"),
            Map.entry("paidAt", "2026-11-19T12:05:00Z"),
            Map.entry("itemSummary", "2x Sourdough (Butter)")
        );

        EmailMessage message = templates.renderEmail(NotificationEventType.ORDER_PAID, payload);

        assertThat(message.subject()).contains("BB-20261120-0001");
        assertThat(message.htmlBody()).contains("BB-20261120-0001", "2026-11-20", "10:00-11:00",
            "Alice Smith", "alice@example.com", "$12.50", "VENMO", "2026-11-19T12:05:00Z",
            "2x Sourdough (Butter)");
        assertThat(message.textBody()).contains("BB-20261120-0001", "$12.50", "VENMO");
    }

    @Test
    void smsIsShortAndPushHasNoPii() {
        SmsMessage sms = templates.renderSms(NotificationEventType.ORDER_READY,
            Map.of("orderNumber", "BB-1", "firstName", "Alice", "mapsUrl", "https://public.example.com/orders/1"));
        assertThat(sms.body()).contains("BB-1", "https://public.example.com/orders/1").hasSizeLessThan(320);

        PushMessage push = templates.renderPush(NotificationEventType.ADMIN_NEW_ORDER,
            Map.of("orderNumber", "BB-1", "customerName", "Alice Smith",
                "customerEmail", "alice@example.com", "customerPhone", "+15555550123"));
        assertThat(push.title()).contains("BB-1");
        assertThat(push.body()).contains("BB-1", "Alice").doesNotContain("alice@example.com", "+15555550123");
    }

    @Test
    void templatesHandleMissingKeysWithoutFailure() {
        EmailMessage message = templates.renderEmail(NotificationEventType.MENU_PUBLISHED, Map.of());
        assertThat(message.htmlBody()).isNotBlank();
        assertThat(message.textBody()).isNotBlank();
        assertThat(templates.renderSms(NotificationEventType.ORDER_REMINDER, Map.of()).body()).isNotBlank();
    }
}

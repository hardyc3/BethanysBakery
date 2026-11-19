package com.bethanysbakery.api.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.util.ReflectionTestUtils.setField;

import com.bethanysbakery.api.domain.menu.MenuDate;
import com.bethanysbakery.api.domain.menu.MenuDateStatus;
import com.bethanysbakery.api.domain.menu.PickupWindow;
import com.bethanysbakery.api.domain.order.Order;
import com.bethanysbakery.api.domain.order.OrderLine;
import com.bethanysbakery.api.domain.order.OrderLineOption;
import com.bethanysbakery.api.domain.order.OrderStatus;
import com.bethanysbakery.api.domain.order.PaymentStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationPayloadsTest {

    private static final String BASE = "https://public.example.com";

    @Test
    void orderPayloadContainsFullFulfillmentDetails() {
        UUID orderId = UUID.randomUUID();
        UUID menuDateId = UUID.randomUUID();
        MenuDate date = new MenuDate(menuDateId, null, LocalDate.of(2026, 11, 20), Instant.now());
        PickupWindow window = new PickupWindow(UUID.randomUUID(), date, "10:00-11:00",
            Instant.parse("2026-11-20T17:00:00Z"), Instant.parse("2026-11-20T18:00:00Z"), 10, Instant.now());

        Order order = new Order(orderId, "BB-20261120-0001", UUID.randomUUID(), date, window, null,
            OrderStatus.COMPLETED, PaymentStatus.PAID, 1250, 1250, "Alice Smith",
            "alice@example.com", "+15555550123", "Leave at door", Instant.parse("2026-11-20T20:00:00Z"),
            Instant.parse("2026-11-19T12:00:00Z"));
        setField(order, "paymentMethod", "VENMO");
        setField(order, "paidAt", Instant.parse("2026-11-19T12:05:00Z"));
        setField(order, "rejectionReason", null);

        OrderLine line = new OrderLine(UUID.randomUUID(), order, UUID.randomUUID(), "Sourdough", 500, 2,
            1000, "Extra crust");
        OrderLineOption option = new OrderLineOption(UUID.randomUUID(), line, "Add-ons", "Butter", 250);
        setField(line, "options", List.of(option));
        setField(order, "lines", List.of(line));

        Map<String, Object> payload = NotificationPayloads.forOrder(order, BASE, "bethanysbakery", "Bethany's Bakery");

        assertThat(payload).containsEntry("orderNumber", "BB-20261120-0001")
            .containsEntry("bakeDate", "2026-11-20")
            .containsEntry("pickupWindow", "10:00-11:00")
            .containsEntry("customerName", "Alice Smith")
            .containsEntry("customerEmail", "alice@example.com")
            .containsEntry("customerPhone", "+15555550123")
            .containsEntry("notes", "Leave at door")
            .containsEntry("totalCents", 1250)
            .containsEntry("paymentStatus", "PAID")
            .containsEntry("paymentMethod", "VENMO")
            .containsEntry("paidAt", "2026-11-19T12:05:00Z")
            .containsEntry("mapsUrl", BASE + "/orders/" + orderId)
            .containsEntry("menuUrl", BASE + "/menu-dates/" + menuDateId)
            .containsEntry("venmoHandle", "bethanysbakery")
            .containsEntry("venmoAmount", "$12.50");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) payload.get("items");
        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item).containsEntry("itemName", "Sourdough")
                .containsEntry("quantity", 2)
                .containsEntry("lineTotalCents", 1000)
                .containsEntry("notes", "Extra crust");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> options = (List<Map<String, Object>>) item.get("options");
            assertThat(options).singleElement().satisfies(optionValue -> assertThat(optionValue)
                .containsEntry("groupName", "Add-ons")
                .containsEntry("optionName", "Butter")
                .containsEntry("priceDeltaCents", 250));
        });
    }

    @Test
    void menuPayloadsUseAbsoluteLinks() {
        UUID menuId = UUID.randomUUID();
        assertThat(NotificationPayloads.forMenuPublished(menuId, "Weekly menu", BASE))
            .containsEntry("menuTitle", "Weekly menu")
            .containsEntry("menuUrl", BASE + "/menu/" + menuId);

        UUID dateId = UUID.randomUUID();
        assertThat(NotificationPayloads.forReminder(dateId, LocalDate.of(2026, 11, 20), BASE))
            .containsEntry("bakeDate", "2026-11-20")
            .containsEntry("menuUrl", BASE + "/menu-dates/" + dateId);

        assertThat(NotificationPayloads.forMenuCancelled("MENU_DATE", dateId, LocalDate.of(2026, 11, 20), BASE))
            .containsEntry("entityType", "MENU_DATE")
            .containsEntry("entityId", dateId.toString())
            .containsEntry("bakeDate", "2026-11-20");
    }
}

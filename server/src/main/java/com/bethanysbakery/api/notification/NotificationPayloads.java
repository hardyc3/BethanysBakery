package com.bethanysbakery.api.notification;

import com.bethanysbakery.api.domain.order.Order;
import com.bethanysbakery.api.domain.order.OrderLine;
import com.bethanysbakery.api.domain.order.OrderLineOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Builds template payloads from domain objects. Pure logic — no I/O, no Spring. */
public final class NotificationPayloads {

    private NotificationPayloads() {}

    public static Map<String, Object> forOrder(Order order, String appBaseUrl, String venmoHandle,
            String venmoDisplayName) {
        String base = base(appBaseUrl);
        UUID orderId = order.getId();
        UUID menuDateId = order.getMenuDate() == null ? null : order.getMenuDate().getId();
        LocalDate bakeDate = order.getMenuDate() == null ? null : order.getMenuDate().getBakeDate();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", orderId.toString());
        payload.put("orderNumber", order.getOrderNumber());
        if (menuDateId != null) {
            payload.put("menuDateId", menuDateId.toString());
        }
        payload.put("status", order.getStatus() == null ? null : order.getStatus().name());
        payload.put("bakeDate", bakeDate == null ? null : bakeDate.toString());
        payload.put("pickupWindow", windowLabel(order));
        payload.put("pickupStart", instant(order.getPickupWindow() == null ? null : order.getPickupWindow().getStartAt()));
        payload.put("pickupEnd", instant(order.getPickupWindow() == null ? null : order.getPickupWindow().getEndAt()));
        payload.put("customerName", order.getCustomerName());
        payload.put("firstName", firstName(order.getCustomerName()));
        payload.put("customerEmail", order.getCustomerEmail());
        payload.put("customerPhone", order.getCustomerPhone());
        payload.put("notes", order.getNotes());
        payload.put("rejectionReason", order.getRejectionReason());
        payload.put("cancellationReason", order.getCancellationReason());
        payload.put("delayReason", order.getDelayReason());
        payload.put("subtotalCents", order.getSubtotalCents());
        payload.put("totalCents", order.getTotalCents());
        payload.put("totalDisplay", money(order.getTotalCents()));
        payload.put("paymentStatus", order.getPaymentStatus() == null ? null : order.getPaymentStatus().name());
        payload.put("paymentMethod", order.getPaymentMethod());
        payload.put("paidAt", instant(order.getPaidAt()));
        payload.put("mapsUrl", base + "/orders/" + orderId);
        payload.put("menuUrl", menuDateId == null ? base + "/menu" : base + "/menu-dates/" + menuDateId);
        payload.put("venmoHandle", venmoHandle);
        payload.put("venmoDisplayName", venmoDisplayName);
        payload.put("venmoAmount", money(order.getTotalCents()));

        List<Map<String, Object>> items = new ArrayList<>();
        List<String> summary = new ArrayList<>();
        for (OrderLine line : order.getLines()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("itemName", line.getItemName());
            item.put("quantity", line.getQuantity());
            item.put("unitPriceCents", line.getUnitPriceCents());
            item.put("lineTotalCents", line.getLineTotalCents());
            item.put("notes", line.getNotes());
            List<Map<String, Object>> options = new ArrayList<>();
            List<String> optionNames = new ArrayList<>();
            for (OrderLineOption option : line.getOptions()) {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("groupName", option.getGroupName());
                value.put("optionName", option.getOptionName());
                value.put("priceDeltaCents", option.getPriceDeltaCents());
                options.add(value);
                optionNames.add(option.getOptionName());
            }
            item.put("options", options);
            items.add(item);
            summary.add(line.getQuantity() + "x " + line.getItemName()
                + (optionNames.isEmpty() ? "" : " (" + String.join(", ", optionNames) + ")")
                + (line.getNotes() == null || line.getNotes().isBlank() ? "" : " — " + line.getNotes()));
        }
        payload.put("items", items);
        payload.put("itemSummary", String.join(", ", summary));
        return payload;
    }

    public static Map<String, Object> forMenuPublished(UUID menuId, String title, String appBaseUrl) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("menuId", menuId.toString());
        payload.put("menuTitle", title);
        payload.put("menuUrl", base(appBaseUrl) + "/menu/" + menuId);
        return payload;
    }

    public static Map<String, Object> forReminder(UUID menuDateId, LocalDate bakeDate, String appBaseUrl) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("menuDateId", menuDateId.toString());
        payload.put("bakeDate", bakeDate.toString());
        payload.put("menuUrl", base(appBaseUrl) + "/menu-dates/" + menuDateId);
        return payload;
    }

    public static Map<String, Object> forMenuCancelled(String entityType, UUID entityId, LocalDate bakeDate,
            String appBaseUrl) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("entityType", entityType);
        payload.put("entityId", entityId.toString());
        payload.put("bakeDate", bakeDate == null ? null : bakeDate.toString());
        payload.put("menuUrl", base(appBaseUrl) + (entityType.equals("MENU") ? "/menu/" : "/menu-dates/") + entityId);
        return payload;
    }

    private static String base(String appBaseUrl) {
        if (appBaseUrl == null || appBaseUrl.isBlank()) {
            return "";
        }
        return appBaseUrl.endsWith("/") ? appBaseUrl.substring(0, appBaseUrl.length() - 1) : appBaseUrl;
    }

    private static String windowLabel(Order order) {
        return order.getPickupWindow() == null ? null : order.getPickupWindow().getLabel();
    }

    private static String instant(java.time.Instant value) {
        return value == null ? null : value.toString();
    }

    private static String money(int cents) {
        return String.format(Locale.US, "$%.2f", cents / 100.0);
    }

    private static String firstName(String fullName) {
        if (fullName == null || fullName.isBlank()) {
            return "there";
        }
        return fullName.trim().split("\\s+")[0];
    }
}

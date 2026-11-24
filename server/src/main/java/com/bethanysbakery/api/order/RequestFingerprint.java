package com.bethanysbakery.api.order;

import com.bethanysbakery.api.api.order.OrderApi;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Lowercase SHA-256 of the canonical diagnostic order request. Never used for business decisions. */
public final class RequestFingerprint {

    private static final String FIELD_SEPARATOR = "\u001f";
    private static final String CANONICAL_VERSION = "v1";

    private RequestFingerprint() {}

    public static String sha256Hex(OrderApi.CreateOrderRequest request) {
        StringBuilder canonical = new StringBuilder(CANONICAL_VERSION);
        appendField(canonical, text(request.menuDateId()));
        appendField(canonical, text(request.pickupWindowId()));
        appendField(canonical, notes(request.notes()));

        List<Map.Entry<LineKey, Integer>> lines = normalizedLines(request.items());
        appendField(canonical, String.valueOf(lines.size()));
        for (Map.Entry<LineKey, Integer> entry : lines) {
            LineKey key = entry.getKey();
            appendField(canonical, key.offeringId());
            appendField(canonical, key.joinedOptionIds());
            appendField(canonical, key.notes());
            appendField(canonical, String.valueOf(entry.getValue()));
        }
        return sha256Hex(canonical.toString());
    }

    private static List<Map.Entry<LineKey, Integer>> normalizedLines(List<OrderApi.CreateOrderItem> items) {
        Map<LineKey, Integer> merged = new LinkedHashMap<>();
        if (items != null) {
            for (OrderApi.CreateOrderItem item : items) {
                LineKey key = new LineKey(
                    text(item.offeringId()), sortedOptionIds(item.optionIds()), notes(item.notes()));
                merged.merge(key, item.quantity() == null ? 0 : item.quantity(), Integer::sum);
            }
        }
        List<Map.Entry<LineKey, Integer>> lines = new ArrayList<>(merged.entrySet());
        lines.sort(Comparator
            .comparing((Map.Entry<LineKey, Integer> entry) -> entry.getKey().offeringId())
            .thenComparing(entry -> entry.getKey().joinedOptionIds())
            .thenComparing(entry -> entry.getKey().notes()));
        return lines;
    }

    private static List<String> sortedOptionIds(List<UUID> optionIds) {
        if (optionIds == null) return List.of();
        return optionIds.stream().map(RequestFingerprint::text).distinct().sorted().toList();
    }

    private static void appendField(StringBuilder canonical, String value) {
        canonical.append(value.length()).append(':').append(value).append(FIELD_SEPARATOR);
    }

    private static String notes(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }

    private static String text(UUID value) {
        return value == null ? "" : value.toString();
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private record LineKey(String offeringId, List<String> optionIds, String notes) {
        String joinedOptionIds() {
            return String.join(FIELD_SEPARATOR, optionIds);
        }
    }
}

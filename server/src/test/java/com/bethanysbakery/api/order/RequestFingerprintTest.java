package com.bethanysbakery.api.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.bethanysbakery.api.api.order.OrderApi;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Unit tests for the diagnostic canonical request fingerprint. */
class RequestFingerprintTest {

    private static final UUID DATE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID WINDOW = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID O1 = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID O2 = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID OPT1 = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID OPT2 = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

    @Test
    void fingerprintIsLowercaseSha256Hex() {
        assertThat(RequestFingerprint.sha256Hex(request("note", List.of(item(O1, 1, List.of(), null)))))
            .matches("[0-9a-f]{64}");
    }

    @Test
    void stableAcrossItemAndOptionOrderingAndTrimmedNotes() {
        String first = RequestFingerprint.sha256Hex(request("  menu note  ", List.of(
            item(O2, 1, List.of(OPT2, OPT1), "  line note "),
            item(O1, 2, List.of(), null))));
        String second = RequestFingerprint.sha256Hex(request("menu note", List.of(
            item(O1, 2, List.of(), " "),
            item(O2, 1, List.of(OPT1, OPT2), "line note"))));

        assertThat(second).isEqualTo(first);
    }

    @Test
    void duplicateLinesMergeBeforeHashing() {
        String merged = RequestFingerprint.sha256Hex(request(null, List.of(
            item(O1, 2, List.of(OPT1), "n"), item(O1, 3, List.of(OPT1), "n"))));
        String single = RequestFingerprint.sha256Hex(request(null, List.of(item(O1, 5, List.of(OPT1), "n"))));

        assertThat(single).isEqualTo(merged);
    }

    @Test
    void blankAndNullNotesCanonicalizeToEmpty() {
        String nullNotes = RequestFingerprint.sha256Hex(request(null, List.of(item(O1, 1, List.of(), null))));
        String blankNotes = RequestFingerprint.sha256Hex(request("   ", List.of(item(O1, 1, List.of(), "\t"))));

        assertThat(blankNotes).isEqualTo(nullNotes);
    }

    @Test
    void ignoresIdempotencyKeyClientTimestampAndPrices() {
        String baseline = RequestFingerprint.sha256Hex(request("n", List.of(item(O1, 1, List.of(), "l"))));
        String changed = RequestFingerprint.sha256Hex(new OrderApi.CreateOrderRequest(
            DATE, WINDOW, "different-key", List.of(item(O1, 1, List.of(), "l")), "n",
            Instant.parse("2099-01-01T00:00:00Z")));

        assertThat(changed).isEqualTo(baseline);
    }

    @Test
    void changesWhenAnyCanonicalFieldChanges() {
        String baseline = RequestFingerprint.sha256Hex(request("note", List.of(item(O1, 1, List.of(OPT1), "line"))));

        assertThat(RequestFingerprint.sha256Hex(request("other note", List.of(item(O1, 1, List.of(OPT1), "line")))))
            .isNotEqualTo(baseline);
        assertThat(RequestFingerprint.sha256Hex(request("note", List.of(item(O1, 2, List.of(OPT1), "line")))))
            .isNotEqualTo(baseline);
        assertThat(RequestFingerprint.sha256Hex(request("note", List.of(item(O1, 1, List.of(OPT2), "line")))))
            .isNotEqualTo(baseline);
        assertThat(RequestFingerprint.sha256Hex(request("note", List.of(item(O1, 1, List.of(OPT1), "other")))))
            .isNotEqualTo(baseline);
        assertThat(RequestFingerprint.sha256Hex(request("note", List.of(item(O2, 1, List.of(OPT1), "line")))))
            .isNotEqualTo(baseline);
        assertThat(RequestFingerprint.sha256Hex(new OrderApi.CreateOrderRequest(
            UUID.fromString("33333333-3333-3333-3333-333333333333"), WINDOW, null,
            List.of(item(O1, 1, List.of(OPT1), "line")), "note", null)))
            .isNotEqualTo(baseline);
        assertThat(RequestFingerprint.sha256Hex(new OrderApi.CreateOrderRequest(
            DATE, UUID.fromString("44444444-4444-4444-4444-444444444444"), null,
            List.of(item(O1, 1, List.of(OPT1), "line")), "note", null)))
            .isNotEqualTo(baseline);
    }

    private static OrderApi.CreateOrderRequest request(String notes, List<OrderApi.CreateOrderItem> items) {
        return new OrderApi.CreateOrderRequest(DATE, WINDOW, "key", items, notes, null);
    }

    private static OrderApi.CreateOrderItem item(UUID offering, int quantity, List<UUID> optionIds, String notes) {
        return new OrderApi.CreateOrderItem(offering, quantity, optionIds, notes);
    }
}

package com.bethanysbakery.api.api.order;

import com.bethanysbakery.api.domain.order.CancelledBy;
import com.bethanysbakery.api.domain.order.OrderStatus;
import com.bethanysbakery.api.domain.order.PaymentStatus;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class OrderApi {

    private OrderApi() {}

    public record CreateOrderRequest(
        @NotNull UUID menuDateId,
        @NotNull UUID pickupWindowId,
        @JsonProperty("idempotency_key")
        @JsonAlias("idempotencyKey")
        @Size(max = 128) String idempotencyKey,
        @Valid @NotEmpty @Size(max = 50) List<@Valid CreateOrderItem> items,
        @Size(max = 500) String notes
    ) {}

    public record CreateOrderItem(
        @NotNull UUID offeringId,
        @NotNull @Min(1) @Max(99) Integer quantity,
        @Size(max = 100) List<UUID> optionIds,
        @Size(max = 300) String notes
    ) {}

    public record CancelRequest(@Size(max = 500) String reason) {}

    public record ReasonRequest(@NotBlank @Size(max = 500) String reason) {}

    public enum OrderFilter { ALL, ACTIVE, COMPLETED, CANCELLED }

    public record Order(
        UUID id,
        String orderNumber,
        OrderStatus status,
        PaymentStatus paymentStatus,
        String paymentMethod,
        UUID menuDateId,
        LocalDate bakeDate,
        PickupWindowRef pickupWindow,
        OrderCustomer customer,
        List<OrderLine> lines,
        int subtotalCents,
        int totalCents,
        String notes,
        String rejectionReason,
        String cancellationReason,
        CancelledBy cancelledBy,
        Instant acceptedAt,
        Instant readyAt,
        Instant completedAt,
        Instant paidAt,
        Instant delayNotifiedAt,
        String delayReason,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt,
        PaymentInstructions paymentInstructions
    ) {}

    public record PickupWindowRef(UUID id, String label, Instant startAt, Instant endAt) {}

    public record OrderCustomer(String name, String email, String phone) {}

    public record OrderLine(UUID id, UUID offeringId, String itemName, int unitPriceCents, int quantity,
            int lineTotalCents, List<OrderLineOption> options, String notes) {}

    public record OrderLineOption(String groupName, String optionName, int priceDeltaCents) {}

    public record PaymentInstructions(String venmoHandle, String venmoDisplayName, int amountCents,
            String note, String venmoDeepLink) {}

    public record OrderList(List<Order> items, long total) {}

    public record AdminSummary(LocalDate bakeDate, long pending, long accepted, long ready, long completed,
            long unpaid) {}

    public record BakeList(LocalDate bakeDate, Instant generatedAt, long orderCount, List<BakeListItem> items) {}

    public record BakeListItem(UUID offeringId, String itemName, int totalQuantity,
            List<BakeListOptionCount> options) {}

    public record BakeListOptionCount(String groupName, String optionName, int count) {}
}

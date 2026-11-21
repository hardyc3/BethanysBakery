package com.bethanysbakery.api.order;

import com.bethanysbakery.api.api.order.OrderApi;
import com.bethanysbakery.api.config.BakeryProperties;
import com.bethanysbakery.api.domain.order.Order;
import com.bethanysbakery.api.domain.order.OrderLine;
import com.bethanysbakery.api.domain.order.OrderLineOption;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class OrderViews {

    private final BakeryProperties properties;

    public OrderViews(BakeryProperties properties) {
        this.properties = properties;
    }

    public OrderApi.Order order(Order order) {
        List<OrderApi.OrderLine> lines = order.getLines().stream()
            .map(this::line)
            .toList();
        return new OrderApi.Order(
            order.getId(),
            order.getOrderNumber(),
            order.getStatus(),
            order.getPaymentStatus(),
            order.getPaymentMethod(),
            order.getMenuDate().getId(),
            order.getMenuDate().getBakeDate(),
            new OrderApi.PickupWindowRef(order.getPickupWindow().getId(), order.getPickupWindow().getLabel(),
                order.getPickupWindow().getStartAt(), order.getPickupWindow().getEndAt()),
            new OrderApi.OrderCustomer(order.getCustomerName(), order.getCustomerEmail(), order.getCustomerPhone()),
            lines,
            order.getSubtotalCents(),
            order.getTotalCents(),
            order.getNotes(),
            order.getRejectionReason(),
            order.getCancellationReason(),
            order.getCancelledBy(),
            order.getAcceptedAt(),
            order.getReadyAt(),
            order.getCompletedAt(),
            order.getPaidAt(),
            order.getDelayNotifiedAt(),
            order.getDelayReason(),
            order.getExpiresAt(),
            order.getCreatedAt(),
            order.getUpdatedAt(),
            paymentInstructions(order)
        );
    }

    private OrderApi.OrderLine line(OrderLine line) {
        List<OrderApi.OrderLineOption> options = line.getOptions().stream()
            .map(option -> new OrderApi.OrderLineOption(option.getGroupName(), option.getOptionName(),
                option.getPriceDeltaCents()))
            .toList();
        return new OrderApi.OrderLine(line.getId(), line.getOfferingId(), line.getItemName(),
            line.getUnitPriceCents(), line.getQuantity(), line.getLineTotalCents(), options, line.getNotes());
    }

    private OrderApi.PaymentInstructions paymentInstructions(Order order) {
        BakeryProperties.Venmo venmo = properties.venmo();
        String base = properties.auth().appPublicBaseUrl();
        String deepLink = base == null || base.isBlank() ? null
            : (base.endsWith("/") ? base.substring(0, base.length() - 1) : base)
                + "/orders/" + order.getId() + "/pay";
        return new OrderApi.PaymentInstructions(venmo.handle(), venmo.displayName(), order.getTotalCents(),
            order.getOrderNumber(), deepLink);
    }
}

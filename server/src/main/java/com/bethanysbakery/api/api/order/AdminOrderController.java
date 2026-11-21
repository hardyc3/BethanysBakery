package com.bethanysbakery.api.api.order;

import com.bethanysbakery.api.api.notification.NotificationApi;
import com.bethanysbakery.api.auth.admin.AdminPrincipal;
import com.bethanysbakery.api.domain.order.OrderStatus;
import com.bethanysbakery.api.domain.order.PaymentStatus;
import com.bethanysbakery.api.notification.PaymentService;
import com.bethanysbakery.api.order.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/v1/orders")
public class AdminOrderController {

    private final OrderService service;
    private final PaymentService payments;

    public AdminOrderController(OrderService service, PaymentService payments) {
        this.service = service;
        this.payments = payments;
    }

    @GetMapping
    public OrderApi.OrderList list(@RequestParam(required = false) LocalDate date,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) PaymentStatus paymentStatus,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listForAdmin(date, status, paymentStatus, page, size);
    }

    @GetMapping("/{orderId}")
    public OrderApi.Order get(@PathVariable UUID orderId) {
        return service.getForAdmin(orderId);
    }

    @PostMapping("/{orderId}/accept")
    public OrderApi.Order accept(@PathVariable UUID orderId, @AuthenticationPrincipal AdminPrincipal principal) {
        return service.accept(orderId, principal.adminId());
    }

    @PostMapping("/{orderId}/reject")
    public OrderApi.Order reject(@PathVariable UUID orderId,
            @Valid @RequestBody OrderApi.ReasonRequest request,
            @AuthenticationPrincipal AdminPrincipal principal) {
        return service.reject(orderId, request.reason(), principal.adminId());
    }

    @PostMapping("/{orderId}/ready")
    public OrderApi.Order ready(@PathVariable UUID orderId, @AuthenticationPrincipal AdminPrincipal principal) {
        return service.ready(orderId, principal.adminId());
    }

    @PostMapping("/{orderId}/completed")
    public OrderApi.Order completed(@PathVariable UUID orderId, @AuthenticationPrincipal AdminPrincipal principal) {
        return service.complete(orderId, principal.adminId());
    }

    @PostMapping("/{orderId}/cancel")
    public OrderApi.Order cancel(@PathVariable UUID orderId,
            @Valid @RequestBody OrderApi.ReasonRequest request,
            @AuthenticationPrincipal AdminPrincipal principal) {
        return service.cancelByAdmin(orderId, request.reason(), principal.adminId());
    }

    @PutMapping("/{orderId}/payment")
    @Operation(operationId = "updateOrderPayment")
    public OrderApi.Order payment(@PathVariable UUID orderId,
            @Valid @RequestBody NotificationApi.PaymentUpdateRequest request,
            @AuthenticationPrincipal AdminPrincipal principal) {
        return payments.updatePayment(orderId, request.paid(), request.method(), principal.adminId());
    }

    @PostMapping("/{orderId}/delay")
    @Operation(operationId = "notifyOrderDelayed")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void delay(@PathVariable UUID orderId,
            @RequestBody(required = false) NotificationApi.DelayOrderRequest request,
            @AuthenticationPrincipal AdminPrincipal principal) {
        payments.notifyDelayed(orderId, request == null ? null : request.reason(), principal.adminId());
    }
}

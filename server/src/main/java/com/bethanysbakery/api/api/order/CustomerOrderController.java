package com.bethanysbakery.api.api.order;

import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.auth.RateLimiter;
import com.bethanysbakery.api.auth.customer.CustomerPrincipal;
import com.bethanysbakery.api.order.OrderService;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class CustomerOrderController {

    private static final int ORDER_CREATION_LIMIT = 20;
    private static final Duration ORDER_CREATION_WINDOW = Duration.ofMinutes(1);
    private static final int ORDER_CANCEL_LIMIT = 20;
    private static final Duration ORDER_CANCEL_WINDOW = Duration.ofMinutes(1);

    private final OrderService service;
    private final RateLimiter rateLimiter;

    public CustomerOrderController(OrderService service, RateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/orders")
    public ResponseEntity<OrderApi.Order> create(@Valid @RequestBody OrderApi.CreateOrderRequest request,
            @AuthenticationPrincipal CustomerPrincipal principal) {
        requireRate("order-create:" + principal.customerId(), ORDER_CREATION_LIMIT, ORDER_CREATION_WINDOW);
        OrderService.Created result = service.create(request, principal.customerId());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.order());
    }

    @GetMapping("/me/orders")
    public OrderApi.OrderList list(@AuthenticationPrincipal CustomerPrincipal principal,
            @RequestParam(required = false) OrderApi.OrderFilter filter,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.listForCustomer(principal.customerId(), filter, page, size);
    }

    @GetMapping("/orders/{orderId}")
    public OrderApi.Order get(@PathVariable UUID orderId,
            @AuthenticationPrincipal CustomerPrincipal principal) {
        return service.getForCustomer(orderId, principal.customerId());
    }

    @PostMapping("/orders/{orderId}/cancel")
    public OrderApi.Order cancel(@PathVariable UUID orderId,
            @AuthenticationPrincipal CustomerPrincipal principal,
            @Valid @RequestBody(required = false) OrderApi.CancelRequest request) {
        requireRate("order-cancel:" + principal.customerId(), ORDER_CANCEL_LIMIT, ORDER_CANCEL_WINDOW);
        return service.cancelByCustomer(orderId, principal.customerId(), request == null ? null : request.reason());
    }

    private void requireRate(String key, int limit, Duration window) {
        RateLimiter.Result allowed = rateLimiter.tryAcquire(key, limit, window);
        if (!allowed.allowed()) {
            throw AuthException.rateLimited(allowed.retryAfterSeconds());
        }
    }
}

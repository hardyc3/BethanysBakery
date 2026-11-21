package com.bethanysbakery.api.api.notification;

import com.bethanysbakery.api.auth.customer.CustomerPrincipal;
import com.bethanysbakery.api.notification.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me/notification-preferences")
public class CustomerNotificationPreferencesController {

    private final NotificationService service;

    public CustomerNotificationPreferencesController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(operationId = "getNotificationPreferences")
    public NotificationApi.NotificationPreferencesView get(@AuthenticationPrincipal CustomerPrincipal principal) {
        return service.preferences(principal.customerId());
    }

    @PutMapping
    @Operation(operationId = "updateNotificationPreferences")
    public NotificationApi.NotificationPreferencesView update(@AuthenticationPrincipal CustomerPrincipal principal,
            @Valid @RequestBody NotificationApi.NotificationPreferencesWrite request) {
        return service.updatePreferences(principal.customerId(), request);
    }
}

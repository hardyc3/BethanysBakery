package com.bethanysbakery.api.api.notification;

import com.bethanysbakery.api.auth.admin.AdminPrincipal;
import com.bethanysbakery.api.notification.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/v1/notifications")
public class AdminNotificationController {

    private final NotificationService service;

    public AdminNotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(operationId = "listNotifications")
    public NotificationApi.NotificationList list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(NotificationApi.status(status), page, size);
    }

    @PostMapping("/{notificationId}/retry")
    @Operation(operationId = "retryNotification")
    public NotificationApi.NotificationView retry(@PathVariable UUID notificationId,
            @AuthenticationPrincipal AdminPrincipal principal) {
        return NotificationApi.view(service.retry(notificationId, principal.adminId()));
    }
}

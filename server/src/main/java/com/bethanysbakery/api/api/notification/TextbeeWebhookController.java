package com.bethanysbakery.api.api.notification;

import com.bethanysbakery.api.notification.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/webhooks/textbee")
public class TextbeeWebhookController {

    private final NotificationService service;

    public TextbeeWebhookController(NotificationService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(operationId = "receiveTextbeeWebhook")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void receive(@RequestBody byte[] rawBody,
            @RequestHeader(value = "X-Textbee-Signature", required = false) String signature) {
        service.handleTextbeeWebhook(rawBody, signature);
    }
}

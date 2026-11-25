package com.bethanysbakery.api.notification;

import com.bethanysbakery.api.config.NotificationProperties;
import com.bethanysbakery.api.notification.NotificationMessages.DeliveryResult;
import com.bethanysbakery.api.notification.NotificationMessages.SmsMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

@Component
@Profile("!local & !dummy")
public class TextbeeSmsSender implements SmsSender {

    private final NotificationProperties properties;
    private final ObjectMapper mapper;
    private final RestTemplate rest;

    @Autowired
    public TextbeeSmsSender(NotificationProperties properties, ObjectMapper mapper) {
        this(properties, mapper, ProviderHttp.timedRestTemplate());
    }

    TextbeeSmsSender(NotificationProperties properties, ObjectMapper mapper, RestTemplate rest) {
        this.properties = properties;
        this.mapper = mapper;
        this.rest = rest;
    }

    @Override
    public DeliveryResult send(SmsMessage message) {
        if (!properties.textbeeConfigured()) {
            throw new ProviderNotConfiguredException("Textbee");
        }
        NotificationProperties.Textbee config = properties.textbee();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, String> body = Map.of(
            "apiKey", config.apiKey(),
            "deviceID", config.deviceId(),
            "receiver", message.to(),
            "message", message.body());

        try {
            URI uri = URI.create(config.baseUrl() + "/api/v1/sendSMS");
            ResponseEntity<String> response = rest.exchange(uri, HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
            JsonNode node = mapper.readTree(response.getBody() == null ? "{}" : response.getBody());
            if (node.hasNonNull("success") && !node.get("success").asBoolean()) {
                return DeliveryResult.failed("Textbee rejected message");
            }
            String id = node.path("id").asText(node.path("messageId").asText(null));
            return DeliveryResult.sent(id == null ? "textbee-" + UUID.randomUUID() : id);
        } catch (HttpStatusCodeException ex) {
            return DeliveryResult.failed("Textbee HTTP " + ex.getStatusCode().value());
        } catch (Exception ex) {
            return DeliveryResult.failed("Textbee " + ex.getClass().getSimpleName());
        }
    }
}

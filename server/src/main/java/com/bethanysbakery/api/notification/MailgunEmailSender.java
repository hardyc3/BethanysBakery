package com.bethanysbakery.api.notification;

import com.bethanysbakery.api.config.NotificationProperties;
import com.bethanysbakery.api.notification.NotificationMessages.DeliveryResult;
import com.bethanysbakery.api.notification.NotificationMessages.EmailMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

@Component
@Profile("!local & !dummy")
public class MailgunEmailSender implements EmailSender {

    private final NotificationProperties properties;
    private final ObjectMapper mapper;
    private final RestTemplate rest;

    @Autowired
    public MailgunEmailSender(NotificationProperties properties, ObjectMapper mapper) {
        this(properties, mapper, ProviderHttp.timedRestTemplate());
    }

    MailgunEmailSender(NotificationProperties properties, ObjectMapper mapper, RestTemplate rest) {
        this.properties = properties;
        this.mapper = mapper;
        this.rest = rest;
    }

    @Override
    public DeliveryResult send(EmailMessage message) {
        if (!properties.mailgunConfigured()) {
            throw new ProviderNotConfiguredException("Mailgun");
        }
        NotificationProperties.Mailgun config = properties.mailgun();
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth("api", config.apiKey());
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("from", config.from());
        form.add("to", message.to());
        form.add("subject", message.subject());
        form.add("text", message.textBody());
        form.add("html", message.htmlBody());

        try {
            URI uri = URI.create(config.apiBase() + "/" + config.domain() + "/messages");
            ResponseEntity<String> response = rest.exchange(uri, HttpMethod.POST,
                new HttpEntity<>(form, headers), String.class);
            JsonNode body = mapper.readTree(response.getBody() == null ? "{}" : response.getBody());
            String id = body.path("id").asText(null);
            return DeliveryResult.sent(id == null ? "mailgun-" + UUID.randomUUID() : id);
        } catch (HttpStatusCodeException ex) {
            return DeliveryResult.failed("Mailgun HTTP " + ex.getStatusCode().value());
        } catch (Exception ex) {
            return DeliveryResult.failed("Mailgun " + ex.getClass().getSimpleName());
        }
    }
}

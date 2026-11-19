package com.bethanysbakery.api.notification;

import com.bethanysbakery.api.config.NotificationProperties;
import com.bethanysbakery.api.notification.NotificationMessages.DeliveryResult;
import com.bethanysbakery.api.notification.NotificationMessages.PushMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

/** FCM HTTP v1. Service-account JWT is minted locally; no client setup in this phase. */
@Component
@Profile("!local")
public class FcmPushSender implements PushSender {

    private static final String TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

    private final NotificationProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final RestTemplate rest;

    @Autowired
    public FcmPushSender(NotificationProperties properties, ObjectMapper mapper, Clock clock) {
        this(properties, mapper, clock, ProviderHttp.timedRestTemplate());
    }

    FcmPushSender(NotificationProperties properties, ObjectMapper mapper, Clock clock, RestTemplate rest) {
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
        this.rest = rest;
    }

    @Override
    public DeliveryResult send(PushMessage message) {
        if (!properties.fcmConfigured()) {
            throw new ProviderNotConfiguredException("FCM");
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(accessToken());
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> fcmMessage = new LinkedHashMap<>();
            fcmMessage.put("token", message.token());
            fcmMessage.put("notification", Map.of("title", message.title(), "body", message.body()));
            if (!message.data().isEmpty()) {
                fcmMessage.put("data", message.data());
            }
            Map<String, Object> body = Map.of("message", fcmMessage);
            URI uri = URI.create("https://fcm.googleapis.com/v1/projects/" + properties.fcm().projectId()
                + "/messages:send");
            ResponseEntity<String> response = rest.exchange(uri, HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
            JsonNode node = mapper.readTree(response.getBody() == null ? "{}" : response.getBody());
            return DeliveryResult.sent(node.path("name").asText("fcm-" + UUID.randomUUID()));
        } catch (HttpStatusCodeException ex) {
            String body = ex.getResponseBodyAsString();
            if (invalidToken(body)) {
                return DeliveryResult.failed("FCM_INVALID_TOKEN");
            }
            return DeliveryResult.failed("FCM HTTP " + ex.getStatusCode().value());
        } catch (Exception ex) {
            return DeliveryResult.failed("FCM " + ex.getClass().getSimpleName());
        }
    }

    /** Callers clear the device token when this is true. */
    public static boolean invalidToken(String responseBody) {
        return responseBody != null
            && (responseBody.contains("UNREGISTERED") || responseBody.contains("INVALID_ARGUMENT"));
    }

    private String accessToken() throws Exception {
        JsonNode account = mapper.readTree(resolveServiceAccount(properties.fcm().serviceAccountJson()));
        Instant now = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
            .issuer(account.path("client_email").asText())
            .audience(TOKEN_URI)
            .claim("scope", SCOPE)
            .issueTime(Date.from(now))
            .expirationTime(Date.from(now.plusSeconds(3600)))
            .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner(privateKey(account.path("private_key").asText())));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
        form.add("assertion", jwt.serialize());
        ResponseEntity<String> response = rest.exchange(URI.create(TOKEN_URI), HttpMethod.POST,
            new HttpEntity<>(form, headers), String.class);
        return mapper.readTree(response.getBody() == null ? "{}" : response.getBody())
            .path("access_token").asText();
    }

    private String resolveServiceAccount(String value) throws IOException {
        if (value != null && value.trim().startsWith("{")) {
            return value;
        }
        return Files.readString(Path.of(value));
    }

    private PrivateKey privateKey(String pem) throws Exception {
        String cleaned = pem.replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA")
            .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(cleaned)));
    }
}

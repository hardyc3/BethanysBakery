package com.bethanysbakery.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bethanysbakery.api.auth.device.DevicePairingService;
import com.bethanysbakery.api.support.CsrTestSupport;
import com.bethanysbakery.api.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class DevicePairingIntegrationTest extends IntegrationTestBase {

    private static final String PASSWORD = "correct horse battery staple";

    @Autowired
    DevicePairingService pairingService;

    @Test
    void pairingIssuesCertificateAndApiKeyOnlyOnce() throws Exception {
        SeededAdmin admin = seedAdmin("pair@example.com", PASSWORD, List.of());
        String pairingCode = pairingService.createPairingCode("test");
        CsrTestSupport.Material material = CsrTestSupport.ecP256("Pixel 8");

        MvcResult result = pair("10.3.0.1", pairingCode, "Pixel 8", material);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID deviceId = UUID.fromString(body.get("deviceId").asText());
        String apiKey = body.get("apiKey").asText();
        assertThat(apiKey).isNotBlank();
        assertThat(body.get("clientCertificatePem").asText()).contains("BEGIN CERTIFICATE");
        assertThat(body.get("caCertificatePem").asText()).contains("BEGIN CERTIFICATE");
        assertThat(Instant.parse(body.get("certificateExpiresAt").asText())).isAfter(Instant.now());

        pairExpecting(401, "10.3.0.2", pairingCode, "Pixel 8", material);

        MvcResult login = mvc.perform(post("/admin/v1/auth/login")
                .header("X-Device-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "email", "pair@example.com",
                    "password", PASSWORD,
                    "totpCode", currentTotpCode(admin.totpSecret())))))
            .andExpect(status().isOk())
            .andReturn();
        String accessToken = objectMapper.readTree(login.getResponse().getContentAsString())
            .get("accessToken").asText();

        mvc.perform(get("/admin/v1/devices")
                .header("Authorization", "Bearer " + accessToken)
                .header("X-Device-Key", apiKey))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(deviceId.toString()))
            .andExpect(jsonPath("$[0].status").value("ACTIVE"));

        assertThat(jdbc.queryForObject(
            "select count(*) from audit_event where action = 'ADMIN_DEVICE_PAIRED'", Integer.class))
            .isEqualTo(1);
    }

    @Test
    void revokedDeviceFailsImmediatelyAndLosesRefreshTokens() throws Exception {
        SeededAdmin admin = seedAdmin("revoke@example.com", PASSWORD, List.of());
        String pairingCode = pairingService.createPairingCode("test");
        CsrTestSupport.Material material = CsrTestSupport.ecP256("Pixel Revoke");
        JsonNode paired = objectMapper.readTree(pair("10.3.1.1", pairingCode, "Pixel Revoke", material)
            .getResponse().getContentAsString());
        UUID deviceId = UUID.fromString(paired.get("deviceId").asText());
        String apiKey = paired.get("apiKey").asText();

        MvcResult login = mvc.perform(post("/admin/v1/auth/login")
                .header("X-Device-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "email", "revoke@example.com",
                    "password", PASSWORD,
                    "totpCode", currentTotpCode(admin.totpSecret())))))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode session = objectMapper.readTree(login.getResponse().getContentAsString());
        String accessToken = session.get("accessToken").asText();
        String refreshToken = session.get("refreshToken").asText();

        mvc.perform(delete("/admin/v1/devices/{deviceId}", deviceId)
                .header("Authorization", "Bearer " + accessToken)
                .header("X-Device-Key", apiKey))
            .andExpect(status().isNoContent());

        mvc.perform(get("/admin/v1/me")
                .header("Authorization", "Bearer " + accessToken)
                .header("X-Device-Key", apiKey))
            .andExpect(status().isUnauthorized());

        mvc.perform(post("/admin/v1/auth/refresh")
                .header("X-Device-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
            .andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForObject(
            "select count(*) from refresh_token where device_id = ? and revoked_at is null",
            Integer.class, deviceId)).isZero();
        assertThat(jdbc.queryForObject(
            "select count(*) from audit_event where action = 'ADMIN_DEVICE_REVOKED'", Integer.class))
            .isEqualTo(1);
    }

    @Test
    void rejectsExpiredPairingCode() throws Exception {
        seedAdmin("expired-pair@example.com", PASSWORD, List.of());
        String pairingCode = pairingService.createPairingCode("test");
        jdbc.update("update device_pairing set expires_at = now() - interval '1 minute'");

        pairExpecting(401, "10.3.2.1", pairingCode, "Pixel", CsrTestSupport.ecP256("Pixel"));
    }

    @Test
    void rejectsInvalidCsrAndMismatchedPublicKey() throws Exception {
        seedAdmin("csr@example.com", PASSWORD, List.of());
        String code = pairingService.createPairingCode("test");
        pairExpecting(400, "10.3.3.1", code, "RSA device", CsrTestSupport.rsa("RSA device"));

        String code2 = pairingService.createPairingCode("test");
        CsrTestSupport.Material first = CsrTestSupport.ecP256("First");
        CsrTestSupport.Material second = CsrTestSupport.ecP256("Second");
        var request = post("/admin/v1/devices/pair")
            .header("CF-Connecting-IP", "10.3.3.2")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "pairingCode", code2,
                "deviceName", "Mismatch",
                "publicKeyPem", second.publicKeyPem(),
                "csrPem", first.csrPem())));
        mvc.perform(request)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private MvcResult pair(String ip, String code, String deviceName, CsrTestSupport.Material material)
            throws Exception {
        return mvc.perform(post("/admin/v1/devices/pair")
                .header("CF-Connecting-IP", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "pairingCode", code,
                    "deviceName", deviceName,
                    "publicKeyPem", material.publicKeyPem(),
                    "csrPem", material.csrPem(),
                    "appVersion", "1.2.3"))))
            .andReturn();
    }

    private void pairExpecting(int status, String ip, String code, String deviceName,
            CsrTestSupport.Material material) throws Exception {
        mvc.perform(post("/admin/v1/devices/pair")
                .header("CF-Connecting-IP", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "pairingCode", code,
                    "deviceName", deviceName,
                    "publicKeyPem", material.publicKeyPem(),
                    "csrPem", material.csrPem()))))
            .andExpect(result -> assertThat(result.getResponse().getStatus()).isEqualTo(status));
    }
}

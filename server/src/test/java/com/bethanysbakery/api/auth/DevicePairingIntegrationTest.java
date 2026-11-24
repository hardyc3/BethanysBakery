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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

        // One active device is now the invariant: a second pairing is rejected before code reuse is checked.
        pairExpecting(409, "10.3.0.2", pairingCode, "Pixel 8", material);

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
    void secondPairingWhileOneActiveDeviceExistsIsConflictAndRevocationAllowsPairing() throws Exception {
        SeededAdmin admin = seedAdmin("single-device@example.com", PASSWORD, List.of());
        JsonNode first = objectMapper.readTree(pair("10.4.0.1", pairingService.createPairingCode("test"),
            "First", CsrTestSupport.ecP256("First")).getResponse().getContentAsString());
        String apiKey = first.get("apiKey").asText();
        UUID firstDeviceId = UUID.fromString(first.get("deviceId").asText());

        MvcResult conflict = pair("10.4.0.2", pairingService.createPairingCode("test"),
            "Second", CsrTestSupport.ecP256("Second"));
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(objectMapper.readTree(conflict.getResponse().getContentAsString()).get("code").asText())
            .isEqualTo("INVALID_STATE");

        MvcResult login = mvc.perform(post("/admin/v1/auth/login")
                .header("X-Device-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "email", "single-device@example.com",
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
            .andExpect(jsonPath("$.length()").value(1));

        mvc.perform(delete("/admin/v1/devices/{deviceId}", firstDeviceId)
                .header("Authorization", "Bearer " + accessToken)
                .header("X-Device-Key", apiKey))
            .andExpect(status().isNoContent());

        MvcResult afterRevokeResult = pair("10.4.0.3", pairingService.createPairingCode("test"),
            "After revoke", CsrTestSupport.ecP256("After revoke"));
        assertThat(afterRevokeResult.getResponse().getStatus()).isEqualTo(201);
        JsonNode afterRevoke = objectMapper.readTree(afterRevokeResult.getResponse().getContentAsString());
        String newApiKey = afterRevoke.get("apiKey").asText();
        MvcResult oldKeyRejected = mvc.perform(get("/admin/v1/devices")
                .header("X-Device-Key", apiKey))
            .andExpect(status().isUnauthorized())
            .andReturn();
        assertThat(oldKeyRejected.getResponse().getContentAsString()).contains("UNAUTHORIZED");

        MvcResult newLogin = mvc.perform(post("/admin/v1/auth/login")
                .header("X-Device-Key", newApiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "email", "single-device@example.com",
                    "password", PASSWORD,
                    "totpCode", currentTotpCode(admin.totpSecret())))))
            .andExpect(status().isOk())
            .andReturn();
        String newAccessToken = objectMapper.readTree(newLogin.getResponse().getContentAsString())
            .get("accessToken").asText();
        JsonNode devices = objectMapper.readTree(mvc.perform(get("/admin/v1/devices")
                .header("Authorization", "Bearer " + newAccessToken)
                .header("X-Device-Key", newApiKey))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(devices).hasSize(2);
        java.util.Map<String, String> statusById = new java.util.HashMap<>();
        for (JsonNode device : devices) {
            statusById.put(device.get("id").asText(), device.get("status").asText());
        }
        assertThat(statusById)
            .containsEntry(firstDeviceId.toString(), "REVOKED")
            .containsEntry(afterRevoke.get("deviceId").asText(), "ACTIVE");
        assertThat(jdbc.queryForObject("select count(*) from admin_device where status = 'ACTIVE'",
            Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentPairingWithOneActiveDeviceHasExactlyOneWinner() throws Exception {
        SeededAdmin admin = seedAdmin("concurrent-pair@example.com", PASSWORD, List.of());
        String firstCode = pairingService.createPairingCode("test");
        String secondCode = pairingService.createPairingCode("test");
        CsrTestSupport.Material firstMaterial = CsrTestSupport.ecP256("Concurrent One");
        CsrTestSupport.Material secondMaterial = CsrTestSupport.ecP256("Concurrent Two");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<MvcResult> first = pool.submit(() -> {
                start.await();
                return pair("10.4.1.1", firstCode, "Concurrent One", firstMaterial);
            });
            Future<MvcResult> second = pool.submit(() -> {
                start.await();
                return pair("10.4.1.2", secondCode, "Concurrent Two", secondMaterial);
            });
            start.countDown();
            MvcResult firstResult = first.get(30, TimeUnit.SECONDS);
            MvcResult secondResult = second.get(30, TimeUnit.SECONDS);
            List<Integer> statuses = List.of(firstResult.getResponse().getStatus(),
                secondResult.getResponse().getStatus());
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
            MvcResult winner = firstResult.getResponse().getStatus() == 201 ? firstResult : secondResult;
            JsonNode paired = objectMapper.readTree(winner.getResponse().getContentAsString());
            if (secondResult.getResponse().getStatus() == 409) {
                assertThat(secondResult.getResponse().getContentAsString()).contains("INVALID_STATE");
            } else {
                assertThat(firstResult.getResponse().getContentAsString()).contains("INVALID_STATE");
            }

            MvcResult login = mvc.perform(post("/admin/v1/auth/login")
                    .header("X-Device-Key", paired.get("apiKey").asText())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of(
                        "email", "concurrent-pair@example.com",
                        "password", PASSWORD,
                        "totpCode", currentTotpCode(admin.totpSecret())))))
                .andExpect(status().isOk())
                .andReturn();
            String accessToken = objectMapper.readTree(login.getResponse().getContentAsString())
                .get("accessToken").asText();
            mvc.perform(get("/admin/v1/devices")
                    .header("Authorization", "Bearer " + accessToken)
                    .header("X-Device-Key", paired.get("apiKey").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(paired.get("deviceId").asText()));
        } finally {
            pool.shutdownNow();
        }
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

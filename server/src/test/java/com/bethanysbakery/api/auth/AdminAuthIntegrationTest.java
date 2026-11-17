package com.bethanysbakery.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bethanysbakery.api.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class AdminAuthIntegrationTest extends IntegrationTestBase {

    private static final String PASSWORD = "correct horse battery staple";

    @Test
    void loginWithTotpReturnsSessionAndRecordsAudit() throws Exception {
        SeededAdmin admin = seedAdmin("admin@example.com", PASSWORD, List.of());
        SeedDevice device = seedDevice(admin.id(), "device-key-1", "Pixel");

        MvcResult login = login("admin@example.com", PASSWORD, currentTotpCode(admin.totpSecret()), null,
            device.apiKey());
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(login.getResponse().getContentAsString());
        assertThat(body.get("accessToken").asText()).isNotBlank();
        assertThat(body.get("refreshToken").asText()).isNotBlank();
        assertThat(body.get("expiresIn").asLong()).isEqualTo(900);
        assertThat(body.at("/admin/email").asText()).isEqualTo("admin@example.com");

        mvc.perform(get("/admin/v1/me")
                .header("Authorization", "Bearer " + body.get("accessToken").asText())
                .header("X-Device-Key", device.apiKey()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("admin@example.com"));

        assertThat(jdbc.queryForObject(
            "select count(*) from audit_event where action = 'ADMIN_LOGIN_SUCCESS'", Integer.class))
            .isEqualTo(1);
    }

    @Test
    void loginRequiresKnownDeviceKey() throws Exception {
        SeededAdmin admin = seedAdmin("admin2@example.com", PASSWORD, List.of());
        seedDevice(admin.id(), "device-key-2", "Pixel");
        String code = currentTotpCode(admin.totpSecret());

        loginExpecting(401, "admin2@example.com", PASSWORD, code, null, null);

        mvc.perform(post("/admin/v1/auth/login")
                .header("X-Device-Key", "unknown-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("admin2@example.com", PASSWORD, code, null)))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void fiveFailedLoginsLockTheAccount() throws Exception {
        SeededAdmin admin = seedAdmin("lock@example.com", PASSWORD, List.of());
        SeedDevice device = seedDevice(admin.id(), "device-key-3", "Pixel");
        String code = currentTotpCode(admin.totpSecret());

        for (int attempt = 0; attempt < 5; attempt++) {
            loginExpecting(401, "lock@example.com", "wrong-password", code, null, device.apiKey());
        }

        assertThat(admins.findById(admin.id()).orElseThrow().getLockedUntil()).isAfter(Instant.now());
        loginExpecting(401, "lock@example.com", PASSWORD, code, null, device.apiKey());
        assertThat(jdbc.queryForObject(
            "select count(*) from audit_event where action = 'ADMIN_LOGIN_FAILED'", Integer.class))
            .isGreaterThanOrEqualTo(6);
    }

    @Test
    void recoveryCodeWorksOnce() throws Exception {
        SeededAdmin admin = seedAdmin("recovery@example.com", PASSWORD, List.of("ABCDE-FGHIJ"));
        SeedDevice device = seedDevice(admin.id(), "device-key-4", "Pixel");

        loginExpecting(200, "recovery@example.com", PASSWORD, null, "ABCDE-FGHIJ", device.apiKey());
        loginExpecting(401, "recovery@example.com", PASSWORD, null, "ABCDE-FGHIJ", device.apiKey());
    }

    @Test
    void wrongTotpIsRejected() throws Exception {
        SeededAdmin admin = seedAdmin("totp@example.com", PASSWORD, List.of());
        SeedDevice device = seedDevice(admin.id(), "device-key-5", "Pixel");
        String valid = currentTotpCode(admin.totpSecret());
        String wrong = "000000".equals(valid) ? "123456" : "000000";

        loginExpecting(401, "totp@example.com", PASSWORD, wrong, null, device.apiKey());
    }

    @Test
    void appInfoIsAvailableBeforeLogin() throws Exception {
        SeededAdmin admin = seedAdmin("app@example.com", PASSWORD, List.of());
        SeedDevice device = seedDevice(admin.id(), "device-key-6", "Pixel");

        mvc.perform(get("/admin/v1/app-info").header("X-Device-Key", device.apiKey()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.latestVersion").value("1.0.0"))
            .andExpect(jsonPath("$.downloadUrl").value("https://example.com/admin-app.apk"));
    }

    @Test
    void refreshRotatesAndReuseRevokesFamily() throws Exception {
        SeededAdmin admin = seedAdmin("refresh-admin@example.com", PASSWORD, List.of());
        SeedDevice device = seedDevice(admin.id(), "device-key-7", "Pixel");
        MvcResult login = login("refresh-admin@example.com", PASSWORD, currentTotpCode(admin.totpSecret()), null,
            device.apiKey());
        String refreshToken = objectMapper.readTree(login.getResponse().getContentAsString())
            .get("refreshToken").asText();

        MvcResult rotated = mvc.perform(post("/admin/v1/auth/refresh")
                .header("X-Device-Key", device.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
            .andExpect(status().isOk())
            .andReturn();
        String next = objectMapper.readTree(rotated.getResponse().getContentAsString())
            .get("refreshToken").asText();
        assertThat(next).isNotEqualTo(refreshToken);

        mvc.perform(post("/admin/v1/auth/refresh")
                .header("X-Device-Key", device.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))))
            .andExpect(status().isUnauthorized());

        mvc.perform(post("/admin/v1/auth/refresh")
                .header("X-Device-Key", device.apiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", next))))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void auditEventsAreRedactedAndListable() throws Exception {
        SeededAdmin admin = seedAdmin("audit@example.com", PASSWORD, List.of());
        SeedDevice device = seedDevice(admin.id(), "device-key-8", "Pixel");
        MvcResult login = login("audit@example.com", PASSWORD, currentTotpCode(admin.totpSecret()), null,
            device.apiKey());
        String accessToken = objectMapper.readTree(login.getResponse().getContentAsString())
            .get("accessToken").asText();

        mvc.perform(get("/admin/v1/audit-events")
                .param("entityType", "ADMIN_USER")
                .param("entityId", admin.id().toString())
                .header("Authorization", "Bearer " + accessToken)
                .header("X-Device-Key", device.apiKey()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].action").value("ADMIN_LOGIN_SUCCESS"))
            .andExpect(jsonPath("$[0].actorType").value("ADMIN"));

        String stored = jdbc.queryForObject(
            "select after_json::text from audit_event where action = 'ADMIN_LOGIN_SUCCESS'", String.class);
        assertThat(stored).doesNotContain(PASSWORD);
    }

    // -- helpers ----------------------------------------------------------

    private MvcResult login(String email, String password, String totpCode, String recoveryCode, String deviceKey)
            throws Exception {
        return mvc.perform(post("/admin/v1/auth/login")
                .header("X-Device-Key", deviceKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody(email, password, totpCode, recoveryCode)))
            .andExpect(status().isOk())
            .andReturn();
    }

    private void loginExpecting(int expectedStatus, String email, String password, String totpCode,
            String recoveryCode, String deviceKey) throws Exception {
        var request = post("/admin/v1/auth/login")
            .contentType(MediaType.APPLICATION_JSON)
            .content(loginBody(email, password, totpCode, recoveryCode));
        if (deviceKey != null) {
            request.header("X-Device-Key", deviceKey);
        }
        mvc.perform(request).andExpect(status().is(expectedStatus));
    }

    private String loginBody(String email, String password, String totpCode, String recoveryCode) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("email", email);
        body.put("password", password);
        if (totpCode != null) body.put("totpCode", totpCode);
        if (recoveryCode != null) body.put("recoveryCode", recoveryCode);
        return objectMapper.writeValueAsString(body);
    }
}

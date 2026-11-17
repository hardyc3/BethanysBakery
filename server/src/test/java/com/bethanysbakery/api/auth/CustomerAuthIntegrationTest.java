package com.bethanysbakery.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bethanysbakery.api.auth.session.SessionService;
import com.bethanysbakery.api.common.Uuids;
import com.bethanysbakery.api.domain.auth.Customer;
import com.bethanysbakery.api.domain.auth.CustomerStatus;
import com.bethanysbakery.api.domain.auth.SubjectType;
import com.bethanysbakery.api.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class CustomerAuthIntegrationTest extends IntegrationTestBase {

    @Autowired
    SessionService sessions;

    @Test
    void otpCreatesCustomerAndSetsSessionCookies() throws Exception {
        JsonNode requested = requestOtp("EMAIL", "new@example.com", "10.1.0.1");

        assertThat(requested.get("channel").asText()).isEqualTo("EMAIL");
        assertThat(requested.get("destinationMasked").asText()).isEqualTo("n***@example.com");
        assertThat(requested.get("resendAfterSeconds").asInt()).isEqualTo(60);
        UUID challengeId = UUID.fromString(requested.get("challengeId").asText());
        String code = otpSender.lastCode("new@example.com");
        assertThat(code).matches("^[0-9]{6}$");

        MvcResult verified = verify(challengeId, code);
        assertThat(verified.getResponse().getStatus()).isEqualTo(200);
        JsonNode customer = objectMapper.readTree(verified.getResponse().getContentAsString()).get("customer");
        assertThat(customer.get("email").asText()).isEqualTo("new@example.com");
        assertThat(customer.get("emailVerified").asBoolean()).isTrue();
        assertThat(customer.get("phoneVerified").asBoolean()).isFalse();
        assertThat(verified.getResponse().getHeaderValues("Set-Cookie")).hasSize(2);

        Cookie access = cookieValue(verified, "bb_access");
        mvc.perform(get("/api/v1/me").cookie(access))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("new@example.com"));

        assertThat(customers.count()).isEqualTo(1);

        Map<String, Object> stored = jdbc.queryForMap(
            "select code_hash, destination_encrypted from otp_challenge where id = ?", challengeId);
        assertThat(stored.get("code_hash")).isEqualTo(crypto.hmacSha256Hex(code));
        assertThat(stored.get("code_hash")).isNotEqualTo(code);
        assertThat(stored.get("destination_encrypted")).isNotEqualTo("new@example.com");
        assertThat(crypto.decrypt((String) stored.get("destination_encrypted"))).isEqualTo("new@example.com");
    }

    @Test
    void expiredOtpFailsWithoutSession() throws Exception {
        JsonNode requested = requestOtp("EMAIL", "expired@example.com", "10.1.0.2");
        UUID challengeId = UUID.fromString(requested.get("challengeId").asText());
        String code = otpSender.lastCode("expired@example.com");
        jdbc.update("update otp_challenge set expires_at = now() - interval '1 minute' where id = ?", challengeId);

        verifyExpecting(challengeId, code, 400, "OTP_EXPIRED")
            .andExpect(cookie().doesNotExist("bb_access"));

        assertThat(customers.count()).isZero();
    }

    @Test
    void otpIsSingleUse() throws Exception {
        Session session = login("single@example.com", "10.1.0.3");

        verifyExpecting(session.challengeId(), session.code(), 400, "OTP_INVALID")
            .andExpect(cookie().doesNotExist("bb_access"));

        assertThat(customers.count()).isEqualTo(1);
    }

    @Test
    void fiveWrongAttemptsLockChallenge() throws Exception {
        JsonNode requested = requestOtp("EMAIL", "locked@example.com", "10.1.0.4");
        UUID challengeId = UUID.fromString(requested.get("challengeId").asText());
        String correct = otpSender.lastCode("locked@example.com");
        String wrong = "000000".equals(correct) ? "123456" : "000000";

        for (int attempt = 0; attempt < 5; attempt++) {
            verifyExpecting(challengeId, wrong, 400, "OTP_INVALID");
        }
        verifyExpecting(challengeId, correct, 400, "OTP_LOCKED");

        Map<String, Object> stored = jdbc.queryForMap(
            "select attempts, consumed_at from otp_challenge where id = ?", challengeId);
        assertThat(stored.get("attempts")).isEqualTo(5);
        assertThat(stored.get("consumed_at")).isNull();
    }

    @Test
    void loginSmsNormalizesPhoneAndCreatesCustomer() throws Exception {
        JsonNode requested = requestOtp("SMS", "(555) 555-0103", "10.1.2.1");
        assertThat(requested.get("channel").asText()).isEqualTo("SMS");
        String code = otpSender.lastCode("+15555550103");
        UUID challengeId = UUID.fromString(requested.get("challengeId").asText());

        MvcResult verified = verify(challengeId, code);
        assertThat(verified.getResponse().getStatus()).isEqualTo(200);
        JsonNode customer = objectMapper.readTree(verified.getResponse().getContentAsString()).get("customer");
        assertThat(customer.get("phone").asText()).isEqualTo("+15555550103");
        assertThat(customer.get("phoneVerified").asBoolean()).isTrue();
    }

    @Test
    void disabledCustomerDoesNotLeakAndCannotLogin() throws Exception {
        Customer disabled = new Customer(Uuids.v7(), "Disabled", "disabled@example.com", null,
            CustomerStatus.DISABLED, Instant.now());
        customers.save(disabled);

        JsonNode requested = requestOtp("EMAIL", "disabled@example.com", "10.1.0.5");
        assertThat(otpSender.lastCode("disabled@example.com")).isNull();

        UUID challengeId = UUID.fromString(requested.get("challengeId").asText());
        jdbc.update("update otp_challenge set code_hash = ? where id = ?",
            crypto.hmacSha256Hex("123456"), challengeId);

        verifyExpecting(challengeId, "123456", 401, "UNAUTHORIZED")
            .andExpect(cookie().doesNotExist("bb_access"));
        assertThat(customers.count()).isEqualTo(1);
    }

    @Test
    void refreshRotatesAndReuseRevokesFamily() throws Exception {
        Session session = login("refresh@example.com", "10.1.0.6");

        MvcResult first = mvc.perform(post("/api/v1/auth/refresh").cookie(session.refresh()))
            .andExpect(status().isOk())
            .andReturn();
        Cookie rotatedRefresh = cookieValue(first, "bb_refresh");
        assertThat(rotatedRefresh.getValue()).isNotEqualTo(session.refresh().getValue());

        mvc.perform(post("/api/v1/auth/refresh").cookie(session.refresh()))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mvc.perform(post("/api/v1/auth/refresh").cookie(rotatedRefresh))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredRefreshTokenIsRejected() throws Exception {
        Session session = login("expired-refresh@example.com", "10.1.3.1");
        jdbc.update("update refresh_token set expires_at = now() - interval '1 minute' where token_hash = ?",
            crypto.sha256Hex(session.refresh().getValue()));

        mvc.perform(post("/api/v1/auth/refresh").cookie(session.refresh()))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void csrfTokenIsRequiredForCustomerMutations() throws Exception {
        Session session = login("csrf@example.com", "10.1.0.7");
        String body = objectMapper.writeValueAsString(Map.of("name", "Renamed"));

        mvc.perform(patch("/api/v1/me")
                .cookie(session.access())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("CSRF_INVALID"));

        CsrfToken csrf = csrf(session.access());
        mvc.perform(patch("/api/v1/me")
                .cookie(session.access(), csrf.cookie())
                .header("X-CSRF-Token", csrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Renamed"));

        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void contactVerificationUpdatesContactAndRevokesOtherSessions() throws Exception {
        Session session = login("change@example.com", "10.1.0.8");
        UUID customerId = UUID.fromString(session.body().at("/customer/id").asText());
        SessionService.SessionTokens other = sessions.create(
            SubjectType.CUSTOMER, customerId, null, "test-agent", "10.1.0.9");
        Cookie otherAccess = new Cookie("bb_access", other.accessToken());
        Cookie otherRefresh = new Cookie("bb_refresh", other.refreshToken());
        CsrfToken csrf = csrf(otherAccess);

        requestContactVerification(otherAccess, csrf, "EMAIL", "new-contact@example.com")
            .andExpect(status().isAccepted());
        String code = otpSender.lastCode("new-contact@example.com");
        UUID challengeId = latestContactChallengeId();

        mvc.perform(post("/api/v1/me/contact-verification/confirm")
                .cookie(otherAccess, csrf.cookie())
                .header("X-CSRF-Token", csrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("challengeId", challengeId, "code", code))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value("new-contact@example.com"))
            .andExpect(jsonPath("$.emailVerified").value(true));

        mvc.perform(post("/api/v1/auth/refresh").cookie(session.refresh()))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").cookie(otherRefresh))
            .andExpect(status().isOk());
    }

    @Test
    void contactVerificationRejectsDestinationOwnedByAnotherCustomer() throws Exception {
        Session session = login("owner-a@example.com", "10.1.0.10");
        CsrfToken csrf = csrf(session.access());
        customers.save(new Customer(Uuids.v7(), "Owner B", "owner-b@example.com", null,
            CustomerStatus.ACTIVE, Instant.now()));

        requestContactVerification(session.access(), csrf, "EMAIL", "owner-b@example.com")
            .andExpect(status().isAccepted());
        String code = otpSender.lastCode("owner-b@example.com");
        UUID challengeId = latestContactChallengeId();

        mvc.perform(post("/api/v1/me/contact-verification/confirm")
                .cookie(session.access(), csrf.cookie())
                .header("X-CSRF-Token", csrf.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("challengeId", challengeId, "code", code))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("CONTACT_IN_USE"));

        assertThat(customers.findByEmail("owner-a@example.com")).isPresent();
        assertThat(customers.findByEmail("owner-b@example.com")).isPresent();
    }

    // -- helpers ----------------------------------------------------------

    private Session login(String email, String ip) throws Exception {
        JsonNode requested = requestOtp("EMAIL", email, ip);
        UUID challengeId = UUID.fromString(requested.get("challengeId").asText());
        String code = otpSender.lastCode(email);
        assertThat(code).isNotNull();
        MvcResult verified = verify(challengeId, code);
        return new Session(
            objectMapper.readTree(verified.getResponse().getContentAsString()),
            cookieValue(verified, "bb_access"),
            cookieValue(verified, "bb_refresh"),
            challengeId,
            code
        );
    }

    private MvcResult verify(UUID challengeId, String code) throws Exception {
        return mvc.perform(post("/api/v1/auth/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("challengeId", challengeId, "code", code))))
            .andExpect(status().isOk())
            .andReturn();
    }

    private org.springframework.test.web.servlet.ResultActions verifyExpecting(UUID challengeId, String code,
            int expectedStatus, String expectedCode) throws Exception {
        return mvc.perform(post("/api/v1/auth/otp/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("challengeId", challengeId, "code", code))))
            .andExpect(status().is(expectedStatus))
            .andExpect(jsonPath("$.code").value(expectedCode));
    }

    private CsrfToken csrf(Cookie access) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/auth/csrf").cookie(access))
            .andExpect(status().isOk())
            .andReturn();
        String token = objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
        return new CsrfToken(cookieValue(result, "bb_csrf"), token);
    }

    private org.springframework.test.web.servlet.ResultActions requestContactVerification(Cookie access,
            CsrfToken csrf, String channel, String destination) throws Exception {
        return mvc.perform(post("/api/v1/me/contact-verification/request")
            .cookie(access, csrf.cookie())
            .header("X-CSRF-Token", csrf.token())
            .header("CF-Connecting-IP", "10.1.1.1")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of("channel", channel, "destination", destination))));
    }

    private UUID latestContactChallengeId() {
        return jdbc.queryForObject(
            "select id from otp_challenge where purpose = 'CONTACT_VERIFY' order by created_at desc limit 1",
            UUID.class);
    }

    private record Session(JsonNode body, Cookie access, Cookie refresh, UUID challengeId, String code) {}

    private record CsrfToken(Cookie cookie, String token) {}
}

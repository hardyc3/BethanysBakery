package com.bethanysbakery.api.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bethanysbakery.api.auth.otp.DummyOtpSender;
import com.bethanysbakery.api.auth.otp.DummyOtpCodeGenerator;
import com.bethanysbakery.api.auth.otp.OtpSender;
import com.bethanysbakery.api.notification.EmailSender;
import com.bethanysbakery.api.notification.LocalEmailSender;
import com.bethanysbakery.api.notification.LocalSmsSender;
import com.bethanysbakery.api.notification.SmsSender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Smoke coverage for the dummy image without starting an external database. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dummy")
@TestPropertySource(properties = {"management.server.port=0", "spring.main.lazy-initialization=false"})
class DummyProfileIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext context;
    @Autowired OtpSender otpSender;

    @Test
    void flywayAppliesV1ThroughV8() {
        Integer count = jdbc.queryForObject(
            "select count(*) from flyway_schema_history where success and version between '1' and '8'",
            Integer.class);
        assertThat(count).isEqualTo(8);
    }

    @Test
    void documentedFixedConstantsMatchSpec() {
        assertThat(DummyConstants.ADMIN_EMAIL).isEqualTo("admin@dummy.local");
        assertThat(DummyConstants.ADMIN_PASSWORD).isEqualTo("DummyAdmin!234");
        assertThat(DummyConstants.ADMIN_TOTP_SECRET).isEqualTo("JBSWY3DPEHPK3PXP");
        assertThat(DummyConstants.ADMIN_RECOVERY_CODE).isEqualTo("DUMMY-12345");
        assertThat(DummyConstants.DEVICE_KEY).isEqualTo("dummy-device-key");
        assertThat(DummyConstants.CUSTOMER_OTP).isEqualTo("123456");
        assertThat(DummyOtpCodeGenerator.CODE).isEqualTo("123456");
    }

    @Test
    void fixedAdminAndDeviceCanUseAdminApi() throws Exception {
        JsonNode config = json(mvc.perform(get("/api/v1/config"))
            .andExpect(status().isOk()).andReturn());
        assertThat(config.get("bakeryName").asText()).isEqualTo("Dummy Bakery");

        String token = adminLogin();
        mvc.perform(get("/admin/v1/me")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header("X-Device-Key", DummyConstants.DEVICE_KEY))
            .andExpect(status().isOk());
    }

    @Test
    void customerOtpUsesFixedCode() throws Exception {
        MvcResult csrfResult = mvc.perform(get("/api/v1/auth/csrf"))
            .andExpect(status().isOk()).andReturn();
        JsonNode csrf = json(csrfResult);
        Cookie cookie = csrfResult.getResponse().getCookie("bb_csrf");
        assertThat(cookie).isNotNull();

        JsonNode request = json(mvc.perform(post("/api/v1/auth/otp/request")
                .cookie(cookie)
                .header("X-CSRF-Token", csrf.get("token").asText())
                .header("CF-Connecting-IP", "127.0.0.1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"channel\":\"EMAIL\",\"destination\":\"customer@dummy.local\"}"))
            .andExpect(status().isAccepted()).andReturn());
        String challengeId = request.get("challengeId").asText();

        JsonNode session = json(mvc.perform(post("/api/v1/auth/otp/verify")
                .cookie(cookie)
                .header("CF-Connecting-IP", "127.0.0.1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"challengeId\":\"" + challengeId + "\",\"code\":\""
                    + DummyOtpCodeGenerator.CODE + "\"}"))
            .andExpect(status().isOk()).andReturn());
        assertThat(session.get("customer").get("email").asText()).isEqualTo("customer@dummy.local");
    }

    @Test
    void documentedRecoveryCodeLogsInOnce() throws Exception {
        JsonNode session = json(mvc.perform(post("/admin/v1/auth/login")
                .header("X-Device-Key", "dummy-device-key")
                .header("CF-Connecting-IP", "127.0.0.1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"admin@dummy.local\",\"password\":\"DummyAdmin!234\","
                    + "\"recoveryCode\":\"DUMMY-12345\"}"))
            .andExpect(status().isOk()).andReturn());
        assertThat(session.get("accessToken").asText()).isNotBlank();
    }

    @Test
    void dummyProfileUsesOnlyLocalSenders() {
        assertThat(otpSender).isInstanceOf(DummyOtpSender.class);
        assertThat(context.getBeansOfType(EmailSender.class).values()).allMatch(LocalEmailSender.class::isInstance);
        assertThat(context.getBeansOfType(SmsSender.class).values()).allMatch(LocalSmsSender.class::isInstance);
    }

    private String adminLogin() throws Exception {
        long period = Math.floorDiv(Instant.now().getEpochSecond(), 30);
        String totp = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6)
            .generate(DummyConstants.ADMIN_TOTP_SECRET, period);
        JsonNode session = json(mvc.perform(post("/admin/v1/auth/login")
                .header("X-Device-Key", DummyConstants.DEVICE_KEY)
                .header("CF-Connecting-IP", "127.0.0.1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + DummyConstants.ADMIN_EMAIL + "\",\"password\":\""
                    + DummyConstants.ADMIN_PASSWORD + "\",\"totpCode\":\"" + totp + "\"}"))
            .andExpect(status().isOk()).andReturn());
        return session.get("accessToken").asText();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString());
    }
}

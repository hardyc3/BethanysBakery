package com.bethanysbakery.api.support;

import com.bethanysbakery.api.auth.crypto.CryptoService;
import com.bethanysbakery.api.auth.crypto.PasswordService;
import com.bethanysbakery.api.auth.crypto.TotpService;
import com.bethanysbakery.api.auth.otp.LocalOtpSender;
import com.bethanysbakery.api.common.Uuids;
import com.bethanysbakery.api.domain.audit.AuditEventRepository;
import com.bethanysbakery.api.domain.auth.AdminDevice;
import com.bethanysbakery.api.domain.auth.AdminDeviceRepository;
import com.bethanysbakery.api.domain.auth.AdminUser;
import com.bethanysbakery.api.domain.auth.AdminUserRepository;
import com.bethanysbakery.api.domain.auth.AdminUserStatus;
import com.bethanysbakery.api.domain.auth.CustomerRepository;
import com.bethanysbakery.api.domain.auth.DevicePlatform;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    // Singleton container: started once for the JVM and shared by every integration test class.
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected LocalOtpSender otpSender;
    @Autowired protected CryptoService crypto;
    @Autowired protected PasswordService passwords;
    @Autowired protected TotpService totp;
    @Autowired protected CustomerRepository customers;
    @Autowired protected AdminUserRepository admins;
    @Autowired protected AdminDeviceRepository devices;
    @Autowired protected AuditEventRepository auditEvents;
    @Autowired protected com.bethanysbakery.api.auth.RateLimiter rateLimiter;

    @BeforeEach
    void resetDatabase() {
        rateLimiter.clear();
        jdbc.execute("""
            TRUNCATE TABLE audit_event, refresh_token, otp_challenge, device_pairing,
                notification_delivery, notification, notification_preference,
                outbox_event, order_line_option, order_line, daily_order_sequence, orders,
                offering_option, offering_option_group, offering_window, offering, pickup_window,
                menu_date, menu,
                customization_option, customization_group, item_template, media,
                admin_device, admin_user, customer CASCADE
            """);
    }

    protected JsonNode requestOtp(String channel, String destination, String ip) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/otp/request")
                .contentType(MediaType.APPLICATION_JSON)
                .header("CF-Connecting-IP", ip)
                .content(objectMapper.writeValueAsString(Map.of("channel", channel, "destination", destination))))
            .andExpect(status().isAccepted())
            .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    protected SeededAdmin seedAdmin(String email, String password, List<String> recoveryCodes) {
        String secret = totp.generateSecret();
        List<String> hashes = recoveryCodes.stream()
            .map(code -> crypto.sha256Hex(normalizeRecoveryCode(code)))
            .toList();
        AdminUser admin = new AdminUser(
            Uuids.v7(),
            email.toLowerCase(Locale.ROOT),
            "Test Admin",
            passwords.hash(password),
            crypto.encrypt(secret),
            hashes,
            AdminUserStatus.ACTIVE,
            Instant.now()
        );
        admins.save(admin);
        return new SeededAdmin(admin.getId(), secret);
    }

    protected SeedDevice seedDevice(UUID adminUserId, String apiKey, String name) {
        AdminDevice device = new AdminDevice(
            Uuids.v7(),
            adminUserId,
            name,
            DevicePlatform.ANDROID,
            "-----BEGIN PUBLIC KEY-----\ntest\n-----END PUBLIC KEY-----",
            crypto.sha256Hex(UUID.randomUUID().toString()),
            crypto.sha256Hex(apiKey),
            Instant.now(),
            "1.0.0"
        );
        devices.save(device);
        return new SeedDevice(device.getId(), apiKey);
    }

    protected String currentTotpCode(String secret) throws Exception {
        long period = Math.floorDiv(Instant.now().getEpochSecond(), 30);
        return new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6).generate(secret, period);
    }

    protected static Cookie cookieValue(MvcResult result, String name) {
        Cookie cookie = result.getResponse().getCookie(name);
        if (cookie == null) {
            throw new AssertionError("Missing cookie " + name);
        }
        return cookie;
    }

    protected static String normalizeRecoveryCode(String code) {
        return code.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    public record SeededAdmin(UUID id, String totpSecret) {}

    public record SeedDevice(UUID id, String apiKey) {}
}

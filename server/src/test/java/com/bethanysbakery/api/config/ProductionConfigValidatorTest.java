package com.bethanysbakery.api.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

class ProductionConfigValidatorTest {

    private static final String JWT = "a-very-long-jwt-secret-value-1234567890";

    @TempDir Path dir;

    @Test
    void acceptsHardenedProductionConfig(@TempDir Path ca) throws Exception {
        Path cert = ca.resolve("ca.crt");
        Path key = ca.resolve("ca.key");
        Files.writeString(cert, "cert");
        Files.writeString(key, "key");
        MockEnvironment env = env();
        assertThatCode(() -> validator(env, props(cert, key), notifications("https://api.mailgun.net/v3",
            "https://sms.textbee.dev")).validate()).doesNotThrowAnyException();
    }

    @Test
    void rejectsWeakSecretsAndBypass(@TempDir Path ca) throws Exception {
        Path cert = ca.resolve("ca.crt");
        Path key = ca.resolve("ca.key");
        Files.writeString(cert, "cert");
        Files.writeString(key, "key");
        assertThatThrownBy(() -> validator(env(), props(cert, key, "https://public.example.com",
            "https://admin.example.com", "https://pair.example.com", true, JWT), notifications(null, null))
            .validate()).isInstanceOf(IllegalStateException.class).hasMessageContaining("ADMIN_MTLS_LOCAL_BYPASS");
        assertThatThrownBy(() -> validator(weakDbEnv(), props(cert, key), notifications(null, null))
            .validate()).isInstanceOf(IllegalStateException.class).hasMessageContaining("DB_PASSWORD");
        assertThatThrownBy(() -> validator(env(), props(cert, key, "https://public.example.com",
            "https://admin.example.com", "https://pair.example.com", false, "short"), notifications(null, null))
            .validate()).isInstanceOf(IllegalStateException.class).hasMessageContaining("JWT_SECRET");
    }

    @Test
    void rejectsInsecureUrlsMissingCaAndEnabledDocs(@TempDir Path ca) throws Exception {
        Path cert = ca.resolve("ca.crt");
        Path key = ca.resolve("ca.key");
        Files.writeString(cert, "cert");
        Files.writeString(key, "key");
        assertThatThrownBy(() -> validator(env(), props(cert, key, "http://public.example.com",
            "https://admin.example.com", "https://pair.example.com", false, JWT), notifications(null, null)).validate())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("APP_PUBLIC_BASE_URL");
        assertThatThrownBy(() -> validator(env(), props(ca.resolve("missing.crt"), key), notifications(null, null))
            .validate()).isInstanceOf(IllegalStateException.class).hasMessageContaining("ADMIN_DEVICE_CA_CERT_PATH");
        assertThatThrownBy(() -> validator(docsEnv(), props(cert, key), notifications(null, null)).validate())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("OpenAPI");
        assertThatThrownBy(() -> validator(env(), props(cert, key),
            notifications("http://insecure.example", null)).validate())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("MAILGUN_API_BASE");
        assertThatThrownBy(() -> validator(env(), props(cert, key),
            notifications(null, "http://insecure.example")).validate())
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("TEXTBEE_BASE_URL");
    }

    private static MockEnvironment env() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.profiles.active", "prod");
        env.setProperty("bakery.edge.cloudflare-proxy", "true");
        env.setProperty("spring.datasource.username", "bakery_prod");
        env.setProperty("spring.datasource.password", "a-strong-database-password");
        env.setProperty("springdoc.api-docs.enabled", "false");
        env.setProperty("springdoc.swagger-ui.enabled", "false");
        return env;
    }

    private static MockEnvironment weakDbEnv() {
        MockEnvironment env = env();
        env.setProperty("spring.datasource.password", "change-me");
        return env;
    }

    private static MockEnvironment docsEnv() {
        MockEnvironment env = env();
        env.setProperty("springdoc.swagger-ui.enabled", "true");
        return env;
    }

    private static BakeryProperties props(Path cert, Path key) {
        return props(cert, key, "https://public.example.com", "https://admin.example.com",
            "https://pair.example.com", false, "a-very-long-jwt-secret-value-1234567890");
    }

    private static BakeryProperties props(Path cert, Path key, String publicUrl, String adminUrl, String pairUrl,
            boolean bypass, String jwt) {
        return new BakeryProperties("Bakery", "America/Denver", "address", "https://maps.example.com",
            "support@example.com", 24,
            new BakeryProperties.Auth(jwt, JWT, cert.toString(), key.toString(),
                "Cf-Authenticated-Client-Cert", "Cf-Authenticated-Client-Cert-Verified", bypass,
                java.time.Duration.ofMinutes(15), java.time.Duration.ofDays(14), java.time.Duration.ofMinutes(15),
                java.time.Duration.ofDays(30), java.time.Duration.ofMinutes(10), 5, 60, 3, 5,
                java.time.Duration.ofMinutes(15), publicUrl, adminUrl, pairUrl),
            new BakeryProperties.AdminApp("2.0.0", "1.0.0", "https://downloads.bakery.test/app.apk", null),
            new BakeryProperties.Venmo(null, null));
    }

    private static NotificationProperties notifications(String mailgunBase, String textbeeBase) {
        return new NotificationProperties(false, null,
            new NotificationProperties.Mailgun(null, null, mailgunBase, null),
            new NotificationProperties.Textbee(textbeeBase, null, null, "webhook-secret"), null);
    }

    private static ProductionConfigValidator validator(MockEnvironment env, BakeryProperties props,
            NotificationProperties notifications) {
        return new ProductionConfigValidator(env, props, notifications);
    }
}

package com.bethanysbakery.api.config;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Fails startup under prod/staging when deployment security prerequisites are missing. */
@Component
@Profile("!local & !test & !dummy")
public class ProductionConfigValidator {

    private static final Set<String> WEAK_DB_PASSWORDS = Set.of("change-me", "changeme", "bakery", "password",
        "postgres", "secret", "test", "dev");
    private static final Set<String> WEAK_SECRETS = Set.of("change-me", "changeme", "secret", "test", "dev",
        "example", "password");

    private final Environment env;
    private final BakeryProperties bakery;
    private final NotificationProperties notifications;

    public ProductionConfigValidator(Environment env, BakeryProperties bakery, NotificationProperties notifications) {
        this.env = env;
        this.bakery = bakery;
        this.notifications = notifications;
    }

    @PostConstruct
    public void validate() {
        List<String> errors = new ArrayList<>();
        BakeryProperties.Auth auth = bakery.auth();
        if (auth.localBypass()) {
            errors.add("ADMIN_MTLS_LOCAL_BYPASS must be false");
        }
        if (env.getProperty("bakery.edge.cloudflare-proxy", Boolean.class, false) != Boolean.TRUE) {
            errors.add("CLOUDFLARE_PROXY must be true outside local/test");
        }
        requireStrongSecret("JWT_SECRET", auth.jwtSecret(), errors);
        requireStrongSecret("APP_ENCRYPTION_KEY", auth.appEncryptionKey(), errors);
        requireHttps("APP_PUBLIC_BASE_URL", auth.appPublicBaseUrl(), errors);
        requireHttps("APP_ADMIN_API_URL", auth.appAdminApiUrl(), errors);
        requireHttps("APP_PAIR_URL", auth.appPairUrl(), errors);
        requireFile("ADMIN_DEVICE_CA_CERT_PATH", auth.adminDeviceCaCertPath(), errors);
        requireFile("ADMIN_DEVICE_CA_KEY_PATH", auth.adminDeviceCaKeyPath(), errors);

        String dbUser = env.getProperty("spring.datasource.username", "");
        String dbPassword = env.getProperty("spring.datasource.password", "");
        if (dbUser.isBlank() || "bakery".equalsIgnoreCase(dbUser)) {
            errors.add("DB_USER must not be blank or a local/dev default");
        }
        if (dbPassword.isBlank() || WEAK_DB_PASSWORDS.contains(dbPassword.toLowerCase(Locale.ROOT))) {
            errors.add("DB_PASSWORD must not be blank or a local/dev default");
        }

        String downloadUrl = bakery.adminApp().downloadUrl();
        if (downloadUrl == null || !https(downloadUrl) || downloadUrl.contains("example.com")) {
            errors.add("ADMIN_APP_DOWNLOAD_URL must be a real HTTPS URL");
        }

        NotificationProperties.Mailgun mailgun = notifications.mailgun();
        if (mailgun != null && !mailgun.apiBase().isBlank() && !https(mailgun.apiBase())) {
            errors.add("MAILGUN_API_BASE must use HTTPS");
        }
        NotificationProperties.Textbee textbee = notifications.textbee();
        if (textbee != null && !blank(textbee.baseUrl()) && !https(textbee.baseUrl())) {
            errors.add("TEXTBEE_BASE_URL must use HTTPS");
        }

        if (flag("springdoc.api-docs.enabled") || flag("springdoc.swagger-ui.enabled")) {
            errors.add("OpenAPI UI must be disabled outside local/test");
        }
        String otpSender = env.getProperty("bakery.otp.sender", "");
        if ("local".equalsIgnoreCase(otpSender)) {
            errors.add("LocalOtpSender is only allowed under local/test profiles");
        }

        if (!errors.isEmpty()) {
            throw new IllegalStateException("Production configuration invalid: " + String.join("; ", errors));
        }
    }

    private void requireStrongSecret(String name, String value, List<String> errors) {
        if (blank(value) || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32
                || WEAK_SECRETS.contains(value.toLowerCase(Locale.ROOT))) {
            errors.add(name + " must be at least 32 bytes and not a known default");
        }
    }

    private void requireHttps(String name, String value, List<String> errors) {
        if (blank(value) || !https(value)) {
            errors.add(name + " must use HTTPS");
        }
    }

    private void requireFile(String name, String value, List<String> errors) {
        if (blank(value) || !Files.isRegularFile(Path.of(value))) {
            errors.add(name + " must reference an existing file");
        }
    }

    private boolean flag(String key) {
        return Boolean.parseBoolean(env.getProperty(key, "false"));
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean https(String value) {
        if (!value.startsWith("https://")) {
            return false;
        }
        try {
            return "https".equalsIgnoreCase(URI.create(value).getScheme());
        } catch (RuntimeException ex) {
            return false;
        }
    }
}

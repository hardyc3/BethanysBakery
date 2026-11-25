package com.bethanysbakery.api.config;

import com.bethanysbakery.api.auth.crypto.CryptoService;
import com.bethanysbakery.api.auth.crypto.PasswordService;
import com.bethanysbakery.api.common.Uuids;
import com.bethanysbakery.api.domain.auth.AdminDevice;
import com.bethanysbakery.api.domain.auth.AdminDeviceRepository;
import com.bethanysbakery.api.domain.auth.AdminUser;
import com.bethanysbakery.api.domain.auth.AdminUserRepository;
import com.bethanysbakery.api.domain.auth.AdminUserStatus;
import com.bethanysbakery.api.domain.auth.DevicePlatform;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Seeds the documented admin and device so clients can skip real pairing. */
@Component
@Profile("dummy")
public class DummySeedRunner {

    private static final Logger log = LoggerFactory.getLogger(DummySeedRunner.class);
    private static final String PUBLIC_KEY = "-----BEGIN PUBLIC KEY-----\ndummy\n-----END PUBLIC KEY-----";

    private final AdminUserRepository admins;
    private final AdminDeviceRepository devices;
    private final PasswordService passwords;
    private final CryptoService crypto;

    public DummySeedRunner(AdminUserRepository admins, AdminDeviceRepository devices,
            PasswordService passwords, CryptoService crypto) {
        this.admins = admins;
        this.devices = devices;
        this.passwords = passwords;
        this.crypto = crypto;
    }

    @PostConstruct
    public void seed() {
        Instant now = Instant.now();
        AdminUser admin = admins.findByEmail(DummyConstants.ADMIN_EMAIL).orElseGet(() -> admins.save(new AdminUser(
            Uuids.v7(),
            DummyConstants.ADMIN_EMAIL.toLowerCase(Locale.ROOT),
            "Dummy Admin",
            passwords.hash(DummyConstants.ADMIN_PASSWORD),
            crypto.encrypt(DummyConstants.ADMIN_TOTP_SECRET),
            List.of(crypto.sha256Hex(normalize(DummyConstants.ADMIN_RECOVERY_CODE))),
            AdminUserStatus.ACTIVE,
            now
        )));
        String apiKeyHash = crypto.sha256Hex(DummyConstants.DEVICE_KEY);
        if (devices.findByApiKeyHash(apiKeyHash).isEmpty()) {
            devices.save(new AdminDevice(
                Uuids.v7(),
                admin.getId(),
                "Dummy Device",
                DevicePlatform.ANDROID,
                PUBLIC_KEY,
                crypto.sha256Hex("dummy-device-certificate"),
                apiKeyHash,
                now,
                "1.0.0"
            ));
        }
        log.info("Dummy seed ready: admin and fixed device are available");
    }

    private static String normalize(String code) {
        return code.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }
}

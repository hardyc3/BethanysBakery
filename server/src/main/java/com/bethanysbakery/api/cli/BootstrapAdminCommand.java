package com.bethanysbakery.api.cli;

import com.bethanysbakery.api.auth.audit.AuditService;
import com.bethanysbakery.api.auth.crypto.CryptoService;
import com.bethanysbakery.api.auth.crypto.PasswordService;
import com.bethanysbakery.api.auth.crypto.QrCodeRenderer;
import com.bethanysbakery.api.auth.crypto.TotpService;
import com.bethanysbakery.api.common.Uuids;
import com.bethanysbakery.api.domain.audit.ActorType;
import com.bethanysbakery.api.domain.auth.AdminUser;
import com.bethanysbakery.api.domain.auth.AdminUserRepository;
import com.bethanysbakery.api.domain.auth.AdminUserStatus;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class BootstrapAdminCommand {
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AdminUserRepository admins;
    private final PasswordService passwords;
    private final TotpService totp;
    private final CryptoService crypto;
    private final QrCodeRenderer qr;
    private final AuditService audit;
    private final Function<String, String> envReader;

    @Autowired
    public BootstrapAdminCommand(AdminUserRepository admins, PasswordService passwords, TotpService totp,
            CryptoService crypto, QrCodeRenderer qr, AuditService audit) {
        this(admins, passwords, totp, crypto, qr, audit, System::getenv);
    }

    BootstrapAdminCommand(AdminUserRepository admins, PasswordService passwords, TotpService totp,
            CryptoService crypto, QrCodeRenderer qr, AuditService audit, Function<String, String> envReader) {
        this.admins = admins;
        this.passwords = passwords;
        this.totp = totp;
        this.crypto = crypto;
        this.qr = qr;
        this.audit = audit;
        this.envReader = envReader;
    }

    public int run(Map<String, String> args) {
        String email = args.get("email");
        String name = args.get("name");
        if (email == null || email.isBlank() || name == null || name.isBlank()) {
            System.err.println("Usage: --bootstrap-admin --email <email> --name <name>");
            return 2;
        }
        if (admins.existsByStatus(AdminUserStatus.ACTIVE)) {
            System.err.println("An active admin already exists.");
            return 1;
        }
        String password = envReader.apply("ADMIN_BOOTSTRAP_PASSWORD");
        if (password == null || password.isBlank()) {
            var console = System.console();
            if (console == null) {
                System.err.println("ADMIN_BOOTSTRAP_PASSWORD is required when no terminal is available.");
                return 1;
            }
            char[] first = console.readPassword("Admin password: ");
            char[] second = console.readPassword("Confirm password: ");
            if (first == null || second == null || !java.util.Arrays.equals(first, second)) {
                System.err.println("Passwords do not match.");
                return 1;
            }
            password = new String(first);
        }
        if (password.length() < 12) {
            System.err.println("Admin password must be at least 12 characters.");
            return 1;
        }

        String secret = totp.generateSecret();
        List<String> plainCodes = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String code = recoveryCode();
            plainCodes.add(code);
            hashes.add(crypto.sha256Hex(normalize(code)));
        }
        AdminUser admin = new AdminUser(
            Uuids.v7(),
            email.trim().toLowerCase(Locale.ROOT),
            name.trim(),
            passwords.hash(password),
            crypto.encrypt(secret),
            hashes,
            AdminUserStatus.ACTIVE,
            Instant.now()
        );
        admins.save(admin);
        audit.record(ActorType.SYSTEM, null, "ADMIN_BOOTSTRAPPED", "ADMIN_USER", admin.getId(), null,
            Map.of("email", admin.getEmail()));

        String uri = totp.otpauthUri("Bethany's Bakery Admin", admin.getEmail(), secret);
        System.out.println();
        System.out.println("Admin bootstrapped: " + admin.getEmail());
        System.out.println("TOTP URI: " + uri);
        System.out.println(qr.render(uri));
        System.out.println("Recovery codes (shown once):");
        for (String code : plainCodes) {
            System.out.println("  " + code);
        }
        return 0;
    }

    private static String recoveryCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            if (i == 5) sb.append('-');
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String normalize(String code) {
        return code.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }
}

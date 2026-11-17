package com.bethanysbakery.api.auth.admin;

import com.bethanysbakery.api.api.auth.AuthApi;
import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.auth.audit.AuditService;
import com.bethanysbakery.api.auth.crypto.CryptoService;
import com.bethanysbakery.api.auth.crypto.PasswordService;
import com.bethanysbakery.api.auth.crypto.TotpService;
import com.bethanysbakery.api.auth.session.SessionService;
import com.bethanysbakery.api.common.Uuids;
import com.bethanysbakery.api.config.BakeryProperties;
import com.bethanysbakery.api.domain.audit.ActorType;
import com.bethanysbakery.api.domain.auth.AdminUser;
import com.bethanysbakery.api.domain.auth.AdminUserRepository;
import com.bethanysbakery.api.domain.auth.AdminUserStatus;
import com.bethanysbakery.api.domain.auth.SubjectType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminAuthService {
    private final AdminUserRepository admins;
    private final PasswordService passwords;
    private final TotpService totp;
    private final CryptoService crypto;
    private final SessionService sessions;
    private final AuditService audit;
    private final BakeryProperties.Auth config;
    private final String dummyPasswordHash;

    public AdminAuthService(AdminUserRepository admins, PasswordService passwords, TotpService totp,
            CryptoService crypto, SessionService sessions, AuditService audit, BakeryProperties properties) {
        this.admins = admins;
        this.passwords = passwords;
        this.totp = totp;
        this.crypto = crypto;
        this.sessions = sessions;
        this.audit = audit;
        this.config = properties.auth();
        this.dummyPasswordHash = passwords.hash("not-a-real-password");
    }

    @Transactional(noRollbackFor = AuthException.class)
    public AuthApi.AdminSession login(AuthApi.AdminLoginRequest request, UUID deviceId, String userAgent, String ip) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        Optional<AdminUser> found = admins.findByEmail(email);
        if (found.isEmpty()) {
            passwords.matches(request.password(), dummyPasswordHash);
            auditFailure(null, "UNKNOWN_ACCOUNT");
            throw AuthException.unauthorized("Invalid credentials");
        }
        AdminUser admin = found.get();
        if (admin.getStatus() != AdminUserStatus.ACTIVE) {
            passwords.matches(request.password(), dummyPasswordHash);
            auditFailure(admin.getId(), "DISABLED");
            throw AuthException.unauthorized("Invalid credentials");
        }
        Instant now = Instant.now();
        if (admin.getLockedUntil() != null && admin.getLockedUntil().isAfter(now)) {
            auditFailure(admin.getId(), "LOCKED");
            throw AuthException.unauthorized("Invalid credentials");
        }

        boolean passwordOk = passwords.matches(request.password(), admin.getPasswordHash());
        boolean secondFactorOk = false;
        if (passwordOk) {
            if (request.recoveryCode() != null && !request.recoveryCode().isBlank()) {
                secondFactorOk = consumeRecoveryCode(admin, request.recoveryCode());
            } else if (request.totpCode() != null && !request.totpCode().isBlank()) {
                String secret = crypto.decrypt(admin.getTotpSecretEncrypted());
                secondFactorOk = totp.verify(secret, request.totpCode());
            }
        }
        if (!passwordOk || !secondFactorOk) {
            int failures = admin.getFailedLoginCount() + 1;
            if (failures >= config.adminMaxFailedLogins()) {
                admin.setFailedLoginCount(0);
                admin.setLockedUntil(now.plus(config.adminLockout()));
            } else {
                admin.setFailedLoginCount(failures);
            }
            admins.save(admin);
            auditFailure(admin.getId(), "INVALID_CREDENTIALS");
            throw AuthException.unauthorized("Invalid credentials");
        }

        admin.recordSuccessfulLogin(now);
        admins.save(admin);
        SessionService.SessionTokens tokens = sessions.create(
            SubjectType.ADMIN, admin.getId(), deviceId, userAgent, ip
        );
        audit.record(ActorType.ADMIN, admin.getId(), "ADMIN_LOGIN_SUCCESS", "ADMIN_USER", admin.getId(), null,
            Map.of("deviceId", deviceId.toString()));
        return toSession(tokens, admin);
    }

    @Transactional(noRollbackFor = AuthException.class)
    public AuthApi.AdminSession refresh(String rawRefreshToken, UUID deviceId, String userAgent, String ip) {
        SessionService.SessionTokens tokens = sessions.rotate(
            SubjectType.ADMIN, rawRefreshToken, deviceId, userAgent, ip
        );
        AdminUser admin = admins.findById(tokens.subjectId())
            .orElseThrow(() -> AuthException.unauthorized("Invalid refresh token"));
        if (admin.getStatus() != AdminUserStatus.ACTIVE) {
            sessions.revokeFamily(tokens.familyId());
            throw AuthException.unauthorized("Admin disabled");
        }
        return toSession(tokens, admin);
    }

    @Transactional
    public void logout(AdminPrincipal principal) {
        sessions.revokeFamily(principal.sessionId());
    }

    @Transactional(readOnly = true)
    public AuthApi.AdminProfile profile(AdminPrincipal principal) {
        AdminUser admin = admins.findById(principal.adminId())
            .orElseThrow(() -> AuthException.unauthorized("Admin not found"));
        return new AuthApi.AdminProfile(admin.getId(), admin.getEmail(), admin.getName());
    }

    private boolean consumeRecoveryCode(AdminUser admin, String recoveryCode) {
        String normalized = recoveryCode.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        String hash = crypto.sha256Hex(normalized);
        List<String> codes = new ArrayList<>(admin.getRecoveryCodesHash());
        for (int i = 0; i < codes.size(); i++) {
            if (crypto.constantTimeEquals(codes.get(i), hash)) {
                codes.remove(i);
                admin.setRecoveryCodesHash(codes);
                admins.save(admin);
                return true;
            }
        }
        return false;
    }

    private void auditFailure(UUID adminId, String reason) {
        UUID actorId = adminId;
        ActorType actorType = actorId == null ? ActorType.SYSTEM : ActorType.ADMIN;
        UUID entityId = actorId == null ? Uuids.v7() : actorId;
        audit.record(actorType, actorId, "ADMIN_LOGIN_FAILED", "ADMIN_USER", entityId, null,
            Map.of("reason", reason));
    }

    private static AuthApi.AdminSession toSession(SessionService.SessionTokens tokens, AdminUser admin) {
        return new AuthApi.AdminSession(
            tokens.accessToken(),
            tokens.refreshToken(),
            tokens.expiresIn(),
            new AuthApi.AdminProfile(admin.getId(), admin.getEmail(), admin.getName())
        );
    }
}

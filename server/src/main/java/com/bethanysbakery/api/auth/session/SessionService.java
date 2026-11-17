package com.bethanysbakery.api.auth.session;

import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.auth.crypto.CryptoService;
import com.bethanysbakery.api.common.Uuids;
import com.bethanysbakery.api.config.BakeryProperties;
import com.bethanysbakery.api.domain.auth.RefreshToken;
import com.bethanysbakery.api.domain.auth.RefreshTokenRepository;
import com.bethanysbakery.api.domain.auth.SubjectType;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SessionService {
    private final RefreshTokenRepository repository;
    private final CryptoService crypto;
    private final JwtService jwtService;
    private final BakeryProperties.Auth config;

    public SessionService(RefreshTokenRepository repository, CryptoService crypto, JwtService jwtService,
            BakeryProperties properties) {
        this.repository = repository;
        this.crypto = crypto;
        this.jwtService = jwtService;
        this.config = properties.auth();
    }

    @Transactional
    public SessionTokens create(SubjectType type, UUID subjectId, UUID deviceId, String userAgent, String ip) {
        Instant now = Instant.now();
        UUID familyId = Uuids.v7();
        String refresh = crypto.randomToken(32);
        Instant refreshExpiresAt = now.plus(refreshTtl(type));
        RefreshToken token = new RefreshToken(
            Uuids.v7(), type, subjectId, deviceId, crypto.sha256Hex(refresh), familyId, refreshExpiresAt,
            truncate(userAgent, 512), truncate(ip, 64), now
        );
        repository.save(token);
        String access = jwtService.issue(type, subjectId, familyId, deviceId, now, accessTtl(type));
        return new SessionTokens(subjectId, familyId, access, refresh, accessTtl(type).toSeconds(), refreshExpiresAt);
    }

    @Transactional(noRollbackFor = AuthException.class)
    public SessionTokens rotate(SubjectType type, String rawRefreshToken, UUID deviceId, String userAgent, String ip) {
        Instant now = Instant.now();
        RefreshToken current = repository.findByTokenHashForUpdate(crypto.sha256Hex(rawRefreshToken))
            .orElseThrow(() -> AuthException.unauthorized("Invalid refresh token"));

        if (current.getSubjectType() != type) {
            throw AuthException.unauthorized("Invalid refresh token");
        }
        if (deviceId != null && current.getDeviceId() != null && !deviceId.equals(current.getDeviceId())) {
            throw AuthException.unauthorized("Invalid refresh token");
        }
        if (current.isRevoked() || current.isExpired(now)) {
            throw AuthException.unauthorized("Invalid refresh token");
        }
        if (current.isRotated()) {
            repository.revokeFamily(current.getFamilyId(), now);
            throw AuthException.unauthorized("Refresh token reuse detected");
        }

        String nextRaw = crypto.randomToken(32);
        Instant refreshExpiresAt = now.plus(refreshTtl(type));
        RefreshToken next = new RefreshToken(
            Uuids.v7(), type, current.getSubjectId(), current.getDeviceId(), crypto.sha256Hex(nextRaw),
            current.getFamilyId(), refreshExpiresAt, truncate(userAgent, 512), truncate(ip, 64), now
        );
        repository.save(next);
        current.rotate(now, next.getId());
        repository.save(current);

        String access = jwtService.issue(type, current.getSubjectId(), current.getFamilyId(), current.getDeviceId(),
            now, accessTtl(type));
        return new SessionTokens(current.getSubjectId(), current.getFamilyId(), access, nextRaw,
            accessTtl(type).toSeconds(), refreshExpiresAt);
    }

    @Transactional
    public void revokeFamily(UUID familyId) {
        repository.revokeFamily(familyId, Instant.now());
    }

    @Transactional
    public void revokeDevice(UUID deviceId) {
        repository.revokeDevice(deviceId, Instant.now());
    }

    @Transactional
    public void revokeOtherFamilies(SubjectType type, UUID subjectId, UUID keepFamilyId) {
        repository.revokeOtherFamilies(type, subjectId, keepFamilyId, Instant.now());
    }

    private Duration accessTtl(SubjectType type) {
        return type == SubjectType.ADMIN ? config.adminAccessTtl() : config.customerAccessTtl();
    }

    private Duration refreshTtl(SubjectType type) {
        return type == SubjectType.ADMIN ? config.adminRefreshTtl() : config.customerRefreshTtl();
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    public record SessionTokens(UUID subjectId, UUID familyId, String accessToken, String refreshToken,
            long expiresIn, Instant refreshExpiresAt) {}
}

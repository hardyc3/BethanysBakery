package com.bethanysbakery.api.auth.session;

import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.config.BakeryProperties;
import com.bethanysbakery.api.domain.auth.SubjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private static final String ISSUER = "bethanys-bakery-api";

    private final byte[] secret;

    public JwtService(BakeryProperties properties) {
        try {
            this.secret = Base64.getDecoder().decode(properties.auth().jwtSecret());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("JWT_SECRET must be valid base64", ex);
        }
        if (secret.length < 32) {
            throw new IllegalStateException("JWT_SECRET must decode to at least 32 bytes");
        }
    }

    public String issue(SubjectType subjectType, UUID subjectId, UUID familyId, UUID deviceId, Instant now, Duration ttl) {
        try {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .subject(subjectId.toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(ttl)))
                .issuer(ISSUER)
                .audience(audience(subjectType))
                .claim("typ", subjectType.name())
                .claim("sid", familyId.toString());
            if (deviceId != null) {
                claims.claim("deviceId", deviceId.toString());
            }
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
            jwt.sign(new MACSigner(secret));
            return jwt.serialize();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to issue access token", ex);
        }
    }

    public AccessClaims parse(String token, SubjectType expectedType) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm())) {
                throw AuthException.unauthorized("Invalid token");
            }
            if (!jwt.verify(new MACVerifier(secret))) {
                throw AuthException.unauthorized("Invalid token");
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Instant expiresAt = claims.getExpirationTime() == null ? null : claims.getExpirationTime().toInstant();
            if (expiresAt == null || !expiresAt.isAfter(Instant.now())) {
                throw AuthException.unauthorized("Token expired");
            }
            if (!ISSUER.equals(claims.getIssuer())) {
                throw AuthException.unauthorized("Invalid token");
            }
            if (claims.getAudience() == null || !claims.getAudience().contains(audience(expectedType))) {
                throw AuthException.unauthorized("Invalid token");
            }
            String typ = claims.getStringClaim("typ");
            if (!expectedType.name().equals(typ)) {
                throw AuthException.unauthorized("Invalid token");
            }
            String subject = claims.getSubject();
            String sid = claims.getStringClaim("sid");
            if (subject == null || sid == null) {
                throw AuthException.unauthorized("Invalid token");
            }
            String deviceId = claims.getStringClaim("deviceId");
            return new AccessClaims(
                UUID.fromString(subject),
                expectedType,
                UUID.fromString(sid),
                deviceId == null ? null : UUID.fromString(deviceId),
                expiresAt
            );
        } catch (AuthException ex) {
            throw ex;
        } catch (Exception ex) {
            throw AuthException.unauthorized("Invalid token");
        }
    }

    private static String audience(SubjectType type) {
        return type == SubjectType.ADMIN ? "admin" : "customer";
    }

    public record AccessClaims(UUID subjectId, SubjectType type, UUID sessionId, UUID deviceId, Instant expiresAt) {}
}

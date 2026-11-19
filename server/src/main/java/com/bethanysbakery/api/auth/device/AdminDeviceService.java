package com.bethanysbakery.api.auth.device;

import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.auth.audit.AuditService;
import com.bethanysbakery.api.auth.crypto.CryptoService;
import com.bethanysbakery.api.auth.session.SessionService;
import com.bethanysbakery.api.config.BakeryProperties;
import com.bethanysbakery.api.domain.audit.ActorType;
import com.bethanysbakery.api.domain.auth.AdminDevice;
import com.bethanysbakery.api.domain.auth.AdminDeviceRepository;
import com.bethanysbakery.api.domain.auth.AdminDeviceStatus;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminDeviceService {
    private final AdminDeviceRepository devices;
    private final CryptoService crypto;
    private final SessionService sessions;
    private final AuditService audit;
    private final BakeryProperties.Auth config;
    private final Environment env;

    public AdminDeviceService(AdminDeviceRepository devices, CryptoService crypto, SessionService sessions,
            AuditService audit, BakeryProperties properties, Environment env) {
        this.devices = devices;
        this.crypto = crypto;
        this.sessions = sessions;
        this.audit = audit;
        this.config = properties.auth();
        this.env = env;
    }

    @Transactional
    public AdminDevice authenticate(HttpServletRequest request) {
        String apiKey = request.getHeader("X-Device-Key");
        if (apiKey == null || apiKey.isBlank()) {
            throw AuthException.unauthorized("Device key required");
        }
        boolean bypass = config.localBypass() && env.acceptsProfiles(Profiles.of("local", "test"));
        AdminDevice device;
        if (bypass) {
            device = devices.findByApiKeyHash(crypto.sha256Hex(apiKey))
                .orElseThrow(() -> AuthException.unauthorized("Unknown device"));
        } else {
            String verified = request.getHeader(config.adminMtlsVerifiedHeader());
            if (!("true".equalsIgnoreCase(verified) || "success".equalsIgnoreCase(verified))) {
                throw AuthException.unauthorized("Client certificate not verified");
            }
            String certificatePem = request.getHeader(config.adminMtlsHeader());
            if (certificatePem == null || certificatePem.isBlank()) {
                throw AuthException.unauthorized("Client certificate required");
            }
            X509Certificate certificate = parseCertificate(certificatePem);
            String fingerprint;
            try {
                fingerprint = crypto.sha256Hex(certificate.getEncoded());
            } catch (Exception ex) {
                throw AuthException.unauthorized("Invalid client certificate");
            }
            device = devices.findByCertFingerprintSha256(fingerprint)
                .orElseThrow(() -> AuthException.unauthorized("Unknown device"));
        }
        if (device.getStatus() != AdminDeviceStatus.ACTIVE) {
            throw AuthException.unauthorized("Device revoked");
        }
        if (!crypto.constantTimeEquals(device.getApiKeyHash(), crypto.sha256Hex(apiKey))) {
            throw AuthException.unauthorized("Invalid device key");
        }
        touch(device, request.getHeader("X-App-Version"));
        return device;
    }

    @Transactional(readOnly = true)
    public List<AdminDevice> list() {
        return devices.findAllByOrderByPairedAtDesc();
    }

    @Transactional
    public void revoke(UUID deviceId, UUID actingAdminId) {
        AdminDevice device = devices.findById(deviceId)
            .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Device not found"));
        String previous = device.getStatus().name();
        boolean hadToken = device.getFcmToken() != null;
        if (device.getStatus() != AdminDeviceStatus.REVOKED) {
            device.revoke(Instant.now());
            devices.save(device);
        }
        sessions.revokeDevice(deviceId);
        audit.record(ActorType.ADMIN, actingAdminId, "ADMIN_DEVICE_REVOKED", "ADMIN_DEVICE", deviceId,
            Map.of("status", previous, "fcmRegistered", hadToken), Map.of("status", "REVOKED", "fcmRegistered", false));
    }

    @Transactional
    public void updatePushToken(UUID deviceId, String token, UUID actingAdminId) {
        AdminDevice device = devices.findById(deviceId)
            .orElseThrow(() -> new AuthException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Device not found"));
        if (device.getStatus() != AdminDeviceStatus.ACTIVE) {
            throw AuthException.unauthorized("Device revoked");
        }
        String value = token == null || token.isBlank() ? null : token.trim();
        if (value != null && value.length() > 4096) {
            throw AuthException.badRequest("VALIDATION_ERROR", "Push token is too long");
        }
        Instant now = Instant.now();
        boolean previous = device.getFcmToken() != null;
        device.setPushToken(value, now);
        devices.save(device);
        audit.record(ActorType.ADMIN, actingAdminId, "ADMIN_DEVICE_PUSH_TOKEN_UPDATED", "ADMIN_DEVICE", deviceId,
            Map.of("fcmRegistered", previous), Map.of("fcmRegistered", value != null));
    }

    private void touch(AdminDevice device, String appVersion) {
        Instant now = Instant.now();
        if (device.getLastSeenAt() == null || device.getLastSeenAt().isBefore(now.minusSeconds(60))) {
            device.seen(now, appVersion);
            devices.save(device);
        }
    }

    private static X509Certificate parseCertificate(String headerValue) {
        String value = headerValue.trim();
        if (value.contains("%")) {
            value = URLDecoder.decode(value, StandardCharsets.UTF_8);
        }
        value = value.replace("\\n", "\n");
        try {
            if (!value.contains("BEGIN CERTIFICATE")) {
                value = "-----BEGIN CERTIFICATE-----\n" + value + "\n-----END CERTIFICATE-----";
            }
            String base64 = value
                .replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(base64);
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der));
        } catch (Exception ex) {
            throw AuthException.unauthorized("Invalid client certificate");
        }
    }
}

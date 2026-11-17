package com.bethanysbakery.api.auth.device;

import com.bethanysbakery.api.api.auth.AuthApi;
import com.bethanysbakery.api.auth.AuthException;
import com.bethanysbakery.api.auth.RateLimiter;
import com.bethanysbakery.api.auth.audit.AuditService;
import com.bethanysbakery.api.auth.crypto.CryptoService;
import com.bethanysbakery.api.common.Uuids;
import com.bethanysbakery.api.domain.audit.ActorType;
import com.bethanysbakery.api.domain.auth.AdminDevice;
import com.bethanysbakery.api.domain.auth.AdminDeviceRepository;
import com.bethanysbakery.api.domain.auth.AdminUser;
import com.bethanysbakery.api.domain.auth.AdminUserRepository;
import com.bethanysbakery.api.domain.auth.AdminUserStatus;
import com.bethanysbakery.api.domain.auth.DevicePairing;
import com.bethanysbakery.api.domain.auth.DevicePairingRepository;
import com.bethanysbakery.api.domain.auth.DevicePlatform;
import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DevicePairingService {
    private static final Duration PAIRING_TTL = Duration.ofMinutes(15);
    private static final Duration DEVICE_CERT_TTL = Duration.ofDays(180);

    private final DevicePairingRepository pairings;
    private final AdminDeviceRepository devices;
    private final AdminUserRepository admins;
    private final CertificateAuthority certificateAuthority;
    private final CryptoService crypto;
    private final AuditService audit;
    private final RateLimiter rateLimiter;

    public DevicePairingService(DevicePairingRepository pairings, AdminDeviceRepository devices,
            AdminUserRepository admins, CertificateAuthority certificateAuthority, CryptoService crypto,
            AuditService audit, RateLimiter rateLimiter) {
        this.pairings = pairings;
        this.devices = devices;
        this.admins = admins;
        this.certificateAuthority = certificateAuthority;
        this.crypto = crypto;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
    }

    @Transactional
    public String createPairingCode(String createdBy) {
        Instant now = Instant.now();
        String code = crypto.randomToken(16);
        pairings.save(new DevicePairing(Uuids.v7(), crypto.sha256Hex(code), now.plus(PAIRING_TTL), createdBy, now));
        return code;
    }

    @Transactional
    public AuthApi.PairDeviceResponse pair(AuthApi.PairDeviceRequest request, String ip) {
        RateLimiter.Result limit = rateLimiter.tryAcquire("pair:ip:" + ip, 10, Duration.ofMinutes(15));
        if (!limit.allowed()) {
            throw AuthException.rateLimited(limit.retryAfterSeconds());
        }
        Instant now = Instant.now();
        DevicePairing pairing = pairings.findByCodeHashForUpdate(crypto.sha256Hex(request.pairingCode()))
            .orElseThrow(() -> AuthException.unauthorized("Invalid pairing code"));
        if (pairing.isUsed() || pairing.isExpired(now)) {
            throw AuthException.unauthorized("Invalid pairing code");
        }
        AdminUser admin = admins.findFirstByStatus(AdminUserStatus.ACTIVE)
            .orElseThrow(() -> AuthException.badRequest("NOT_CONFIGURED", "Admin is not bootstrapped"));
        if (!certificateAuthority.publicKeyMatches(request.csrPem(), request.publicKeyPem())) {
            throw AuthException.badRequest("VALIDATION_ERROR", "CSR public key does not match");
        }
        CertificateAuthority.IssuedCertificate issued = certificateAuthority.issue(
            request.csrPem(), request.deviceName(), DEVICE_CERT_TTL
        );
        String apiKey = crypto.randomToken(32);
        X509Certificate certificate = parseCertificate(issued.clientCertificatePem());
        String fingerprint;
        try {
            fingerprint = crypto.sha256Hex(certificate.getEncoded());
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to fingerprint issued certificate", ex);
        }
        AdminDevice device = new AdminDevice(
            Uuids.v7(), admin.getId(), request.deviceName(), DevicePlatform.ANDROID, request.publicKeyPem(),
            fingerprint, crypto.sha256Hex(apiKey), now, request.appVersion()
        );
        devices.save(device);
        pairing.markUsed(now);
        pairings.save(pairing);
        audit.record(ActorType.ADMIN, admin.getId(), "ADMIN_DEVICE_PAIRED", "ADMIN_DEVICE", device.getId(), null,
            Map.of("name", request.deviceName(), "platform", "ANDROID"));
        return new AuthApi.PairDeviceResponse(
            device.getId(), apiKey, issued.clientCertificatePem(), issued.caCertificatePem(), issued.expiresAt()
        );
    }

    private static X509Certificate parseCertificate(String pem) {
        try {
            String base64 = pem
                .replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replaceAll("\\s", "");
            byte[] der = Base64.getDecoder().decode(base64);
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to parse issued certificate", ex);
        }
    }
}
